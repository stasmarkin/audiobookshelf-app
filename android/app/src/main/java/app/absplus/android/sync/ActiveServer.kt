package app.absplus.android.sync

import android.content.Context
import android.content.SharedPreferences
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.DeviceManager

/**
 * The saved server the app works with, even when there is no live session: offline on a cold start, after a logout
 * or after the server rejected the refresh token. [DeviceManager.serverConnectionConfig] is null in all of those.
 */
object ActiveServer {
  private const val PREFS = "absplus_sync"
  private const val KEY_ACTIVE_ID = "activeServerConfigId"

  private var prefs: SharedPreferences? = null
  @Volatile private var rememberedId: String? = null

  @Synchronized
  fun init(context: Context) {
    if (prefs != null) return
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs = p
    val stored = p.getString(KEY_ACTIVE_ID, null)
    // The server the app last signed in to wins when it is still saved: on the first start of this version, and after
    // a rollback, where the older version switched servers by changing only lastServerConnectionConfigId
    val last = DeviceManager.deviceData.lastServerConnectionConfigId
    rememberedId = if (last != null && DeviceManager.getServerConnectionConfig(last) != null) last else stored
    if (rememberedId != stored) p.edit().putString(KEY_ACTIVE_ID, rememberedId).apply()
  }

  fun current(): ServerConnectionConfig? =
          DeviceManager.serverConnectionConfig ?: DeviceManager.getServerConnectionConfig(rememberedId)

  fun remember(serverConfigId: String) {
    rememberedId = serverConfigId
    prefs?.edit()?.putString(KEY_ACTIVE_ID, serverConfigId)?.apply()
    ServerReachability.onServerConfigured(serverConfigId)
  }

  fun forget(serverConfigId: String) {
    if (rememberedId != serverConfigId) return
    rememberedId = null
    prefs?.edit()?.remove(KEY_ACTIVE_ID)?.apply()
    ServerReachability.onServerCleared()
  }

  @Synchronized
  internal fun resetForTest() {
    prefs?.edit()?.clear()?.commit()
    prefs = null
    rememberedId = null
  }
}
