package app.absplus.android.plugins

import app.absplus.android.diagnostics.DLog
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.absplus.android.MainActivity
import app.absplus.android.data.*
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.LibraryItemDownloadStarter
import app.absplus.android.managers.LocalItemLocks
import app.absplus.android.player.LocalAvailability
import app.absplus.android.player.LocalPlaybackAvailability
import app.absplus.android.media.MediaEventManager
import app.absplus.android.player.CastManager
import app.absplus.android.player.PlayerListener
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.server.ApiHandler
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import org.json.JSONObject

@CapacitorPlugin(name = "AbsAudioPlayer")
class AbsAudioPlayer : Plugin() {
  private val tag = "AbsAudioPlayer"
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  private lateinit var mainActivity: MainActivity
  private lateinit var apiHandler:ApiHandler
  var castManager:CastManager? = null

  lateinit var playerNotificationService: PlayerNotificationService

  private var isCastAvailable:Boolean = false

  // Track foreground state to avoid flooding WebView with events while backgrounded
  private var isInForeground: Boolean = true

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)

    try {
      initCastManager()
    } catch(e:Exception) {
      DLog.e(tag, "initCastManager exception ${e.printStackTrace()}")
    }

    val foregroundServiceReady : () -> Unit = {
      playerNotificationService = mainActivity.foregroundService

      playerNotificationService.clientEventEmitter = (object : PlayerNotificationService.ClientEventEmitter {
        override fun onPlaybackSession(playbackSession: PlaybackSession) {
          notifyListeners("onPlaybackSession", JSObject(jacksonMapper.writeValueAsString(playbackSession)))
        }

        override fun onPlaybackClosed() {
          emit("onPlaybackClosed", true)
        }

        override fun onPlayingUpdate(isPlaying: Boolean) {
          emit("onPlayingUpdate", isPlaying)
        }

        override fun onMetadata(metadata: PlaybackMetadata) {
          // Skip frequent metadata updates when app is backgrounded to prevent event queue buildup,
          // but always forward ENDED state so playlist queue advancement works in background
          if (!isInForeground && metadata.playerState != PlayerState.ENDED) return
          notifyListeners("onMetadata", JSObject(jacksonMapper.writeValueAsString(metadata)))
        }

        override fun onSleepTimerEnded(currentPosition: Long) {
          emit("onSleepTimerEnded", currentPosition)
        }

        override fun onSleepTimerSet(sleepTimeRemaining: Int, isAutoSleepTimer:Boolean) {
          // Skip sleep timer updates when app is backgrounded to prevent event queue buildup
          if (!isInForeground) return
          val ret = JSObject()
          ret.put("value", sleepTimeRemaining)
          ret.put("isAuto", isAutoSleepTimer)
          notifyListeners("onSleepTimerSet", ret)
        }

        override fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress) {
          // Skip progress updates when app is backgrounded to prevent event queue buildup
          if (!isInForeground) return
          notifyListeners("onLocalMediaProgressUpdate", JSObject(jacksonMapper.writeValueAsString(localMediaProgress)))
        }

        override fun onPlaybackFailed(errorMessage: String) {
          emit("onPlaybackFailed", errorMessage)
        }

        override fun onMediaPlayerChanged(mediaPlayer:String) {
          emit("onMediaPlayerChanged", mediaPlayer)
        }

        override fun onProgressSyncFailing() {
          emit("onProgressSyncFailing", "")
        }

        override fun onProgressSyncSuccess() {
          emit("onProgressSyncSuccess", "")
        }

        override fun onNetworkMeteredChanged(isUnmetered:Boolean) {
          emit("onNetworkMeteredChanged", isUnmetered)
        }

        override fun onMediaItemHistoryUpdated(mediaItemHistory:MediaItemHistory) {
          notifyListeners("onMediaItemHistoryUpdated", JSObject(jacksonMapper.writeValueAsString(mediaItemHistory)))
        }

        override fun onPlaybackSpeedChanged(playbackSpeed:Float) {
          emit("onPlaybackSpeedChanged", playbackSpeed)
        }
      })

      MediaEventManager.clientEventEmitter = playerNotificationService.clientEventEmitter

      pendingPlaybackStateCalls.forEach { resolvePlaybackState(it) }
      pendingPlaybackStateCalls.clear()
    }
    mainActivity.pluginCallback = foregroundServiceReady
  }

  private val pendingPlaybackStateCalls = mutableListOf<PluginCall>()

  /**
   * Lets a (re)started JS UI attach to a session the native player already has, e.g. after the
   * Activity was destroyed while playback continued, or after a widget Play restored a session.
   * Resolves with no playbackSession when nothing is prepared; never starts playback.
   */
  @PluginMethod
  fun getCurrentPlaybackState(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      if (::playerNotificationService.isInitialized) resolvePlaybackState(call)
      else pendingPlaybackStateCalls.add(call)
    }
  }

  private fun resolvePlaybackState(call: PluginCall) {
    val pns = playerNotificationService
    val ret = JSObject()
    val session = pns.currentPlaybackSession
    if (session != null) {
      ret.put("playbackSession", JSObject(jacksonMapper.writeValueAsString(session)))
      ret.put("isPlaying", pns.currentPlayer.isPlaying)
      ret.put("currentTime", pns.getCurrentTimeSeconds())
      ret.put("duration", session.getTotalDuration())
      ret.put("playbackRate", pns.currentPlayer.playbackParameters.speed.toDouble())
      ret.put("mediaPlayer", pns.getMediaPlayer())
      // What this phone last wrote to the server for this session, for the UI's refocus check (see ServerProgressAck)
      runCatching { pns.mediaProgressSyncer.serverProgressAck }.getOrNull()?.takeIf { it.sessionId == session.id }?.let {
        ret.put("serverProgressAck", JSObject(jacksonMapper.writeValueAsString(it)))
      }
    }
    val queue = JSObject()
    queue.put("items", JSArray(jacksonMapper.writeValueAsString(pns.playlistQueue)))
    queue.put("currentIndex", pns.playlistQueueIndex)
    ret.put("queue", queue)
    DLog.i("PlaybackRestore", "UI requested native playback state | hasSession=${session != null} | isPlaying=${session != null && pns.currentPlayer.isPlaying} | queue=${pns.playlistQueue.size}")
    call.resolve(ret)
  }

  fun emit(evtName: String, value: Any) {
    val ret = JSObject()
    ret.put("value", value)
    notifyListeners(evtName, ret)
  }

  override fun handleOnDestroy() {
    castManager?.detach()
    super.handleOnDestroy()
  }

  override fun handleOnPause() {
    super.handleOnPause()
    isInForeground = false
  }

  override fun handleOnResume() {
    super.handleOnResume()
    isInForeground = true

    // Send current state to UI after resume to sync up (with small delay to let WebView fully resume)
    if (::playerNotificationService.isInitialized && playerNotificationService.currentPlaybackSession != null) {
      Handler(Looper.getMainLooper()).postDelayed({
        playerNotificationService.sendClientMetadata(PlayerState.READY)
        playerNotificationService.sleepTimerManager.sendCurrentSleepTimerState()
        playerNotificationService.mediaProgressSyncer.currentLocalMediaProgress?.let {
          playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
        }
      }, 100)
    }
  }

  private fun initCastManager() {
    val googleApi = GoogleApiAvailability.getInstance()
    val statusCode = googleApi.isGooglePlayServicesAvailable(mainActivity)

    if (statusCode != ConnectionResult.SUCCESS) {
        if (statusCode == ConnectionResult.SERVICE_MISSING) {
          DLog.w(tag, "initCastManager: Google Api Missing")
        } else if (statusCode == ConnectionResult.SERVICE_DISABLED) {
          DLog.w(tag, "initCastManager: Google Api Disabled")
        } else if (statusCode == ConnectionResult.SERVICE_INVALID) {
          DLog.w(tag, "initCastManager: Google Api Invalid")
        } else if (statusCode == ConnectionResult.SERVICE_UPDATING) {
          DLog.w(tag, "initCastManager: Google Api Updating")
        } else if (statusCode == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED) {
          DLog.w(tag, "initCastManager: Google Api Update Required")
        }
        return
    }

    val connListener = object: CastManager.ChromecastListener() {
      override fun onReceiverAvailableUpdate(available: Boolean) {
        DLog.d(tag, "ChromecastListener: CAST Receiver Update Available $available")
        isCastAvailable = available
        emit("onCastAvailableUpdate", available)
      }

      override fun onSessionRejoin(jsonSession: JSONObject?) {
        DLog.d(tag, "ChromecastListener: CAST onSessionRejoin")
      }

      override fun onMediaLoaded(jsonMedia: JSONObject?) {
        DLog.d(tag, "ChromecastListener: CAST onMediaLoaded")
      }

      override fun onMediaUpdate(jsonMedia: JSONObject?) {
        DLog.d(tag, "ChromecastListener: CAST onMediaUpdate")
      }

      override fun onSessionUpdate(jsonSession: JSONObject?) {
        DLog.d(tag, "ChromecastListener: CAST onSessionUpdate")
      }

      override fun onSessionEnd(jsonSession: JSONObject?) {
        DLog.d(tag, "ChromecastListener: CAST onSessionEnd")
      }

      override fun onMessageReceived(p0: CastDevice, p1: String, p2: String) {
        DLog.d(tag, "ChromecastListener: CAST onMessageReceived")
      }
    }

    castManager = CastManager(mainActivity)
    castManager?.startRouteScan(connListener)
  }

  @PluginMethod
  fun prepareLibraryItem(call: PluginCall) {
    val libraryItemId = call.getString("libraryItemId", "").toString()
    val episodeId = call.getString("episodeId", "").toString()
    val playWhenReady = call.getBoolean("playWhenReady") == true
    val playbackRate = call.getFloat("playbackRate",1f) ?: 1f
    val startTimeOverride = call.getDouble("startTime")

    AbsLogger.info("AbsAudioPlayer", "prepareLibraryItem: lid=$libraryItemId, startTimeOverride=$startTimeOverride, playbackRate=$playbackRate")

    if (libraryItemId.isEmpty()) {
      DLog.e(tag, "Invalid call to play library item no library item id")
      return call.resolve(JSObject("{\"error\":\"Invalid request\"}"))
    }

    if (libraryItemId.startsWith("local")) { // Play local media item
      var availability: LocalAvailability? = null
      var episode: PodcastEpisode? = null
      val localLibraryItem = LocalItemLocks.readForPlayback(libraryItemId, { DeviceManager.dbManager.getLocalLibraryItem(libraryItemId) }) {
        if (episodeId.isNotEmpty()) episode = (it.media as Podcast).episodes?.find { ep -> ep.id == episodeId }
        availability = LocalPlaybackAvailability.check(it, AndroidVolumeProbe(mainActivity)) {
          (episodeId.isEmpty() || episode != null) && it.hasTracks(mainActivity, episode)
        }
        availability == LocalAvailability.OK
      }
      if (localLibraryItem == null) {
        if (availability == null) {
          // Must resolve here - an unresolved call leaves AudioPlayerContainer.vue's
          // preparingPlayback guard stuck true, permanently blocking all further playback.
          DLog.e(tag, "prepareLibraryItem: Local library item not found $libraryItemId")
          return call.resolve(JSObject("{\"error\":\"Local library item not found\"}"))
        }
        if (availability == LocalAvailability.STORAGE_UNAVAILABLE) {
          DLog.e(tag, "prepareLibraryItem: SD card not available for $libraryItemId")
          return call.resolve(JSObject().put("error", LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE))
        }
        if (episodeId.isNotEmpty() && episode == null) {
          DLog.e(tag, "prepareLibraryItem: Podcast episode not found $episodeId")
          return call.resolve(JSObject("{\"error\":\"Podcast episode not found\"}"))
        }
        return call.resolve(JSObject("{\"error\":\"No audio files found on device. Download book again to fix.\"}"))
      }
      localLibraryItem.let {

        Handler(Looper.getMainLooper()).post {
          val playbackSession = it.getPlaybackSession(episode, playerNotificationService.getDeviceInfo())
          // Concise on purpose: the serialized item contains file paths, SAF URIs and the server connection id
          DLog.d(tag, "prepareLibraryItem: downloaded item ${it.id} (server item ${it.libraryItemId ?: "none"}${episode?.let { e -> ", episode " + e.id } ?: ""}) mediaType=${it.mediaType} title=\"${playbackSession.displayTitle}\" tracks=${playbackSession.audioTracks.size} start=${playbackSession.currentTime}s duration=${playbackSession.getTotalDuration()}s rate=$playbackRate playWhenReady=$playWhenReady")
          if (startTimeOverride != null) {
            DLog.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
            playbackSession.currentTime = startTimeOverride
          }

          if (playerNotificationService.mediaProgressSyncer.listeningTimerRunning) { // If progress syncing then first stop before preparing next
            playerNotificationService.mediaProgressSyncer.stop {
              DLog.d(tag, "Media progress syncer was already syncing - stopped")
              PlayerListener.lazyIsPlaying = false

              Handler(Looper.getMainLooper()).post { // TODO: This was needed again which is probably a design a flaw
                playerNotificationService.preparePlayer(
                  playbackSession,
                  playWhenReady,
                  playbackRate
                )
              }
            }
          } else {
            playerNotificationService.mediaProgressSyncer.reset()
            playerNotificationService.preparePlayer(playbackSession, playWhenReady, playbackRate)
          }
        }
        return call.resolve(JSObject())
      }
    } else { // Play library item from server
      val playItemRequestPayload = playerNotificationService.getPlayItemRequestPayload(false)
      Handler(Looper.getMainLooper()).post {
        playerNotificationService.mediaProgressSyncer.stop {
          apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
            if (it == null) {
              call.resolve(JSObject("{\"error\":\"Server play request failed\"}"))
            } else {
              if (startTimeOverride != null) {
                DLog.d(tag, "prepareLibraryItem: Using start time override $startTimeOverride")
                it.currentTime = startTimeOverride
              }

              Handler(Looper.getMainLooper()).post {
                DLog.d(tag, "prepareLibraryItem: streamed session ${it.id} for item ${it.libraryItemId}${it.episodeId?.let { e -> ", episode " + e } ?: ""} mediaType=${it.mediaType} playMethod=${it.playMethod} title=\"${it.displayTitle}\" tracks=${it.audioTracks.size} start=${it.currentTime}s duration=${it.getTotalDuration()}s rate=$playbackRate playWhenReady=$playWhenReady")
                PlayerListener.lazyIsPlaying = false
                playerNotificationService.preparePlayer(it, playWhenReady, playbackRate)
              }
              call.resolve(JSObject(jacksonMapper.writeValueAsString(it)))
            }
          }
        }
      }
    }
  }

  @PluginMethod
  fun getCurrentTime(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val currentTime = playerNotificationService.getCurrentTimeSeconds()
      val bufferedTime = playerNotificationService.getBufferedTimeSeconds()
      val ret = JSObject()
      ret.put("value", currentTime)
      ret.put("bufferedTime", bufferedTime)
      call.resolve(ret)
    }
  }

  @PluginMethod
  fun pausePlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.pause()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPlayer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.play()
      call.resolve()
    }
  }

  @PluginMethod
  fun playPause(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val playing = playerNotificationService.playPause()
      call.resolve(JSObject("{\"playing\":$playing}"))
    }
  }

  @PluginMethod
  fun seek(call: PluginCall) {
    val time: Double = call.getDouble("value", 0.0) ?: 0.0 // Value in seconds, fractional
    DLog.d(tag, "seek action to $time")
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.seekPlayer((time * 1000L).toLong())
      call.resolve()
    }
  }

  @PluginMethod
  fun seekForward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.seekForward(amount * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun seekBackward(call: PluginCall) {
    val amount:Int = call.getInt("value", 0) ?: 0 // Value in seconds
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.seekBackward(amount * 1000L) // convert to ms
      call.resolve()
    }
  }

  @PluginMethod
  fun setPlaybackSpeed(call: PluginCall) {
    val playbackSpeed:Float = call.getFloat("value", 1.0f) ?: 1.0f

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.setPlaybackSpeed(playbackSpeed)
      call.resolve()
    }
  }

  @PluginMethod
  fun closePlayback(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.closePlayback()
      call.resolve()
    }
  }

  @PluginMethod
  fun setSleepTimer(call: PluginCall) {
    val time:Long = call.getString("time", "360000")!!.toLong()
    val isChapterTime:Boolean = call.getBoolean("isChapterTime", false) == true

    Handler(Looper.getMainLooper()).post {
        val playbackSession: PlaybackSession? = playerNotificationService.mediaProgressSyncer.currentPlaybackSession ?: playerNotificationService.currentPlaybackSession
        val success:Boolean = playerNotificationService.sleepTimerManager.setManualSleepTimer(playbackSession?.id ?: "", time, isChapterTime)
        val ret = JSObject()
        ret.put("success", success)
        call.resolve(ret)
    }
  }

  @PluginMethod
  fun getSleepTimerTime(call: PluginCall) {
    val time = playerNotificationService.sleepTimerManager.getSleepTimerTime()
    val ret = JSObject()
    ret.put("value", time)
    call.resolve(ret)
  }

  @PluginMethod
  fun increaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.increaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun decreaseSleepTime(call: PluginCall) {
    val time:Long = call.getString("time", "300000")!!.toLong()

    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.decreaseSleepTime(time)
      val ret = JSObject()
      ret.put("success", true)
      call.resolve()
    }
  }

  @PluginMethod
  fun cancelSleepTimer(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.sleepTimerManager.cancelSleepTimer()
    }
    call.resolve()
  }

  @PluginMethod
  fun requestSession(call: PluginCall) {
    // Need to make sure the player service has been started
    DLog.d(tag, "CAST REQUEST SESSION PLUGIN")
    call.resolve()
    if (castManager == null) {
      DLog.e(tag, "Cast Manager not initialized")
      return
    }
    castManager?.requestSession(playerNotificationService, object : CastManager.RequestSessionCallback() {
      override fun onError(errorCode: Int) {
        DLog.e(tag, "CAST REQUEST SESSION CALLBACK ERROR $errorCode")
      }

      override fun onCancel() {
        DLog.d(tag, "CAST REQUEST SESSION ON CANCEL")
      }

      override fun onJoin(jsonSession: JSONObject?) {
        DLog.d(tag, "CAST REQUEST SESSION ON JOIN")
      }
    })
  }

  @PluginMethod
  fun setPlaylistQueue(call: PluginCall) {
    val items = call.getArray("items") ?: run { call.resolve(); return }
    val currentIndex = call.getInt("currentIndex", 0) ?: 0
    val queue = mutableListOf<PlayerNotificationService.PlaylistQueueItem>()
    for (i in 0 until items.length()) {
      val item = items.getJSONObject(i)
      queue.add(PlayerNotificationService.PlaylistQueueItem(
        libraryItemId = item.getString("libraryItemId"),
        episodeId = if (item.has("episodeId") && !item.isNull("episodeId")) item.getString("episodeId") else null
      ))
    }
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.playlistQueue = queue
      playerNotificationService.playlistQueueIndex = currentIndex
      DLog.d(tag, "setPlaylistQueue: ${queue.size} items, currentIndex=$currentIndex")
      call.resolve()
    }
  }

  @PluginMethod
  fun getPlaylistQueue(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      val result = JSObject()
      result.put("items", JSArray(jacksonMapper.writeValueAsString(playerNotificationService.playlistQueue)))
      result.put("currentIndex", playerNotificationService.playlistQueueIndex)
      call.resolve(result)
    }
  }

  @PluginMethod
  fun clearPlaylistQueue(call: PluginCall) {
    Handler(Looper.getMainLooper()).post {
      playerNotificationService.playlistQueue = emptyList()
      playerNotificationService.playlistQueueIndex = -1
      DLog.d(tag, "clearPlaylistQueue: cleared")
      call.resolve()
    }
  }

  @PluginMethod
  fun getIsCastAvailable(call: PluginCall) {
    val jsobj = JSObject()
    jsobj.put("value", isCastAvailable)
    call.resolve(jsobj)
  }
}
