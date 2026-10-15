package app.absplus.android.sync

import android.content.Context
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.userdata.UserDataDatabase
import app.absplus.android.device.DeviceManager
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
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
        val notifyWindowMs: Long = 500
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
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            log = { AbsLogger.info("SyncEngine", it) }))
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
    val store = UserDataStore(deps.db, notifier, deps.clock, inFlightId = { pusher.inFlightId })
    private val reconciler = UserDataReconciler(deps.http, store)

    private val lock = Any()
    private val runMutex = Mutex()
    private var runner: Job? = null
    private var wantPush = false
    private var wantReconcile = false
    private var reconcileDeferred = false
    private var socketLostInBackground = false
    private var lastReconcileAt: Long? = null
    private var lastReconcileStartAt: Long? = null
    private var backoffJob: Job? = null
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
            val recent = lastReconcileStartAt?.let { deps.clock() - it < RECONCILE_MIN_INTERVAL_MS } ?: false
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

    suspend fun applyEvent(name: String, payloadJson: String, serverConfigId: String?): Boolean {
      val active = deps.activeServer()?.id ?: return false
      if (serverConfigId != active) return false
      return when (name) {
        "user_updated" -> {
          reconciler.applyUserObject(active, payloadJson)
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
      while (true) {
        val reconcile = synchronized(lock) {
          if (!wantPush) {
            if (runner === self) runner = null
            return
          }
          wantPush = false
          wantReconcile.also { wantReconcile = false }
        }
        runMutex.withLock { runOnce(reconcile) }
      }
    }

    private suspend fun runOnce(reconcile: Boolean) {
      val config = deps.activeServer() ?: return
      if (deps.serverState.value != ServerState.ONLINE) {
        if (reconcile) synchronized(lock) { reconcileDeferred = true }
        publishPending(config.id)
        return
      }
      _syncState.update { it.copy(running = true) }
      var error: String? = null
      try {
        if (reconcile) {
          synchronized(lock) {
            reconcileDeferred = false
            lastReconcileStartAt = deps.clock()
          }
          deps.log("Reconcile ${config.id}")
          reconciler.reconcileBookmarks(config).onSuccess { lastReconcileAt = deps.clock() }.onFailure { failure ->
            error = failure.message
            synchronized(lock) { reconcileDeferred = true }
          }
        }
        val result = pusher.pushAll(config)
        if (result.stoppedBy != null) error = error ?: result.stoppedBy.name.lowercase()
        if (result.stoppedBy == StopReason.BACKOFF) scheduleBackoff(config.id)
        _syncState.update {
          SyncState(running = false, pendingCount = result.remaining, lastError = error,
                  lastSyncAt = if (error == null) deps.clock() else it.lastSyncAt)
        }
      } finally {
        _syncState.update { it.copy(running = false) }
      }
    }

    private suspend fun scheduleBackoff(serverConfigId: String) {
      val head = outbox.head(serverConfigId) ?: return
      val wait = (head.nextAttemptAt - deps.clock()).coerceAtLeast(0)
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
        lastReconcileAt = null
        lastReconcileStartAt = null
      }
      backoffJob?.cancel()
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
