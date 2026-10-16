package app.absplus.android.data

/**
 * Auto download state of one podcast on one saved server.
 *
 * Ownership is explicit: only [autoDownloadedEpisodeIds] (episodes the auto download itself queued) may ever be removed
 * by automatic retention. [manualEpisodeIds] are episodes the user requested from the app; a manual request always
 * wins, also for an episode that auto download queued earlier. Episodes on the device that are in neither list (for
 * example downloads made before auto download existed) are treated as the user's own and are never removed.
 *
 * [retention] decides whether automatic retention runs for this podcast at all: [RETENTION_AUTO] (the default) removes
 * eligible auto-owned episodes outside the newest window, [RETENTION_KEEP_ALL] never removes anything automatically.
 * Selection of new episodes follows the newest window in both modes.
 */
data class PodcastAutoDownloadEntry(
  var enabled: String, // DEFAULT | ON | OFF
  var keepCount: Int?,
  var autoDownloadedEpisodeIds: MutableList<String>,
  var manualEpisodeIds: MutableList<String> = mutableListOf(),
  // Failed automatic attempts per episode; retried automatically only below AutoDownloadPolicy.MAX_AUTO_ATTEMPTS
  var failedAttempts: MutableMap<String, Int> = mutableMapOf(),
  var retention: String = RETENTION_AUTO // AUTO | KEEP_ALL
) {
  companion object {
    const val ENABLED_DEFAULT = "DEFAULT"
    const val ENABLED_ON = "ON"
    const val ENABLED_OFF = "OFF"
    const val RETENTION_AUTO = "AUTO"
    const val RETENTION_KEEP_ALL = "KEEP_ALL"
  }
}
