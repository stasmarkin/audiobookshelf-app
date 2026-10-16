package app.absplus.android.managers

import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StorageMovePassTest {
  private fun book() =
          Book(
                  BookMetadata("Book", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null),
                  null, listOf(), null, null, mutableListOf(), null, null, null, null
          )

  private fun item(id: String, size: Long = 10) =
          LocalLibraryItem(
                  id, "internal-book", "", "", "", false, "book", book(),
                  mutableListOf(LocalFile("f-$id", "a.mp3", "", "", "", "audio/mpeg", size)),
                  null, null, true, "server", null, null, "li-$id"
          )

  /** Plans every item not moved yet; the call limit makes a pass that never ends fail instead of hang. */
  private class Planner(val items: List<LocalLibraryItem>, val waiting: Map<String, WaitReason> = emptyMap()) {
    val moved = mutableSetOf<String>()
    var calls = 0

    fun plan(): MovePlan {
      check(calls < 50) { "planFn called $calls times: the pass does not end" }
      calls++
      return MovePlan(items.filter { it.id !in moved }, waiting)
    }
  }

  @Test
  fun persistentFailureSkipsTheItem() {
    val planner = Planner(listOf(item("A"), item("B")))
    val calls = mutableMapOf<String, Int>()
    val movedEvents = mutableListOf<String>()

    val status =
            StorageMovePass.run(
                    planner::plan,
                    { i ->
                      calls.merge(i.id, 1, Int::plus)
                      if (i.id == "A") MoveResult.Failed("boom") else MoveResult.Moved.also { planner.moved += i.id }
                    },
                    { movedEvents += it },
                    {})

    assertEquals("a failed item is tried once per pass", 1, calls["A"])
    assertEquals("the others still move", listOf("B"), movedEvents)
    assertEquals("boom", status.lastError)
    assertEquals(mapOf(WaitReason.RETRY to 1), status.waiting)
    assertEquals(1, status.done)
    assertFalse(status.running)
  }

  @Test
  fun discardedTwiceBecomesWaiting() {
    val planner = Planner(listOf(item("A"), item("B")))
    val calls = mutableMapOf<String, Int>()

    val status =
            StorageMovePass.run(
                    planner::plan,
                    { i ->
                      calls.merge(i.id, 1, Int::plus)
                      if (i.id == "A") MoveResult.Discarded("changed during copy")
                      else MoveResult.Moved.also { planner.moved += i.id }
                    },
                    {},
                    {})

    assertEquals("A is tried twice, then waits", 2, calls["A"])
    assertEquals(1, calls["B"])
    assertEquals(mapOf(WaitReason.RETRY to 1), status.waiting)
    assertEquals(1, status.done)
    assertFalse(status.running)
  }

  @Test
  fun eventOnlyForMoved() {
    val planner = Planner(listOf(item("A"), item("B"), item("C")), mapOf("X" to WaitReason.UNAVAILABLE))
    val movedEvents = mutableListOf<String>()
    val statuses = mutableListOf<MoveStatus>()

    val status =
            StorageMovePass.run(
                    planner::plan,
                    { i ->
                      if (i.id == "B") MoveResult.Discarded("playing")
                      else MoveResult.Moved.also { planner.moved += i.id }
                    },
                    { movedEvents += it },
                    { statuses += it })

    assertEquals(listOf("A", "C"), movedEvents)
    assertFalse("the last status ends the pass", statuses.last().running)
    assertTrue("every earlier status is in progress", statuses.dropLast(1).all { it.running })
    assertEquals(status, statuses.last())
    assertEquals(mapOf(WaitReason.UNAVAILABLE to 1, WaitReason.RETRY to 1), status.waiting)
    assertEquals(2, status.done)
    assertEquals(20L, status.bytesDone)
  }

  @Test
  fun reconcileClearsFlagAfterCompletedPass() {
    val calls = mutableListOf<String>()
    val flags = mutableListOf<Boolean>()
    val planner = Planner(listOf(item("A")), mapOf("X" to WaitReason.UNAVAILABLE))

    StorageMovePass.reconcile(
            { flags += it; calls += "flag=$it" },
            { calls += "heal" },
            { calls += "clean" },
            { calls += "plan"; planner.plan() },
            { MoveResult.Failed("boom") },
            {},
            {})

    assertEquals(listOf("flag=true", "heal", "clean", "plan", "plan", "flag=false"), calls)
    assertEquals(false, flags.last())

    val thrownFlags = mutableListOf<Boolean>()
    try {
      StorageMovePass.reconcile(
              { thrownFlags += it },
              {},
              { throw IllegalStateException("cleanup") },
              { Planner(listOf()).plan() },
              { MoveResult.Moved },
              {},
              {})
      fail("the cleanup error must reach the caller")
    } catch (e: IllegalStateException) {
      assertEquals("cleanup", e.message)
    }
    assertEquals("the flag stays set for the next pass", true, thrownFlags.last())
  }
}
