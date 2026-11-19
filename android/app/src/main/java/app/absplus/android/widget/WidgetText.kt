package app.absplus.android.widget

import kotlin.math.roundToLong

/**
 * Snapshot progress/time text for the full widget. Values are fixed at the last widget update (play/pause,
 * session start, close): the widget never runs a timer or schedules updates to advance them.
 */
object WidgetText {
  const val PROGRESS_MAX = 1000

  data class Snapshot(val elapsed: String, val remaining: String, val progress: Int)

  /**
   * Null when position/duration are unknown or unusable (the readout is then hidden). The times are listening time at
   * [speed], as in the app's player; the bar is the share of the book.
   */
  fun snapshot(positionMs: Long?, durationMs: Long?, speed: Float = 1f): Snapshot? {
    if (positionMs == null || durationMs == null || durationMs <= 0 || positionMs < 0) return null
    val position = positionMs.coerceAtMost(durationMs)
    val progress = ((position.toDouble() / durationMs) * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
    val rate = if (speed.isFinite() && speed > 0f) speed.toDouble() else 1.0
    val listening = { ms: Long -> (ms / rate).roundToLong() }
    return Snapshot(elapsed = format(listening(position)), remaining = "-" + format(listening(durationMs - position)), progress = progress)
  }

  /** h:mm:ss, or m:ss under an hour (same shape as the app's timestamps). */
  fun format(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
  }
}
