package app.absplus.android.sync

import android.content.Context
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.db.userdata.UserDataDatabase
import app.absplus.android.device.DeviceManager
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class SyncReason { APP_START, ONLINE, SOCKET_INIT, FOREGROUND, MANUAL, LOCAL_WRITE }

data class SyncState(
        val running: Boolean,
        val pendingCount: Int,
        val lastError: String?,
        val lastSyncAt: Long?,
        /** A pass over the catalog into replica.db is running. */
        val catalogRunning: Boolean = false
)

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

/** Catalog events kept while a pass runs; more drop them all and schedule another pass instead. */
const val CATALOG_EVENT_BUFFER = 1_000

/** Where `AbsData.setCurrentLibrary` keeps the library a catalog pass starts with. */
object CatalogPrefs {
  const val PREFS = "absplus_sync"
  const val CURRENT_LIBRARY = "currentLibraryId"
}

class CatalogDeps(
        val db: ReplicaDatabase,
        val currentLibraryId: () -> String?,
        /**
         * Catalog requests, apart from the user's: a slow 500-item page or its timeout must not mark the server
         * unreachable. Null: [SyncDeps.http].
         */
        val http: SyncHttp? = null,
        /** A socket reconnect passes over the catalog at most this often (from the start of the last pass). */
        val minIntervalMs: Long = RECONCILE_MIN_INTERVAL_MS,
        /** Runs inside the write of a catalog item event, after its check; for tests of concurrent events. */
        internal val beforeEventWrite: suspend () -> Unit = {}
)

class SyncDeps(
        val db: UserDataDatabase,
        val http: SyncHttp,
        val activeServer: () -> ServerConnectionConfig?,
        val serverState: StateFlow<ServerState>,
        /** See [ServerReachability.onlineEntries]. */
        val onlineEntries: StateFlow<Long>,
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
        /** See [UserDataStore]: items whose listening sessions still wait for `local-all`. */
        val unsentSessionKeys: (serverConfigId: String) -> Set<String> = { emptySet() },
        val mirror: ProgressMirror = ProgressMirror.None,
        val paperImport: PaperProgressImport? = null,
        /** Every Paper `LocalMediaProgress`, read for [paperImport] on start. */
        val paperProgress: () -> List<LocalMediaProgress> = { emptyList() },
        val catalog: CatalogDeps? = null
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

  /** Reads of the catalog replica; null before [init]. */
  val catalog: CatalogReader? get() = core?.catalogReader

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
    val catalogHttp = SyncHttp(
            client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build(),
            refresh = { id, onResult -> handler.refreshAuthTokens(id, null, onResult) },
            tokenFor = { id -> DeviceManager.getServerConnectionConfig(id)?.token },
            report = NoReachabilityReports)
    val db = UserDataDatabase.get(context.applicationContext)
    val replica = ReplicaDatabase.get(context.applicationContext)
    val catalogPrefs = context.applicationContext.getSharedPreferences(CatalogPrefs.PREFS, Context.MODE_PRIVATE)
    start(SyncDeps(
            db = db,
            http = http,
            activeServer = ActiveServer::current,
            serverState = ServerReachability.state,
            onlineEntries = ServerReachability.onlineEntries,
            serverId = ServerReachability.serverConfigId,
            isForeground = { SyncRuntime.isForeground },
            clock = System::currentTimeMillis,
            // A failure in any sync job is logged, never a crash of the process (the player may be running)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler { _, e -> AbsLogger.error("SyncEngine", "Sync job failed: $e") }),
            log = { AbsLogger.info("SyncEngine", it) },
            logError = { AbsLogger.error("SyncEngine", it) },
            ownerOf = { id -> DeviceManager.getServerConnectionConfig(id)?.userId },
            unsentSessionKeys = PaperProgressMirror::unsentSessionKeys,
            mirror = PaperProgressMirror,
            paperImport = PaperProgressImport(db,
                    context.applicationContext.getSharedPreferences(PaperProgressImport.PREFS, Context.MODE_PRIVATE)),
            paperProgress = { DeviceManager.dbManager.getAllLocalMediaProgress() },
            catalog = CatalogDeps(replica, currentLibraryId = { catalogPrefs.getString(CatalogPrefs.CURRENT_LIBRARY, null) }, http = catalogHttp)))
  }

  private fun start(deps: SyncDeps) {
    val started = Core(deps)
    core = started
    started.start()
    started.startUp()
  }

  fun syncNow(reason: SyncReason) {
    core?.syncNow(reason)
  }

  /** After a user action was written: refreshes the pending count and pushes when online. */
  fun onLocalWrite() {
    core?.onLocalWrite()
  }

  /**
   * The WebView's socket dropped: its reconnect (`SOCKET_INIT`) passes over the catalog again, since events of the gap
   * are lost; in the background this also makes the next foreground return consider a reconcile.
   */
  fun onSocketLost() {
    core?.onSocketLost()
  }

  /**
   * Queues [block] to run with the store on the sync scope, off the caller's thread and after the writes queued before
   * it; false before [init]. For writes that must not hold up their caller, such as playback ticks.
   */
  fun launchWrite(block: suspend (UserDataStore) -> Unit): Boolean = core?.launchWrite(block) ?: false

  /**
   * Runs [block] in the same queue as [launchWrite] and returns its result: a user's change lands after the ticks
   * sampled before it, never under them. Throws when sync is not started.
   */
  suspend fun <T> write(block: suspend (UserDataStore) -> T): T =
          (core ?: throw IllegalStateException("Sync is not started")).write(block)

  /** Imports [paper] like on start (a downloaded copy relinked to another server item); false before [init]. */
  fun importPaperProgress(paper: List<LocalMediaProgress>): Boolean = core?.importPaperProgress(paper) ?: false

  /**
   * A server event the WebView received while [serverConfigId] was active; dropped when another server is active now.
   * Returns whether it was applied.
   */
  suspend fun applyEvent(name: String, payloadJson: String, serverConfigId: String?): Boolean =
          core?.applyEvent(name, payloadJson, serverConfigId) ?: false

  /**
   * The user opens [itemId] of [serverConfigId]: its expanded copy, see [ExpandedItemCache]; skipped unless that server is
   * the active one. Null before [init].
   */
  suspend fun openItem(serverConfigId: String, itemId: String, force: Boolean = false): OpenOutcome? = core?.openItem(serverConfigId, itemId, force)

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
            recentlySettledMs = deps.recentlySettledMs, unsentSessionKeys = deps.unsentSessionKeys, mirror = deps.mirror)
    private val reconciler = UserDataReconciler(deps.http, store, deps.db, deps.clock, deps.paperImport)
    val catalogReader: CatalogReader? = deps.catalog?.let { catalog ->
      CatalogReader(catalog.db, { deps.db.mediaProgress().getAll(it) }, { sc, ids -> deps.db.mediaProgress().getForItems(sc, ids) },
              { notifier.version("progress") + notifier.version("playback") }, deps.clock, store::userJson)
    }
    private val catalogReconciler = deps.catalog?.let { catalog ->
      CatalogReconciler(catalog.db, catalog.http ?: deps.http, deps.clock, { notifier.notify("catalog", it) }, { catalogReader?.onReplicaChanged() }, deps.log)
    }
    private val catalogApplier = deps.catalog?.let { catalog ->
      CatalogEventApplier(catalog.db, { notifier.notify("catalog", it) }, { catalogReader?.onReplicaChanged() }, catalog.beforeEventWrite)
    }
    private val expandedCache = deps.catalog?.let { catalog ->
      ExpandedItemCache(catalog.db, deps.http, { deps.serverState.value == ServerState.ONLINE }, { deps.activeServer()?.id },
              { sc, event, payload -> applyCatalogEvent(event, payload, sc) }, deps.log)
    }
    /** Catalog events apply one at a time in arrival order, never under [runMutex]. */
    private val catalogEventMutex = Mutex()
    private val bufferLock = Any()
    /** The pass whose events are being buffered, 0 for none: a cancelled pass must not touch the buffer of the next. */
    private var catalogPassingId = 0L
    private var lastPassId = 0L
    private val catalogBuffer = ArrayList<Triple<String, String, JsonNode>>()
    private var catalogBufferOverflow = false

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
    private var catalogJob: Job? = null
    private var lastCatalogStartAt: Long? = null
    private var delayedCatalog: Job? = null
    private var catalogAgain = false
    /** A reconnect during a pass: one more, but through the reconnect interval. */
    private var socketAgain = false
    /** The socket dropped and has not reconnected yet: its reconnect passes over the catalog again. */
    private var socketDown = false
    private var unreachableRetries = 0
    private var backoffJob: Job? = null
    private var snapshotFollowUp: Job? = null
    private val jobs = mutableListOf<Job>()
    /** Writes run one at a time in the order they were requested: a later tick must not be overwritten by an earlier one. */
    private val writes = Channel<suspend (UserDataStore) -> Unit>(Channel.UNLIMITED)
    /** Runs wait for the Paper import of the start: a reconcile before it could delete a row it is about to write. */
    private val startupImport = CompletableDeferred<Unit>()

    init {
      pusher = OutboxPusher(deps.db, deps.http, store, deps.clock, deps.log)
    }

    fun start() {
      jobs += scope.launch {
        for (write in writes) {
          try {
            write(store)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            deps.logError("Write failed: $e")
          }
        }
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
      synchronized(lock) {
        runner?.cancel()
        catalogJob?.cancel()
        delayedCatalog?.cancel()
      }
      backoffJob?.cancel()
      snapshotFollowUp?.cancel()
      jobs.forEach { it.cancel() }
    }

    fun syncNow(reason: SyncReason) {
      var passCatalog = false
      val job = synchronized(lock) {
        if (reason == SyncReason.SOCKET_INIT) {
          // Decided by the socket's state, not by when it dropped: before, during or after a pass alike
          if (socketDown && deps.isForeground()) {
            if (catalogJob != null) socketAgain = true else passCatalog = true
          }
          socketDown = false
        }
        if (reason == SyncReason.MANUAL && deps.isForeground() && catalogJob != null) catalogAgain = true
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
        if (runner != null) null
        // Lazy, so the job is known as the runner before its first line runs on any dispatcher
        else scope.launch(start = CoroutineStart.LAZY) { drain() }.also { runner = it }
      }
      job?.start()
      if (passCatalog) passCatalogAfterReconnect()
    }

    /** A reconnect storm passes at most once an interval: an early one waits for it instead of being dropped. */
    private fun passCatalogAfterReconnect() {
      val minInterval = deps.catalog?.minIntervalMs ?: return
      val wait = synchronized(lock) { lastCatalogStartAt?.let { it + minInterval - deps.clock() } ?: 0L }
      if (wait <= 0) return startCatalogPass()
      synchronized(lock) {
        if (delayedCatalog?.isActive == true) return
        delayedCatalog = scope.launch {
          delay(wait)
          val start = synchronized(lock) {
            when {
              !deps.isForeground() -> {
                reconcileDeferred = true
                false
              }
              catalogJob != null -> {
                socketAgain = true
                false
              }
              else -> true
            }
          }
          if (start) startCatalogPass()
        }
      }
    }

    /** Paper progress newer than userdata.db is imported before the first sync, off the caller's thread. */
    fun startUp() {
      val import = deps.paperImport ?: return firstSync()
      launchWrite {
        try {
          val count = import.import(deps.paperProgress())
          if (count > 0) deps.log("Imported $count Paper progress rows")
        } finally {
          firstSync()
        }
      }
    }

    private fun firstSync() {
      startupImport.complete(Unit)
      // The current state is the start's own sync below; every later entry into ONLINE syncs again. Not the state
      // itself: a quick OFFLINE and back is conflated away there, and its push would be missed.
      val seen = deps.onlineEntries.value
      jobs += scope.launch {
        deps.onlineEntries.filter { it != seen }.collect {
          if (deps.serverState.value == ServerState.ONLINE) syncNow(SyncReason.ONLINE)
        }
      }
      syncNow(SyncReason.APP_START)
    }

    fun importPaperProgress(paper: List<LocalMediaProgress>): Boolean {
      val import = deps.paperImport ?: return false
      return launchWrite { import.import(paper) }
    }

    fun launchWrite(block: suspend (UserDataStore) -> Unit): Boolean = writes.trySend(block).isSuccess

    suspend fun <T> write(block: suspend (UserDataStore) -> T): T {
      val result = CompletableDeferred<T>()
      val queued = launchWrite { store ->
        try {
          result.complete(block(store))
        } catch (e: Throwable) {
          result.completeExceptionally(e)
        }
      }
      if (!queued) throw IllegalStateException("Sync is stopped")
      return result.await()
    }

    fun onLocalWrite() {
      refreshPendingCount()
      if (deps.serverState.value == ServerState.ONLINE) syncNow(SyncReason.LOCAL_WRITE)
    }

    fun onSocketLost() {
      synchronized(lock) {
        socketDown = true
        if (!deps.isForeground()) socketLostInBackground = true
      }
    }

    suspend fun openItem(serverConfigId: String, itemId: String, force: Boolean): OpenOutcome? {
      val cache = expandedCache ?: return null
      // Read once: the caller's replica answer and the request must be about the same server
      val config = deps.activeServer()?.takeIf { it.id == serverConfigId } ?: return OpenOutcome(OpenResult.SKIPPED)
      return cache.open(config, itemId, force)
    }

    /** User events are serialized with runs, so an event never merges between a push and the settling of its rows. */
    suspend fun applyEvent(name: String, payloadJson: String, serverConfigId: String?): Boolean =
            if (name in CATALOG_EVENTS) applyCatalogEvent(name, payloadJson, serverConfigId) else applyUserEvent(name, payloadJson, serverConfigId)

    private suspend fun applyUserEvent(name: String, payloadJson: String, serverConfigId: String?): Boolean = runMutex.withLock {
      val active = deps.activeServer()?.id ?: return@withLock false
      if (serverConfigId != active) return@withLock false
      when (name) {
        "user_updated" -> {
          if (reconciler.applyUserObject(active, payloadJson)) reconcileAfterSettleWindow()
          true
        }
        "user_item_progress_updated" -> reconciler.applyProgressEvent(active, payloadJson)
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
        // Requests made meanwhile pile up into one run
        startupImport.await()
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
        if (reconcile) synchronized(lock) { reconcileDeferred = true }
        publishPending(config.id)
        return
      }
      _syncState.update { it.copy(running = true) }
      var error: String? = null
      try {
        if (reconcile) {
          val startedAt = deps.clock()
          synchronized(lock) {
            reconcileDeferred = false
            reconcileRunning = true
          }
          deps.log("Reconcile ${config.id}")
          val result = try {
            reconciler.reconcileUser(config)
          } finally {
            synchronized(lock) { reconcileRunning = false }
          }
          result.onSuccess {
            lastReconcileAt = deps.clock()
            synchronized(lock) { lastReconcileOkStartAt = startedAt }
          }.onFailure { failure ->
            error = failure.message
            deps.logError("Reconcile ${config.id} failed: ${failure.message}")
            synchronized(lock) { reconcileDeferred = true }
          }
        }
        val result = pusher.pushAll(config)
        if (reconcile) startCatalogPass()
        if (result.stoppedBy != null) error = error ?: result.stoppedBy.name.lowercase()
        when {
          result.stoppedBy == StopReason.BACKOFF -> scheduleBackoff(config.id)
          result.stoppedBy == StopReason.UNREACHABLE && deps.serverState.value == ServerState.ONLINE -> scheduleUnreachableRetry()
          else -> unreachableRetries = 0
        }
        // A copy: a catalog pass may be running and owns catalogRunning
        _syncState.update {
          it.copy(running = false, pendingCount = result.remaining, lastError = error,
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
        catalogJob?.cancel()
        catalogJob = null
        delayedCatalog?.cancel()
        delayedCatalog = null
        lastCatalogStartAt = null
        catalogAgain = false
        socketAgain = false
        socketDown = false
        wantPush = false
        wantReconcile = false
        lastReconcileAt = null
        lastReconcileOkStartAt = null
        unreachableRetries = 0
      }
      backoffJob?.cancel()
      snapshotFollowUp?.cancel()
      clearCatalogBuffer()
      _syncState.update { it.copy(catalogRunning = false) }
      refreshPendingCount()
    }

    /** [passId]: only while that pass is the one buffering; null clears whatever buffers. */
    private fun clearCatalogBuffer(passId: Long? = null) = synchronized(bufferLock) {
      if (passId != null && catalogPassingId != passId) return@synchronized
      catalogBuffer.clear()
      catalogBufferOverflow = false
      catalogPassingId = 0L
    }

    /** A pass over the catalog, off [runMutex]; one at a time, a request during it may ask for one more. */
    private fun startCatalogPass() {
      val reconciler = catalogReconciler ?: return
      val job = synchronized(lock) {
        if (catalogJob != null) return
        scope.launch(start = CoroutineStart.LAZY) { runCatalog(reconciler) }.also { catalogJob = it }
      }
      job.start()
    }

    private suspend fun runCatalog(reconciler: CatalogReconciler) {
      val self = coroutineContext[Job]
      try {
        while (true) {
          val config = deps.activeServer() ?: return
          _syncState.update { it.copy(catalogRunning = true) }
          synchronized(lock) { lastCatalogStartAt = deps.clock() }
          val passId = synchronized(bufferLock) {
            lastPassId++
            catalogPassingId = lastPassId
            lastPassId
          }
          val result = try {
            reconciler.reconcile(config, deps.catalog?.currentLibraryId?.invoke())
          } catch (e: CancellationException) {
            clearCatalogBuffer(passId)
            throw e
          } catch (e: Exception) {
            // A failed pass still ends its buffering, or every later event would wait for good
            deps.logError("Catalog ${config.id} failed: $e")
            null
          }
          when {
            result == null -> Unit
            result.stoppedBy != null -> deps.logError("Catalog ${config.id} stopped: ${result.stoppedBy}")
            else -> deps.log("Catalog ${config.id}: ${result.libraries} libraries, failed ${result.failedLibraries}")
          }
          applyBufferedEvents(passId)
          val (again, afterReconnect) = synchronized(lock) {
            val more = catalogAgain
            val socket = socketAgain
            catalogAgain = false
            socketAgain = false
            val foreground = deps.isForeground()
            when {
              more && foreground -> true to false
              else -> {
                if ((more || socket) && !foreground) reconcileDeferred = true
                // In the same block as the check: a request after it sees no pass and starts one
                if (catalogJob === self) catalogJob = null
                false to (socket && foreground)
              }
            }
          }
          if (afterReconnect) passCatalogAfterReconnect()
          if (!again) return
        }
      } finally {
        synchronized(lock) { if (catalogJob === self) catalogJob = null }
        if (synchronized(lock) { catalogJob == null }) _syncState.update { it.copy(catalogRunning = false) }
      }
    }

    /** Events that came during the pass, in order, after its pages: a page read before an event never overwrites it. */
    private suspend fun applyBufferedEvents(passId: Long) {
      var reconcile = false
      catalogEventMutex.withLock {
        val (events, overflow) = synchronized(bufferLock) {
          if (catalogPassingId != passId) return@synchronized emptyList<Triple<String, String, JsonNode>>() to false
          val list = catalogBuffer.toList()
          val overflow = catalogBufferOverflow
          catalogBuffer.clear()
          catalogBufferOverflow = false
          catalogPassingId = 0L
          list to overflow
        }
        if (overflow) synchronized(lock) { catalogAgain = true }
        val active = deps.activeServer()?.id
        for ((serverConfigId, name, payload) in events) {
          if (serverConfigId != active) continue
          try {
            if (catalogApplier?.apply(serverConfigId, name, payload) == CatalogEventResult.NEEDS_RECONCILE) reconcile = true
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            deps.logError("Catalog event $name failed: $e")
          }
        }
      }
      if (reconcile) syncNow(SyncReason.MANUAL)
    }

    private suspend fun applyCatalogEvent(name: String, payloadJson: String, serverConfigId: String?): Boolean {
      val applier = catalogApplier ?: return false
      val active = deps.activeServer()?.id ?: return false
      if (serverConfigId != active) return false
      val payload = try {
        CatalogJson.mapper.readTree(payloadJson) ?: return false
      } catch (e: Exception) {
        return false
      }
      val result = catalogEventMutex.withLock {
        val buffered = synchronized(bufferLock) {
          if (catalogPassingId == 0L) return@synchronized false
          if (catalogBuffer.size >= CATALOG_EVENT_BUFFER) {
            catalogBuffer.clear()
            catalogBufferOverflow = true
          } else {
            catalogBuffer += Triple(active, name, payload)
          }
          true
        }
        if (buffered) null else applier.apply(active, name, payload)
      }
      return when (result) {
        null, CatalogEventResult.APPLIED -> true
        CatalogEventResult.IGNORED -> false
        CatalogEventResult.NEEDS_RECONCILE -> {
          syncNow(SyncReason.MANUAL)
          true
        }
      }
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
