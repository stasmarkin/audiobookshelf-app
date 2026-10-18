package app.absplus.android.media

import app.absplus.android.player.AUTO_BROWSE_ROOT
import app.absplus.android.player.CONTINUE_ROOT
import app.absplus.android.sync.DataChange
import app.absplus.android.sync.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Android Auto nodes are told to reload on a change of the data or of the server. */
class AutoRefreshTest {
  private var now = 1_000_000L
  private val refresh = AutoRefresh({ now })

  private fun change(scope: String) = DataChange(scope, listOf("li1"))

  @Test fun playbackTicksNotifyNothing() {
    refresh.served(CONTINUE_ROOT)
    refresh.served("__LIBRARY__l1__AUTHORS")
    repeat(100) {
      now += 600
      assertEquals(emptySet<String>(), refresh.onData(change("playback")))
      assertEquals(emptySet<String>(), refresh.due())
    }
    assertEquals(emptySet<String>(), refresh.onData(change("bookmarks")))
    assertFalse(refresh.hasPending)
  }

  @Test fun progressIsThrottled() {
    assertEquals(setOf(CONTINUE_ROOT), refresh.onData(change("progress")))
    now += 3_000
    assertEquals(emptySet<String>(), refresh.onData(change("progress")))
    assertTrue(refresh.hasPending)
    now += 6_999
    assertEquals(emptySet<String>(), refresh.due())
    now += 1
    assertEquals(setOf(CONTINUE_ROOT), refresh.due())
    assertEquals(emptySet<String>(), refresh.due())
    assertFalse(refresh.hasPending)
  }

  @Test fun progressNotifiesServedNodes() {
    refresh.served("__LIBRARY__l1__SERIES__s1")
    assertEquals(setOf(CONTINUE_ROOT, "__LIBRARY__l1__SERIES__s1"), refresh.onData(change("progress")))
  }

  @Test fun catalogNotifiesServedNodes() {
    refresh.served("__LIBRARY__l1__AUTHORS")
    refresh.served("l1")
    assertEquals(setOf(AUTO_BROWSE_ROOT, "__LIBRARY__l1__AUTHORS", "l1"), refresh.onData(change("catalog")))
    now += 1_000
    assertEquals(emptySet<String>(), refresh.onData(change("catalog")))
    now += 1_000
    assertEquals(setOf(AUTO_BROWSE_ROOT, "__LIBRARY__l1__AUTHORS", "l1"), refresh.due())
  }

  @Test fun onlyTheLatestServedNodesAreKept() {
    repeat(60) { refresh.served("node$it") }
    refresh.served("node5")
    val notified = refresh.onData(change("catalog"))
    assertEquals(51, notified.size)
    assertTrue("served again: among the latest", "node5" in notified)
    assertFalse("node10" in notified)
  }

  @Test fun serverStateChangeRefreshesMarks() {
    refresh.served("__LIBRARY__l1__AUTHOR__a1")
    assertEquals("the first state is where the lists were drawn", emptySet<String>(), refresh.onServerState(ServerState.ONLINE))
    assertEquals(setOf("__LIBRARY__l1__AUTHOR__a1"), refresh.onServerState(ServerState.OFFLINE))
    assertEquals(emptySet<String>(), refresh.onServerState(ServerState.OFFLINE))
    assertEquals("still no stream", emptySet<String>(), refresh.onServerState(ServerState.UNKNOWN))
    assertEquals(setOf("__LIBRARY__l1__AUTHOR__a1"), refresh.onServerState(ServerState.UNAUTHORIZED))
    assertEquals("both take play requests", emptySet<String>(), refresh.onServerState(ServerState.ONLINE))
  }

  @Test fun userChangeRefreshesShelves() {
    refresh.served("__RECENTLY__l1")
    assertEquals(setOf(AUTO_BROWSE_ROOT, "__RECENTLY__l1"), refresh.onData(change("user")))
    now += 500
    assertEquals("throttled with the catalog", emptySet<String>(), refresh.onData(change("catalog")))
    now += 1_500
    assertEquals(setOf(AUTO_BROWSE_ROOT, "__RECENTLY__l1"), refresh.due())
  }

  @Test fun serverSwitchNotifiesTheRoot() {
    refresh.served("l1")
    refresh.onData(change("catalog"))
    now += 100
    assertTrue(AUTO_BROWSE_ROOT in refresh.onServerSwitched())
  }
}
