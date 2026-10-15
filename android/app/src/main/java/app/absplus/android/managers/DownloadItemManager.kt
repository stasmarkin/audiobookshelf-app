package app.absplus.android.managers

import app.absplus.android.sync.LocalCopyReady
import app.absplus.android.sync.SyncRuntime
import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.file.fullName
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderAccess
import app.absplus.android.device.FolderScanner
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
import app.absplus.android.server.RefreshResult
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
        private var clientEventEmitter: DownloadEventEmitter
) {
  private val tag = "DownloadItemManager"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activeCalls = ConcurrentHashMap<String, InternalDownloadManager.DownloadHandle>()
  private val safFolderLocks = ConcurrentHashMap<String, Any>()
  private val scanLocks = ConcurrentHashMap<String, Any>()
  private val reservations = mutableMapOf<String, Long>()
  private val lastPersistTime = mutableMapOf<String, Long>()
  private val finalizingItems = mutableSetOf<String>()
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
  }

  @Synchronized
  fun setEventEmitter(eventEmitter: DownloadEventEmitter) {
    clientEventEmitter = eventEmitter
    downloadItemQueue.forEach(clientEventEmitter::onDownloadItem)
    notifyQueueChanged()
  }

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
      item.downloadItemParts.filter { it.moved }.forEach { part ->
        if (!finalizedFileExists(part)) {
          AbsLogger.error(tag, "Finalized file is missing; resetting ${part.filename}")
          part.moved = false
          part.completed = false
          part.completedDestinationUri = null
          part.downloadId = null
          part.reusedExistingFile = false
        }
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
      var resetFailed = false
      // A terminal failure keeps its reason after a restart (e.g. lost folder access), so the
      // Downloads screen still offers the right recovery instead of a generic failure
      // (by part id: parts are data classes whose hash changes when they are reset)
      val failures = item.downloadItemParts.associate { it.id to (it.permissionLost to it.failureReason) }
      item.downloadItemParts.forEach { part ->
        if (part.moved) return@forEach
        if (!resetPartForFreshDownload(part)) resetFailed = true
      }
      if (resetFailed) {
        item.terminalFailureAt = item.terminalFailureAt ?: System.currentTimeMillis()
        item.stagingCleanupAt = null
      }
      if (item.terminalFailureAt != null) {
        item.downloadItemParts.filter { !it.moved }.forEach { part ->
          part.failed = true
          failures[part.id]?.let { (permissionLost, reason) ->
            part.permissionLost = permissionLost
            part.failureReason = reason
          }
        }
      }
      downloadItemQueue.add(item)
      if (item.terminalFailureAt != null) IncompleteDownloadCleanup.schedule(context, item)
      clientEventEmitter.onDownloadItem(item)
    }
    notifyQueueChanged()
  }

  @Synchronized
  fun addDownloadItem(newItem: DownloadItem) {
    val existingItem = downloadItemQueue.find { it.id == newItem.id }
    if (existingItem != null) {
      return
    }
    val downloadItem = newItem.withoutPodcastEpisodes()
    persist(downloadItem, force = true)
    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    notifyQueueChanged()
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
  fun resumeWork() {
    checkUpdateDownloadQueue()
    notifyQueueChanged()
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
    var deleted = 0
    plan.delete.forEach { path ->
      val file = File(path)
      if (file.exists()) {
        if (file.delete()) deleted++ else AbsLogger.error(tag, "Could not delete ${file.name} of removed download ${item.id}")
      }
      // Its now-empty item folder in app storage goes too (never a folder outside the app's own files)
      file.parentFile?.takeIf {
        it.path.startsWith(context.filesDir.path) && it != context.filesDir && it.list()?.isEmpty() == true
      }?.delete()
    }
    IncompleteDownloadCleanup.cancel(context, item.id)
    downloadItemQueue.remove(item)
    lastPersistTime.remove(item.id)
    DeviceManager.dbManager.removeDownloadItem(item.id)
    val state = if (item.downloadItemParts.any { it.permissionLost }) "folder access lost" else if (item.terminalFailureAt != null) "failed" else "in progress"
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
          item.isInternalStorage || FolderAccess.canWrite(context, item.localFolder)

  /** Whether a saved local item already uses this part's finished file. */
  private fun isUsedByLocalItem(part: DownloadItemPart): Boolean =
          DeviceManager.dbManager.getLocalLibraryItems().any { local ->
            local.localFiles.any { file ->
              file.absolutePath == part.finalDestinationPath ||
                      (part.completedDestinationUri != null && file.contentUrl == part.completedDestinationUri)
            } || local.coverAbsolutePath == part.finalDestinationPath
          }

  @Synchronized
  fun hasWork(): Boolean =
          finalizingItems.isNotEmpty() ||
                  downloadItemQueue.any { item ->
                    item.downloadItemParts.any { part ->
                      (!part.moved && !part.failed) || part.isMoving
                    }
                  }

  @Synchronized
  private fun checkUpdateDownloadQueue() {
    downloadItemQueue.toList().forEach { item ->
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
      item.getNextDownloadItemParts(slots).forEach { part ->
        val existingFile = findSharedStorageFile(part)
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
        if (!part.isInternalStorage && !hasFolderAccess(item)) {
          failPermissionLost(item, part)
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
                            },
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
    if (part.isInternalStorage) finalizeInternalFile(item, part) else moveDownloadedFile(item, part)
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

  @Synchronized
  private fun markTerminalFailure(item: DownloadItem, part: DownloadItemPart, reason: String) {
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
    IncompleteDownloadCleanup.schedule(context, item)
    clientEventEmitter.onDownloadItemPartUpdate(part)
    notifyQueueChanged()
  }

  private fun finalizeInternalFile(item: DownloadItem, part: DownloadItemPart) {
    if (part.moved || part.isMoving) return
    part.isMoving = true
    val stagingFile = File(part.destinationPath)
    val finalFile = File(part.finalDestinationPath)
    finalFile.parentFile?.mkdirs()
    val backup = File(finalFile.parentFile, ".${finalFile.name}.abs-backup")
    try {
      if (backup.exists() && !backup.delete()) throw IllegalStateException("Could not clear backup")
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
        val existing = findDocumentByFilename(folder, part)
        if (existing != null && !existing.delete())
                throw IllegalStateException("Could not replace existing file")
        if (!temporary.renameTo(part.filename))
                throw IllegalStateException("Could not finalize SAF temporary file")
        val destination =
                findDocumentByFilename(folder, part)
                        ?: throw IllegalStateException("Could not reopen finalized SAF file")
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
      try {
        val scanLock = scanLocks.computeIfAbsent(scanDestinationKey(item)) { Any() }
        synchronized(scanLock) {
          folderScanner.scanDownloadItem(item) { scanResult ->
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
            if (scanResult?.localLibraryItem != null) {
              SyncRuntime.localCopyReady.tryEmit(LocalCopyReady(item.serverConnectionConfigId, item.libraryItemId, item.episodeId))
            }
            synchronized(this@DownloadItemManager) {
              downloadItemQueue.remove(item)
              DeviceManager.dbManager.removeDownloadItem(item.id)
            }
          }
        }
      } catch (e: Exception) {
        // The files are already in place, so leave the item queued for restoreQueue to rescan.
        AbsLogger.error(tag, "Could not finalize download item ${item.id}: ${e.message}")
      } finally {
        synchronized(this@DownloadItemManager) {
          finalizingItems.remove(item.id)
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
    val required = if (part.isInternalStorage) remaining else remaining + expectedSize
    val key = storageKey(staging)
    val fs = statFsFor(staging)
    val headroom = max(MIN_FREE_SPACE_BYTES, fs.totalBytes / 20L)
    val alreadyReserved = reservations.filterKeys { storageKey(File(it)) == key }.values.sum()
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

  private fun storageKey(file: File): String =
          if (file.absolutePath.startsWith(context.filesDir.absolutePath)) "internal"
          else "external"

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
    if (part.isInternalStorage) return null
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
    if (!part.isInternalStorage || !part.serverPath.endsWith("/cover")) return false
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
    if (part.isInternalStorage) {
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

  /** Resets an unmoved part when recovery crosses a service-session boundary. */
  private fun resetPartForFreshDownload(part: DownloadItemPart): Boolean {
    val stagingFile = File(part.destinationPath)
    if (stagingFile.exists() && !stagingFile.delete()) {
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
    part.failureReason = null
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
