package app.absplus.android.plugins

import app.absplus.android.MainActivity
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import app.absplus.android.server.ApiHandler
import app.absplus.android.managers.AutoDeleteManager
import app.absplus.android.managers.AutoDeleteScheduler
import app.absplus.android.managers.AutoDownloadManager
import app.absplus.android.managers.AutoDownloadStore
import app.absplus.android.managers.DownloadItemManager
import app.absplus.android.managers.DownloadIdentifiers
import app.absplus.android.managers.LibraryItemDownloadStarter
import app.absplus.android.services.DownloadServiceHost
import app.absplus.android.managers.AutoDownloadScheduler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject

@CapacitorPlugin(name = "AbsDownloader")
class AbsDownloader : Plugin() {
  private val tag = "AbsDownloader"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var apiHandler: ApiHandler
  lateinit var downloadItemManager: DownloadItemManager

  private val clientEventEmitter = (object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem:DownloadItem) {
      notifyListeners("onDownloadItem", JSObject(jacksonMapper.writeValueAsString(downloadItem)))
    }
    override fun onDownloadItemPartUpdate(downloadItemPart:DownloadItemPart) {
      notifyListeners("onDownloadItemPartUpdate", JSObject(jacksonMapper.writeValueAsString(downloadItemPart)))
    }
    override fun onDownloadItemComplete(jsobj:JSObject) {
      notifyListeners("onItemDownloadComplete", jsobj)
    }
    override fun onQueueChanged(hasWork: Boolean) {
      notifyListeners("onQueueChanged", JSObject().put("hasWork", hasWork))
    }
  })

  private var onDeletedListener: (() -> Unit)? = null

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)
    downloadItemManager = DownloadServiceHost.ensure(mainActivity)
    DownloadServiceHost.attachBridge(mainActivity, clientEventEmitter)
    onDeletedListener = { notifyListeners("onLocalItemsChanged", JSObject()) }
    AutoDeleteManager.onDeleted = onDeletedListener

    @OptIn(DelicateCoroutinesApi::class)
    GlobalScope.launch(Dispatchers.IO) {
      try {
        AutoDownloadScheduler.reschedule(mainActivity)
        AutoDeleteScheduler.reschedule(mainActivity)
      } catch (e: Exception) {
        AbsLogger.error(tag, "Failed to schedule auto download: ${e.message}")
      }
    }
  }

  override fun handleOnDestroy() {
    // A newer instance of the plugin may have registered its own already
    if (AutoDeleteManager.onDeleted === onDeletedListener) AutoDeleteManager.onDeleted = null
    DownloadServiceHost.detachBridge()
    super.handleOnDestroy()
  }

  @PluginMethod
  fun setDownloadNotificationStrings(call: PluginCall) {
    DownloadServiceHost.setNotificationStrings(
            mainActivity,
            call.getString("preparing") ?: "Preparing downloads",
            call.getString("downloadingFile") ?: "Downloading {0}",
            call.getString("waitingForStorage") ?: "Waiting for available storage",
            call.getString("downloads") ?: "Downloads",
            call.getString("cancel") ?: "Cancel",
            call.getString("moveToSd") ?: DownloadServiceHost.DEFAULT_MOVE_TO_SD,
            call.getString("moveToInternal") ?: DownloadServiceHost.DEFAULT_MOVE_TO_INTERNAL,
            call.getString("pausedBySystem") ?: DownloadServiceHost.DEFAULT_PAUSED_BY_SYSTEM)
    call.resolve()
  }

  /** The download queue (active, queued and failed items) for the Downloads screen. */
  @PluginMethod
  fun getDownloadQueue(call: PluginCall) {
    DownloadServiceHost.statusSnapshot(mainActivity) { statuses ->
      val array = JSArray()
      statuses.forEach { status ->
        array.put(JSObject(jacksonMapper.writeValueAsString(status.item))
                .put("waitingFor", status.waitingFor ?: JSONObject.NULL)
                .put("folderAccessLost", status.folderAccessLost)
                .put("isAuto", status.isAuto))
      }
      call.resolve(JSObject().put("items", array))
    }
  }

  /** Retry a stopped download: unfinished files restart from byte 0, finished files are kept. */
  @PluginMethod
  fun retryDownload(call: PluginCall) {
    val downloadItemId = call.getString("downloadItemId") ?: ""
    AbsLogger.info(tag, "User chose Retry for download $downloadItemId")
    DownloadServiceHost.retryExisting(mainActivity, downloadItemId) { result ->
      val errorCode =
              when (result) {
                DownloadServiceHost.ExistingDownloadResult.RETRIED -> null
                DownloadServiceHost.ExistingDownloadResult.NOT_FOUND -> "notFound"
                DownloadServiceHost.ExistingDownloadResult.ACTIVE -> "active"
                DownloadServiceHost.ExistingDownloadResult.FOLDER_ACCESS -> "folderAccess"
                DownloadServiceHost.ExistingDownloadResult.STORAGE_UNAVAILABLE -> "storageUnavailable"
                DownloadServiceHost.ExistingDownloadResult.SERVICE_START_FAILED -> "service"
                DownloadServiceHost.ExistingDownloadResult.HELD -> "held"
              }
      call.resolve(if (errorCode == null) JSObject() else JSObject().put("errorCode", errorCode))
    }
  }

  /** Cancel (active or queued) or Clear (failed) one download; see DownloadControls for what is deleted. */
  @PluginMethod
  fun removeDownload(call: PluginCall) {
    val downloadItemId = call.getString("downloadItemId") ?: ""
    val action = if (call.getString("action") == "clear") "Clear" else "Cancel"
    AbsLogger.info(tag, "User chose $action for download $downloadItemId")
    DownloadServiceHost.remove(mainActivity, downloadItemId, action) { result ->
      val errorCode =
              when (result) {
                DownloadItemManager.RemoveResult.REMOVED -> null
                DownloadItemManager.RemoveResult.NOT_FOUND -> "notFound"
                DownloadItemManager.RemoveResult.FINISHING -> "finishing"
                DownloadItemManager.RemoveResult.MOVING -> "moving"
              }
      call.resolve(if (errorCode == null) JSObject() else JSObject().put("errorCode", errorCode))
    }
  }

  /** Replays restored queue items when the frontend subscribes to download events. */
  @PluginMethod(returnType = PluginMethod.RETURN_NONE)
  override fun addListener(call: PluginCall) {
    super.addListener(call)
    if (call.getString("eventName") == "onDownloadItem" && ::downloadItemManager.isInitialized) {
      // Download threads change the queue while the frontend subscribes
      val queued = synchronized(downloadItemManager) { downloadItemManager.downloadItemQueue.toList() }
      queued.forEach { item ->
        notifyListeners("onDownloadItem", JSObject(jacksonMapper.writeValueAsString(item)))
      }
    }
  }

  @PluginMethod
  fun downloadLibraryItem(call: PluginCall) {
    val libraryItemId = call.data.getString("libraryItemId").toString()
    var episodeId = call.data.getString("episodeId").toString()
    if (episodeId == "null") episodeId = ""
    // Reject before queue lookup or API/path construction; do not rewrite IDs into colliding names.
    if (!DownloadIdentifiers.isSafe(libraryItemId) ||
            (episodeId.isNotEmpty() && !DownloadIdentifiers.isSafe(episodeId))) {
      resolveDownloadCall(call, "Invalid download identifier")
      return
    }
    val localFolderId = call.data.getString("localFolderId", "").toString()
    AbsLogger.info(tag, "Requested download for item $libraryItemId${if (episodeId.isEmpty()) "" else " / episode $episodeId"}")
    // A request from the user makes the episode the user's own, also when auto download already queued or started
    // it: automatic retention never removes it and the auto download network rules no longer hold it back
    val activeServerId = DeviceManager.serverConnectionConfig?.id
    if (episodeId.isNotEmpty() && !activeServerId.isNullOrBlank()) {
      try {
        AutoDownloadStore.claimManual(activeServerId, libraryItemId, episodeId)
      } catch (e: Exception) {
        AbsLogger.error(tag, "Could not record the manual download of $episodeId (${e.javaClass.simpleName}: ${e.message})")
      }
    }

    val downloadId = if (episodeId.isEmpty()) libraryItemId else "$libraryItemId-$episodeId"
    DownloadServiceHost.retryExisting(mainActivity, downloadId) { result ->
      when (result) {
        DownloadServiceHost.ExistingDownloadResult.RETRIED -> call.resolve()
        DownloadServiceHost.ExistingDownloadResult.ACTIVE -> {
          AbsLogger.info(tag, "Download already active for $downloadId")
          call.resolve(JSObject("{\"error\":\"Download already started for this media entity\"}"))
        }
        DownloadServiceHost.ExistingDownloadResult.SERVICE_START_FAILED ->
                call.resolve(JSObject().put("error", DownloadServiceHost.SERVICE_START_FAILED_MESSAGE))
        DownloadServiceHost.ExistingDownloadResult.FOLDER_ACCESS ->
                call.resolve(JSObject().put("error", "Folder access lost: re-select the download folder to retry").put("errorCode", "folderAccess"))
        DownloadServiceHost.ExistingDownloadResult.STORAGE_UNAVAILABLE ->
                call.resolve(JSObject().put("error", LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE).put("errorCode", "storageUnavailable"))
        DownloadServiceHost.ExistingDownloadResult.HELD ->
                call.resolve(JSObject().put("error", "Download authorization changed: reconnect the original server before retrying").put("errorCode", "held"))
        DownloadServiceHost.ExistingDownloadResult.NOT_FOUND -> {
          apiHandler.getLibraryItemWithProgress(libraryItemId, episodeId) { libraryItem ->
            if (libraryItem == null) {
              call.resolve(JSObject("{\"error\":\"Server request failed\"}"))
            } else if (!DownloadIdentifiers.matchesRequest(libraryItemId, libraryItem.id)) {
              // The configured server's response is a separate trust boundary from the request.
              resolveDownloadCall(call, "Server returned an invalid download identifier")
            } else {
              AbsLogger.info(tag, "Preparing download for ${libraryItem.id}")

              val explicitFolderId = localFolderId.takeUnless { it.isEmpty() || it == "null" }
              if (episodeId.isNotEmpty() && libraryItem.mediaType != "podcast") {
                call.resolve(JSObject("{\"error\":\"Invalid library item not a podcast\"}"))
              } else if (episodeId.isNotEmpty()) {
                val podcast = libraryItem.media as Podcast
                val episode = podcast.episodes?.find { it.id == episodeId }
                if (episode == null) {
                  call.resolve(JSObject("{\"error\":\"Invalid podcast episode not found\"}"))
                } else {
                  LibraryItemDownloadStarter.startLibraryItemDownload(mainActivity, libraryItem, explicitFolderId, episode) { error -> resolveDownloadCall(call, error) }
                }
              } else {
                LibraryItemDownloadStarter.startLibraryItemDownload(mainActivity, libraryItem, explicitFolderId, null) { error -> resolveDownloadCall(call, error) }
              }
            }
          }
        }
      }
    }
  }

  @OptIn(DelicateCoroutinesApi::class)
  @PluginMethod
  fun checkAutoDownloads(call: PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      try {
        // The app is in foreground now, so work a background worker could not start can run
        DownloadServiceHost.startServiceIfIdle(mainActivity)
        val status = AutoDownloadManager.startCheck(mainActivity, AutoDownloadManager.Trigger.APP, call.getBoolean("skipCleanup", false) == true)
        call.resolve(JSObject().put("status", status.jsValue))
      } catch (e: Exception) {
        AbsLogger.error(tag, "checkAutoDownloads failed (${e.javaClass.simpleName}: ${e.message})")
        call.reject(e.message ?: "checkAutoDownloads failed")
      }
    }
  }

  // Free space comes from getDownloadStorageStatus (the storage downloads go to); only what that lacks is here
  @OptIn(DelicateCoroutinesApi::class)
  @PluginMethod
  fun getDownloadsStorage(call: PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      try {
        call.resolve(
          JSObject()
            .put("usedBytes", AutoDeleteManager.usedBytes(mainActivity))
            .put("skippedForLimit", AutoDeleteManager.getSkippedForLimit(mainActivity))
        )
      } catch (e: Exception) {
        AbsLogger.error(tag, "getDownloadsStorage failed (${e.javaClass.simpleName}: ${e.message})")
        call.reject(e.message ?: "getDownloadsStorage failed")
      }
    }
  }

  @OptIn(DelicateCoroutinesApi::class)
  @PluginMethod
  fun getPodcastAutoDownload(call: PluginCall) {
    val libraryItemId = call.getString("libraryItemId")
    if (libraryItemId.isNullOrBlank()) return call.reject("libraryItemId is required")
    val serverConfigId = DeviceManager.serverConnectionConfig?.id ?: return call.reject("No active server connection")
    GlobalScope.launch(Dispatchers.IO) {
      try {
        val entry = AutoDownloadStore.get(serverConfigId, libraryItemId)
        call.resolve(JSObject().put("enabled", entry.enabled).put("keepCount", entry.keepCount ?: JSONObject.NULL).put("retention", entry.retention))
      } catch (e: Exception) {
        AbsLogger.error(tag, "getPodcastAutoDownload failed (${e.javaClass.simpleName}: ${e.message})")
        call.reject(e.message ?: "getPodcastAutoDownload failed")
      }
    }
  }

  /** Episode ids of the podcast that auto download fetched for the current server, to mark them in the episode list. */
  @OptIn(DelicateCoroutinesApi::class)
  @PluginMethod
  fun getAutoDownloadedEpisodes(call: PluginCall) {
    val libraryItemId = call.getString("libraryItemId")
    if (libraryItemId.isNullOrBlank()) return call.reject("libraryItemId is required")
    val serverConfigId = DeviceManager.serverConnectionConfig?.id ?: return call.resolve(JSObject().put("episodeIds", JSArray()))
    GlobalScope.launch(Dispatchers.IO) {
      val ids = try {
        AutoDownloadStore.get(serverConfigId, libraryItemId).autoDownloadedEpisodeIds.toList()
      } catch (e: Exception) {
        AbsLogger.error(tag, "getAutoDownloadedEpisodes failed (${e.javaClass.simpleName}: ${e.message})")
        emptyList()
      }
      call.resolve(JSObject().put("episodeIds", JSArray(ids)))
    }
  }

  @OptIn(DelicateCoroutinesApi::class)
  @PluginMethod
  fun setPodcastAutoDownload(call: PluginCall) {
    val libraryItemId = call.getString("libraryItemId")
    if (libraryItemId.isNullOrBlank()) return call.reject("libraryItemId is required")
    val enabled = call.getString("enabled")
    if (enabled != PodcastAutoDownloadEntry.ENABLED_DEFAULT && enabled != PodcastAutoDownloadEntry.ENABLED_ON && enabled != PodcastAutoDownloadEntry.ENABLED_OFF) {
      return call.reject("enabled must be DEFAULT, ON or OFF")
    }
    // Absent keeps the saved choice (older callers); anything else must be one of the two modes
    val retention = call.getString("retention")
    if (retention != null && retention != PodcastAutoDownloadEntry.RETENTION_AUTO && retention != PodcastAutoDownloadEntry.RETENTION_KEEP_ALL) {
      return call.reject("retention must be AUTO or KEEP_ALL")
    }
    val rawKeepCount = call.data.opt("keepCount")
    val keepCount: Int? = when {
      rawKeepCount == null || rawKeepCount == JSONObject.NULL -> null
      rawKeepCount !is Number -> return call.reject("keepCount must be a number or null")
      rawKeepCount.toDouble() != Math.rint(rawKeepCount.toDouble()) -> return call.reject("keepCount must be an integer")
      rawKeepCount.toDouble() < 0 || rawKeepCount.toDouble() > 999 -> return call.reject("keepCount must be between 0 and 999")
      else -> rawKeepCount.toInt()
    }
    val serverConfigId = DeviceManager.serverConnectionConfig?.id ?: return call.reject("No active server connection")
    GlobalScope.launch(Dispatchers.IO) {
      try {
        AutoDownloadStore.setSettings(serverConfigId, libraryItemId, enabled, keepCount, retention)
        call.resolve()
      } catch (e: Exception) {
        AbsLogger.error(tag, "setPodcastAutoDownload failed (${e.javaClass.simpleName}: ${e.message})")
        call.reject(e.message ?: "setPodcastAutoDownload failed")
        return@launch
      }
      try {
        val settings = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()
        val applies = enabled == PodcastAutoDownloadEntry.ENABLED_ON ||
                (enabled == PodcastAutoDownloadEntry.ENABLED_DEFAULT && settings.autoDownloadAllPodcasts)
        if (settings.autoDownloadEnabled && applies) {
          // Queues newly selected episodes only; removals wait for the next regular check
          val status = AutoDownloadManager.startCheck(mainActivity, AutoDownloadManager.Trigger.SETTINGS)
          android.util.Log.d(tag, "Auto download check after settings change: ${status.jsValue}")
        }
      } catch (e: Exception) {
        AbsLogger.error(tag, "Auto download check after settings change failed (${e.javaClass.simpleName}: ${e.message})")
      }
    }
  }

  private fun resolveDownloadCall(call: PluginCall, error: String?) {
    if (error == null) call.resolve()
    else call.resolve(JSObject().put("error", error))
  }
}
