package app.absplus.android.plugins

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.absplus.android.MainActivity
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.media.MediaEventManager
import app.absplus.android.server.ApiHandler
import app.absplus.android.sync.ActiveServer
import app.absplus.android.sync.ServerReachability
import app.absplus.android.server.RefreshResult
import app.absplus.android.managers.SecureStorage
import app.absplus.android.widget.WidgetRenderer
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@CapacitorPlugin(name = "AbsDatabase")
class AbsDatabase : Plugin() {
  val tag = "AbsDatabase"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private lateinit var mainActivity: MainActivity
  private lateinit var apiHandler: ApiHandler
  private lateinit var secureStorage: SecureStorage

  data class LocalMediaProgressPayload(val value:List<LocalMediaProgress>)
  data class LocalLibraryItemsPayload(val value:List<LocalLibraryItem>)
  data class LocalFoldersPayload(val value:List<LocalFolder>)
  data class ServerConnConfigPayload(val id:String?, val index:Int, val name:String?, val userId:String, val username:String, var version:String, val token:String, val refreshToken:String?, val address:String?, val customHeaders:Map<String,String>?)

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)
    ApiHandler.absDatabaseNotifyListeners = ::notifyListeners

    secureStorage = SecureStorage(mainActivity)

    DeviceManager.dbManager.cleanLocalMediaProgress()
    DeviceManager.dbManager.cleanLocalLibraryItems(mainActivity)
  }

  /**
   * The selected theme changed: redraw placed home-screen widgets. Takes no arguments; the widget re-reads
   * the saved built-in theme id itself (WidgetTheme). Rendering only, no playback state is touched.
   */
  @PluginMethod
  fun refreshWidgets(call:PluginCall) {
    WidgetRenderer.renderAll(context)
    call.resolve()
  }

  @PluginMethod
  fun getDeviceData(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      val deviceData = DeviceManager.dbManager.getDeviceData()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(deviceData)))
    }
  }

  @PluginMethod
  fun getLocalFolders(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      val folders = DeviceManager.dbManager.getAllLocalFolders()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalFoldersPayload(folders))))
    }
  }

  @PluginMethod
  fun getLocalFolder(call:PluginCall) {
    val folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      DeviceManager.dbManager.getLocalFolder(folderId)?.let {
        val folderObj = jacksonMapper.writeValueAsString(it)
        call.resolve(JSObject(folderObj))
      } ?: call.resolve()
    }
  }

  @PluginMethod
  fun getLocalLibraryItem(call:PluginCall) {
    val id = call.getString("id", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(id)
      if (localLibraryItem == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
    }
  }

  @PluginMethod
  fun getLocalLibraryItemByLId(call:PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItemId)
      if (localLibraryItem == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
    }
  }

  @PluginMethod
  fun getLocalLibraryItemByLIdOrIno(call:PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    val ino = call.getString("ino")
    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(libraryItemId, ino)
      if (localLibraryItem == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
      }
    }
  }

  @PluginMethod
  fun getLocalLibraryItems(call:PluginCall) {
    val mediaType = call.getString("mediaType", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItems = DeviceManager.dbManager.getLocalLibraryItems(mediaType)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalLibraryItemsPayload(localLibraryItems))))
    }
  }

  @PluginMethod
  fun getLocalLibraryItemsInFolder(call:PluginCall) {
    val folderId = call.getString("folderId", "").toString()
    GlobalScope.launch(Dispatchers.IO) {
      val localLibraryItems = DeviceManager.dbManager.getLocalLibraryItemsInFolder(folderId)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalLibraryItemsPayload(localLibraryItems))))
    }
  }

  @PluginMethod
  fun setCurrentServerConnectionConfig(call:PluginCall) {
    Log.d(tag, "setCurrentServerConnectionConfig called")
    val serverConfigPayload = jacksonMapper.readValue<ServerConnConfigPayload>(call.data.toString())
    var serverConnectionConfig = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == serverConfigPayload.id }

    val userId =  serverConfigPayload.userId
    val username = serverConfigPayload.username
    val serverVersion = serverConfigPayload.version
    val accessToken = serverConfigPayload.token
    val refreshToken = serverConfigPayload.refreshToken // Refresh only sent after login or refresh

    GlobalScope.launch(Dispatchers.IO) {
      if (serverConnectionConfig == null) { // New Server Connection
        val serverAddress = call.getString("address", "").toString()

        // Create new server connection config
        val sscId = DeviceManager.getBase64Id("$serverAddress@$username")
        val sscIndex = DeviceManager.deviceData.serverConnectionConfigs.size

        // Store refresh token securely if provided
        val hasRefreshToken = if (!refreshToken.isNullOrEmpty()) {
          secureStorage.storeRefreshToken(sscId, refreshToken)
        } else {
          false
        }
        Log.d(tag, "Refresh token secured = $hasRefreshToken")

        serverConnectionConfig = ServerConnectionConfig(sscId, sscIndex, "$serverAddress ($username)", serverAddress, serverVersion, userId, username, accessToken, serverConfigPayload.customHeaders)

        // Add and save
        DeviceManager.deviceData.serverConnectionConfigs.add(serverConnectionConfig!!)
        DeviceManager.deviceData.lastServerConnectionConfigId = serverConnectionConfig?.id
        DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      } else {
        var shouldSave = false
        if (serverConnectionConfig?.username != username || serverConnectionConfig?.token != accessToken || serverConnectionConfig?.version != serverVersion) {
          serverConnectionConfig?.userId = userId
          serverConnectionConfig?.username = username
          serverConnectionConfig?.name = "${serverConnectionConfig?.address} (${serverConnectionConfig?.username})"
          serverConnectionConfig?.version = serverVersion
          serverConnectionConfig?.token = accessToken
          shouldSave = true
        }

        // Update refresh token if provided
        if (!refreshToken.isNullOrEmpty()) {
          val stored = secureStorage.storeRefreshToken(serverConnectionConfig!!.id, refreshToken)
          Log.d(tag, "Refresh token secured = $stored")
        }

        // Set last connection config
        if (DeviceManager.deviceData.lastServerConnectionConfigId != serverConfigPayload.id) {
          DeviceManager.deviceData.lastServerConnectionConfigId = serverConfigPayload.id
          shouldSave = true
        }

        if (shouldSave) DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      }

      // Requests sent for another server must not be retried with renewed credentials after the switch.
      if (DeviceManager.serverConnectionConfig?.id != serverConnectionConfig?.id) ApiHandler.invalidateAuthRequests()
      DeviceManager.serverConnectionConfig = serverConnectionConfig
      serverConnectionConfig?.id?.let { id ->
        ActiveServer.remember(id)
        ServerReachability.reportSignedIn(id)
      }
      call.resolve(JSObject(jacksonMapper.writeValueAsString(DeviceManager.serverConnectionConfig)))
    }
  }

  @PluginMethod
  fun removeServerConnectionConfig(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      val serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()

      // Remove refresh token if it exists
      secureStorage.removeRefreshToken(serverConnectionConfigId)

      DeviceManager.deviceData.serverConnectionConfigs = DeviceManager.deviceData.serverConnectionConfigs.filter { it.id != serverConnectionConfigId } as MutableList<ServerConnectionConfig>
      if (DeviceManager.deviceData.lastServerConnectionConfigId == serverConnectionConfigId) {
        DeviceManager.deviceData.lastServerConnectionConfigId = null
      }
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      if (DeviceManager.serverConnectionConfig?.id == serverConnectionConfigId) {
        DeviceManager.serverConnectionConfig = null
        ApiHandler.invalidateAuthRequests()
      }
      ActiveServer.forget(serverConnectionConfigId)
      call.resolve()
    }
  }

  @PluginMethod
  fun getRefreshToken(call:PluginCall) {
    val serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()

    GlobalScope.launch(Dispatchers.IO) {
      val refreshToken = secureStorage.getRefreshToken(serverConnectionConfigId)
      if (refreshToken != null) {
        val result = JSObject()
        result.put("refreshToken", refreshToken)
        call.resolve(result)
      } else {
        call.resolve()
      }
    }
  }

  @PluginMethod
  fun clearRefreshToken(call:PluginCall) {
    val serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()

    val refreshToken = secureStorage.removeRefreshToken(serverConnectionConfigId)
    val result = JSObject()
    result.put("success", refreshToken)
    call.resolve(result)
  }

  /**
   * Renews the access token of a saved server connection. Shares the refresh with every other caller in the app
   * (native requests, downloads), so the rotating refresh token is used once. Resolves with status "success" and
   * the access token, "rejected" (the server refused the refresh token; the session was cleared) or "transient"
   * (offline, timeout, server error; nothing changed).
   */
  @PluginMethod
  fun refreshAccessToken(call:PluginCall) {
    val serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()
    if (serverConnectionConfigId.isEmpty()) {
      call.resolve(JSObject().put("status", "transient"))
      return
    }
    apiHandler.refreshAuthTokens(serverConnectionConfigId) { result ->
      val ret = JSObject()
      when (result) {
        is RefreshResult.Success -> ret.put("status", "success").put("accessToken", result.accessToken)
        RefreshResult.Rejected -> ret.put("status", "rejected")
        RefreshResult.Transient -> ret.put("status", "transient")
      }
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun getAccessToken(call:PluginCall) {
    val serverConnectionConfigId = call.getString("serverConnectionConfigId", "").toString()
    val serverConnectionConfig = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == serverConnectionConfigId }
    val token = serverConnectionConfig?.token ?: ""
    val ret = JSObject()
    ret.put("token", token)
    call.resolve(ret)
  }

  @PluginMethod
  fun logout(call:PluginCall) {
    ApiHandler.invalidateAuthRequests()
    GlobalScope.launch(Dispatchers.IO) {
      DeviceManager.serverConnectionConfig = null
      DeviceManager.deviceData.lastServerConnectionConfigId = null
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      call.resolve()
    }
  }

  @PluginMethod
  fun getAllLocalMediaProgress(call:PluginCall) {
    GlobalScope.launch(Dispatchers.IO) {
      val localMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress()
      call.resolve(JSObject(jacksonMapper.writeValueAsString(LocalMediaProgressPayload(localMediaProgress))))
    }
  }

  @PluginMethod
  fun getLocalMediaProgressForServerItem(call:PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    var episodeId:String? = call.getString("episodeId", "").toString()
    if (episodeId == "") episodeId = null

    GlobalScope.launch(Dispatchers.IO) {
      val allLocalMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress()
      val localMediaProgress = allLocalMediaProgress.find { libraryItemId == it.libraryItemId && (episodeId == null || it.episodeId == episodeId) }

      if (localMediaProgress == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
      }
    }
  }

  @PluginMethod
  fun removeLocalMediaProgress(call:PluginCall) {
    val localMediaProgressId = call.getString("localMediaProgressId", "").toString()
    DeviceManager.dbManager.removeLocalMediaProgress(localMediaProgressId)
    call.resolve()
  }

  @PluginMethod
  fun syncLocalSessionsWithServer(call:PluginCall) {
    if (DeviceManager.serverConnectionConfig == null) {
      AbsLogger.error("AbsDatabase", "syncLocalSessionsWithServer: not connected to server")
      return call.resolve()
    }

    val savedSessions = DeviceManager.dbManager.getPlaybackSessions().filter { it.serverConnectionConfigId == DeviceManager.serverConnectionConfigId }

    if (savedSessions.isNotEmpty()) {
      apiHandler.sendSyncLocalSessions(savedSessions) { success, errorMsg ->
        if (!success) {
          call.resolve(JSObject("{\"error\":\"$errorMsg\"}"))
        } else {
          AbsLogger.info("AbsDatabase", "syncLocalSessionsWithServer: Finished sending local playback sessions to server. Removing ${savedSessions.size} saved sessions.")
          // Remove all local sessions
          savedSessions.forEach {
            DeviceManager.dbManager.removePlaybackSession(it.id)
          }
          call.resolve()
        }
      }
    } else {
      AbsLogger.info("AbsDatabase", "syncLocalSessionsWithServer: No saved local playback sessions to send to server.")
      call.resolve()
    }
  }

  @PluginMethod
  fun updateLocalMediaProgressFinished(call:PluginCall) {
    val localLibraryItemId = call.getString("localLibraryItemId", "").toString()
    var localEpisodeId:String? = call.getString("localEpisodeId", "").toString()
    if (localEpisodeId.isNullOrEmpty()) localEpisodeId = null
    val isFinished = call.getBoolean("isFinished", false) == true
    call.resolve(finishLocalProgress(localLibraryItemId, localEpisodeId, isFinished))
  }

  @PluginMethod
  fun updateLocalEbookProgress(call:PluginCall) {
    val localLibraryItemId = call.getString("localLibraryItemId", "").toString()
    val ebookLocation = call.getString("ebookLocation", "").toString()
    val ebookProgress = call.getDouble("ebookProgress") ?: 0.0

    val localMediaProgressId = localLibraryItemId
    var localMediaProgress = DeviceManager.dbManager.getLocalMediaProgress(localMediaProgressId)

    if (localMediaProgress == null) {
      Log.d(tag, "updateLocalEbookProgress Local Media Progress not found $localMediaProgressId - Creating new")
      val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
        ?: return call.resolve(JSObject("{\"error\":\"Library Item not found\"}"))

      val book = localLibraryItem.media as Book

      localMediaProgress = LocalMediaProgress(
        id = localMediaProgressId,
        localLibraryItemId = localLibraryItemId,
        localEpisodeId = null,
        duration = book.duration ?: 0.0,
        progress = 0.0,
        currentTime = 0.0,
        isFinished = false,
        ebookLocation = ebookLocation,
        ebookProgress = ebookProgress,
        lastUpdate = System.currentTimeMillis(),
        startedAt = 0L,
        finishedAt = null,
        serverConnectionConfigId = localLibraryItem.serverConnectionConfigId,
        serverAddress = localLibraryItem.serverAddress,
        serverUserId = localLibraryItem.serverUserId,
        libraryItemId = localLibraryItem.libraryItemId,
        episodeId = null)
    } else {
      localMediaProgress.updateEbookProgress(ebookLocation, ebookProgress)
    }

    // Save local media progress locally
    DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)

    val lmpstring = jacksonMapper.writeValueAsString(localMediaProgress)
    Log.d(tag, "updateLocalEbookProgress: Local Media Progress String $lmpstring")

    val jsobj = JSObject()
    jsobj.put("localMediaProgress", JSObject(lmpstring))
    call.resolve(jsobj)
  }

  @PluginMethod
  fun updateLocalTrackOrder(call:PluginCall) {
    val localLibraryItemId = call.getString("localLibraryItemId", "") ?: ""
    val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
    if (localLibraryItem == null) {
      call.resolve()
      return
    }

    val audioTracks = localLibraryItem.media.getAudioTracks() as MutableList

    val tracks:JSArray = call.getArray("tracks") ?: JSArray()
    Log.d(tag, "updateLocalTrackOrder $tracks")

    var index = 1
    var hasUpdates = false
    for (i in 0 until tracks.length()) {
      val track = tracks.getJSONObject(i)
      val localFileId = track.getString("localFileId")

      val existingTrack = audioTracks.find{ it.localFileId == localFileId }
      if (existingTrack != null) {
        Log.d(tag, "Found existing track ${existingTrack.localFileId} that has index ${existingTrack.index} should be index $index")
        if (existingTrack.index != index) hasUpdates = true
        existingTrack.index = index++
      } else {
        Log.e(tag, "Audio track with local file id not found")
      }
    }

    if (hasUpdates) {
      Log.d(tag, "Save library item track orders")
      localLibraryItem.media.setAudioTracks(audioTracks)
      DeviceManager.dbManager.saveLocalLibraryItem(localLibraryItem)
      call.resolve(JSObject(jacksonMapper.writeValueAsString(localLibraryItem)))
    } else {
      Log.d(tag, "No tracks need to be updated")
      call.resolve()
    }
  }

  @PluginMethod
  fun updateDeviceSettings(call:PluginCall) { // Returns device data
    Log.d(tag, "updateDeviceSettings called")
    val newDeviceSettings = jacksonMapper.readValue<DeviceSettings>(call.data.toString())

    Handler(Looper.getMainLooper()).post {
      DeviceManager.deviceData.deviceSettings = newDeviceSettings
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)

      // Updates playback actions for media notification (handles media control seek locking setting)
      if (mainActivity.isPlayerNotificationServiceInitialized()) {
        mainActivity.foregroundService.setMediaSessionConnectorPlaybackActions()
      }

      call.resolve(JSObject(jacksonMapper.writeValueAsString(DeviceManager.deviceData)))
    }
  }

  @PluginMethod
  fun getMediaItemHistory(call:PluginCall) { // Returns device data
    Log.d(tag, "getMediaItemHistory called")
    val mediaId = call.getString("mediaId") ?: ""

    GlobalScope.launch(Dispatchers.IO) {
      val mediaItemHistory = DeviceManager.dbManager.getMediaItemHistory(mediaId)
      if (mediaItemHistory == null) {
        call.resolve()
      } else {
        call.resolve(JSObject(jacksonMapper.writeValueAsString(mediaItemHistory)))
      }
    }
  }
}

/**
 * Marks the Paper progress of a downloaded item finished or not, creating it when missing. Only for copies without a
 * server: a server item's "finished" goes through userdata.db and its outbox (`AbsData.markFinished`), so nothing is
 * sent from here.
 */
internal fun finishLocalProgress(localLibraryItemId: String, localEpisodeId: String?, isFinished: Boolean): JSObject {
  val localMediaProgressId = if (localEpisodeId.isNullOrEmpty()) localLibraryItemId else "$localLibraryItemId-$localEpisodeId"
  var localMediaProgress = DeviceManager.dbManager.getLocalMediaProgress(localMediaProgressId)

  if (localMediaProgress == null) {
    val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
            ?: return JSObject("{\"error\":\"Library Item not found\"}")
    if (localLibraryItem.mediaType != "podcast" && !localEpisodeId.isNullOrEmpty()) {
      return JSObject("{\"error\":\"Invalid library item not a podcast\"}")
    }

    val duration: Double
    var podcastEpisode: PodcastEpisode? = null
    if (!localEpisodeId.isNullOrEmpty()) {
      podcastEpisode = (localLibraryItem.media as Podcast).episodes?.find { it.id == localEpisodeId }
              ?: return JSObject("{\"error\":\"Podcast episode not found\"}")
      duration = podcastEpisode.duration ?: 0.0
    } else {
      duration = (localLibraryItem.media as? Book)?.duration ?: 0.0
    }

    val now = System.currentTimeMillis()
    localMediaProgress = LocalMediaProgress(
            id = localMediaProgressId,
            localLibraryItemId = localLibraryItemId,
            localEpisodeId = localEpisodeId,
            duration = duration,
            progress = if (isFinished) 1.0 else 0.0,
            currentTime = 0.0,
            isFinished = isFinished,
            ebookLocation = null,
            ebookProgress = null,
            lastUpdate = now,
            startedAt = if (isFinished) now else 0L,
            finishedAt = if (isFinished) now else null,
            serverConnectionConfigId = localLibraryItem.serverConnectionConfigId,
            serverAddress = localLibraryItem.serverAddress,
            serverUserId = localLibraryItem.serverUserId,
            libraryItemId = localLibraryItem.libraryItemId,
            episodeId = podcastEpisode?.serverEpisodeId)
  } else {
    localMediaProgress.updateIsFinished(isFinished)
  }
  DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)

  return JSObject()
          .put("local", true)
          .put("server", false)
          .put("localMediaProgress", JSObject(jacksonObjectMapper().writeValueAsString(localMediaProgress)))
}
