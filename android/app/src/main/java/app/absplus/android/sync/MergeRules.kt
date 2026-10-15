package app.absplus.android.sync

import app.absplus.android.db.userdata.BookmarkEntity
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.db.userdata.OutboxEntity

data class ServerBookmark(val libraryItemId: String, val time: Double, val title: String, val createdAt: Long)

data class BookmarkMergePlan(val upserts: List<BookmarkEntity>, val deletes: List<BookmarkEntity>)

/**
 * Merges the server's bookmarks into the local rows of one server. A pending row (an unsent add, rename or removal)
 * wins over the server and is not touched, so a bookmark removed offline stays hidden until the removal is sent.
 * Every other row follows the server: added, retitled, or deleted when the server no longer has it.
 */
fun mergeBookmarks(serverConfigId: String, local: List<BookmarkEntity>, server: List<ServerBookmark>): BookmarkMergePlan {
  val localByKey = local.filter { it.serverConfigId == serverConfigId }.associateBy { bookmarkItemKey(it.libraryItemId, it.time) }
  val serverByKey = LinkedHashMap<String, ServerBookmark>()
  for (bookmark in server) {
    if (bookmark.time.isFinite()) serverByKey.putIfAbsent(bookmarkItemKey(bookmark.libraryItemId, bookmark.time), bookmark)
  }

  val upserts = mutableListOf<BookmarkEntity>()
  for ((key, remote) in serverByKey) {
    val row = localByKey[key]
    if (row?.pending == true) continue
    val fromServer = BookmarkEntity(serverConfigId, remote.libraryItemId, row?.time ?: remote.time, remote.title,
            remote.createdAt, deletedAt = null, pending = false)
    if (row != fromServer) upserts.add(fromServer)
  }
  val deletes = localByKey.filter { (key, row) -> !row.pending && key !in serverByKey }.values.toList()
  return BookmarkMergePlan(upserts, deletes)
}

/** A media progress record as the server sends it; [episodeId] is null for a book. */
data class ServerProgress(
        val id: String,
        val libraryItemId: String,
        val episodeId: String?,
        val duration: Double,
        val progress: Double,
        val currentTime: Double,
        val isFinished: Boolean,
        val hideFromContinueListening: Boolean,
        val ebookLocation: String?,
        val ebookProgress: Double?,
        val lastUpdate: Long,
        val startedAt: Long,
        val finishedAt: Long?
)

sealed interface ProgressMerge {
  data object KeepLocal : ProgressMerge
  data class TakeServer(val row: MediaProgressEntity) : ProgressMerge
}

/**
 * The one merge rule of progress: the larger `lastUpdate` wins, a tie keeps the local row, and a local row with
 * something unsent is never overwritten. [hasUnsentSessions]: the item has listening sessions waiting for `local-all`,
 * which count as unsent, so offline listening to a downloaded book is not rolled back by the server.
 */
fun mergeProgress(serverConfigId: String, local: MediaProgressEntity?, server: ServerProgress,
                  hasUnsentSessions: Boolean = false): ProgressMerge {
  if (local != null && (local.pending || hasUnsentSessions || local.lastUpdate >= server.lastUpdate)) return ProgressMerge.KeepLocal
  return ProgressMerge.TakeServer(MediaProgressEntity(
          serverConfigId = serverConfigId, libraryItemId = server.libraryItemId, episodeId = server.episodeId.orEmpty(),
          serverProgressId = server.id, duration = server.duration, progress = server.progress,
          currentTime = server.currentTime, isFinished = server.isFinished,
          hideFromContinueListening = server.hideFromContinueListening, ebookLocation = server.ebookLocation,
          ebookProgress = server.ebookProgress, lastUpdate = server.lastUpdate, startedAt = server.startedAt,
          finishedAt = server.finishedAt, source = PROGRESS_SOURCE_SERVER, pending = false))
}

const val PROGRESS_SOURCE_SERVER = "server"
const val PROGRESS_SOURCE_DEVICE = "device"

/** What to do with the outbox of one item when a new action [OutboxPlan] is about it. */
data class OutboxPlan(val deleteIds: List<Long>, val insert: OutboxEntity?, val update: OutboxEntity?)

/**
 * Folds a new action into the queued rows of the same server and item.
 *
 * The row in flight ([inFlightId], already in an HTTP request) is never folded or cancelled: the new action queues
 * after it. Only an add that was never attempted (`attempts == 0`) can absorb a rename or cancel out with a removal;
 * once attempted, the server may have applied it even if the answer was lost, so the removal is sent separately.
 * A `progress_delete` drops the queued patches of the item; a patch never folds across a queued delete.
 */
fun coalesce(existing: List<OutboxEntity>, incoming: OutboxEntity, inFlightId: Long?): OutboxPlan {
  val queued = existing.filter { it.matches(incoming) && it.id != inFlightId }
  val unsentAdds = queued.filter { it.kind == OutboxKind.BOOKMARK_ADD && it.attempts == 0 }
  val unsentAdd = unsentAdds.lastOrNull()
  val updates = queued.filter { it.kind == OutboxKind.BOOKMARK_UPDATE }

  return when (incoming.kind) {
    OutboxKind.PROGRESS_PATCH -> {
      // The body is read from the row at send time, so one row with every changed field replaces the queued ones.
      // Never across a reset: a patch queued before the DELETE has to stay before it
      val lastDelete = existing.filter { it.matches(incoming) && it.kind == OutboxKind.PROGRESS_DELETE }.maxOfOrNull { it.id }
      val patches = queued.filter { it.kind == OutboxKind.PROGRESS_PATCH && (lastDelete == null || it.id > lastDelete) }
      val fields = (patches + incoming).flatMap { OutboxPayload.progressPatchFields(it.payload).orEmpty() }
      OutboxPlan(patches.map { it.id }, incoming.copy(payload = OutboxPayload.progressPatch(fields)), null)
    }
    // The reset makes queued changes of the same progress pointless
    OutboxKind.PROGRESS_DELETE -> OutboxPlan(queued.filter { it.kind == OutboxKind.PROGRESS_PATCH }.map { it.id }, incoming, null)
    OutboxKind.BOOKMARK_UPDATE ->
      if (unsentAdd != null) OutboxPlan(updates.map { it.id }, null, unsentAdd.copy(payload = incoming.payload))
      else OutboxPlan(updates.map { it.id }, incoming, null)
    OutboxKind.BOOKMARK_REMOVE ->
      when {
        unsentAdd != null -> OutboxPlan((unsentAdds + updates).map { it.id }, null, null)
        queued.any { it.kind == OutboxKind.BOOKMARK_REMOVE } -> OutboxPlan(updates.map { it.id }, null, null)
        else -> OutboxPlan(updates.map { it.id }, incoming, null)
      }
    else -> OutboxPlan(emptyList(), incoming, null)
  }
}

private fun OutboxEntity.matches(other: OutboxEntity) = serverConfigId == other.serverConfigId && itemKey == other.itemKey
