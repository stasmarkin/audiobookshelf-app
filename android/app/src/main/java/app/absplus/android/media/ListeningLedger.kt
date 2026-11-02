package app.absplus.android.media

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Listening time of a stream the server has not taken through `/sync`, saved as a session of its own (never under the
 * server session's id, which `local-all` would overwrite). [timeListening] is the segment's total: `local-all` replaces
 * a session's time rather than adding to it.
 */
data class OfflineSegment(val id: String, val timeListening: Long, val startedAt: Long)

/**
 * Where each second of one streamed session goes: through `/sync` or into an [OfflineSegment], exactly one of them.
 * Ticks call it on the main thread, `/sync` answers on OkHttp threads; several requests may be in flight at once.
 * The time not settled yet ([pendingSegment]) is saved by the syncer, so a process that dies does not lose it.
 */
class ListeningLedger(
        private val newId: () -> String = { UUID.randomUUID().toString() },
        private val clock: () -> Long = System::currentTimeMillis
) {
  /** Where the syncer saves [pendingSegment]: one ledger, one key. */
  val key: String = UUID.randomUUID().toString().also { ofThisProcess.add(it) }

  private var unsent = 0L
  private var inFlight = 0L
  private var open: OfflineSegment? = null
  // The id the next segment takes, chosen early so a saved pending segment and the segment it becomes are one session
  private var nextId: String? = null

  @Synchronized
  fun addListened(seconds: Long) {
    if (seconds > 0) unsent += seconds
  }

  /** Takes the time listened since the last attempt; the caller settles it with [ackServerSync] or [failServerSync]. */
  @Synchronized
  fun beginServerSync(): Long = unsent.also {
    inFlight += it
    unsent = 0
  }

  /** The server took [sentSeconds]; the next failure starts a new segment. */
  @Synchronized
  fun ackServerSync(sentSeconds: Long) {
    inFlight = maxOf(inFlight - maxOf(sentSeconds, 0), 0)
    open = null
  }

  /**
   * [sentSeconds] did not reach the server: they go into the open segment, opened now if there is none. Null when
   * there is no segment and nothing to put in one.
   */
  @Synchronized
  fun failServerSync(sentSeconds: Long): OfflineSegment? {
    val seconds = maxOf(sentSeconds, 0)
    inFlight = maxOf(inFlight - seconds, 0)
    if (seconds == 0L && open == null) return null
    // Its listening began when the failed time did
    val segment = open ?: OfflineSegment(takeNextId(), 0, clock() - seconds * 1000)
    return segment.copy(timeListening = segment.timeListening + seconds).also { open = it }
  }

  @Synchronized
  fun segment(): OfflineSegment? = open

  /** The segment as it would be if all time not settled yet failed now; null when everything is settled. */
  @Synchronized
  fun pendingSegment(): OfflineSegment? {
    val unsettled = unsent + inFlight
    if (unsettled <= 0) return null
    val segment = open ?: OfflineSegment(nextId ?: newId().also { nextId = it }, 0, clock() - unsettled * 1000)
    return segment.copy(timeListening = segment.timeListening + unsettled)
  }

  private fun takeNextId(): String = nextId?.also { nextId = null } ?: newId()

  companion object {
    // A ledger of this process settles its own time when its answers come, also after the service that made it is gone
    private val ofThisProcess: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isOfThisProcess(key: String) = key in ofThisProcess

    internal fun forgetProcessForTest() = ofThisProcess.clear()
  }
}
