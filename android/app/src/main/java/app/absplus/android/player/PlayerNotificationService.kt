package app.absplus.android.player

import app.absplus.android.diagnostics.DLog
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.os.PowerManager
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.VolumeProviderCompat
import android.media.AudioManager
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import app.absplus.android.BuildConfig
import app.absplus.android.R
import app.absplus.android.data.*
import app.absplus.android.data.DeviceInfo
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.managers.DbManager
import app.absplus.android.sync.SyncRuntime
import app.absplus.android.sync.ActiveServer
import app.absplus.android.sync.LocalCopyReady
import app.absplus.android.sync.ServerReachability
import app.absplus.android.sync.ServerState
import app.absplus.android.managers.LocalItemLocks
import app.absplus.android.managers.LibraryItemDownloadStarter
import app.absplus.android.device.AppStorage
import app.absplus.android.managers.PlaybackRestoreStore
import app.absplus.android.managers.PlaybackRestoreStore.LOG_TAG as RESTORE_TAG
import app.absplus.android.managers.SleepTimerManager
import android.content.pm.ServiceInfo
import androidx.media.session.MediaButtonReceiver
import app.absplus.android.media.CoverCache
import app.absplus.android.media.MediaManager
import app.absplus.android.media.MediaProgressSyncer
import app.absplus.android.media.getUriToAbsIconDrawable
import app.absplus.android.media.getUriToDrawable
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
import app.absplus.android.services.StorageMoveService
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.CustomActionProvider
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import java.util.*
import kotlin.concurrent.schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.sync.CatalogJson
import app.absplus.android.sync.SyncEngine

const val SLEEP_TIMER_WAKE_UP_EXPIRATION = 120000L // 2m
const val PLAYER_CAST = "cast-player"
const val PLAYER_EXO = "exo-player"
const val NO_AUDIO_FILES_MESSAGE = "No audio files found on device. Download book again to fix."
private const val PROGRESS_ROW_TIMEOUT_MS = 3_000L
private const val SERVER_PROGRESS_TIMEOUT_MS = 10_000L
// A queue start waits this long at most for the awaited item's display; a later one is shown when it arrives
private const val AWAITING_READ_TIMEOUT_MS = 1_000L
// Within that read: a busy write queue then only loses the awaited item's position
private const val AWAITING_PROGRESS_BUDGET_MS = 500L

class PlayerNotificationService : MediaBrowserServiceCompat() {

  companion object {
    var isStarted = false
    var isClosed = false
    var isUnmeteredNetwork = false
    var hasNetworkConnectivity = false // Not 100% reliable has internet
    var isSwitchingPlayer = false // Used when switching between cast player and exoplayer
  }

  private val tag = "PlayerNotificationServ"
  private var keepPlaybackPin = false

  interface ClientEventEmitter {
    fun onPlaybackSession(playbackSession: PlaybackSession)
    fun onPlaybackClosed()
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onSleepTimerEnded(currentPosition: Long)
    fun onSleepTimerSet(sleepTimeRemaining: Int, isAutoSleepTimer: Boolean)
    fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress)
    fun onPlaybackFailed(errorMessage: String)
    fun onMediaPlayerChanged(mediaPlayer: String)
    fun onProgressSyncFailing()
    fun onProgressSyncSuccess()
    fun onNetworkMeteredChanged(isUnmetered: Boolean)
    fun onMediaItemHistoryUpdated(mediaItemHistory: MediaItemHistory)
    fun onPlaybackSpeedChanged(playbackSpeed: Float)
    fun onAwaitingServer(awaiting: Boolean, display: AwaitingDisplay?)
  }
  private val binder = LocalBinder()

  var clientEventEmitter: ClientEventEmitter? = null

  private lateinit var ctx: Context
  internal lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  lateinit var mediaSession: MediaSessionCompat
  private var remoteVolumeProvider: VolumeProviderCompat? = null
  private lateinit var transportControls: MediaControllerCompat.TransportControls

  lateinit var mediaManager: MediaManager
  lateinit var apiHandler: ApiHandler

  lateinit var mPlayer: ExoPlayer
  lateinit var currentPlayer: Player
  var castPlayer: CastPlayer? = null

  // Defense-in-depth receiver for Bluetooth disconnect / headphone unplug.
  // ExoPlayer's setHandleAudioBecomingNoisy(true) registers its own receiver,
  // but on some devices (Samsung One UI, Android 12+) the foreground service
  // re-assertion can interfere.  This explicit receiver ensures pause sticks.
  private var audioNoisyReceiver: BroadcastReceiver? = null

  lateinit var sleepTimerManager: SleepTimerManager
  lateinit var mediaProgressSyncer: MediaProgressSyncer

  private var notificationId = 10
  private var channelId = "audiobookshelf_channel"
  // Display name only: the id stays, so Android renames the existing channel and keeps its settings
  private var channelName = "Audiobookshelf+ Playback"

  var currentPlaybackSession: PlaybackSession? = null
  private var initialPlaybackRate: Float? = null

  private val metadataScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var metadataArtJob: Job? = null
  // Item starts that read media_progress before preparing, and the server watch of the wait below
  private val startScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  // A stream that lost its server, or a queue stopped at an item it can only stream, waits here instead of closing
  val awaiting = AwaitingServer()
  internal val awaitingPresentation = AwaitingPresentation { shown, display -> onAwaitingPresentationChanged(shown, display) }
  /** The book a waiting queue is on, shown instead of the open session (or of nothing). */
  val awaitingDisplay: AwaitingDisplay? get() = awaitingPresentation.display
  private var awaitingCovers: Pair<AwaitingDisplay, AwaitingCover>? = null
  /** The cover of [awaitingDisplay], resolved off the main thread when it was read. */
  val awaitingCover: AwaitingCover? get() = awaitingCovers?.takeIf { it.first == awaitingDisplay }?.second
  // The start that resumes the awaited item, while its display stays on screen
  private var resumingTicket: StartGate.Ticket? = null
  // The connector would publish the open session's state and positions under the awaited item's title
  private var sessionDetachedForAwaiting = false
  // The notification of a wait with no session to draw one (same id: a prepared session's notification replaces it)
  private var awaitingNotificationShown = false
  private var lastStreamError = ""
  // Only the latest start that waits on something prepares the player
  private val startGate = StartGate()
  private val pauseOrigin = PauseOrigin()

  private var isAndroidAuto = false

  // Playlist queue for native background advancement (bypasses WebView/JS layer)
  data class PlaylistQueueItem(
          val libraryItemId: String,
          val episodeId: String?,
          val ino: String? = null,
          val serverConnectionConfigId: String? = null,
          /** The server's track count of a book; a downloaded copy with fewer tracks streams. */
          val expectedTracks: Int? = null
  )
  // Persisted on every change so a widget/headset Play can rebuild the queue after process death
  var playlistQueue: List<PlaylistQueueItem> = emptyList()
    set(value) {
      field = value
      PlaybackRestoreStore.saveQueue(this, field, playlistQueueIndex)
    }
  var playlistQueueIndex: Int = -1
    set(value) {
      field = value
      PlaybackRestoreStore.saveQueue(this, playlistQueue, field)
    }

  // Session restoration (widget/headset/system Play when no media is prepared)
  private var isRestoringPlayback = false
  private var isServiceDestroyed = false
  // True while the service is foreground only because of a media-button start, before any media is prepared
  private var isMediaButtonPlaceholderForeground = false
  // Stops name the latest start id, so a queued start (a key's UP) keeps the service (see ServiceStopper)
  private val serviceStopper = ServiceStopper { startId -> stopSelfResult(startId) }
  private val mainHandler = Handler(Looper.getMainLooper())
  // Releases a placeholder whose key-up never arrived (see MediaButtonLifecycle.KEY_UP_GRACE_MS)
  private val missingKeyUpRelease = Runnable {
    if (currentPlaybackSession == null && !isRestoringPlayback) releaseMediaButtonPlaceholderForeground("no key-up received")
  }

  // The following are used for the shake detection
  private var isShakeSensorRegistered: Boolean = false
  private var mSensorManager: SensorManager? = null
  private var mAccelerometer: Sensor? = null
  private var mShakeDetector: ShakeDetector? = null
  private var shakeSensorUnregisterTask: TimerTask? = null

  // These are used to trigger reloading if
  private var forceReloadingAndroidAuto: Boolean = false
  private var firstLoadDone: Boolean = false

  fun isBrowseTreeInitialized(): Boolean {
    return this::browseTree.isInitialized
  }

  // Cache latest search so it wont trigger again when returning from series for example
  private var cachedSearch: String = ""
  private var cachedSearchResults: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

  /*
     Service related stuff
  */
  override fun onBind(intent: Intent): IBinder? {
    DLog.d(tag, "onBind")

    // Android Auto Media Browser Service
    if (SERVICE_INTERFACE == intent.action) {
      DLog.d(tag, "Is Media Browser Service")
      return super.onBind(intent)
    }
    return binder
  }

  inner class LocalBinder : Binder() {
    // Return this instance of LocalService so clients can call public methods
    fun getService(): PlayerNotificationService = this@PlayerNotificationService
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    isStarted = true
    serviceStopper.onStartCommand(startId)
    DLog.d(tag, "onStartCommand $startId action=${intent?.action}")

    if (StickyRestart.isEmptyRestart(intent, currentPlaybackSession != null, isRestoringPlayback)) {
      stopEmptyStickyRestart()
      return START_NOT_STICKY
    }

    if (MediaButtonLifecycle.isOrphanForegroundStart(intent, currentPlaybackSession != null, isRestoringPlayback, PlayerNotificationListener.isForegroundService)) {
      // Android requires startForeground() for this start even though nothing is prepared any more
      DLog.w(RESTORE_TAG, "Foreground start with nothing prepared - satisfying it and stopping")
      startMediaButtonPlaceholderForeground()
      releaseMediaButtonPlaceholderForeground("orphan foreground start")
      return START_NOT_STICKY
    }

    if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
      // MediaButtonReceiver started us with startForegroundService, so we must be foreground
      // promptly even if nothing is prepared yet (e.g. widget Play after the service was destroyed)
      DLog.i(RESTORE_TAG, "Media button start command | sessionPrepared=${currentPlaybackSession != null} | foreground=${PlayerNotificationListener.isForegroundService}")
      if (!PlayerNotificationListener.isForegroundService) startMediaButtonPlaceholderForeground()
      // null = no key event, so the session callback (which normally releases the placeholder) won't run
      if (MediaButtonReceiver.handleIntent(mediaSession, intent) == null) onMediaButtonHandled()
    }

    return START_STICKY
  }

  // System restart after process death with nothing prepared (see StickyRestart): remove the dead
  // notification and stop. Resumable state is untouched; onDestroy redraws the widget as not playing.
  private fun stopEmptyStickyRestart() {
    DLog.i(RESTORE_TAG, "Sticky restart with no prepared session - removing stale notification and stopping | resumable=${PlaybackRestoreStore.isResumable(this)}")
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
    NotificationManagerCompat.from(this).cancel(notificationId)
    PlayerNotificationListener.isForegroundService = false
    isStarted = false
    stopSelf()
  }

  private fun startMediaButtonPlaceholderForeground() {
    val title = awaitingDisplay?.title ?: DeviceManager.deviceData.lastPlaybackSession?.displayTitle ?: getString(R.string.app_name)
    val notification = NotificationCompat.Builder(this, channelId)
      .setSmallIcon(R.drawable.icon_monochrome)
      .setContentTitle(title)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setSilent(true)
      .build()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        startForeground(notificationId, notification)
      }
      PlayerNotificationListener.isForegroundService = true
      isMediaButtonPlaceholderForeground = true
      DLog.i(RESTORE_TAG, "Started placeholder foreground for media button")
    } catch (e: Exception) {
      DLog.e(RESTORE_TAG, "Could not start placeholder foreground: $e")
    }
  }

  // Called once a media button command has finished; drops the placeholder if nothing ended up playing
  fun onMediaButtonHandled() {
    mainHandler.removeCallbacks(missingKeyUpRelease)
    if (!isMediaButtonPlaceholderForeground || isRestoringPlayback) return
    if (currentPlaybackSession == null) {
      releaseMediaButtonPlaceholderForeground("nothing to play")
    } else {
      // A session already existed: put the real player notification back over the placeholder
      isMediaButtonPlaceholderForeground = false
      playerNotificationManager.invalidate()
    }
  }

  // Called for the DOWN of a key that acts at UP: keep the placeholder for the UP, but not forever
  fun onMediaButtonPending() {
    if (!isMediaButtonPlaceholderForeground) return
    mainHandler.removeCallbacks(missingKeyUpRelease)
    mainHandler.postDelayed(missingKeyUpRelease, MediaButtonLifecycle.KEY_UP_GRACE_MS)
  }

  private fun releaseMediaButtonPlaceholderForeground(reason: String) {
    if (!isMediaButtonPlaceholderForeground) return
    mainHandler.removeCallbacks(missingKeyUpRelease)
    isMediaButtonPlaceholderForeground = false
    PlayerNotificationListener.isForegroundService = false
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
    // The placeholder took the waiting queue's notification id
    awaitingDisplay?.takeIf { currentPlaybackSession == null }?.let { showAwaitingNotification(it) }
    // A newer start (another key event) may already be queued: it keeps the service, and re-enters foreground
    val stopped = serviceStopper.stop()
    if (stopped) isStarted = false
    DLog.i(RESTORE_TAG, "Releasing placeholder foreground ($reason) | stopped=$stopped startId=${serviceStopper.lastStartId}")
  }

  @Deprecated("Deprecated in Java")
  override fun onStart(intent: Intent?, startId: Int) {
    DLog.d(tag, "onStart $startId")
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createNotificationChannel(channelId: String, channelName: String): String {
    val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
    chan.lightColor = Color.DKGRAY
    chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    service.createNotificationChannel(chan)
    return channelId
  }

  // detach player
  override fun onDestroy() {
    try {
      val connectivityManager =
              getSystemService(ConnectivityManager::class.java) as ConnectivityManager
      connectivityManager.unregisterNetworkCallback(networkCallback)
    } catch (error: Exception) {
      DLog.e(tag, "Error unregistering network listening callback $error")
    }

    // Unregister the defense-in-depth AUDIO_BECOMING_NOISY receiver
    audioNoisyReceiver?.let {
      try { unregisterReceiver(it) } catch (_: Exception) {}
      audioNoisyReceiver = null
    }

    DLog.d(tag, "onDestroy")
    DLog.marker("Playback service destroyed")
    DLog.i(RESTORE_TAG, "Player service destroyed | hadSession=${currentPlaybackSession != null} | resumable=${PlaybackRestoreStore.isResumable(this)} | queue=${playlistQueue.size}")
    isServiceDestroyed = true
    mainHandler.removeCallbacks(missingKeyUpRelease)
    isStarted = false
    isClosed = true
    isMediaButtonPlaceholderForeground = false
    PlayerNotificationListener.isForegroundService = false
    DeviceManager.widgetUpdater?.onPlayerChanged(this)
    if (awaitingNotificationShown) NotificationManagerCompat.from(this).cancel(notificationId)

    playerNotificationManager.setPlayer(null)
    mPlayer.release()
    castPlayer?.release()
    mediaSession.release()
    mediaProgressSyncer.reset()
    metadataScope.cancel()
    startScope.cancel()
    // The player is released without closePlayback
    LocalItemLocks.clearPlaybackPin()

    super.onDestroy()
  }

  // removing service when user swipe out our app
  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)

    val playerWantsToPlay = try { currentPlayer.playWhenReady } catch (e: Exception) { false }
    // isPlaying is false while buffering (e.g. a stream just after Play), which is still intent to play
    val isBuffering = try { currentPlayer.playbackState == Player.STATE_BUFFERING } catch (e: Exception) { false }
    val isActuallyPlaying = try { currentPlayer.isPlaying } catch (e: Exception) { false }
    val isPlaying = isActuallyPlaying || (playerWantsToPlay && isBuffering)
    // Only keep the service alive if the player is actively playing or if it
    // intends to continue (playWhenReady == true with a playlist queue, i.e.
    // between episodes).  When paused by Bluetooth disconnect or user action,
    // playWhenReady is false and the service should be allowed to stop.
    if (isPlaying || (playlistQueue.isNotEmpty() && playerWantsToPlay)) {
      DLog.d(tag, "onTaskRemoved: keeping service alive (playlistQueue=${playlistQueue.size}, isPlaying=$isPlaying, playWhenReady=$playerWantsToPlay)")
      DLog.marker("UI task removed (playing - service kept)")
      DLog.i(RESTORE_TAG, "Task removed while playing - existing player/session kept alive")
      return
    }

    // Not playing: let the service stop. The session stays resumable (lastPlaybackSession +
    // PlaybackRestoreStore), so a later widget/headset Play rebuilds it via restoreLastPlaybackSession().
    DLog.d(tag, "onTaskRemoved: stopping service (playlistQueue=${playlistQueue.size}, isPlaying=$isPlaying, playWhenReady=$playerWantsToPlay)")
    DLog.marker("UI task removed (not playing - service stopping)")
    DLog.i(RESTORE_TAG, "Task removed while not playing - stopping service, session left resumable=${PlaybackRestoreStore.isResumable(this)}")
    stopSelf()
  }

  override fun onCreate() {
    super.onCreate()
    ctx = this

    // Initialize Paper (also starts persistent diagnostics in a fresh process)
    DbManager.initialize(ctx)
    SyncRuntime.ensureInit(ctx)
    DLog.d(tag, "onCreate")
    DLog.marker("Playback service created")
    DLog.i(RESTORE_TAG, "Player service created (new instance - any previous player/session is gone)")

    // Initialize widget
    DeviceManager.initializeWidgetUpdater(ctx)

    // To listen for network change from metered to unmetered
    val networkRequest =
            NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()
    val connectivityManager =
            getSystemService(ConnectivityManager::class.java) as ConnectivityManager
    connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

    DbManager.initialize(ctx)

    // Initialize API
    apiHandler = ApiHandler(ctx)

    // Initialize sleep timer
    sleepTimerManager = SleepTimerManager(this)

    // Initialize Media Progress Syncer
    mediaProgressSyncer = MediaProgressSyncer(this) { apiHandler }

    // Initialize shake sensor
    DLog.d(tag, "onCreate Register sensor listener ${mAccelerometer?.isWakeUpSensor}")
    initSensor()

    // Initialize media manager
    mediaManager = MediaManager(apiHandler, ctx)

    channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              createNotificationChannel(channelId, channelName)
            } else ""

    val sessionActivityPendingIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
              PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE)
            }

    mediaSession =
            MediaSessionCompat(this, tag).apply {
              setSessionActivity(sessionActivityPendingIntent)
              isActive = true
            }

    val mediaController = MediaControllerCompat(ctx, mediaSession.sessionToken)

    // This is for Media Browser
    sessionToken = mediaSession.sessionToken

    val builder = PlayerNotificationManager.Builder(ctx, notificationId, channelId)

    builder.setMediaDescriptionAdapter(AbMediaDescriptionAdapter(mediaController, this))
    builder.setNotificationListener(PlayerNotificationListener(this))

    playerNotificationManager = builder.build()
    playerNotificationManager.setMediaSessionToken(mediaSession.sessionToken)
    playerNotificationManager.setUsePlayPauseActions(true)
    playerNotificationManager.setUseNextAction(false)
    playerNotificationManager.setUsePreviousAction(false)
    playerNotificationManager.setUseChronometer(false)
    playerNotificationManager.setUseStopAction(false)
    playerNotificationManager.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    playerNotificationManager.setPriority(NotificationCompat.PRIORITY_MAX)
    playerNotificationManager.setUseFastForwardActionInCompactView(true)
    playerNotificationManager.setUseRewindActionInCompactView(true)
    playerNotificationManager.setSmallIcon(R.drawable.icon_monochrome)

    // Unknown action
    playerNotificationManager.setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)

    transportControls = mediaController.transportControls

    mediaSessionConnector = MediaSessionConnector(mediaSession)
    mediaManager.onPlaybackError = { msg ->
      Handler(Looper.getMainLooper()).post {
        mediaSessionConnector.setCustomErrorMessage(msg)
        republishAwaitingSession()
      }
    }
    // Without this, the connector's default metadata provider rebuilds metadata from the
    // player's own state on media item transitions/timeline changes, dropping the cover art
    // bitmap that PlaybackSession.resolveCoverBitmapAsync resolves separately.
    mediaSessionConnector.setMediaMetadataProvider { _ ->
      nowPlayingMetadata(ctx, currentPlaybackSession, awaitingDisplay, getString(R.string.waiting_for_server))
    }
    val queueNavigator: TimelineQueueNavigator =
            object : TimelineQueueNavigator(mediaSession) {
              override fun getSupportedQueueNavigatorActions(player: Player): Long {
                return PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
              }

              override fun getMediaDescription(
                      player: Player,
                      windowIndex: Int
              ): MediaDescriptionCompat {
                if (currentPlaybackSession == null) {
                  DLog.e(tag, "Playback session is not set - returning blank MediaDescriptionCompat")
                  return MediaDescriptionCompat.Builder().build()
                }

                var bitmap: Bitmap? = null
                // Local covers get bitmap
                // Note: In Android Auto for local cover images, setting the icon uri to a local path does not work (cover is blank)
                // so we create and set the bitmap here instead of AbMediaDescriptionAdapter
                val sessionCover = currentPlaybackSession!!.getCoverUri(ctx)
                // On the main thread: the bitmap the session already resolved, else a downscaled decode
                if (CoverArt.isLocal(sessionCover)) {
                  bitmap = currentPlaybackSession!!.resolvedCoverBitmapOrNull()
                          ?: CoverArt.decodeLocal(ctx, sessionCover, "media session", CoverArt.DESCRIPTION_COVER_PX)
                }
                // The default artwork when a local cover can't be decoded
                val coverUri = currentPlaybackSession!!.getDisplayCoverUri(ctx)

                // Fix for local images crashing on Android 11 for specific devices
                // https://stackoverflow.com/questions/64186578/android-11-mediastyle-notification-crash/64232958#64232958
                try {
                  ctx.grantUriPermission(
                          "com.android.systemui",
                          coverUri,
                          Intent.FLAG_GRANT_READ_URI_PERMISSION
                  )
                } catch (error: Exception) {
                  DLog.e(tag, "Grant uri permission error $error")
                }

                val extra = Bundle()
                extra.putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        currentPlaybackSession!!.displayAuthor
                )

                val mediaDescriptionBuilder =
                        MediaDescriptionCompat.Builder()
                                .setExtras(extra)
                                .setTitle(currentPlaybackSession!!.displayTitle)

                bitmap?.let { mediaDescriptionBuilder.setIconBitmap(it) }
                  ?: mediaDescriptionBuilder.setIconUri(coverUri)

                return mediaDescriptionBuilder.build()
              }
            }

    setMediaSessionConnectorPlaybackActions()
    mediaSessionConnector.setQueueNavigator(queueNavigator)
    mediaSessionConnector.setPlaybackPreparer(MediaSessionPlaybackPreparer(this))

    mediaSession.setCallback(MediaSessionCallback(this))

    initializeMPlayer()
    currentPlayer = mPlayer

    // onlineEntries, not state: state is conflated and can miss a quick OFFLINE -> ONLINE
    startScope.launch {
      ServerReachability.onlineEntries.collect {
        perform(awaiting.onServerState(ServerReachability.state.value, ServerReachability.serverConfigId.value, streamPermission()))
      }
    }
    // Download callbacks come from a worker thread; the collector runs on main
    startScope.launch { SyncRuntime.localCopyReady.collect { onLocalCopyReady(it) } }
    startScope.launch {
      var previous: String? = null
      ServerReachability.serverConfigId.collect { id ->
        onActiveServerChanged(previous, id)
        previous = id
      }
    }
  }

  private fun initializeMPlayer() {
    val customLoadControl: LoadControl =
            DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            1000 * 20, // 20s min buffer
                            1000 * 45, // 45s max buffer
                            1000 * 5, // 5s playback start
                            1000 * 20 // 20s playback rebuffer
                    )
                    .build()

    mPlayer =
            ExoPlayer.Builder(this)
                    .setLoadControl(customLoadControl)
                    .setSeekBackIncrementMs(deviceSettings.jumpBackwardsTimeMs)
                    .setSeekForwardIncrementMs(deviceSettings.jumpForwardTimeMs)
                    .build()
    mPlayer.setWakeMode(C.WAKE_MODE_NETWORK)
    mPlayer.setHandleAudioBecomingNoisy(true)
    mPlayer.addListener(PlayerListener(this))
    val audioAttributes: AudioAttributes =
            AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build()
    mPlayer.setAudioAttributes(audioAttributes, true)

    // Defense-in-depth: register an explicit receiver for audio route changes
    // (Bluetooth disconnect, headphone unplug).  ExoPlayer's built-in handler
    // should pause the player, but on some devices the foreground service
    // lifecycle can interfere.  This receiver ensures the pause is authoritative.
    audioNoisyReceiver?.let {
      try { unregisterReceiver(it) } catch (_: Exception) {}
    }
    audioNoisyReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
          DLog.d(tag, "ACTION_AUDIO_BECOMING_NOISY received — ensuring player is paused")
          onPauseRequested()
          try {
            if (mPlayer.playWhenReady) {
              mPlayer.playWhenReady = false
              DLog.d(tag, "Forced playWhenReady=false on AUDIO_BECOMING_NOISY")
            }
          } catch (e: Exception) {
            DLog.e(tag, "Error handling AUDIO_BECOMING_NOISY: $e")
          }
        }
      }
    }
    val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    registerReceiver(audioNoisyReceiver, noisyFilter)

    // attach player to playerNotificationManager
    playerNotificationManager.setPlayer(notificationControls(mPlayer))

    mediaSessionConnector.setPlayer(mPlayer)
  }

  /*
    User callable methods
  */
  fun preparePlayer(
          playbackSession: PlaybackSession,
          playWhenReady: Boolean,
          playbackRate: Float?
  ) {
    // A resume hands out its action (and ends the wait) before it prepares; anything else replaces what waited. The
    // awaited item's display goes once this session is out, so the app's player never closes in between
    if (awaiting.isAwaiting) {
      DLog.i(tag, "Stopped waiting for the server (${CancelReason.OTHER_ITEM_STARTED})")
      awaiting.cancel(CancelReason.OTHER_ITEM_STARTED)
    }
    mediaSessionConnector.setCustomErrorMessage(null)
    // Native playback entry points (for example Android Auto) can bypass the JS queue setup.
    val queuedItem = playlistQueue.getOrNull(playlistQueueIndex)
    if (queuedItem != null && !queuedItem.matches(playbackSession)) {
      playlistQueue = emptyList()
      playlistQueueIndex = -1
    }
    if (!isStarted) {
      DLog.i(tag, "preparePlayer: foreground service not started - Starting service --")
      Intent(ctx, PlayerNotificationService::class.java).also { intent ->
        ContextCompat.startForegroundService(ctx, intent)
      }
    }

    // Before the finished-item reset below: this is the position the server gave the session
    mediaProgressSyncer.noteServerSessionStart(playbackSession)

    // TODO: When an item isFinished the currentTime should be reset to 0
    //        will reset the time if currentTime is within 5s of duration (for android auto)
    DLog.d(
            tag,
            "Prepare Player Session Current Time=${playbackSession.currentTime}, Duration=${playbackSession.duration}"
    )
    if (playbackSession.duration - playbackSession.currentTime < 5) {
      DLog.d(tag, "Prepare Player Session is finished, so restart it")
      playbackSession.currentTime = 0.0
    }

    isClosed = false

    val mediaItems = playbackSession.getMediaItems(ctx)
    val playbackRateToUse = playbackRate ?: initialPlaybackRate ?: 1f
    initialPlaybackRate = playbackRate

    // Set actions on Android Auto like jump forward/backward
    setMediaSessionConnectorCustomActions(playbackSession)

    playbackSession.mediaPlayer = getMediaPlayer()

    if (playbackSession.mediaPlayer == PLAYER_CAST && playbackSession.isLocal) {
      DLog.w(tag, "Cannot cast local media item - switching player")
      currentPlaybackSession = null
      // The nested preparePlayer may replay an older session; the pin belongs to this local item
      keepPlaybackPin = true
      try {
        switchToPlayer(false)
      } finally {
        keepPlaybackPin = false
      }
      playbackSession.mediaPlayer = getMediaPlayer()
    }

    if (playbackSession.mediaPlayer == PLAYER_CAST) {
      // If cast-player is the first player to be used
      mediaSessionConnector.setPlayer(castPlayer)
      playerNotificationManager.setPlayer(castPlayer?.let(::notificationControls))
    }

    currentPlaybackSession = playbackSession
    DeviceManager.setLastPlaybackSession(
            playbackSession
    ) // Save playback session to use when app is closed

    metadataArtJob?.cancel()
    metadataArtJob =
            playbackSession.resolveCoverBitmapAsync(ctx, metadataScope) {
              mediaSessionConnector.invalidateMediaSessionMetadata()
              republishAwaitingSession()
            }
    mediaSessionConnector.invalidateMediaSessionMetadata()

    AbsLogger.info("PlayerNotificationService", "preparePlayer: Started playback session for item ${currentPlaybackSession?.mediaItemId}. MediaPlayer ${currentPlaybackSession?.mediaPlayer}")
    // Notify client
    clientEventEmitter?.onPlaybackSession(playbackSession)
    resumingTicket = null
    awaitingPresentation.end()

    // Update widget
    DeviceManager.widgetUpdater?.onPlayerChanged(this)

    if (mediaItems.isEmpty()) {
      DLog.e(tag, "Invalid playback session no media items to play")
      currentPlaybackSession = null
      if (!keepPlaybackPin) LocalItemLocks.clearPlaybackPin()
      return
    }
    if (playbackSession.localLibraryItem == null && !keepPlaybackPin) {
      pinOwner = null
      if (LocalItemLocks.clearPlaybackPin()) StorageMoveService.requestReconcile(this)
    }

    PlaybackRestoreStore.setResumable(this, true)
    // PlayerNotificationManager posts its notification under the same id, replacing any placeholder
    isMediaButtonPlaceholderForeground = false

    if (mPlayer == currentPlayer) {
      val mediaSource: MediaSource

      if (playbackSession.isLocal) {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing local item ${currentPlaybackSession?.mediaItemId}.")
        val dataSourceFactory = DefaultDataSource.Factory(ctx)

        val extractorsFactory = DefaultExtractorsFactory()
        extractorsFactory.setConstantBitrateSeekingEnabled(true)

        if (DeviceManager.deviceData.deviceSettings?.enableMp3IndexSeeking == true) {
          // @see
          // https://exoplayer.dev/troubleshooting.html#why-is-seeking-inaccurate-in-some-mp3-files
          extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        }

        mediaSource =
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItems[0])
      } else if (!playbackSession.isHLS) {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Direct playing item ${currentPlaybackSession?.mediaItemId}.")
        val dataSourceFactory = DefaultHttpDataSource.Factory()

        val extractorsFactory = DefaultExtractorsFactory()
        extractorsFactory.setConstantBitrateSeekingEnabled(true)

        if (DeviceManager.deviceData.deviceSettings?.enableMp3IndexSeeking == true) {
          // @see
          // https://exoplayer.dev/troubleshooting.html#why-is-seeking-inaccurate-in-some-mp3-files
          extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        }

        dataSourceFactory.setUserAgent(channelId)
        mediaSource =
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItems[0])
      } else {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing HLS stream of item ${currentPlaybackSession?.mediaItemId}.")
        val dataSourceFactory = DefaultHttpDataSource.Factory()
        dataSourceFactory.setUserAgent(channelId)
        dataSourceFactory.setDefaultRequestProperties(
                hashMapOf("Authorization" to "Bearer ${DeviceManager.token}")
        )
        mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItems[0])
      }
      mPlayer.setMediaSource(mediaSource)

      // Add remaining media items if multi-track
      if (mediaItems.size > 1) {
        currentPlayer.addMediaItems(mediaItems.subList(1, mediaItems.size))
        DLog.d(tag, "currentPlayer total media items ${currentPlayer.mediaItemCount}")

        val currentTrackIndex = playbackSession.getCurrentTrackIndex()
        val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
        DLog.d(
                tag,
                "currentPlayer current track index $currentTrackIndex & current track time $currentTrackTime"
        )
        currentPlayer.seekTo(currentTrackIndex, currentTrackTime)
      } else {
        currentPlayer.seekTo(playbackSession.currentTimeMs)
      }

      DLog.d(
              tag,
              "Prepare complete for session ${currentPlaybackSession?.displayTitle} | ${currentPlayer.mediaItemCount}"
      )
      currentPlayer.playWhenReady = playWhenReady
      currentPlayer.setPlaybackSpeed(playbackRateToUse)

      currentPlayer.prepare()
    } else if (castPlayer != null) {
      val currentTrackIndex = playbackSession.getCurrentTrackIndex()
      val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
      val mediaType = playbackSession.mediaType
      DLog.d(tag, "Loading cast player $currentTrackIndex $currentTrackTime $mediaType")

      castPlayer?.load(
              mediaItems,
              currentTrackIndex,
              currentTrackTime,
              playWhenReady,
              playbackRateToUse,
              mediaType
      )
    }
  }

  private fun setMediaSessionConnectorCustomActions(playbackSession: PlaybackSession) {
    val customActionProviders =
            mutableListOf(
                    JumpBackwardCustomActionProvider(),
                    JumpForwardCustomActionProvider(),
                    ChangePlaybackSpeedCustomActionProvider() // Will be pushed to far left
            )
    if (playbackSession.mediaPlayer != PLAYER_CAST) {
      customActionProviders.addAll(
              listOf(
                      SkipBackwardCustomActionProvider(),
                      SkipForwardCustomActionProvider(),
              )
      )
    }
    mediaSessionConnector.setCustomActionProviders(*customActionProviders.toTypedArray())
    republishAwaitingSession()
  }

  fun setMediaSessionConnectorPlaybackActions() {
    var playbackActions =
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_STOP

    if (deviceSettings.allowSeekingOnMediaControls) {
      playbackActions = playbackActions or PlaybackStateCompat.ACTION_SEEK_TO
    }
    mediaSessionConnector.setEnabledPlaybackActions(playbackActions)
    republishAwaitingSession()
  }

  fun handlePlayerPlaybackError(error: PlaybackException) {
    val errorMessage = error.message ?: "Unknown Error"
    val playbackSession = currentPlaybackSession ?: return
    // Before the transcode fallback (and for HLS too): a transcode would hit the same unreachable server
    if (!playbackSession.isLocal && isServerLoss(error.errorCode, httpStatusOf(error))) {
      lastStreamError = errorMessage
      enterStreamLoss(playbackSession, getCurrentTimeSeconds(), currentPlayer.playWhenReady)
      return
    }
    if (playbackSession.isDirectPlay) {
      // On error and was attempting to direct play - fallback to transcode
      val playItemRequestPayload = getPlayItemRequestPayload(true)
      DLog.d(tag, "Fallback to transcode $playItemRequestPayload.mediaPlayer")
      val position = getCurrentTimeSeconds()
      val wasPlaying = currentPlayer.playWhenReady

      val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
      val episodeId = playbackSession.episodeId
      mediaProgressSyncer.stop(false) {
        apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
          if (it == null) { // Play request failed
            // Posted: the request's reachability report lands first
            mainHandler.post {
              if (ServerReachability.state.value != ServerState.ONLINE && currentPlaybackSession === playbackSession) {
                lastStreamError = errorMessage
                enterStreamLoss(playbackSession, position, wasPlaying)
              } else {
                clientEventEmitter?.onPlaybackFailed(errorMessage)
                closePlayback(true)
              }
            }
          } else {
            mainHandler.post { preparePlayer(it, true, null) }
          }
        }
      }
    } else {
      clientEventEmitter?.onPlaybackFailed(errorMessage)
      closePlayback(true)
    }
  }

  /** The stream lost its server: pause and wait for it instead of closing; the session, queue and notification stay. */
  private fun enterStreamLoss(session: PlaybackSession, position: Double, wasPlaying: Boolean) {
    val libraryItemId = session.libraryItemId
    val serverConfigId = session.serverConnectionConfigId ?: ActiveServer.current()?.id
    if (libraryItemId == null || serverConfigId == null) {
      clientEventEmitter?.onPlaybackFailed(lastStreamError)
      closePlayback(true)
      return
    }
    DLog.w(tag, "Stream of $libraryItemId lost its server at ${position}s (playWhenReady=$wasPlaying) - waiting for the server")
    // This pause is not the user's: its event, delivered after the wait is set, must not cancel the intent to play
    pauseOrigin.beforeSelfPause(currentPlayer.playWhenReady)
    currentPlayer.pause()
    mainHandler.post { pauseOrigin.settle() }
    // Saves the position to media_progress, and unsent listening time to its offline segment
    if (mediaProgressSyncer.listeningTimerRunning) mediaProgressSyncer.pause {}
    PlayerListener.lazyIsPlaying = false
    clientEventEmitter?.onPlayingUpdate(false)
    // Also for a 5xx the reachability machine does not count as a loss: it goes offline and probes
    ActiveServer.current()?.address?.let { ServerReachability.reportUnreachable(it) }
    val action = awaiting.onStreamLost(libraryItemId, session.episodeId, position, wasPlaying, serverConfigId, expectedTracksOf(session))
    if (action != null) return perform(action)
    awaitingPresentation.begin(null, null)
    DeviceManager.widgetUpdater?.onPlayerChanged(this)
  }

  /**
   * The queue waits on [item]; false when it could not. [display]: read before (a start from the app); with
   * [readDisplay] it is read now and shown once it arrives.
   */
  private fun blockQueue(item: PlaylistQueueItem, playWhenReady: Boolean, display: AwaitingDisplay? = null, readDisplay: Boolean = true): Boolean {
    // A stream always comes from the active server
    val serverConfigId = ActiveServer.current()?.id
    if (serverConfigId == null) {
      DLog.e(tag, "advancePlaylistQueue: no server to stream ${item.libraryItemId} from, giving up")
      return false
    }
    DLog.i(tag, "advancePlaylistQueue: ${item.libraryItemId} is not downloaded and the server is away - waiting on it")
    val action = awaiting.onQueueBlocked(item, serverConfigId, playWhenReady)
    if (action != null) {
      perform(action)
      return false
    }
    resumingTicket = null
    awaitingPresentation.begin(item, display)
    if (readDisplay) startScope.launch { publishAwaitingDisplay(item, readAwaitingDisplay(item, serverConfigId)) }
    // Checks the server at once instead of at the next backoff step (when there is a network)
    ServerReachability.retryNow()
    return true
  }

  /** A display read after the wait on [item] began: shown if the queue still waits on it. */
  internal fun publishAwaitingDisplay(item: PlaylistQueueItem, display: AwaitingDisplay?) {
    awaitingPresentation.onRead(item, display, awaiting.blockedItem)
  }

  /** What the replica knows of [item] for the player while the queue waits on it; null without a row. */
  private suspend fun readAwaitingDisplay(item: PlaylistQueueItem, serverConfigId: String): AwaitingDisplay? = try {
    readAwaitingDisplayRow(AWAITING_PROGRESS_BUDGET_MS, { progressRow(serverConfigId, item.libraryItemId, item.episodeId) }) { progress ->
      withContext(Dispatchers.IO) {
        val replica = ReplicaDatabase.get(ctx)
        val row = replica.items().get(serverConfigId, item.libraryItemId) ?: return@withContext null
        val episode = item.episodeId?.let { replica.episodes().byIds(serverConfigId, listOf(it)).firstOrNull() }
        // An incomplete copy streams too; its cover is the only one there is offline
        val copy = DeviceManager.dbManager.getLocalLibraryItemByLId(item.libraryItemId)?.takeIf { it.serverConnectionConfigId == serverConfigId }
        awaitingDisplayOf(CatalogJson.mapper.readTree(row.json), episode?.let { CatalogJson.mapper.readTree(it.json) }, progress,
                awaitingCoverOf(copy?.coverContentUrl, CoverCache.instance?.latestFile(item.libraryItemId)), serverConfigId, item.episodeId)
      }
    }
  } catch (e: Exception) {
    DLog.w(tag, "Could not read the replica for ${item.libraryItemId}: $e")
    null
  }

  // Resolving a cover on an SD card may copy it: off the main thread, after the item is already shown
  private fun loadCoverOf(display: AwaitingDisplay) {
    val coverUri = display.coverUri ?: return
    if (awaitingCovers?.first == display) return
    startScope.launch {
      loadAwaitingCover(display, {
        withContext(Dispatchers.IO) { CoverArt.localCoverUri(this@PlayerNotificationService, coverUri).let { AwaitingCover(it, CoverArt.decodeLocal(this@PlayerNotificationService, it, "awaiting item")) } }
      }, { awaitingDisplay }) { cover ->
        awaitingCovers = display to cover
        refreshNowPlaying(display)
        DeviceManager.widgetUpdater?.onPlayerChanged(this@PlayerNotificationService)
      }
    }
  }

  private fun onAwaitingPresentationChanged(shown: Boolean, display: AwaitingDisplay?) {
    clientEventEmitter?.onAwaitingServer(shown, display)
    refreshNowPlaying(display)
    DeviceManager.widgetUpdater?.onPlayerChanged(this)
    display?.let { loadCoverOf(it) }
  }

  /**
   * The media session and the notification. While an item is awaited the connector is detached: it would publish the
   * open session's state, whose seeks would move that session under the awaited item's title.
   */
  private fun refreshNowPlaying(display: AwaitingDisplay?) {
    // Not created yet (a bare instance in tests)
    if (!::mediaSessionConnector.isInitialized) return
    if (display != null) {
      sessionDetachedForAwaiting = true
      republishAwaitingSession()
    } else {
      if (sessionDetachedForAwaiting) {
        mediaSessionConnector.setPlayer(currentPlayer)
        sessionDetachedForAwaiting = false
      }
      mediaSessionConnector.invalidateMediaSessionMetadata()
      mediaSessionConnector.invalidateMediaSessionPlaybackState()
    }
    if (currentPlaybackSession != null) {
      awaitingNotificationShown = false
      playerNotificationManager.invalidate()
    } else if (display != null) {
      showAwaitingNotification(display)
    } else if (awaitingNotificationShown) {
      awaitingNotificationShown = false
      if (!isMediaButtonPlaceholderForeground) NotificationManagerCompat.from(this).cancel(notificationId)
    }
  }

  // Any connector call publishes the detached connector's empty state: the awaited item is published again after it
  private fun republishAwaitingSession() {
    if (!sessionDetachedForAwaiting) return
    val display = awaitingDisplay ?: return
    mediaSessionConnector.setPlayer(null)
    mediaSession.setMetadata(nowPlayingMetadata(this, currentPlaybackSession, display, getString(R.string.waiting_for_server), awaitingCover?.bitmap))
    mediaSession.setPlaybackState(awaitingPlaybackState(display))
  }

  // With nothing prepared the player draws no notification: this one names the awaited item, paused, with Play
  private fun showAwaitingNotification(display: AwaitingDisplay) {
    if (isMediaButtonPlaceholderForeground) return
    val play = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
    val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.icon_monochrome)
            .setContentTitle(display.title)
            .setContentText(getString(R.string.waiting_for_server))
            .setLargeIcon(awaitingCover?.bitmap)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .addAction(androidx.mediarouter.R.drawable.ic_media_play_dark, getString(R.string.widget_play_pause), play)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))
            .build()
    try {
      NotificationManagerCompat.from(this).notify(notificationId, notification)
      awaitingNotificationShown = true
    } catch (e: SecurityException) {
      DLog.w(tag, "Cannot post the waiting notification: $e")
    }
  }

  // The resume start [ticket] ended without preparing its item (replaced, failed, the queue moved on): nothing shows it
  private fun dropResumingDisplay(ticket: StartGate.Ticket) {
    if (ticket !== resumingTicket) return
    resumingTicket = null
    if (awaitingPresentation.isResuming) awaitingPresentation.end()
  }

  internal fun onActiveServerChanged(previous: String?, id: String?) {
    if (previous != null && previous != id) {
      startGate.invalidate()
      // The resume of the old server's item went with its start
      if (awaitingPresentation.isResuming) {
        resumingTicket = null
        awaitingPresentation.end()
      }
    }
    val waitingFor = awaiting.serverConfigId
    if (waitingFor != null && waitingFor != id) onServerSwitchedWhileAwaiting()
  }

  private fun onServerSwitchedWhileAwaiting() {
    if (awaiting.isStreamLost) {
      // The player holds a dead stream of the other server: nothing will resume it
      DLog.i(tag, "Active server changed while a lost stream waited - closing it")
      stopAwaiting(CancelReason.SERVER_SWITCHED)
      clientEventEmitter?.onPlaybackFailed(lastStreamError.ifEmpty { "Server changed" })
      closePlayback(true)
    } else {
      stopAwaiting(CancelReason.SERVER_SWITCHED)
    }
  }

  private fun streamPermission(): StreamPermission {
    val metered = getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
    return StreamPermission(metered, deviceSettings.streamingUsingCellular)
  }

  /** The player's playWhenReady went false: a pause from outside unless the service made it itself. */
  fun onPlayWhenReadyOff() {
    if (pauseOrigin.isUserPause()) onPauseRequested()
  }

  /** Any pause: the app, a headset, the notification, audio becoming noisy, focus loss. */
  fun onPauseRequested() {
    if (awaiting.isAwaiting) awaiting.onUserPause()
    // A start that is still preparing then prepares paused
    startGate.pause()
  }

  private fun perform(action: AwaitingAction?) {
    when (action) {
      null -> return
      AwaitingAction.GiveUp -> {
        DLog.w(tag, "The server answers but resuming keeps failing - closing")
        awaitingPresentation.end()
        clientEventEmitter?.onPlaybackFailed(lastStreamError.ifEmpty { "Server play request failed" })
        closePlayback(true)
      }
      is AwaitingAction.ResumeStream -> {
        if (action.queueItem != null) awaitingPresentation.resuming() else awaitingPresentation.end()
        resume(action)
      }
      is AwaitingAction.SwitchToLocal -> {
        awaitingPresentation.end()
        switchToLocal(action)
      }
    }
  }

  private fun resume(action: AwaitingAction.ResumeStream) {
    val queued = action.queueItem
    if (queued != null) {
      val current = playlistQueue.getOrNull(playlistQueueIndex)
      if (current != queued) {
        DLog.i(tag, "Server is back but the queue moved on from ${queued.libraryItemId} - not resuming it")
        awaitingPresentation.end()
        return
      }
      DLog.i(tag, "Server is back - continuing the queue with ${current.libraryItemId}")
      resumingTicket = startQueueItem(current, action.playWhenReady)
      return
    }
    DLog.i(tag, "Server is back - resuming ${action.libraryItemId} at ${action.position}s (playWhenReady=${action.playWhenReady})")
    val rate = currentPlayer.playbackParameters.speed
    val ticket = startItem(StartRequest(action.libraryItemId, action.episodeId, serverConfigId = action.serverConfigId, startTime = action.position,
            playWhenReady = action.playWhenReady, playbackRate = rate, expectedTracks = action.expectedTracks)) { result ->
      if (result is StartResult.Failed) mainHandler.post { onResumeFailed(action, result) }
    }
    resumeTicket = ticket
  }

  private var resumeTicket: StartGate.Ticket? = null

  // Posted after the play request: its reachability report has landed
  private fun onResumeFailed(action: AwaitingAction.ResumeStream, failure: StartResult.Failed) {
    val ticket = resumeTicket ?: return
    // Null: something else started or closed meanwhile; false: paused during the request
    val intent = startGate.admit(ticket) ?: return
    val state = ServerReachability.state.value
    DLog.w(tag, "Resuming ${action.libraryItemId} failed (${failure.message}, HTTP ${failure.httpStatus}, server $state)")
    if (isServerGoneStatus(failure.httpStatus)) {
      // A proxy answered for a server that is down: the reachability check probes it
      ActiveServer.current()?.address?.let { ServerReachability.reportUnreachable(it) }
    } else if (state == ServerState.ONLINE) {
      lastStreamError = failure.message
    }
    val next = awaiting.onResumeFailed(action.copy(playWhenReady = intent), ServerReachability.state.value, failure.httpStatus)
    if (next != null) perform(next) else awaitingPresentation.begin(awaiting.blockedItem, null)
  }

  private fun onLocalCopyReady(ready: LocalCopyReady) {
    val session = currentPlaybackSession
    val stream = session?.takeIf { !it.isLocal }?.libraryItemId?.let { libraryItemId ->
      CurrentStream(libraryItemId, session.episodeId, session.serverConnectionConfigId, getCurrentTimeSeconds(), currentPlayer.playWhenReady,
              getMediaPlayer() == PLAYER_CAST)
    }
    perform(awaiting.onLocalCopyReady(ready, stream))
  }

  /** Plays the finished download instead of the stream (or the item a wait was for), without closing the player. */
  private fun switchToLocal(action: AwaitingAction.SwitchToLocal) {
    DLog.i(tag, "Download of ${action.libraryItemId} finished - switching to the file at ${action.position}s")
    val session = currentPlaybackSession
    val replacedStream = session?.takeIf { !it.isLocal && it.libraryItemId == action.libraryItemId }
    val config = DeviceManager.getServerConnectionConfig(session?.serverConnectionConfigId)
    val request = StartRequest(action.libraryItemId, action.episodeId, serverConfigId = session?.serverConnectionConfigId,
            startTime = action.position, playWhenReady = action.playWhenReady, playbackRate = currentPlayer.playbackParameters.speed,
            followPlayer = action.fromPlayer)
    // The stream's listened time and position are taken now (the sync takes its snapshot before sending); the answer
    // and the close of the server session come in the background, the file does not wait for them
    mediaProgressSyncer.stop(true) {
      replacedStream?.let { stream -> apiHandler.closePlaybackSession(stream.id, config) { DLog.d(tag, "Closed streamed session ${stream.id}") } }
    }
    startItem(request) { if (it is StartResult.Failed) DLog.e(tag, "Switch to the download of ${action.libraryItemId} failed: ${it.message}") }
  }

  internal fun stopAwaiting(reason: CancelReason) {
    DLog.i(tag, "Stopped waiting for the server ($reason)")
    awaiting.cancel(reason)
    awaitingPresentation.end()
  }

  /** The app replaced or cleared the queue: a queue that waited on an item no longer current stops waiting. */
  fun onQueueReplaced() {
    val current = playlistQueue.getOrNull(playlistQueueIndex)
    val blocked = awaiting.blockedItem
    if (blocked != null) {
      if (current != blocked) stopAwaiting(CancelReason.OTHER_ITEM_STARTED)
    } else if (awaitingPresentation.isResuming && awaitingPresentation.item != current) {
      resumingTicket = null
      awaitingPresentation.end()
    }
  }

  fun onPlaybackReady() = awaiting.onPlaybackReady()

  fun handlePlaybackEnded() {
    DLog.d(tag, "handlePlaybackEnded")
    if (isAndroidAuto && currentPlaybackSession?.isPodcastEpisode == true) {
      DLog.d(tag, "Podcast playback ended on android auto")
      val libraryItem = currentPlaybackSession?.libraryItem ?: return

      // Need to sync with server to set as finished
      mediaProgressSyncer.finished {
        // Need to reload media progress
        mediaManager.loadServerUserMediaProgress {
          val podcast = libraryItem.media as Podcast
          val nextEpisode = podcast.getNextUnfinishedEpisode(libraryItem.id, mediaManager)
          DLog.d(tag, "handlePlaybackEnded nextEpisode=$nextEpisode")
          nextEpisode?.let { podcastEpisode ->
            val playbackRate = mediaManager.getSavedPlaybackRate()
            mainHandler.post { startWrapper(libraryItem, podcastEpisode, true, playbackRate) }
          }
        }
      }
    }
  }

  sealed interface StartResult {
    data class Started(val session: PlaybackSession) : StartResult
    data class Failed(val message: String, val httpStatus: Int? = null) : StartResult
    /** A newer start, Close or a server switch came first: nothing was prepared. */
    object Replaced : StartResult
    /**
     * The queue's item can only stream and the server is away: the queue waits on it ([queueItemToAwait]). [display]:
     * what the player shows meanwhile, null when the replica has no row for it.
     */
    data class AwaitingServer(val display: AwaitingDisplay?) : StartResult
  }

  fun sourceResolver() = PlaybackSourceResolver({ DeviceManager.dbManager.getLocalLibraryItems() }, { DeviceManager.dbManager.getLocalLibraryItem(it) },
          { !AppStorage.isAppStorage(it.folderId) || AppStorage.isAvailable(it.folderId, AndroidVolumeProbe(ctx)) }) { item, episode ->
    item.hasTracks(ctx, episode)
  }

  /**
   * What [request] would play from now; a cast session streams (it cannot play a file on the phone). The copies and
   * their files are read off the main thread; call from main.
   */
  suspend fun planFor(request: StartRequest): PlaybackSource {
    val effective = if (getMediaPlayer() == PLAYER_CAST) request.copy(forceStream = true) else request
    val active = ActiveServer.current()?.id
    return withContext(Dispatchers.IO) { planStart(effective, active, sourceResolver()) }
  }

  // Which start holds the playback pin: a replaced start gives back only its own pin, never a newer start's
  private val pinOwnerLock = Any()
  @Volatile private var pinOwner: Any? = null

  /** Re-reads [plan]'s copy under its item lock and pins it for [owner], so a storage move cannot commit under it. */
  private suspend fun pinLocal(plan: PlaybackSource.Local, owner: Any): PlaybackSource = withContext(Dispatchers.IO) {
    synchronized(pinOwnerLock) {
      pinLocalSource(plan, { DeviceManager.dbManager.getLocalLibraryItem(it) }) { item, episode ->
        LocalPlaybackAvailability.check(item, AndroidVolumeProbe(ctx)) { !item.isInvalid && item.hasTracks(ctx, episode) }
      }.also { if (it is PlaybackSource.Local) pinOwner = owner }
    }
  }

  private fun failureMessage(source: PlaybackSource): String = when (source) {
    is PlaybackSource.NotFound -> source.message
    is PlaybackSource.StorageUnavailable -> LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE
    else -> NO_AUDIO_FILES_MESSAGE
  }

  // A start replaced after it pinned its copy gives the pin back to what plays now. Off main: a pin may wait on an item lock
  private fun releaseStalePin(owner: Any) {
    startScope.launch(Dispatchers.IO) {
      synchronized(pinOwnerLock) {
        // Close and a stream start drop the owner with the pin, so nothing is pinned again after them
        if (pinOwner !== owner) return@synchronized
        pinOwner = null
        val playing = currentPlaybackSession?.localLibraryItem?.id
        if (playing != null) {
          LocalItemLocks.pinForPlayback(playing)
        } else if (LocalItemLocks.clearPlaybackPin()) {
          StorageMoveService.requestReconcile(this@PlayerNotificationService)
        }
      }
    }
  }

  /** [planFor] for a caller without a coroutine; [onSource] runs on main. */
  fun resolveSource(request: StartRequest, onSource: (PlaybackSource) -> Unit) {
    mainHandler.post { startScope.launch { onSource(planFor(request)) } }
  }

  /**
   * The one start of an item for the app's Play, Android Auto, a resume after the server came back and a switch to a
   * finished download: a downloaded copy always plays, the stream is the fallback. Main thread. [onResult] gets the
   * session before it is prepared, or why nothing starts. A newer start, Close or a server switch before the session is
   * ready replaces this one; a Pause makes it prepare paused.
   */
  fun startItem(request: StartRequest, onResult: (StartResult) -> Unit = {}): StartGate.Ticket {
    val ticket = startGate.begin(request.playWhenReady)
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { runStart(request, ticket, onResult) }
    } else {
      runStart(request, ticket, onResult)
    }
    return ticket
  }

  private fun runStart(request: StartRequest, ticket: StartGate.Ticket, onResult: (StartResult) -> Unit) = startScope.launch {
    // Every wait below may outlive this start: a replaced one ends without touching the session that plays now
    fun replaced(): Boolean {
      if (startGate.admit(ticket) != null) return false
      DLog.i(tag, "Start of ${request.libraryItemId} was replaced (another start, Close or a server switch)")
      onResult(StartResult.Replaced)
      return true
    }
    val plan = planFor(request)
    if (replaced()) return@launch
    when (plan) {
      is PlaybackSource.NotFound -> onResult(StartResult.Failed(plan.message))
      is PlaybackSource.LocalBroken -> onResult(StartResult.Failed(NO_AUDIO_FILES_MESSAGE))
      is PlaybackSource.StorageUnavailable -> onResult(StartResult.Failed(LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE))
      is PlaybackSource.Local -> {
        val startTime = request.startTime ?: localStartTime(plan.item, plan.episode, request.libraryItemId)
        if (replaced()) return@launch
        val pinned = pinLocal(plan, ticket)
        if (replaced()) {
          releaseStalePin(ticket)
          return@launch
        }
        if (pinned !is PlaybackSource.Local) {
          onResult(StartResult.Failed(failureMessage(pinned)))
          return@launch
        }
        val session = pinned.item.getPlaybackSession(pinned.episode, getDeviceInfo(), startTime)
        // Concise on purpose: the serialized item contains file paths, SAF URIs and the server connection id
        DLog.d(tag, "startItem: downloaded item ${pinned.item.id} (server item ${pinned.item.libraryItemId ?: "none"}${pinned.episode?.let { e -> ", episode " + e.id } ?: ""}) for ${request.libraryItemId} start=${session.currentTime}s playWhenReady=${request.playWhenReady}")
        onResult(StartResult.Started(session))
        stopSyncerThen { prepareAdmitted(ticket, session, request) }
      }
      is PlaybackSource.Stream -> {
        // Without an active server there is nothing to wait for: the start fails as before
        val queued = ActiveServer.current()?.let { queueItemToAwait(request, plan, playlistQueue, playlistQueueIndex, ServerReachability.state.value) }
        if (queued != null) {
          awaitQueueStart(ticket, queued, onResult)
          return@launch
        }
        val payload = getPlayItemRequestPayload(false)
        stopSyncerThen {
          apiHandler.playLibraryItemWithStatus(plan.libraryItemId, plan.episodeId ?: "", payload) { session, httpStatus ->
            if (session == null) {
              onResult(StartResult.Failed("Server play request failed", httpStatus))
              return@playLibraryItemWithStatus
            }
            startScope.launch {
              if (replaced()) return@launch
              applyStreamStart(session, request.startTime)
              if (replaced()) return@launch
              DLog.d(tag, "startItem: streamed session ${session.id} for item ${session.libraryItemId}${session.episodeId?.let { e -> ", episode $e" } ?: ""} playMethod=${session.playMethod} start=${session.currentTime}s playWhenReady=${request.playWhenReady}")
              onResult(StartResult.Started(session))
              prepareAdmitted(ticket, session, request)
            }
          }
        }
      }
    }
  }

  /**
   * The app started the queue on an item it cannot stream now: what plays stops, the queue waits on the item, shown
   * from the replica. [onResult] gets Replaced when another start, Close, a server switch or a queue change came first.
   */
  private suspend fun awaitQueueStart(ticket: StartGate.Ticket, item: PlaylistQueueItem, onResult: (StartResult) -> Unit) {
    // Read before the pause below, which would turn the intent of this start into a paused one
    val playWhenReady = startGate.admit(ticket) ?: return onResult(StartResult.Replaced)
    if (currentPlayer.playWhenReady) {
      pauseOrigin.beforeSelfPause(true)
      currentPlayer.pause()
      mainHandler.post { pauseOrigin.settle() }
    }
    if (mediaProgressSyncer.listeningTimerRunning) mediaProgressSyncer.pause {}
    PlayerListener.lazyIsPlaying = false
    clientEventEmitter?.onPlayingUpdate(false)
    val serverConfigId = ActiveServer.current()?.id
    startAwaiting(
            read = {
              serverConfigId?.let { sc ->
                readWithin(startScope, AWAITING_READ_TIMEOUT_MS, { readAwaitingDisplay(item, sc) }) { publishAwaitingDisplay(item, it) }
              }
            },
            stillCurrent = { startGate.admit(ticket) != null && playlistQueue.getOrNull(playlistQueueIndex) == item },
            // A Pause during the read is kept
            block = { display -> blockQueue(item, startGate.admitAgain(ticket, playWhenReady) == true, display, readDisplay = false) },
            answer = { started -> onResult(if (started != null) StartResult.AwaitingServer(started.display) else StartResult.Replaced) },
            resumeIfOnline = { perform(awaiting.onServerState(ServerReachability.state.value, ServerReachability.serverConfigId.value, streamPermission())) })
  }

  private fun prepareAdmitted(ticket: StartGate.Ticket, session: PlaybackSession, request: StartRequest) {
    val playWhenReady = startGate.admit(ticket)
    if (playWhenReady == null) {
      DLog.i(tag, "Start of ${request.libraryItemId} was replaced (another start, Close or a server switch) - not preparing")
      releaseStalePin(ticket)
      return
    }
    if (request.followPlayer) {
      // Read just before the file replaces the stream, which kept playing meanwhile
      val stream = currentPlaybackSession
      if (stream != null && !stream.isLocal && stream.libraryItemId == request.libraryItemId) session.currentTime = getCurrentTimeSeconds()
    }
    sessionExpectedTracks = session.id to request.expectedTracks
    PlayerListener.lazyIsPlaying = false
    preparePlayer(session, playWhenReady, request.playbackRate)
  }

  // The server's track count the playing session was started with; a resume keeps it (see PlaybackSourceResolver)
  private var sessionExpectedTracks: Pair<String, Int?>? = null

  private fun expectedTracksOf(session: PlaybackSession): Int? =
          sessionExpectedTracks?.takeIf { it.first == session.id }?.second ?: (session.libraryItem?.media as? Book)?.numTracks

  /** Android Auto's browse-tree item (server item or device copy) through [startItem]. */
  fun startWrapper(wrapper: LibraryItemWrapper, episode: PodcastEpisode?, playWhenReady: Boolean, playbackRate: Float?) {
    startItem(StartRequest.forWrapper(wrapper, episode, playWhenReady, playbackRate)) {
      if (it !is StartResult.Failed) return@startItem
      DLog.e(tag, "Failed to play library item ${wrapper.id}: ${it.message}")
      if (it.message == LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE) mediaManager.onPlaybackError?.invoke(it.message)
    }
  }

  // Stops the progress syncer of what is playing before another session is prepared
  private fun stopSyncerThen(block: () -> Unit) {
    if (mediaProgressSyncer.listeningTimerRunning) {
      mediaProgressSyncer.stop { mainHandler.post(block) }
    } else {
      mediaProgressSyncer.reset()
      block()
    }
  }

  /** [requestedId]: the server id the start asked for, which may be another item than the copy's (found by ino). */
  private suspend fun localStartTime(item: LocalLibraryItem, episode: PodcastEpisode?, requestedId: String? = null): Double? {
    val row = progressRow(item.serverConnectionConfigId, item.libraryItemId, episode?.serverEpisodeId)
    val requestedRow = requestedId?.takeIf { !it.startsWith("local") && it != item.libraryItemId }
            ?.let { progressRow(item.serverConnectionConfigId, it, episode?.serverEpisodeId) }
    val paper = DeviceManager.dbManager.getLocalMediaProgress(item.localMediaProgressId(episode))
            ?.let { ProgressPoint(it.currentTime, it.lastUpdate, it.isFinished) }
    return chooseLocalStartTime(row, requestedRow, paper)
  }

  /**
   * The position a new stream session starts at: [explicit], else the device's `media_progress` row when it is newer
   * than the server's progress (a seek while paused offline is only there). The server is asked only when they differ.
   */
  private suspend fun applyStreamStart(session: PlaybackSession, explicit: Double?) {
    if (explicit != null) {
      session.currentTime = explicit
      return
    }
    val libraryItemId = session.libraryItemId ?: return
    val row = progressRow(session.serverConnectionConfigId, libraryItemId, session.episodeId) ?: return
    if (abs(row.currentTime - session.currentTime) <= 1.0) return
    val config = DeviceManager.getServerConnectionConfig(session.serverConnectionConfigId)
    val server = withTimeoutOrNull(SERVER_PROGRESS_TIMEOUT_MS) {
      suspendCancellableCoroutine<MediaProgress?> { cont -> apiHandler.getMediaProgress(libraryItemId, session.episodeId, config) { cont.resume(it) } }
    } ?: return // Not answered: the server's position of the session stands
    chooseStartTime(row, ProgressPoint(server.currentTime, server.lastUpdate, server.isFinished))?.let { session.currentTime = it }
  }

  private suspend fun progressRow(serverConfigId: String?, libraryItemId: String?, episodeId: String?): MediaProgressEntity? {
    if (serverConfigId == null || libraryItemId == null) return null
    return try {
      // In the write queue, so a position written just before (a pause, a seek) is read back
      withTimeoutOrNull(PROGRESS_ROW_TIMEOUT_MS) { SyncEngine.write { it.progressRow(serverConfigId, libraryItemId, episodeId) } }
    } catch (e: Exception) {
      DLog.w(tag, "Could not read media_progress for $libraryItemId: $e")
      null
    }
  }

  fun advancePlaylistQueue() {
    DLog.d(tag, "advancePlaylistQueue: called with queueSize=${playlistQueue.size}, currentIndex=$playlistQueueIndex")
    if (playlistQueue.isEmpty()) {
      DLog.d(tag, "advancePlaylistQueue: queue is empty, nothing to do")
      return
    }
    val nextIndex = playlistQueueIndex + 1
    if (nextIndex >= playlistQueue.size) {
      DLog.d(tag, "advancePlaylistQueue: end of queue (nextIndex=$nextIndex >= size=${playlistQueue.size})")
      playlistQueue = emptyList()
      playlistQueueIndex = -1
      return
    }
    playlistQueueIndex = nextIndex
    val nextItem = playlistQueue[nextIndex]
    DLog.d(tag, "advancePlaylistQueue: advancing to index $nextIndex, libraryItemId=${nextItem.libraryItemId}, episodeId=${nextItem.episodeId}")
    startQueueItem(nextItem, true)
  }

  private fun startQueueItem(nextItem: PlaylistQueueItem, playWhenReady: Boolean): StartGate.Ticket {
    val playbackRate = initialPlaybackRate ?: 1f

    // Acquire a temporary WakeLock to keep the CPU alive during playlist advancement
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiobookshelf:playlistAdvance")
    wakeLock.acquire(60_000L) // 60 second timeout
    val ticket = startGate.begin(playWhenReady)

    startScope.launch {
      val plan = planFor(StartRequest.forQueueItem(nextItem, playWhenReady, playbackRate))
      // A newer start, Close or a server switch came first: leave everything to it
      if (startGate.admit(ticket) == null) {
        dropResumingDisplay(ticket)
        if (wakeLock.isHeld) wakeLock.release()
        return@launch
      }
      when (plan) {
        is PlaybackSource.NotFound, is PlaybackSource.LocalBroken, is PlaybackSource.StorageUnavailable -> {
          // Downloads can be removed after queue setup. Never fall back to streaming.
          DLog.e(tag, "advancePlaylistQueue: ${nextItem.libraryItemId} has no playable download ($plan)")
          if (playlistQueue.getOrNull(playlistQueueIndex) === nextItem) {
            playlistQueue = emptyList()
            playlistQueueIndex = -1
          }
          dropResumingDisplay(ticket)
          if (wakeLock.isHeld) wakeLock.release()
        }
        is PlaybackSource.Local -> {
          val startTime = localStartTime(plan.item, plan.episode, nextItem.libraryItemId)
          if (startGate.admit(ticket) == null) {
            dropResumingDisplay(ticket)
            if (wakeLock.isHeld) wakeLock.release()
            return@launch
          }
          val pinned = pinLocal(plan, ticket)
          if (startGate.admit(ticket) == null) {
            releaseStalePin(ticket)
            dropResumingDisplay(ticket)
            if (wakeLock.isHeld) wakeLock.release()
            return@launch
          }
          if (pinned !is PlaybackSource.Local) {
            DLog.e(tag, "advancePlaylistQueue: ${nextItem.libraryItemId} has no playable download ($pinned)")
            if (playlistQueue.getOrNull(playlistQueueIndex) === nextItem) {
              playlistQueue = emptyList()
              playlistQueueIndex = -1
            }
            dropResumingDisplay(ticket)
            if (wakeLock.isHeld) wakeLock.release()
            return@launch
          }
          val playbackSession = pinned.item.getPlaybackSession(pinned.episode, getDeviceInfo(), startTime)
          DLog.d(tag, "advancePlaylistQueue: local session ready for ${pinned.item.id}")

          fun startLocalPlayback() {
            if (playlistQueue.getOrNull(playlistQueueIndex) === nextItem) {
              prepareQueued(ticket, playbackSession, playbackRate)
            } else {
              releaseStalePin(ticket)
              dropResumingDisplay(ticket)
            }
            if (wakeLock.isHeld) wakeLock.release()
          }

          // No progress anywhere on the device: this item may still have progress recorded on the server only (e.g.
          // listened to elsewhere before downloading)
          val serverLibraryItemId = pinned.item.libraryItemId
          val serverConnectionConfig = pinned.item.serverConnectionConfigId?.let { DeviceManager.getServerConnectionConfig(it) }
          if (playbackSession.currentTime == 0.0 && !serverLibraryItemId.isNullOrEmpty() && serverConnectionConfig != null && DeviceManager.checkConnectivity(ctx)) {
            DLog.d(tag, "advancePlaylistQueue: no local progress, checking server progress for $serverLibraryItemId")
            apiHandler.getMediaProgress(serverLibraryItemId, pinned.episode?.serverEpisodeId, serverConnectionConfig) { mediaProgress ->
              if (mediaProgress != null && !mediaProgress.isFinished && mediaProgress.currentTime > 0.0) {
                DLog.d(tag, "advancePlaylistQueue: found server progress, resuming from ${mediaProgress.currentTime}")
                playbackSession.currentTime = mediaProgress.currentTime
              }
              mainHandler.post { startLocalPlayback() }
            }
          } else {
            startLocalPlayback()
          }
        }
        is PlaybackSource.Stream -> {
          if (ServerReachability.state.value != ServerState.ONLINE) {
            blockQueueFor(ticket, nextItem)
            if (wakeLock.isHeld) wakeLock.release()
            return@launch
          }
          DLog.d(tag, "advancePlaylistQueue: requesting server item ${plan.libraryItemId}")
          // Acquire WiFi lock for server items to keep network alive during API call
          val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
          @Suppress("DEPRECATION")
          val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "audiobookshelf:playlistAdvance")
          wifiLock.acquire()

          // Stop progress syncer fire-and-forget (don't block on callback)
          mediaProgressSyncer.stop {
            DLog.d(tag, "advancePlaylistQueue: mediaProgressSyncer stopped (fire-and-forget)")
          }

          // Immediately request next item from server without waiting for sync to complete
          advancePlaylistQueueServerItem(nextItem, plan, ticket, playbackRate, wakeLock, wifiLock, 0)
        }
      }
    }
    return ticket
  }

  private fun prepareQueued(ticket: StartGate.Ticket, session: PlaybackSession, playbackRate: Float) {
    val playWhenReady = startGate.admit(ticket)
    if (playWhenReady == null) {
      DLog.i(tag, "advancePlaylistQueue: replaced by another start - not preparing")
      releaseStalePin(ticket)
      dropResumingDisplay(ticket)
      return
    }
    sessionExpectedTracks = session.id to playlistQueue.getOrNull(playlistQueueIndex)?.expectedTracks
    PlayerListener.lazyIsPlaying = false
    preparePlayer(session, playWhenReady, playbackRate)
  }

  // Waits on the queue item, unless another start or Close replaced this one meanwhile
  private fun blockQueueFor(ticket: StartGate.Ticket, item: PlaylistQueueItem) {
    val playWhenReady = startGate.admit(ticket) ?: return dropResumingDisplay(ticket)
    blockQueue(item, playWhenReady)
  }

  private fun advancePlaylistQueueServerItem(nextItem: PlaylistQueueItem, plan: PlaybackSource.Stream, ticket: StartGate.Ticket, playbackRate: Float, wakeLock: PowerManager.WakeLock, wifiLock: WifiManager.WifiLock, retryCount: Int) {
    if (playlistQueue.getOrNull(playlistQueueIndex) !== nextItem || startGate.admit(ticket) == null) {
      dropResumingDisplay(ticket)
      if (wifiLock.isHeld) wifiLock.release()
      if (wakeLock.isHeld) wakeLock.release()
      return
    }
    if (retryCount > 0 && ServerReachability.state.value != ServerState.ONLINE) {
      blockQueueFor(ticket, nextItem)
      if (wifiLock.isHeld) wifiLock.release()
      if (wakeLock.isHeld) wakeLock.release()
      return
    }
    DLog.d(tag, "advancePlaylistQueueServerItem: libraryItemId=${plan.libraryItemId}, episodeId=${plan.episodeId}, retry=$retryCount")
    val playItemRequestPayload = getPlayItemRequestPayload(false)
    apiHandler.playLibraryItem(plan.libraryItemId, plan.episodeId ?: "", playItemRequestPayload) { session ->
      if (session == null && retryCount < 3) {
        val delay = (retryCount + 1) * 2000L
        DLog.w(tag, "advancePlaylistQueue: Server play request failed for ${plan.libraryItemId}, retrying in ${delay}ms (attempt ${retryCount + 1}/3)")
        mainHandler.postDelayed({
          advancePlaylistQueueServerItem(nextItem, plan, ticket, playbackRate, wakeLock, wifiLock, retryCount + 1)
        }, delay)
      } else if (session != null) {
        DLog.d(tag, "advancePlaylistQueue: Got server session, calling preparePlayer for ${plan.libraryItemId}")
        startScope.launch {
          if (startGate.admit(ticket) != null) applyStreamStart(session, null)
          if (playlistQueue.getOrNull(playlistQueueIndex) === nextItem) prepareQueued(ticket, session, playbackRate) else dropResumingDisplay(ticket)
          if (wifiLock.isHeld) wifiLock.release()
          if (wakeLock.isHeld) wakeLock.release()
        }
      } else mainHandler.post {
        if (ServerReachability.state.value != ServerState.ONLINE && playlistQueue.getOrNull(playlistQueueIndex) === nextItem) {
          blockQueueFor(ticket, nextItem)
        } else {
          // The server answered with an error: nothing to wait for
          DLog.e(tag, "advancePlaylistQueue: Server play request failed for ${plan.libraryItemId} after ${retryCount + 1} attempts, giving up")
          dropResumingDisplay(ticket)
        }
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
      }
    }
  }

  fun startNewPlaybackSession() {
    currentPlaybackSession?.let { playbackSession ->
      DLog.i(tag, "Starting new playback session for ${playbackSession.displayTitle}")

      val forceTranscode = playbackSession.isHLS // If already HLS then force
      val playItemRequestPayload = getPlayItemRequestPayload(forceTranscode)

      val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
      val episodeId = playbackSession.episodeId
      mediaProgressSyncer.stop(false) {
        apiHandler.playLibraryItem(libraryItemId, episodeId, playItemRequestPayload) {
          if (it == null) {
            DLog.e(tag, "Failed to start new playback session")
          } else {
            DLog.d(
                    tag,
                    "New playback session response from server with session id ${it.id} for \"${it.displayTitle}\""
            )
            Handler(Looper.getMainLooper()).post { preparePlayer(it, true, null) }
          }
        }
      }
    }
  }

  fun switchToPlayer(useCastPlayer: Boolean) {
    val wasPlaying = currentPlayer.isPlaying
    if (useCastPlayer) {
      if (currentPlayer == castPlayer) {
        DLog.d(tag, "switchToPlayer: Already using Cast Player " + castPlayer?.deviceInfo)
        return
      } else {
        DLog.d(tag, "switchToPlayer: Switching to cast player from exo player stop exo player")
        mPlayer.stop()
      }
    } else {
      if (currentPlayer == mPlayer) {
        DLog.d(tag, "switchToPlayer: Already using Exo Player " + mPlayer.deviceInfo)
        return
      } else if (castPlayer != null) {
        DLog.d(tag, "switchToPlayer: Switching to exo player from cast player stop cast player")
        castPlayer?.stop()
      }
    }

    if (currentPlaybackSession == null) {
      DLog.e(tag, "switchToPlayer: No Current playback session")
    } else {
      isSwitchingPlayer = true
    }

    // Playback session in progress syncer is a copy that is up-to-date so replace current here with
    // that
    //  TODO: bad design here implemented to prevent the session in MediaProgressSyncer from
    // changing while syncing
    if (mediaProgressSyncer.currentPlaybackSession != null) {
      currentPlaybackSession = mediaProgressSyncer.currentPlaybackSession?.clone()
    }

    currentPlayer =
            if (useCastPlayer) {
              DLog.d(tag, "switchToPlayer: Using Cast Player " + castPlayer?.deviceInfo)
              mediaSessionConnector.setPlayer(castPlayer)
              playerNotificationManager.setPlayer(castPlayer?.let(::notificationControls))
              setMediaSessionToCastVolume()
              castPlayer as CastPlayer
            } else {
              DLog.d(tag, "switchToPlayer: Using ExoPlayer")
              mediaSessionConnector.setPlayer(mPlayer)
              playerNotificationManager.setPlayer(notificationControls(mPlayer))
              setMediaSessionToLocalVolume()
              mPlayer
            }

    republishAwaitingSession()
    clientEventEmitter?.onMediaPlayerChanged(getMediaPlayer())

    currentPlaybackSession?.let {
      DLog.d(tag, "switchToPlayer: Starting new playback session ${it.displayTitle}")
      if (wasPlaying) { // media is paused when switching players
        clientEventEmitter?.onPlayingUpdate(false)
      }

      // TODO: Start a new playback session here instead of using the existing
      preparePlayer(it, false, null)
    }
  }

  private fun setMediaSessionToCastVolume() {
    val currentVol = try { castPlayer?.getDeviceVolume() ?: 0 } catch (_: Exception) { 0 }
    val provider = object : VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE, 100, currentVol) {
      override fun onSetVolumeTo(volume: Int) {
        // Clamp, update UI immediately, then send to device
        val clamped = volume.coerceIn(0, 100)
        setCurrentVolume(clamped)
        try { castPlayer?.setDeviceVolume(clamped) } catch (_: Exception) {}
      }

      override fun onAdjustVolume(direction: Int) {
        // Use Android-provided step (−1, 0, +1). Clamp, update UI immediately, then send.
        val current = currentVolume
        val target = (current + direction).coerceIn(0, 100)
        setCurrentVolume(target)
        try { castPlayer?.setDeviceVolume(target) } catch (_: Exception) {}
      }
    }
    remoteVolumeProvider = provider
    mediaSession.setPlaybackToRemote(provider)
  }

  private fun setMediaSessionToLocalVolume() {
    mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
    remoteVolumeProvider = null
  }

  fun getCurrentTrackStartOffsetMs(): Long {
    return if (currentPlayer.mediaItemCount > 1) {
      val windowIndex = currentPlayer.currentMediaItemIndex
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(windowIndex) ?: 0L
      currentTrackStartOffset
    } else {
      0
    }
  }

  fun getCurrentTime(): Long {
    return currentPlayer.currentPosition + getCurrentTrackStartOffsetMs()
  }

  fun getCurrentTimeSeconds(): Double {
    return getCurrentTime() / 1000.0
  }

  private fun getBufferedTime(): Long {
    return if (currentPlayer.mediaItemCount > 1) {
      val windowIndex = currentPlayer.currentMediaItemIndex
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(windowIndex) ?: 0L
      currentPlayer.bufferedPosition + currentTrackStartOffset
    } else {
      currentPlayer.bufferedPosition
    }
  }

  fun getBufferedTimeSeconds(): Double {
    return getBufferedTime() / 1000.0
  }

  fun getDuration(): Long {
    return currentPlaybackSession?.totalDurationMs ?: 0L
  }

  /**
   * Keeps the widget's last-known position current: this session's currentTime (read once the player
   * is gone in this process) and the persisted last session (read after a process restart). Called
   * with the position the progress syncer samples (sync ticks, pause, stop). Display only.
   */
  fun rememberPlaybackPosition(sessionId: String?, currentTime: Double) {
    val session = currentPlaybackSession ?: return
    if (sessionId == null || session.id != sessionId) return
    session.currentTime = currentTime
    DeviceManager.updateLastPlaybackPosition(sessionId, currentTime)
    // While playing, the same sample keeps the widget's time and progress moving (about every sync
    // tick). A pause is drawn by the play-state change, so a paused clock is never redrawn.
    if (currentPlayer.isPlaying) showWidgetPosition(sessionId, currentTime)
  }

  /** Shows an already-sampled position of this session on the widget. Presentation only. */
  fun showWidgetPosition(sessionId: String?, currentTime: Double?) {
    val session = currentPlaybackSession ?: return
    if (sessionId == null || currentTime == null || session.id != sessionId) return
    DeviceManager.widgetUpdater?.onPlaybackPosition(this, (currentTime * 1000).toLong())
  }

  fun getCurrentPlaybackSessionCopy(): PlaybackSession? {
    return currentPlaybackSession?.clone()
  }

  fun getCurrentBookChapter(): BookChapter? {
    return currentPlaybackSession?.getChapterForTime(this.getCurrentTime())
  }

  fun getEndTimeOfChapterOrTrack(): Long? {
    return getCurrentBookChapter()?.endMs ?: currentPlaybackSession?.getCurrentTrackEndTime()
  }

  private fun getNextBookChapter(): BookChapter? {
    return currentPlaybackSession?.getNextChapterForTime(this.getCurrentTime())
  }

  fun getEndTimeOfNextChapterOrTrack(): Long? {
    return getNextBookChapter()?.endMs ?: currentPlaybackSession?.getNextTrackEndTime()
  }

  // Called from PlayerListener play event
  // check with server if progress has updated since last play and sync progress update
  fun checkCurrentSessionProgress(seekBackTime: Long): Boolean {
    if (currentPlaybackSession == null) return true

    mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
      if (!DeviceManager.checkConnectivity(ctx)) {
        return true // carry on
      }

      if (playbackSession.isLocal) {

        // Make sure this connection config exists
        val serverConnectionConfig =
                DeviceManager.getServerConnectionConfig(playbackSession.serverConnectionConfigId)
        if (serverConnectionConfig == null) {
          DLog.d(
                  tag,
                  "checkCurrentSessionProgress: Local library item server connection config is not saved ${playbackSession.serverConnectionConfigId}"
          )
          return true // carry on
        }

        // Local playback session check if server has updated media progress
        DLog.d(
                tag,
                "checkCurrentSessionProgress: Checking if local media progress was updated on server"
        )
        apiHandler.getMediaProgress(
                playbackSession.libraryItemId!!,
                playbackSession.episodeId,
                serverConnectionConfig
        ) { mediaProgress ->
          if (mediaProgress != null &&
                          mediaProgress.lastUpdate > playbackSession.updatedAt &&
                          mediaProgress.currentTime != playbackSession.currentTime
          ) {
            DLog.d(
                    tag,
                    "checkCurrentSessionProgress: Media progress was updated since last play time updating from ${playbackSession.currentTime} to ${mediaProgress.currentTime}"
            )
            mediaProgressSyncer.syncFromServerProgress(mediaProgress)

            // Update current playback session stored in PNS since MediaProgressSyncer version is a
            // copy
            mediaProgressSyncer.currentPlaybackSession?.let { updatedPlaybackSession ->
              currentPlaybackSession = updatedPlaybackSession
            }

            Handler(Looper.getMainLooper()).post {
              seekPlayer(playbackSession.currentTimeMs)
              // Should already be playing
              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              currentPlaybackSession?.let { mediaProgressSyncer.play(it) }
              clientEventEmitter?.onPlayingUpdate(true)
            }
          } else {
            Handler(Looper.getMainLooper()).post {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }

              // Should already be playing
              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
                mediaProgressSyncer.play(playbackSession)
              }
              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      } else {
        // Streaming from server so check if playback session still exists on server
        DLog.d(
                tag,
                "checkCurrentSessionProgress: Checking if playback session ${playbackSession.id} for server stream is still available"
        )
        apiHandler.getPlaybackSession(playbackSession.id) {
          if (it == null) {
            DLog.d(
                    tag,
                    "checkCurrentSessionProgress: Playback session does not exist on server - start new playback session"
            )

            Handler(Looper.getMainLooper()).post {
              currentPlayer.pause()
              startNewPlaybackSession()
            }
          } else {
            DLog.d(tag, "checkCurrentSessionProgress: Playback session still available on server")
            Handler(Looper.getMainLooper()).post {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }

              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
                mediaProgressSyncer.play(playbackSession)
              }

              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      }
    }
    return false
  }

  fun play() {
    if (awaiting.isAwaiting) {
      // The player has nothing to play while waiting: start again now if the server is back, else probe it. Before the
      // restore below: a queue may wait with no session, and the last session is not what it waits on
      val action = awaiting.onUserPlay(ServerReachability.state.value, ServerReachability.serverConfigId.value, streamPermission())
      if (action == null) ServerReachability.retryNow() else perform(action)
      return
    }
    if (awaitingPresentation.isResuming) {
      // The awaited item is starting: Play is for it, not for the session still open under its title
      startGate.play()
      return
    }
    if (currentPlaybackSession == null) {
      // Widget/headset/notification/system Play reached a service with nothing prepared
      // (service or process was recreated). Rebuild the last session instead of no-op'ing.
      DLog.marker("Playback restoration requested")
      DLog.i(RESTORE_TAG, "Play received with no prepared session - attempting restore")
      if (!restoreLastPlaybackSession(true)) onMediaButtonHandled()
      return
    }
    DLog.i(RESTORE_TAG, "Play received - using existing player/session ${currentPlaybackSession?.mediaItemId}")
    if (currentPlayer.isPlaying) {
      DLog.d(tag, "Already playing")
      return
    }
    currentPlayer.volume = 1F
    currentPlayer.play()
  }

  /**
   * Rebuilds the last playback session (identity from DeviceData.lastPlaybackSession, position from
   * local/server progress, rate from saved user settings, queue from PlaybackRestoreStore).
   * Only runs for an explicit play command; never at app launch.
   * @return true if a restore was started
   */
  fun restoreLastPlaybackSession(playWhenReady: Boolean): Boolean {
    if (isRestoringPlayback) {
      DLog.i(RESTORE_TAG, "Restore already in progress")
      return true
    }
    val saved = DeviceManager.deviceData.lastPlaybackSession
    if (saved == null || !PlaybackRestoreStore.isResumable(this)) {
      DLog.i(RESTORE_TAG, "Nothing to restore (hasLastSession=${saved != null}, resumable=${PlaybackRestoreStore.isResumable(this)})")
      return false
    }

    isRestoringPlayback = true
    val playbackRate = mediaManager.getSavedPlaybackRate()
    DLog.i(RESTORE_TAG, "Restoring ${if (saved.isLocal) "downloaded" else "streamed"} item ${saved.mediaItemId} | rate=$playbackRate")

    // A stream downloaded since plays the copy; a copy that is gone is not streamed instead. Read off the main thread
    startScope.launch {
      val resolved = withContext(Dispatchers.IO) { sourceResolver().restoreSource(saved) }
      when (val source = if (resolved is PlaybackSource.Local) pinLocal(resolved, Any()) else resolved) {
        is PlaybackSource.NotFound -> failRestore("downloaded item no longer exists (${source.message})", permanent = true)
        is PlaybackSource.LocalBroken -> failRestore("downloaded item has no playable files", permanent = true)
        is PlaybackSource.StorageUnavailable -> failRestore("sd card not available", permanent = false)
        is PlaybackSource.Local -> {
          val session = source.item.getPlaybackSession(source.episode, getDeviceInfo(), localStartTime(source.item, source.episode))
          finishRestore(session, playWhenReady, playbackRate)
        }
        is PlaybackSource.Stream -> restoreStream(saved, source, playWhenReady, playbackRate)
      }
    }
    return true
  }

  private fun restoreStream(saved: PlaybackSession, stream: PlaybackSource.Stream, playWhenReady: Boolean, playbackRate: Float) {
    val config = DeviceManager.getServerConnectionConfig(saved.serverConnectionConfigId)
    val libraryItemId = stream.libraryItemId
    if (config == null || libraryItemId.isEmpty()) {
      failRestore("server connection for streamed item no longer saved", permanent = true)
      return
    }
    val activeConfigId = DeviceManager.serverConnectionConfig?.id ?: DeviceManager.deviceData.lastServerConnectionConfigId
    if (activeConfigId != config.id) {
      failRestore("streamed item belongs to a server that is not the current one", permanent = false)
      return
    }
    if (!DeviceManager.checkConnectivity(this)) {
      failRestore("no network for streamed item", permanent = false)
      return
    }
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
    val streamingSetting = deviceSettings.streamingUsingCellular
    if (connectivityManager?.isActiveNetworkMetered == true && streamingSetting != StreamingUsingCellularSetting.ALWAYS) {
      failRestore("streaming on cellular is not allowed without confirmation ($streamingSetting)", permanent = false)
      return
    }
    if (DeviceManager.serverConnectionConfig == null) {
      DLog.i(RESTORE_TAG, "Using saved server connection for restore")
      DeviceManager.serverConnectionConfig = config
      ActiveServer.remember(config.id)
    }

    // A new server session returns the server's current progress for this item
    apiHandler.playLibraryItem(libraryItemId, stream.episodeId, getPlayItemRequestPayload(false)) { session ->
      startScope.launch {
        if (session == null) {
          failRestore("server did not return a playback session", permanent = false)
        } else {
          applyStreamStart(session, null)
          finishRestore(session, playWhenReady, playbackRate)
        }
      }
    }
  }

  private fun finishRestore(session: PlaybackSession, playWhenReady: Boolean, playbackRate: Float) {
    isRestoringPlayback = false
    if (isServiceDestroyed || serviceStopper.isStopPending) {
      // Preparing here would start a new service instance that nothing makes foreground
      DLog.w(RESTORE_TAG, "Service destroyed or stopping before restore finished - dropping")
      return
    }
    if (currentPlaybackSession != null) {
      DLog.i(RESTORE_TAG, "Another session started while restoring - keeping it")
      return
    }

    val savedQueue = PlaybackRestoreStore.loadQueue(this)
    if (PlaybackRestoreStore.queueForRestoredItem(savedQueue, session) != null) {
      playlistQueue = savedQueue.items
      playlistQueueIndex = savedQueue.index
      DLog.i(RESTORE_TAG, "Queue restored (${savedQueue.items.size} items, index ${savedQueue.index})")
    } else if (savedQueue.items.isNotEmpty()) {
      DLog.i(RESTORE_TAG, "Saved queue does not match restored item - not restoring queue")
    }

    DLog.i(RESTORE_TAG, "Session rebuilt for ${session.mediaItemId} | position=${session.currentTime}s | rate=$playbackRate | playWhenReady=$playWhenReady")
    preparePlayer(session, playWhenReady, playbackRate)
  }

  private fun failRestore(reason: String, permanent: Boolean) {
    isRestoringPlayback = false
    DLog.w(RESTORE_TAG, "Restore failed: $reason (permanent=$permanent)")
    if (permanent) {
      PlaybackRestoreStore.setResumable(this, false)
      DeviceManager.widgetUpdater?.onPlayerClosed()
    }
    onMediaButtonHandled()
  }

  fun pause() {
    onPauseRequested()
    currentPlayer.pause()
  }

  fun playPause(): Boolean {
    return if (currentPlayer.isPlaying) {
      pause()
      false
    } else {
      play()
      true
    }
  }

  fun seekPlayer(time: Long) {
    var timeToSeek = time
    DLog.d(tag, "seekPlayer mediaCount = ${currentPlayer.mediaItemCount} | $timeToSeek")
    if (timeToSeek < 0) {
      DLog.w(tag, "seekPlayer invalid time $timeToSeek - setting to 0")
      timeToSeek = 0L
    } else if (timeToSeek > getDuration()) {
      DLog.w(tag, "seekPlayer invalid time $timeToSeek - setting to MAX - 2000")
      timeToSeek = getDuration() - 2000L
    }
    if (awaiting.isAwaiting) awaiting.onUserSeek(timeToSeek / 1000.0)

    if (currentPlayer.mediaItemCount > 1) {
      currentPlaybackSession?.currentTime = timeToSeek / 1000.0
      val newWindowIndex = currentPlaybackSession?.getCurrentTrackIndex() ?: 0
      val newTimeOffset = currentPlaybackSession?.getCurrentTrackTimeMs() ?: 0
      DLog.d(tag, "seekPlayer seekTo $newWindowIndex | $newTimeOffset")
      currentPlayer.seekTo(newWindowIndex, newTimeOffset)
    } else {
      currentPlayer.seekTo(timeToSeek)
    }
  }

  fun skipToPrevious() {
    navigateExternal(false)
  }

  fun skipToNext() {
    navigateExternal(true)
  }

  private fun navigateExternal(forward: Boolean, mode: String? = deviceSettings.externalMediaNavigation) {
    val session = currentPlaybackSession ?: return
    if (!this::currentPlayer.isInitialized || currentPlayer.mediaItemCount == 0) return
    val target = ExternalNavigation.target(mode, forward, session, getCurrentTime(),
      currentPlayer.currentMediaItemIndex, currentPlayer.mediaItemCount,
      deviceSettings.jumpForwardTimeMs, deviceSettings.jumpBackwardsTimeMs) ?: return
    DLog.d(tag, "External navigation mode=${ExternalNavigation.normalize(mode)} forward=$forward target=$target")
    // Use the established absolute-time seek, including its multi-file mapping and progress callbacks.
    // Never use seekToNext(): at the end it can leave the current book/episode.
    seekPlayer(target)
  }

  fun jumpForward() {
    navigateExternal(true, "time")
  }

  fun jumpBackward() {
    navigateExternal(false, "time")
  }

  /** Notification FF/RW must use current settings, rather than the increments captured at player creation. */
  internal fun notificationControls(player: Player): Player = object : ForwardingPlayer(player) {
    // While an item is awaited its notification shows it: the keys must not move or play the open session
    override fun seekForward() { if (awaitingDisplay == null) jumpForward() }
    override fun seekBack() { if (awaitingDisplay == null) jumpBackward() }
    override fun play() { if (awaiting.isAwaiting || awaitingDisplay != null) this@PlayerNotificationService.play() else super.play() }
    override fun getSeekForwardIncrement(): Long = deviceSettings.jumpForwardTimeMs
    override fun getSeekBackIncrement(): Long = deviceSettings.jumpBackwardsTimeMs
  }

  fun seekForward(amount: Long) {
    seekPlayer(getCurrentTime() + amount)
  }

  fun seekBackward(amount: Long) {
    seekPlayer(getCurrentTime() - amount)
  }

  fun setPlaybackSpeed(speed: Float) {
    mediaManager.userSettingsPlaybackRate = speed
    currentPlayer.setPlaybackSpeed(speed)

    // Refresh Android Auto actions
    mediaProgressSyncer.currentPlaybackSession?.let { setMediaSessionConnectorCustomActions(it) }
  }

  fun closePlayback(calledOnError: Boolean? = false) {
    if (awaiting.isAwaiting) stopAwaiting(CancelReason.CLOSED) else awaitingPresentation.end()
    startGate.invalidate()
    playlistQueue = emptyList()
    playlistQueueIndex = -1
    pinOwner = null
    if (LocalItemLocks.clearPlaybackPin()) StorageMoveService.requestReconcile(this)
    DLog.d(tag, "closePlayback")
    val config = DeviceManager.serverConnectionConfig

    val isLocal = mediaProgressSyncer.currentIsLocal
    val currentSessionId = mediaProgressSyncer.currentSessionId
    if (mediaProgressSyncer.listeningTimerRunning) {
      DLog.i(tag, "About to close playback so stopping media progress syncer first")

      mediaProgressSyncer.stop(
              calledOnError == false
      ) { // If closing on error then do not sync progress (causes exception)
        DLog.d(tag, "Media Progress syncer stopped")
        // If not local session then close on server
        if (!isLocal && currentSessionId != "") {
          apiHandler.closePlaybackSession(currentSessionId, config) {
            DLog.d(tag, "Closed playback session $currentSessionId")
          }
        }
      }
    } else {
      // If not local session then close on server
      if (!isLocal && currentSessionId != "") {
        apiHandler.closePlaybackSession(currentSessionId, config) {
          DLog.d(tag, "Closed playback session $currentSessionId")
        }
      }
    }

    try {
      currentPlayer.stop()
      currentPlayer.clearMediaItems()
    } catch (e: Exception) {
      DLog.e(tag, "Exception clearing exoplayer $e")
    }

    currentPlaybackSession = null
    mediaProgressSyncer.reset()
    clientEventEmitter?.onPlaybackClosed()

    // An explicit close (or fatal playback error) must not be revived by a later widget/headset Play
    PlaybackRestoreStore.setResumable(this, false)

    PlayerListener.lastPauseTime = 0
    isClosed = true
    DeviceManager.widgetUpdater?.onPlayerClosed()
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  fun sendClientMetadata(playerState: PlayerState) {
    val duration = currentPlaybackSession?.getTotalDuration() ?: 0.0
    clientEventEmitter?.onMetadata(PlaybackMetadata(duration, getCurrentTimeSeconds(), playerState))
  }

  fun getMediaPlayer(): String {
    return if (currentPlayer == castPlayer) PLAYER_CAST else PLAYER_EXO
  }

  @SuppressLint("HardwareIds")
  fun getDeviceInfo(): DeviceInfo {
    /* EXAMPLE
     manufacturer: Google
     model: Pixel 6
     brand: google
     sdkVersion: 32
     appVersion: 0.9.46-beta
    */
    val deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    return DeviceInfo(
            deviceId,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME
    )
  }

  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload {
    return PlayItemRequestPayload(
            getMediaPlayer(),
            !forceTranscode,
            forceTranscode,
            getDeviceInfo()
    )
  }

  fun getContext(): Context {
    return ctx
  }

  fun alertSyncFailing() {
    clientEventEmitter?.onProgressSyncFailing()
  }

  fun alertSyncSuccess() {
    clientEventEmitter?.onProgressSyncSuccess()
  }

  //
  // MEDIA BROWSER STUFF (ANDROID AUTO)
  //
  private val VALID_MEDIA_BROWSERS =
          mutableListOf(
                  "app.absplus.android",
                  "app.absplus.android.debug",
                  ANDROID_AUTO_PKG_NAME,
                  ANDROID_AUTO_SIMULATOR_PKG_NAME,
                  ANDROID_WEARABLE_PKG_NAME,
                  ANDROID_GSEARCH_PKG_NAME,
                  ANDROID_AUTOMOTIVE_PKG_NAME
          )

  private val AUTO_MEDIA_ROOT = "/"
  private val LIBRARIES_ROOT = "__LIBRARIES__"
  private val RECENTLY_ROOT = "__RECENTLY__"
  private val DOWNLOADS_ROOT = "__DOWNLOADS__"
  private val CONTINUE_ROOT = "__CONTINUE__"
  private lateinit var browseTree: BrowseTree
  private val browseTreeInitListeners = mutableListOf<() -> Unit>()

  private fun waitForBrowseTree(cb: () -> Unit)
  {
    if (this::browseTree.isInitialized)
    {
      cb()
    }
    else
    {
      browseTreeInitListeners += cb
    }
  }

  private fun onBrowseTreeInitialized()
  {
    // Called after browseTree is assigned for the first time
    browseTreeInitListeners.forEach { it.invoke() }
    browseTreeInitListeners.clear()
  }

  /**
   * The Android Auto root before the server load has finished: only entries whose children can be served without
   * it. That is Downloads (local, allowed before firstLoadDone); Continue, Recent and Libraries are refused until
   * the first load is done, so they appear with the refreshed root.
   */
  internal fun provisionalAutoRootItems(): MutableList<MediaBrowserCompat.MediaItem> =
    BrowseTree(this, emptyList(), emptyList(), false)[AUTO_MEDIA_ROOT].orEmpty().map { item ->
      MediaBrowserCompat.MediaItem(item.description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }.toMutableList()

  /** Called by [autoRootLoader] on the load's thread once the server checks and libraries have finished. */
  private fun onAndroidAutoItemsLoaded() {
    AbsLogger.info(tag, "onLoadChildren: Loaded Android Auto data, initializing browseTree")
    browseTree =
            BrowseTree(
                    this,
                    mediaManager.serverItemsInProgress,
                    mediaManager.serverLibraries,
                    mediaManager.allLibraryPersonalizationsDone
            )
    onBrowseTreeInitialized()
    firstLoadDone = true
    if (mediaManager.serverLibraries.isNotEmpty()) {
      AbsLogger.info(tag, "onLoadChildren: Android Auto fetching personalized data for all libraries")
      mediaManager.populatePersonalizedDataForAllLibraries {
        AbsLogger.info(tag, "onLoadChildren: Android Auto loaded personalized data for all libraries")
        notifyChildrenChanged("/")
      }

      AbsLogger.info(tag, "onLoadChildren: Android Auto fetching in progress items")
      mediaManager.initializeInProgressItems {
        AbsLogger.info(tag, "onLoadChildren: Android Auto loaded in progress items")
        notifyChildrenChanged("/")
      }
    }
  }

  private val autoRootLoader = AutoRootLoader(
    load = { onDone -> mediaManager.loadAndroidAutoItems(onDone) },
    onLoaded = { onAndroidAutoItemsLoaded() },
    notifyRootChanged = { notifyChildrenChanged(AUTO_MEDIA_ROOT) }
  )

  // Only allowing android auto or similar to access media browser service
  //  normal loading of audiobooks is handled in webview (not natively)
  private fun isValid(packageName: String, uid: Int): Boolean {
    DLog.d(tag, "onGetRoot: Checking package $packageName with uid $uid")
    if (!VALID_MEDIA_BROWSERS.contains(packageName)) {
      DLog.d(tag, "onGetRoot: package $packageName not valid for the media browser service")
      return false
    }
    return true
  }

  override fun onGetRoot(
          clientPackageName: String,
          clientUid: Int,
          rootHints: Bundle?
  ): BrowserRoot? {
    // Verify that the specified package is allowed to access your content
    return if (!isValid(clientPackageName, clientUid)) {
      // No further calls will be made to other media browsing methods.
      null
    } else {
      AbsLogger.info(tag, "onGetRoot: clientPackageName: $clientPackageName, clientUid: $clientUid")
      isStarted = true

      // Reset cache if no longer connected to server or server changed
      if (mediaManager.checkResetServerItems()) {
        AbsLogger.info(tag, "onGetRoot: Reset Android Auto server items cache (${DeviceManager.serverConnectionConfigString})")
        forceReloadingAndroidAuto = true
      }

      isAndroidAuto = true

      val extras = Bundle()
      extras.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
      extras.putInt(
              MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
              MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
      )
      extras.putInt(
              MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
              MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
      )

      BrowserRoot(AUTO_MEDIA_ROOT, extras)
    }
  }

  override fun onLoadChildren(
          parentMediaId: String,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    AbsLogger.info(tag, "onLoadChildren: parentMediaId: $parentMediaId (${DeviceManager.serverConnectionConfigString})")

    result.detach()

    // Prevent crashing if app is restarted while browsing (nodes that need no server data are always served)
    if (!servableBeforeFirstLoad(parentMediaId) && !firstLoadDone) {
      result.sendResult(null)
      return
    }

    if (parentMediaId == DOWNLOADS_ROOT) { // Load downloads
      val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
      val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")
      val localBrowseItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

      localBooks.forEach { localLibraryItem ->
        if (localLibraryItem.media.getAudioTracks().isNotEmpty()) {
          val progress = DeviceManager.dbManager.getLocalMediaProgress(localLibraryItem.id)
          val description = localLibraryItem.getMediaDescription(progress, ctx)

          localBrowseItems +=
                  MediaBrowserCompat.MediaItem(
                          description,
                          MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                  )
        }
      }

      localPodcasts.forEach { localLibraryItem ->
        val mediaDescription = localLibraryItem.getMediaDescription(null, ctx)
        localBrowseItems +=
                MediaBrowserCompat.MediaItem(
                        mediaDescription,
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                )
      }

      result.sendResult(localBrowseItems)
    } else if (parentMediaId == CONTINUE_ROOT) {
      val localBrowseItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      mediaManager.serverItemsInProgress.forEach { itemInProgress ->
        val progress: MediaProgressWrapper?
        val mediaDescription: MediaDescriptionCompat
        if (itemInProgress.episode != null) {
          if (itemInProgress.isLocal) {
            progress =
                    DeviceManager.dbManager.getLocalMediaProgress(
                            "${itemInProgress.libraryItemWrapper.id}-${itemInProgress.episode.id}"
                    )
          } else {
            progress =
                    mediaManager.serverUserMediaProgress.find {
                      it.libraryItemId == itemInProgress.libraryItemWrapper.id &&
                              it.episodeId == itemInProgress.episode.id
                    }

            // to show download icon
            val localLibraryItem =
                    DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(
                            itemInProgress.libraryItemWrapper.id,
                            (itemInProgress.libraryItemWrapper as LibraryItem).ino
                    )
            localLibraryItem?.let { lli ->
              val localEpisode =
                      (lli.media as Podcast).episodes?.find {
                        it.serverEpisodeId == itemInProgress.episode.id
                      }
              itemInProgress.episode.localEpisodeId = localEpisode?.id
            }
          }
          mediaDescription =
                  itemInProgress.episode.getMediaDescription(
                          itemInProgress.libraryItemWrapper,
                          progress,
                          ctx
                  )
        } else {
          if (itemInProgress.isLocal) {
            progress =
                    DeviceManager.dbManager.getLocalMediaProgress(
                            itemInProgress.libraryItemWrapper.id
                    )
          } else {
            progress =
                    mediaManager.serverUserMediaProgress.find {
                      it.libraryItemId == itemInProgress.libraryItemWrapper.id
                    }

            val localLibraryItem =
                    DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(
                            itemInProgress.libraryItemWrapper.id,
                            (itemInProgress.libraryItemWrapper as LibraryItem).ino
                    )
            (itemInProgress.libraryItemWrapper as LibraryItem).localLibraryItemId =
                    localLibraryItem?.id // To show downloaded icon
          }
          mediaDescription = itemInProgress.libraryItemWrapper.getMediaDescription(progress, ctx)
        }
        localBrowseItems +=
                MediaBrowserCompat.MediaItem(
                        mediaDescription,
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
      }
      result.sendResult(localBrowseItems)
    } else if (parentMediaId == AUTO_MEDIA_ROOT) {
      DLog.d(tag, "Trying to initialize browseTree.")
      if (!this::browseTree.isInitialized || forceReloadingAndroidAuto || autoRootLoader.isLoading) {
        forceReloadingAndroidAuto = false
        // Answer at once with what needs no server (Downloads), instead of waiting for the server checks, which
        // can take 3 s per unreachable server or about 30 s for a stalled one (Discussion #30). The full root
        // follows through notifyChildrenChanged("/") when the load finishes; a load already running is reused.
        AbsLogger.info(tag, "onLoadChildren: Loading Android Auto items (provisional root sent)")
        result.sendResult(provisionalAutoRootItems())
        autoRootLoader.reload()
      } else {
        DLog.d(tag, "Starting browseTree refresh")
        browseTree =
                BrowseTree(
                        this,
                        mediaManager.serverItemsInProgress,
                        mediaManager.serverLibraries,
                        mediaManager.allLibraryPersonalizationsDone
                )
        onBrowseTreeInitialized()
        val children =
                browseTree[parentMediaId]?.map { item ->
                  DLog.d(tag, "Found top menu item: ${item.description.title}")
                  MediaBrowserCompat.MediaItem(
                          item.description,
                          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                  )
                }

        AbsLogger.info(tag, "onLoadChildren: Android auto data loaded")
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
    } else if (parentMediaId == LIBRARIES_ROOT || parentMediaId == RECENTLY_ROOT)
    {
      DLog.d(tag, "First load done: $firstLoadDone")
      if (!firstLoadDone)
      {
        result.sendResult(null)
        return
      }

      if (!this::browseTree.isInitialized)
      {
        // ✅ good: detach and wait for init
        result.detach()
        waitForBrowseTree {
          val children = browseTree[parentMediaId]?.map { item ->
            DLog.d(tag, "[MENU: $parentMediaId] Showing list item ${item.description.title}")
            MediaBrowserCompat.MediaItem(
              item.description,
              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
          }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
        return
      }

      // Already initialized: just return
      val children = browseTree[parentMediaId]?.map { item ->
        DLog.d(tag, "[MENU: $parentMediaId] Showing list item ${item.description.title}")
        MediaBrowserCompat.MediaItem(
          item.description,
          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
      }
      result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
    } else if (mediaManager.getIsLibrary(parentMediaId)) { // Load library items for library
      DLog.d(tag, "Loading items for library $parentMediaId")
      val selectedLibrary = mediaManager.getLibrary(parentMediaId)
      if (selectedLibrary?.mediaType == "podcast") { // Podcasts are browseable
        mediaManager.loadLibraryPodcasts(parentMediaId) { libraryItems ->
          val children =
                  libraryItems?.map { libraryItem ->
                    val mediaDescription = libraryItem.getMediaDescription(null, ctx)
                    MediaBrowserCompat.MediaItem(
                            mediaDescription,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else {
        val children =
                mutableListOf(
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Authors")
                                        .setMediaId("__LIBRARY__${parentMediaId}__AUTHORS")
                                        .setIconUri(getUriToAbsIconDrawable(ctx, "authors"))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Series")
                                        .setMediaId("__LIBRARY__${parentMediaId}__SERIES_LIST")
                                        .setIconUri(getUriToAbsIconDrawable(ctx, "columns"))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Collections")
                                        .setMediaId("__LIBRARY__${parentMediaId}__COLLECTIONS")
                                        .setIconUri(
                                                getUriToDrawable(
                                                        ctx,
                                                        R.drawable.md_book_multiple_outline
                                                )
                                        )
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                )
        if (mediaManager.getHasDiscovery(parentMediaId)) {
          children.add(
                  MediaBrowserCompat.MediaItem(
                          MediaDescriptionCompat.Builder()
                                  .setTitle("Discovery")
                                  .setMediaId("__LIBRARY__${parentMediaId}__DISCOVERY")
                                  .setIconUri(getUriToDrawable(ctx, R.drawable.md_telescope))
                                  .build(),
                          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                  )
          )
        }
        result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
      }
    } else if (parentMediaId.startsWith(RECENTLY_ROOT)) {
      DLog.d(tag, "Browsing recently $parentMediaId")
      val mediaIdParts = parentMediaId.split("__")
      if (!mediaManager.getIsLibrary(mediaIdParts[2])) {
        DLog.d(tag, "${mediaIdParts[2]} is not library")
        result.sendResult(null)
        return
      }
      DLog.d(tag, "Mediaparts: ${mediaIdParts.size} | $mediaIdParts")
      if (mediaIdParts.size == 3) {
        mediaManager.getLibraryRecentShelfs(mediaIdParts[2]) { availableShelfs ->
          DLog.d(tag, "Found ${availableShelfs.size} shelfs")
          val children: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
          for (shelf in availableShelfs) {
            if (shelf.type == "book") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Books")
                                      .setMediaId("${parentMediaId}__BOOK")
                                      .setIconUri(
                                              getUriToDrawable(
                                                      ctx,
                                                      R.drawable.md_book_open_blank_variant_outline
                                              )
                                      )
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "series") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Series")
                                      .setMediaId("${parentMediaId}__SERIES")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "columns"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "episode") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Episodes")
                                      .setMediaId("${parentMediaId}__EPISODE")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "microphone_2"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "podcast") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Podcast")
                                      .setMediaId("${parentMediaId}__PODCAST")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "podcast"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "authors") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Authors")
                                      .setMediaId("${parentMediaId}__AUTHORS")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "authors"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            }
          }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts.size == 4) {
        mediaManager.getLibraryRecentShelfByType(mediaIdParts[2], mediaIdParts[3]) { shelf ->
          if (shelf === null) {
            result.sendResult(mutableListOf())
          } else {
            if (shelf.type == "book") {
              val children =
                      (shelf as LibraryShelfBookEntity).entities?.map { libraryItem ->
                        val progress =
                                mediaManager.serverUserMediaProgress.find {
                                  it.libraryItemId == libraryItem.id
                                }
                        val localLibraryItem =
                                DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(libraryItem.id, libraryItem.ino)
                        libraryItem.localLibraryItemId = localLibraryItem?.id
                        val description =
                                libraryItem.getMediaDescription(progress, ctx, null, false)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "episode") {
              val episodesWithRecentEpisode =
                      (shelf as LibraryShelfEpisodeEntity).entities?.filter { libraryItem ->
                        libraryItem.recentEpisode !== null
                      }
              val children =
                      episodesWithRecentEpisode?.map { libraryItem ->
                        val podcast = libraryItem.media as Podcast
                        val progress =
                                mediaManager.serverUserMediaProgress.find {
                                  it.libraryItemId == libraryItem.libraryId &&
                                          it.episodeId == libraryItem.recentEpisode?.id
                                }

                        // to show download icon
                        val localLibraryItem =
                                DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(
                                        libraryItem.recentEpisode!!.id,
                                        libraryItem.ino
                                )
                        localLibraryItem?.let { lli ->
                          val localEpisode =
                                  (lli.media as Podcast).episodes?.find {
                                    it.serverEpisodeId == libraryItem.recentEpisode.id
                                  }
                          libraryItem.recentEpisode.localEpisodeId = localEpisode?.id
                        }

                        val description =
                                libraryItem.recentEpisode.getMediaDescription(
                                        libraryItem,
                                        progress,
                                        ctx
                                )
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "podcast") {
              val children =
                      (shelf as LibraryShelfPodcastEntity).entities?.map { libraryItem ->
                        val mediaDescription = libraryItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                mediaDescription,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "series") {
              val children =
                      (shelf as LibraryShelfSeriesEntity).entities?.map { librarySeriesItem ->
                        val description = librarySeriesItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else if (shelf.type == "authors") {
              val children =
                      (shelf as LibraryShelfAuthorEntity).entities?.map { authorItem ->
                        val description = authorItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
            } else {
              result.sendResult(mutableListOf())
            }
          }
        }
      }
    } else if (parentMediaId.startsWith("__LIBRARY__")) {
      DLog.d(tag, "Browsing library $parentMediaId")
      val mediaIdParts = parentMediaId.split("__")
      /*
       MediaIdParts for Library
       1: LIBRARY
       2: mediaId for library
       3: Browsing style (AUTHORS, AUTHOR, AUTHOR_SERIES, SERIES_LIST, SERIES, COLLECTION, COLLECTIONS, DISCOVERY)
       4:
         - Paging: SERIES_LIST, AUTHORS
         - SeriesId: SERIES
         - AuthorId: AUTHOR, AUTHOR_SERIES
         - CollectionId: COLLECTIONS
       5: SeriesId: AUTHOR_SERIES
      */
      if (!mediaManager.getIsLibrary(mediaIdParts[2])) {
        DLog.d(tag, "${mediaIdParts[2]} is not library")
        result.sendResult(null)
        return
      }
      DLog.d(tag, "$mediaIdParts")
      if (mediaIdParts[3] == "SERIES_LIST" && mediaIdParts.size == 5) {
        DLog.d(tag, "Loading series from library ${mediaIdParts[2]} with paging ${mediaIdParts[4]}")
        mediaManager.loadLibrarySeriesWithAudio(mediaIdParts[2], mediaIdParts[4]) { seriesItems ->
          DLog.d(tag, "Received ${seriesItems.size} series")

          val seriesLetters =
                  seriesItems
                          .groupingBy { iwb ->
                            iwb.title.substring(0, mediaIdParts[4].length + 1).uppercase()
                          }
                          .eachCount()
          if (seriesItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping &&
                          seriesItems.size > 1 &&
                          seriesLetters.size > 1
          ) {
            val children =
                    seriesLetters.map { (seriesLetter, seriesCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(seriesLetter)
                                      .setMediaId("${parentMediaId}${seriesLetter.last()}")
                                      .setSubtitle("$seriesCount series")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    seriesItems.map { seriesItem ->
                      val description = seriesItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "SERIES_LIST") {
        DLog.d(tag, "Loading series from library ${mediaIdParts[2]}")
        mediaManager.loadLibrarySeriesWithAudio(mediaIdParts[2]) { seriesItems ->
          DLog.d(tag, "Received ${seriesItems.size} series")
          if (seriesItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping && seriesItems.size > 1
          ) {
            val seriesLetters =
                    seriesItems.groupingBy { iwb -> iwb.title.first().uppercaseChar() }.eachCount()
            val children =
                    seriesLetters.map { (seriesLetter, seriesCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(seriesLetter.toString())
                                      .setSubtitle("$seriesCount series")
                                      .setMediaId("${parentMediaId}__${seriesLetter}")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    seriesItems.map { seriesItem ->
                      val description = seriesItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "SERIES") {
        DLog.d(tag, "Loading items for serie ${mediaIdParts[4]} from library ${mediaIdParts[2]}")
        mediaManager.loadLibrarySeriesItemsWithAudio(mediaIdParts[2], mediaIdParts[4]) {
                libraryItems ->
          DLog.d(tag, "Received ${libraryItems.size} library items")
          var items = libraryItems
          if (DeviceManager.deviceData.deviceSettings!!.androidAutoBrowseSeriesSequenceOrder ===
                          AndroidAutoBrowseSeriesSequenceOrderSetting.DESC
          ) {
            items = libraryItems.reversed()
          }
          val children =
                  items.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(libraryItem.id, libraryItem.ino)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "AUTHORS" && mediaIdParts.size == 5) {
        DLog.d(tag, "Loading authors from library ${mediaIdParts[2]} with paging ${mediaIdParts[4]}")
        mediaManager.loadAuthorsWithBooks(mediaIdParts[2], mediaIdParts[4]) { authorItems ->
          DLog.d(tag, "Received ${authorItems.size} authors")

          val authorLetters =
                  authorItems
                          .groupingBy { iwb ->
                            iwb.name.substring(0, mediaIdParts[4].length + 1).uppercase()
                          }
                          .eachCount()
          if (authorItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping &&
                          authorItems.size > 1 &&
                          authorLetters.size > 1
          ) {
            val children =
                    authorLetters.map { (authorLetter, authorCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(authorLetter)
                                      .setMediaId("${parentMediaId}${authorLetter.last()}")
                                      .setSubtitle("$authorCount authors")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    authorItems.map { authorItem ->
                      val description = authorItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "AUTHORS") {
        DLog.d(tag, "Loading authors from library ${mediaIdParts[2]}")
        mediaManager.loadAuthorsWithBooks(mediaIdParts[2]) { authorItems ->
          DLog.d(tag, "Received ${authorItems.size} authors")
          if (authorItems.size >
                          DeviceManager.deviceData.deviceSettings!!
                                  .androidAutoBrowseLimitForGrouping && authorItems.size > 1
          ) {
            val authorLetters =
                    authorItems.groupingBy { iwb -> iwb.name.first().uppercaseChar() }.eachCount()
            val children =
                    authorLetters.map { (authorLetter, authorCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(authorLetter.toString())
                                      .setSubtitle("$authorCount authors")
                                      .setMediaId("${parentMediaId}__${authorLetter}")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          } else {
            val children =
                    authorItems.map { authorItem ->
                      val description = authorItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
          }
        }
      } else if (mediaIdParts[3] == "AUTHOR") {
        mediaManager.loadAuthorBooksWithAudio(mediaIdParts[2], mediaIdParts[4]) { libraryItems ->
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(libraryItem.id, libraryItem.ino)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    if (libraryItem.collapsedSeries != null) {
                      val description =
                              libraryItem.getMediaDescription(progress, ctx, mediaIdParts[4])
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    } else {
                      val description = libraryItem.getMediaDescription(progress, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                      )
                    }
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "AUTHOR_SERIES") {
        mediaManager.loadAuthorSeriesBooksWithAudio(
                mediaIdParts[2],
                mediaIdParts[4],
                mediaIdParts[5]
        ) { libraryItems ->
          var items = libraryItems
          if (DeviceManager.deviceData.deviceSettings!!.androidAutoBrowseSeriesSequenceOrder ===
                          AndroidAutoBrowseSeriesSequenceOrderSetting.DESC
          ) {
            items = libraryItems.reversed()
          }
          val children =
                  items.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(libraryItem.id, libraryItem.ino)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    if (libraryItem.collapsedSeries != null) {
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    } else {
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                      )
                    }
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "COLLECTIONS") {
        DLog.d(tag, "Loading collections from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryCollectionsWithAudio(mediaIdParts[2]) { collectionItems ->
          DLog.d(tag, "Received ${collectionItems.size} collections")
          val children =
                  collectionItems.map { collectionItem ->
                    val description = collectionItem.getMediaDescription(null, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "COLLECTION") {
        DLog.d(tag, "Loading collection ${mediaIdParts[4]} books from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryCollectionBooksWithAudio(mediaIdParts[2], mediaIdParts[4]) {
                libraryItems ->
          DLog.d(tag, "Received ${libraryItems.size} collections")
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(libraryItem.id, libraryItem.ino)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else if (mediaIdParts[3] == "DISCOVERY") {
        DLog.d(tag, "Loading discovery from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryDiscoveryBooksWithAudio(mediaIdParts[2]) { libraryItems ->
          DLog.d(tag, "Received ${libraryItems.size} libraryItems for discovery")
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            DeviceManager.dbManager.getLocalLibraryItemByLIdOrIno(libraryItem.id, libraryItem.ino)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          result.sendResult(children as MutableList<MediaBrowserCompat.MediaItem>?)
        }
      } else {
        result.sendResult(null)
      }
    } else {
      DLog.d(tag, "Loading podcast episodes for podcast $parentMediaId")
      mediaManager.loadPodcastEpisodeMediaBrowserItems(parentMediaId, ctx) { result.sendResult(it) }
    }
  }

  override fun onSearch(
          query: String,
          extras: Bundle?,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    result.detach()
    if (cachedSearch != query) {
      DLog.d(tag, "Search bundle: $extras")
      var foundBooks: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundPodcasts: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundSeries: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      var foundAuthors: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

      mediaManager.serverLibraries.forEach { serverLibrary ->
        runBlocking {
          // Skip searching library if it doesn't have any audio files
          if (serverLibrary.stats?.numAudioFiles == 0) return@runBlocking
          val searchResult = mediaManager.doSearch(serverLibrary.id, query)
          for (resultData in searchResult.entries.iterator()) {
            when (resultData.key) {
              "book" -> foundBooks.addAll(resultData.value)
              "series" -> foundSeries.addAll(resultData.value)
              "authors" -> foundAuthors.addAll(resultData.value)
              "podcast" -> foundPodcasts.addAll(resultData.value)
            }
          }
        }
      }
      foundBooks.addAll(foundSeries)
      foundBooks.addAll(foundAuthors)
      cachedSearchResults = foundBooks
    }
    result.sendResult(cachedSearchResults)
    cachedSearch = query
    DLog.d(tag, "onSearch: Done")
  }

  //
  // SHAKE SENSOR
  //
  private fun initSensor() {
    // ShakeDetector initialization
    mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    mAccelerometer = mSensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    mShakeDetector = ShakeDetector()
    mShakeDetector!!.setOnShakeListener(
            object : ShakeDetector.OnShakeListener {
              override fun onShake(count: Int) {
                DLog.d(tag, "PHONE SHAKE! $count")
                sleepTimerManager.handleShake()
              }
            }
    )
  }

  // Shake sensor used for sleep timer
  fun registerSensor() {
    if (isShakeSensorRegistered) {
      DLog.i(tag, "Shake sensor already registered")
      return
    }
    shakeSensorUnregisterTask?.cancel()

    DLog.d(tag, "Registering shake SENSOR ${mAccelerometer?.isWakeUpSensor}")
    val success =
            mSensorManager!!.registerListener(
                    mShakeDetector,
                    mAccelerometer,
                    SensorManager.SENSOR_DELAY_UI
            )
    if (success) isShakeSensorRegistered = true
  }

  fun unregisterSensor() {
    if (!isShakeSensorRegistered) return

    // Unregister shake sensor after wake up expiration
    shakeSensorUnregisterTask?.cancel()
    shakeSensorUnregisterTask =
            Timer("ShakeUnregisterTimer", false).schedule(SLEEP_TIMER_WAKE_UP_EXPIRATION) {
              Handler(Looper.getMainLooper()).post {
                DLog.d(tag, "wake time expired: Unregistering shake sensor")
                mSensorManager!!.unregisterListener(mShakeDetector)
                isShakeSensorRegistered = false
              }
            }
  }

  private val networkCallback =
          object : ConnectivityManager.NetworkCallback() {
            // Network capabilities have changed for the network
            override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
            ) {
              super.onCapabilitiesChanged(network, networkCapabilities)

              isUnmeteredNetwork =
                      networkCapabilities.hasCapability(
                              NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                      )
              hasNetworkConnectivity =
                      networkCapabilities.hasCapability(
                              NetworkCapabilities.NET_CAPABILITY_VALIDATED
                      ) &&
                              networkCapabilities.hasCapability(
                                      NetworkCapabilities.NET_CAPABILITY_INTERNET
                              )
              DLog.i(
                      tag,
                      "Network capabilities changed. hasNetworkConnectivity=$hasNetworkConnectivity | isUnmeteredNetwork=$isUnmeteredNetwork"
              )
              clientEventEmitter?.onNetworkMeteredChanged(isUnmeteredNetwork)
              // A wait held back by the cellular setting may go on now
              if (isUnmeteredNetwork) mainHandler.post {
                perform(awaiting.onServerState(ServerReachability.state.value, ServerReachability.serverConfigId.value, streamPermission()))
              }
              if (hasNetworkConnectivity) {
                // Force android auto loading if libraries are empty.
                // Lack of network connectivity is most likely reason for libraries being empty
                if (isBrowseTreeInitialized() &&
                                firstLoadDone &&
                                mediaManager.serverLibraries.isEmpty()
                ) {
                  forceReloadingAndroidAuto = true
                  notifyChildrenChanged("/")
                }
              }
            }
          }

  inner class JumpBackwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_JUMP_BACKWARD,
                      getContext().getString(R.string.action_jump_backward),
                      R.drawable.exo_icon_rewind
              )
              .build()
    }
  }

  inner class JumpForwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_JUMP_FORWARD,
                      getContext().getString(R.string.action_jump_forward),
                      R.drawable.exo_icon_fastforward
              )
              .build()
    }
  }

  inner class SkipForwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_SKIP_FORWARD,
                      getContext().getString(R.string.action_skip_forward),
                      R.drawable.skip_next_24
              )
              .build()
    }
  }

  inner class SkipBackwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_SKIP_BACKWARD,
                      getContext().getString(R.string.action_skip_backward),
                      R.drawable.skip_previous_24
              )
              .build()
    }
  }

  inner class ChangePlaybackSpeedCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      val playbackRate = mediaManager.getSavedPlaybackRate()

      // Rounding values in the event a non preset value (.5, 1, 1.2, 1.5, 2, 3) is selected in the
      // phone app
      val drawable: Int =
              when (playbackRate) {
                in 0.5f..0.7f -> R.drawable.ic_play_speed_0_5x
                in 0.8f..1.0f -> R.drawable.ic_play_speed_1_0x
                in 1.1f..1.3f -> R.drawable.ic_play_speed_1_2x
                in 1.4f..1.7f -> R.drawable.ic_play_speed_1_5x
                in 1.8f..2.4f -> R.drawable.ic_play_speed_2_0x
                in 2.5f..3.0f -> R.drawable.ic_play_speed_3_0x
                // anything set above 3 will be show the 3x to save from creating 100 icons
                else -> R.drawable.ic_play_speed_3_0x
              }
      val customActionExtras = Bundle()
      customActionExtras.putFloat("speed", playbackRate)
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_CHANGE_SPEED,
                      getContext().getString(R.string.action_change_speed),
                      drawable
              )
              .setExtras(customActionExtras)
              .build()
    }
  }
}
