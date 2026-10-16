package app.absplus.android.managers

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.file.fullName
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderAccess
import app.absplus.android.device.FolderScanner
import app.absplus.android.device.VolumeProbe
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
import app.absplus.android.server.RefreshResult
import app.absplus.android.services.StorageMoveService
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Manages the process-owned queue for app-managed downloads. */
class DownloadItemManager(
        private val folderScanner: FolderScanner,
        private val context: Context,
        private var clientEventEmitter: DownloadEventEmitter,
        private val volumes: VolumeProbe = AndroidVolumeProbe(context)
) {
  private val tag = "DownloadItemManager"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activeCalls = ConcurrentHashMap<String, InternalDownloadManager.DownloadHandle>()
  private val safFolderLocks = ConcurrentHashMap<String, Any>()
  private val scanLocks = ConcurrentHashMap<String, Any>()
  private val reservations = mutableMapOf<String, Long>()
  private val lastPersistTime = mutableMapOf<String, Long>()
  private val finalizingItems = mutableSetOf<String>()
  private val awaitingStorage = mutableSetOf<String>()
  // M03: set when a worker's execution window ended with no foreground service (see setAutoTransfersHeld)
  @Volatile private var autoTransfersHeld = false
  private val refreshingServerIds = mutableSetOf<String>()
  // Lazy: only token refresh needs it, and building it opens the Android keystore
  private val apiHandler by lazy { ApiHandler(context) }
  private var watcherRunning = false
  private val jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  var downloadItemQueue: MutableList<DownloadItem> = mutableListOf()
    private set
  var currentDownloadItemParts: MutableList<DownloadItemPart> = mutableListOf()
    private set

  enum class QueuedState { NOT_QUEUED, PENDING, FAILED, STORAGE_UNAVAILABLE }

  /** Auto-owned transfers follow the auto download network/charging settings (tests replace it). */
  var autoGate: AutoTransferGate = AutoTransferGate.forContext(context)

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem: DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart)
    fun onDownloadItemComplete(jsobj: JSObject)
    fun onQueueChanged(hasWork: Boolean)
  }

  interface InternalProgressCallback {
    fun onSizeResolved(totalBytes: Long)
    fun onProgress(totalBytesWritten: Long, progress: Long)
    fun onComplete(failed: Boolean)
    fun onAuthError()
    /** What identifies the file a new staged copy comes from (ETag or Last-Modified), for resuming it later. */
    fun onValidator(validator: String?) {}
  }

  @Synchronized
  fun setEventEmitter(eventEmitter: DownloadEventEmitter) {
    clientEventEmitter = eventEmitter
    downloadItemQueue.forEach(clientEventEmitter::onDownloadItem)
    notifyQueueChanged()
  }

  @Synchronized
  fun queuedState(downloadItemId: String): QueuedState {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return QueuedState.NOT_QUEUED
    return when {
      item.terminalFailureAt == null -> QueuedState.PENDING
      item.downloadItemParts.any { it.storageUnavailable } -> QueuedState.STORAGE_UNAVAILABLE
      else -> QueuedState.FAILED
    }
  }

  // Ownership stays with the episode on failure; only the failed attempt is counted, so the next check retries an
  // auto-owned episode a bounded number of times and never takes over a manual download
  private fun recordAutoOutcome(item: DownloadItem, failed: Boolean) {
    val episodeId = item.episodeId
    if (episodeId.isNullOrBlank() || item.serverConnectionConfigId.isBlank()) return
    try {
      if (failed) AutoDownloadStore.recordAutoFailure(item.serverConnectionConfigId, item.libraryItemId, episodeId)
      else AutoDownloadStore.recordAutoSuccess(item.serverConnectionConfigId, item.libraryItemId, episodeId)
    } catch (e: Exception) {
      AbsLogger.error(tag, "Could not update auto download state (${e.javaClass.simpleName}: ${e.message})")
    }
  }

  /** A part of an auto-owned item that may not start a network transfer now. */
  private fun isBlockedTransfer(item: DownloadItem, part: DownloadItemPart, allowed: Boolean): Boolean =
          (!allowed || autoTransfersHeld) && !part.moved && !part.completed && !part.isMoving && part !in currentDownloadItemParts &&
                  autoGate.isAuto(item)

  /**
   * Stops running transfers of auto-owned items when their conditions no longer hold (Wi-Fi lost, metered network,
   * charger unplugged). Bytes stay in staging and the part resumes from there once allowed again.
   */
  @Synchronized
  fun pauseBlockedAutoTransfers() {
    if (currentDownloadItemParts.isEmpty() || (!autoTransfersHeld && autoGate.allowedNow())) return
    downloadItemQueue.filter { autoGate.isAuto(it) }.forEach { item ->
      item.downloadItemParts.filter { it in currentDownloadItemParts && !it.completed && !it.isMoving }.forEach { part ->
        activeCalls.remove(part.id)?.cancel()
        currentDownloadItemParts.remove(part)
        reservations.remove(part.destinationPath)
        part.downloadId = null
        part.bytesDownloaded = File(part.destinationPath).takeIf { it.exists() }?.length() ?: 0L
        AbsLogger.info(tag, "Paused auto download ${part.filename}: ${if (autoTransfersHeld) "the background window ended" else "auto download conditions are not met"}")
        persist(item, force = true)
        clientEventEmitter.onDownloadItemPartUpdate(part)
      }
    }
  }

  /**
   * The background lease of auto-owned transfers (M03). Held when a worker's execution window ended and no foreground
   * service keeps the process alive: running auto transfers stop (their staged bytes are kept and resumed by range
   * later) and none start until the next worker window, the foreground service or the app in front releases it.
   * Manual downloads always run under the foreground service and are never held.
   */
  fun setAutoTransfersHeld(held: Boolean) {
    synchronized(this) {
      if (autoTransfersHeld == held) return
      autoTransfersHeld = held
    }
    AbsLogger.info(tag, if (held) "Auto downloads wait for the next background window or the app" else "Auto downloads may run")
    if (held) pauseBlockedAutoTransfers()
    notifyQueueChanged()
  }

  /**
   * Why a queued item is not transferring, for the Downloads screen: "sdCard" (its finished files wait for the SD card
   * to be saved), or for an auto download "network", "wifi", "charging" (the auto download conditions) or
   * "background" (the background window ended; it continues in the next one or when the app is open); else null.
   */
  @Synchronized
  fun waitingReason(item: DownloadItem): String? {
    if (item.id in awaitingStorage) return "sdCard"
    val waiting = item.downloadItemParts.any {
      !it.moved && !it.completed && !it.failed && !it.isMoving && it !in currentDownloadItemParts
    }
    if (!waiting || !autoGate.isAuto(item)) return null
    return autoGate.blockedReason() ?: if (autoTransfersHeld) "background" else null
  }

  /** Whether the auto download feature queued this item (Downloads screen label). */
  @Synchronized
  fun isAutoOwned(item: DownloadItem): Boolean = autoGate.isAuto(item)

  @Synchronized
  fun restoreQueue() {
    if (downloadItemQueue.isNotEmpty()) return
    DeviceManager.dbManager.getDownloadItems().forEach { item ->
      // Validate before restore resets (and deletes) partial files. Missing ownership is a hold,
      // not a terminal transfer failure and must not schedule the 24-hour staging cleanup.
      if (item.downloadItemParts.any { !it.moved && downloadToken(it, item) == null }) {
        holdForDownloadAuthorization(item)
        downloadItemQueue.add(item)
        clientEventEmitter.onDownloadItem(item)
        return@forEach
      }
      val volumeMissing = storageUnavailable(item)
      // Files on a missing SD card are not gone, so they must not be reset as missing
      item.downloadItemParts.filter { it.moved && !volumeMissing }.forEach { part ->
        if (!finalizedFileExists(part)) {
          AbsLogger.error(tag, "Finalized file is missing; resetting ${part.filename}")
          part.moved = false
          part.completed = false
          part.completedDestinationUri = null
          part.downloadId = null
          part.reusedExistingFile = false
        }
      }
      if (item.isDownloadFinished && volumeMissing) {
        // Scanning now would find no files and drop the item, orphaning its files on the card
        awaitingStorage.add(item.id)
        downloadItemQueue.add(item)
        clientEventEmitter.onDownloadItem(item)
        AbsLogger.info(tag, "Download ${item.id} is complete; finalizing waits for the SD card")
        return@forEach
      }
      if (item.isDownloadFinished) {
        downloadItemQueue.add(item)
        checkDownloadItemFinished(item)
        return@forEach
      }
      // A missing finalized file above can make a previously moved part unfinished. Recheck
      // before resetting its retained staging copy; the initial guard only saw moved parts.
      if (item.downloadItemParts.any { !it.moved && downloadToken(it, item) == null }) {
        holdForDownloadAuthorization(item)
        downloadItemQueue.add(item)
        clientEventEmitter.onDownloadItem(item)
        return@forEach
      }
      if (volumeMissing && item.downloadItemParts.any { !it.moved }) {
        // Staging on a missing card can't be reset; the card comes back, so keep the auto-download record
        item.downloadItemParts.filter { !it.moved }.forEach { part ->
          part.failed = true
          part.storageUnavailable = true
          part.permissionLost = false
          part.failureReason = LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE
          part.completed = false
          part.isMoving = false
          part.downloadId = null
        }
        item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
        item.stagingCleanupAt = null
        persist(item, force = true)
        downloadItemQueue.add(item)
        IncompleteDownloadCleanup.schedule(context, item)
        clientEventEmitter.onDownloadItem(item)
        AbsLogger.info(tag, "Download ${item.id} is unfinished; waiting for the SD card")
        return@forEach
      }
      var resetFailed = false
      // A terminal failure keeps its reason after a restart (e.g. lost folder access), so the
      // Downloads screen still offers the right recovery instead of a generic failure
      // (by part id: parts are data classes whose hash changes when they are reset)
      val failures = item.downloadItemParts.associate { it.id to RestoredFailure(it.permissionLost, it.storageUnavailable, it.failureReason) }
      item.downloadItemParts.forEach { part ->
        if (part.moved) return@forEach
        if (!resetPartForRestart(part)) resetFailed = true
      }
      if (resetFailed) {
        val newlyFailed = item.terminalFailureAt == null
        item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
        item.stagingCleanupAt = null
        if (newlyFailed) recordAutoOutcome(item, failed = true)
      }
      if (item.terminalFailureAt != null) {
        item.downloadItemParts.filter { !it.moved }.forEach { part ->
          part.failed = true
          failures[part.id]?.let { failure ->
            part.permissionLost = failure.permissionLost
            part.storageUnavailable = failure.storageUnavailable
            part.failureReason = failure.reason
          }
        }
      }
      downloadItemQueue.add(item)
      if (item.terminalFailureAt != null) IncompleteDownloadCleanup.schedule(context, item)
      clientEventEmitter.onDownloadItem(item)
    }
    notifyQueueChanged()
  }

  private data class RestoredFailure(val permissionLost: Boolean, val storageUnavailable: Boolean, val reason: String?)

  @Synchronized
  fun addDownloadItem(newItem: DownloadItem) {
    val existingItem = downloadItemQueue.find { it.id == newItem.id }
    if (existingItem != null) {
      return
    }
    val downloadItem = newItem.withoutPodcastEpisodes()
    assignOwnDestinations(downloadItem)
    persist(downloadItem, force = true)
    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    notifyQueueChanged()
  }

  /**
   * R29-N02 admission: a part whose destination belongs to another download (another episode, file, item or server,
   * on record or already queued, or an unaccounted file on disk) gets a stable owner-specific name instead, so nothing
   * downloaded later can replace it. A recorded episode keeps the name its earlier copy uses (re-download / retry).
   * Called under the queue lock, so concurrent admissions see each other.
   */
  private fun assignOwnDestinations(item: DownloadItem) {
    val locals = DeviceManager.dbManager.getLocalLibraryItems()
    val claimed = mutableListOf<Pair<String, DownloadDestinationGuard.Owner>>()
    val parts = item.downloadItemParts.map { part ->
      val owner = DownloadDestinationGuard.ownerOf(item, part)
      val finalFile = File(part.finalDestinationPath)
      val existingName = DownloadDestinationGuard.recordedEpisodeFile(owner, finalFile.parent ?: "", locals)
      var chosen = if (existingName != null && existingName != part.filename) withFilename(item, part, existingName) else part
      if (existingName == null && admissionConflict(item, chosen, owner, locals, claimed)) {
        val alternate = withFilename(item, part, DownloadDestinationGuard.alternateFilename(part.filename, owner))
        AbsLogger.info(tag, "Destination of ${part.filename} belongs to another download; using ${alternate.filename}")
        chosen = alternate
      }
      claimed += DownloadDestinationGuard.canonical(chosen.finalDestinationPath) to owner
      chosen
    }
    item.downloadItemParts.clear()
    item.downloadItemParts.addAll(parts)
  }

  private fun admissionConflict(
          item: DownloadItem,
          part: DownloadItemPart,
          owner: DownloadDestinationGuard.Owner,
          locals: List<app.absplus.android.data.LocalLibraryItem>,
          claimed: List<Pair<String, DownloadDestinationGuard.Owner>>
  ): Boolean {
    val path = DownloadDestinationGuard.canonical(part.finalDestinationPath)
    val others = claimed.filter { it.first == path }.map { it.second } +
            downloadItemQueue.filter { it.id != item.id }.flatMap { queued ->
              queued.downloadItemParts.filter { DownloadDestinationGuard.canonical(it.finalDestinationPath) == path }
                      .map { DownloadDestinationGuard.ownerOf(queued, it) }
            } +
            DownloadDestinationGuard.recordedOwners(path, locals)
    // App storage only: a device-folder path is not a readable File (its finalization guard handles existing files)
    val onDisk = part.isAppStorage && File(path).exists()
    val verdict = DownloadDestinationGuard.verdict(owner, others, onDisk)
    // An existing cover of the same podcast or book is reused as before
    return verdict == DownloadDestinationGuard.Verdict.OTHER_OWNER ||
            (verdict == DownloadDestinationGuard.Verdict.UNKNOWN_FILE && owner.resource != "cover")
  }

  private fun withFilename(item: DownloadItem, part: DownloadItemPart, filename: String): DownloadItemPart =
          DownloadItemPart.make(
                  item.id,
                  filename,
                  part.fileSize,
                  File(File(part.destinationPath).parentFile, "$filename.part"),
                  File(File(part.finalDestinationPath).parentFile, filename),
                  part.finalDestinationSubfolder,
                  part.serverPath,
                  item.localFolder,
                  part.ebookFile,
                  part.audioTrack,
                  part.episode
          )

  /** Owners of [path] other than [part]: local records, and queued parts already placed there but not yet registered. */
  private fun finalizationOwners(item: DownloadItem, part: DownloadItemPart, path: String): List<DownloadDestinationGuard.Owner> {
    val target = DownloadDestinationGuard.canonical(path)
    return DownloadDestinationGuard.recordedOwners(target, DeviceManager.dbManager.getLocalLibraryItems()).toList() +
            downloadItemQueue.flatMap { queued ->
              queued.downloadItemParts.filter {
                it !== part && (it.moved || it.isMoving) && DownloadDestinationGuard.canonical(it.finalDestinationPath) == target
              }.map { DownloadDestinationGuard.ownerOf(queued, it) }
            }
  }

  /** The queued item with this id. Under the queue's lock: download threads add and remove items concurrently. */
  @Synchronized
  fun findDownloadItem(downloadItemId: String): DownloadItem? = downloadItemQueue.find { it.id == downloadItemId }

  /** Names of device folders the item can't write to any more (see [DownloadControls.foldersWithoutAccess]). */
  @Synchronized
  fun foldersWithoutAccess(downloadItemId: String): List<String> {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return emptyList()
    return DownloadControls.foldersWithoutAccess(item) { hasFolderAccess(item) }
  }

  @Synchronized
  fun retryDownloadItem(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    if (DownloadControls.storageUnavailable(item) { storageUnavailable(item) }) {
      AbsLogger.info(tag, "Retry of ${item.id} refused: SD card not available")
      return false
    }
    if (!DownloadControls.canRetry(item) { it in currentDownloadItemParts }) return false
    if (item.downloadItemParts.any { !it.moved && downloadToken(it, item) == null }) {
      holdForDownloadAuthorization(item)
      clientEventEmitter.onDownloadItem(item)
      notifyQueueChanged()
      return false
    }
    val unfinished = item.downloadItemParts.count { !it.moved }
    AbsLogger.info(
            tag,
            "Retry download ${item.id} \"${item.itemTitle}\" (folder ${item.localFolder.name}): restarting $unfinished unfinished file(s) from byte 0, keeping ${item.downloadItemParts.size - unfinished} finished"
    )
    synchronized(IncompleteDownloadCleanup) {
      var resetFailed = false
      item.downloadItemParts.filter { !it.moved }.forEach { part ->
        if (!resetPartForFreshDownload(part)) resetFailed = true
      }
      if (resetFailed) {
        item.downloadItemParts.filter { !it.moved }.forEach { it.failed = true }
        persist(item, force = true)
        return false
      }
      item.terminalFailureAt = null
      item.stagingCleanupAt = null
      IncompleteDownloadCleanup.cancel(context, item.id)
      persist(item, force = true)
    }
    clientEventEmitter.onDownloadItem(item)
    notifyQueueChanged()
    return true
  }

  @Synchronized
  fun isStorageUnavailable(downloadItemId: String): Boolean {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return false
    return DownloadControls.storageUnavailable(item) { storageUnavailable(item) }
  }

  @Synchronized
  fun resumeWork() {
    checkUpdateDownloadQueue()
    notifyQueueChanged()
  }

  /** Finalizes downloads that waited for their SD card, which nothing else wakes when no other download runs. */
  @Synchronized
  fun resumeAwaitingStorage() {
    if (awaitingStorage.isNotEmpty()) resumeWork()
  }

  enum class RemoveResult { REMOVED, NOT_FOUND, FINISHING, MOVING }

  /**
   * Cancel (active or queued) or Clear (stopped): stops the item's transfers, drops it from the
   * queue and deletes what [DownloadControls.removalPlan] allows. [action] is only for the log.
   */
  @Synchronized
  fun removeDownloadItem(downloadItemId: String, action: String): RemoveResult {
    val item = downloadItemQueue.find { it.id == downloadItemId } ?: return RemoveResult.NOT_FOUND
    when (DownloadControls.removalRefusal(item, item.id in finalizingItems)) {
      DownloadControls.Refusal.FINISHING -> return RemoveResult.FINISHING
      DownloadControls.Refusal.MOVING -> return RemoveResult.MOVING
      null -> {}
    }
    item.downloadItemParts.forEach { part ->
      // Callbacks of a cancelled transfer ignore parts that are no longer active
      activeCalls.remove(part.id)?.cancel()
      currentDownloadItemParts.remove(part)
      reservations.remove(part.destinationPath)
    }
    val plan = DownloadControls.removalPlan(item, ::isUsedByLocalItem)
    val appRoots = AppStorage.appOwnedRoots(volumes)
    var deleted = 0
    // Paths on a card that is out or is not the registered card are not this download's files to delete
    val cardUnverified = item.localFolder.id.startsWith(AppStorage.APPSD_PREFIX) && !AppStorage.isVerified(item.localFolder.id, volumes)
    if (cardUnverified) AbsLogger.info(tag, "Removing ${item.id} without deleting its files: its SD card is not available")
    plan.delete.filter { !cardUnverified }.forEach { path ->
      val file = File(path)
      if (file.exists()) {
        if (file.delete()) deleted++ else AbsLogger.error(tag, "Could not delete ${file.name} of removed download ${item.id}")
      }
      // Its now-empty item folder in app storage goes too (never a folder outside the app's own files)
      file.parentFile?.takeIf { dir ->
        appRoots.any { AppStorage.isUnder(dir, it) } &&
                appRoots.none { it.absoluteFile == dir.absoluteFile } &&
                dir.list()?.isEmpty() == true
      }?.delete()
    }
    IncompleteDownloadCleanup.cancel(context, item.id)
    downloadItemQueue.remove(item)
    lastPersistTime.remove(item.id)
    DeviceManager.dbManager.removeDownloadItem(item.id)
    val state = if (item.downloadItemParts.any { it.permissionLost }) "folder access lost" else if (item.downloadItemParts.any { it.storageUnavailable }) "SD card not available" else if (item.terminalFailureAt != null) "failed" else "in progress"
    AbsLogger.info(
            tag,
            "$action download ${item.id} \"${item.itemTitle}\" (folder ${item.localFolder.name}, was $state): deleted $deleted app-owned file(s), kept ${plan.keptFinishedFiles.size} finished file(s) in the destination"
    )
    notifyQueueChanged()
    return RemoveResult.REMOVED
  }

  /** Cancels every download that can be removed now (the notification's Cancel). */
  @Synchronized
  fun cancelAll() {
    downloadItemQueue.map { it.id }.forEach { removeDownloadItem(it, "Cancel all") }
    notifyQueueChanged()
  }

  /**
   * Whether the app can still write to the item's device folder (see [FolderAccess], shared with the
   * Local Folders permission check).
   */
  private fun hasFolderAccess(item: DownloadItem): Boolean =
          FolderAccess.canWrite(context, item.localFolder, volumes)

  /** The item's SD card is not mounted and writable; internal storage is always available. */
  private fun storageUnavailable(item: DownloadItem): Boolean =
          item.isAppStorage && !AppStorage.isAvailable(item.localFolder, volumes)

  /** Whether a saved local item already uses this part's finished file. */
  private fun isUsedByLocalItem(part: DownloadItemPart): Boolean =
          DeviceManager.dbManager.getLocalLibraryItems().any { local ->
            local.localFiles.any { file ->
              file.absolutePath == part.finalDestinationPath ||
                      (part.completedDestinationUri != null && file.contentUrl == part.completedDestinationUri)
            } || local.coverAbsolutePath == part.finalDestinationPath
          }

  // Auto-owned parts that may not transfer now are not work: the service and a worker must not stay alive (or start)
  // just to wait for Wi-Fi or a charger; the next allowed check, worker run or app start resumes them
  @Synchronized
  fun hasWork(): Boolean {
    if (finalizingItems.isNotEmpty()) return true
    val allowed by lazy { autoGate.allowedNow() }
    return downloadItemQueue.any { item ->
      item.downloadItemParts.any { part ->
        ((!part.moved && !part.failed) || part.isMoving) && !isBlockedTransfer(item, part, allowed)
      }
    }
  }

  @Synchronized
  private fun checkUpdateDownloadQueue() {
    val allowed by lazy { autoGate.allowedNow() }
    // Manual downloads first: auto-owned items never hold up something the user asked for
    downloadItemQueue.toList().sortedBy { autoGate.isAuto(it) }.forEach { item ->
      // Picks up a finalization that waited for the SD card to come back
      if (item.id in awaitingStorage) {
        if (!storageUnavailable(item)) {
          awaitingStorage.remove(item.id)
          checkDownloadItemFinished(item)
        }
        return@forEach
      }
      var slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      if (slots <= 0) return@forEach
      item.downloadItemParts
              .filter { part ->
                part.completed &&
                        !part.moved &&
                        !part.failed &&
                        !part.isMoving &&
                        part !in currentDownloadItemParts &&
                        File(part.destinationPath).exists() &&
                        !hasActiveDestinationConflict(part)
              }
              .take(slots)
              .forEach { part ->
                currentDownloadItemParts.add(part)
                part.downloadId = APP_MANAGED_DOWNLOAD_ID
              }
      slots = MAX_SIMULTANEOUS_DOWNLOADS - currentDownloadItemParts.size
      if (slots <= 0) return@forEach
      // Staged parts above may finish (no network); new transfers of a blocked auto item wait, untouched
      if ((!allowed || autoTransfersHeld) && autoGate.isAuto(item)) return@forEach
      item.getNextDownloadItemParts(slots).forEach { part ->
        // A same-named file in the device folder is only reused when no other download owns it (R29-N02)
        val existingFile = findSharedStorageFile(part)?.takeIf {
          finalizationOwners(item, part, part.finalDestinationPath)
                  .all { other -> DownloadDestinationGuard.sameOwner(DownloadDestinationGuard.ownerOf(item, part), other) }
        }
        if (existingFile != null) {
          part.bytesDownloaded = existingFile.length()
          part.progress = 100L
          part.completedDestinationUri = existingFile.uri.toString()
          part.reusedExistingFile = true
          File(part.destinationPath).delete()
          completePart(item, part)
          clientEventEmitter.onDownloadItemPartUpdate(part)
          return@forEach
        }
        if (completeFromExistingInternalCover(item, part)) return@forEach
        if (!hasFolderAccess(item)) {
          if (item.isAppStorage) failStorageUnavailable(item, part) else failPermissionLost(item, part)
          return@forEach
        }
        if (hasActiveDestinationConflict(part)) {
          leaveQueued(item, part)
        } else if (part.fileSize <= 0L && currentDownloadItemParts.any { it.fileSize <= 0L }) {
          leaveQueued(item, part)
        } else if (tryReserve(part)) startDownload(item, part)
        else {
          part.waitingForSpace = true
          part.lastUpdateTime = System.currentTimeMillis()
          persist(item)
          clientEventEmitter.onDownloadItemPartUpdate(part)
        }
      }
    }
    if (hasWork()) startWatchingDownloads() else notifyQueueChanged()
  }

  private fun startDownload(item: DownloadItem, part: DownloadItemPart) {
    val token = downloadToken(part, item)
    if (token == null) {
      holdForDownloadAuthorization(item)
      clientEventEmitter.onDownloadItem(item)
      return
    }
    val stagingFile = File(part.destinationPath)
    stagingFile.parentFile?.mkdirs()
    part.downloadId = APP_MANAGED_DOWNLOAD_ID
    part.waitingForSpace = false
    part.lastUpdateTime = System.currentTimeMillis()
    currentDownloadItemParts.add(part)
    persist(item, force = true)
    AbsLogger.info(tag, "Starting download for ${part.filename}")
    val handle =
            InternalDownloadManager(
                            stagingFile,
                            part.fileSize,
                            object : InternalProgressCallback {
                              override fun onSizeResolved(totalBytes: Long) {
                                synchronized(this@DownloadItemManager) {
                                  if (part !in currentDownloadItemParts || totalBytes < 0L) return
                                  if (part.fileSize == totalBytes) return
                                  AbsLogger.info(
                                          tag,
                                          "Using server size $totalBytes instead of metadata size ${part.fileSize} for ${part.filename}"
                                  )
                                  part.fileSize = totalBytes
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  persist(item, force = true)
                                  clientEventEmitter.onDownloadItemPartUpdate(part)
                                }
                              }

                              override fun onProgress(totalBytesWritten: Long, progress: Long) {
                                synchronized(this@DownloadItemManager) {
                                  if (part !in currentDownloadItemParts) return
                                  part.bytesDownloaded = totalBytesWritten
                                  part.progress = progress
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  persist(item)
                                }
                              }

                              override fun onComplete(failed: Boolean) {
                                synchronized(this@DownloadItemManager) {
                                  if (part !in currentDownloadItemParts) return
                                  part.failed = failed
                                  part.completed = !failed
                                  part.lastUpdateTime = System.currentTimeMillis()
                                  activeCalls.remove(part.id)
                                  persist(item, force = true)
                                }
                              }

                              override fun onAuthError() {
                                synchronized(this@DownloadItemManager) {
                                  if (part !in currentDownloadItemParts) return
                                  handleAuthError(item, part)
                                }
                              }

                              override fun onValidator(validator: String?) {
                                synchronized(this@DownloadItemManager) {
                                  if (part !in currentDownloadItemParts) return
                                  part.resumeValidator = validator
                                  persist(item, force = true)
                                }
                              }
                            },
                            part.resumeValidator,
                            { hasAvailableSpace(part) }
                    )
                    .download(serverUrl(item, part), token)
    if (part in currentDownloadItemParts && !part.completed && !part.failed) {
      activeCalls[part.id] = handle
    }
  }

  private fun downloadToken(part: DownloadItemPart, item: DownloadItem): String? {
    val saved = DeviceManager.getServerConnectionConfig(item.serverConnectionConfigId)?.copy()
    return DownloadAuthorization.tokenFor(
            item.serverConnectionConfigId, item.serverAddress, item.serverUserId,
            saved?.id, saved?.address, saved?.userId, saved?.token, serverUrl(item, part))
  }

  /** Retain media and queue ownership when credentials cannot be safely selected. */
  private fun holdForDownloadAuthorization(item: DownloadItem) {
    synchronized(IncompleteDownloadCleanup) {
      item.downloadItemParts.filter { !it.moved && !it.isMoving }.forEach { part ->
        activeCalls.remove(part.id)?.cancel()
        currentDownloadItemParts.remove(part)
        reservations.remove(part.destinationPath)
        part.downloadId = null
        part.failed = true
        part.waitingForSpace = false
        part.authorizationHold = true
        part.failureReason = "Download authorization changed: reconnect the original server before retrying"
      }
      item.terminalFailureAt = null
      IncompleteDownloadCleanup.cancel(context, item.id)
      persist(item, force = true)
    }
  }

  @Synchronized
  private fun startWatchingDownloads() {
    if (watcherRunning) return
    watcherRunning = true
    scope.launch {
      while (true) {
        val activeParts =
                synchronized(this@DownloadItemManager) { currentDownloadItemParts.toList() }
        activeParts.forEach(::handlePartUpdate)
        pauseBlockedAutoTransfers()
        synchronized(this@DownloadItemManager) {
          checkUpdateDownloadQueue()
          if (!hasWork()) {
            watcherRunning = false
            notifyQueueChanged()
            return@launch
          }
        }
        delay(WATCH_INTERVAL_MS)
      }
    }
  }

  private fun handlePartUpdate(part: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(part)
    val item =
            synchronized(this) { downloadItemQueue.find { it.id == part.downloadItemId } }
                    ?: run {
                      removeActivePart(part)
                      return
                    }
    if (!part.completed && !part.failed) {
      val lastUpdate = part.lastUpdateTime ?: return
      if (System.currentTimeMillis() - lastUpdate > STALL_TIMEOUT_MS) {
        AbsLogger.error(tag, "Download stalled: ${part.filename}")
        activeCalls.remove(part.id)?.cancel()
        failOrRetry(item, part, "Download stalled")
      }
      return
    }
    if (part.failed) {
      failOrRetry(item, part, "Transfer failed")
      return
    }
    // Never finalize an empty staged file as the downloaded media (defense in depth for every completion path)
    if (!part.moved && !part.isMoving && File(part.destinationPath).let { !it.isFile || it.length() <= 0L }) {
      AbsLogger.error(tag, "Not finalizing ${part.filename}: the staged file is empty")
      part.completed = false
      failOrRetry(item, part, "Downloaded file is empty")
      return
    }
    if (part.isAppStorage) finalizeInternalFile(item, part) else moveDownloadedFile(item, part)
  }

  @Synchronized
  private fun failOrRetry(item: DownloadItem, part: DownloadItemPart, reason: String) {
    removeActivePart(part)
    part.retryCount += 1
    reservations.remove(part.destinationPath)
    if (part.retryCount > MAX_RETRIES) {
      markTerminalFailure(item, part, "$reason after $MAX_RETRIES retries")
      return
    }
    part.failed = false
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    persist(item, force = true)
    clientEventEmitter.onDownloadItemPartUpdate(part)
  }

  /** A 401 refreshes the token for this queued item's server without consuming transfer retries. */
  @Synchronized
  private fun handleAuthError(item: DownloadItem, part: DownloadItemPart) {
    removeActivePart(part)
    reservations.remove(part.destinationPath)
    part.downloadId = null
    part.isMoving = false
    part.failed = false
    part.completed = false
    part.authRetryCount += 1
    part.lastUpdateTime = System.currentTimeMillis()
    if (part.authRetryCount > MAX_AUTH_RETRIES) {
      markTerminalFailure(item, part, "Unauthorized after $MAX_AUTH_RETRIES token refresh attempts")
      return
    }

    AbsLogger.info(
            tag,
            "Refreshing token after 401 for ${part.filename} (attempt ${part.authRetryCount})"
    )
    persist(item, force = true)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    refreshTokenThenResume(item.serverConnectionConfigId)
  }

  private fun refreshTokenThenResume(serverConnectionConfigId: String) {
    if (!refreshingServerIds.add(serverConnectionConfigId)) return
    apiHandler.refreshAuthTokens(serverConnectionConfigId) { result ->
      synchronized(this@DownloadItemManager) {
        refreshingServerIds.remove(serverConnectionConfigId)
        when (result) {
          is RefreshResult.Success -> {
            AbsLogger.info(
                    tag,
                    "Token refresh succeeded; resuming downloads for $serverConnectionConfigId"
            )
            checkUpdateDownloadQueue()
          }
          RefreshResult.Rejected -> failParkedAuthParts(serverConnectionConfigId)
          // Parked parts are still queued, so MAX_AUTH_RETRIES bounds the reattempts.
          RefreshResult.Transient -> {
            AbsLogger.info(
                    tag,
                    "Token refresh could not be completed; retrying downloads for $serverConnectionConfigId"
            )
            checkUpdateDownloadQueue()
          }
        }
      }
    }
  }

  @Synchronized
  private fun failParkedAuthParts(serverConnectionConfigId: String) {
    downloadItemQueue.toList().forEach { item ->
      if (item.serverConnectionConfigId != serverConnectionConfigId) return@forEach
      item.downloadItemParts
              .filter {
                it.authRetryCount > 0 &&
                        !it.completed &&
                        !it.failed &&
                        it.downloadId == null &&
                        it !in currentDownloadItemParts
              }
              .forEach { part ->
                markTerminalFailure(item, part, "Unable to refresh download authorization")
              }
    }
  }

  /** Retrying can't fix a revoked SAF grant, so this fails immediately instead of burning retries. */
  private fun failPermissionLost(item: DownloadItem, part: DownloadItemPart) {
    part.isMoving = false
    part.permissionLost = true
    markTerminalFailure(
            item,
            part,
            "Lost access to \"${part.localFolderName}\" — re-select the folder in Local Folders to resume this download"
    )
  }

  // The card will come back with the files intact: automatic retries can't succeed meanwhile, and the
  // episode must not be auto-downloaded again
  private fun failStorageUnavailable(item: DownloadItem, part: DownloadItemPart) {
    part.isMoving = false
    part.storageUnavailable = true
    markTerminalFailure(item, part, LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE, countsAsAutoFailure = false)
  }

  @Synchronized
  private fun markTerminalFailure(item: DownloadItem, part: DownloadItemPart, reason: String, countsAsAutoFailure: Boolean = true) {
    AbsLogger.error(tag, "$reason: ${part.filename}")
    removeActivePart(part)
    reservations.remove(part.destinationPath)
    part.failed = true
    part.completed = false
    part.downloadId = null
    part.isMoving = false
    part.failureReason = reason
    item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
    item.stagingCleanupAt = null
    persist(item, force = true)
    // A missing SD card is no failed attempt: the download waits for the card and keeps its auto ownership
    if (countsAsAutoFailure) recordAutoOutcome(item, failed = true)
    IncompleteDownloadCleanup.schedule(context, item)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    notifyQueueChanged()
  }

  // Under the queue lock: the ownership decision and the renames are one step for every finalization (R29-N02)
  @Synchronized
  private fun finalizeInternalFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    if (storageUnavailable(item)) {
      failStorageUnavailable(item, part)
      return
    }
    part.isMoving = true
    val stagingFile = File(part.destinationPath)
    val finalFile = File(part.finalDestinationPath)
    finalFile.parentFile?.mkdirs()
    val backup = File(finalFile.parentFile, ".${finalFile.name}.abs-backup")
    try {
      // A backup left by an interrupted finalization may be the only copy: restore it, or keep it aside, never delete it
      if (backup.exists()) {
        if (!finalFile.exists()) {
          if (!backup.renameTo(finalFile)) {
            markTerminalFailure(item, part, "Could not restore the previous copy of this download location")
            return
          }
          AbsLogger.info(tag, "Restored the previous copy of ${part.filename} left by an interrupted finalization")
        } else if (!DownloadDestinationGuard.preserve(backup)) {
          markTerminalFailure(item, part, "Could not set aside an earlier backup of this download location")
          return
        }
      }
      val owner = DownloadDestinationGuard.ownerOf(item, part)
      when (DownloadDestinationGuard.verdict(owner, finalizationOwners(item, part, finalFile.path), finalFile.exists())) {
        DownloadDestinationGuard.Verdict.OTHER_OWNER -> {
          markTerminalFailure(item, part, "Download location is used by another download; nothing was replaced")
          return
        }
        // Unaccounted bytes (e.g. a crash before registration) are kept aside, never deleted
        DownloadDestinationGuard.Verdict.UNKNOWN_FILE ->
                if (!DownloadDestinationGuard.preserve(finalFile)) {
                  markTerminalFailure(item, part, "Could not set aside an unrecognized file at the download location")
                  return
                }
        else -> {}
      }
      if (finalFile.exists() && !finalFile.renameTo(backup))
              throw IllegalStateException("Could not protect existing file")
      if (!stagingFile.renameTo(finalFile)) {
        if (backup.exists()) backup.renameTo(finalFile)
        throw IllegalStateException("Could not finalize internal staging file")
      }
      backup.delete()
      AbsLogger.info(tag, "Move completed for ${part.filename} (app storage)")
      completePart(item, part)
    } catch (e: Exception) {
      part.isMoving = false
      part.failed = true
      failOrRetry(item, part, e.message ?: "Internal finalization failed")
    }
  }

  private fun moveDownloadedFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    if (!hasFolderAccess(item)) {
      failPermissionLost(item, part)
      return
    }
    val root =
            DocumentFile.fromTreeUri(context, Uri.parse(part.localFolderUrl))
                    ?: return failFinalization(item, part, "Could not resolve SAF destination")
    part.isMoving = true
    persist(item, force = true)
    scope.launch {
      try {
        if (!hasAvailableSpace(part))
                throw IllegalStateException("Insufficient storage for SAF copy")
        val folderKey = "${root.uri}/${part.finalDestinationSubfolder}"
        val folderLock = safFolderLocks.computeIfAbsent(folderKey) { Any() }
        val folder =
                synchronized(folderLock) { getOrCreateFolder(root, part.finalDestinationSubfolder) }
                        ?: throw IllegalStateException("Could not create SAF destination folder")
        val temporaryName = ".${part.filename}.${part.id.hashCode()}.part"
        folder.findFile(temporaryName)?.delete()
        val temporary =
                folder.createFile(mimeTypeFor(part), temporaryName)
                        ?: throw IllegalStateException("Could not create SAF temporary file")
        val staging = File(part.destinationPath)
        FileInputStream(staging).use { input ->
          context.contentResolver.openOutputStream(temporary.uri, "w")?.use { input.copyTo(it) }
                  ?: throw IllegalStateException("Could not open SAF output stream")
        }
        if (temporary.length() != staging.length())
                throw IllegalStateException("SAF copy size mismatch")
        // R29-N02: the ownership decision, the replacement and the rename are one step per destination folder
        val destination = synchronized(folderLock) {
          val backupName = ".${part.filename}.abs-backup"
          // A backup left by an interrupted finalization may be the only copy: restore it, or keep it aside
          folder.findFile(backupName)?.let { left ->
            if (findDocumentByFilename(folder, part) == null) {
              if (!left.renameTo(part.filename))
                      throw IllegalStateException("Could not restore the previous copy of this download location")
            } else if (!left.renameTo(".${part.filename}.abs-preserved-${System.currentTimeMillis()}"))
                    throw IllegalStateException("Could not set aside an earlier backup of this download location")
          }
          val existing = findDocumentByFilename(folder, part)
          val owner = DownloadDestinationGuard.ownerOf(item, part)
          val others = synchronized(this@DownloadItemManager) { finalizationOwners(item, part, part.finalDestinationPath) }
          val verdict = DownloadDestinationGuard.verdict(owner, others, existing != null)
          // Another download's file, or a file in the user's folder nobody accounts for: never delete it
          if (verdict == DownloadDestinationGuard.Verdict.OTHER_OWNER || verdict == DownloadDestinationGuard.Verdict.UNKNOWN_FILE) {
            temporary.delete()
            null
          } else {
            // The same owner's previous copy is kept as a backup until the new file is in place
            if (existing != null && !existing.renameTo(backupName))
                    throw IllegalStateException("Could not protect existing file")
            if (!temporary.renameTo(part.filename)) {
              if (existing != null && !existing.renameTo(part.filename))
                      AbsLogger.error(tag, "Could not restore ${part.filename}; the previous copy is kept as $backupName")
              throw IllegalStateException("Could not finalize SAF temporary file")
            }
            val finalized = findDocumentByFilename(folder, part)
                    ?: throw IllegalStateException("Could not reopen finalized SAF file")
            if (existing != null && !existing.delete()) AbsLogger.error(tag, "Could not remove the previous copy $backupName")
            finalized
          }
        }
        if (destination == null) {
          synchronized(this@DownloadItemManager) {
            markTerminalFailure(item, part, "Download location is used by another file; nothing was replaced")
          }
          return@launch
        }
        if (destination.length() != staging.length())
                throw IllegalStateException("SAF final size mismatch")
        if (!staging.delete()) AbsLogger.error(tag, "Could not remove staging file ${staging.name}")
        part.completedDestinationUri = destination.uri.toString()
        AbsLogger.info(tag, "Move completed for ${part.filename} (selected device folder)")
        completePart(item, part)
      } catch (e: Exception) {
        failFinalization(item, part, "SAF copy failed: ${e.message}")
      }
    }
  }

  @Synchronized
  private fun failFinalization(item: DownloadItem, part: DownloadItemPart, message: String) {
    AbsLogger.error(tag, message)
    part.isMoving = false
    part.failed = true
    failOrRetry(item, part, message)
  }

  @Synchronized
  private fun completePart(item: DownloadItem, part: DownloadItemPart) {
    part.moved = true
    part.completed = true
    part.failed = false
    part.isMoving = false
    reservations.remove(part.destinationPath)
    removeActivePart(part)
    persist(item, force = true)
    checkDownloadItemFinished(item)
  }

  @Synchronized
  private fun checkDownloadItemFinished(item: DownloadItem) {
    if (!item.isDownloadFinished || !finalizingItems.add(item.id)) return
    IncompleteDownloadCleanup.cancel(context, item.id)
    scope.launch {
      var waitForCard = false
      try {
        val scanLock = scanLocks.computeIfAbsent(scanDestinationKey(item)) { Any() }
        synchronized(scanLock) {
          var incomplete = false
          folderScanner.scanDownloadItem(item, onIncomplete = { incomplete = true }) { scanResult ->
            if (DownloadFinalizationPolicy.waitInsteadOfDropping(scanResult?.localLibraryItem != null, storageUnavailable(item), incomplete)) {
              waitForCard = true
              AbsLogger.info(tag, "Download ${item.id} is complete; finalizing waits for the SD card")
              return@scanDownloadItem
            }
            val event =
                    JSObject().apply {
                      put("libraryItemId", item.id)
                      put("localFolderId", item.localFolder.id)
                      scanResult?.localLibraryItem?.let {
                        put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(it)))
                      }
                      scanResult?.localMediaProgress?.let {
                        put("localMediaProgress", JSObject(jacksonMapper.writeValueAsString(it)))
                      }
                    }
            clientEventEmitter.onDownloadItemComplete(event)
            recordAutoOutcome(item, failed = false)
            synchronized(this@DownloadItemManager) {
              downloadItemQueue.remove(item)
              DeviceManager.dbManager.removeDownloadItem(item.id)
            }
            StorageMoveService.requestReconcile(context)
          }
        }
      } catch (e: Exception) {
        // The files are already in place, so leave the item queued for restoreQueue to rescan.
        AbsLogger.error(tag, "Could not finalize download item ${item.id}: ${e.message}")
      } finally {
        synchronized(this@DownloadItemManager) {
          finalizingItems.remove(item.id)
          // Together with leaving finalizingItems: a queue check in between would drop the wait and find it still finalizing
          if (waitForCard) awaitingStorage.add(item.id)
          notifyQueueChanged()
        }
      }
    }
  }

  private fun tryReserve(part: DownloadItemPart): Boolean {
    val staging = File(part.destinationPath)
    staging.parentFile?.mkdirs()
    val expectedSize = if (part.fileSize > 0L) part.fileSize else UNKNOWN_PART_RESERVATION_BYTES
    val remaining =
            (expectedSize - (staging.takeIf(File::exists)?.length() ?: 0L)).coerceAtLeast(0L)
    val required = if (part.isAppStorage) remaining else remaining + expectedSize
    val key = AppStorage.storageKey(staging, volumes)
    val fs = statFsFor(staging)
    val headroom = max(MIN_FREE_SPACE_BYTES, fs.totalBytes / 20L)
    val alreadyReserved = reservations.filterKeys { AppStorage.storageKey(File(it), volumes) == key }.values.sum()
    if (fs.availableBytes - alreadyReserved < required + headroom) return false
    reservations[part.destinationPath] = required
    return true
  }

  private fun hasAvailableSpace(part: DownloadItemPart): Boolean {
    val staging = File(part.destinationPath)
    val fs = statFsFor(staging)
    return fs.availableBytes >= max(MIN_FREE_SPACE_BYTES, fs.totalBytes / 20L)
  }

  private fun statFsFor(staging: File): StatFs {
    var directory = staging.parentFile ?: context.filesDir
    directory.mkdirs()
    while (!directory.exists()) directory = directory.parentFile ?: context.filesDir
    return StatFs(directory.absolutePath)
  }

  @Synchronized
  private fun removeActivePart(part: DownloadItemPart) {
    activeCalls.remove(part.id)
    currentDownloadItemParts.remove(part)
  }

  private fun persist(item: DownloadItem, force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - (lastPersistTime[item.id] ?: 0L) < PERSIST_INTERVAL_MS) return
    lastPersistTime[item.id] = now
    DeviceManager.dbManager.saveDownloadItem(item)
  }

  private fun notifyQueueChanged() {
    clientEventEmitter.onQueueChanged(hasWork())
  }

  fun destroy() {
    activeCalls.values.forEach(InternalDownloadManager.DownloadHandle::cancel)
    activeCalls.clear()
    scope.cancel()
  }

  private fun getOrCreateFolder(root: DocumentFile, relativePath: String): DocumentFile? {
    var current = root
    relativePath.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
    }
    return current
  }

  private fun findSharedStorageFile(part: DownloadItemPart): DocumentFile? {
    if (part.isAppStorage) return null
    val root = DocumentFile.fromTreeUri(context, Uri.parse(part.localFolderUrl)) ?: return null
    var folder = root
    part.finalDestinationSubfolder.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      folder = folder.findFile(segment) ?: return null
    }
    val file = findDocumentByFilename(folder, part) ?: return null
    if (!file.isFile) return null
    if (part.fileSize > 0L && file.length() != part.fileSize) return null
    if (part.fileSize <= 0L && file.length() <= 0L) return null
    return file
  }

  private fun completeFromExistingInternalCover(
          item: DownloadItem,
          part: DownloadItemPart
  ): Boolean {
    if (!part.isAppStorage || !part.serverPath.endsWith("/cover")) return false
    val file = File(part.finalDestinationPath)
    if (!file.isFile || file.length() <= 0L) return false
    if (part.fileSize > 0L && file.length() != part.fileSize) return false
    part.bytesDownloaded = file.length()
    part.progress = 100L
    part.reusedExistingFile = true
    AbsLogger.info(tag, "Reusing existing cover ${part.filename}")
    File(part.destinationPath).delete()
    completePart(item, part)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    return true
  }

  private fun hasActiveDestinationConflict(part: DownloadItemPart): Boolean =
          currentDownloadItemParts.any { activePart ->
            activePart !== part &&
                    activePart.localFolderId == part.localFolderId &&
                    activePart.finalDestinationPath == part.finalDestinationPath
          }

  private fun leaveQueued(item: DownloadItem, part: DownloadItemPart) {
    if (!part.waitingForSpace) return
    part.waitingForSpace = false
    part.downloadId = null
    persist(item)
    clientEventEmitter.onDownloadItemPartUpdate(part)
  }

  private fun scanDestinationKey(item: DownloadItem): String =
          "${item.localFolder.id}:${item.itemFolderPath}"

  private fun finalizedFileExists(part: DownloadItemPart): Boolean {
    if (part.isAppStorage) {
      val file = File(part.finalDestinationPath)
      return file.isFile &&
              if (part.fileSize > 0L) file.length() == part.fileSize else file.length() > 0L
    }
    part.completedDestinationUri?.let { uri ->
      try {
        val file = DocumentFile.fromSingleUri(context, Uri.parse(uri))
        if (file?.isFile == true && (part.fileSize <= 0L || file.length() == part.fileSize))
                return true
      } catch (e: Exception) {
        AbsLogger.error(tag, "Could not validate SAF file ${part.filename}: ${e.message}")
      }
    }
    return findSharedStorageFile(part) != null
  }

  /**
   * Restart after the process ended: the staged bytes of a transfer whose source file is identified (ETag or
   * Last-Modified) are kept, minus their last [StagedResume.ROLLBACK_BYTES] that a power loss may have left unwritten,
   * and resumed with an If-Range request (InternalDownloadManager); anything else restarts from byte zero as before.
   */
  private fun resetPartForRestart(part: DownloadItemPart): Boolean {
    val validator = part.resumeValidator
    val kept = if (validator.isNullOrBlank()) 0L else StagedResume.keepForResume(File(part.destinationPath))
    if (kept <= 0L) return resetPartForFreshDownload(part)
    resetPartForFreshDownload(part, keepStaging = true)
    part.resumeValidator = validator
    part.bytesDownloaded = kept
    part.progress = if (part.fileSize > 0L) (kept * 100L / part.fileSize).coerceAtMost(99L) else 0L
    AbsLogger.info(tag, "Keeping $kept staged bytes of ${part.filename} to resume after a restart")
    return true
  }

  /** Resets an unmoved part when recovery crosses a service-session boundary. */
  private fun resetPartForFreshDownload(part: DownloadItemPart, keepStaging: Boolean = false): Boolean {
    val stagingFile = File(part.destinationPath)
    if (!keepStaging && stagingFile.exists() && !stagingFile.delete()) {
      AbsLogger.error(tag, "Could not delete staging file ${part.filename}")
      part.failed = true
      return false
    }
    part.completed = false
    part.bytesDownloaded = 0L
    part.progress = 0L
    part.failed = false
    part.isMoving = false
    part.downloadId = null
    part.retryCount = 0
    part.authRetryCount = 0
    part.waitingForSpace = false
    part.reusedExistingFile = false
    // Clear any terminal-failure state from a prior attempt (e.g. a lost SAF permission the
    // user has since fixed) so a stale reason/flag doesn't linger and mislead the Downloads UI.
    part.permissionLost = false
    part.storageUnavailable = false
    part.failureReason = null
    part.resumeValidator = null
    part.authorizationHold = false
    return true
  }

  private fun findDocumentByFilename(folder: DocumentFile, part: DownloadItemPart): DocumentFile? {
    folder.findFile(part.filename)?.let {
      return it
    }
    val expectedBaseName = part.filename.substringBeforeLast('.')
    return folder.listFiles().firstOrNull { document ->
      document.name == part.filename ||
              document.fullName == part.filename ||
              (part.audioTrack != null &&
                      document.isFile &&
                      ((document.name ?: "").substringBeforeLast('.') == expectedBaseName ||
                              document.fullName.substringBeforeLast('.') == expectedBaseName))
    }
  }

  private fun mimeTypeFor(part: DownloadItemPart): String =
          part.audioTrack?.mimeType
                  ?: when (part.ebookFile?.ebookFormat?.lowercase()) {
                    "epub" -> "application/epub+zip"
                    "pdf" -> "application/pdf"
                    else -> "image/jpeg"
                  }

  private fun serverUrl(item: DownloadItem, part: DownloadItemPart): String {
    val rawCover = if (part.serverPath.endsWith("/cover")) "?raw=1" else ""
    return "${item.serverAddress}${part.serverPath}$rawCover"
  }

  private companion object {
    const val APP_MANAGED_DOWNLOAD_ID = -1L
    const val MAX_SIMULTANEOUS_DOWNLOADS = 3
    const val WATCH_INTERVAL_MS = 1_000L
    const val STALL_TIMEOUT_MS = 60_000L
    const val MAX_RETRIES = 5
    const val MAX_AUTH_RETRIES = 2
    const val PERSIST_INTERVAL_MS = 2_000L
    const val MIN_FREE_SPACE_BYTES = 100L * 1024L * 1024L
    const val UNKNOWN_PART_RESERVATION_BYTES = 100L * 1024L * 1024L
  }
}

object DownloadFinalizationPolicy {
  /**
   * A finished download whose scan saved nothing stays queued instead of being dropped when its SD card went away, or
   * when the scan refused to save a partial item because some files could not be read (FolderScanner.scanParts is all
   * or nothing on removable storage). Its files are not gone, and dropping it would orphan them (H02).
   */
  fun waitInsteadOfDropping(saved: Boolean, storageUnavailable: Boolean, incomplete: Boolean): Boolean =
          !saved && (storageUnavailable || incomplete)
}
