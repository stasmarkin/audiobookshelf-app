package app.absplus.android.player

/**
 * Orders the starts that wait on something (a play request, media_progress, a resume): only the latest may prepare the
 * player. Close and a server switch drop the start in flight; a Pause meanwhile makes it prepare paused. Main thread.
 */
class StartGate {
  data class Ticket(val generation: Long, val playWhenReady: Boolean)

  private var generation = 0L
  // The user's last Pause or Play during the start of this generation
  private var intentGeneration = -1L
  private var intentPlay = false

  fun begin(playWhenReady: Boolean): Ticket = Ticket(++generation, playWhenReady)

  fun invalidate() {
    generation++
  }

  fun pause() {
    intentGeneration = generation
    intentPlay = false
  }

  /** A Play while the start is in flight: it prepares playing even if it began paused. */
  fun play() {
    intentGeneration = generation
    intentPlay = true
  }

  /** The playWhenReady to prepare [ticket] with, or null when a newer start, Close or a server switch replaced it. */
  fun admit(ticket: Ticket): Boolean? {
    if (ticket.generation != generation) return null
    return if (intentGeneration == generation) intentPlay else ticket.playWhenReady
  }

  /** [admit] again after a wait, keeping a Pause made during it; [before]: what the first admit said. */
  fun admitAgain(ticket: Ticket, before: Boolean): Boolean? = admit(ticket)?.let { before && it }
}
