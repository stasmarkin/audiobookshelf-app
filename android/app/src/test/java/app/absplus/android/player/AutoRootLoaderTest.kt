package app.absplus.android.player

import org.junit.Assert.*
import org.junit.Test

/** The Android Auto root load runs in the background, once at a time, and refreshes the root when done (#30). */
class AutoRootLoaderTest {
  private val events = mutableListOf<String>()
  private val pending = mutableListOf<() -> Unit>()
  private var loads = 0
  private var failOnLoaded = false

  private val loader = AutoRootLoader(
    load = { onDone -> loads++; pending += onDone },
    onLoaded = { events += "loaded"; if (failOnLoaded) throw IllegalStateException("tree") },
    notifyRootChanged = { events += "notify" }
  )

  @Test fun rootRequestDoesNotWaitForTheServerLoad() {
    assertTrue(loader.reload())
    // The load (server checks, authorize, libraries) is still running: nothing installed or refreshed yet,
    // and the caller has already returned (the service sends the provisional Downloads root before this call).
    assertTrue(loader.isLoading)
    assertEquals(1, loads)
    assertTrue(events.isEmpty())
  }

  @Test fun overlappingRootRequestsShareOneLoadAndOneRefresh() {
    assertTrue(loader.reload())
    assertFalse(loader.reload())
    assertFalse(loader.reload())
    assertEquals(1, loads)
    pending.single()()
    assertEquals(listOf("loaded", "notify"), events)
    assertFalse(loader.isLoading)
  }

  @Test fun refreshFollowsTheInstalledTree() {
    loader.reload()
    pending.single()()
    assertEquals(listOf("loaded", "notify"), events)
  }

  @Test fun unreachableOrTimedOutServerStillCompletesAndAllowsALaterLoad() {
    // loadAndroidAutoItems calls back after a failed ping, a timed-out authorize or empty libraries alike
    loader.reload()
    pending.removeAt(0)()
    assertEquals(listOf("loaded", "notify"), events)
    assertTrue(loader.reload()) // e.g. the next car connection or a forced reload
    assertEquals(2, loads)
  }

  @Test fun aLoadThatNeverReturnsStartsNoFurtherLoads() {
    loader.reload()
    repeat(5) { assertFalse(loader.reload()) }
    assertEquals(1, loads)
    assertTrue(loader.isLoading)
    assertTrue(events.isEmpty())
  }

  @Test fun anErrorWhileInstallingReleasesTheLoader() {
    failOnLoaded = true
    loader.reload()
    assertThrows(IllegalStateException::class.java) { pending.single()() }
    assertFalse(loader.isLoading)
    failOnLoaded = false
    assertTrue(loader.reload())
  }
}
