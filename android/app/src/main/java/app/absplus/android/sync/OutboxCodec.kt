package app.absplus.android.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Kinds of outbox rows. Stored as text: a version that does not know a kind drops the row with a log entry. */
object OutboxKind {
  const val PROGRESS_PATCH = "progress_patch"
  const val PROGRESS_DELETE = "progress_delete"
  const val BOOKMARK_ADD = "bookmark_add"
  const val BOOKMARK_UPDATE = "bookmark_update"
  const val BOOKMARK_REMOVE = "bookmark_remove"
}

/**
 * The fields of a server media progress that a `progress_patch` may send, in the order they are written. The body of
 * the PATCH carries only the listed fields (from the row at send time) and `lastUpdate`, so marking a book finished
 * does not overwrite the audio position another device saved.
 */
val PROGRESS_PATCH_FIELDS = listOf(
        "duration", "progress", "currentTime", "isFinished", "hideFromContinueListening",
        "ebookLocation", "ebookProgress", "finishedAt", "startedAt")

/** What listening changes; sent after a reset that playback followed. */
val PLAYBACK_FIELDS = listOf("duration", "progress", "currentTime", "isFinished")

/** Outbox key of a progress row: `<libraryItemId>|<episodeId>`, with "" for a book. */
fun progressItemKey(libraryItemId: String, episodeId: String?): String = "$libraryItemId|${episodeId.orEmpty()}"

/** Outbox key of a bookmark: `<libraryItemId>@<canonicalTime>`. */
fun bookmarkItemKey(libraryItemId: String, time: Double): String = "$libraryItemId@${canonicalTime(time)}"

/** libraryItemId and episodeId ("" for a book) of a [progressItemKey]. */
fun progressKeyParts(itemKey: String): Pair<String, String> = itemKey.substringBeforeLast('|') to itemKey.substringAfterLast('|', "")

fun bookmarkKeyLibraryItemId(itemKey: String): String = itemKey.substringBeforeLast('@')

/**
 * A bookmark time as the server path and keys expect it: a whole number without ".0" (754.0 and 754 are one bookmark,
 * DELETE /bookmark/754), a fraction as is.
 */
fun canonicalTime(time: Double): String {
  require(time.isFinite()) { "Bookmark time must be finite: $time" }
  return if (time == Math.floor(time) && Math.abs(time) < 1e15) time.toLong().toString() else time.toString()
}

/** JSON payloads of outbox rows, always an object. Readers return null for a payload they cannot use. */
object OutboxPayload {
  private val mapper = jacksonObjectMapper()
  private val nodes = JsonNodeFactory.instance

  /**
   * `{"fields":[…]}`: unknown names are left out, the rest are kept in [PROGRESS_PATCH_FIELDS] order. [finishedMark]:
   * true for the user's explicit "finished"/"not finished" (playback keeps it until sent), false for a change that
   * overrides one (a reset), null for neither. [conditionalFields]: those of [fields] sent only if the device's
   * position is ahead of the server's (versions that do not know them send them anyway).
   */
  fun progressPatch(fields: Collection<String>, finishedMark: Boolean? = null, conditionalFields: Collection<String> = emptyList()): String {
    val array = nodes.arrayNode()
    PROGRESS_PATCH_FIELDS.filter { it in fields }.forEach { array.add(it) }
    val node = nodes.objectNode().set<ObjectNode>("fields", array)
    if (finishedMark != null) node.put("finishedMark", finishedMark)
    val conditional = PROGRESS_PATCH_FIELDS.filter { it in fields && it in conditionalFields }
    if (conditional.isNotEmpty()) node.set<ObjectNode>("conditionalFields", nodes.arrayNode().also { a -> conditional.forEach { a.add(it) } })
    return mapper.writeValueAsString(node)
  }

  /** The fields of a `progress_patch` sent only if the device's position is ahead of the server's. */
  fun conditionalFields(payload: String): List<String> {
    val array = parse(payload)?.get("conditionalFields")?.takeIf { it.isArray } ?: return emptyList()
    val names = array.mapNotNull { node -> node.takeIf { it.isTextual }?.asText() }.toSet()
    return PROGRESS_PATCH_FIELDS.filter { it in names }
  }

  /** The `finishedMark` of a `progress_patch` payload; null when it has none. */
  fun finishedMark(payload: String): Boolean? = parse(payload)?.get("finishedMark")?.takeIf { it.isBoolean }?.asBoolean()

  /** The fields of a `progress_patch` payload, or null when it is not one. */
  fun progressPatchFields(payload: String): List<String>? {
    val array = parse(payload)?.get("fields")?.takeIf { it.isArray } ?: return null
    val names = array.mapNotNull { node -> node.takeIf { it.isTextual }?.asText() }.toSet()
    return PROGRESS_PATCH_FIELDS.filter { it in names }
  }

  /** [lastUpdate]: when the reset was made, so playback after it is still sent once the DELETE is answered. */
  fun progressDelete(serverProgressId: String, lastUpdate: Long? = null): String {
    val node = nodes.objectNode().put("serverProgressId", serverProgressId)
    if (lastUpdate != null) node.put("lastUpdate", lastUpdate)
    return mapper.writeValueAsString(node)
  }

  fun serverProgressId(payload: String): String? =
          parse(payload)?.get("serverProgressId")?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }

  fun deleteLastUpdate(payload: String): Long? = parse(payload)?.get("lastUpdate")?.takeIf { it.isIntegralNumber }?.asLong()

  /** `{"time":754.0,"title":"…"}` for add and update, `{"time":754.0}` for remove. */
  fun bookmark(time: Double, title: String? = null): String {
    val node = nodes.objectNode().put("time", time)
    if (title != null) node.put("title", title)
    return mapper.writeValueAsString(node)
  }

  fun bookmarkTime(payload: String): Double? =
          parse(payload)?.get("time")?.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() }

  fun bookmarkTitle(payload: String): String? = parse(payload)?.get("title")?.takeIf { it.isTextual }?.asText()

  /** [payload] with the id of the user the row belongs to; an unreadable payload is returned unchanged. */
  fun withUserId(payload: String, userId: String): String {
    val node = parse(payload) as? ObjectNode ?: return payload
    return mapper.writeValueAsString(node.put("userId", userId))
  }

  /** The owner written by [withUserId]; null for rows queued before owners were recorded. */
  fun userId(payload: String): String? = parse(payload)?.get("userId")?.takeIf { it.isTextual }?.asText()

  private fun parse(payload: String): JsonNode? =
          try {
            mapper.readTree(payload)?.takeIf { it.isObject }
          } catch (e: Exception) {
            null
          }
}
