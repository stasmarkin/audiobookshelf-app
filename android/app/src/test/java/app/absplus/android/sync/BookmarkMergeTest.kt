package app.absplus.android.sync

import app.absplus.android.db.userdata.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [mergeBookmarks]: pure rules, synthetic ids. */
class BookmarkMergeTest {
  private fun local(time: Double, title: String = "Local", pending: Boolean = false, deletedAt: Long? = null) =
          BookmarkEntity("srv", "li1", time, title, createdAt = 1L, deletedAt = deletedAt, pending = pending)

  private fun server(time: Double, title: String = "Local") = ServerBookmark("li1", time, title, createdAt = 1L)

  @Test
  fun tombstoneHidesServerCopyUntilRemoveIsSent() {
    val tombstone = local(754.0, deletedAt = 10L, pending = true)

    val plan = mergeBookmarks("srv", listOf(tombstone), listOf(server(754.0)))

    assertEquals(BookmarkMergePlan(emptyList(), emptyList()), plan)
  }

  @Test
  fun pendingAddSurvivesServerAbsence() {
    val plan = mergeBookmarks("srv", listOf(local(10.0, pending = true)), emptyList())

    assertEquals(BookmarkMergePlan(emptyList(), emptyList()), plan)
  }

  @Test
  fun syncedBookmarkMissingOnServerIsDeleted() {
    val synced = local(10.0)

    val plan = mergeBookmarks("srv", listOf(synced), emptyList())

    assertEquals(listOf(synced), plan.deletes)
    assertTrue(plan.upserts.isEmpty())
  }

  @Test
  fun serverTitleWinsUnlessPending() {
    val synced = mergeBookmarks("srv", listOf(local(10.0, title = "Old")), listOf(server(10.0, title = "New")))
    assertEquals(listOf(local(10.0, title = "New")), synced.upserts)
    assertTrue(synced.deletes.isEmpty())

    val renamedOffline = mergeBookmarks("srv", listOf(local(10.0, title = "Mine", pending = true)), listOf(server(10.0, title = "New")))
    assertEquals(BookmarkMergePlan(emptyList(), emptyList()), renamedOffline)
  }

  @Test
  fun timeKeyIsNumeric() {
    val plan = mergeBookmarks("srv", listOf(local(754.0)), listOf(server(754.toDouble())))

    assertEquals(BookmarkMergePlan(emptyList(), emptyList()), plan)
  }

  @Test
  fun newServerBookmarkIsAddedAndUnchangedRowIsLeftAlone() {
    val plan = mergeBookmarks("srv", listOf(local(10.0)), listOf(server(10.0), ServerBookmark("li2", 5.5, "Other", 7L)))

    assertEquals(listOf(BookmarkEntity("srv", "li2", 5.5, "Other", 7L, null, false)), plan.upserts)
    assertTrue(plan.deletes.isEmpty())
  }

  @Test
  fun sameTimeOnAnotherItemIsAnotherBookmark() {
    val plan = mergeBookmarks("srv", listOf(local(10.0)), listOf(ServerBookmark("li2", 10.0, "Local", 1L)))

    assertEquals(listOf("li2"), plan.upserts.map { it.libraryItemId })
    assertEquals(listOf("li1"), plan.deletes.map { it.libraryItemId })
  }

  @Test
  fun rowsOfAnotherServerAreIgnored() {
    val foreign = BookmarkEntity("other", "li1", 10.0, "Local", 1L, null, false)

    assertEquals(BookmarkMergePlan(emptyList(), emptyList()), mergeBookmarks("srv", listOf(foreign), emptyList()))
  }
}
