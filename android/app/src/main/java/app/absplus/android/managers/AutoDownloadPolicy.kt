package app.absplus.android.managers

import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.DownloadUsingCellularSetting
import app.absplus.android.data.PodcastAutoDownloadEntry
import app.absplus.android.services.DownloadServiceHost

/** Pure decisions of podcast auto download, kept apart from Android and network code for tests. */
object AutoDownloadPolicy {
  /** Automatic attempts per episode; after that only a manual download (or Retry) tries again. */
  const val MAX_AUTO_ATTEMPTS = 3

  enum class EpisodeOutcome { QUEUED, SKIPPED, FAILED }

  // A missing SD card is not a failure: the episode is retried on a later check once the card is back
  fun outcomeFor(error: String?): EpisodeOutcome =
          when (error) {
            null, DownloadServiceHost.SERVICE_START_FAILED_MESSAGE -> EpisodeOutcome.QUEUED
            LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE -> EpisodeOutcome.SKIPPED
            else -> EpisodeOutcome.FAILED
          }

  // Auto download has no UI to ask in, so anything but "Always" for cellular means unmetered only
  fun requiresUnmeteredNetwork(settings: DeviceSettings): Boolean =
          settings.autoDownloadWifiOnly || settings.downloadUsingCellular != DownloadUsingCellularSetting.ALWAYS

  /** Network/power state the transfer gate decides on. */
  data class Conditions(val connected: Boolean, val unmetered: Boolean, val charging: Boolean)

  /**
   * Whether auto-owned transfers may run now: never offline, never on a metered network while unmetered-only applies,
   * never off the charger while charging-only is set. Manual downloads are not subject to this.
   */
  fun transfersAllowed(settings: DeviceSettings, conditions: Conditions): Boolean = blockedReason(settings, conditions) == null

  /** The first condition that stops auto-owned transfers now: "network" (offline), "wifi" (metered network), "charging"; null when none. */
  fun blockedReason(settings: DeviceSettings, conditions: Conditions): String? {
    if (!conditions.connected) return "network"
    if (requiresUnmeteredNetwork(settings) && !conditions.unmetered) return "wifi"
    if (settings.autoDownloadChargingOnly && !conditions.charging) return "charging"
    return null
  }

  fun isEnabledFor(entry: PodcastAutoDownloadEntry, settings: DeviceSettings): Boolean =
          entry.enabled == PodcastAutoDownloadEntry.ENABLED_ON ||
                  (entry.enabled == PodcastAutoDownloadEntry.ENABLED_DEFAULT && settings.autoDownloadAllPodcasts)

  /** Whether automatic retention may remove anything of this podcast ("Automatically manage downloads"). */
  fun cleansUp(entry: PodcastAutoDownloadEntry): Boolean = entry.retention == PodcastAutoDownloadEntry.RETENTION_AUTO

  /**
   * Whether a check runs retention for a podcast: never in the check that follows a settings change, and only when the
   * podcast was managed both when the check started and now, so switching back from "Keep all downloads" while a check
   * runs removes nothing until the next regular check. Each removal is checked once more as it happens.
   */
  fun retentionRuns(trigger: AutoDownloadManager.Trigger, atCheckStart: PodcastAutoDownloadEntry, now: PodcastAutoDownloadEntry): Boolean =
          trigger != AutoDownloadManager.Trigger.SETTINGS && cleansUp(atCheckStart) && cleansUp(now)

  fun keepCountFor(entry: PodcastAutoDownloadEntry, settings: DeviceSettings): Int =
          entry.keepCount ?: settings.autoDownloadKeepCount

  /** The [keepCount] newest candidates; items without a publish date count as the oldest. */
  fun <T> newestWindow(items: List<T>, keepCount: Int, publishedAt: (T) -> Long?, isCandidate: (T) -> Boolean): List<T> {
    if (keepCount <= 0) return emptyList()
    return items.filter(isCandidate).sortedWith(compareByDescending(nullsFirst<Long>(), publishedAt)).take(keepCount)
  }

  /** What an auto check does with one episode of the newest window. */
  enum class WindowAction { ENQUEUE, RETRY, SKIP }

  /**
   * The device's copy of an episode. An EMPTY copy (0-byte or missing file) is not a valid download. It is still left
   * alone: downloading it again would add a second local record for the episode, so the user deletes it.
   */
  enum class LocalCopy { NONE, VALID, EMPTY }

  fun localCopyOf(fileLength: Long?): LocalCopy = when {
    fileLength == null -> LocalCopy.NONE
    fileLength <= 0L -> LocalCopy.EMPTY
    else -> LocalCopy.VALID
  }

  /**
   * Decision for an episode inside the window. Only auto-owned work is ever retried, and only below
   * [MAX_AUTO_ATTEMPTS]; manual claims and episodes already on the device are left alone.
   *
   * @param queued the download queue's state for this episode
   * @param onDevice a local copy (any origin) exists for this server
   */
  fun windowAction(entry: PodcastAutoDownloadEntry, episodeId: String, queued: DownloadItemManager.QueuedState, onDevice: Boolean): WindowAction =
          windowAction(entry, episodeId, queued, if (onDevice) LocalCopy.VALID else LocalCopy.NONE)

  fun windowAction(entry: PodcastAutoDownloadEntry, episodeId: String, queued: DownloadItemManager.QueuedState, local: LocalCopy): WindowAction {
    val onDevice = local != LocalCopy.NONE
    if (episodeId in entry.manualEpisodeIds) return WindowAction.SKIP
    val owned = episodeId in entry.autoDownloadedEpisodeIds
    val attempts = entry.failedAttempts[episodeId] ?: 0
    return when (queued) {
      DownloadItemManager.QueuedState.PENDING -> WindowAction.SKIP
      // Its SD card is away: the queued download and its files wait for the card, nothing is retried or re-queued
      DownloadItemManager.QueuedState.STORAGE_UNAVAILABLE -> WindowAction.SKIP
      DownloadItemManager.QueuedState.FAILED ->
              if (owned && attempts < MAX_AUTO_ATTEMPTS) WindowAction.RETRY else WindowAction.SKIP
      DownloadItemManager.QueuedState.NOT_QUEUED -> when {
        onDevice -> WindowAction.SKIP
        // Owned but gone: a cleared failure is retried (bounded); one that completed and was deleted is not
        owned -> if (attempts in 1 until MAX_AUTO_ATTEMPTS) WindowAction.ENQUEUE else WindowAction.SKIP
        else -> WindowAction.ENQUEUE
      }
    }
  }

  /** A downloaded episode as retention sees it. */
  data class LocalEpisode(
          val serverEpisodeId: String,
          val inProgress: Boolean, // started and not finished (local or server progress)
          val isLastPlayed: Boolean, // the session currently loaded in / last used by the player
          val queued: Boolean,
          val emptyFile: Boolean = false // 0-byte or missing file: left for the user to delete (see LocalCopy)
  )

  /**
   * Episodes automatic retention may delete: auto-owned, not claimed manually, no longer in the newest window, still
   * listed by the server (missing metadata never causes deletion), not being listened to, not the player's session
   * and not in the download queue. Nothing at all under "Keep all downloads", and never an empty file.
   */
  fun retentionDeletions(
          entry: PodcastAutoDownloadEntry,
          windowIds: Set<String>,
          serverEpisodeIds: Set<String>,
          local: List<LocalEpisode>
  ): List<String> =
          if (!cleansUp(entry)) emptyList()
          else local.filter { ep ->
            ep.serverEpisodeId in entry.autoDownloadedEpisodeIds &&
                    ep.serverEpisodeId !in entry.manualEpisodeIds &&
                    ep.serverEpisodeId !in windowIds &&
                    ep.serverEpisodeId in serverEpisodeIds &&
                    !ep.inProgress && !ep.isLastPlayed && !ep.queued && !ep.emptyFile
          }.map { it.serverEpisodeId }
}
