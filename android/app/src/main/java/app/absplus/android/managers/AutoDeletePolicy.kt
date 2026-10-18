package app.absplus.android.managers

import app.absplus.android.data.DeviceSettings

/** Pure decisions of auto delete of finished books and the downloads limit, kept apart from Android and Paper for tests. */
object AutoDeletePolicy {
  data class Progress(val isFinished: Boolean, val finishedAt: Long?, val lastUpdate: Long, val ebookLocation: String? = null)

  /** Which server item, for which account, a device progress belongs to; a relink or an account switch changes it. */
  data class Identity(val serverConnectionConfigId: String?, val serverUserId: String?, val libraryItemId: String?)

  /** What an online pass saw on the server about a device progress, for later offline passes. */
  data class Confirmation(val identity: Identity, val lastUpdate: Long, val isFinished: Boolean)

  data class Candidate(
          val key: String,
          val sizeBytes: Long,
          val local: Progress?,
          val server: Progress?,
          // True only when the server's progress for this download was fetched in this pass
          val serverKnown: Boolean,
          val confirmed: Confirmation?,
          val hasPendingSession: Boolean,
          val isProtected: Boolean,
          val identity: Identity = Identity(null, null, null),
          // When this download finished; progress finished before that was copied from the server, not listened to
          val downloadedAt: Long? = null
  )

  data class FinishedState(val finished: Boolean, val finishedAt: Long?)

  data class SpaceSelection(val selected: List<Candidate>, val enough: Boolean)

  private const val DAY_MS = 24L * 60 * 60 * 1000
  private const val GB = 1024L * 1024 * 1024
  private val VALID_DELAY_DAYS = setOf(0, 1, 3, 7)
  private const val DEFAULT_DELAY_DAYS = 1
  private val VALID_LIMIT_GB = setOf(1, 2, 5, 10, 20)

  fun delayDays(settings: DeviceSettings): Int {
    val days = settings.autoDeleteDelayDays
    return if (days != null && days in VALID_DELAY_DAYS) days else DEFAULT_DELAY_DAYS
  }

  /** Null when the limit is off (or holds a value the UI never offers). */
  fun limitBytes(settings: DeviceSettings): Long? =
          if (settings.downloadsLimitGb in VALID_LIMIT_GB) settings.downloadsLimitGb * GB else null

  /** The more recently updated record wins (the server on a tie); without the server only the device's counts. */
  fun finishedState(local: Progress?, server: Progress?, serverKnown: Boolean): FinishedState {
    val winner =
            when {
              !serverKnown -> local
              local == null -> server
              server == null -> local
              local.lastUpdate > server.lastUpdate -> local
              else -> server
            } ?: return FinishedState(false, null)
    return FinishedState(winner.isFinished, winner.finishedAt ?: winner.lastUpdate)
  }

  /** Whether the device progress is already on the server, so dropping it with the download loses nothing. */
  fun isSyncSafe(c: Candidate): Boolean {
    if (c.hasPendingSession) return false
    val local = c.local ?: return true
    if (c.serverKnown) {
      val server = c.server ?: return false
      // Both finished never bumps lastUpdate on sync, so it counts, unless the device holds an ebook position the server lacks
      return server.lastUpdate >= local.lastUpdate ||
              (server.isFinished && local.isFinished && (local.ebookLocation == null || local.ebookLocation == server.ebookLocation))
    }
    // A confirmation of "not finished" (the server was newer) must not let a later offline pass delete a finished device record
    val confirmed = c.confirmed ?: return false
    return confirmed.identity == c.identity && confirmed.isFinished && confirmed.lastUpdate >= local.lastUpdate
  }

  /** An online pass that sees the device progress on the server records it for later offline passes. */
  fun shouldConfirm(c: Candidate): Boolean = c.serverKnown && c.local != null && isSyncSafe(c)

  fun confirmationFor(c: Candidate): Confirmation =
          Confirmation(c.identity, c.local!!.lastUpdate, finishedState(c.local, c.server, c.serverKnown).finished)

  /** Progress finished before the download itself finished came from the server with the file: wait for a new finish. */
  fun finishedAfterDownload(c: Candidate, state: FinishedState): Boolean =
          c.downloadedAt == null || (state.finishedAt != null && state.finishedAt > c.downloadedAt)

  fun isDue(finishedAt: Long, delayDays: Int, now: Long): Boolean = now - finishedAt >= delayDays * DAY_MS

  // Room for the limit is made by the same books and the same toggle: with it off nothing is deleted, only auto downloads are held back
  private fun isEligible(c: Candidate, settings: DeviceSettings): Boolean =
          settings.autoDeleteFinishedBooks && !c.isProtected && isSyncSafe(c) &&
                  finishedAfterDownload(c, finishedState(c.local, c.server, c.serverKnown))

  fun selectDue(candidates: List<Candidate>, settings: DeviceSettings, now: Long): List<Candidate> {
    val delay = delayDays(settings)
    return candidates.filter { c ->
      if (!isEligible(c, settings)) return@filter false
      val state = finishedState(c.local, c.server, c.serverKnown)
      state.finished && state.finishedAt != null && isDue(state.finishedAt, delay, now)
    }
  }

  /** Every candidate that may be deleted for room, longest finished first, ignoring the delay. */
  fun orderedForSpace(candidates: List<Candidate>, settings: DeviceSettings): List<Candidate> =
          candidates.filter { isEligible(it, settings) }
                  .map { it to finishedState(it.local, it.server, it.serverKnown) }
                  .filter { it.second.finished }
                  .sortedBy { it.second.finishedAt ?: Long.MAX_VALUE }
                  .map { it.first }

  /** The first of [orderedForSpace] that cover [bytesToFree], and whether they do. */
  fun selectForSpace(candidates: List<Candidate>, settings: DeviceSettings, bytesToFree: Long): SpaceSelection {
    if (bytesToFree <= 0) return SpaceSelection(emptyList(), true)
    val selected = mutableListOf<Candidate>()
    var freed = 0L
    for (c in orderedForSpace(candidates, settings)) {
      if (freed >= bytesToFree) break
      selected.add(c)
      freed += c.sizeBytes
    }
    return SpaceSelection(selected, freed >= bytesToFree)
  }
}
