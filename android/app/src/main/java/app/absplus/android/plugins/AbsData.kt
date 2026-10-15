package app.absplus.android.plugins

import android.content.Context
import app.absplus.android.sync.ActiveServer
import app.absplus.android.sync.AuthorsRequest
import app.absplus.android.sync.CatalogPrefs
import app.absplus.android.sync.CatalogReader
import app.absplus.android.sync.ItemsRequest
import app.absplus.android.sync.MAX_READ_LIMIT
import app.absplus.android.sync.SeriesRequest
import app.absplus.android.sync.ServerReachability
import app.absplus.android.sync.ServerState
import app.absplus.android.sync.SyncEngine
import app.absplus.android.sync.SyncReason
import app.absplus.android.sync.SyncRuntime
import app.absplus.android.sync.SyncState
import app.absplus.android.sync.UserDataJson
import app.absplus.android.sync.UserDataStore
import app.absplus.android.sync.parseServerProgress
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import org.json.JSONArray
import org.json.JSONObject

/** The payload of a server event as JSON text: an object, or the array of `items_added`/`items_updated`; else null. */
internal fun eventPayloadJson(data: JSONObject): String? = when (val payload = data.opt("payload")) {
  is JSONObject, is JSONArray -> payload.toString()
  else -> null
}

private fun flag(data: JSONObject, key: String): Boolean = when (val value = data.opt(key)) {
  is Boolean -> value
  is Number -> value.toInt() != 0
  is String -> value == "1" || value == "true"
  else -> false
}

/** `getLibraryItems` arguments; IllegalArgumentException with the message the call is rejected with. */
internal fun itemsRequest(data: JSONObject): ItemsRequest {
  val (libraryId, limit, page) = pageArguments(data)
  return ItemsRequest(libraryId, text(data, "sort"), flag(data, "desc"), text(data, "filter"), flag(data, "collapseseries"),
          flag(data, "sortingIgnorePrefix"), limit, page)
}

/** `getSeries` arguments; IllegalArgumentException with the message the call is rejected with. */
internal fun seriesRequest(data: JSONObject): SeriesRequest {
  val (libraryId, limit, page) = pageArguments(data)
  return SeriesRequest(libraryId, text(data, "sort"), flag(data, "desc"), flag(data, "sortingIgnorePrefix"), limit, page)
}

/** `getAuthors` arguments; IllegalArgumentException with the message the call is rejected with. */
internal fun authorsRequest(data: JSONObject): AuthorsRequest {
  val (libraryId, limit, page) = pageArguments(data)
  return AuthorsRequest(libraryId, limit, page)
}

private fun text(data: JSONObject, key: String) = data.opt(key)?.takeIf { it is String && it.isNotEmpty() } as String?

private fun pageArguments(data: JSONObject): Triple<String, Int, Int> {
  val libraryId = data.optString("libraryId").takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("libraryId is required")
  val limit = (data.opt("limit") as? Number)?.toInt() ?: data.optString("limit").toIntOrNull()
  if (limit == null || limit !in 1..MAX_READ_LIMIT) throw IllegalArgumentException("limit must be 1..$MAX_READ_LIMIT")
  val page = (data.opt("page") as? Number)?.toInt() ?: data.optString("page").toIntOrNull() ?: 0
  if (page < 0) throw IllegalArgumentException("page must not be negative")
  return Triple(libraryId, limit, page)
}

/**
 * Offline-first data bridge: the active server's reachability (its state, the WebView's reports about its own requests
 * and socket, a manual retry), the user's progress, bookmarks and user object from userdata.db with their writes, the
 * catalog from replica.db (at most [MAX_READ_LIMIT] entities a call), and the sync engine (`onDataChanged`,
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

  /** Visible bookmarks of `serverConfigId` (a downloaded copy's server), the active server by default; empty without one. */
  @PluginMethod
  fun getBookmarks(call: PluginCall) {
    val store = SyncEngine.store
    val serverConfigId = call.getString("serverConfigId") ?: ActiveServer.current()?.id
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
      needsTitle && title.isNullOrBlank() -> return call.reject("title is required")
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

  /**
   * The user's data of `serverConfigId` (the active server by default): `{ user, mediaProgress, bookmarks }`, with the
   * user object as last known (null before the first one) and progress in the server's shape.
   */
  @PluginMethod
  fun getUserData(call: PluginCall) {
    val store = SyncEngine.store
    val serverConfigId = call.getString("serverConfigId") ?: ActiveServer.current()?.id
    if (store == null || serverConfigId == null) {
      call.resolve(JSObject(UserDataJson.userData(null, emptyList(), emptyList()).toString()))
      return
    }
    io.launch {
      try {
        val data = UserDataJson.userData(store.userJson(serverConfigId), store.progressRows(serverConfigId),
                store.visibleBookmarks(serverConfigId))
        call.resolve(JSObject(data.toString()))
      } catch (e: Exception) {
        call.reject("Could not read the user's data: ${e.message}")
      }
    }
  }

  /** `{ mediaProgress }` of the given `libraryItemIds` (every episode of a podcast), for refreshing only those. */
  @PluginMethod
  fun getMediaProgress(call: PluginCall) {
    val store = SyncEngine.store
    val serverConfigId = call.getString("serverConfigId") ?: ActiveServer.current()?.id
    val ids = call.getArray("libraryItemIds")?.toList<Any>()?.filterIsInstance<String>()?.toSet().orEmpty()
    if (store == null || serverConfigId == null || ids.isEmpty()) {
      call.resolve(JSObject().put("mediaProgress", JSArray()))
      return
    }
    io.launch {
      try {
        val rows = store.progressRowsFor(serverConfigId, ids)
        call.resolve(JSObject().put("mediaProgress", JSArray(UserDataJson.progressList(rows).toString())))
      } catch (e: Exception) {
        call.reject("Could not read progress: ${e.message}")
      }
    }
  }

  @PluginMethod
  fun markFinished(call: PluginCall) = writeProgress(call) { store, serverConfigId, libraryItemId ->
    val isFinished = call.getBoolean("isFinished") ?: throw IllegalArgumentException("isFinished is required")
    store.markFinished(serverConfigId, libraryItemId, call.getString("episodeId")?.ifEmpty { null }, isFinished)
  }

  @PluginMethod
  fun resetProgress(call: PluginCall) = writeProgress(call) { store, serverConfigId, libraryItemId ->
    store.resetProgress(serverConfigId, libraryItemId, call.getString("episodeId")?.ifEmpty { null })
  }

  @PluginMethod
  fun updateEbookProgress(call: PluginCall) = writeProgress(call) { store, serverConfigId, libraryItemId ->
    val ebookProgress = (call.data.opt("ebookProgress") as? Number)?.toDouble()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("ebookProgress is required")
    store.updateEbookProgress(serverConfigId, libraryItemId, call.getString("ebookLocation"), ebookProgress)
  }

  /**
   * A progress write for `serverConfigId` (the server a downloaded copy belongs to), the active server by default; its
   * outbox row is sent while that server is active.
   */
  private fun writeProgress(call: PluginCall, write: suspend (UserDataStore, String, String) -> Unit) {
    val store = SyncEngine.store
    val serverConfigId = call.getString("serverConfigId") ?: ActiveServer.current()?.id
    val libraryItemId = call.getString("libraryItemId")
    when {
      store == null -> return call.reject("Sync is not started")
      serverConfigId.isNullOrEmpty() -> return call.reject("No server")
      libraryItemId.isNullOrEmpty() -> return call.reject("libraryItemId is required")
    }
    io.launch {
      try {
        SyncEngine.write { write(it, serverConfigId!!, libraryItemId!!) }
        SyncEngine.onLocalWrite()
        call.resolve()
      } catch (e: Exception) {
        call.reject(e.message ?: "Could not save the progress")
      }
    }
  }

  /**
   * A server progress record the WebView got (the value on refocus), merged by the one rule. Resolves `{ applied,
   * protected, currentTime }`: the row's position after the merge, and whether the device keeps its own (an unsent
   * change or listening session), so the player can follow a value an earlier sync already took.
   */
  @PluginMethod
  fun applyServerProgress(call: PluginCall) {
    val store = SyncEngine.store
    val serverConfigId = call.getString("serverConfigId") ?: ActiveServer.current()?.id
    val record = call.getObject("progress")?.let { parseServerProgress(jacksonObjectMapper().readTree(it.toString())) }
    when {
      store == null -> return call.reject("Sync is not started")
      serverConfigId.isNullOrEmpty() -> return call.reject("No server")
      record == null -> return call.reject("progress with libraryItemId and lastUpdate is required")
    }
    io.launch {
      try {
        val result = SyncEngine.write { it.mergeServerProgress(serverConfigId!!, record!!) }
        call.resolve(JSObject()
                .put("applied", result.applied)
                .put("protected", result.protected)
                .put("currentTime", result.row?.currentTime ?: JSONObject.NULL))
      } catch (e: Exception) {
        call.reject(e.message ?: "Could not apply the progress")
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

  /**
   * A server event with the server that was active when the WebView received it; resolves `{ applied }`. The payload
   * is an object, or an array for `items_added`/`items_updated`.
   */
  @PluginMethod
  fun applyServerEvent(call: PluginCall) {
    val event = call.getString("event")
    val payload = eventPayloadJson(call.data)
    if (event.isNullOrEmpty() || payload == null) {
      call.reject("event and payload are required")
      return
    }
    val serverConfigId = call.getString("serverConfigId")
    io.launch {
      try {
        val applied = SyncEngine.applyEvent(event, payload, serverConfigId)
        call.resolve(JSObject().put("applied", applied))
      } catch (e: Exception) {
        call.reject(e.message ?: "Could not apply the event")
      }
    }
  }

  private fun emptyReplica() = JSONObject().put("reconciledAt", JSONObject.NULL).put("complete", false)

  /** Runs [read] for the catalog of `serverConfigId` (the active server by default); [empty] without one. */
  private fun readCatalog(call: PluginCall, empty: () -> JSONObject, read: suspend (CatalogReader, String) -> JSONObject) {
    val reader = SyncEngine.catalog
    val serverConfigId = call.getString("serverConfigId") ?: ActiveServer.current()?.id
    if (reader == null || serverConfigId == null) {
      call.resolve(JSObject(empty().put("serverConfigId", serverConfigId ?: JSONObject.NULL).toString()))
      return
    }
    io.launch {
      try {
        call.resolve(JSObject(read(reader, serverConfigId).toString()))
      } catch (e: IllegalArgumentException) {
        call.reject(e.message ?: "Invalid request")
      } catch (e: Exception) {
        call.reject("Could not read the catalog: ${e.message}")
      }
    }
  }

  /** `{ serverConfigId, libraries, replica: { reconciledAt, complete } }` in the server's order. */
  @PluginMethod
  fun getLibraries(call: PluginCall) = readCatalog(call, { JSONObject().put("libraries", JSONArray()).put("replica", emptyReplica()) }) { reader, sc ->
    reader.libraries(sc)
  }

  /** `{ serverConfigId, library, filterdata, issues, numUserPlaylists, replica }`; `library: null` when the replica lacks it. */
  @PluginMethod
  fun getLibrary(call: PluginCall) {
    val id = call.getString("id")
    if (id.isNullOrEmpty()) return call.reject("id is required")
    readCatalog(call, {
      JSONObject().put("library", JSONObject.NULL).put("filterdata", JSONObject.NULL).put("issues", 0).put("numUserPlaylists", 0)
              .put("replica", emptyReplica())
    }) { reader, sc -> reader.library(sc, id) }
  }

  /** A page of `GET /api/libraries/:id/items` (minified) from the replica; `limit` must be 1..100. */
  @PluginMethod
  fun getLibraryItems(call: PluginCall) {
    val request = try {
      itemsRequest(call.data)
    } catch (e: IllegalArgumentException) {
      return call.reject(e.message)
    }
    readCatalog(call, {
      JSONObject().put("results", JSONArray()).put("total", 0).put("limit", request.limit).put("page", request.page)
              .put("replica", emptyReplica())
    }) { reader, sc -> reader.items(sc, request) }
  }

  /** A page of `GET /api/libraries/:id/series` from the replica, books included; `limit` must be 1..100. */
  @PluginMethod
  fun getSeries(call: PluginCall) {
    val request = try {
      seriesRequest(call.data)
    } catch (e: IllegalArgumentException) {
      return call.reject(e.message)
    }
    readCatalog(call, {
      JSONObject().put("results", JSONArray()).put("total", 0).put("limit", request.limit).put("page", request.page)
              .put("replica", emptyReplica())
    }) { reader, sc -> reader.series(sc, request) }
  }

  /** `{ serverConfigId, series, replica }`; `series: null` when the replica lacks it. */
  @PluginMethod
  fun getSeriesById(call: PluginCall) {
    val id = call.getString("id")
    if (id.isNullOrEmpty()) return call.reject("id is required")
    readCatalog(call, { JSONObject().put("series", JSONObject.NULL).put("replica", emptyReplica()) }) { reader, sc -> reader.seriesById(sc, id) }
  }

  /** A page of `GET /api/libraries/:id/authors` from the replica, ordered by name; `limit` must be 1..100. */
  @PluginMethod
  fun getAuthors(call: PluginCall) {
    val request = try {
      authorsRequest(call.data)
    } catch (e: IllegalArgumentException) {
      return call.reject(e.message)
    }
    readCatalog(call, {
      JSONObject().put("results", JSONArray()).put("total", 0).put("limit", request.limit).put("page", request.page)
              .put("replica", emptyReplica())
    }) { reader, sc -> reader.authors(sc, request) }
  }

  /** The library the next catalog pass reconciles first. */
  @PluginMethod
  fun setCurrentLibrary(call: PluginCall) {
    val libraryId = call.getString("libraryId")
    if (libraryId.isNullOrEmpty()) return call.reject("libraryId is required")
    context.getSharedPreferences(CatalogPrefs.PREFS, Context.MODE_PRIVATE).edit()
            .putString(CatalogPrefs.CURRENT_LIBRARY, libraryId).apply()
    call.resolve()
  }

  private fun syncState(state: SyncState) = JSObject()
          .put("running", state.running)
          .put("catalogRunning", state.catalogRunning)
          .put("pendingCount", state.pendingCount)
          .put("lastError", state.lastError ?: JSONObject.NULL)
          .put("lastSyncAt", state.lastSyncAt ?: JSONObject.NULL)

  private fun serverState(state: ServerState, serverConfigId: String?) = JSObject()
          .put("state", state.name.lowercase())
          .put("serverConfigId", serverConfigId ?: JSONObject.NULL)
}
