package app.absplus.android.sync

import android.content.Context
import app.absplus.android.managers.DbManager

/**
 * Lazily starts the process-wide sync singletons. There is no Application class and the process can start without an
 * Activity (Android Auto, widget, media buttons), so every entry point calls [ensureInit].
 */
object SyncRuntime {
  private var initialized = false

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
    initialized = true
  }

  fun setForeground(foreground: Boolean) {
    isForeground = foreground
    if (foreground) ServerReachability.onForeground()
  }

  @Synchronized
  internal fun resetForTest() {
    initialized = false
    isForeground = false
  }
}
