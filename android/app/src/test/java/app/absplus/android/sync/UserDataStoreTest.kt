package app.absplus.android.sync

import app.absplus.android.db.userdata.BookmarkEntity
import app.absplus.android.db.userdata.OutboxDao
import app.absplus.android.db.userdata.OutboxEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [UserDataStore] on in-memory Room under Robolectric. Synthetic ids only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDataStoreTest {
  private val db = inMemoryUserData()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var inFlight: Long? = null
  private val store = store()

  private fun store(outbox: OutboxDao = db.outbox()) =
          UserDataStore(db, DataChangeNotifier(10, scope) { _, _ -> }, clock = { 1_000L }, inFlightId = { inFlight }, outbox = outbox)

  @After
  fun tearDown() {
    scope.cancel()
    db.close()
  }

  private suspend fun row(time: Double = 754.0) = db.bookmarks().getForItem("srv", "li1").firstOrNull { it.time == time }
  private suspend fun queue() = db.outbox().byItemKey("srv", bookmarkItemKey("li1", 754.0))
  private suspend fun kinds() = queue().map { it.kind }

  @Test
  fun addBookmarkWritesRowAndOutboxInOneTransaction() = runBlocking {
    store.addBookmark("srv", "li1", 754.0, "Mark")
    assertEquals(BookmarkEntity("srv", "li1", 754.0, "Mark", 1_000L, null, pending = true), row())
    assertEquals(listOf(OutboxKind.BOOKMARK_ADD), kinds())
    assertEquals(OutboxPayload.bookmark(754.0, "Mark"), queue().single().payload)

    val broken = object : OutboxDao by db.outbox() {
      override suspend fun insert(entity: OutboxEntity): Long = throw IllegalStateException("disk full")
    }
    try {
      store(broken).addBookmark("srv", "li1", 800.0, "Lost")
      fail("the outbox failure must reach the caller")
    } catch (e: IllegalStateException) {
      assertEquals("disk full", e.message)
    }
    assertNull("the bookmark row is rolled back with its outbox row", row(800.0))
  }

  @Test
  fun removeUnsentBookmarkDeletesRowAndOutbox() = runBlocking {
    store.addBookmark("srv", "li1", 754.0, "Mark")
    store.removeBookmark("srv", "li1", 754.0)

    assertNull(row())
    assertTrue(queue().isEmpty())
  }

  @Test
  fun removeSentBookmarkLeavesTombstone() = runBlocking {
    db.bookmarks().upsert(BookmarkEntity("srv", "li1", 754.0, "Synced", 1L, null, pending = false))

    store.removeBookmark("srv", "li1", 754.0)

    val tombstone = row()!!
    assertNotNull(tombstone.deletedAt)
    assertTrue(tombstone.pending)
    assertEquals(listOf(OutboxKind.BOOKMARK_REMOVE), kinds())
    assertTrue(db.bookmarks().getVisible("srv").isEmpty())
  }

  @Test
  fun removeOfAttemptedAddLeavesTombstone() = runBlocking {
    store.addBookmark("srv", "li1", 754.0, "Mark")
    db.outbox().update(queue().single().copy(attempts = 1))

    store.removeBookmark("srv", "li1", 754.0)

    assertNotNull(row()!!.deletedAt)
    assertTrue(row()!!.pending)
    assertEquals(listOf(OutboxKind.BOOKMARK_ADD, OutboxKind.BOOKMARK_REMOVE), kinds())
    store.applyServerBookmarks("srv", listOf(ServerBookmark("li1", 754.0, "Mark", 5L)))
    assertTrue("the server copy of the lost add stays hidden", db.bookmarks().getVisible("srv").isEmpty())
  }

  @Test
  fun removeAddRemoveKeepsTombstoneWhileRemovalIsQueued() = runBlocking {
    db.bookmarks().upsert(BookmarkEntity("srv", "li1", 754.0, "Synced", 1L, null, pending = false))
    store.removeBookmark("srv", "li1", 754.0)
    store.addBookmark("srv", "li1", 754.0, "Again")
    assertNull(row()!!.deletedAt)
    assertEquals(listOf(OutboxKind.BOOKMARK_REMOVE, OutboxKind.BOOKMARK_ADD), kinds())

    store.removeBookmark("srv", "li1", 754.0)

    assertEquals(listOf(OutboxKind.BOOKMARK_REMOVE), kinds())
    assertNotNull("the server still has the bookmark until the removal is sent", row()!!.deletedAt)
    assertTrue(row()!!.pending)
  }

  @Test
  fun onOutboxSentClearsPendingAndPurgesTombstone() = runBlocking {
    store.addBookmark("srv", "li1", 754.0, "Mark")
    store.onOutboxSent(queue().single())
    assertFalse(row()!!.pending)
    assertTrue(queue().isEmpty())

    store.removeBookmark("srv", "li1", 754.0)
    assertNotNull(row()!!.deletedAt)
    store.onOutboxSent(queue().single())
    assertNull(row())
  }

  @Test
  fun pendingStaysWhileNewerRowsRemain() = runBlocking {
    store.addBookmark("srv", "li1", 754.0, "Mark")
    val a = queue().single()
    inFlight = a.id
    store.renameBookmark("srv", "li1", 754.0, "Renamed")
    val b = queue().last()
    assertEquals(OutboxKind.BOOKMARK_UPDATE, b.kind)

    store.onOutboxSent(a)
    assertTrue(row()!!.pending)
    inFlight = b.id
    store.onOutboxSent(b)
    assertFalse(row()!!.pending)
    assertEquals("Renamed", row()!!.title)
  }

  @Test
  fun droppedAddDeletesTheBookmark() = runBlocking {
    store.addBookmark("srv", "li1", 754.0, "Mark")

    store.onOutboxDropped(queue().single())

    assertNull(row())
    assertTrue(queue().isEmpty())
  }

  @Test
  fun addAtAVisibleBookmarkRenamesIt() = runBlocking {
    db.bookmarks().upsert(BookmarkEntity("srv", "li1", 754.0, "Synced", 1L, null, pending = false))

    store.addBookmark("srv", "li1", 754.0, "New title")

    assertEquals("New title", row()!!.title)
    assertEquals(1L, row()!!.createdAt)
    assertEquals(listOf(OutboxKind.BOOKMARK_UPDATE), kinds())
  }

  @Test
  fun invalidTimeIsRejectedBeforeAnyWrite() = runBlocking {
    for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
      try {
        store.addBookmark("srv", "li1", time, "Bad")
        fail("time $time must be rejected")
      } catch (e: IllegalArgumentException) {
        // expected
      }
    }
    assertTrue(db.bookmarks().getAll("srv").isEmpty())
    assertEquals(0, db.outbox().count("srv"))
  }

  @Test
  fun progressPendingFollowsItsOutboxRows() = runBlocking {
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 1L, pending = false))
    store.queueProgressPatch("srv", "li1", null, listOf("currentTime"))
    assertTrue(db.mediaProgress().get("srv", "li1", "")!!.pending)

    store.onOutboxSent(db.outbox().byItemKey("srv", progressItemKey("li1", null)).single())

    assertFalse(db.mediaProgress().get("srv", "li1", "")!!.pending)
  }
}
