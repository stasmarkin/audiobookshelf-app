package app.absplus.android.media

import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
import app.absplus.android.data.ItemInProgress
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where an Android Auto node is read from, what voice and Play pick, and when the root snapshot is read again. */
class AutoSourceTest {
  private var serverCalls = 0
  private fun server(value: String): ((String) -> Unit) -> Unit = { cb -> serverCalls++; cb(value) }

  @Test fun reconciledNeverCallsTheServer() = runTest {
    assertEquals("replica", replicaOr({ true }, { AutoAnswer("replica", true) }, server("server")))
    assertEquals(0, serverCalls)
  }

  @Test fun unreconciledOnlineGoesToTheServer() = runTest {
    assertEquals("server", replicaOr({ true }, { AutoAnswer("replica", false) }, server("server")))
    assertEquals(1, serverCalls)
  }

  @Test fun unreconciledOfflineAnswersTheReplica() = runTest {
    assertEquals("replica", replicaOr({ false }, { AutoAnswer("replica", false) }, server("server")))
    assertEquals(emptyList<String>(), replicaOr({ false }, { AutoAnswer(emptyList<String>(), false) }) { cb -> serverCalls++; cb(listOf("x")) })
    assertEquals(0, serverCalls)
  }

  @Test fun replicaFailureFallsBackOnlineOnly() = runTest {
    val failing: suspend () -> AutoAnswer<String>? = { throw IllegalStateException("broken replica") }
    assertEquals("server", replicaOr({ true }, failing, server("server")))
    assertNull(replicaOr({ false }, failing, server("server")))
    assertEquals("server", replicaOr({ true }, { null }, server("server")))
    assertNull(replicaOr({ false }, { null }, server("server")))
    assertEquals(2, serverCalls)
  }

  @Test fun aServerPathAnsweringTwiceCountsOnce() = runTest {
    assertEquals("first", replicaOr({ true }, { AutoAnswer("replica", false) }) { cb -> cb("first"); cb("second") })
  }

  private fun item(id: String, downloaded: Boolean = false) = LibraryItem(id, "ino-$id", "lib", "f", "/$id", id, 1, 1, 1, 1, 1, null, null,
          false, false, "book", MediaType(MediaTypeMetadata(id, false), null), null, null, null, if (downloaded) "local_$id" else null, null)

  @Test fun voicePrefersADownloadOffline() {
    val found = listOf(item("a"), item("b", downloaded = true), item("c"))
    assertEquals("b", voicePick(found, online = false)!!.id)
    assertEquals("a", voicePick(found, online = true)!!.id)
    assertEquals("a", voicePick(listOf(item("a"), item("c")), online = false)!!.id)
    assertNull(voicePick(emptyList(), online = false))
  }

  private fun localBook(id: String) = LocalLibraryItem(id, "f", "", "", "", false, "book",
          Book(BookMetadata(id, null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null),
                  null, emptyList(), null, null, null, null, null, null, 1),
          mutableListOf(), null, null, true, "srv", null, null, null)

  @Test fun playWithoutChoiceResumesTheLatest() {
    val latest = ItemInProgress(item("latest"), null, 200, false)
    val older = ItemInProgress(item("older"), null, 100, false)
    assertEquals("latest", firstPlayableOf(listOf(latest, older), listOf(localBook("local_1")))!!.first.id)
    assertEquals("local_1", firstPlayableOf(emptyList(), listOf(localBook("local_1")))!!.first.id)
    assertNull(firstPlayableOf(emptyList(), emptyList()))
  }

  @Test fun snapshotIsReusedUntilDataChanges() {
    var now = 0L
    val gate = SnapshotGate({ now })
    var recomputes = 0
    var progressReads = 0
    fun request(server: String = "srv") {
      val decision = gate.decide(server)
      when (decision.action) {
        SnapshotGate.Action.RECOMPUTE -> recomputes++
        SnapshotGate.Action.PROGRESS -> progressReads++
        SnapshotGate.Action.NONE -> {}
      }
      gate.done(decision, server)
    }
    request(); request()
    assertEquals(1, recomputes)

    gate.onData("catalog")
    request()
    assertEquals(2, recomputes)

    repeat(10) {
      now += 500
      gate.onData("playback")
      request()
    }
    assertEquals(2, recomputes)
    assertTrue(progressReads <= 1)

    now += 10_000
    request()
    assertEquals("ticks since the last read are read once the interval passed", 1, progressReads)
    request()
    assertEquals(1, progressReads)

    gate.onData("bookmarks")
    request()
    assertEquals(2, recomputes)

    request("srv2")
    assertEquals(3, recomputes)
  }

  @Test fun aChangeDuringARecomputeIsNotLost() {
    val gate = SnapshotGate({ 0L })
    val decision = gate.decide("srv")
    gate.onData("progress")
    gate.done(decision, "srv")
    assertEquals(SnapshotGate.Action.RECOMPUTE, gate.decide("srv").action)
  }

  private fun snapshot(generation: Long, continueIds: List<String>, server: String = "srv") = AutoSnapshot(server, emptyList(), emptySet(), emptySet(),
          continueIds.map { ItemInProgress(item(it), null, 1, false) }, emptyMap(), generation)

  @Test fun staleSnapshotIsRecomputedOnTheNextRead() = runTest {
    val gate = SnapshotGate({ 0L })
    var generation = 0L
    var continueIds = listOf("a")
    val cache = SnapshotCache(gate, { "srv" }, { snapshot(++generation, continueIds) }, { emptyMap() })
    val first = cache.refresh()!!
    assertSame(first, cache.refresh())

    continueIds = listOf("b", "a")
    gate.onData("progress")
    val second = cache.refresh()!!
    assertNotSame(first, second)
    assertEquals(2, second.generation)
    assertEquals(listOf("b", "a"), second.continueItems.map { it.libraryItemWrapper.id })
  }

  @Test fun aProgressReadKeepsTheSnapshotAndItsGeneration() = runTest {
    var now = 0L
    val gate = SnapshotGate({ now })
    val progress = MediaProgress("p", "a", null, 10.0, 0.5, 5.0, false, null, null, 1, 1, null)
    val cache = SnapshotCache(gate, { "srv" }, { snapshot(7, listOf("a")) }, { mapOf("a" to progress) })
    cache.refresh()
    now = 20_000
    gate.onData("playback")
    val read = cache.refresh()!!
    assertEquals(7, read.generation)
    assertSame(progress, read.progress["a"])
  }

  @Test fun aFailedRecomputeDropsAnotherServersSnapshot() = runTest {
    val gate = SnapshotGate({ 0L })
    var active = "srv"
    var fail = false
    val cache = SnapshotCache(gate, { active }, { if (fail) error("replica") else snapshot(1, emptyList(), active) }, { emptyMap() })
    cache.refresh()
    active = "srv2"
    fail = true
    assertNull(cache.refresh())
    fail = false
    assertEquals("srv2", cache.refresh()!!.serverConfigId)
    assertFalse(cache.current == null)
  }
}
