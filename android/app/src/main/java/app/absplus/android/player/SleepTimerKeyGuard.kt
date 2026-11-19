package app.absplus.android.player

import android.view.KeyEvent

/**
 * In the sleep timer's warning window a headset media key extends the timer and does nothing else.
 * Every event of that press and any press within the multi-click timeout after it are consumed too,
 * so a double or triple click neither pauses nor seeks.
 */
internal class SleepTimerKeyGuard {
  private data class Press(val code: Int, val downTime: Long)
  private val swallowed = ArrayDeque<Press>()
  private var lastExtendAt: Long? = null

  fun intercept(event: KeyEvent, nowMs: Long, canExtend: () -> Boolean, extend: () -> Boolean): Boolean {
    if (event.keyCode !in mediaKeys) return false
    // The home widget's pending intents carry KeyEvent(action, code) with no times; the widget keeps its own actions
    if (event.downTime == 0L && event.eventTime == 0L) return false
    if (Press(event.keyCode, event.downTime) in swallowed) return true

    val extendedAt = lastExtendAt
    if (extendedAt != null && nowMs - extendedAt < SWALLOW_AFTER_EXTEND_MS) {
      swallow(event)
      return true
    }

    if (!canExtend()) return false
    // An UP whose DOWN came just before the window counts as the press only for keys that act at UP:
    // for the others that DOWN already acted
    val startsPress = event.action == KeyEvent.ACTION_DOWN || !MediaButtonLifecycle.actsOnKeyDown(event.keyCode)
    if (startsPress && event.repeatCount == 0 && !event.isCanceled) {
      if (!extend()) return false
      swallow(event)
      lastExtendAt = nowMs
    }
    // A canceled event or a hold repeat inside the window must not act as pause or navigation either
    return true
  }

  private fun swallow(event: KeyEvent) {
    swallowed.addLast(Press(event.keyCode, event.downTime))
    if (swallowed.size > 8) swallowed.removeFirst()
  }

  companion object {
    private const val SWALLOW_AFTER_EXTEND_MS = 1000L

    private val mediaKeys = setOf(
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND
    )
  }
}
