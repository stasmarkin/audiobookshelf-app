package app.absplus.android.managers

import android.content.Context
import android.util.Log
import app.absplus.android.data.*
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.AppStorage
import app.absplus.android.models.DownloadItem
import app.absplus.android.plugins.AbsLogger
import io.paperdb.Paper

class DbManager {
  val tag = "DbManager"
  private val itemStore = LocalItemStore(PaperLocalItemBook)

  companion object {
    private var isDbInitialized = false

    fun initialize(ctx: Context) {
      if (isDbInitialized) return
      Paper.init(ctx)
      isDbInitialized = true
      Log.i("DbManager", "Initialized Paper db")
      // Every process entry point (Activity, player service, widget, downloads) initializes the db
      // first, so this is also where persistent diagnostics start (after Paper, for log migration)
      app.absplus.android.diagnostics.DiagnosticLog.init(ctx)
    }
  }

  fun getDeviceData(): DeviceData {
    return Paper.book("device").read("data")
            ?: DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
  }
  fun saveDeviceData(deviceData: DeviceData) {
    Paper.book("device").write("data", deviceData)
  }

  fun getLocalLibraryItems(mediaType: String? = null): MutableList<LocalLibraryItem> {
    val localLibraryItems: MutableList<LocalLibraryItem> = mutableListOf()
    Paper.book("localLibraryItems").allKeys.forEach {
      val localLibraryItem: LocalLibraryItem? = Paper.book("localLibraryItems").read(it)
      if (localLibraryItem != null &&
                      (mediaType.isNullOrEmpty() || mediaType == localLibraryItem.mediaType)
      ) {
        localLibraryItems.add(localLibraryItem)
      }
    }
    return localLibraryItems
  }

  fun getLocalLibraryItemsInFolder(folderId: String): List<LocalLibraryItem> {
    val localLibraryItems = getLocalLibraryItems()
    return localLibraryItems.filter { it.folderId == folderId }
  }

  fun getLocalLibraryItemByLId(libraryItemId: String): LocalLibraryItem? {
    return getLocalLibraryItems().find { it.libraryItemId == libraryItemId }
  }

  // Cross-library fallback: the same physical book scanned into two libraries has a different
  // libraryItemId in each, but the server preserves the same filesystem ino for both.
  fun getLocalLibraryItemByIno(ino: String?): LocalLibraryItem? {
    if (ino.isNullOrEmpty()) return null
    return getLocalLibraryItems().find { it.ino == ino }
  }

  fun getLocalLibraryItemByLIdOrIno(libraryItemId: String, ino: String?): LocalLibraryItem? {
    return getLocalLibraryItemByLId(libraryItemId) ?: getLocalLibraryItemByIno(ino)
  }

  fun getLocalLibraryItem(localLibraryItemId: String): LocalLibraryItem? {
    return Paper.book("localLibraryItems").read(localLibraryItemId)
  }

  fun getLocalLibraryItemWithEpisode(podcastEpisodeId: String): LibraryItemWithEpisode? {
    var podcastEpisode: PodcastEpisode? = null
    val localLibraryItem =
            getLocalLibraryItems("podcast").find { localLibraryItem ->
              val podcast = localLibraryItem.media as Podcast
              podcastEpisode = podcast.episodes?.find { it.id == podcastEpisodeId }
              podcastEpisode != null
            }
    return if (localLibraryItem != null) {
      LibraryItemWithEpisode(localLibraryItem, podcastEpisode!!)
    } else {
      null
    }
  }

  fun removeLocalLibraryItem(localLibraryItemId: String) {
    itemStore.remove(localLibraryItemId)
  }

  fun saveLocalLibraryItems(localLibraryItems: List<LocalLibraryItem>) {
    localLibraryItems.forEach { itemStore.save(it) }
  }

  fun saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) {
    itemStore.save(localLibraryItem)
  }

  fun updateLocalLibraryItem(id: String, transform: (LocalLibraryItem) -> ItemUpdate): ItemUpdate? =
          itemStore.update(id, transform)

  fun saveLocalFolder(localFolder: LocalFolder) {
    Paper.book("localFolders").write(localFolder.id, localFolder)
  }

  fun getLocalFolder(folderId: String): LocalFolder? {
    return Paper.book("localFolders").read(folderId)
  }

  fun getAllLocalFolders(): List<LocalFolder> {
    val localFolders: MutableList<LocalFolder> = mutableListOf()
    Paper.book("localFolders").allKeys.forEach { localFolderId ->
      Paper.book("localFolders").read<LocalFolder>(localFolderId)?.let { localFolders.add(it) }
    }
    return localFolders
  }

  fun removeLocalFolder(folderId: String) {
    val localLibraryItems = getLocalLibraryItemsInFolder(folderId)
    localLibraryItems.forEach { removeLocalLibraryItem(it.id) }
    Paper.book("localFolders").delete(folderId)
  }

  fun saveDownloadItem(downloadItem: DownloadItem) {
    Paper.book("downloadItems").write(downloadItem.id, downloadItem)
  }

  fun removeDownloadItem(downloadItemId: String) {
    Paper.book("downloadItems").delete(downloadItemId)
  }

  fun getDownloadItems(): List<DownloadItem> {
    val downloadItems: MutableList<DownloadItem> = mutableListOf()
    val book = Paper.book("downloadItems")
    book.allKeys.forEach { downloadItemId ->
      book.read<DownloadItem>(downloadItemId)?.let { stored ->
        val compact = stored.withoutPodcastEpisodes()
        // Rewrite entries saved by older versions so they shrink on disk too
        if (compact !== stored) book.write(downloadItemId, compact)
        downloadItems.add(compact)
      }
    }
    return downloadItems
  }

  /**
   * Validate persisted download queue entries before restoring them.
   *
   * Queue entries can become incompatible or malformed, for example after an app update. If any
   * entry cannot be read or fails the checks required for queue restoration, the entire persisted
   * queue is cleared so startup can continue without restoring invalid data.
   *
   * @return true if the queue passes validation or was successfully cleared; false if an invalid
   * queue could not be cleared
   */
  fun ensureValidDownloadQueue(): Boolean {
    val downloadBook = Paper.book("downloadItems")
    val queueIsValid =
            try {
              // One entry at a time: entries saved before compaction can each hold a whole podcast
              downloadBook.allKeys
                      .asSequence()
                      .map { downloadItemId ->
                        val item =
                                downloadBook.read<DownloadItem>(downloadItemId)
                                        ?: throw IllegalStateException(
                                                "Download queue entry $downloadItemId could not be read"
                                        )
                        if (item.id != downloadItemId) {
                          throw IllegalStateException(
                                  "Download queue entry $downloadItemId has a mismatched item ID"
                          )
                        }
                        item
                      }
                      .all { item ->
                        listOf<String?>(item.id, item.libraryItemId, item.localFolder.id).all {
                          !it.isNullOrBlank()
                        } &&
                                item.downloadItemParts.isNotEmpty() &&
                                item.downloadItemParts.all { part ->
                                  listOf<String?>(
                                                  part.id,
                                                  part.downloadItemId,
                                                  part.filename,
                                                  part.destinationPath,
                                                  part.finalDestinationPath,
                                                  part.serverPath,
                                                  part.localFolderId
                                          )
                                          .all { !it.isNullOrBlank() } &&
                                          part.downloadItemId == item.id
                                }
                      }
            } catch (readError: Exception) {
              AbsLogger.error(
                      tag,
                      "Could not read persisted download queue; clearing queue (${readError.message})"
              )
              false
            }

    if (queueIsValid) return true

    AbsLogger.error(tag, "Persisted download queue contains invalid data; clearing queue")
    return try {
      downloadBook.destroy()
      val remainingKeys = downloadBook.allKeys
      if (remainingKeys.isEmpty()) {
        AbsLogger.info(tag, "Cleared invalid persisted download queue")
        true
      } else {
        AbsLogger.error(
                tag,
                "Invalid persisted download queue still contains ${remainingKeys.size} entries after clearing"
        )
        false
      }
    } catch (clearError: Exception) {
      AbsLogger.error(
              tag,
              "Could not clear invalid persisted download queue (${clearError.message})"
      )
      false
    }
  }

  fun saveLocalMediaProgress(mediaProgress: LocalMediaProgress) {
    Paper.book("localMediaProgress").write(mediaProgress.id, mediaProgress)
  }
  // Background writers use these: under the item's lock a write cannot slip between auto delete's last check and its delete.
  // The player's own writes (MediaProgressSyncer) keep the plain call: they run on the main thread, which must not wait on a
  // delete, and the book being played is never auto deleted.
  fun saveLocalMediaProgressLocked(mediaProgress: LocalMediaProgress) {
    LocalItemLocks.withItem(mediaProgress.localLibraryItemId) { saveLocalMediaProgress(mediaProgress) }
  }
  fun removeLocalMediaProgressLocked(localMediaProgressId: String) {
    val itemId = getLocalMediaProgress(localMediaProgressId)?.localLibraryItemId
    if (itemId == null) removeLocalMediaProgress(localMediaProgressId)
    else LocalItemLocks.withItem(itemId) { removeLocalMediaProgress(localMediaProgressId) }
  }

  // For books this will just be the localLibraryItemId for podcast episodes this will be
  // "{localLibraryItemId}-{episodeId}"
  fun getLocalMediaProgress(localMediaProgressId: String): LocalMediaProgress? {
    return Paper.book("localMediaProgress").read(localMediaProgressId)
  }
  fun getAllLocalMediaProgress(): List<LocalMediaProgress> {
    val mediaProgress: MutableList<LocalMediaProgress> = mutableListOf()
    Paper.book("localMediaProgress").allKeys.forEach { localMediaProgressId ->
      Paper.book("localMediaProgress").read<LocalMediaProgress>(localMediaProgressId)?.let {
        mediaProgress.add(it)
      }
    }
    return mediaProgress
  }
  fun removeLocalMediaProgress(localMediaProgressId: String) {
    Paper.book("localMediaProgress").delete(localMediaProgressId)
  }

  /**
   * Removes the local progress records of a local item the user deleted (or of one of its episodes). They are this
   * device's bookkeeping for local media: listening on a server item is kept on the server and in the saved playback
   * sessions, which are synced separately and not touched here.
   */
  fun removeLocalMediaProgressFor(localLibraryItemId: String, localEpisodeId: String? = null) {
    getAllLocalMediaProgress()
            .filter { it.localLibraryItemId == localLibraryItemId && (localEpisodeId == null || it.localEpisodeId == localEpisodeId) }
            .forEach { removeLocalMediaProgress(it.id) }
  }

  fun removeAllLocalMediaProgress() {
    Paper.book("localMediaProgress").destroy()
  }

  // Make sure all local file ids still exist
  fun cleanLocalLibraryItems(context: Context) {
    Paper.book("localLibraryItems").allKeys.forEach { id ->
      updateLocalLibraryItem(id) { lli ->
        // Never on removable storage (H02): a file that looks missing there may be on a card that is out, is being
        // removed while this runs, or is a different card; the records, episodes and their progress are kept. Only an
        // explicit user deletion removes them.
        LocalItemCleanup.clean(lli, { it.exists(context) }, skip = lli.folderId.startsWith(AppStorage.APPSD_PREFIX))
      }
    }
  }

  // Remove any local media progress where the local media item is not found
  fun cleanLocalMediaProgress() {
    val localMediaProgress = getAllLocalMediaProgress()
    val localLibraryItems = getLocalLibraryItems()
    localMediaProgress.forEach {
      val matchingLLI = localLibraryItems.find { lli -> lli.id == it.localLibraryItemId }
      if (!it.id.startsWith("local")) {
        // A bug on the server when syncing local media progress was replacing the media progress id
        // causing duplicate progress. Remove them.
        Log.d(
                tag,
                "cleanLocalMediaProgress: Invalid local media progress does not start with 'local' (fixed on server 2.0.24)"
        )
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI == null) {
        Log.d(
                tag,
                "cleanLocalMediaProgress: No matching local library item for local media progress ${it.id} - removing"
        )
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI.isPodcast) {
        if (it.localEpisodeId.isNullOrEmpty()) {
          Log.d(tag, "cleanLocalMediaProgress: Podcast media progress has no episode id - removing")
          Paper.book("localMediaProgress").delete(it.id)
        } else {
          val podcast = matchingLLI.media as Podcast
          val matchingLEp = podcast.episodes?.find { ep -> ep.id == it.localEpisodeId }
          if (matchingLEp == null) {
            Log.d(
                    tag,
                    "cleanLocalMediaProgress: Podcast media progress for episode ${it.localEpisodeId} not found - removing"
            )
            Paper.book("localMediaProgress").delete(it.id)
          }
        }
      }
    }
  }

  fun saveMediaItemHistory(mediaItemHistory: MediaItemHistory) {
    Paper.book("mediaItemHistory").write(mediaItemHistory.id, mediaItemHistory)
  }
  fun getMediaItemHistory(id: String): MediaItemHistory? {
    return Paper.book("mediaItemHistory").read(id)
  }

  fun savePlaybackSession(playbackSession: PlaybackSession) {
    Paper.book("playbackSession").write(playbackSession.id, playbackSession)
  }
  fun removePlaybackSession(playbackSessionId: String) {
    Paper.book("playbackSession").delete(playbackSessionId)
  }
  fun getPlaybackSessions(): List<PlaybackSession> {
    val sessions: MutableList<PlaybackSession> = mutableListOf()
    Paper.book("playbackSession").allKeys.forEach { playbackSessionId ->
      Paper.book("playbackSession").read<PlaybackSession>(playbackSessionId)?.let {
        sessions.add(it)
      }
    }
    return sessions
  }

  fun getPodcastAutoDownloadEntry(key: String): PodcastAutoDownloadEntry? {
    return Paper.book("autoDownload").read(key)
  }
  fun savePodcastAutoDownloadEntry(key: String, entry: PodcastAutoDownloadEntry) {
    Paper.book("autoDownload").write(key, entry)
  }
  fun getPodcastAutoDownloadKeys(): List<String> {
    return Paper.book("autoDownload").allKeys
  }

  // What an online auto delete pass saw on the server about a device progress; records of the older Long format read as none
  fun getAutoDeleteConfirmed(progressId: String): AutoDeletePolicy.Confirmation? {
    val stored: Any? = Paper.book("autoDeleteConfirmed").read(progressId)
    return stored as? AutoDeletePolicy.Confirmation
  }
  fun saveAutoDeleteConfirmed(progressId: String, confirmation: AutoDeletePolicy.Confirmation) {
    Paper.book("autoDeleteConfirmed").write(progressId, confirmation)
  }
  fun removeAutoDeleteConfirmed(progressId: String) {
    Paper.book("autoDeleteConfirmed").delete(progressId)
  }
  fun getAutoDeleteConfirmedKeys(): List<String> {
    return Paper.book("autoDeleteConfirmed").allKeys
  }

  // When a download finished (progress id -> time), so progress copied from the server with it is not taken for listening
  fun getAutoDeleteDownloadedAt(progressId: String): Long? {
    val stored: Any? = Paper.book("autoDeleteDownloadedAt").read(progressId)
    return stored as? Long
  }
  fun saveAutoDeleteDownloadedAt(progressId: String, time: Long) {
    Paper.book("autoDeleteDownloadedAt").write(progressId, time)
  }
  fun removeAutoDeleteDownloadedAt(progressId: String) {
    Paper.book("autoDeleteDownloadedAt").delete(progressId)
  }
  fun getAutoDeleteDownloadedAtKeys(): List<String> {
    return Paper.book("autoDeleteDownloadedAt").allKeys
  }

  // App logs now live in the rolling diagnostic log (DiagnosticLog), which migrates the old "log" book once
}
