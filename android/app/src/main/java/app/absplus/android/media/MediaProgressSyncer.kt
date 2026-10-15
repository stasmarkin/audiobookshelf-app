package app.absplus.android.media

import app.absplus.android.diagnostics.DLog
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.player.ServerProgressAck
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
import app.absplus.android.sync.SyncEngine
import java.util.*
import kotlin.concurrent.schedule

data class MediaProgressSyncData(
        var timeListened: Long, // seconds
        var duration: Double, // seconds
        var currentTime: Double // seconds
)

data class SyncResult(
        var serverSyncAttempted: Boolean,
        var serverSyncSuccess: Boolean?,
        var serverSyncMessage: String?
)

class MediaProgressSyncer(
        val playerNotificationService: PlayerNotificationService,
        apiHandler: () -> ApiHandler
) {
  // Created on the first request: its secure storage is not needed to store a tick
  private val apiHandler by lazy(apiHandler)
  private val tag = "MediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning: Boolean = false

  internal var lastSyncTime: Long = 0
    private set
  private var failedSyncs: Int = 0

  var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
  var currentLocalMediaProgress: LocalMediaProgress? = null

  // What this phone last wrote to the server for the streamed session it holds (see ServerProgressAck)
  @Volatile var serverProgressAck: ServerProgressAck? = null
    private set

  /** A streamed session prepared from the server: its position is the server's progress until this phone syncs */
  @Synchronized
  fun noteServerSessionStart(playbackSession: PlaybackSession) {
    if (playbackSession.isLocal || serverProgressAck?.sessionId == playbackSession.id) return
    serverProgressAck = ServerProgressAck.sessionStart(playbackSession.id, playbackSession.currentTime)
  }

  @Synchronized
  private fun noteServerSync(sessionId: String, currentTime: Double, acknowledged: Boolean) {
    serverProgressAck = serverProgressAck?.let { if (acknowledged) it.acknowledged(sessionId, currentTime, System.currentTimeMillis()) else it.sent(sessionId, currentTime) }
  }

  private val currentDisplayTitle
    get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal
    get() = currentPlaybackSession?.isLocal == true
  val currentSessionId
    get() = currentPlaybackSession?.id ?: ""
  private val currentPlaybackDuration
    get() = currentPlaybackSession?.duration ?: 0.0

  fun start(playbackSession: PlaybackSession) {
    if (listeningTimerRunning) {
      DLog.d(tag, "start: Timer already running for $currentDisplayTitle")
      if (playbackSession.id != currentSessionId) {
        DLog.d(tag, "Playback session changed, reset timer")
        currentLocalMediaProgress = null
        listeningTimerTask?.cancel()
        lastSyncTime = 0L
        DLog.d(tag, "start: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0
      } else {
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      currentLocalMediaProgress = null
    }

    listeningTimerRunning = true
    lastSyncTime = System.currentTimeMillis()
    currentPlaybackSession = playbackSession.clone()
    DLog.d(
            tag,
            "start: init last sync time $lastSyncTime with playback session id=${currentPlaybackSession?.id}"
    )

    listeningTimerTask =
            Timer("ListeningTimer", false).schedule(15000L, 15000L) {
              Handler(Looper.getMainLooper()).post() {
                if (playerNotificationService.currentPlayer.isPlaying) {
                  // Set auto sleep timer if enabled and within start/end time
                  playerNotificationService.sleepTimerManager.checkAutoSleepTimer()

                  // Only sync with server on unmetered connection every 15s OR sync with server if
                  // last sync time is >= 60s
                  val shouldSyncServer =
                          PlayerNotificationService.isUnmeteredNetwork ||
                                  System.currentTimeMillis() - lastSyncTime >=
                                          METERED_CONNECTION_SYNC_INTERVAL

                  val currentTime = playerNotificationService.getCurrentTimeSeconds()
                  if (currentTime > 0) {
                    sync(shouldSyncServer, currentTime) { syncResult ->
                      DLog.d(tag, "Sync complete")

                      currentPlaybackSession?.let { playbackSession ->
                        MediaEventManager.saveEvent(playbackSession, syncResult)
                      }
                    }
                  }
                }
              }
            }
  }

  fun play(playbackSession: PlaybackSession) {
    DLog.d(tag, "play ${playbackSession.displayTitle}")
    MediaEventManager.playEvent(playbackSession)

    start(playbackSession)
  }

  fun stop(shouldSync: Boolean? = true, cb: () -> Unit) {
    if (!listeningTimerRunning) {
      reset()
      return cb()
    }

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    DLog.d(tag, "stop: Stopping listening for $currentDisplayTitle")

    val currentTime =
            if (shouldSync == true) playerNotificationService.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0) { // Current time should always be > 0 on stop
      sync(true, currentTime) { syncResult ->
        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.stopEvent(playbackSession, syncResult)
        }

        reset()
        cb()
      }
    } else {
      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.stopEvent(playbackSession, null)
      }

      reset()
      cb()
    }
  }

  fun pause(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    DLog.d(tag, "pause: Pausing progress syncer for $currentDisplayTitle")
    DLog.d(tag, "pause: Last sync time $lastSyncTime")

    val currentTime = playerNotificationService.getCurrentTimeSeconds()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      sync(true, currentTime) { syncResult ->
        lastSyncTime = 0L
        DLog.d(tag, "pause: Set last sync time 0 $lastSyncTime")
        failedSyncs = 0

        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.pauseEvent(playbackSession, syncResult)
        }

        cb()
      }
    } else {
      lastSyncTime = 0L
      DLog.d(tag, "pause: Set last sync time 0 $lastSyncTime (current time < 0)")
      failedSyncs = 0

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.pauseEvent(playbackSession, null)
      }

      cb()
    }
  }

  fun finished(cb: () -> Unit) {
    if (!listeningTimerRunning) return

    listeningTimerTask?.cancel()
    listeningTimerTask = null
    listeningTimerRunning = false
    DLog.d(tag, "finished: Stopping listening for $currentDisplayTitle")

    sync(true, currentPlaybackSession?.duration ?: 0.0) { syncResult ->
      reset()

      currentPlaybackSession?.let { playbackSession ->
        MediaEventManager.finishedEvent(playbackSession, syncResult)
      }

      cb()
    }
  }

  fun seek() {
    currentPlaybackSession?.currentTime = playerNotificationService.getCurrentTimeSeconds()
    DLog.d(tag, "seek: $currentDisplayTitle, currentTime=${currentPlaybackSession?.currentTime}")

    if (currentPlaybackSession == null) {
      DLog.e(tag, "seek: Playback session not set")
      return
    }

    // The widget shows the confirmed seek position right away (also while paused, e.g. its own jump keys)
    playerNotificationService.showWidgetPosition(currentPlaybackSession?.id, currentPlaybackSession?.currentTime)
    currentPlaybackSession?.let { recordSeek(it, it.currentTime, playerNotificationService.currentPlaybackSession?.id) }

    MediaEventManager.seekEvent(currentPlaybackSession!!, null)
  }

  // Currently unused
  fun syncFromServerProgress(mediaProgress: MediaProgress) {
    currentPlaybackSession?.let {
      it.updatedAt = mediaProgress.lastUpdate
      it.currentTime = mediaProgress.currentTime

      MediaEventManager.syncEvent(
              mediaProgress,
              "Received from server get media progress request while playback session open"
      )
      saveLocalProgress(it)
    }
  }

  fun sync(shouldSyncServer: Boolean, currentTime: Double, cb: (SyncResult?) -> Unit) {
    if (lastSyncTime <= 0) {
      DLog.e(tag, "Last sync time is not set $lastSyncTime")
      return cb(null)
    }

    val diffSinceLastSync = System.currentTimeMillis() - lastSyncTime
    if (diffSinceLastSync < 1000L) {
      return cb(null)
    }
    val listeningTimeToAdd = diffSinceLastSync / 1000L

    // Captured now, not read live: the syncer may move on to the next queue item before the network response arrives
    val syncedTitle = currentDisplayTitle

    val syncData = MediaProgressSyncData(listeningTimeToAdd, currentPlaybackDuration, currentTime)
    currentPlaybackSession?.syncData(syncData)

    if (currentPlaybackSession?.progress?.isNaN() == true) {
      DLog.e(
              tag,
              "Current Playback Session invalid progress ${currentPlaybackSession?.progress} | Current Time: ${currentPlaybackSession?.currentTime} | Duration: ${currentPlaybackSession?.getTotalDuration()}"
      )
      return cb(null)
    }

    // Same sampled position, kept as the widget's last-known display position
    playerNotificationService.rememberPlaybackPosition(currentPlaybackSession?.id, currentTime)

    val hasNetworkConnection = DeviceManager.checkConnectivity(playerNotificationService)

    currentPlaybackSession?.let { saveTick(it, currentTime) }

    if (currentIsLocal) {
      currentPlaybackSession?.let {
        lastSyncTime = System.currentTimeMillis()

        DLog.d(
                tag,
                // The config id encodes the server address and username, so only whether one is set is logged
                "Sync local device (server connection set=${DeviceManager.serverConnectionConfig != null}, same server as item=${it.serverConnectionConfigId != null && DeviceManager.serverConnectionConfig?.id == it.serverConnectionConfigId})"
        )
        AbsLogger.info("MediaProgressSyncer", "sync: Saved local progress (title: \"$syncedTitle\") (currentTime: $currentTime) (session id: ${it.id})")

        // Local library item is linked to a server library item
        // Send sync to server also if connected to this server and local item belongs to this
        // server
        val isConnectedToSameServer = it.serverConnectionConfigId != null && DeviceManager.serverConnectionConfig?.id == it.serverConnectionConfigId
        if (hasNetworkConnection &&
                        shouldSyncServer &&
                        !it.libraryItemId.isNullOrEmpty() &&
                        isConnectedToSameServer
        ) {
          apiHandler.sendLocalProgressSync(it) { syncSuccess, errorMsg ->
            if (syncSuccess) {
              failedSyncs = 0
              playerNotificationService.alertSyncSuccess()
              DeviceManager.dbManager.removePlaybackSession(it.id) // Remove session from db
              AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced local progress (title: \"$syncedTitle\") (currentTime: $currentTime) (session id: ${it.id})")
            } else {
              failedSyncs++
              if (failedSyncs == 2) {
                playerNotificationService.alertSyncFailing() // Show alert in client
                failedSyncs = 0
              }
              AbsLogger.error("MediaProgressSyncer", "sync: Local progress sync failed (count: $failedSyncs) (title: \"$syncedTitle\") (currentTime: $currentTime) (session id: ${it.id}) (${DeviceManager.serverConnectionConfigName})")
            }

            cb(SyncResult(true, syncSuccess, errorMsg))
          }
        } else {
          AbsLogger.info("MediaProgressSyncer", "sync: Not sending local progress to server (title: \"$syncedTitle\") (currentTime: $currentTime) (session id: ${it.id}) (hasNetworkConnection: $hasNetworkConnection) (isConnectedToSameServer: $isConnectedToSameServer)")
          cb(SyncResult(false, null, null))
        }
      }
    } else if (hasNetworkConnection && shouldSyncServer) {
      AbsLogger.info("MediaProgressSyncer", "sync: Sending progress sync to server (title: \"$syncedTitle\") (currentTime: $currentTime) (session id: ${currentSessionId}) (${DeviceManager.serverConnectionConfigName})")

      val sentSessionId = currentSessionId
      noteServerSync(sentSessionId, currentTime, false)
      apiHandler.sendProgressSync(sentSessionId, syncData) { syncSuccess, errorMsg ->
        serverSyncAnswered(sentSessionId, syncedTitle, currentTime, syncSuccess)
        cb(SyncResult(true, syncSuccess, errorMsg))
      }
    } else {
      AbsLogger.info("MediaProgressSyncer", "sync: Not sending progress to server (title: \"$syncedTitle\") (currentTime: $currentTime) (session id: $currentSessionId) (${DeviceManager.serverConnectionConfigName}) (hasNetworkConnection: $hasNetworkConnection)")
      cb(SyncResult(false, null, null))
    }
  }

  /**
   * The answer to `/sync` of [sentSessionId]. Everything is about the session that was sent: by now the player may
   * have moved on to the next queue item, and removing that one instead would leave the sent session to be sent
   * again through `local-all`.
   */
  internal fun serverSyncAnswered(sentSessionId: String, syncedTitle: String, currentTime: Double, syncSuccess: Boolean) {
    // The sync clock and failure count belong to the session playing now: a late answer for the previous item must not
    // reset them (its listening time would be counted short)
    val stillCurrent = sentSessionId == currentSessionId
    if (syncSuccess) {
      noteServerSync(sentSessionId, currentTime, true)
      AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced progress (title: \"$syncedTitle\") (currentTime: $currentTime) (session id: $sentSessionId) (${DeviceManager.serverConnectionConfigName})")

      if (stillCurrent) {
        failedSyncs = 0
        playerNotificationService.alertSyncSuccess()
        lastSyncTime = System.currentTimeMillis()
      }
      DeviceManager.dbManager.removePlaybackSession(sentSessionId) // Remove session from db
    } else {
      if (stillCurrent) {
        failedSyncs++
        if (failedSyncs == 2) {
          playerNotificationService.alertSyncFailing() // Show alert in client
          failedSyncs = 0
        }
      }
      AbsLogger.error("MediaProgressSyncer", "sync: Progress sync failed (count: $failedSyncs) (title: \"$syncedTitle\") (currentTime: $currentTime) (session id: $sentSessionId) (${DeviceManager.serverConnectionConfigName})")
    }
  }

  private fun saveLocalProgress(playbackSession: PlaybackSession) {
    if (currentLocalMediaProgress == null) {
      val mediaProgress =
              DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
      if (mediaProgress == null) {
        currentLocalMediaProgress = playbackSession.getNewLocalMediaProgress()
      } else {
        currentLocalMediaProgress = mediaProgress
        currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
      }
    } else {
      currentLocalMediaProgress?.updateFromPlaybackSession(playbackSession)
    }

    currentLocalMediaProgress?.let {
      if (it.progress.isNaN()) {
        DLog.e(tag, "Invalid progress on local media progress")
      } else {
        DeviceManager.dbManager.saveLocalMediaProgress(it)
        playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(it)
        DLog.d(
                tag,
                "Saved Local Progress Current Time: ID ${it.id} | ${it.currentTime} | Duration ${it.duration} | Progress ${it.progressPercent}%"
        )
      }
    }
  }

  /** Everything a tick stores on the device: the session, the downloaded copy's Paper progress and the Room row. */
  internal fun saveTick(session: PlaybackSession, currentTime: Double) {
    // Sessions are removed once successfully synced with the server
    DeviceManager.dbManager.savePlaybackSession(session)
    if (session.isLocal) saveLocalProgress(session)
    recordTick(session, currentTime)
  }

  /**
   * Writes the tick of a server-linked session into userdata.db, off the player's thread. A downloaded copy's Paper
   * progress is written by [saveLocalProgress], so only a stream is mirrored to Paper (when a copy exists). A copy
   * without a server link has no Room row.
   */
  internal fun recordTick(session: PlaybackSession, currentTime: Double) {
    val serverConfigId = session.serverConnectionConfigId?.takeIf { it.isNotEmpty() } ?: return
    val libraryItemId = session.libraryItemId?.takeIf { it.isNotEmpty() } ?: return
    val episodeId = session.episodeId
    val duration = session.getTotalDuration()
    val progress = session.progress
    val lastUpdate = session.updatedAt
    val mirror = !session.isLocal
    SyncEngine.launchWrite { store ->
      store.recordPlayback(serverConfigId, libraryItemId, episodeId, currentTime, duration, progress, lastUpdate, mirror)
    }
  }

  /**
   * A seek is where the user put the player, also while paused, when no tick follows: without it the next refocus
   * would take the last tick's position as the device's and move the player back.
   */
  internal fun recordSeek(session: PlaybackSession, currentTime: Double, playerSessionId: String?) {
    // While the player prepares the next item (queue, restore, a switch to the downloaded file) its seek to the start
    // position arrives before this syncer moves on: that position is not this session's
    if (playerSessionId != session.id) return
    val serverConfigId = session.serverConnectionConfigId?.takeIf { it.isNotEmpty() } ?: return
    val libraryItemId = session.libraryItemId?.takeIf { it.isNotEmpty() } ?: return
    val episodeId = session.episodeId
    val duration = session.getTotalDuration()
    if (duration <= 0) return
    val progress = currentTime / duration
    val lastUpdate = System.currentTimeMillis()
    // A downloaded copy's Paper progress is the player's own (saveLocalProgress); a late mirror could undo a newer tick
    val mirror = !session.isLocal
    SyncEngine.launchWrite { store ->
      store.recordPlayback(serverConfigId, libraryItemId, episodeId, currentTime, duration, progress, lastUpdate, mirror)
    }
  }

  fun reset() {
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
    DLog.d(tag, "reset: Set last sync time 0 $lastSyncTime")
    failedSyncs = 0
  }
}
