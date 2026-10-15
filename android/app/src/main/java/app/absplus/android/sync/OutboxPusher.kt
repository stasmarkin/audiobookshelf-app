package app.absplus.android.sync

import androidx.room.withTransaction
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.db.userdata.OutboxEntity
import app.absplus.android.db.userdata.UserDataDatabase
import app.absplus.android.plugins.AbsLogger
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLEncoder

data class PushResult(val sent: Int, val dropped: Int, val remaining: Int, val stoppedBy: StopReason?)

enum class StopReason { UNREACHABLE, UNAUTHORIZED, BACKOFF }

/** Delay before the next attempt after the n-th server error; the last one repeats. */
val OUTBOX_BACKOFF_MS = listOf(1_000L, 5_000L, 30_000L, 300_000L)

/**
 * Sends the outbox of one server strictly in order, one request at a time. A row is deleted only after the server
 * accepted it (2xx) or rejected it for good (other 4xx, logged); a server error backs the queue off, and no answer or a
 * rejected session stops it with every row kept.
 */
class OutboxPusher(
        private val db: UserDataDatabase,
        private val http: SyncHttp,
        private val store: UserDataStore,
        private val clock: () -> Long,
        private val log: (String) -> Unit = { AbsLogger.error("OutboxPusher", it) }
) {
  private val outbox = db.outbox()
  private val progressDao = db.mediaProgress()
  private val mapper = jacksonObjectMapper()

  /** The row currently in an HTTP request. */
  @Volatile var inFlightId: Long? = null
    private set

  private sealed interface Step {
    /**
     * [sentProgress]: the progress row a PATCH body was built from, to detect a newer write during the request.
     * [serverAhead]: the server's record that kept the conditional fields back; the row takes them once settled.
     */
    data class Sent(val sentProgress: MediaProgressEntity? = null, val serverAhead: ServerAhead? = null) : Step
    data class Drop(val reason: String) : Step
    data class Retry(val reason: String) : Step
    data class Stop(val reason: StopReason, val message: String) : Step
  }

  suspend fun pushAll(config: ServerConnectionConfig): PushResult {
    var sent = 0
    var dropped = 0
    var stoppedBy: StopReason? = null
    try {
      while (true) {
        // Claimed in a transaction, so a write coalescing the outbox sees the row as in flight or not at all
        val row = db.withTransaction {
          val due = outbox.nextDue(config.id, clock()) ?: return@withTransaction null
          // Counted before the request: an add whose answer is lost must not be cancelled out by a later removal
          due.copy(attempts = due.attempts + 1).also { outbox.update(it); inFlightId = it.id }
        }
        if (row == null) {
          if (outbox.head(config.id) != null) stoppedBy = StopReason.BACKOFF
          break
        }
        when (val step = send(config, row)) {
          is Step.Sent -> {
            store.onOutboxSent(row, followUp(row, step.sentProgress), step.serverAhead)
            sent++
          }
          is Step.Drop -> {
            log("Dropped ${row.kind} ${row.itemKey}: ${step.reason}")
            store.onOutboxDropped(row)
            dropped++
          }
          is Step.Retry -> {
            val delay = OUTBOX_BACKOFF_MS[minOf(row.attempts - 1, OUTBOX_BACKOFF_MS.lastIndex)]
            outbox.update(row.copy(lastError = step.reason, nextAttemptAt = clock() + delay))
            stoppedBy = StopReason.BACKOFF
            break
          }
          is Step.Stop -> {
            outbox.update(row.copy(lastError = step.message))
            stoppedBy = step.reason
            break
          }
        }
        inFlightId = null
      }
    } finally {
      inFlightId = null
    }
    return PushResult(sent, dropped, outbox.count(config.id), stoppedBy)
  }

  private suspend fun send(config: ServerConnectionConfig, row: OutboxEntity): Step {
    // A saved server can be signed in again as another user under the same id; their token must not send this row
    val owner = OutboxPayload.userId(row.payload)
    if (owner != null && owner != config.userId) return Step.Drop("queued for another user of this server")
    return sendAs(config, row)
  }

  private suspend fun sendAs(config: ServerConnectionConfig, row: OutboxEntity): Step = when (row.kind) {
    OutboxKind.PROGRESS_PATCH -> sendProgressPatch(config, row)
    OutboxKind.PROGRESS_DELETE -> {
      val id = OutboxPayload.serverProgressId(row.payload)
      if (id == null) Step.Drop("no serverProgressId")
      // Already gone on the server is what a reset wants
      else classify(http.send(config, "DELETE", "/api/me/progress/${segment(id)}"), notFoundIsSuccess = true)
    }
    OutboxKind.BOOKMARK_ADD, OutboxKind.BOOKMARK_UPDATE -> {
      val time = OutboxPayload.bookmarkTime(row.payload)
      val title = OutboxPayload.bookmarkTitle(row.payload)
      if (time == null || title == null) Step.Drop("unreadable payload")
      else {
        val body = JsonNodeFactory.instance.objectNode().put("time", time).put("title", title)
        val method = if (row.kind == OutboxKind.BOOKMARK_ADD) "POST" else "PATCH"
        classify(http.send(config, method, bookmarkPath(row), mapper.writeValueAsString(body)))
      }
    }
    OutboxKind.BOOKMARK_REMOVE -> {
      val time = OutboxPayload.bookmarkTime(row.payload)
      if (time == null) Step.Drop("unreadable payload")
      else classify(http.send(config, "DELETE", "${bookmarkPath(row)}/${canonicalTime(time)}"))
    }
    else -> Step.Drop("unknown kind")
  }

  /** The body is built from the progress row now, with only the fields the action changed and its lastUpdate. */
  private suspend fun sendProgressPatch(config: ServerConnectionConfig, row: OutboxEntity): Step {
    val fields = OutboxPayload.progressPatchFields(row.payload)
    if (fields.isNullOrEmpty()) return Step.Drop("no fields")
    val (libraryItemId, episodeId) = progressKeyParts(row.itemKey)
    val progress = progressDao.get(row.serverConfigId, libraryItemId, episodeId)
            ?: return Step.Drop("no progress row")
    if (!progress.pending) return Step.Drop("progress row is not pending")
    val path = "/api/me/progress/${segment(libraryItemId)}" + if (episodeId.isEmpty()) "" else "/${segment(episodeId)}"
    var sendFields = fields
    var serverAhead: ServerAhead? = null
    val conditional = OutboxPayload.conditionalFields(row.payload).filter { it in fields }
    // The user's own finished mark (also a reset) decides the state: the session's older position is not sent with it,
    // and the row, which the mark set, is not compared with the server's
    if (conditional.isNotEmpty() && "isFinished" in fields) sendFields = fields - conditional.toSet()
    else if (conditional.isNotEmpty()) {
      when (val outcome = http.send(config, "GET", path)) {
        is HttpOutcome.Ok -> parseServerProgress(readTree(outcome.body))?.let { server ->
          if (server.isFinished || server.currentTime >= progress.currentTime) {
            sendFields = fields - conditional.toSet()
            serverAhead = ServerAhead(server, progress.lastUpdate, conditional, sendFields)
          }
        } // An unreadable record is no reason to hold the queue: sent as before the check
        is HttpOutcome.HttpError -> when (outcome.code) {
          408, 429, in 500..599 -> return Step.Retry("HTTP ${outcome.code}")
          else -> Unit // No record (404) or a check the server refuses: sent as before the check
        }
        else -> return classify(outcome)
      }
    }
    if (sendFields.isEmpty()) return Step.Sent(serverAhead = serverAhead)
    val step = classify(http.send(config, "PATCH", path, mapper.writeValueAsString(patchBody(progress, sendFields))))
    return if (step is Step.Sent) Step.Sent(progress, serverAhead) else step
  }

  /**
   * A write during the PATCH (a tick or a session) left the row newer than what was sent; one more PATCH keeps the
   * server from staying on the older values.
   */
  private suspend fun followUp(row: OutboxEntity, sent: MediaProgressEntity?): OutboxEntity? {
    if (row.kind == OutboxKind.PROGRESS_DELETE) return followUpOfReset(row)
    if (sent == null) return null
    val now = progressDao.get(sent.serverConfigId, sent.libraryItemId, sent.episodeId) ?: return null
    if (now.lastUpdate <= sent.lastUpdate) return null
    return OutboxEntity(serverConfigId = row.serverConfigId, kind = OutboxKind.PROGRESS_PATCH, itemKey = row.itemKey,
            payload = row.payload, createdAt = clock())
  }

  /**
   * Listening after the reset that is not queued behind the DELETE yet (it started while the DELETE was on the wire)
   * would be lost with the server record: a PATCH of it follows.
   */
  private suspend fun followUpOfReset(row: OutboxEntity): OutboxEntity? {
    val resetAt = OutboxPayload.deleteLastUpdate(row.payload) ?: return null
    val (libraryItemId, episodeId) = progressKeyParts(row.itemKey)
    val now = progressDao.get(row.serverConfigId, libraryItemId, episodeId) ?: return null
    if (now.lastUpdate <= resetAt) return null
    if (outbox.byItemKey(row.serverConfigId, row.itemKey).any { it.kind == OutboxKind.PROGRESS_PATCH && it.id > row.id }) return null
    return OutboxEntity(serverConfigId = row.serverConfigId, kind = OutboxKind.PROGRESS_PATCH, itemKey = row.itemKey,
            payload = OutboxPayload.progressPatch(PLAYBACK_FIELDS), createdAt = clock())
  }

  private fun readTree(body: String) = try {
    mapper.readTree(body)
  } catch (e: Exception) {
    null
  }

  private fun patchBody(progress: MediaProgressEntity, fields: List<String>): ObjectNode {
    val body = JsonNodeFactory.instance.objectNode()
    for (field in fields) {
      when (field) {
        "duration" -> body.put(field, progress.duration)
        "progress" -> body.put(field, progress.progress)
        "currentTime" -> body.put(field, progress.currentTime)
        "isFinished" -> body.put(field, progress.isFinished)
        "hideFromContinueListening" -> body.put(field, progress.hideFromContinueListening)
        "ebookLocation" -> body.put(field, progress.ebookLocation)
        "ebookProgress" -> body.put(field, progress.ebookProgress)
        "finishedAt" -> body.put(field, progress.finishedAt)
        "startedAt" -> body.put(field, progress.startedAt)
      }
    }
    return body.put("lastUpdate", progress.lastUpdate)
  }

  private fun classify(outcome: HttpOutcome, notFoundIsSuccess: Boolean = false): Step = when (outcome) {
    is HttpOutcome.Ok -> Step.Sent()
    is HttpOutcome.Unauthorized -> Step.Stop(StopReason.UNAUTHORIZED, "unauthorized")
    is HttpOutcome.Unreachable -> Step.Stop(StopReason.UNREACHABLE, outcome.message)
    is HttpOutcome.HttpError -> when {
      outcome.code == 404 && notFoundIsSuccess -> Step.Sent()
      // Timeouts and rate limits are worth another try; any other client error will fail the same way again
      outcome.code == 408 || outcome.code == 429 -> Step.Retry("HTTP ${outcome.code}")
      outcome.code in 400..499 -> Step.Drop("HTTP ${outcome.code}")
      else -> Step.Retry("HTTP ${outcome.code}")
    }
  }

  private fun bookmarkPath(row: OutboxEntity) = "/api/me/item/${segment(bookmarkKeyLibraryItemId(row.itemKey))}/bookmark"

  private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
