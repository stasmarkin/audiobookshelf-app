package app.absplus.android.sync

import app.absplus.android.device.DeviceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric runs the main looper paused: posted probes and timers run only when the test advances the clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerReachabilityTest {
  private lateinit var f: SyncTestFixture
  private val base = "https://host.test.invalid/abs"

  @Before fun setUp() { f = SyncTestFixture() }

  @After fun tearDown() = f.close()

  /** Server "a" at [base] remembered and confirmed ONLINE by one successful probe. */
  private fun online() {
    f.save(f.config("a", base), f.config("b"))
    ActiveServer.init(f.context)
    ActiveServer.remember("a")
    ServerReachability.start(f.context, f.probe)
    f.idle()
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
  }

  @Test
  fun probeUsesPingOfActiveServerAndEmitsOnline() {
    online()
    assertEquals(listOf(base), f.probed.map { it.address })
    assertEquals("a", ServerReachability.serverConfigId.value)
  }

  @Test
  fun unreachableRequestStartsBackoffProbe() {
    online()
    f.probeAnswer = false
    ServerReachability.reportUnreachable("$base/api/me")
    assertEquals(ServerState.OFFLINE, ServerReachability.state.value)
    f.advance(4_999)
    assertEquals(1, f.probed.size)
    f.advance(1)
    assertEquals(2, f.probed.size)
    f.advance(9_999)
    assertEquals("next probe after 10 s", 2, f.probed.size)
    f.probeAnswer = true
    f.advance(1)
    assertEquals(3, f.probed.size)
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
  }

  @Test
  fun foreignAddressIsIgnored() {
    online()
    ServerReachability.reportUnreachable("https://other.test.invalid/api/x")
    ServerReachability.reportUnreachable("http://host.test.invalid/abs/api/x")
    ServerReachability.reportUnreachable("capacitor://localhost/_capacitor_file_/x")
    f.advance(60_000)
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
    assertEquals(1, f.probed.size)
  }

  @Test
  fun subpathServerIsMatchedByPrefix() {
    online()
    ServerReachability.reportUnreachable("https://host.test.invalid/other/api/me")
    ServerReachability.reportUnreachable("https://host.test.invalid/absx/api")
    ServerReachability.reportUnreachable("https://host.test.invalid/")
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
    ServerReachability.reportUnreachable("https://HOST.test.invalid:443/abs/api/me?x=1")
    assertEquals(ServerState.OFFLINE, ServerReachability.state.value)
    ServerReachability.reportResponse("$base/ping", 200)
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
    ServerReachability.reportUnreachable(base)
    assertEquals(ServerState.OFFLINE, ServerReachability.state.value)
  }

  @Test
  fun serverErrorKeepsStateAndAnyOtherStatusMeansOnline() {
    online()
    ServerReachability.reportUnreachable("$base/api/me")
    ServerReachability.reportResponse("$base/api/me", 502)
    assertEquals(ServerState.OFFLINE, ServerReachability.state.value)
    ServerReachability.reportResponse("$base/api/me", 404)
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
  }

  @Test
  fun startWithRememberedServerProbes() {
    f.save(f.config("a"))
    ActiveServer.init(f.context)
    ActiveServer.remember("a")
    assertEquals(null, DeviceManager.serverConnectionConfig)
    ServerReachability.start(f.context, f.probe)
    f.idle()
    assertEquals(listOf("a"), f.probed.map { it.id })
  }

  @Test
  fun startWithoutNetworkIsOfflineAndDoesNotProbe() {
    f.setNetwork(false)
    f.save(f.config("a"))
    ActiveServer.init(f.context)
    ActiveServer.remember("a")
    ServerReachability.start(f.context, f.probe)
    f.advance(60_000)
    assertEquals(ServerState.OFFLINE, ServerReachability.state.value)
    assertTrue(f.probed.isEmpty())
  }

  @Test
  fun rejectionOfActiveServerWithoutSessionIsUnauthorized() {
    online()
    DeviceManager.serverConnectionConfig = null
    ServerReachability.reportRefreshRejected("b")
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
    ServerReachability.reportRefreshRejected("a")
    assertEquals(ServerState.UNAUTHORIZED, ServerReachability.state.value)
    ServerReachability.reportSignedIn("a")
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
  }

  @Test
  fun staleProbeResultIsDropped() {
    f.save(f.config("a", base), f.config("b"))
    ActiveServer.init(f.context)
    ActiveServer.remember("a")
    f.probeAnswer = null
    ServerReachability.start(f.context, f.probe)
    f.idle()
    assertEquals(1, f.pending.size)

    ActiveServer.remember("b") // switch while a's probe is in flight
    f.idle()
    assertEquals(listOf("a", "b"), f.probed.map { it.id })
    f.pending[0].complete(false)
    f.idle()
    assertEquals("a's late failure must not mark b offline", ServerState.UNKNOWN, ServerReachability.state.value)
    f.pending[1].complete(true)
    f.idle()
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
    assertEquals("b", ServerReachability.serverConfigId.value)
  }

  @Test
  fun rememberingAnotherServerProbesItsAddress() {
    online()
    ActiveServer.remember("b") // e.g. Android Auto picked another saved server
    assertEquals("b", ServerReachability.serverConfigId.value)
    f.idle()
    assertEquals(listOf(base, "https://b.test.invalid"), f.probed.map { it.address })
    assertEquals(ServerState.ONLINE, ServerReachability.state.value)
  }

  @Test
  fun forgettingTheActiveServerIsUnknown() {
    online()
    ActiveServer.forget("a")
    assertEquals(ServerState.UNKNOWN, ServerReachability.state.value)
    assertEquals(null, ServerReachability.serverConfigId.value)
  }

  @Test
  fun ensureInitIsIdempotentAndWorksWithoutActivity() {
    val before = f.networkCallbacks().size
    SyncRuntime.ensureInit(f.context)
    SyncRuntime.ensureInit(f.context)
    assertEquals(before + 1, f.networkCallbacks().size)
    ServerReachability.reportUnreachable("https://a.test.invalid/api/me")
    ServerReachability.reportSocketLost()
    f.idle()
    assertEquals(ServerState.UNKNOWN, ServerReachability.state.value)
  }
}
