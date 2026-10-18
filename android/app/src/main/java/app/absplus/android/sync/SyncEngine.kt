package app.absplus.android.sync

import android.content.Context
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.userdata.UserDataDatabase
import app.absplus.android.device.DeviceManager
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class SyncReason { APP_START, ONLINE, SOCKET_INIT, FOREGROUND, MANUAL, LOCAL_WRITE }

data class SyncState(val running: Boolean, val pendingCount: Int, val lastError: String?, val lastSyncAt: Long?)

data class DataChange(val scope: String, val ids: List<String>)

/** A foreground return reconciles after a socket drop in the background only when the last reconcile is older. */
const val FOREGROUND_RECONCILE_AFTER_MS = 15 * 60_000L

/**
 * Automatic reconcile requests within this time of the last reconcile start are covered by it: a reconnect brings
 * ONLINE and the socket's init together. A manual sync always reconciles.
 */
const val RECONCILE_MIN_INTERVAL_MS = 30_000L

/**
 * Retry of the outbox after a push found no usable answer while the server still counts as online (a failed token
 * refresh): nothing else would trigger it. Restarts after a run that is not stopped this way.
 */
val UNREACHABLE_RETRY_MS = listOf(30_000L, 60_000L, 300_000L)

class SyncDeps(
        val db: UserDataDatabase,
        val http: SyncHttp,
        val activeServer: () -> ServerConnectionConfig?,
        val serverState: StateFlow<ServerState>,
        /** Changes when the active server is switched or cleared. */
        val serverId: StateFlow<String?>,
        val isForeground: () -> Boolean,
        val clock: () -> Long,
        val scope: CoroutineScope,
        val log: (String) -> Unit,
        val notifyWindowMs: Long = 500,
        val logError: (String) -> Unit = log,
        /** The user a saved server is signed in as (see [UserDataStore]). */
        val ownerOf: (serverConfigId: String) -> String? = { null },
        val recentlySettledMs: Long = UserDataStore.RECENTLY_SETTLED_MS,
        val unreachableRetryMs: List<Long> = UNREACHABLE_RETRY_MS,
        /** A live session (not a cold start before JS restores it, not after a logout); gates redoing a stale answer. */
        val signedIn: () -> Boolean = { true }
)

/**
 * Process-wide sync of the user's data with the active server: pull (reconcile) and push of the outbox.
 *
 * The outbox is pushed whenever the server is online. A reconcile runs only with the app in the foreground: in the
 * background (Android Auto, the player service) it is deferred until the next foreground return. Runs never overlap;
 * a request during a run schedules one more run after it.
 */
object SyncEngine {
  private val _syncState = MutableStateFlow(SyncState(running = false, pendingCount = 0, lastError = null, lastSyncAt = null))
  val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
  private val _dataChanges = MutableSharedFlow<DataChange>(extraBufferCapacity = 64)
  val dataChanges: SharedFlow<DataChange> = _dataChanges.asSharedFlow()

  @Volatile private var core: Core? = null

  /** The store for user actions; null before [init]. */
  val store: UserDataStore? get() = core?.store

  /** [apiHandler] is created on the first token refresh: its secure storage is not needed before that. */
  @Synchronized
  fun init(context: Context, apiHandler: () -> ApiHandler) {
    if (core != null) return
    val handler by lazy(apiHandler)
    val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    val http = SyncHttp(
            client,
            refresh = { id, onResult -> handler.refreshAuthTokens(id, null, onResult) },
            tokenFor = { id -> DeviceManager.getServerConnectionConfig(id)?.token })
    start(SyncDeps(
            db = UserDataDatabase.get(context.applicationContext),
            http = http,
            activeServer = ActiveServer::current,
            serverState = ServerReachability.state,
            serverId = ServerReachability.serverConfigId,
            isForeground = { SyncRuntime.isForeground },
            clock = System::currentTimeMillis,
            // A failure in any sync job is logged, never a crash of the process (the player may be running)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler { _, e -> AbsLogger.error("SyncEngine", "Sync job failed: $e") }),
            log = { AbsLogger.info("SyncEngine", it) },
            logError = { AbsLogger.error("SyncEngine", it) },
            ownerOf = { id -> DeviceManager.getServerConnectionConfig(id)?.userId },
            signedIn = { DeviceManager.serverConnectionConfig != null }))
  }

  private fun start(deps: SyncDeps) {
    val started = Core(deps)
    core = started
    started.start()
    started.syncNow(SyncReason.APP_START)
  }

  fun syncNow(reason: SyncReason) {
    core?.syncNow(reason)
  }

  /** After a user action was written: refreshes the pending count and pushes when online. */
  fun onLocalWrite() {
    core?.onLocalWrite()
  }

  /** The WebView's socket dropped; in the background this makes the next foreground return consider a reconcile. */
  fun onSocketLost() {
    core?.onSocketLost()
  }

  /**
   * A server event the WebView received while [serverConfigId] was active; dropped when another server is active now.
   * Returns whether it was applied.
   */
  suspend fun applyEvent(name: String, payloadJson: String, serverConfigId: String?): Boolean =
          core?.applyEvent(name, payloadJson, serverConfigId) ?: false

  @Synchronized
  internal fun initForTest(deps: SyncDeps) {
    resetForTest()
    start(deps)
  }

  internal suspend fun awaitIdleForTest() {
    core?.awaitIdle()
  }

  @Synchronized
  internal fun resetForTest() {
    core?.stop()
    core = null
    _syncState.value = SyncState(running = false, pendingCount = 0, lastError = null, lastSyncAt = null)
  }

  private class Core(private val deps: SyncDeps) {
    private val scope = deps.scope
    private val outbox = deps.db.outbox()
    private val notifier = DataChangeNotifier(deps.notifyWindowMs, scope) { name, ids -> _dataChanges.tryEmit(DataChange(name, ids)) }
    private lateinit var pusher: OutboxPusher
    val store = UserDataStore(deps.db, notifier, deps.clock, inFlightId = { pusher.inFlightId }, ownerOf = deps.ownerOf,
            recentlySettledMs = deps.recentlySettledMs)
    private val reconciler = UserDataReconciler(deps.http, store)

    private val lock = Any()
    private val runMutex = Mutex()
    private var runner: Job? = null
    private var wantPush = false
    private var wantReconcile = false
    private var reconcileDeferred = false
    private var socketLostInBackground = false
    private var lastReconcileAt: Long? = null
    /** Start of the last reconcile that succeeded; a failed one does not cover later requests. */
    private var lastReconcileOkStartAt: Long? = null
    private var reconcileRunning = false
    /** The next reconcile redoes one whose answer went stale; a stale redo is not redone again. */
    private var staleReconcileRedo = false
    private var unreachableRetries = 0
    private var backoffJob: Job? = null
    private var snapshotFollowUp: Job? = null
    private val jobs = mutableListOf<Job>()

    init {
      pusher = OutboxPusher(deps.db, deps.http, store, deps.clock, deps.log)
    }

    fun start() {
      jobs += scope.launch {
        deps.serverState.collect { state -> if (state == ServerState.ONLINE) syncNow(SyncReason.ONLINE) }
      }
      jobs += scope.launch {
        var previous = deps.serverId.value
        deps.serverId.collect { id ->
          if (id == previous) return@collect
          previous = id
          onServerSwitched()
        }
      }
    }

    fun stop() {
      synchronized(lock) { runner?.cancel() }
      backoffJob?.cancel()
      snapshotFollowUp?.cancel()
      jobs.forEach { it.cancel() }
    }

    fun syncNow(reason: SyncReason) {
      val job = synchronized(lock) {
        when {
          reason == SyncReason.LOCAL_WRITE -> Unit
          !deps.isForeground() -> reconcileDeferred = true
          reason == SyncReason.FOREGROUND -> {
            val stale = lastReconcileAt?.let { deps.clock() - it > FOREGROUND_RECONCILE_AFTER_MS } ?: true
            if (reconcileDeferred || (socketLostInBackground && stale)) wantReconcile = true
            socketLostInBackground = false
          }
          reason == SyncReason.MANUAL -> wantReconcile = true
          else -> {
            val recent = reconcileRunning || lastReconcileOkStartAt?.let { deps.clock() - it < RECONCILE_MIN_INTERVAL_MS } ?: false
            if (!recent) wantReconcile = true
          }
        }
        wantPush = true
        if (runner != null) return
        // Lazy, so the job is known as the runner before its first line runs on any dispatcher
        scope.launch(start = CoroutineStart.LAZY) { drain() }.also { runner = it }
      }
      job.start()
    }

    fun onLocalWrite() {
      refreshPendingCount()
      if (deps.serverState.value == ServerState.ONLINE) syncNow(SyncReason.LOCAL_WRITE)
    }

    fun onSocketLost() {
      if (!deps.isForeground()) synchronized(lock) { socketLostInBackground = true }
    }

    /** Serialized with runs, so an event never merges between a push and the settling of its rows. */
    suspend fun applyEvent(name: String, payloadJson: String, serverConfigId: String?): Boolean = runMutex.withLock {
      val active = deps.activeServer()?.id ?: return@withLock false
      if (serverConfigId != active) return@withLock false
      when (name) {
        "user_updated" -> {
          if (reconciler.applyUserObject(active, payloadJson)) reconcileAfterSettleWindow()
          true
        }
        else -> false
      }
    }

    suspend fun awaitIdle() {
      while (true) {
        val job = synchronized(lock) { runner } ?: return
        job.join()
      }
    }

    private suspend fun drain() {
      val self = coroutineContext[Job]
      var finished = false
      try {
        while (true) {
          val reconcile = synchronized(lock) {
            if (!wantPush) {
              // In the same block as the check: a request after it sees no runner and starts one
              if (runner === self) runner = null
              finished = true
              return
            }
            wantPush = false
            wantReconcile.also { wantReconcile = false }
          }
          try {
            runMutex.withLock { runOnce(reconcile) }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // A failing database or a bug in one run must not end syncing for the rest of the process
            deps.logError("Sync run failed: $e")
            _syncState.update { it.copy(running = false, lastError = e.message ?: e.javaClass.simpleName) }
          }
        }
      } finally {
        // Cancelled or failed outside a run: let the next request start a new runner
        if (!finished) synchronized(lock) { if (runner === self) runner = null }
      }
    }

    private suspend fun runOnce(reconcile: Boolean) {
      val config = deps.activeServer() ?: return
      if (deps.serverState.value != ServerState.ONLINE) {
        // A redo that did not run is spent: the deferred reconcile is a new one, with a redo of its own
        if (reconcile) synchronized(lock) { reconcileDeferred = true; staleReconcileRedo = false }
        publishPending(config.id)
        return
      }
      _syncState.update { it.copy(running = true) }
      var error: String? = null
      try {
        if (reconcile) {
          val startedAt = deps.clock()
          val redo = synchronized(lock) {
            reconcileDeferred = false
            reconcileRunning = true
            staleReconcileRedo.also { staleReconcileRedo = false }
          }
          deps.log("Reconcile ${config.id}")
          val result = try {
            reconciler.reconcileBookmarks(config)
          } finally {
            synchronized(lock) { reconcileRunning = false }
          }
          result.onSuccess {
            lastReconcileAt = deps.clock()
            synchronized(lock) { lastReconcileOkStartAt = startedAt }
          }.onFailure { failure ->
            error = failure.message
            deps.logError("Reconcile ${config.id} failed: ${failure.message}")
            synchronized(lock) {
              // A stale answer says nothing about the server: on a cold start it is the config JS sets meanwhile.
              // After a logout the session is gone, and the pull waits for the next foreground as before.
              if (failure is SyncFailure && failure.stale && !redo && deps.isForeground() && deps.signedIn()) {
                staleReconcileRedo = true
                wantReconcile = true
                wantPush = true
              } else {
                reconcileDeferred = true
              }
            }
          }
        }
        val result = pusher.pushAll(config)
        if (result.stoppedBy != null) error = error ?: result.stoppedBy.name.lowercase()
        when {
          result.stoppedBy == StopReason.BACKOFF -> scheduleBackoff(config.id)
          result.stoppedBy == StopReason.UNREACHABLE && deps.serverState.value == ServerState.ONLINE -> scheduleUnreachableRetry()
          else -> unreachableRetries = 0
        }
        _syncState.update {
          SyncState(running = false, pendingCount = result.remaining, lastError = error,
                  lastSyncAt = if (error == null) deps.clock() else it.lastSyncAt)
        }
      } finally {
        _syncState.update { it.copy(running = false) }
      }
    }

    /**
     * A snapshot left recently settled bookmarks alone, so a change another device made to them is not applied yet: a
     * fresh pull after the window does it. Background and offline defer it like any reconcile.
     */
    private fun reconcileAfterSettleWindow() {
      snapshotFollowUp?.cancel()
      snapshotFollowUp = scope.launch {
        delay(deps.recentlySettledMs)
        syncNow(SyncReason.MANUAL)
      }
    }

    private fun scheduleUnreachableRetry() {
      val wait = deps.unreachableRetryMs[minOf(unreachableRetries, deps.unreachableRetryMs.lastIndex)]
      unreachableRetries++
      scheduleRetry(wait)
    }

    private suspend fun scheduleBackoff(serverConfigId: String) {
      val head = outbox.head(serverConfigId) ?: return
      scheduleRetry((head.nextAttemptAt - deps.clock()).coerceAtLeast(0))
    }

    private fun scheduleRetry(wait: Long) {
      backoffJob?.cancel()
      backoffJob = scope.launch {
        delay(wait)
        syncNow(SyncReason.LOCAL_WRITE)
      }
    }

    private fun onServerSwitched() {
      // The answer to a request for the previous server must not settle its rows; they stay for when it is back
      synchronized(lock) {
        runner?.cancel()
        runner = null
        wantPush = false
        wantReconcile = false
        staleReconcileRedo = false
        lastReconcileAt = null
        lastReconcileOkStartAt = null
        unreachableRetries = 0
      }
      backoffJob?.cancel()
      snapshotFollowUp?.cancel()
      refreshPendingCount()
    }

    private fun refreshPendingCount() {
      scope.launch { publishPending(deps.activeServer()?.id) }
    }

    private suspend fun publishPending(serverConfigId: String?) {
      val count = serverConfigId?.let { outbox.count(it) } ?: 0
      _syncState.update { it.copy(pendingCount = count) }
    }
  }
}
