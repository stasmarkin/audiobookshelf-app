package app.absplus.android.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastAutoDownloadEntry
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.device.AndroidStorageIo
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.NodeKind
import app.absplus.android.models.DownloadItem
import app.absplus.android.plugins.AbsLogger
import java.io.File

/** Current network/power state for the auto download gate. */
object AutoDownloadConditions {
  fun current(context: Context): AutoDownloadPolicy.Conditions {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager?
    return AutoDownloadPolicy.Conditions(connected, unmetered, bm?.isCharging == true)
  }

  fun transfersAllowed(context: Context): Boolean = blockedReason(context) == null

  fun blockedReason(context: Context): String? =
          AutoDownloadPolicy.blockedReason(DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default(), current(context))
}

/**
 * Decides, per queued item, whether the download queue may transfer it now. Auto-owned items follow the auto download
 * network/charging settings in the shared queue too (worker, app start, restore and retry all go through the queue);
 * manual downloads are never gated here.
 */
interface AutoTransferGate {
  fun isAuto(item: DownloadItem): Boolean
  fun allowedNow(): Boolean
  /** Why auto-owned transfers may not run now (see [AutoDownloadPolicy.blockedReason]), or null. */
  fun blockedReason(): String? = if (allowedNow()) null else "network"

  companion object {
    fun forContext(context: Context): AutoTransferGate = object : AutoTransferGate {
      override fun isAuto(item: DownloadItem): Boolean =
              try {
                AutoDownloadStore.isAutoOwned(item.serverConnectionConfigId, item.libraryItemId, item.episodeId)
              } catch (e: Exception) {
                false
              }
      override fun allowedNow(): Boolean = AutoDownloadConditions.transfersAllowed(context)
      override fun blockedReason(): String? = AutoDownloadConditions.blockedReason(context)
    }
  }
}

/** Applies [AutoDownloadPolicy.retentionDeletions] to the app-storage copy (internal or SD card) of one podcast of one server. */
object AutoDownloadRetention {
  private const val TAG = "AutoDownloadRetention"

  /** Replaceable in tests. */
  @Volatile var journal: MoveJournal = PaperMoveJournal
  @Volatile var volumes: (Context) -> app.absplus.android.device.VolumeProbe = { AndroidVolumeProbe(it) }

  /**
   * @param serverProgress server progress keyed by episode id (the check's current user)
   * @param queuedEpisodeIds episodes of this podcast that are in the download queue
   * @return episode ids that were deleted
   */
  fun apply(
          context: Context,
          serverConfigId: String,
          libraryItemId: String,
          entry: PodcastAutoDownloadEntry,
          windowIds: Set<String>,
          serverEpisodeIds: Set<String>,
          serverProgress: Map<String, MediaProgress>,
          queuedEpisodeIds: Set<String>
  ): List<String> {
    // Decided with the entry passed in; every removal is checked again at that moment (see deleteIfStillRemovable)
    if (!AutoDownloadPolicy.cleansUp(entry)) return emptyList()
    val probe = volumes(context)
    val deleted = mutableListOf<String>()
    DeviceManager.dbManager.getLocalLibraryItems()
            .filter {
              it.libraryItemId == libraryItemId && it.serverConnectionConfigId == serverConfigId &&
                      AppStorage.isAppStorage(it.folderId)
            }
            .forEach { snapshot ->
              // A move between storages keeps its own copies until it finishes: retention waits for it
              if (journal.get(snapshot.id) != null) {
                AbsLogger.info(TAG, "Not removing episodes of ${snapshot.id}: a move between storages is not finished")
                return@forEach
              }
              // Only on the registered card that is in the device now (H02); internal storage is always there
              if (!AppStorage.isVerified(snapshot.folderId, probe)) {
                AbsLogger.info(TAG, "Not removing episodes of ${snapshot.id}: its SD card is not available")
                return@forEach
              }
              val downloadsRoot = AppStorage.downloadsRoot(snapshot.folderId, probe)?.canonicalFile ?: return@forEach
              val podcast = snapshot.media as? Podcast ?: return@forEach
              val episodes = podcast.episodes ?: return@forEach
              val view = episodes.mapNotNull { ep ->
                val serverEpisodeId = ep.serverEpisodeId ?: return@mapNotNull null
                val remote = serverProgress[serverEpisodeId]
                val inProgress = startedLocally(snapshot.id, ep.id) || (remote != null && remote.currentTime > 0 && !remote.isFinished)
                val lastPlayed = isLastPlayed(snapshot.id, ep.id, libraryItemId, serverEpisodeId)
                val length = fileOf(snapshot, ep)?.let { if (it.isFile) it.length() else 0L }
                AutoDownloadPolicy.LocalEpisode(serverEpisodeId, inProgress, lastPlayed, serverEpisodeId in queuedEpisodeIds,
                        emptyFile = AutoDownloadPolicy.localCopyOf(length ?: 0L) != AutoDownloadPolicy.LocalCopy.VALID)
              }
              val remove = AutoDownloadPolicy.retentionDeletions(entry, windowIds, serverEpisodeIds, view).toSet()
              if (remove.isEmpty()) return@forEach
              // Deletion and record change happen under the item's lock on a fresh read, so a move, download or
              // manual deletion that saved the item meanwhile is never overwritten by this snapshot
              DeviceManager.dbManager.updateLocalLibraryItem(snapshot.id) { local ->
                if (local.folderId != snapshot.folderId || journal.get(local.id) != null) return@updateLocalLibraryItem ItemUpdate.Keep
                val current = (local.media as? Podcast)?.episodes ?: return@updateLocalLibraryItem ItemUpdate.Keep
                var changed = false
                current.filter { it.serverEpisodeId in remove }.forEach { ep ->
                  val localFileId = ep.audioTrack?.localFileId ?: return@forEach
                  // Records written before R29-N02 can point two episodes at one file: removing it would take the other one too
                  if (current.any { it !== ep && it.audioTrack?.localFileId == localFileId }) {
                    AbsLogger.error(TAG, "Not removing ${ep.serverEpisodeId}: its file is shared with another episode")
                    return@forEach
                  }
                  val localFile = local.localFiles.find { it.id == localFileId }
                  val path = localFile?.absolutePath?.let { File(it) }
                  // Only a regular file inside this storage's own downloads folder; links and anything else stay (M01)
                  if (path == null || AndroidStorageIo.kind(path) == NodeKind.LINK ||
                          !path.canonicalFile.path.startsWith(downloadsRoot.path + File.separator)) {
                    AbsLogger.error(TAG, "Not removing ${ep.serverEpisodeId}: file is outside app storage")
                    return@forEach
                  }
                  val serverEpisodeId = ep.serverEpisodeId!!
                  // Playback may have started since the plan was made; the store lock covers the setting and ownership
                  val removedFile = AutoDownloadStore.deleteIfStillRemovable(serverConfigId, libraryItemId, serverEpisodeId) {
                    when {
                      startedLocally(local.id, ep.id) || isLastPlayed(local.id, ep.id, libraryItemId, serverEpisodeId) -> false
                      !AppStorage.isVerified(local.folderId, probe) -> false
                      else -> when (AndroidStorageIo.kind(path)) {
                        NodeKind.MISSING -> true
                        NodeKind.FILE -> AndroidStorageIo.delete(path).also {
                          if (!it) AbsLogger.error(TAG, "Could not delete the file of $serverEpisodeId")
                        }
                        else -> false
                      }
                    }
                  }
                  if (!removedFile) {
                    AbsLogger.info(TAG, "Kept $serverEpisodeId: no longer removable at deletion time")
                    return@forEach
                  }
                  (local.media as Podcast).removeAudioTrack(localFileId)
                  local.removeLocalFile(localFileId)
                  deleted.add(serverEpisodeId)
                  changed = true
                }
                if (changed) ItemUpdate.Save(local) else ItemUpdate.Keep
              }
            }
    if (deleted.isNotEmpty()) AbsLogger.info(TAG, "Removed ${deleted.size} auto downloaded episode(s) of $libraryItemId outside the latest window")
    return deleted
  }

  private fun fileOf(local: LocalLibraryItem, ep: PodcastEpisode): File? {
    val localFileId = ep.audioTrack?.localFileId ?: return null
    return local.localFiles.find { it.id == localFileId }?.absolutePath?.let { File(it) }
  }

  private fun startedLocally(localItemId: String, localEpisodeId: String): Boolean {
    val progress = DeviceManager.dbManager.getLocalMediaProgress("$localItemId-$localEpisodeId")
    return progress != null && progress.currentTime > 0 && !progress.isFinished
  }

  private fun isLastPlayed(localItemId: String, localEpisodeId: String, libraryItemId: String, serverEpisodeId: String): Boolean {
    val last = DeviceManager.deviceData.lastPlaybackSession ?: return false
    return (last.localLibraryItem?.id == localItemId && last.localEpisodeId == localEpisodeId) ||
            (last.libraryItemId == libraryItemId && last.episodeId == serverEpisodeId)
  }
}
