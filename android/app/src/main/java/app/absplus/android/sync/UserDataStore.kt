package app.absplus.android.sync

import androidx.room.withTransaction
import app.absplus.android.db.userdata.BookmarkEntity
import app.absplus.android.db.userdata.OutboxDao
import app.absplus.android.db.userdata.OutboxEntity
import app.absplus.android.db.userdata.UserDataDatabase

/**
 * Every write of the user's data. A user action and its outbox row are written in one transaction, and a row is
 * `pending` exactly while the outbox still has rows for its item, so a server copy never overwrites an unsent change.
 */
class UserDataStore(
        private val db: UserDataDatabase,
        private val notifier: DataChangeNotifier,
        private val clock: () -> Long,
        /** The outbox row [OutboxPusher] has on the wire; [coalesce] leaves it alone. */
        private val inFlightId: () -> Long?,
        /** The user a saved server is signed in as; written into each outbox row so it is never sent as someone else. */
        private val ownerOf: (serverConfigId: String) -> String? = { null },
        private val outbox: OutboxDao = db.outbox()
) {
  private val bookmarks = db.bookmarks()
  private val progress = db.mediaProgress()
  /** Bookmark keys whose outbox rows were settled lately, with the time; see [applyServerBookmarks]. */
  private val recentlySettled = HashMap<String, Long>()

  suspend fun visibleBookmarks(serverConfigId: String): List<BookmarkEntity> = bookmarks.getVisible(serverConfigId)

  /** Adds a bookmark; one that is already visible at [time] is renamed instead (the server treats it the same way). */
  suspend fun addBookmark(serverConfigId: String, libraryItemId: String, time: Double, title: String) {
    requireValidTime(time)
    requireValidTitle(title)
    db.withTransaction {
      val existing = bookmarks.getForItem(serverConfigId, libraryItemId).firstOrNull { it.time == time }
      if (existing != null && existing.deletedAt == null) {
        bookmarks.upsert(existing.copy(title = title, pending = true))
        enqueue(serverConfigId, OutboxKind.BOOKMARK_UPDATE, bookmarkItemKey(libraryItemId, time), OutboxPayload.bookmark(time, title))
      } else {
        bookmarks.upsert(BookmarkEntity(serverConfigId, libraryItemId, time, title, clock(), deletedAt = null, pending = true))
        enqueue(serverConfigId, OutboxKind.BOOKMARK_ADD, bookmarkItemKey(libraryItemId, time), OutboxPayload.bookmark(time, title))
      }
    }
    notifier.notify(SCOPE_BOOKMARKS, listOf(libraryItemId))
  }

  /** @throws NoSuchElementException when there is no visible bookmark at [time] */
  suspend fun renameBookmark(serverConfigId: String, libraryItemId: String, time: Double, title: String) {
    requireValidTime(time)
    requireValidTitle(title)
    db.withTransaction {
      val existing = bookmarks.getForItem(serverConfigId, libraryItemId).firstOrNull { it.time == time && it.deletedAt == null }
              ?: throw NoSuchElementException("No bookmark at $time")
      bookmarks.upsert(existing.copy(title = title, pending = true))
      enqueue(serverConfigId, OutboxKind.BOOKMARK_UPDATE, bookmarkItemKey(libraryItemId, time), OutboxPayload.bookmark(time, title))
    }
    notifier.notify(SCOPE_BOOKMARKS, listOf(libraryItemId))
  }

  /**
   * Removes a bookmark. The row disappears at once only when nothing about it is left to send (an add that never
   * left the device cancels out); otherwise it stays as a pending tombstone that hides the server copy.
   */
  suspend fun removeBookmark(serverConfigId: String, libraryItemId: String, time: Double) {
    requireValidTime(time)
    val removed = db.withTransaction {
      val existing = bookmarks.getForItem(serverConfigId, libraryItemId).firstOrNull { it.time == time && it.deletedAt == null }
              ?: return@withTransaction false
      val key = bookmarkItemKey(libraryItemId, time)
      enqueue(serverConfigId, OutboxKind.BOOKMARK_REMOVE, key, OutboxPayload.bookmark(time))
      if (outbox.byItemKey(serverConfigId, key).isEmpty()) bookmarks.delete(serverConfigId, libraryItemId, time)
      else bookmarks.upsert(existing.copy(deletedAt = clock(), pending = true))
      true
    }
    if (removed) notifier.notify(SCOPE_BOOKMARKS, listOf(libraryItemId))
  }

  /**
   * Merges the server's list. A list that arrived by itself ([fromSnapshot]: socket event, authorize answer) may have
   * been built before an outbox row settled, so bookmarks settled within [RECENTLY_SETTLED_MS] keep their local state:
   * a just-sent bookmark is not deleted and a just-removed one does not come back. A fresh pull touches everything.
   */
  suspend fun applyServerBookmarks(serverConfigId: String, list: List<ServerBookmark>, fromSnapshot: Boolean = false) {
    val recent = if (fromSnapshot) recentlySettledKeys(serverConfigId) else emptySet()
    val plan = db.withTransaction {
      val local = bookmarks.getAll(serverConfigId).filter { bookmarkItemKey(it.libraryItemId, it.time) !in recent }
      val server = list.filter { !it.time.isFinite() || bookmarkItemKey(it.libraryItemId, it.time) !in recent }
      val plan = mergeBookmarks(serverConfigId, local, server)
      plan.upserts.forEach { bookmarks.upsert(it) }
      plan.deletes.forEach { bookmarks.delete(it.serverConfigId, it.libraryItemId, it.time) }
      plan
    }
    val changed = (plan.upserts + plan.deletes).map { it.libraryItemId }.distinct()
    if (changed.isNotEmpty()) notifier.notify(SCOPE_BOOKMARKS, changed)
  }

  /** [row] reached the server; [followUp] is queued in the same transaction, before `pending` is recomputed. */
  suspend fun onOutboxSent(row: OutboxEntity, followUp: OutboxEntity? = null) = settle(row, dropped = false, followUp)

  /** [row] will never reach the server (rejected or unreadable); a bookmark with nothing else to send is dropped too. */
  suspend fun onOutboxDropped(row: OutboxEntity) = settle(row, dropped = true, followUp = null)

  /** Queues a `progress_patch` of [fields] for the item, folded into a queued one. */
  suspend fun queueProgressPatch(serverConfigId: String, libraryItemId: String, episodeId: String?, fields: Collection<String>) {
    db.withTransaction {
      enqueue(serverConfigId, OutboxKind.PROGRESS_PATCH, progressItemKey(libraryItemId, episodeId), OutboxPayload.progressPatch(fields))
      setProgressPending(serverConfigId, progressItemKey(libraryItemId, episodeId))
    }
  }

  private suspend fun settle(row: OutboxEntity, dropped: Boolean, followUp: OutboxEntity?) {
    val scope = db.withTransaction {
      outbox.delete(row.id)
      if (followUp != null) enqueue(followUp.serverConfigId, followUp.kind, followUp.itemKey, followUp.payload)
      val left = outbox.byItemKey(row.serverConfigId, row.itemKey).isNotEmpty()
      when (row.kind) {
        OutboxKind.BOOKMARK_ADD, OutboxKind.BOOKMARK_UPDATE, OutboxKind.BOOKMARK_REMOVE -> {
          markSettled(row.serverConfigId, row.itemKey)
          val libraryItemId = bookmarkKeyLibraryItemId(row.itemKey)
          val bookmark = bookmarks.getForItem(row.serverConfigId, libraryItemId)
                  .firstOrNull { bookmarkItemKey(it.libraryItemId, it.time) == row.itemKey }
          when {
            bookmark == null || left -> Unit
            dropped || bookmark.deletedAt != null -> bookmarks.delete(bookmark.serverConfigId, bookmark.libraryItemId, bookmark.time)
            else -> bookmarks.upsert(bookmark.copy(pending = false))
          }
          SCOPE_BOOKMARKS to libraryItemId
        }
        OutboxKind.PROGRESS_PATCH, OutboxKind.PROGRESS_DELETE -> {
          setProgressPending(row.serverConfigId, row.itemKey)
          SCOPE_PROGRESS to progressKeyParts(row.itemKey).first
        }
        else -> null
      }
    }
    scope?.let { (name, id) -> notifier.notify(name, listOf(id)) }
  }

  private suspend fun setProgressPending(serverConfigId: String, itemKey: String) {
    val (libraryItemId, episodeId) = progressKeyParts(itemKey)
    val row = progress.get(serverConfigId, libraryItemId, episodeId) ?: return
    val pending = outbox.byItemKey(serverConfigId, itemKey).isNotEmpty()
    if (row.pending != pending) progress.upsert(row.copy(pending = pending))
  }

  /** Must run inside a transaction. */
  private suspend fun enqueue(serverConfigId: String, kind: String, itemKey: String, payload: String) {
    val incoming = OutboxEntity(serverConfigId = serverConfigId, kind = kind, itemKey = itemKey, payload = payload,
            createdAt = clock())
    val plan = coalesce(outbox.byItemKey(serverConfigId, itemKey), incoming, inFlightId())
    // After coalescing: a folded payload is rebuilt from the action's own fields
    val owner = ownerOf(serverConfigId)
    fun owned(row: OutboxEntity) = if (owner == null) row else row.copy(payload = OutboxPayload.withUserId(row.payload, owner))
    plan.deleteIds.forEach { outbox.delete(it) }
    plan.update?.let { outbox.update(owned(it)) }
    plan.insert?.let { outbox.insert(owned(it)) }
  }

  private fun markSettled(serverConfigId: String, itemKey: String) = synchronized(recentlySettled) {
    recentlySettled["$serverConfigId $itemKey"] = clock()
  }

  private fun recentlySettledKeys(serverConfigId: String): Set<String> = synchronized(recentlySettled) {
    val now = clock()
    recentlySettled.entries.removeAll { now - it.value >= RECENTLY_SETTLED_MS }
    recentlySettled.keys.filter { it.startsWith("$serverConfigId ") }.map { it.substringAfter(' ') }.toSet()
  }

  private fun requireValidTime(time: Double) = require(time.isFinite() && time >= 0) { "Invalid bookmark time: $time" }

  // The server rejects an empty title, which would drop the bookmark when it is sent
  private fun requireValidTitle(title: String) = require(title.isNotBlank()) { "Bookmark title is empty" }

  companion object {
    const val SCOPE_BOOKMARKS = "bookmarks"
    const val SCOPE_PROGRESS = "progress"
    /** How long a server snapshot is assumed to be possibly older than a settled outbox row. */
    const val RECENTLY_SETTLED_MS = 120_000L
  }
}
