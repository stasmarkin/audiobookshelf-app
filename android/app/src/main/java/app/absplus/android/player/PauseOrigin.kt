package app.absplus.android.player

/**
 * Tells the service's own pauses from the user's in the player's playWhenReady events. ExoPlayer delivers the event of
 * a pause made inside one of its callbacks only after that callback returns, so the service marks the pause it is about
 * to make and drops the mark once the event had its chance to arrive ([settle], posted after it). Main thread.
 */
class PauseOrigin {
  private var selfPauses = 0

  /** Before the service pauses the player itself; a player that is not playing sends no event. */
  fun beforeSelfPause(playWhenReady: Boolean) {
    if (playWhenReady) selfPauses++
  }

  /** For a playWhenReady=false event: whether it is a pause from outside (user, headset, notification, noisy audio). */
  fun isUserPause(): Boolean {
    if (selfPauses == 0) return true
    selfPauses--
    return false
  }

  fun settle() {
    selfPauses = 0
  }
}
