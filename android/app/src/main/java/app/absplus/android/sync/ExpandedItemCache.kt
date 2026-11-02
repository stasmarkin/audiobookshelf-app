package app.absplus.android.sync

import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.replica.DetailLevel
import app.absplus.android.db.replica.ReplicaDatabase
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

enum class OpenResult { UP_TO_DATE, UPDATED, REMOVED, NOT_IN_REPLICA, SKIPPED, FAILED }

/** [serverItem]: the server's answer without `userMediaProgress`, only for [OpenResult.UPDATED] and [OpenResult.NOT_IN_REPLICA]. */
data class OpenOutcome(val result: OpenResult, val serverItem: String? = null)

/**
 * The expanded copy of an item (chapters, tracks, files; a podcast's episodes) asked from the server when the user opens
 * it online, one at a time and only when the replica has no copy of the item's current version, or when [open] is
 * forced. The answer is written as an `item_updated` event and a 404 as `item_removed` ([applyItemEvent]): the copy
 * follows the same rules as the socket's deltas. Progress in the answer is dropped; it is synced on its own.
 */
class ExpandedItemCache(
        private val db: ReplicaDatabase,
        private val http: SyncHttp,
        private val isOnline: () -> Boolean,
        private val activeServerId: () -> String?,
        /** True when the event was applied or buffered for after a catalog pass, false when the replica has a newer version. */
        private val applyItemEvent: suspend (sc: String, event: String, payloadJson: String) -> Boolean,
        private val log: (String) -> Unit
) {
  private val mapper = CatalogJson.mapper
  private val lock = Any()
  private val inFlight = HashMap<String, CompletableDeferred<OpenOutcome>>()

  /**
   * A request already running for the item is joined whatever [force] says, so the page of an episode and of its
   * podcast opened together send one.
   */
  suspend fun open(config: ServerConnectionConfig, itemId: String, force: Boolean = false): OpenOutcome {
    if (!isOnline()) return OpenOutcome(OpenResult.SKIPPED)
    val key = config.id + "\u0000" + itemId
    synchronized(lock) { inFlight[key] }?.let { return it.await() }
    if (!force && db.items().get(config.id, itemId)?.detailLevel == DetailLevel.EXPANDED) return OpenOutcome(OpenResult.UP_TO_DATE)
    val (deferred, owner) = synchronized(lock) {
      inFlight[key]?.let { it to false } ?: CompletableDeferred<OpenOutcome>().also { inFlight[key] = it }.let { it to true }
    }
    if (!owner) return deferred.await()
    try {
      val outcome = try {
        fetch(config, itemId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("Expanded item $itemId failed: $e")
        OpenOutcome(OpenResult.FAILED)
      }
      deferred.complete(outcome)
      return outcome
    } finally {
      // A cancelled owner still releases those who joined it
      deferred.complete(OpenOutcome(OpenResult.FAILED))
      synchronized(lock) { if (inFlight[key] === deferred) inFlight.remove(key) }
    }
  }

  private suspend fun fetch(config: ServerConnectionConfig, itemId: String): OpenOutcome {
    val sc = config.id
    val path = "/api/items/${URLEncoder.encode(itemId, "UTF-8").replace("+", "%20")}?expanded=1&include=progress,rssfeed"
    val answer = http.send(config, "GET", path)
    coroutineContext.ensureActive()
    // An answer for the server that was active before a switch must not land in the replica
    if (activeServerId() != sc) return OpenOutcome(OpenResult.FAILED)
    return when (answer) {
      is HttpOutcome.Ok -> {
        val item = mapper.readTree(answer.body) as? ObjectNode ?: return OpenOutcome(OpenResult.FAILED)
        item.remove("userMediaProgress")
        val body = mapper.writeValueAsString(item)
        val row = db.items().get(sc, itemId)
        // Checked before the write: during a catalog pass the event is only buffered, so the applier cannot tell yet
        if (row != null && item.path("updatedAt").asLong() < row.updatedAt) return OpenOutcome(OpenResult.UP_TO_DATE)
        val libraryId = item.path("libraryId").asText()
        when {
          libraryId.isEmpty() || db.libraries().get(sc, libraryId) == null -> OpenOutcome(OpenResult.NOT_IN_REPLICA, body)
          applyItemEvent(sc, "item_updated", body) -> OpenOutcome(OpenResult.UPDATED, body)
          else -> OpenOutcome(OpenResult.UP_TO_DATE)
        }
      }
      HttpOutcome.HttpError(404) -> {
        val row = db.items().get(sc, itemId)
        if (row != null) {
          applyItemEvent(sc, "item_removed", mapper.writeValueAsString(mapper.createObjectNode().put("id", itemId).put("libraryId", row.libraryId)))
        }
        OpenOutcome(OpenResult.REMOVED)
      }
      else -> OpenOutcome(OpenResult.FAILED)
    }
  }
}
