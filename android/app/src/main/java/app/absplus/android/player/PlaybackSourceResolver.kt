package app.absplus.android.player

import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.db.userdata.MediaProgressEntity

/** Where an item plays from: a downloaded copy always wins, the server stream is the fallback. */
sealed interface PlaybackSource {
  data class Local(val item: LocalLibraryItem, val episode: PodcastEpisode?) : PlaybackSource
  data class Stream(val libraryItemId: String, val episodeId: String?) : PlaybackSource
  /** A copy whose files are gone: never streamed instead, the user downloads it again. */
  data class LocalBroken(val item: LocalLibraryItem) : PlaybackSource
  /** A `local_*` id (or its episode) that is not on the device: there is no server id to stream. */
  data class NotFound(val message: String) : PlaybackSource
}

class PlaybackSourceResolver(
        private val localItems: () -> List<LocalLibraryItem>,
        private val hasTracks: (LocalLibraryItem, PodcastEpisode?) -> Boolean
) {
  /**
   * [libraryItemId] is a server id or a device `local_*` id (Local Media, queues saved by older versions). For a server
   * id the copy must belong to [serverConfigId]: the same item of another account is not this user's download. A copy
   * matches by server id or by `ino` (a book downloaded through another library it also belongs to); an episode by
   * `serverEpisodeId`.
   */
  fun resolve(serverConfigId: String?, libraryItemId: String, episodeId: String?, ino: String? = null): PlaybackSource {
    if (libraryItemId.startsWith("local")) return resolveLocalId(libraryItemId, episodeId)
    if (serverConfigId == null) return PlaybackSource.Stream(libraryItemId, episodeId)
    val ownCopies = localItems().filter { it.serverConnectionConfigId == serverConfigId }
    val copies = ownCopies.filter { it.libraryItemId == libraryItemId } +
            ownCopies.filter { !ino.isNullOrEmpty() && it.ino == ino && it.libraryItemId != libraryItemId }
    if (copies.isEmpty()) return PlaybackSource.Stream(libraryItemId, episodeId)
    if (episodeId.isNullOrEmpty()) return localOrBroken(copies.map { it to null })
    val withEpisode = copies.mapNotNull { copy -> episodeOf(copy) { it.serverEpisodeId == episodeId }?.let { copy to it } }
    if (withEpisode.isEmpty()) return PlaybackSource.Stream(libraryItemId, episodeId)
    return localOrBroken(withEpisode)
  }

  private fun resolveLocalId(localId: String, episodeId: String?): PlaybackSource {
    val item = localItems().find { it.id == localId } ?: return PlaybackSource.NotFound("Local library item not found")
    if (episodeId.isNullOrEmpty()) return localOrBroken(listOf(item to null))
    val episode = episodeOf(item) { it.id == episodeId } ?: return PlaybackSource.NotFound("Podcast episode not found")
    return localOrBroken(listOf(item to episode))
  }

  private fun localOrBroken(candidates: List<Pair<LocalLibraryItem, PodcastEpisode?>>): PlaybackSource {
    val playable = candidates.firstOrNull { (item, episode) -> !item.isInvalid && hasTracks(item, episode) }
            ?: return PlaybackSource.LocalBroken(candidates.first().first)
    return PlaybackSource.Local(playable.first, playable.second)
  }

  private fun episodeOf(item: LocalLibraryItem, match: (PodcastEpisode) -> Boolean): PodcastEpisode? =
          (item.media as? Podcast)?.episodes?.find(match)
}

/** A position with the time it was written, from the Paper copy or the server. */
data class ProgressPoint(val currentTime: Double, val lastUpdate: Long, val isFinished: Boolean)

/**
 * The start position when none is given: the newer of the device's `media_progress` row and [other] (a tie goes to
 * [other]); a finished winner starts over. Null when neither exists.
 */
fun chooseStartTime(row: MediaProgressEntity?, other: ProgressPoint?): Double? {
  val rowPoint = row?.let { ProgressPoint(it.currentTime, it.lastUpdate, it.isFinished) }
  val winner = when {
    rowPoint == null -> other
    other == null -> rowPoint
    rowPoint.lastUpdate > other.lastUpdate -> rowPoint
    else -> other
  } ?: return null
  return if (winner.isFinished) 0.0 else winner.currentTime
}
