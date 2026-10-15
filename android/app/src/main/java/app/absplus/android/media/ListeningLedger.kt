package app.absplus.android.media

import java.util.UUID

/**
 * Listening time of a stream the server has not taken through `/sync`, saved as a session of its own (never under the
 * server session's id, which `local-all` would overwrite). [timeListening] is the segment's total: `local-all` replaces
 * a session's time rather than adding to it.
 */
data class OfflineSegment(val id: String, val timeListening: Long, val startedAt: Long)

/**
 * Where each second of one streamed session goes: through `/sync` or into an [OfflineSegment], exactly one of them.
 * Ticks call it on the main thread, `/sync` answers on OkHttp threads; several requests may be in flight at once.
 * In memory only: a segment already saved survives a restart as an ordinary Paper session.
 */
class ListeningLedger(
        private val newId: () -> String = { UUID.randomUUID().toString() },
        private val clock: () -> Long = System::currentTimeMillis
) {
  private var unsent = 0L
  private var open: OfflineSegment? = null

  @Synchronized
  fun addListened(seconds: Long) {
    if (seconds > 0) unsent += seconds
  }

  /** Takes the time listened since the last attempt; the caller settles it with [ackServerSync] or [failServerSync]. */
  @Synchronized
  fun beginServerSync(): Long = unsent.also { unsent = 0 }

  /** The server took [sentSeconds]; the next failure starts a new segment. */
  @Synchronized
  fun ackServerSync(@Suppress("UNUSED_PARAMETER") sentSeconds: Long) {
    open = null
  }

  /** [sentSeconds] did not reach the server: they go into the open segment, opened now if there is none. */
  @Synchronized
  fun failServerSync(sentSeconds: Long): OfflineSegment {
    val seconds = maxOf(sentSeconds, 0)
    // Its listening began when the failed time did
    val segment = open ?: OfflineSegment(newId(), 0, clock() - seconds * 1000)
    return segment.copy(timeListening = segment.timeListening + seconds).also { open = it }
  }

  @Synchronized
  fun segment(): OfflineSegment? = open
}
