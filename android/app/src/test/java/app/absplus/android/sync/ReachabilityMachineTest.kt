package app.absplus.android.sync

import app.absplus.android.sync.ReachabilityEffect.CancelProbe
import app.absplus.android.sync.ReachabilityEffect.Emit
import app.absplus.android.sync.ReachabilityEffect.ScheduleProbe
import app.absplus.android.sync.ReachabilityInput.Foreground
import app.absplus.android.sync.ReachabilityInput.ManualRetry
import app.absplus.android.sync.ReachabilityInput.NetworkAvailable
import app.absplus.android.sync.ReachabilityInput.NetworkLost
import app.absplus.android.sync.ReachabilityInput.ProbeFailed
import app.absplus.android.sync.ReachabilityInput.ProbeSucceeded
import app.absplus.android.sync.ReachabilityInput.RefreshRejected
import app.absplus.android.sync.ReachabilityInput.RequestSucceeded
import app.absplus.android.sync.ReachabilityInput.RequestUnreachable
import app.absplus.android.sync.ReachabilityInput.ServerCleared
import app.absplus.android.sync.ReachabilityInput.ServerConfigured
import app.absplus.android.sync.ReachabilityInput.ServerError
import app.absplus.android.sync.ReachabilityInput.SignedIn
import app.absplus.android.sync.ReachabilityInput.SocketLost
import app.absplus.android.sync.ServerState.OFFLINE
import app.absplus.android.sync.ServerState.ONLINE
import app.absplus.android.sync.ServerState.UNAUTHORIZED
import app.absplus.android.sync.ServerState.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityMachineTest {
  private fun assertEffects(expected: Set<ReachabilityEffect>, actual: List<ReachabilityEffect>, message: String = "") =
          assertEquals(message, expected, actual.toSet())

  /** A machine with server "a" configured (except UNKNOWN) and driven into [target], network up. */
  private fun machineIn(target: ServerState): ReachabilityMachine {
    val m = ReachabilityMachine(networkAvailable = true)
    if (target == UNKNOWN) return m
    m.on(ServerConfigured("a"))
    when (target) {
      ONLINE -> m.on(ProbeSucceeded)
      OFFLINE -> m.on(ProbeFailed)
      UNAUTHORIZED -> m.on(RefreshRejected("a"))
      UNKNOWN -> Unit
    }
    assertEquals(target, m.state)
    return m
  }

  @Test
  fun serverConfiguredProbesImmediately() {
    val m = ReachabilityMachine(networkAvailable = true)
    assertEffects(setOf(ScheduleProbe(0)), m.on(ServerConfigured("a")))
    assertEquals(UNKNOWN, m.state)
    assertEffects(setOf(Emit(ONLINE)), m.on(ProbeSucceeded))

    val failing = ReachabilityMachine(networkAvailable = true)
    failing.on(ServerConfigured("a"))
    assertEffects(setOf(Emit(OFFLINE), ScheduleProbe(5000)), failing.on(ProbeFailed))
  }

  @Test
  fun serverConfiguredWithoutNetworkIsOfflineWithoutProbe() {
    val m = ReachabilityMachine(networkAvailable = false)
    assertEffects(setOf(CancelProbe, Emit(OFFLINE)), m.on(ServerConfigured("a")))
    assertEffects(setOf(ScheduleProbe(0)), m.on(NetworkAvailable))
  }

  @Test
  fun serverSwitchResetsToUnknownAndProbes() {
    val m = machineIn(ONLINE)
    assertEffects(emptySet(), m.on(ServerConfigured("a")), "same server: nothing")
    assertEffects(setOf(Emit(UNKNOWN), ScheduleProbe(0)), m.on(ServerConfigured("b")))
    assertEffects(emptySet(), m.on(SignedIn("a")), "sign-in of the previous server is stale")
    assertEffects(setOf(CancelProbe, Emit(ONLINE)), m.on(SignedIn("b")))
  }

  @Test
  fun unreachableRequestGoesOfflineAndSchedulesBackoff() {
    val m = machineIn(ONLINE)
    assertEffects(setOf(Emit(OFFLINE), ScheduleProbe(5000)), m.on(RequestUnreachable))
    for (delay in listOf(10_000L, 30_000L, 60_000L, 300_000L, 300_000L)) {
      assertEffects(setOf(ScheduleProbe(delay)), m.on(ProbeFailed))
    }
    assertEffects(emptySet(), m.on(RequestUnreachable), "already offline: the scheduled probe stays")
  }

  @Test
  fun serverErrorDoesNotChangeState() {
    for (start in listOf(ONLINE, OFFLINE)) {
      val m = machineIn(start)
      val effects = m.on(ServerError(502))
      assertTrue("$start: $effects", effects.none { it is Emit })
      assertEquals(start, m.state)
    }
  }

  @Test
  fun noProbeWithoutNetwork() {
    val m = machineIn(ONLINE)
    assertEffects(setOf(CancelProbe, Emit(OFFLINE)), m.on(NetworkLost))
    assertTrue(m.on(ProbeFailed).none { it is ScheduleProbe })
    assertTrue(m.on(Foreground).none { it is ScheduleProbe })
    assertTrue(m.on(ManualRetry).none { it is ScheduleProbe })
    assertTrue(m.on(RequestUnreachable).none { it is ScheduleProbe })
  }

  @Test
  fun transitionTable() {
    val inputs = listOf(
            NetworkAvailable, NetworkLost, ServerConfigured("a"), ServerConfigured("b"), ServerCleared,
            RequestSucceeded, RequestUnreachable, ServerError(503), RefreshRejected("a"), RefreshRejected("b"),
            SignedIn("a"), ProbeSucceeded, ProbeFailed, Foreground, ManualRetry, SocketLost)
    // Columns follow `inputs`. UNKNOWN has no server: only ServerConfigured moves it.
    val table = mapOf(
            UNKNOWN to listOf(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN,
                    UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN),
            ONLINE to listOf(ONLINE, OFFLINE, ONLINE, UNKNOWN, UNKNOWN, ONLINE, OFFLINE, ONLINE, UNAUTHORIZED, ONLINE,
                    ONLINE, ONLINE, OFFLINE, ONLINE, ONLINE, ONLINE),
            OFFLINE to listOf(OFFLINE, OFFLINE, OFFLINE, UNKNOWN, UNKNOWN, ONLINE, OFFLINE, OFFLINE, UNAUTHORIZED, OFFLINE,
                    ONLINE, ONLINE, OFFLINE, OFFLINE, OFFLINE, OFFLINE),
            UNAUTHORIZED to listOf(UNAUTHORIZED, UNAUTHORIZED, UNAUTHORIZED, UNKNOWN, UNKNOWN, UNAUTHORIZED, UNAUTHORIZED,
                    UNAUTHORIZED, UNAUTHORIZED, UNAUTHORIZED, ONLINE, UNAUTHORIZED, UNAUTHORIZED, UNAUTHORIZED,
                    UNAUTHORIZED, UNAUTHORIZED))
    for ((start, expectedRow) in table) {
      inputs.forEachIndexed { i, input ->
        val m = machineIn(start)
        val effects = m.on(input)
        val expected = expectedRow[i]
        assertEquals("$start + $input", expected, m.state)
        val emits = effects.filterIsInstance<Emit>()
        if (expected == start) {
          assertTrue("$start + $input must not emit: $effects", emits.isEmpty())
        } else {
          assertEquals("$start + $input", listOf(Emit(expected)), emits)
        }
      }
    }
  }

  @Test
  fun networkChangeForegroundAndManualRetryResetBackoff() {
    for (reset in listOf(NetworkAvailable, Foreground, ManualRetry)) {
      val m = machineIn(OFFLINE) // first ProbeFailed already scheduled 5000
      m.on(ProbeFailed)
      m.on(ProbeFailed)
      m.on(ProbeFailed)
      assertEffects(setOf(ScheduleProbe(0)), m.on(reset), "$reset")
      assertEffects(setOf(ScheduleProbe(5000)), m.on(ProbeFailed), "$reset")
    }
  }

  @Test
  fun refreshRejectedIsStickyUntilSignIn() {
    val m = machineIn(ONLINE)
    assertEffects(setOf(Emit(UNAUTHORIZED), CancelProbe), m.on(RefreshRejected("a")))
    assertEffects(emptySet(), m.on(RequestSucceeded))
    assertEffects(emptySet(), m.on(ProbeSucceeded))
    assertEquals(UNAUTHORIZED, m.state)
    assertEffects(setOf(CancelProbe, Emit(ONLINE)), m.on(SignedIn("a")))
  }

  @Test
  fun signedInResetsBackoff() {
    val m = machineIn(OFFLINE)
    m.on(ProbeFailed)
    m.on(SignedIn("a"))
    assertEffects(setOf(Emit(OFFLINE), ScheduleProbe(5000)), m.on(RequestUnreachable))
  }

  @Test
  fun serverClearedIsUnknown() {
    val m = machineIn(ONLINE)
    assertEffects(setOf(Emit(UNKNOWN), CancelProbe), m.on(ServerCleared))
    assertEffects(emptySet(), m.on(RequestUnreachable))
  }

  @Test
  fun socketLostProbesImmediately() {
    val m = machineIn(ONLINE)
    assertEffects(setOf(ScheduleProbe(0)), m.on(SocketLost))
    assertEquals(ONLINE, m.state)
    assertEffects(setOf(Emit(OFFLINE), ScheduleProbe(5000)), m.on(ProbeFailed))

    val noNetwork = machineIn(ONLINE).apply { on(NetworkLost) }
    assertEffects(emptySet(), noNetwork.on(SocketLost), "no network: no probe")
    assertEffects(emptySet(), machineIn(UNAUTHORIZED).on(SocketLost), "unauthorized ignores the socket")
  }

  @Test
  fun configuredServerAwaitingFirstProbeAcceptsRequestResults() {
    val up = ReachabilityMachine(networkAvailable = true).apply { on(ServerConfigured("a")) }
    assertEffects(setOf(CancelProbe, Emit(ONLINE)), up.on(RequestSucceeded))
    val down = ReachabilityMachine(networkAvailable = true).apply { on(ServerConfigured("a")) }
    assertEffects(setOf(Emit(OFFLINE), ScheduleProbe(5000)), down.on(RequestUnreachable))
    val lost = ReachabilityMachine(networkAvailable = true).apply { on(ServerConfigured("a")) }
    assertEffects(setOf(CancelProbe, Emit(OFFLINE)), lost.on(NetworkLost))
  }
}
