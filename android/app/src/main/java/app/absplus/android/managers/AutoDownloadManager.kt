package app.absplus.android.managers

import android.content.Context
import android.util.Log
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastAutoDownloadEntry
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DeviceManager
import app.absplus.android.models.User
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
import app.absplus.android.services.DownloadServiceHost
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the newest unfinished episodes of opted-in podcasts downloaded. */
object AutoDownloadManager {
  enum class Status(val jsValue: String) {
    STARTED("started"),
    // Only the cleanup ran; auto download did not, so the app throttles this separately and does not hold back the next download attempt
    CLEANED("cleaned"),
    DISABLED("disabled"),
    CONSTRAINTS("constraints"),
    BUSY("busy"),
    NO_SERVER("no-server")
  }

  /**
   * APP: app open/foreground; WORKER: periodic background work; SETTINGS: right after a podcast's auto download settings
   * were saved. A SETTINGS check only queues new episodes; retention waits for the next APP or WORKER check, so changing
   * a setting never removes downloads by itself. CLEANUP: the periodic auto delete work, which only cleans up (the
   * auto download work does not clean up and keeps its own network and charging limits).
   */
  enum class Trigger { APP, WORKER, SETTINGS, CLEANUP }

  private const val TAG = "AutoDownloadManager"
  private const val PAGE_LIMIT = 100
  private const val MAX_PAGES = 1000
  private const val DEFAULT_TIMEOUT_MS = 120_000L
  // WorkManager stops a worker after 10 minutes; unfinished transfers resume on the next run or app open
  private const val WORKER_TRANSFER_BUDGET_MS = 8 * 60_000L

  private val mutex = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private class StartResult(val error: String?)

  private class RetryCheck(val refusal: DownloadServiceHost.ExistingDownloadResult?)

  // What a started pass does: the cleanup does not depend on auto download or its network/charging limits
  private class Plan(val status: Status, val settings: DeviceSettings? = null, val cleanup: Boolean = false, val download: Boolean = false) {
    // On a started plan the mutex is held and the caller must unlock it
    val runs get() = status == Status.STARTED || status == Status.CLEANED
  }

  /** Worker entry point: runs preflight and, if STARTED, the whole check before returning. */
  suspend fun check(context: Context, trigger: Trigger): Status {
    val appContext = context.applicationContext
    val plan = preflight(appContext, trigger)
    if (!plan.runs || plan.settings == null) return plan.status
    try {
      runCheck(appContext, trigger, plan)
    } finally {
      mutex.unlock()
    }
    if (trigger == Trigger.WORKER && plan.download) DownloadServiceHost.runQueueWhileWorking(appContext, WORKER_TRANSFER_BUDGET_MS)
    return plan.status
  }

  /** Plugin entry point: runs preflight; on STARTED the check continues in the background. Blocking (Paper I/O) — call off the main thread. */
  fun startCheck(context: Context, trigger: Trigger, skipCleanup: Boolean = false): Status {
    val appContext = context.applicationContext
    val plan = preflight(appContext, trigger, skipCleanup)
    if (!plan.runs || plan.settings == null) return plan.status
    scope.launch {
      try {
        runCheck(appContext, trigger, plan)
      } finally {
        mutex.unlock()
      }
    }
    return plan.status
  }

  // On STARTED the mutex is held and the caller must unlock it
  private fun preflight(appContext: Context, trigger: Trigger, skipCleanup: Boolean = false): Plan {
    DbManager.initialize(appContext)

    if (DeviceManager.serverConnectionConfig == null && (trigger == Trigger.WORKER || trigger == Trigger.CLEANUP)) {
      DeviceManager.serverConnectionConfig = DeviceManager.deviceData.getLastServerConnectionConfig()
    }
    if (DeviceManager.serverConnectionConfig == null) return Plan(Status.NO_SERVER)

    val settings = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()
    val cleanup = AutoDeleteManager.isCleanupEnabled(settings) && !skipCleanup && (trigger == Trigger.APP || trigger == Trigger.CLEANUP)
    val autoDownload = settings.autoDownloadEnabled && trigger != Trigger.CLEANUP
    if (!autoDownload && !cleanup) return Plan(Status.DISABLED)

    val download = autoDownload && (trigger == Trigger.WORKER || constraintsMet(appContext, settings))
    if (!download && !cleanup) return Plan(Status.CONSTRAINTS)

    if (!mutex.tryLock()) return Plan(Status.BUSY)
    return Plan(if (download) Status.STARTED else Status.CLEANED, settings, cleanup, download)
  }

  // Same rule as the download queue's gate for auto-owned transfers (offline, metered, charger)
  private fun constraintsMet(appContext: Context, settings: DeviceSettings): Boolean =
          AutoDownloadPolicy.transfersAllowed(settings, AutoDownloadConditions.current(appContext))

  private enum class Recheck { OK, SKIP_PODCAST, ABORT_CHECK }

  // Settings and server can change while the check awaits network or queue responses
  private fun recheck(serverConfigId: String, libraryItemId: String): Recheck {
    if (DeviceManager.serverConnectionConfig?.id != serverConfigId) return Recheck.ABORT_CHECK
    val current = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()
    if (!current.autoDownloadEnabled) return Recheck.ABORT_CHECK
    val entry = AutoDownloadStore.get(serverConfigId, libraryItemId)
    return if (AutoDownloadPolicy.isEnabledFor(entry, current)) Recheck.OK else Recheck.SKIP_PODCAST
  }

  private suspend fun <T> await(timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: ((T) -> Unit) -> Unit): T? {
    return withTimeoutOrNull(timeoutMs) {
      suspendCancellableCoroutine<T> { cont ->
        block { result -> if (cont.isActive) cont.resume(result) }
      }
    }
  }

  /** The best local copy of an episode across the given local items (any server: storage is per library item). */
  private fun localCopy(locals: List<LocalLibraryItem>, libraryItemId: String, episodeId: String): AutoDownloadPolicy.LocalCopy {
    var result = AutoDownloadPolicy.LocalCopy.NONE
    locals.filter { it.libraryItemId == libraryItemId }.forEach { local ->
      (local.media as? Podcast)?.episodes?.filter { it.serverEpisodeId == episodeId }?.forEach { ep ->
        val path = ep.audioTrack?.localFileId?.let { id -> local.localFiles.find { it.id == id } }?.absolutePath
        // A content URI (device folder) has no file length here; such a copy counts as present
        val length = when {
          path == null -> 0L
          path.startsWith("content://") -> 1L
          else -> File(path).let { if (it.isFile) it.length() else 0L }
        }
        if (AutoDownloadPolicy.localCopyOf(length) == AutoDownloadPolicy.LocalCopy.VALID) return AutoDownloadPolicy.LocalCopy.VALID
        result = AutoDownloadPolicy.LocalCopy.EMPTY
      }
    }
    return result
  }

  private suspend fun runCheck(appContext: Context, trigger: Trigger, plan: Plan) {
    try {
      val serverConfigId = DeviceManager.serverConnectionConfig?.id ?: return
      val api = ApiHandler(appContext)
      val user = await<User?> { api.getCurrentUser(it) }
      if (plan.cleanup) {
        // Server progress is trusted for auto delete only while the connected account is the one it was loaded for
        val ownerProgress = user?.takeIf { DeviceManager.serverConnectionConfig?.id == serverConfigId && AutoDeleteManager.isUserCurrent(it) }
        if (user != null && ownerProgress == null) AbsLogger.info(TAG, "Auto delete: the account changed while loading the user, server progress is not used")
        try {
          AutoDeleteManager.runCleanup(appContext, ownerProgress)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          AbsLogger.error(TAG, "Auto delete failed unexpectedly (${e.javaClass.simpleName}: ${e.message})")
        }
      }
      if (!plan.download || plan.settings == null) return
      if (user == null) {
        AbsLogger.error(TAG, "Could not load the current user, ending check")
        return
      }
      doCheck(appContext, trigger, serverConfigId, plan.settings, api, user)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      AbsLogger.error(TAG, "Auto download check failed unexpectedly (${e.javaClass.simpleName}: ${e.message})")
    }
  }

  private suspend fun doCheck(
          appContext: Context,
          trigger: Trigger,
          serverConfigId: String,
          settings: DeviceSettings,
          api: ApiHandler,
          user: User
  ) {
    val finished = HashSet<String>()
    val progressByKey = HashMap<String, MediaProgress>()
    user.mediaProgress.forEach { progress ->
      val episodeId = progress.episodeId ?: return@forEach
      val key = "${progress.libraryItemId}/$episodeId"
      progressByKey[key] = progress
      if (progress.isFinished) finished.add(key)
    }

    val entries = AutoDownloadStore.getEntriesForServer(serverConfigId)
    val targets = LinkedHashSet<String>()
    entries.forEach { (id, entry) -> if (entry.enabled == PodcastAutoDownloadEntry.ENABLED_ON) targets.add(id) }
    if (settings.autoDownloadAllPodcasts) {
      val libraries = await<List<app.absplus.android.data.Library>> { api.getLibraries(it) }
      if (libraries == null) {
        AbsLogger.error(TAG, "Could not load libraries, ending check")
        return
      }
      libraries.filter { it.mediaType == "podcast" }.forEach { library ->
        var page = 0
        while (page < MAX_PAGES) {
          val result = await<app.absplus.android.data.LibraryItemsPage?> { api.getLibraryItemsPage(library.id, page, PAGE_LIMIT, it) }
          if (result == null) {
            AbsLogger.error(TAG, "Could not load page $page of library ${library.id}, skipping the rest of it")
            break
          }
          result.results.forEach { targets.add(it.id) }
          if (result.isLastPage) break
          page++
        }
      }
      entries.forEach { (id, entry) -> if (entry.enabled == PodcastAutoDownloadEntry.ENABLED_OFF) targets.remove(id) }
    }

    AbsLogger.info(TAG, "Auto download check started (${trigger.name}), ${targets.size} podcasts")

    val localItems = DeviceManager.dbManager.getLocalLibraryItems()
    var queued = 0
    var retried = 0
    var skipped = 0
    var failedPodcasts = 0
    var removed = 0
    var skippedForLimit = 0
    fun limitOn() = AutoDeletePolicy.limitBytes(DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()) != null

    // Makes room for the download under the limit; false (and the reason logged) when it does not fit
    fun fitsLimit(downloadId: String, expected: Long): Boolean {
      val limit = AutoDeletePolicy.limitBytes(DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()) ?: return true
      // Counted afresh each time: retention, finished or failed downloads and manual deletes change it while the pass runs
      val used = AutoDeleteManager.usedBytes(appContext) + AutoDeleteManager.queuedBytes(appContext)
      if (used + expected <= limit) return true
      val freed = AutoDeleteManager.freeSpaceFor(appContext, expected, user)
      if (freed.enough) return true
      val remaining = used - freed.freedBytes
      // Part of the pass's skipped count; Settings shows the limit-specific share
      skipped++
      skippedForLimit++
      AbsLogger.info(TAG, "Auto download skipped: $downloadId does not fit the limit (needs $expected bytes, room ${maxOf(0L, limit - remaining)} bytes)")
      return false
    }

    fun expectedSize(downloadId: String, episode: app.absplus.android.data.PodcastEpisode): Long {
      if (!limitOn()) return 0L
      val size = episode.audioFile?.metadata?.size
      if (size == null) AbsLogger.info(TAG, "No size known for $downloadId, counting it as 0 for the downloads limit")
      return size ?: 0L
    }

    for (id in targets) {
      if (DeviceManager.serverConnectionConfig?.id != serverConfigId) {
        AbsLogger.info(TAG, "Server connection changed, aborting auto download check")
        return
      }
      // Settings may change while a long check runs
      val current = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()
      if (!current.autoDownloadEnabled) {
        AbsLogger.info(TAG, "Auto download was disabled, aborting check")
        return
      }
      val entry = AutoDownloadStore.get(serverConfigId, id)
      if (!AutoDownloadPolicy.isEnabledFor(entry, current)) continue
      val keepCount = AutoDownloadPolicy.keepCountFor(entry, current)
      if (keepCount <= 0) continue

      // Stored and listed ids reach URLs, queue lookups and folder paths below
      if (!DownloadIdentifiers.isSafe(id)) {
        AbsLogger.error(TAG, "Invalid podcast id, skipping")
        failedPodcasts++
        continue
      }
      val item = await<app.absplus.android.data.LibraryItem?> { api.getLibraryItemWithProgress(id, null, it) }
      if (item == null) {
        AbsLogger.error(TAG, "Could not load podcast $id, skipping")
        failedPodcasts++
        continue
      }
      if (!DownloadIdentifiers.matchesRequest(id, item.id)) {
        AbsLogger.error(TAG, "Server returned an invalid id for podcast $id, skipping")
        failedPodcasts++
        continue
      }
      if (DeviceManager.serverConnectionConfig?.id != serverConfigId) {
        AbsLogger.info(TAG, "Server connection changed, aborting auto download check")
        return
      }
      val podcast = item.media as? Podcast ?: continue

      val window = AutoDownloadPolicy.newestWindow(podcast.episodes ?: mutableListOf(), keepCount, { it.publishedAt }) {
        it.audioFile != null && "$id/${it.id}" !in finished
      }

      // Local copies are stored per library item (one folder, one local item), whichever saved server downloaded
      // them, so any copy counts as on the device; retention, by contrast, only ever touches this server's own copy
      val podcastLocals = localItems.filter { it.libraryItemId == id }
      // Auto download writes to app storage (internal or the SD card setting); a podcast kept in a device folder is left to the user
      if (podcastLocals.any { !AppStorage.isAppStorage(it.folderId) }) {
        AbsLogger.info(TAG, "Podcast $id is downloaded to a device folder; auto download skips it")
        continue
      }
      var podcastFailed = false

      for (episode in window) {
        if (!DownloadIdentifiers.isSafe(episode.id)) {
          AbsLogger.error(TAG, "Invalid episode id in podcast $id, skipping")
          podcastFailed = true
          continue
        }
        val downloadId = "$id-${episode.id}"
        val state = await<DownloadItemManager.QueuedState> { DownloadServiceHost.queuedState(appContext, downloadId, it) }
        if (state == null) {
          AbsLogger.error(TAG, "Timed out reading queue state for $downloadId")
          podcastFailed = true
          continue
        }
        when (recheck(serverConfigId, id)) {
          Recheck.ABORT_CHECK -> {
            AbsLogger.info(TAG, "Server changed or auto download disabled, aborting check")
            return
          }
          Recheck.SKIP_PODCAST -> break
          Recheck.OK -> Unit
        }
        // The local snapshot is taken before network calls; a manual download may have finished since
        val copy = localCopy(podcastLocals, id, episode.id).takeIf { it == AutoDownloadPolicy.LocalCopy.VALID }
                ?: localCopy(DeviceManager.dbManager.getLocalLibraryItems(), id, episode.id)
        if (copy == AutoDownloadPolicy.LocalCopy.EMPTY) {
          AbsLogger.error(TAG, "Episode ${episode.id} of $id has an empty file on the device; not counted as downloaded and left for the user to delete")
        }
        // Fresh entry: a manual request may have claimed the episode while this check was waiting
        when (AutoDownloadPolicy.windowAction(AutoDownloadStore.get(serverConfigId, id), episode.id, state, copy)) {
          AutoDownloadPolicy.WindowAction.SKIP -> Unit
          AutoDownloadPolicy.WindowAction.RETRY -> {
            // With a limit, room may be freed first, so a retry that cannot start must not cost finished downloads
            var expected = 0L
            if (limitOn()) {
              val check = await<RetryCheck> { cb -> DownloadServiceHost.retryRefusal(appContext, downloadId) { cb(RetryCheck(it)) } }
              if (check == null || check.refusal != null) {
                Log.d(TAG, "Retry of $downloadId not applied: ${check?.refusal ?: "timed out"}")
                continue
              }
              val queuedItem = DownloadServiceHost.queueSnapshot().firstOrNull { it.id == downloadId }
              expected = if (queuedItem == null) 0L else AutoDeleteManager.retryExtraBytes(queuedItem)
              if (!fitsLimit(downloadId, expected)) continue
            }
            val result = await<DownloadServiceHost.ExistingDownloadResult> { DownloadServiceHost.retryExisting(appContext, downloadId, it) }
            if (result == DownloadServiceHost.ExistingDownloadResult.RETRIED) {
              retried++
            } else {
              Log.d(TAG, "Retry of $downloadId not applied: $result")
            }
          }
          AutoDownloadPolicy.WindowAction.ENQUEUE -> {
            // With a limit, the destination is checked before room is freed: a refused start would cost finished books for nothing
            if (limitOn()) {
              val refusal = LibraryItemDownloadStarter.destinationRefusal(appContext, item, episode)
              if (refusal != null) {
                skipped++
                AbsLogger.info(TAG, "Skipped $downloadId: $refusal")
                continue
              }
            }
            // Before ownership is recorded: an episode skipped for the limit must stay a plain new episode
            val expected = expectedSize(downloadId, episode)
            if (!fitsLimit(downloadId, expected)) continue
            // Ownership is recorded before the item exists, so the queue never sees it as a manual download
            if (!AutoDownloadStore.markAutoQueued(serverConfigId, id, episode.id)) continue
            item.userMediaProgress = progressByKey["$id/${episode.id}"]
            val result = await<StartResult> { cb ->
              LibraryItemDownloadStarter.startPodcastEpisodeDownload(appContext, item, episode) { cb(StartResult(it)) }
            }
            when {
              result == null -> {
                AbsLogger.error(TAG, "Timed out starting download $downloadId")
                AutoDownloadStore.recordAutoFailure(serverConfigId, id, episode.id)
                podcastFailed = true
              }
              result.error == null -> queued++
              // The item is already persisted in the queue and resumes when the app opens
              result.error == DownloadServiceHost.SERVICE_START_FAILED_MESSAGE -> queued++
              // Its SD card is away: not a failed attempt, the episode is tried again once the card is back
              result.error == LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE -> {
                skipped++
                AbsLogger.info(TAG, "Skipped $downloadId: ${result.error}")
              }
              else -> {
                AbsLogger.error(TAG, "Could not start download $downloadId: ${result.error}")
                AutoDownloadStore.recordAutoFailure(serverConfigId, id, episode.id)
                podcastFailed = true
              }
            }
          }
        }
      }
      if (podcastFailed) failedPodcasts++

      // Retention: only with this podcast's current server metadata and progress in hand (both loaded above)
      if (recheck(serverConfigId, id) != Recheck.OK) continue
      val atStart = entries[id] ?: AutoDownloadStore.get(serverConfigId, id)
      if (!AutoDownloadPolicy.retentionRuns(trigger, atStart, AutoDownloadStore.get(serverConfigId, id))) continue
      val serverEpisodeIds = (podcast.episodes ?: mutableListOf()).map { it.id }.toSet()
      val queuedIds = (await<List<app.absplus.android.models.DownloadItem>> { DownloadServiceHost.snapshot(appContext, it) } ?: continue)
        .filter { it.libraryItemId == id && it.serverConnectionConfigId == serverConfigId }
        .mapNotNull { it.episodeId }.toSet()
      val owned = AutoDownloadStore.get(serverConfigId, id)
      val progressById = progressByKey.filterKeys { it.startsWith("$id/") }.mapKeys { it.key.removePrefix("$id/") }
      removed += AutoDownloadRetention.apply(appContext, serverConfigId, id, owned, window.map { it.id }.toSet(), serverEpisodeIds, progressById, queuedIds).size
      AutoDownloadStore.forgetEpisodes(serverConfigId, id, owned.autoDownloadedEpisodeIds.filter { it !in serverEpisodeIds })
    }

    AutoDeleteManager.setSkippedForLimit(appContext, skippedForLimit)
    AbsLogger.info(TAG, "Auto download check finished: queued $queued, retried $retried, skipped $skipped (for the limit $skippedForLimit), removed $removed, failed podcasts $failedPodcasts")
  }
}
