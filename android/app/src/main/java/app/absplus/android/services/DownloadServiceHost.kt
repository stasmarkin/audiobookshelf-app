package app.absplus.android.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderScanner
import app.absplus.android.managers.DbManager
import app.absplus.android.managers.DownloadItemManager
import app.absplus.android.managers.IncompleteDownloadCleanup
import app.absplus.android.managers.LocalItemLocks
import app.absplus.android.models.DownloadItem
import app.absplus.android.plugins.AbsLogger
import com.getcapacitor.JSObject
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Shared process owner used by the foreground service and the Capacitor bridge. */
object DownloadServiceHost {
  const val SERVICE_START_FAILED_MESSAGE = "Unable to start the Android download service"

  enum class ExistingDownloadResult { NOT_FOUND, ACTIVE, RETRIED, SERVICE_START_FAILED, FOLDER_ACCESS, STORAGE_UNAVAILABLE, HELD }

  data class NotificationStrings(
          val preparing: String,
          val downloadingFile: String,
          val waitingForStorage: String,
          val downloads: String,
          val cancel: String,
          val moveToSd: String,
          val moveToInternal: String
  )

  private var manager: DownloadItemManager? = null
  private var bridgeEmitter: DownloadItemManager.DownloadEventEmitter = NoopEmitter
  @Volatile private var service: DownloadService? = null
  @Volatile private var bridgeReady = false
  private val deferredCompletions = Collections.synchronizedList(mutableListOf<JSObject>())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var restoreJob: Job? = null

  @Synchronized
  fun ensure(context: Context): DownloadItemManager {
    if (manager == null) {
      val appContext = context.applicationContext
      DbManager.initialize(appContext)
      manager = DownloadItemManager(FolderScanner(appContext), appContext, ForwardingEmitter)
      watchNetwork(appContext)
      restoreJob = scope.launch {
        try {
          if (DeviceManager.dbManager.ensureValidDownloadQueue()) {
            IncompleteDownloadCleanup.cleanupExpired(appContext)
            manager!!.restoreQueue()
          } else {
            AbsLogger.error(TAG, "Skipping invalid download queue because it could not be cleared")
          }
          onRestoreComplete(appContext)
        } catch (restoreError: Exception) {
          AbsLogger.error(
                  TAG,
                  "Could not restore download queue (${restoreError.javaClass.simpleName}: ${restoreError.message})"
          )
        }
      }
    }
    return manager!!
  }

  /** Attaches the frontend after restored queue items have been emitted. */
  @Synchronized
  fun attachBridge(context: Context, emitter: DownloadItemManager.DownloadEventEmitter) {
    bridgeReady = false
    bridgeEmitter = emitter
    ensure(context).setEventEmitter(ForwardingEmitter)
    bridgeReady = true
    val completions = synchronized(deferredCompletions) {
      deferredCompletions.toList().also { deferredCompletions.clear() }
    }
    completions.forEach(bridgeEmitter::onDownloadItemComplete)
    // The app is in front: held auto downloads may run again under the foreground service
    ensure(context).setAutoTransfersHeld(false)
    startServiceIfIdle(context)
  }

  /**
   * Auto-owned transfers paused for being offline or on a metered network resume as soon as the network allows them
   * (startServiceIfIdle only starts the service when the queue has work that may run now). Process-wide, registered
   * once; from the background Android may refuse the service start, then the next worker run or app start resumes.
   */
  private fun watchNetwork(appContext: Context) {
    if (networkWatched) return
    networkWatched = true
    try {
      val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = startServiceIfIdle(appContext)
      })
    } catch (e: RuntimeException) {
      AbsLogger.error(TAG, "Could not watch the network for paused auto downloads: ${e.message}")
    }
  }

  /** Starts the service for queued work restored or enqueued while the app was in background (e.g. by a worker). */
  fun startServiceIfIdle(context: Context) {
    val appContext = context.applicationContext
    scope.launch {
      restoreJob?.join()
      if (service == null && manager?.hasWork() == true) startService(appContext)
    }
  }

  /**
   * The app came to the front: auto downloads held when a background window ended may run again, under the foreground
   * service (a process that survived in the background does not load the bridge again). No-op before the manager exists.
   */
  fun releaseAutoHold(context: Context) {
    val m = synchronized(this) { manager } ?: return
    m.setAutoTransfersHeld(false)
    startServiceIfIdle(context)
  }

  /** Finalizes downloads that waited for an SD card that may be back now; no-op before the manager exists. */
  fun resumeAwaitingStorage() {
    val m = synchronized(this) { manager } ?: return
    scope.launch { m.resumeAwaitingStorage() }
  }

  @Synchronized
  fun detachBridge() {
    bridgeReady = false
    bridgeEmitter = NoopEmitter
  }

  /** Builds and queues a download under the local item's lock; [build] returns the item or an error. */
  fun enqueueResolved(
          context: Context,
          localItemId: String,
          build: () -> Pair<DownloadItem?, String?>,
          callback: (String?) -> Unit
  ) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      val error = try {
        LocalItemLocks.withItem(localItemId) {
          val (item, err) = build()
          if (item != null) {
            queue.addDownloadItem(item)
            LocalItemLocks.bump(localItemId)
            null
          } else {
            err ?: "Could not start download"
          }
        }
      } catch (e: Exception) {
        AbsLogger.error(TAG, "Could not start download for $localItemId (${e.javaClass.simpleName}: ${e.message})")
        "Could not start download (${e.javaClass.simpleName}: ${e.message})"
      }
      if (error != null) callback(error)
      else if (startService(context)) callback(null)
      else callback(SERVICE_START_FAILED_MESSAGE)
    }
  }

  /** The folder of any queued download of the item, so its later downloads stay on the same volume. */
  fun queuedFolderId(libraryItemId: String): String? =
          manager?.let { m -> synchronized(m) { m.downloadItemQueue.firstOrNull { it.libraryItemId == libraryItemId }?.localFolder?.id } }

  suspend fun awaitRestored(context: Context) {
    ensure(context)
    restoreJob?.join()
  }

  /** The queue in every state; empty before the manager exists. */
  fun queueSnapshot(): List<DownloadItem> = manager?.let { m -> synchronized(m) { m.downloadItemQueue.toList() } } ?: emptyList()

  fun hasQueuedDownload(libraryItemId: String): Boolean =
          manager?.let { m -> synchronized(m) { m.downloadItemQueue.any { it.libraryItemId == libraryItemId } } } ?: false

  fun retryExisting(
          context: Context,
          downloadItemId: String,
          callback: (ExistingDownloadResult) -> Unit
  ) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      val existing = queue.findDownloadItem(downloadItemId)
      val missingFolders = if (existing == null) emptyList() else queue.foldersWithoutAccess(downloadItemId)
      if (existing == null) {
        callback(ExistingDownloadResult.NOT_FOUND)
      } else if (queue.isStorageUnavailable(downloadItemId)) {
        AbsLogger.info(TAG, "Retry of $downloadItemId refused: SD card not available")
        callback(ExistingDownloadResult.STORAGE_UNAVAILABLE)
      } else if (missingFolders.isNotEmpty()) {
        // Retrying can only fail again until the user re-selects the folder, so the item stays as it is
        AbsLogger.info(TAG, "Retry of $downloadItemId refused: no access to ${missingFolders.joinToString()}")
        callback(ExistingDownloadResult.FOLDER_ACCESS)
      } else if (!queue.retryDownloadItem(downloadItemId)) {
        // Still another server than the one the download was started for: it stays held, nothing was deleted
        val held = queue.findDownloadItem(downloadItemId)?.downloadItemParts?.any { it.authorizationHold } == true
        callback(if (held) ExistingDownloadResult.HELD else ExistingDownloadResult.ACTIVE)
      } else if (startService(context)) {
        callback(ExistingDownloadResult.RETRIED)
      } else {
        callback(ExistingDownloadResult.SERVICE_START_FAILED)
      }
    }
  }

  /** Cancel or Clear one download (see [DownloadItemManager.removeDownloadItem]). */
  fun remove(
          context: Context,
          downloadItemId: String,
          action: String,
          callback: (DownloadItemManager.RemoveResult) -> Unit
  ) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      callback(queue.removeDownloadItem(downloadItemId, action))
    }
  }

  /**
   * Runs queued transfers inside a background worker's execution window. From the background on
   * Android 12+ the foreground service can't be started, so the worker keeps the process alive.
   */
  suspend fun runQueueWhileWorking(context: Context, budgetMs: Long) {
    val queue = ensure(context)
    restoreJob?.join()
    if (service != null) return
    queue.setAutoTransfersHeld(false)
    queue.resumeWork()
    try {
      withTimeoutOrNull(budgetMs) {
        while (service == null && synchronized(queue) { queue.hasWork() }) delay(WORKER_POLL_MS)
      }
    } finally {
      // Budget used up or the worker cancelled: nothing keeps the process alive once the worker returns, so auto
      // transfers stop here and resume (by range) in the next window or when the app is open (M03)
      if (service == null) queue.setAutoTransfersHeld(true)
    }
  }

  fun queuedState(
          context: Context,
          downloadItemId: String,
          callback: (DownloadItemManager.QueuedState) -> Unit
  ) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      callback(queue.queuedState(downloadItemId))
    }
  }

  /** Each queued item with why it waits ([DownloadItemManager.waitingReason]) and whether auto download queued it. */
  data class ItemStatus(val item: DownloadItem, val waitingFor: String?, val isAuto: Boolean)

  fun statusSnapshot(context: Context, callback: (List<ItemStatus>) -> Unit) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      val items = synchronized(queue) { queue.downloadItemQueue.toList() }
      callback(items.map { ItemStatus(it, queue.waitingReason(it), queue.isAutoOwned(it)) })
    }
  }

  /** The current download queue, restored items included, for the Downloads screen. */
  fun snapshot(context: Context, callback: (List<DownloadItem>) -> Unit) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      callback(synchronized(queue) { queue.downloadItemQueue.toList() })
    }
  }

  fun cancelAll(context: Context) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      queue.cancelAll()
    }
  }

  fun setNotificationStrings(
          context: Context,
          preparing: String,
          downloadingFile: String,
          waitingForStorage: String,
          downloads: String,
          cancel: String,
          moveToSd: String,
          moveToInternal: String
  ) {
    context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREPARING, preparing)
            .putString(KEY_DOWNLOADING_FILE, downloadingFile)
            .putString(KEY_WAITING_FOR_STORAGE, waitingForStorage)
            .putString(KEY_DOWNLOADS, downloads)
            .putString(KEY_CANCEL, cancel)
            .putString(KEY_MOVE_TO_SD, moveToSd)
            .putString(KEY_MOVE_TO_INTERNAL, moveToInternal)
            .apply()
  }

  fun notificationStrings(context: Context): NotificationStrings {
    val preferences = context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
    return NotificationStrings(
            preferences.getString(KEY_PREPARING, DEFAULT_PREPARING) ?: DEFAULT_PREPARING,
            preferences.getString(KEY_DOWNLOADING_FILE, DEFAULT_DOWNLOADING_FILE)
                    ?: DEFAULT_DOWNLOADING_FILE,
            preferences.getString(KEY_WAITING_FOR_STORAGE, DEFAULT_WAITING_FOR_STORAGE)
                    ?: DEFAULT_WAITING_FOR_STORAGE,
            preferences.getString(KEY_DOWNLOADS, DEFAULT_DOWNLOADS) ?: DEFAULT_DOWNLOADS,
            preferences.getString(KEY_CANCEL, DEFAULT_CANCEL) ?: DEFAULT_CANCEL,
            preferences.getString(KEY_MOVE_TO_SD, DEFAULT_MOVE_TO_SD) ?: DEFAULT_MOVE_TO_SD,
            preferences.getString(KEY_MOVE_TO_INTERNAL, DEFAULT_MOVE_TO_INTERNAL) ?: DEFAULT_MOVE_TO_INTERNAL)
  }

  fun attachService(downloadService: DownloadService) {
    synchronized(this) { service = downloadService }
    ensure(downloadService).setAutoTransfersHeld(false)
    startWork(downloadService)
  }

  @Synchronized
  fun detachService(downloadService: DownloadService) {
    if (service === downloadService) service = null
  }

  fun startWork(context: Context) {
    val queue = ensure(context)
    scope.launch {
      restoreJob?.join()
      queue.resumeWork()
    }
  }

  private fun onRestoreComplete(context: Context) {
    val attachedService = synchronized(this) { service }
    if (attachedService != null) {
      manager?.resumeWork()
    } else if (bridgeReady && manager?.hasWork() == true) {
      startService(context)
    }
  }

  private fun startService(context: Context): Boolean {
    return try {
      ContextCompat.startForegroundService(context, DownloadService.intent(context))
      true
    } catch (e: RuntimeException) {
      AbsLogger.error(TAG, "Could not start download foreground service: ${e.message}")
      false
    }
  }

  private object ForwardingEmitter : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) { bridgeEmitter.onDownloadItem(downloadItem) }
    override fun onDownloadItemPartUpdate(downloadItemPart: app.absplus.android.models.DownloadItemPart) {
      if (bridgeReady) bridgeEmitter.onDownloadItemPartUpdate(downloadItemPart)
      service?.onPartUpdate(downloadItemPart)
    }
    override fun onDownloadItemComplete(jsobj: JSObject) {
      if (bridgeReady) bridgeEmitter.onDownloadItemComplete(jsobj) else deferredCompletions.add(jsobj)
    }
    override fun onQueueChanged(hasWork: Boolean) {
      bridgeEmitter.onQueueChanged(hasWork)
      service?.onQueueChanged(hasWork)
    }
  }

  private object NoopEmitter : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) = Unit
    override fun onDownloadItemPartUpdate(downloadItemPart: app.absplus.android.models.DownloadItemPart) = Unit
    override fun onDownloadItemComplete(jsobj: JSObject) = Unit
    override fun onQueueChanged(hasWork: Boolean) = Unit
  }

  private const val NOTIFICATION_PREFERENCES = "download_notifications"
  private const val KEY_PREPARING = "preparing"
  private const val KEY_DOWNLOADING_FILE = "downloading_file"
  private const val KEY_WAITING_FOR_STORAGE = "waiting_for_storage"
  private const val KEY_DOWNLOADS = "downloads"
  private const val KEY_CANCEL = "cancel"
  private const val KEY_MOVE_TO_SD = "move_to_sd"
  private const val KEY_MOVE_TO_INTERNAL = "move_to_internal"
  const val DEFAULT_MOVE_TO_SD = "Moving downloads to SD card"
  const val DEFAULT_MOVE_TO_INTERNAL = "Moving downloads to internal storage"
  private const val DEFAULT_PREPARING = "Preparing downloads"
  private const val DEFAULT_DOWNLOADING_FILE = "Downloading {0}"
  private const val DEFAULT_WAITING_FOR_STORAGE = "Waiting for available storage"
  private const val DEFAULT_DOWNLOADS = "Downloads"
  private const val DEFAULT_CANCEL = "Cancel"
  private const val TAG = "DownloadServiceHost"
  private const val WORKER_POLL_MS = 5_000L
  @Volatile private var networkWatched = false
}
