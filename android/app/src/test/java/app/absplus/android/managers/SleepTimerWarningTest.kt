package app.absplus.android.managers

import app.absplus.android.managers.SleepTimerWarning.Companion.chapterExtensionEnd
import app.absplus.android.managers.SleepTimerWarning.Companion.durationExtendable
import app.absplus.android.managers.SleepTimerWarning.Companion.inWindow
import app.absplus.android.managers.SleepTimerWarning.Companion.remainingSeconds
import app.absplus.android.managers.SleepTimerWarning.Companion.remainingSecondsTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerWarningTest {
  private fun chapterEnd(current: Long?, next: Long?, timerEnd: Long, duration: Long = 1_000_000L, now: Long = 0L) =
    chapterExtensionEnd(current, next, timerEnd, duration, now, 1f)

  @Test fun durationExtendableBoundary() {
    listOf(1000L, 15_001L, 15_900L, 16_999L).forEach { assertFalse("length $it", durationExtendable(it)) }
    assertTrue(durationExtendable(17_000L))
  }

  @Test fun inWindowBoundary() {
    assertFalse(inWindow(16))
    assertTrue(inWindow(15))
    assertTrue(inWindow(1))
    assertFalse(inWindow(0))
  }

  @Test fun remainingSecondsMatchesLegacyFormula() {
    assertEquals(14, remainingSeconds(900_000L, 885_100L))
    assertEquals(15, remainingSecondsTo(30_000L, 0L, 2f))
  }

  @Test fun chimeOncePerEntryAndAgainAfterLeaving() {
    val warning = SleepTimerWarning()
    fun tick(elapsed: Long, remaining: Int, enabled: Boolean = true, extendable: Boolean = true) =
      warning.onTick(enabled, elapsed, remaining) { extendable }
    assertFalse("first tick after a reset", tick(1000L, 15))
    assertTrue(tick(2000L, 15))
    assertFalse("already chimed in this entry", tick(3000L, 14))
    assertFalse("left the window", tick(4000L, 16))
    assertTrue("entered again", tick(5000L, 15))

    warning.reset()
    assertFalse("option off", tick(2000L, 10, enabled = false))
    warning.reset()
    assertTrue(tick(2000L, 10))
  }

  @Test fun noChimeWhenAPressCannotExtend() {
    val warning = SleepTimerWarning()
    assertFalse(warning.onTick(true, 2000L, 15) { false })
    assertTrue("chimes once the timer can be extended", warning.onTick(true, 3000L, 14) { true })
  }

  @Test fun canExtendComposition() {
    fun can(enabled: Boolean = true, running: Boolean = true, playing: Boolean = true, remaining: Int = 15,
            isDuration: Boolean = true, length: Long = 900_000L, chapterEnd: () -> Long? = { 400_000L }) =
      SleepTimerWarning().canExtend(enabled, running, playing, remaining, isDuration, length, chapterEnd)

    assertTrue(can())
    assertFalse(can(enabled = false))
    assertFalse(can(running = false))
    assertFalse(can(playing = false))
    assertFalse(can(remaining = 16))
    assertFalse(can(remaining = 0))
    assertFalse(can(length = 16_999L))
    assertFalse(can(isDuration = false, chapterEnd = { null }))
    assertTrue(can(isDuration = false, chapterEnd = { 400_000L }))
    var asked = false
    assertFalse(can(enabled = false, isDuration = false, chapterEnd = { asked = true; 400_000L }))
    assertFalse("chapter end is not looked up while the option is off", asked)
  }

  @Test fun chapterExtensionCases() {
    assertEquals(400_000L, chapterEnd(100_000L, 400_000L, timerEnd = 100_000L))
    assertEquals("end moved back by a manual minus", 100_000L, chapterEnd(100_000L, 400_000L, timerEnd = 50_000L))
    assertEquals("end moved forward by a manual plus", 400_000L, chapterEnd(100_000L, 400_000L, timerEnd = 150_000L))
    assertNull("no next chapter", chapterEnd(100_000L, null, timerEnd = 100_000L))
    assertEquals("clamped to the book", 300_000L, chapterEnd(100_000L, 400_000L, timerEnd = 100_000L, duration = 300_000L))
    assertNull("the extension itself lands in the window", chapterEnd(100_000L, 110_000L, timerEnd = 100_000L, now = 95_000L))
  }

  @Test fun chapterExtensionMatchesWindowRounding() {
    assertEquals(15, remainingSecondsTo(15_999L, 0L, 1f))
    assertEquals(200_000L, chapterEnd(15_999L, 200_000L, timerEnd = 15_000L))
    assertEquals(16_000L, chapterEnd(16_000L, 200_000L, timerEnd = 15_000L))
  }
}
