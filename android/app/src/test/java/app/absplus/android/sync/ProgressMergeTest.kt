package app.absplus.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressMergeTest {
  private fun server(currentTime: Double, lastUpdate: Long, isFinished: Boolean = false, episodeId: String? = null) = ServerProgress(
          id = "mp1", libraryItemId = "li1", episodeId = episodeId, duration = 1000.0, progress = currentTime / 1000.0,
          currentTime = currentTime, isFinished = isFinished, hideFromContinueListening = false, ebookLocation = null,
          ebookProgress = null, lastUpdate = lastUpdate, startedAt = 2L, finishedAt = null)

  @Test
  fun table() {
    data class Case(val name: String, val localTime: Long?, val localPending: Boolean, val serverTime: Long, val takesServer: Boolean)
    val cases = listOf(
            Case("no local row", null, false, 100, true),
            Case("local newer", 200, false, 100, false),
            Case("server newer, not pending", 100, false, 200, true),
            Case("tie keeps local", 150, false, 150, false))
    for (case in cases) {
      val local = case.localTime?.let { progressRow(500.0, it, pending = case.localPending) }
      val result = mergeProgress("srv", local, server(10.0, case.serverTime))
      assertEquals(case.name, case.takesServer, result is ProgressMerge.TakeServer)
    }

    val taken = mergeProgress("srv", null, server(10.0, 100, episodeId = null)) as ProgressMerge.TakeServer
    assertEquals("server", taken.row.source)
    assertEquals(false, taken.row.pending)
    assertEquals("a book is keyed by an empty episode id", "", taken.row.episodeId)
    assertEquals("mp1", taken.row.serverProgressId)

    // No "never move the position back" rule any more: a newer finished server record wins over a later local position
    val finished = mergeProgress("srv", progressRow(900.0, 100, pending = false), server(0.0, 200, isFinished = true))
    assertTrue(finished is ProgressMerge.TakeServer)
    assertEquals(0.0, (finished as ProgressMerge.TakeServer).row.currentTime, 0.0)
  }

  @Test
  fun pendingLocalWinsOverNewerServer() {
    val local = progressRow(500.0, lastUpdate = 100, pending = true)
    assertEquals(ProgressMerge.KeepLocal, mergeProgress("srv", local, server(10.0, 999)))
  }

  @Test
  fun unsentSessionsCountAsPending() {
    val local = progressRow(500.0, lastUpdate = 100, pending = false)
    assertEquals(ProgressMerge.KeepLocal, mergeProgress("srv", local, server(10.0, 999), hasUnsentSessions = true))
    assertTrue(mergeProgress("srv", local, server(10.0, 999), hasUnsentSessions = false) is ProgressMerge.TakeServer)
  }
}
