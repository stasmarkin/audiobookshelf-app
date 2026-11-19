package app.absplus.android.managers

import kotlin.math.roundToInt

/** The last seconds of a sleep timer: a chime once per entry, and whether a headset press may extend the timer. */
class SleepTimerWarning {
  private var chimePlayed = false

  fun reset() {
    chimePlayed = false
  }

  /** @return true when the chime should play on this tick; it only promises an extension that a press can give. */
  fun onTick(enabled: Boolean, elapsed: Long, remaining: Int, extendable: () -> Boolean): Boolean {
    if (!inWindow(remaining)) {
      chimePlayed = false
      return false
    }
    // elapsed is already one tick in on the first tick after a reset, so this skips that tick
    if (!enabled || elapsed <= 1000L || chimePlayed || !extendable()) return false
    chimePlayed = true
    return true
  }

  fun canExtend(enabled: Boolean, running: Boolean, playing: Boolean, remaining: Int, isDuration: Boolean, length: Long, chapterEnd: () -> Long?): Boolean {
    if (!enabled || !running || !playing || !inWindow(remaining)) return false
    return if (isDuration) durationExtendable(length) else chapterEnd() != null
  }

  companion object {
    const val SLEEP_TIMER_WARNING_SECONDS = 15

    fun remainingSeconds(durationLength: Long, elapsed: Long): Int =
      ((durationLength - elapsed) / 1000).toDouble().roundToInt()

    fun remainingSecondsTo(endMs: Long, nowMs: Long, speed: Float): Int =
      (((endMs - nowMs) / 1000).toDouble() / speed).roundToInt()

    fun inWindow(remaining: Int) = remaining in 1..SLEEP_TIMER_WARNING_SECONDS

    // After a reset and the first tick the timer must be outside the window again, or it chimes in a loop
    fun durationExtendable(length: Long) = (length - 1000L) / 1000L > SLEEP_TIMER_WARNING_SECONDS

    /** End of "one more chapter" from the warning window, or null when there is nowhere outside the window to go. */
    fun chapterExtensionEnd(currentChapterEnd: Long?, nextChapterEnd: Long?, timerEnd: Long, duration: Long, nowMs: Long, speed: Float): Long? {
      var candidate = currentChapterEnd
      if (candidate == null || candidate <= timerEnd || remainingSecondsTo(candidate, nowMs, speed) <= SLEEP_TIMER_WARNING_SECONDS) {
        candidate = nextChapterEnd
      }
      if (candidate == null) return null
      val end = minOf(candidate, duration)
      if (end <= timerEnd || remainingSecondsTo(end, nowMs, speed) <= SLEEP_TIMER_WARNING_SECONDS) return null
      return end
    }
  }
}
