package app.absplus.android.player

import android.content.Intent
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Media-button cold start (S22 Phase 4): Play and Headset Hook act at ACTION_UP, so their DOWN must not
 * release the placeholder and stop the service before the UP arrives. These tests cover the decision
 * rules and start-id accounting; real foreground-service behavior is verified on a device (adb key events).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaButtonLifecycleTest {
  private val down = KeyEvent.ACTION_DOWN
  private val up = KeyEvent.ACTION_UP

  @Test
  fun playPauseFinishesAtDownSoItsDownMayReleaseThePlaceholder() {
    assertTrue(MediaButtonLifecycle.completesCommand(down, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    assertTrue(MediaButtonLifecycle.completesCommand(up, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
  }

  @Test
  fun playDownIsPendingAndOnlyItsUpFinishes() {
    assertFalse(MediaButtonLifecycle.completesCommand(down, KeyEvent.KEYCODE_MEDIA_PLAY))
    assertTrue(MediaButtonLifecycle.completesCommand(up, KeyEvent.KEYCODE_MEDIA_PLAY))
  }

  @Test
  fun headsetHookDownIsPendingAndOnlyItsUpFinishes() {
    assertFalse(MediaButtonLifecycle.completesCommand(down, KeyEvent.KEYCODE_HEADSETHOOK))
    assertTrue(MediaButtonLifecycle.completesCommand(up, KeyEvent.KEYCODE_HEADSETHOOK))
  }

  @Test
  fun everyKeyThatActsAtUpWaitsForItsUp() {
    listOf(
      KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP
    ).forEach { assertFalse("DOWN of $it", MediaButtonLifecycle.completesCommand(down, it)) }
    listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)
      .forEach { assertTrue("DOWN of $it", MediaButtonLifecycle.completesCommand(down, it)) }
  }

  @Test
  fun anUpWithoutADownOrAMissingKeyEventCountsAsFinished() {
    // UP alone (no preceding DOWN) still finishes: the placeholder never waits on it
    assertTrue(MediaButtonLifecycle.completesCommand(up, KeyEvent.KEYCODE_MEDIA_PLAY))
    // MediaButtonReceiver intent without a key event: nothing further is coming
    assertTrue(MediaButtonLifecycle.completesCommand(null, null))
  }

  @Test
  fun missingUpIsBoundedByAGracePeriodWellInsideTheForegroundDeadline() {
    // The placeholder is already foreground, so this only bounds how long an unused service lingers
    assertTrue(MediaButtonLifecycle.KEY_UP_GRACE_MS in 1000L..10000L)
  }

  @Test
  fun aPlainStartWithNothingPreparedAndNoForegroundIsAnOrphanForegroundStart() {
    assertTrue(MediaButtonLifecycle.isOrphanForegroundStart(Intent(), hasPreparedSession = false, isRestoringPlayback = false, isForeground = false))
  }

  @Test
  fun preparedRestoringForegroundMediaButtonOrStickyStartsAreNotOrphans() {
    // preparePlayer sets the session before the start command arrives
    assertFalse(MediaButtonLifecycle.isOrphanForegroundStart(Intent(), hasPreparedSession = true, isRestoringPlayback = false, isForeground = false))
    assertFalse(MediaButtonLifecycle.isOrphanForegroundStart(Intent(), hasPreparedSession = false, isRestoringPlayback = true, isForeground = false))
    assertFalse(MediaButtonLifecycle.isOrphanForegroundStart(Intent(), hasPreparedSession = false, isRestoringPlayback = false, isForeground = true))
    // Media buttons have their own placeholder path; a null intent is the sticky restart (StickyRestart)
    assertFalse(MediaButtonLifecycle.isOrphanForegroundStart(Intent(Intent.ACTION_MEDIA_BUTTON), hasPreparedSession = false, isRestoringPlayback = false, isForeground = false))
    assertFalse(MediaButtonLifecycle.isOrphanForegroundStart(null, hasPreparedSession = false, isRestoringPlayback = false, isForeground = false))
  }

  /** Models Android's rule: stopSelfResult(id) stops only when id is the newest start the system issued. */
  private class FakeSystem {
    var issuedStartId = 0
    val stopRequests = mutableListOf<Int>()
    fun issueStart(): Int = ++issuedStartId
    fun stopSelfResult(id: Int): Boolean {
      stopRequests += id
      return id == issuedStartId
    }
  }

  @Test
  fun stopForAnOlderStartKeepsTheServiceWhenTheUpStartIsAlreadyQueued() {
    // DOWN start delivered; the UP start is issued by the system but not delivered yet
    val system = FakeSystem()
    val stopper = ServiceStopper(system::stopSelfResult)
    stopper.onStartCommand(system.issueStart())
    system.issueStart()
    assertFalse(stopper.stop())
    assertFalse(stopper.isStopPending)
  }

  @Test
  fun stopForTheLatestStartStopsAndMarksTheInstanceAsGoing() {
    val system = FakeSystem()
    val stopper = ServiceStopper(system::stopSelfResult)
    stopper.onStartCommand(system.issueStart())
    stopper.onStartCommand(system.issueStart())
    assertTrue(stopper.stop())
    assertTrue(stopper.isStopPending)
    assertEquals(listOf(2), system.stopRequests)
  }

  @Test
  fun aStartAfterAStopTheBindingOutlivedIsNotGoingAway() {
    val system = FakeSystem()
    val stopper = ServiceStopper(system::stopSelfResult)
    stopper.onStartCommand(system.issueStart())
    assertTrue(stopper.stop())
    stopper.onStartCommand(system.issueStart())
    assertFalse(stopper.isStopPending)
  }

  @Test
  fun repeatedRapidPressesOnlyStopAfterTheLastDeliveredStart() {
    val system = FakeSystem()
    val stopper = ServiceStopper(system::stopSelfResult)
    // Three presses = six starts; a release after the 4th delivery must not stop while 5 and 6 are queued
    repeat(4) { stopper.onStartCommand(system.issueStart()) }
    system.issueStart(); system.issueStart()
    assertFalse(stopper.stop())
    stopper.onStartCommand(5); stopper.onStartCommand(6)
    assertTrue(stopper.stop())
  }
}
