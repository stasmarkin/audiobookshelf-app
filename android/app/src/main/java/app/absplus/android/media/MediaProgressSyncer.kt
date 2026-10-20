package app.absplus.android.media

import app.absplus.android.diagnostics.DLog
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.player.ServerProgressAck
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
import app.absplus.android.sync.LocalSessionsCleanup
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
        private val clock: () -> Long = System::currentTimeMillis,
        private val hasNetwork: () -> Boolean = { DeviceManager.checkConnectivity(playerNotificationService) },
        private val playerTime: () -> Double = { playerNotificationService.getCurrentTimeSeconds() },
        private val sendProgressSync: ((String, MediaProgressSyncData, (Boolean, String?) -> Unit) -> Unit)? = null,
        private val sendLocalProgressSync: ((PlaybackSession, (Boolean, String?) -> Unit) -> Unit)? = null,
        apiHandler: () -> ApiHandler
) {
  // Created on the first request: its secure storage is not needed to store a tick
  private val apiHandler by lazy(apiHandler)
  private val tag = "MediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private var listeningTimerTask: TimerTask? = null
  var listeningTimerRunning: Boolean = false

  // The last tick: each tick counts the listening since it
  internal var lastSyncTime: Long = 0
    private set
  // When the stream last tried /sync: on a metered connection it tries once a minute
  private var lastServerSyncAttempt: Long = 0
  @Volatile private var failedSyncs: Int = 0
  // The current session's streamed time not taken by /sync yet; a new session starts a new one
  @Volatile private var ledger = ListeningLedger()
  // The listening time the last session reached here, kept past a reset: its next start goes on from it
  private var lastTimeListening: Pair<String, Long>? = null
  // Changes with every start: an answer to an earlier stop or pause must not reset what started since
  private var generation = 0L

  init {
    recoverUnsettledListening()
  }

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
    serverProgressAck = serverProgressAck?.let { if (acknowledged) it.acknowledged(sessionId, currentTime, clock()) else it.sent(sessionId, currentTime) }
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
        retireLedger()
      } else {
        return
      }
    } else if (playbackSession.id != currentSessionId) {
      currentLocalMediaProgress = null
      retireLedger()
    }

    generation++
    listeningTimerRunning = true
    lastSyncTime = clock()
    lastServerSyncAttempt = lastSyncTime
    currentPlaybackSession = playbackSession.clone().also { it.timeListening = listenedSoFar(it) }
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
                                  clock() - lastServerSyncAttempt >=
                                          METERED_CONNECTION_SYNC_INTERVAL

                  val currentTime = playerTime()
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

  /**
   * A downloaded copy's session is sent whole and the server replaces its listening time with it: the player's copy of
   * the session never counts, so a play after a pause, a stop or a restart goes on from what this syncer counted.
   */
  private fun listenedSoFar(session: PlaybackSession): Long {
    var time = session.timeListening
    currentPlaybackSession?.takeIf { it.id == session.id }?.let { time = maxOf(time, it.timeListening) }
    lastTimeListening?.takeIf { it.first == session.id }?.let { time = maxOf(time, it.second) }
    if (session.isLocal) DeviceManager.dbManager.getPlaybackSession(session.id)?.let { time = maxOf(time, it.timeListening) }
    return time
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

    val currentTime = if (shouldSync == true) playerTime() else 0.0
    if (currentTime > 0) { // Current time should always be > 0 on stop
      val stopped = generation
      val session = currentPlaybackSession
      sync(true, currentTime) { syncResult -> onMain {
        session?.let { playbackSession ->
          MediaEventManager.stopEvent(playbackSession, syncResult)
        }

        if (stopped == generation) reset()
        cb()
      } }
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

    val currentTime = playerTime()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      val paused = generation
      val session = currentPlaybackSession
      sync(true, currentTime) { syncResult -> onMain {
        if (paused == generation) {
          lastSyncTime = 0L
          DLog.d(tag, "pause: Set last sync time 0 $lastSyncTime")
          failedSyncs = 0
        }

        session?.let { playbackSession ->
          MediaEventManager.pauseEvent(playbackSession, syncResult)
        }

        cb()
      } }
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

    val finished = generation
    sync(true, currentPlaybackSession?.duration ?: 0.0) { syncResult -> onMain {
      if (finished == generation) {
        reset()

        currentPlaybackSession?.let { playbackSession ->
          MediaEventManager.finishedEvent(playbackSession, syncResult)
        }
      }

      cb()
    } }
  }

  /** Syncer state belongs to the main thread, where ticks and start() run; a /sync answer arrives on OkHttp's. */
  private fun onMain(block: () -> Unit) {
    val main = Looper.getMainLooper()
    if (Looper.myLooper() == main) block() else Handler(main).post(block)
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

    // While the player prepares the next item its seek to the start position arrives before this syncer moves on:
    // that position is not this session's, so it is not forced into its progress (recordSeek skips it too)
    if (playerNotificationService.currentPlaybackSession?.id != currentPlaybackSession?.id) return
    // A seek is an explicit position change, even with the listening timer stopped or less than
    // a second after the last save. Use the normal durable/session/server path without counting
    // time spent paused as listening time. Do not wait for another Play or Pause event.
    sync(true, currentPlaybackSession!!.currentTime, forcePosition = true) {}
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

  fun sync(shouldSyncServer: Boolean, currentTime: Double, forcePosition: Boolean = false, cb: (SyncResult?) -> Unit) {
    if (!forcePosition && lastSyncTime <= 0) {
      DLog.e(tag, "Last sync time is not set $lastSyncTime")
      return cb(null)
    }

    val diffSinceLastSync = if (lastSyncTime > 0) clock() - lastSyncTime else 0L
    if (!forcePosition && diffSinceLastSync < 1000L) {
      return cb(null)
    }
    val listeningTimeToAdd = if (forcePosition && !listeningTimerRunning) 0L else diffSinceLastSync / 1000L

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
    // Whole seconds only: the remainder counts in the next tick
    lastSyncTime += listeningTimeToAdd * 1000L

    // Same sampled position, kept as the widget's last-known display position
    playerNotificationService.rememberPlaybackPosition(currentPlaybackSession?.id, currentTime)

    val hasNetworkConnection = hasNetwork()

    currentPlaybackSession?.let { saveTick(it, currentTime) }

    if (currentIsLocal) {
      currentPlaybackSession?.let {
        // As before: a downloaded copy is sent by a tick only on an unmetered connection
        lastServerSyncAttempt = clock()

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
          (sendLocalProgressSync ?: apiHandler::sendLocalProgressSync)(it) { syncSuccess, errorMsg ->
            if (syncSuccess) {
              failedSyncs = 0
              playerNotificationService.alertSyncSuccess()
              DeviceManager.dbManager.removePlaybackSession(it.id) // Remove session from db
              LocalSessionsCleanup.onSessionAccepted(it)
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
    } else {
      val session = currentPlaybackSession?.clone() ?: return cb(null)
      syncStream(session, ledger, listeningTimeToAdd, hasNetworkConnection, shouldSyncServer) { cb(it) }
    }
  }

  /**
   * A stream tick: the time listened since the last tick goes to the server through `/sync`, or, when it cannot, into
   * the ledger's offline segment, saved as a Paper session of its own that `local-all` sends later. [session] is this
   * tick's snapshot: the answer settles it even if the player moved on.
   */
  internal fun syncStream(
          session: PlaybackSession, ledger: ListeningLedger, listened: Long, hasNetwork: Boolean, shouldSyncServer: Boolean,
          send: (String, MediaProgressSyncData, (Boolean, String?) -> Unit) -> Unit = sendProgressSync ?: { id, data, done -> apiHandler.sendProgressSync(id, data, done) },
          cb: (SyncResult) -> Unit
  ) {
    val sessionId = session.id
    val currentTime = session.currentTime
    val title = session.displayTitle
    ledger.addListened(listened)
    if (!hasNetwork) {
      settleFailed(session, ledger, ledger.beginServerSync())
      AbsLogger.info("MediaProgressSyncer", "sync: No network, progress kept in an offline session (title: \"$title\") (currentTime: $currentTime) (session id: $sessionId)")
      return cb(SyncResult(false, null, null))
    }
    if (!shouldSyncServer) {
      saveUnsettled(session, ledger)
      return cb(SyncResult(false, null, null))
    }

    AbsLogger.info("MediaProgressSyncer", "sync: Sending progress sync to server (title: \"$title\") (currentTime: $currentTime) (session id: $sessionId) (${DeviceManager.serverConnectionConfigName})")
    val sent = ledger.beginServerSync()
    saveUnsettled(session, ledger)
    lastServerSyncAttempt = clock()
    noteServerSync(sessionId, currentTime, false)
    send(sessionId, MediaProgressSyncData(sent, session.duration, currentTime)) { syncSuccess, errorMsg ->
      // The failure count and its alerts belong to the session playing now, not to a late answer for the previous one
      val stillCurrent = ledger === this.ledger
      if (syncSuccess) {
        synchronized(ledger) {
          ledger.ackServerSync(sent)
          saveUnsettled(session, ledger)
        }
        noteServerSync(sessionId, currentTime, true)
        AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced progress (title: \"$title\") (currentTime: $currentTime) (session id: $sessionId)")
        if (stillCurrent) {
          failedSyncs = 0
          playerNotificationService.alertSyncSuccess()
        }
        // A session an earlier version saved under the server's id
        DeviceManager.dbManager.removePlaybackSession(sessionId)
        LocalSessionsCleanup.onSessionAccepted(session)
      } else {
        settleFailed(session, ledger, sent)
        if (stillCurrent) {
          failedSyncs++
          if (failedSyncs == 2) {
            playerNotificationService.alertSyncFailing() // Show alert in client
            failedSyncs = 0
          }
        }
        AbsLogger.error("MediaProgressSyncer", "sync: Progress sync failed, progress kept in an offline session (count: $failedSyncs) (title: \"$title\") (currentTime: $currentTime) (session id: $sessionId)")
      }
      cb(SyncResult(true, syncSuccess, errorMsg))
    }
  }

  /**
   * Puts [seconds] into the ledger's offline segment and saves it, the session's snapshot under the segment's id with
   * the segment's total time, then the time still unsettled. Serialized per ledger, so a late answer neither lowers
   * the saved time nor moves the position back.
   */
  private fun settleFailed(snapshot: PlaybackSession, ledger: ListeningLedger, seconds: Long) = synchronized(ledger) {
    ledger.failServerSync(seconds)?.let { segment ->
      val db = DeviceManager.dbManager
      db.savePlaybackSession(segmentSession(snapshot, segment, db.getPlaybackSession(segment.id)))
    }
    saveUnsettled(snapshot, ledger)
  }

  /**
   * Saves the ledger's time not settled yet as the offline session it would become, apart from the sessions that are
   * sent: a process that ends before the answer or the next attempt does not lose it ([recoverUnsettledListening]).
   */
  private fun saveUnsettled(snapshot: PlaybackSession, ledger: ListeningLedger) = synchronized(ledger) {
    val db = DeviceManager.dbManager
    val pending = ledger.pendingSegment()
    if (pending == null) db.removeUnsettledListening(ledger.key)
    else db.saveUnsettledListening(ledger.key, segmentSession(snapshot, pending, db.getUnsettledListening(ledger.key)))
  }

  private fun segmentSession(snapshot: PlaybackSession, segment: OfflineSegment, saved: PlaybackSession?) =
          snapshot.clone().apply {
            id = segment.id
            startedAt = segment.startedAt
            timeListening = segment.timeListening
            if (saved != null && saved.updatedAt > updatedAt) {
              currentTime = saved.currentTime
              updatedAt = saved.updatedAt
            }
          }

  /** A ledger that is replaced keeps nothing back: time not attempted yet goes into its segment now. */
  private fun retireLedger() {
    val old = ledger
    ledger = ListeningLedger()
    val session = currentPlaybackSession?.takeIf { !it.isLocal }?.clone() ?: return
    settleFailed(session, old, old.beginServerSync())
  }

  /**
   * Time a previous process left unsettled becomes offline sessions (a service created again in the same process
   * leaves its ledgers to their own answers). Time that was in flight counts as failed: like a
   * lost answer, the server may have taken it already.
   */
  private fun recoverUnsettledListening() {
    val db = DeviceManager.dbManager
    try {
      for (key in db.unsettledListeningKeys()) {
        if (ListeningLedger.isOfThisProcess(key)) continue
        val pending = db.getUnsettledListening(key)
        if (pending != null) {
          val saved = db.getPlaybackSession(pending.id)
          // Totals of one segment: the larger one holds everything
          if (pending.timeListening > (saved?.timeListening ?: 0L)) {
            db.savePlaybackSession(pending.apply {
              if (saved != null && saved.updatedAt > updatedAt) {
                currentTime = saved.currentTime
                updatedAt = saved.updatedAt
              }
            })
          }
        }
        db.removeUnsettledListening(key)
      }
    } catch (e: Exception) {
      // What is left is recovered by the next start
      AbsLogger.error("MediaProgressSyncer", "recoverUnsettledListening: ${e.message}")
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
        synchronized(DbManager.localMediaProgressLock) { DeviceManager.dbManager.saveLocalMediaProgress(it) }
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
    // A downloaded copy's session is removed once the server took it. A stream is never saved under its server id
    // (local-all would overwrite the server's session): what /sync missed is saved as an offline segment (syncStream)
    if (session.isLocal) {
      DeviceManager.dbManager.savePlaybackSession(session)
      saveLocalProgress(session)
    }
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
    // Into the copy's Paper progress too (the player starts from it), but never over a tick the player wrote after the
    // seek: this write runs later, off the player's thread
    SyncEngine.launchWrite { store ->
      store.recordPlayback(serverConfigId, libraryItemId, episodeId, currentTime, duration, progress, lastUpdate,
              mirrorToPaper = true, mirrorUnlessNewer = true)
    }
  }

  fun reset() {
    retireLedger()
    currentPlaybackSession?.let { lastTimeListening = it.id to it.timeListening }
    currentPlaybackSession = null
    currentLocalMediaProgress = null
    lastSyncTime = 0L
    lastServerSyncAttempt = 0L
    DLog.d(tag, "reset: Set last sync time 0 $lastSyncTime")
    failedSyncs = 0
  }
}
