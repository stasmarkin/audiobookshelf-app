package app.absplus.android.sync

import app.absplus.android.db.userdata.OutboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [coalesce]: pure rules, synthetic ids. */
class OutboxCoalescerTest {
  private val bookmarkKey = bookmarkItemKey("li1", 754.0)
  private val progressKey = progressItemKey("li1", null)

  private fun row(id: Long, kind: String, payload: String, attempts: Int = 0, itemKey: String = keyFor(kind)) =
          OutboxEntity(id = id, serverConfigId = "srv", kind = kind, itemKey = itemKey, payload = payload, createdAt = id,
                  attempts = attempts)

  private fun keyFor(kind: String) = if (kind.startsWith("progress")) progressKey else bookmarkKey

  private fun patch(id: Long, vararg fields: String) = row(id, OutboxKind.PROGRESS_PATCH, OutboxPayload.progressPatch(fields.toList()))
  private fun add(id: Long, title: String = "Mark", attempts: Int = 0) =
          row(id, OutboxKind.BOOKMARK_ADD, OutboxPayload.bookmark(754.0, title), attempts)
  private fun update(id: Long, title: String) = row(id, OutboxKind.BOOKMARK_UPDATE, OutboxPayload.bookmark(754.0, title))
  private fun remove(id: Long) = row(id, OutboxKind.BOOKMARK_REMOVE, OutboxPayload.bookmark(754.0))

  @Test
  fun progressPatchKeepsOnlyLatest() {
    val plan = coalesce(listOf(patch(1, "currentTime")), patch(0, "currentTime"), inFlightId = null)

    assertEquals(listOf(1L), plan.deleteIds)
    assertEquals(patch(0, "currentTime"), plan.insert)
    assertNull(plan.update)
  }

  @Test
  fun progressPatchMergesFieldSets() {
    val queued = listOf(patch(1, "currentTime", "progress"), patch(2, "ebookLocation"))

    val plan = coalesce(queued, patch(0, "isFinished"), inFlightId = null)

    assertEquals(listOf(1L, 2L), plan.deleteIds)
    assertEquals(listOf("progress", "currentTime", "isFinished", "ebookLocation"),
            OutboxPayload.progressPatchFields(plan.insert!!.payload))
  }

  @Test
  fun addThenRemoveCancelEachOther() {
    val plan = coalesce(listOf(add(1)), remove(0), inFlightId = null)

    assertEquals(OutboxPlan(listOf(1L), null, null), plan)
  }

  @Test
  fun updateFoldsIntoUnsentAdd() {
    val plan = coalesce(listOf(add(1, title = "Old")), update(0, title = "New"), inFlightId = null)

    assertEquals(OutboxPlan(emptyList(), null, add(1, title = "New")), plan)
  }

  @Test
  fun updateReplacesQueuedUpdate() {
    val plan = coalesce(listOf(update(1, "Old")), update(0, "New"), inFlightId = null)

    assertEquals(OutboxPlan(listOf(1L), update(0, "New"), null), plan)
  }

  @Test
  fun removeAfterSentAddIsQueued() {
    val plan = coalesce(emptyList(), remove(0), inFlightId = null)

    assertEquals(OutboxPlan(emptyList(), remove(0), null), plan)
  }

  @Test
  fun removeDropsQueuedRenames() {
    val plan = coalesce(listOf(update(1, "Renamed")), remove(0), inFlightId = null)

    assertEquals(OutboxPlan(listOf(1L), remove(0), null), plan)
  }

  @Test
  fun attemptedAddIsNotCancelled() {
    val plan = coalesce(listOf(add(1, attempts = 1)), remove(0), inFlightId = null)

    assertEquals(OutboxPlan(emptyList(), remove(0), null), plan)
  }

  @Test
  fun inFlightRowIsNeverCoalesced() {
    val bookmark = coalesce(listOf(add(1)), remove(0), inFlightId = 1L)
    assertEquals(OutboxPlan(emptyList(), remove(0), null), bookmark)

    val progress = coalesce(listOf(patch(1, "currentTime")), patch(0, "isFinished"), inFlightId = 1L)
    assertEquals(OutboxPlan(emptyList(), patch(0, "isFinished"), null), progress)
  }

  @Test
  fun progressDeleteFoldsWithNothing() {
    val delete = row(0, OutboxKind.PROGRESS_DELETE, OutboxPayload.progressDelete("mp1"))
    assertEquals(OutboxPlan(emptyList(), delete, null), coalesce(listOf(patch(1, "currentTime")), delete, inFlightId = null))

    // A patch after a reset keeps the reset queued before it
    val afterReset = coalesce(listOf(delete.copy(id = 1)), patch(0, "currentTime"), inFlightId = null)
    assertEquals(OutboxPlan(emptyList(), patch(0, "currentTime"), null), afterReset)
  }

  @Test
  fun otherItemsAndServersAreLeftAlone() {
    val otherItem = add(1).copy(itemKey = bookmarkItemKey("li1", 755.0))
    val otherServer = add(2).copy(serverConfigId = "other")

    assertEquals(OutboxPlan(emptyList(), remove(0), null), coalesce(listOf(otherItem, otherServer), remove(0), inFlightId = null))
  }
}
