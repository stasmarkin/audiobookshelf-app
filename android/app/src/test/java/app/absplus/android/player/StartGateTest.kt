package app.absplus.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartGateTest {
  @Test fun aNewerStartReplacesTheOneInFlight() {
    val gate = StartGate()
    val resume = gate.begin(playWhenReady = true)
    val play = gate.begin(playWhenReady = true)
    assertNull("the resume's late answer prepares nothing", gate.admit(resume))
    assertEquals(true, gate.admit(play))
  }

  @Test fun closeOrServerSwitchDropsTheStartInFlight() {
    val gate = StartGate()
    val switch = gate.begin(playWhenReady = true)
    gate.invalidate()
    assertNull(gate.admit(switch))
  }

  @Test fun pauseDuringPreparationPreparesPaused() {
    val gate = StartGate()
    val resume = gate.begin(playWhenReady = true)
    gate.pause()
    assertEquals(false, gate.admit(resume))
    // The next start is not affected by an earlier pause
    assertEquals(true, gate.admit(gate.begin(playWhenReady = true)))
    assertEquals(false, gate.admit(gate.begin(playWhenReady = false)))
  }
}
