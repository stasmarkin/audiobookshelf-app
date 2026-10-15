package app.absplus.android.player

/**
 * Orders the starts that wait on something (a play request, media_progress, a resume): only the latest may prepare the
 * player. Close and a server switch drop the start in flight; a Pause meanwhile makes it prepare paused. Main thread.
 */
class StartGate {
  data class Ticket(val generation: Long, val playWhenReady: Boolean)

  private var generation = 0L
  private var pausedGeneration = -1L

  fun begin(playWhenReady: Boolean): Ticket = Ticket(++generation, playWhenReady)

  fun invalidate() {
    generation++
  }

  fun pause() {
    pausedGeneration = generation
  }

  /** The playWhenReady to prepare [ticket] with, or null when a newer start, Close or a server switch replaced it. */
  fun admit(ticket: Ticket): Boolean? {
    if (ticket.generation != generation) return null
    return ticket.playWhenReady && pausedGeneration != generation
  }
}
