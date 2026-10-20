package app.absplus.android.player

import app.absplus.android.data.PlaybackSession
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem

/**
 * Whether this queue entry is what [session] plays. An entry names the server item (or, saved by an older version or
 * for Local Media, the device copy); the session may play either. A copy the resolver found by `ino` (downloaded
 * through another library) matches only for the same account. JS twin: `queueItemMatchesSession`.
 */
fun PlaylistQueueItem.matches(session: PlaybackSession): Boolean {
  val copy = session.localLibraryItem
  val serverItemId = copy?.libraryItemId ?: session.libraryItemId
  val episode = episodeId?.takeIf { it.isNotEmpty() }
  fun same(other: String?) = episode == other?.takeIf { it.isNotEmpty() }
  if (serverItemId != null && libraryItemId == serverItemId) return same(session.episodeId)
  if (copy != null && libraryItemId == copy.id) return same(session.localEpisodeId)
  if (copy != null && !ino.isNullOrEmpty() && ino == copy.ino &&
          serverConnectionConfigId != null && serverConnectionConfigId == copy.serverConnectionConfigId) {
    return same(session.episodeId)
  }
  return false
}
