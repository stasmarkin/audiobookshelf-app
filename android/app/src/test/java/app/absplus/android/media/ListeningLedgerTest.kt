package app.absplus.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningLedgerTest {
  private var ids = 0
  private val ledger = ListeningLedger(newId = { "seg-${++ids}" })

  private fun listenAndSend(seconds: Long): Long {
    ledger.addListened(seconds)
    return ledger.beginServerSync()
  }

  @Test
  fun offlineSegmentGetsOwnIdAndConservesTime() {
    val serverSessionId = "srv-1"
    var acked = 0L
    val segments = LinkedHashMap<String, Long>()

    listenAndSend(15).let { ledger.ackServerSync(it); acked += it }
    assertNull(ledger.segment())

    val first = ledger.failServerSync(listenAndSend(15))
    assertNotEquals(serverSessionId, first.id)
    assertEquals(15L, first.timeListening)

    val same = ledger.failServerSync(listenAndSend(15))
    assertEquals("a further failure adds to the open segment", first.id, same.id)
    assertEquals(30L, same.timeListening)
    segments[same.id] = same.timeListening

    listenAndSend(15).let { ledger.ackServerSync(it); acked += it }
    assertNull("an accepted /sync closes the segment", ledger.segment())

    val next = ledger.failServerSync(listenAndSend(15))
    assertNotEquals(first.id, next.id)
    assertNotEquals(serverSessionId, next.id)
    assertEquals(15L, next.timeListening)
    segments[next.id] = next.timeListening

    assertEquals("every second goes exactly one way", 75L, acked + segments.values.sum())
  }

  @Test
  fun failedSyncDoesNotInflateNextPayload() {
    ledger.failServerSync(listenAndSend(15))
    assertEquals(15L, listenAndSend(15))
    assertEquals("nothing listened since the last attempt", 0L, ledger.beginServerSync())
  }

  @Test
  fun requestsInFlightTogetherSettleTheirOwnTime() {
    val a = listenAndSend(15)
    val b = listenAndSend(10)
    ledger.failServerSync(b)
    ledger.ackServerSync(a)
    assertNull(ledger.segment())
    // b's 10 s were already saved in the closed segment; nothing is counted twice
    assertEquals(0L, ledger.beginServerSync())
  }
}
