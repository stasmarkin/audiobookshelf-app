package app.absplus.android.sync

import app.absplus.android.data.ServerConnectionConfig
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class SyncFailure(message: String) : Exception(message) {
  /** Dropped as [HttpOutcome.STALE] after a logout or server switch, not refused by the server. */
  val stale: Boolean get() = message == HttpOutcome.STALE.message
}

/** Pulls the user's data from the server into userdata.db. In this release: bookmarks. */
class UserDataReconciler(private val http: SyncHttp, private val store: UserDataStore) {
  private val mapper = jacksonObjectMapper()

  /**
   * GET /api/me/bookmarks from server 2.36.0, the bookmarks of GET /api/me before, and also when the split endpoint
   * is missing (404: a server that reports a newer version than it runs).
   */
  suspend fun reconcileBookmarks(config: ServerConnectionConfig): Result<Unit> {
    val split = versionAtLeast(config.version, "2.36.0")
    var outcome = http.send(config, "GET", if (split) "/api/me/bookmarks" else "/api/me")
    if (split && outcome == HttpOutcome.HttpError(404)) outcome = http.send(config, "GET", "/api/me")
    if (outcome !is HttpOutcome.Ok) return Result.failure(SyncFailure(describe(outcome)))
    val root = readTree(outcome.body)
    // The split endpoint is read in both shapes it could have: a bare array or an object with `bookmarks`
    val array = if (root?.isArray == true) root else root?.get("bookmarks")
    val list = parseBookmarks(array) ?: return Result.failure(SyncFailure("no bookmarks in the response"))
    store.applyServerBookmarks(config.id, list)
    return Result.success(Unit)
  }

  /**
   * A user object from the server (socket `user_updated`, `/api/authorize`); one without `bookmarks` changes nothing.
   * @return whether it left recently settled bookmarks alone that it would have changed (see [UserDataStore])
   */
  suspend fun applyUserObject(serverConfigId: String, userJson: String): Boolean {
    val list = parseBookmarks(readTree(userJson)?.get("bookmarks")) ?: return false
    return store.applyServerBookmarks(serverConfigId, list, fromSnapshot = true)
  }

  private fun readTree(json: String): JsonNode? =
          try {
            mapper.readTree(json)
          } catch (e: Exception) {
            null
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
