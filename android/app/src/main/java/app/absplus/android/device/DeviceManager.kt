package app.absplus.android.device

import app.absplus.android.diagnostics.DLog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import app.absplus.android.MediaPlayerWidget
import app.absplus.android.data.*
import app.absplus.android.managers.DbManager
import app.absplus.android.managers.PlaybackRestoreStore
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.updateAppWidget

/** Interface for widget event handling. */
interface WidgetEventEmitter {
  /**
   * Called when the player state changes.
   * @param pns The PlayerNotificationService instance.
   */
  fun onPlayerChanged(pns: PlayerNotificationService)

  /**
   * Called with a playback position the player already sampled (progress sync while playing, or a
   * confirmed seek), so the widget's time and progress follow playback without polling the player.
   * @param positionMs absolute position in the item, in milliseconds
   */
  fun onPlaybackPosition(pns: PlayerNotificationService, positionMs: Long)

  /** Called when the player is closed. */
  fun onPlayerClosed()
}

/** Singleton object for managing device-related operations. */
object DeviceManager {
  const val tag = "DeviceManager"

  val dbManager: DbManager = DbManager()
  var deviceData: DeviceData = dbManager.getDeviceData()
  var serverConnectionConfig: ServerConnectionConfig? = null

  val serverConnectionConfigId get() = serverConnectionConfig?.id ?: ""
  val serverConnectionConfigName get() = serverConnectionConfig?.name ?: ""
  val serverConnectionConfigString get() = serverConnectionConfig?.name ?: "No server connection"
  val serverAddress
    get() = serverConnectionConfig?.address ?: ""
  val serverUserId
    get() = serverConnectionConfig?.userId ?: ""
  val token
    get() = serverConnectionConfig?.token ?: ""
  val serverVersion get() = serverConnectionConfig?.version ?: ""
  val isConnectedToServer
    get() = serverConnectionConfig != null

  var widgetUpdater: WidgetEventEmitter? = null

  init {
    DLog.d(tag, "Device Manager Singleton invoked")

    // Initialize new sleep timer settings and shake sensitivity added in v0.9.61
    if (deviceData.deviceSettings?.autoSleepTimerStartTime == null ||
                    deviceData.deviceSettings?.autoSleepTimerEndTime == null
    ) {
      deviceData.deviceSettings?.autoSleepTimerStartTime = "22:00"
      deviceData.deviceSettings?.autoSleepTimerEndTime = "06:00"
      deviceData.deviceSettings?.sleepTimerLength = 900000L
    }
    if (deviceData.deviceSettings?.shakeSensitivity == null) {
      deviceData.deviceSettings?.shakeSensitivity = ShakeSensitivitySetting.MEDIUM
    }
    // Initialize auto sleep timer auto rewind added in v0.9.64
    if (deviceData.deviceSettings?.autoSleepTimerAutoRewindTime == null) {
      deviceData.deviceSettings?.autoSleepTimerAutoRewindTime = 300000L // 5 minutes
    }
    // Initialize sleep timer almost done chime added in v0.9.81
    if (deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime == null) {
      deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime = false
    }

    // Language added in v0.9.69
    if (deviceData.deviceSettings?.languageCode == null) {
      deviceData.deviceSettings?.languageCode = "en-us"
    }

    if (deviceData.deviceSettings?.downloadUsingCellular == null) {
      deviceData.deviceSettings?.downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS
    }

    if (deviceData.deviceSettings?.streamingUsingCellular == null) {
      deviceData.deviceSettings?.streamingUsingCellular = StreamingUsingCellularSetting.ALWAYS
    }
    if (deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping == null) {
      deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping = 100
    }
    if (deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder == null) {
      deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder =
              AndroidAutoBrowseSeriesSequenceOrderSetting.ASC
    }
    // Podcast auto download added: Paper leaves missing primitives at 0/false, and interval 0 is never valid
    deviceData.deviceSettings?.let {
      if (it.autoDownloadIntervalHours == 0) {
        it.autoDownloadEnabled = false
        it.autoDownloadAllPodcasts = false
        it.autoDownloadKeepCount = 3
        it.autoDownloadWifiOnly = true
        it.autoDownloadChargingOnly = false
        it.autoDownloadIntervalHours = 6
        // Persist for AbsDatabase.getDeviceData, which reads Paper directly; never fail singleton init
        try {
          dbManager.saveDeviceData(deviceData)
        } catch (e: Exception) {
          Log.e(tag, "Failed to save backfilled auto download settings", e)
        }
      }
    }
    // Download storage added: Paper leaves the missing field null despite its non-null type
    deviceData.deviceSettings?.let {
      val normalized = DownloadStorageSetting.normalize(it.downloadStorage)
      if (normalized != it.downloadStorage) {
        it.downloadStorage = normalized
        try {
          dbManager.saveDeviceData(deviceData)
        } catch (e: Exception) {
          Log.e(tag, "Failed to save backfilled download storage setting", e)
        }
      }
    }
    // Auto delete added: the delay's default is 1 day and 0 is valid, so only "never saved" (null) is backfilled
    deviceData.deviceSettings?.let {
      if (it.autoDeleteDelayDays == null) {
        it.autoDeleteDelayDays = 1
        try {
          dbManager.saveDeviceData(deviceData)
        } catch (e: Exception) {
          Log.e(tag, "Failed to save backfilled auto delete settings", e)
        }
      }
    }
  }

  /**
   * Encodes the given ID to a Base64 string.
   * @param id The ID to encode.
   * @return The Base64 encoded string.
   */
  fun getBase64Id(id: String): String {
    return android.util.Base64.encodeToString(
            id.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
    )
  }

  /**
   * Retrieves the server connection configuration for the given ID.
   * @param id The ID of the server connection configuration.
   * @return The ServerConnectionConfig instance or null if not found.
   */
  fun getServerConnectionConfig(id: String?): ServerConnectionConfig? {
    return id?.let { deviceData.serverConnectionConfigs.find { it.id == id } }
  }

  /**
   * Check if the currently connected server version is >= compareVersion
   * Abs server only uses major.minor.patch
   * Note: Version is returned in Abs auth payloads starting v2.6.0
   * Note: Version is saved with the server connection config starting after v0.9.81
   *
   * @example
   * serverVersion=2.25.1
   * isServerVersionGreaterThanOrEqualTo("2.26.0") = false
   *
   * serverVersion=2.26.1
   * isServerVersionGreaterThanOrEqualTo("2.26.0") = true
   */
  fun isServerVersionGreaterThanOrEqualTo(compareVersion:String):Boolean {
    if (serverVersion == "") return false
    if (compareVersion == "") return true

    val serverVersionParts = serverVersion.split(".").map { it.toIntOrNull() ?: 0 }
    val compareVersionParts = compareVersion.split(".").map { it.toIntOrNull() ?: 0 }

    // Compare major, minor, and patch components
    for (i in 0 until maxOf(serverVersionParts.size, compareVersionParts.size)) {
      val serverVersionComponent = serverVersionParts.getOrElse(i) { 0 }
      val compareVersionComponent = compareVersionParts.getOrElse(i) { 0 }

      if (serverVersionComponent < compareVersionComponent) {
        return false // Server version is less than compareVersion
      } else if (serverVersionComponent > compareVersionComponent) {
        return true // Server version is greater than compareVersion
      }
    }

    return true // versions are equal in major, minor, and patch
  }

  /**
   * Checks the network connectivity status.
   * @param ctx The context to use for checking connectivity.
   * @return True if connected to the internet, false otherwise.
   */
  fun checkConnectivity(ctx: Context): Boolean {
    val connectivityManager =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    if (capabilities != null) {
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        DLog.i("Internet", "NetworkCapabilities.TRANSPORT_CELLULAR")
        return true
      } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        DLog.i("Internet", "NetworkCapabilities.TRANSPORT_WIFI")
        return true
      } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
        DLog.i("Internet", "NetworkCapabilities.TRANSPORT_ETHERNET")
        return true
      }
    }
    return false
  }

  /**
   * Sets the last playback session.
   * @param playbackSession The playback session to set.
   */
  fun setLastPlaybackSession(playbackSession: PlaybackSession) {
    deviceData.lastPlaybackSession = playbackSession
    dbManager.saveDeviceData(deviceData)
  }

  /**
   * Saves the latest known position of the last playback session, which the widget shows once no
   * player is live. Display only: restoration takes its position from server/local progress.
   * @return false (nothing saved) when the last session is a different one
   */
  fun updateLastPlaybackPosition(sessionId: String, currentTime: Double): Boolean {
    val last = deviceData.lastPlaybackSession ?: return false
    if (last.id != sessionId) return false
    last.currentTime = currentTime
    dbManager.saveDeviceData(deviceData)
    return true
  }

  /**
   * Initializes the widget updater.
   * @param context The context to use for initializing the widget updater.
   */
  fun initializeWidgetUpdater(context: Context) {
    DLog.d(tag, "Initializing widget updater")
    widgetUpdater =
            (object : WidgetEventEmitter {
              override fun onPlayerChanged(pns: PlayerNotificationService) = render(pns, null)

              override fun onPlaybackPosition(pns: PlayerNotificationService, positionMs: Long) = render(pns, positionMs)

              // sampledPositionMs: a position the player already sampled; null reads the live position
              private fun render(pns: PlayerNotificationService, sampledPositionMs: Long?) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, MediaPlayerWidget::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                if (sampledPositionMs != null && ids.isEmpty()) return

                // A position-only redraw keeps the play state the player intends: a seek briefly buffers
                // (isPlaying=false), which the play-state events also ignore
                val isPlaying = if (sampledPositionMs != null) pns.currentPlayer.playWhenReady else pns.currentPlayer.isPlaying
                val playbackSession = pns.getCurrentPlaybackSessionCopy() ?: deviceData.lastPlaybackSession
                val hasLiveSession = pns.currentPlaybackSession != null && !PlayerNotificationService.isClosed
                val showControls = hasLiveSession || (deviceData.lastPlaybackSession != null && PlaybackRestoreStore.isResumable(context))
                // Progress snapshot for the widget (read only; never throws into the player)
                val positionMs = if (hasLiveSession) sampledPositionMs ?: runCatching { pns.getCurrentTime() }.getOrNull() else null
                val durationMs = if (hasLiveSession) runCatching { pns.getDuration() }.getOrNull()?.takeIf { it > 0 } else null

                for (widgetId in ids) {
                  updateAppWidget(
                          context,
                          appWidgetManager,
                          widgetId,
                          playbackSession,
                          isPlaying,
                          showControls,
                          positionMs,
                          durationMs
                  )
                }
              }

              override fun onPlayerClosed() {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, MediaPlayerWidget::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                val showControls = deviceData.lastPlaybackSession != null && PlaybackRestoreStore.isResumable(context)
                for (widgetId in ids) {
                  updateAppWidget(
                          context,
                          appWidgetManager,
                          widgetId,
                          deviceData.lastPlaybackSession,
                          false,
                          showControls
                  )
                }
              }
            })
  }
}
