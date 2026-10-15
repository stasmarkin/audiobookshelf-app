package app.absplus.android.sync

import android.content.Context
import app.absplus.android.managers.DbManager
import app.absplus.android.server.ApiHandler
import kotlinx.coroutines.flow.MutableSharedFlow

/** A download of [libraryItemId] (server ids; [episodeId] for a podcast episode) for [serverConfigId] is on the device. */
data class LocalCopyReady(val serverConfigId: String, val libraryItemId: String, val episodeId: String?)

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

  @Synchronized
  fun ensureInit(context: Context) {
    if (initialized) return
    val app = context.applicationContext
    // DeviceManager reads Paper on first access, and ActiveServer reads DeviceManager
    DbManager.initialize(app)
    ActiveServer.init(app)
    ServerReachability.start(app)
    SyncEngine.init(app) { ApiHandler(app) }
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
  }
}
