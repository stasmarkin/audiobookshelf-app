package app.absplus.android.plugins

import app.absplus.android.sync.ActiveServer
import app.absplus.android.sync.ServerReachability
import app.absplus.android.sync.ServerState
import app.absplus.android.sync.SyncEngine
import app.absplus.android.sync.SyncReason
import app.absplus.android.sync.SyncRuntime
import app.absplus.android.sync.SyncState
import app.absplus.android.sync.UserDataStore
import com.getcapacitor.JSArray
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
 * Offline-first data bridge: the active server's reachability (its state, the WebView's reports about its own requests
 * and socket, a manual retry), the user's bookmarks from userdata.db, and the sync engine (`onDataChanged`,
 * `onSyncState`, manual sync, server events the WebView received).
 */
@CapacitorPlugin(name = "AbsData")
class AbsData : Plugin() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun load() {
    SyncRuntime.ensureInit(context)
    scope.launch {
      // Also sent when only the server changes, so the WebView learns which server the state is about
      combine(ServerReachability.state, ServerReachability.serverConfigId) { state, id -> state to id }
              .distinctUntilChanged()
              .collect { (state, id) -> notifyListeners("onServerState", serverState(state, id)) }
    }
    scope.launch {
      SyncEngine.dataChanges.collect { change ->
        notifyListeners("onDataChanged", JSObject().put("scope", change.scope).put("ids", JSArray(change.ids)))
      }
    }
    scope.launch { SyncEngine.syncState.collect { notifyListeners("onSyncState", syncState(it)) } }
  }

  override fun handleOnDestroy() {
    scope.cancel()
    io.cancel()
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
    SyncEngine.onSocketLost()
    call.resolve()
  }

  /** Visible bookmarks of the active server; empty without one. */
  @PluginMethod
  fun getBookmarks(call: PluginCall) {
    val store = SyncEngine.store
    val serverConfigId = ActiveServer.current()?.id
    if (store == null || serverConfigId == null) {
      call.resolve(JSObject().put("bookmarks", JSArray()))
      return
    }
    io.launch {
      try {
        val list = JSArray()
        store.visibleBookmarks(serverConfigId).forEach { bookmark ->
          list.put(JSObject()
                  .put("libraryItemId", bookmark.libraryItemId)
                  .put("time", bookmark.time)
                  .put("title", bookmark.title)
                  .put("createdAt", bookmark.createdAt))
        }
        call.resolve(JSObject().put("bookmarks", list))
      } catch (e: Exception) {
        call.reject("Could not read bookmarks: ${e.message}")
      }
    }
  }

  @PluginMethod
  fun addBookmark(call: PluginCall) = writeBookmark(call, needsTitle = true) { store, serverConfigId, libraryItemId, time, title ->
    store.addBookmark(serverConfigId, libraryItemId, time, title!!)
  }

  @PluginMethod
  fun renameBookmark(call: PluginCall) = writeBookmark(call, needsTitle = true) { store, serverConfigId, libraryItemId, time, title ->
    store.renameBookmark(serverConfigId, libraryItemId, time, title!!)
  }

  @PluginMethod
  fun removeBookmark(call: PluginCall) = writeBookmark(call, needsTitle = false) { store, serverConfigId, libraryItemId, time, _ ->
    store.removeBookmark(serverConfigId, libraryItemId, time)
  }

  /**
   * A bookmark write for `serverConfigId` (the server a downloaded copy belongs to), the active server by default.
   * Its outbox row is sent while that server is active.
   */
  private fun writeBookmark(
          call: PluginCall,
          needsTitle: Boolean,
          write: suspend (UserDataStore, String, String, Double, String?) -> Unit
  ) {
    val store = SyncEngine.store
    val serverConfigId = call.getString("serverConfigId") ?: ActiveServer.current()?.id
    val libraryItemId = call.getString("libraryItemId")
    val time = (call.data.opt("time") as? Number)?.toDouble()
    val title = call.getString("title")
    when {
      store == null -> return call.reject("Sync is not started")
      serverConfigId.isNullOrEmpty() -> return call.reject("No server")
      libraryItemId.isNullOrEmpty() || time == null || !time.isFinite() || time < 0 ->
        return call.reject("libraryItemId and a valid time are required")
      needsTitle && title == null -> return call.reject("title is required")
    }
    io.launch {
      try {
        write(store!!, serverConfigId!!, libraryItemId!!, time!!, title)
        SyncEngine.onLocalWrite()
        call.resolve()
      } catch (e: Exception) {
        call.reject(e.message ?: "Could not save the bookmark")
      }
    }
  }

  /** `reason`: "manual" (default) or "socket-init". */
  @PluginMethod
  fun syncNow(call: PluginCall) {
    SyncEngine.syncNow(if (call.getString("reason") == "socket-init") SyncReason.SOCKET_INIT else SyncReason.MANUAL)
    call.resolve()
  }

  @PluginMethod
  fun getSyncState(call: PluginCall) {
    call.resolve(syncState(SyncEngine.syncState.value))
  }

  /** A server event with the server that was active when the WebView received it; resolves `{ applied }`. */
  @PluginMethod
  fun applyServerEvent(call: PluginCall) {
    val event = call.getString("event")
    val payload = call.getObject("payload")
    if (event.isNullOrEmpty() || payload == null) {
      call.reject("event and payload are required")
      return
    }
    val serverConfigId = call.getString("serverConfigId")
    io.launch {
      try {
        val applied = SyncEngine.applyEvent(event, payload.toString(), serverConfigId)
        call.resolve(JSObject().put("applied", applied))
      } catch (e: Exception) {
        call.reject(e.message ?: "Could not apply the event")
      }
    }
  }

  private fun syncState(state: SyncState) = JSObject()
          .put("running", state.running)
          .put("pendingCount", state.pendingCount)
          .put("lastError", state.lastError ?: JSONObject.NULL)
          .put("lastSyncAt", state.lastSyncAt ?: JSONObject.NULL)

  private fun serverState(state: ServerState, serverConfigId: String?) = JSObject()
          .put("state", state.name.lowercase())
          .put("serverConfigId", serverConfigId ?: JSONObject.NULL)
}
