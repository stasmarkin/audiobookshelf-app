package app.absplus.android.sync

import androidx.room.withTransaction
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.userdata.ServerSyncStateEntity
import app.absplus.android.db.userdata.UserDataDatabase
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncFailure(message: String) : Exception(message)

/** Pulls the user's data from the server into userdata.db: progress, bookmarks and the user object. */
class UserDataReconciler(
        private val http: SyncHttp,
        private val store: UserDataStore,
        private val db: UserDataDatabase,
        private val clock: () -> Long = System::currentTimeMillis,
        private val paperImport: PaperProgressImport? = null
) {
  private val mapper = jacksonObjectMapper()

  /**
   * A full pull of the user's data. From server 2.36.0 GET /api/me/progress and /api/me/bookmarks; before that, and
   * when a split endpoint is missing (404: a server that reports a newer version than it runs), one GET /api/me, which
   * also brings the user object. Nothing is applied unless both lists were read. Rows imported from Paper since this
   * server's last reconcile are sent first when newer ([PaperProgressImport]); then only this pull deletes progress
   * rows the server no longer has.
   */
  suspend fun reconcileUser(config: ServerConnectionConfig): Result<Unit> {
    var user: JsonNode? = null
    var progressNode: JsonNode? = null
    var bookmarksNode: JsonNode? = null
    var useMe = !versionAtLeast(config.version, "2.36.0")
    if (!useMe) {
      val progress = http.send(config, "GET", "/api/me/progress")
      if (progress == HttpOutcome.HttpError(404)) useMe = true
      else {
        if (progress !is HttpOutcome.Ok) return Result.failure(SyncFailure(describe(progress)))
        progressNode = listIn(readTree(progress.body), "mediaProgress")
        val bookmarks = http.send(config, "GET", "/api/me/bookmarks")
        if (bookmarks == HttpOutcome.HttpError(404)) useMe = true
        else {
          if (bookmarks !is HttpOutcome.Ok) return Result.failure(SyncFailure(describe(bookmarks)))
          bookmarksNode = listIn(readTree(bookmarks.body), "bookmarks")
        }
      }
    }
    if (useMe) {
      val me = http.send(config, "GET", "/api/me")
      if (me !is HttpOutcome.Ok) return Result.failure(SyncFailure(describe(me)))
      user = readTree(me.body)
      progressNode = user?.get("mediaProgress")
      bookmarksNode = user?.get("bookmarks")
    }
    val progress = parseProgressList(progressNode) ?: return Result.failure(SyncFailure("no progress in the response"))
    val bookmarks = parseBookmarks(bookmarksNode) ?: return Result.failure(SyncFailure("no bookmarks in the response"))
    val imported = paperImport?.unsettled(config.id).orEmpty()
    store.pushNewerImported(config.id, imported, progress)
    store.applyServerProgress(config.id, progress, deleteMissing = true) { paperImport?.unsettled(config.id).orEmpty() }
    store.applyServerBookmarks(config.id, bookmarks)
    if (user != null) saveUser(config.id, user, lastMeAt = clock())
    paperImport?.settle(config.id, imported)
    return Result.success(Unit)
  }

  /**
   * A user object from the server (socket `user_updated`, `/api/authorize`). It may be older than a just-sent change,
   * so it never deletes progress; parts it lacks change nothing. Parsed off the main thread: it can be megabytes.
   * @return whether it left recently settled bookmarks alone that it would have changed (see [UserDataStore])
   */
  suspend fun applyUserObject(serverConfigId: String, userJson: String): Boolean = withContext(Dispatchers.Default) {
    val user = readTree(userJson) ?: return@withContext false
    parseProgressList(user.get("mediaProgress"))?.let { store.applyServerProgress(serverConfigId, it) }
    if (user.isObject) saveUser(serverConfigId, user, lastMeAt = null)
    val bookmarks = parseBookmarks(user.get("bookmarks")) ?: return@withContext false
    store.applyServerBookmarks(serverConfigId, bookmarks, fromSnapshot = true)
  }

  /** Socket `user_item_progress_updated` (`{data: MediaProgress}`) or a bare record; whether it was taken. */
  suspend fun applyProgressEvent(serverConfigId: String, payloadJson: String): Boolean = withContext(Dispatchers.Default) {
    val root = readTree(payloadJson) ?: return@withContext false
    val record = parseServerProgress(root.get("data")?.takeIf { it.isObject } ?: root) ?: return@withContext false
    store.applyServerProgressCandidate(serverConfigId, record)
  }

  /**
   * Keeps the user object for offline use (permissions, settings) without progress, bookmarks and any token, and
   * tells the WebView when it changed. [lastMeAt]: set by a full pull only.
   */
  private suspend fun saveUser(serverConfigId: String, user: JsonNode, lastMeAt: Long?) {
    val stripped = (user.deepCopy<JsonNode>() as? ObjectNode ?: return).also { strip(it) }
    stripped.remove(listOf("mediaProgress", "bookmarks"))
    val json = mapper.writeValueAsString(stripped)
    val changed = db.withTransaction {
      val state = db.syncState().get(serverConfigId)
      val next = ServerSyncStateEntity(serverConfigId, lastMeAt ?: state?.lastMeAt, json, state?.schemaVersion ?: SYNC_STATE_SCHEMA)
      if (next != state) db.syncState().upsert(next)
      state?.userJson != json
    }
    if (changed) store.notifyUserChanged()
  }

  private fun strip(node: JsonNode) {
    if (node is ObjectNode) node.remove(TOKEN_FIELDS)
    node.forEach { strip(it) }
  }

  private fun readTree(json: String): JsonNode? =
          try {
            mapper.readTree(json)
          } catch (e: Exception) {
            null
          }

  /** The list itself, or the list under [field] of an object: the split endpoints are read in both shapes. */
  private fun listIn(root: JsonNode?, field: String): JsonNode? = if (root?.isArray == true) root else root?.get(field)

  /** Null when [array] is not a list; unreadable entries are skipped. */
  private fun parseProgressList(array: JsonNode?): List<ServerProgress>? {
    if (array == null || !array.isArray) return null
    return array.mapNotNull { parseServerProgress(it) }
  }

  /** Null when [array] is not a list; entries without an item id or a numeric time are skipped. */
  private fun parseBookmarks(array: JsonNode?): List<ServerBookmark>? {
    if (array == null || !array.isArray) return null
    return array.mapNotNull { node ->
      val libraryItemId = node.get("libraryItemId")?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }
      val timeNode = node.get("time")
      val time = when {
        timeNode == null -> null
        timeNode.isNumber -> timeNode.asDouble()
        timeNode.isTextual -> timeNode.asText().toDoubleOrNull()
        else -> null
      }?.takeIf { it.isFinite() && it >= 0 }
      if (libraryItemId == null || time == null) null
      else ServerBookmark(libraryItemId, time, node.get("title")?.asText().orEmpty(), node.get("createdAt")?.asLong() ?: 0L)
    }
  }

  private fun describe(outcome: HttpOutcome) = when (outcome) {
    is HttpOutcome.HttpError -> "HTTP ${outcome.code}"
    is HttpOutcome.Unreachable -> outcome.message
    HttpOutcome.Unauthorized -> "unauthorized"
    is HttpOutcome.Ok -> "ok"
  }

  companion object {
    const val SYNC_STATE_SCHEMA = 1
    private val TOKEN_FIELDS = listOf("token", "accessToken", "refreshToken")
  }
}

/** A server `MediaProgress` object; null without an item id or a numeric `lastUpdate`. */
fun parseServerProgress(node: JsonNode?): ServerProgress? {
  if (node == null || !node.isObject) return null
  fun text(name: String) = node.get(name)?.takeIf { it.isTextual }?.asText()
  fun number(name: String) = node.get(name)?.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() }
  fun long(name: String) = node.get(name)?.takeIf { it.isNumber }?.asLong()
  val libraryItemId = text("libraryItemId")?.takeIf { it.isNotEmpty() } ?: return null
  val lastUpdate = long("lastUpdate") ?: return null
  return ServerProgress(
          id = text("id").orEmpty(), libraryItemId = libraryItemId, episodeId = text("episodeId")?.takeIf { it.isNotEmpty() },
          duration = number("duration") ?: 0.0, progress = number("progress") ?: 0.0,
          currentTime = number("currentTime") ?: 0.0, isFinished = node.get("isFinished")?.asBoolean() ?: false,
          hideFromContinueListening = node.get("hideFromContinueListening")?.asBoolean() ?: false,
          ebookLocation = text("ebookLocation"), ebookProgress = number("ebookProgress"), lastUpdate = lastUpdate,
          startedAt = long("startedAt") ?: 0L, finishedAt = long("finishedAt"))
}

/** Whether a server [version] such as "2.36.0" (or "v2.36.1-beta") is at least [min]; an unknown version is not. */
fun versionAtLeast(version: String?, min: String): Boolean {
  if (version.isNullOrBlank()) return false
  fun parts(v: String) = v.trim().removePrefix("v").split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
  val have = parts(version)
  val need = parts(min)
  for (i in 0 until maxOf(have.size, need.size)) {
    val a = have.getOrElse(i) { 0 }
    val b = need.getOrElse(i) { 0 }
    if (a != b) return a > b
  }
  return true
}
