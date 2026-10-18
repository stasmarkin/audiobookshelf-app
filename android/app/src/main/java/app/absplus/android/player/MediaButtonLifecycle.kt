package app.absplus.android.player

import android.content.Intent
import android.view.KeyEvent

/**
 * Media-button lifecycle rules for the player service. MediaButtonReceiver turns every key press into
 * two startForegroundService calls, one for ACTION_DOWN and one for ACTION_UP. MediaSessionCallback acts
 * on Play/Pause, Fast Forward and Rewind at DOWN, but on every other key (Play, Pause, Headset Hook,
 * Next, Previous, Stop) only at UP. A DOWN for those keys has not finished the command, so the service
 * must stay alive for its UP instead of dropping the placeholder foreground and stopping.
 */
internal object MediaButtonLifecycle {
  /** How long a placeholder waits for the UP of a key that acts at UP before giving up (missing UP). */
  const val KEY_UP_GRACE_MS = 5000L

  fun actsOnKeyDown(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> true
    else -> false
  }

  /**
   * True when handling this key event finished a command, so an unused placeholder may be released.
   * False only for the DOWN of a key that acts at UP: its UP is still coming. A missing key event
   * finishes nothing further, so it counts as complete.
   */
  fun completesCommand(action: Int?, keyCode: Int?): Boolean {
    if (action == null || keyCode == null) return true
    return !(action == KeyEvent.ACTION_DOWN && !actsOnKeyDown(keyCode))
  }

  /**
   * True for a plain start (no action) that reached a service with nothing prepared or restoring and no
   * foreground yet. The app only starts the service that way from preparePlayer via startForegroundService,
   * so Android requires startForeground() even if the session it was meant for is already gone (for
   * example a start racing a service that was stopping). The service must satisfy that and stop.
   */
  fun isOrphanForegroundStart(intent: Intent?, hasPreparedSession: Boolean, isRestoringPlayback: Boolean, isForeground: Boolean): Boolean =
    intent != null && intent.action == null && !hasPreparedSession && !isRestoringPlayback && !isForeground
}

/**
 * Start-id accounting for stopping the service. A stop names the last start command this instance has
 * seen, so a start that Android has already queued (the UP of a key press) keeps the service alive
 * instead of being destroyed with it. Once a stop has been accepted, the instance is going away.
 */
internal class ServiceStopper(private val stopSelfResult: (Int) -> Boolean) {
  var lastStartId = 0
    private set
  var isStopPending = false
    private set

  fun onStartCommand(startId: Int) {
    lastStartId = startId
    // A stop the binding outlived (the app's activity holds the service) is undone by this start
    isStopPending = false
  }

  /** Asks Android to stop for the latest seen start; false means a newer start is pending and the service stays. */
  fun stop(): Boolean {
    val stopped = stopSelfResult(lastStartId)
    if (stopped) isStopPending = true
    return stopped
  }
}
