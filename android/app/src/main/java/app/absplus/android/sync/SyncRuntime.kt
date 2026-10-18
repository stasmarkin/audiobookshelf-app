package app.absplus.android.sync

import android.content.Context
import app.absplus.android.managers.DbManager
import app.absplus.android.media.CoverCache
import app.absplus.android.server.ApiHandler
import kotlinx.coroutines.flow.MutableSharedFlow

/** A download of [libraryItemId] (server ids; [episodeId] for a podcast episode) for [serverConfigId] is on the device. */
data class LocalCopyReady(val serverConfigId: String, val libraryItemId: String, val episodeId: String?)

/** How long after Android Auto's last request a reconcile may run without the app in the foreground (Р20). */
const val AUTO_RECONCILE_LEASE_MS = 10 * 60_000L

/**
 * Lazily starts the process-wide sync singletons. There is no Application class and the process can start without an
 * Activity (Android Auto, widget, media buttons), so every entry point calls [ensureInit].
 */
object SyncRuntime {
  private var initialized = false

  /** Emitted from the download thread when a copy is saved; the player switches a stream of it to the file. */
  val localCopyReady = MutableSharedFlow<LocalCopyReady>(extraBufferCapacity = 8)

  /** Whether the app UI is in the foreground (MainActivity resumed). */
  @Volatile var isForeground = false
    private set

  internal var clock: () -> Long = System::currentTimeMillis
  @Volatile private var autoLeaseUntil = 0L

  /**
   * Whether a reconcile may run: the app in the foreground, or Android Auto in use (a lease from its last request). In
   * the car Auto is the user's foreground, and the catalog would otherwise stay as the phone last saw it.
   */
  val mayReconcile: Boolean get() = isForeground || clock() < autoLeaseUntil

  /**
   * Android Auto asked for something: the lease runs [AUTO_RECONCILE_LEASE_MS] from [now]. A new lease without the app
   * in the foreground syncs as a foreground return does: a reconcile deferred in the background runs (a process Auto
   * started defers its start's reconcile, so the first connection always reconciles). The probe is not a foreground's.
   */
  fun onAutoActivity(now: Long = clock()) {
    val opened = synchronized(this) {
      val held = now < autoLeaseUntil
      autoLeaseUntil = now + AUTO_RECONCILE_LEASE_MS
      !held
    }
    if (opened && !isForeground) SyncEngine.syncNow(SyncReason.FOREGROUND)
  }

  @Synchronized
  fun ensureInit(context: Context) {
    if (initialized) return
    val app = context.applicationContext
    // DeviceManager reads Paper on first access, and ActiveServer reads DeviceManager
    DbManager.initialize(app)
    ActiveServer.init(app)
    ServerReachability.start(app)
    SyncEngine.init(app) { ApiHandler(app) }
    CoverCache.init(app)
    initialized = true
  }

  fun setForeground(foreground: Boolean) {
    isForeground = foreground
    if (foreground) {
      ServerReachability.onForeground()
      SyncEngine.syncNow(SyncReason.FOREGROUND)
    }
  }

  @Synchronized
  internal fun resetForTest() {
    initialized = false
    isForeground = false
    autoLeaseUntil = 0L
    clock = System::currentTimeMillis
  }
}
