package app.absplus.android.plugins

import app.absplus.android.sync.ServerReachability
import app.absplus.android.sync.ServerState
import app.absplus.android.sync.SyncRuntime
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Offline-first data bridge. For now only the active server's reachability: its state, the
 * WebView's reports about its own requests and socket, and a manual retry.
 */
@CapacitorPlugin(name = "AbsData")
class AbsData : Plugin() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun load() {
    SyncRuntime.ensureInit(context)
    scope.launch {
      // Also sent when only the server changes, so the WebView learns which server the state is about
      combine(ServerReachability.state, ServerReachability.serverConfigId) { state, id -> state to id }
              .distinctUntilChanged()
              .collect { (state, id) -> notifyListeners("onServerState", serverState(state, id)) }
    }
  }

  override fun handleOnDestroy() {
    scope.cancel()
    super.handleOnDestroy()
  }

  @PluginMethod
  fun getServerState(call: PluginCall) {
    call.resolve(serverState(ServerReachability.state.value, ServerReachability.serverConfigId.value))
  }

  @PluginMethod
  fun retryServer(call: PluginCall) {
    ServerReachability.retryNow()
    call.resolve()
  }

  @PluginMethod
  fun reportServerResponse(call: PluginCall) {
    val address = call.getString("address")
    val status = call.getInt("status")
    if (address.isNullOrEmpty() || status == null) {
      call.reject("address and status are required")
      return
    }
    ServerReachability.reportResponse(address, status)
    call.resolve()
  }

  @PluginMethod
  fun reportServerUnreachable(call: PluginCall) {
    val address = call.getString("address")
    if (address.isNullOrEmpty()) {
      call.reject("address is required")
      return
    }
    ServerReachability.reportUnreachable(address)
    call.resolve()
  }

  @PluginMethod
  fun reportSocketLost(call: PluginCall) {
    ServerReachability.reportSocketLost()
    call.resolve()
  }

  private fun serverState(state: ServerState, serverConfigId: String?) = JSObject()
          .put("state", state.name.lowercase())
          .put("serverConfigId", serverConfigId ?: JSONObject.NULL)
}
