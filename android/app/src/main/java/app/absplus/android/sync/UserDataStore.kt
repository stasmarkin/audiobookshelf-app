package app.absplus.android.sync

import androidx.room.withTransaction
import app.absplus.android.db.userdata.BookmarkEntity
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.db.userdata.OutboxDao
import app.absplus.android.db.userdata.OutboxEntity
import app.absplus.android.db.userdata.UserDataDatabase

/** A server record merged on request, and the row it leaves. */
data class CandidateResult(val applied: Boolean, val row: MediaProgressEntity?, val protected: Boolean)

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
        private val outbox: OutboxDao = db.outbox(),
        private val recentlySettledMs: Long = RECENTLY_SETTLED_MS,
        /** [progressItemKey]s of the items with listening sessions of the server still waiting for `local-all`. */
        private val unsentSessionKeys: (serverConfigId: String) -> Set<String> = { emptySet() },
        private val mirror: ProgressMirror = ProgressMirror.None
) {
  private val bookmarks = db.bookmarks()
  private val progress = db.mediaProgress()
  /** Bookmark keys whose outbox rows were settled lately, with the time; see [applyServerBookmarks]. */
  private val recentlySettled = HashMap<String, Long>()
  /** Progress keys whose reset reached the server lately: the reset's time and when it was answered. */
  private val recentResets = HashMap<String, Pair<Long, Long>>()

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
   *
   * @return whether the snapshot would have changed a bookmark it left alone (a fresh pull should follow)
   */
  suspend fun applyServerBookmarks(serverConfigId: String, list: List<ServerBookmark>, fromSnapshot: Boolean = false): Boolean {
    val recent = if (fromSnapshot) recentlySettledKeys(serverConfigId) else emptySet()
    val (plan, suppressed) = db.withTransaction {
      val all = bookmarks.getAll(serverConfigId)
      val isRecent = { libraryItemId: String, time: Double -> time.isFinite() && bookmarkItemKey(libraryItemId, time) in recent }
      val full = if (recent.isEmpty()) null else mergeBookmarks(serverConfigId, all, list)
      val suppressed = full != null && (full.upserts + full.deletes).any { isRecent(it.libraryItemId, it.time) }
      val plan = mergeBookmarks(serverConfigId, all.filterNot { isRecent(it.libraryItemId, it.time) },
              list.filterNot { isRecent(it.libraryItemId, it.time) })
      plan.upserts.forEach { bookmarks.upsert(it) }
      plan.deletes.forEach { bookmarks.delete(it.serverConfigId, it.libraryItemId, it.time) }
      plan to suppressed
    }
    val changed = (plan.upserts + plan.deletes).map { it.libraryItemId }.distinct()
    if (changed.isNotEmpty()) notifier.notify(SCOPE_BOOKMARKS, changed)
    return suppressed
  }

  suspend fun progressRows(serverConfigId: String): List<MediaProgressEntity> = progress.getAll(serverConfigId)

  /** Rows of just these items; ids beyond SQLite's parameter limit are read in chunks. */
  suspend fun progressRowsFor(serverConfigId: String, libraryItemIds: Collection<String>): List<MediaProgressEntity> =
          libraryItemIds.distinct().chunked(500).flatMap { progress.getForItems(serverConfigId, it) }

  /** The server's user object as last known (without progress, bookmarks and tokens); null when none is. */
  suspend fun userJson(serverConfigId: String): String? = db.syncState().get(serverConfigId)?.userJson

  /** Marks a book or episode finished or not, with the values the Paper copy gets for the same action. */
  suspend fun markFinished(serverConfigId: String, libraryItemId: String, episodeId: String?, isFinished: Boolean) {
    val row = db.withTransaction {
      val now = clock()
      val row = progress.get(serverConfigId, libraryItemId, episodeId.orEmpty()) ?: newRow(serverConfigId, libraryItemId, episodeId, now)
      val updated = row.copy(
              progress = if (row.isFinished != isFinished) (if (isFinished) 1.0 else 0.0) else row.progress,
              currentTime = if (isFinished) 0.0 else row.currentTime,
              isFinished = isFinished, finishedAt = if (isFinished) now else null, lastUpdate = now,
              source = PROGRESS_SOURCE_DEVICE, pending = true)
      progress.upsert(updated)
      // Only the flag: the server resets the position itself when a finished item is unmarked
      enqueue(serverConfigId, OutboxKind.PROGRESS_PATCH, progressItemKey(libraryItemId, episodeId),
              OutboxPayload.progressPatch(listOf("isFinished"), finishedMark = true))
      updated
    }
    mirror.mirror(listOf(row), PaperProgressMirror.MARK_FINISHED_FIELDS)
    notifier.notify(SCOPE_PROGRESS, listOf(libraryItemId))
  }

  suspend fun updateEbookProgress(serverConfigId: String, libraryItemId: String, ebookLocation: String?, ebookProgress: Double) {
    val row = db.withTransaction {
      val now = clock()
      val row = progress.get(serverConfigId, libraryItemId, "") ?: newRow(serverConfigId, libraryItemId, null, now)
      val updated = row.copy(ebookLocation = ebookLocation, ebookProgress = ebookProgress, lastUpdate = now,
              source = PROGRESS_SOURCE_DEVICE, pending = true)
      progress.upsert(updated)
      enqueue(serverConfigId, OutboxKind.PROGRESS_PATCH, progressItemKey(libraryItemId, null), OutboxPayload.progressPatch(PaperProgressMirror.EBOOK_FIELDS))
      updated
    }
    mirror.mirror(listOf(row), PaperProgressMirror.EBOOK_FIELDS)
    notifier.notify(SCOPE_PROGRESS, listOf(libraryItemId))
  }

  /**
   * Resets the progress of an item. The server record is deleted by its id, which the row keeps until the DELETE is
   * answered; a row that never learned its id is zeroed on the server with a PATCH instead. Without a row: nothing.
   */
  suspend fun resetProgress(serverConfigId: String, libraryItemId: String, episodeId: String?) {
    val reset = db.withTransaction {
      val row = progress.get(serverConfigId, libraryItemId, episodeId.orEmpty()) ?: return@withTransaction null
      val now = clock()
      progress.upsert(row.copy(currentTime = 0.0, progress = 0.0, isFinished = false, finishedAt = null,
              ebookLocation = null, ebookProgress = null, hideFromContinueListening = false, lastUpdate = now,
              source = PROGRESS_SOURCE_DEVICE, pending = true))
      val key = progressItemKey(libraryItemId, episodeId)
      val serverProgressId = row.serverProgressId
      if (serverProgressId != null) enqueue(serverConfigId, OutboxKind.PROGRESS_DELETE, key, OutboxPayload.progressDelete(serverProgressId, now))
      else enqueue(serverConfigId, OutboxKind.PROGRESS_PATCH, key, OutboxPayload.progressPatch(RESET_FIELDS, finishedMark = false))
      row
    } ?: return
    mirror.remove(listOf(reset))
    notifier.notify(SCOPE_PROGRESS, listOf(libraryItemId))
  }

  /**
   * A playback tick: the position the player reached at [lastUpdate] (the session's time). Listening reaches the
   * server through sessions, so nothing is queued and `pending` stays as it is, except after a reset that is still
   * queued: the DELETE would leave the server without this listening, so a PATCH of it is queued behind the DELETE.
   * [mirrorToPaper]: false when the Paper copy is written by the player itself.
   */
  suspend fun recordPlayback(serverConfigId: String, libraryItemId: String, episodeId: String?, currentTime: Double,
                             duration: Double, progressValue: Double, lastUpdate: Long, mirrorToPaper: Boolean = true) {
    val row = db.withTransaction {
      val key = progressItemKey(libraryItemId, episodeId)
      val queued = outbox.byItemKey(serverConfigId, key)
      val existing = progress.get(serverConfigId, libraryItemId, episodeId.orEmpty())
      // Sampled before the user's own unsent change (a reset, "finished"): it must not undo it
      if (existing != null && existing.pending && lastUpdate <= existing.lastUpdate) return@withTransaction null
      val row = existing ?: newRow(serverConfigId, libraryItemId, episodeId, lastUpdate).copy(pending = queued.isNotEmpty())
      // An unsent "finished" mark stays as the user set it, even while playing on: the PATCH sends the row's value.
      // Only the user's mark: a reset, an import or listening after a reset send isFinished as playback leaves it
      val keepFinished = queued.any { it.kind == OutboxKind.PROGRESS_PATCH && OutboxPayload.finishedMark(it.payload) == true }
      val finished = if (keepFinished) row.isFinished else progressValue >= FINISHED_PROGRESS
      var updated = row.copy(currentTime = currentTime, duration = duration, progress = progressValue,
              isFinished = finished, finishedAt = if (keepFinished) row.finishedAt else if (finished) lastUpdate else null,
              lastUpdate = lastUpdate, source = PROGRESS_SOURCE_DEVICE)
      val lastDelete = queued.lastOrNull { it.kind == OutboxKind.PROGRESS_DELETE }
      if (lastDelete != null && queued.none { it.kind == OutboxKind.PROGRESS_PATCH && it.id > lastDelete.id }) {
        enqueue(serverConfigId, OutboxKind.PROGRESS_PATCH, key, OutboxPayload.progressPatch(PLAYBACK_FIELDS))
        updated = updated.copy(pending = true)
      }
      progress.upsert(updated)
      updated
    } ?: return
    if (mirrorToPaper) mirror.mirror(listOf(row), PLAYBACK_MIRROR_FIELDS)
    // Its own scope: the WebView re-reads just this row for a tick, not the Paper progress of every copy
    notifier.notify(SCOPE_PLAYBACK, listOf(libraryItemId))
  }

  /**
   * Merges the server's progress records by [mergeProgress]. A row whose item has unsent listening sessions counts as
   * pending. [deleteMissing] (a full pull only, never a snapshot that may predate a just-sent PATCH): a row the server
   * has no record of is deleted, with the Paper progress of its copy, unless it is pending, has unsent sessions or is
   * in [keep] (read in the deleting transaction, so a key remembered by an import that just committed is seen).
   * Anything else ([deleteMissing] false) is a snapshot, which may predate a reset that reached the server lately: its
   * records from before that reset are ignored. A taken record with the device's own values (the server's echo of
   * this device's listening) only updates the row's time and id: no Paper mirror and no notice to the WebView.
   *
   * @return the rows taken from the server
   */
  suspend fun applyServerProgress(serverConfigId: String, list: List<ServerProgress>, deleteMissing: Boolean = false,
                                  keep: () -> Set<String> = { emptySet() }): List<MediaProgressEntity> {
    val unsent = unsentSessionKeys(serverConfigId)
    val resets = if (deleteMissing) emptyMap() else recentResetsOf(serverConfigId)
    val (taken, deleted) = db.withTransaction {
      val taken = mutableListOf<MediaProgressEntity>()
      val seen = HashSet<String>()
      for (server in list) {
        val key = progressItemKey(server.libraryItemId, server.episodeId)
        if (!seen.add(key)) continue
        val resetAt = resets[key]
        if (resetAt != null && server.lastUpdate <= resetAt) continue
        val local = progress.get(serverConfigId, server.libraryItemId, server.episodeId.orEmpty())
        val merge = mergeProgress(serverConfigId, local, server, key in unsent)
        if (merge is ProgressMerge.TakeServer) {
          // Kept even when only the time changed, so a later, older snapshot loses to it
          progress.upsert(merge.row)
          if (local == null || !sameValues(local, merge.row)) taken.add(merge.row)
        } else if (local != null && local.serverProgressId == null && server.id.isNotEmpty()) {
          // The id is what a later reset deletes
          progress.upsert(local.copy(serverProgressId = server.id))
        }
      }
      val kept = if (deleteMissing) keep() else emptySet()
      val deleted = if (!deleteMissing) emptyList() else progress.getAll(serverConfigId).filter { row ->
        val key = progressItemKey(row.libraryItemId, row.episodeId)
        key !in seen && !row.pending && key !in unsent && key !in kept
      }
      deleted.forEach { progress.delete(it.serverConfigId, it.libraryItemId, it.episodeId) }
      taken to deleted
    }
    mirror.mirror(taken)
    mirror.noteServerValues(taken)
    mirror.remove(deleted)
    val changed = (taken + deleted).map { it.libraryItemId }.distinct()
    if (changed.isNotEmpty()) notifier.notify(SCOPE_PROGRESS, changed)
    return taken
  }

  /**
   * Imported rows ([keys]) newer than the server's record are queued to be sent: they hold what was done on the device
   * before this release or on a rolled-back version. One without a server record is not sent, as before this release:
   * it may be progress reset on another client, which must not come back; listening to it still goes with its
   * sessions. Runs before the merge and the deletion of rows missing on the server, which spares these keys this once.
   * A pending row already has its own outbox rows.
   */
  suspend fun pushNewerImported(serverConfigId: String, keys: Set<String>, server: List<ServerProgress>) {
    if (keys.isEmpty()) return
    val byKey = server.associateBy { progressItemKey(it.libraryItemId, it.episodeId) }
    db.withTransaction {
      for (key in keys) {
        val (libraryItemId, episodeId) = progressKeyParts(key)
        val row = progress.get(serverConfigId, libraryItemId, episodeId) ?: continue
        if (row.pending) continue
        val remote = byKey[key] ?: continue
        if (row.lastUpdate <= remote.lastUpdate) continue
        enqueue(serverConfigId, OutboxKind.PROGRESS_PATCH, key, OutboxPayload.progressPatch(PaperProgressImport.IMPORT_FIELDS))
        setProgressPending(serverConfigId, key)
      }
    }
  }

  /**
   * A downloaded copy of the item just got its Paper progress from [snapshot], the server's progress when the download
   * was requested. The snapshot merges like any server value; when userdata.db keeps its own newer row, the copy gets
   * that instead.
   */
  suspend fun onCopyDownloaded(serverConfigId: String, libraryItemId: String, episodeId: String?, snapshot: ServerProgress?) {
    if (serverConfigId.isEmpty() || libraryItemId.isEmpty()) return
    if (snapshot != null && applyServerProgressCandidate(serverConfigId, snapshot)) return
    progress.get(serverConfigId, libraryItemId, episodeId.orEmpty())?.let { mirror.mirror(listOf(it)) }
  }

  /** One server record (socket event, the server value on refocus); whether it was taken. */
  suspend fun applyServerProgressCandidate(serverConfigId: String, server: ServerProgress): Boolean =
          applyServerProgress(serverConfigId, listOf(server)).isNotEmpty()

  /**
   * [applyServerProgressCandidate] with the row as it stands after it: [CandidateResult.protected] when the device
   * holds something the server has not got yet (an unsent change or listening session), which the player keeps.
   */
  suspend fun mergeServerProgress(serverConfigId: String, server: ServerProgress): CandidateResult {
    val applied = applyServerProgressCandidate(serverConfigId, server)
    val row = progress.get(serverConfigId, server.libraryItemId, server.episodeId.orEmpty())
    val key = progressItemKey(server.libraryItemId, server.episodeId)
    val protected = row != null && (row.pending || key in unsentSessionKeys(serverConfigId))
    return CandidateResult(applied, row, protected)
  }

  /** What a server record would change; the time and who wrote it do not count. */
  private fun sameValues(local: MediaProgressEntity, server: MediaProgressEntity) =
          local.currentTime == server.currentTime && local.isFinished == server.isFinished &&
                  local.ebookLocation == server.ebookLocation && local.ebookProgress == server.ebookProgress &&
                  local.hideFromContinueListening == server.hideFromContinueListening

  fun notifyUserChanged() = notifier.notify(SCOPE_USER, emptyList())

  private fun newRow(serverConfigId: String, libraryItemId: String, episodeId: String?, now: Long) = MediaProgressEntity(
          serverConfigId = serverConfigId, libraryItemId = libraryItemId, episodeId = episodeId.orEmpty(),
          serverProgressId = null, duration = 0.0, progress = 0.0, currentTime = 0.0, isFinished = false,
          hideFromContinueListening = false, ebookLocation = null, ebookProgress = null, lastUpdate = now,
          startedAt = now, finishedAt = null, source = PROGRESS_SOURCE_DEVICE, pending = false)

  /** [row] reached the server; [followUp] is queued in the same transaction, before `pending` is recomputed. */
  suspend fun onOutboxSent(row: OutboxEntity, followUp: OutboxEntity? = null) = settle(row, dropped = false, followUp)

  /** [row] will never reach the server (rejected or unreadable); a bookmark with nothing else to send is dropped too. */
  suspend fun onOutboxDropped(row: OutboxEntity) = settle(row, dropped = true, followUp = null)

  /**
   * Queues a `progress_patch` of [fields] for the item, folded into a queued one. [conditionalFields]: see
   * [OutboxPayload.progressPatch].
   */
  suspend fun queueProgressPatch(serverConfigId: String, libraryItemId: String, episodeId: String?, fields: Collection<String>,
                                 conditionalFields: Collection<String> = emptyList()) {
    db.withTransaction {
      enqueue(serverConfigId, OutboxKind.PROGRESS_PATCH, progressItemKey(libraryItemId, episodeId),
              OutboxPayload.progressPatch(fields, conditionalFields = conditionalFields))
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
        OutboxKind.PROGRESS_DELETE -> {
          val (libraryItemId, episodeId) = progressKeyParts(row.itemKey)
          val deletedId = OutboxPayload.serverProgressId(row.payload)
          if (!dropped) markReset(row.serverConfigId, row.itemKey, OutboxPayload.deleteLastUpdate(row.payload) ?: clock())
          val local = progress.get(row.serverConfigId, libraryItemId, episodeId)
          when {
            local == null || dropped -> setProgressPending(row.serverConfigId, row.itemKey)
            // The server has no record now and nothing else is to be sent: neither has the device
            !left -> progress.delete(row.serverConfigId, libraryItemId, episodeId)
            // A PATCH queued behind creates a new record, whose id the next pull brings
            else -> progress.upsert(local.copy(pending = true,
                    serverProgressId = if (local.serverProgressId == deletedId) null else local.serverProgressId))
          }
          SCOPE_PROGRESS to libraryItemId
        }
        OutboxKind.PROGRESS_PATCH -> {
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

  private fun markReset(serverConfigId: String, itemKey: String, resetAt: Long) = synchronized(recentResets) {
    recentResets["$serverConfigId $itemKey"] = resetAt to clock()
  }

  /** Progress keys of the server reset lately, with the time of the reset. */
  private fun recentResetsOf(serverConfigId: String): Map<String, Long> = synchronized(recentResets) {
    val now = clock()
    recentResets.entries.removeAll { now - it.value.second >= recentlySettledMs }
    recentResets.filterKeys { it.startsWith("$serverConfigId ") }.entries.associate { it.key.substringAfter(' ') to it.value.first }
  }

  private fun markSettled(serverConfigId: String, itemKey: String) = synchronized(recentlySettled) {
    recentlySettled["$serverConfigId $itemKey"] = clock()
  }

  private fun recentlySettledKeys(serverConfigId: String): Set<String> = synchronized(recentlySettled) {
    val now = clock()
    recentlySettled.entries.removeAll { now - it.value >= recentlySettledMs }
    recentlySettled.keys.filter { it.startsWith("$serverConfigId ") }.map { it.substringAfter(' ') }.toSet()
  }

  private fun requireValidTime(time: Double) = require(time.isFinite() && time >= 0) { "Invalid bookmark time: $time" }

  // The server rejects an empty title, which would drop the bookmark when it is sent
  private fun requireValidTitle(title: String) = require(title.isNotBlank()) { "Bookmark title is empty" }

  companion object {
    const val SCOPE_BOOKMARKS = "bookmarks"
    const val SCOPE_PROGRESS = "progress"
    const val SCOPE_USER = "user"
    const val SCOPE_PLAYBACK = "playback"
    /** The progress at which playback counts as finished, as for the Paper copy. */
    const val FINISHED_PROGRESS = 0.99
    /** A reset without the server record's id is a PATCH of these. */
    val RESET_FIELDS = listOf("currentTime", "progress", "isFinished", "ebookLocation", "ebookProgress")
    private val PLAYBACK_MIRROR_FIELDS = listOf("duration", "progress", "currentTime", "isFinished", "finishedAt")
    /** How long a server snapshot is assumed to be possibly older than a settled outbox row. */
    const val RECENTLY_SETTLED_MS = 120_000L
  }
}
