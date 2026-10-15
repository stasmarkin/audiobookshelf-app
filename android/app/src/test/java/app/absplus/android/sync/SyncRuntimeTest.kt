package app.absplus.android.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncRuntimeTest {
  private lateinit var f: SyncTestFixture

  @Before fun setUp() { f = SyncTestFixture() }

  @After fun tearDown() = f.close()

  @Test
  fun foregroundFlagDrivesReconcileGate() {
    f.save(f.config("a"))
    ActiveServer.init(f.context)
    ActiveServer.remember("a")
    f.probeAnswer = false
    ServerReachability.start(f.context, f.probe)
    f.idle()
    assertEquals(ServerState.OFFLINE, ServerReachability.state.value)
    assertFalse(SyncRuntime.isForeground)

    SyncRuntime.setForeground(true)
    assertTrue(SyncRuntime.isForeground)
    f.idle()
    assertEquals("returning to the foreground probes right away", 2, f.probed.size)

    SyncRuntime.setForeground(false)
    assertFalse(SyncRuntime.isForeground)
    f.idle()
    assertEquals(2, f.probed.size)
  }
}
