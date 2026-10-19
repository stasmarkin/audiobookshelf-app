package app.absplus.android.player

import app.absplus.android.data.Book
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.LibraryItemWrapper
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.PlaybackSession
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.managers.LocalItemLocks

/** Where an item plays from: a downloaded copy always wins, the server stream is the fallback. */
sealed interface PlaybackSource {
  data class Local(val item: LocalLibraryItem, val episode: PodcastEpisode?) : PlaybackSource
  data class Stream(val libraryItemId: String, val episodeId: String?) : PlaybackSource
  /** A copy whose files are gone: never streamed instead, the user downloads it again. */
  data class LocalBroken(val item: LocalLibraryItem) : PlaybackSource
  /** A copy on an SD card that is out: neither deleted nor streamed instead, it plays once the card is back. */
  data class StorageUnavailable(val item: LocalLibraryItem) : PlaybackSource
  /** A `local_*` id (or its episode) that is not on the device: there is no server id to stream. */
  data class NotFound(val message: String) : PlaybackSource
}

class PlaybackSourceResolver(
        private val localItems: () -> List<LocalLibraryItem>,
        /** One copy by its device id, without reading every copy. */
        private val localItem: (String) -> LocalLibraryItem?,
        private val storageAvailable: (LocalLibraryItem) -> Boolean = { true },
        private val hasTracks: (LocalLibraryItem, PodcastEpisode?) -> Boolean
) {
  /**
   * [libraryItemId] is a server id or a device `local_*` id (Local Media, queues saved by older versions). For a server
   * id the copy must belong to [serverConfigId]: the same item of another account is not this user's download. A copy
   * matches by server id or by `ino` (a book downloaded through another library it also belongs to); an episode by
   * `serverEpisodeId`. A book copy with fewer tracks than the server's [expectedTracks] is a partial download: it streams.
   */
  fun resolve(serverConfigId: String?, libraryItemId: String, episodeId: String?, ino: String? = null, expectedTracks: Int? = null): PlaybackSource {
    if (libraryItemId.startsWith("local")) return resolveLocalId(libraryItemId, episodeId)
    if (serverConfigId == null) return PlaybackSource.Stream(libraryItemId, episodeId)
    val ownCopies = localItems().filter { it.serverConnectionConfigId == serverConfigId && !isPartial(it, episodeId, expectedTracks) }
    val copies = ownCopies.filter { it.libraryItemId == libraryItemId } +
            ownCopies.filter { !ino.isNullOrEmpty() && it.ino == ino && it.libraryItemId != libraryItemId }
    if (copies.isEmpty()) return PlaybackSource.Stream(libraryItemId, episodeId)
    if (episodeId.isNullOrEmpty()) return localOrBroken(copies.map { it to null })
    val withEpisode = copies.mapNotNull { copy -> episodeOf(copy) { it.serverEpisodeId == episodeId }?.let { copy to it } }
    if (withEpisode.isEmpty()) return PlaybackSource.Stream(libraryItemId, episodeId)
    return localOrBroken(withEpisode)
  }

  /** What a saved session plays from after a restart: a stream downloaded since plays the copy. */
  fun restoreSource(session: PlaybackSession): PlaybackSource {
    if (session.isLocal) return resolveLocalId(session.localLibraryItemId, session.localEpisodeId)
    val libraryItemId = session.libraryItemId ?: return PlaybackSource.NotFound("Streamed session has no item")
    return resolve(session.serverConnectionConfigId, libraryItemId, session.episodeId, session.libraryItem?.ino)
  }

  private fun isPartial(copy: LocalLibraryItem, episodeId: String?, expectedTracks: Int?) =
          expectedTracks != null && episodeId.isNullOrEmpty() && copy.mediaType == "book" && copy.media.getAudioTracks().size < expectedTracks

  private fun resolveLocalId(localId: String, episodeId: String?): PlaybackSource {
    val item = localItem(localId) ?: return PlaybackSource.NotFound("Local library item not found")
    if (episodeId.isNullOrEmpty()) return localOrBroken(listOf(item to null))
    val episode = episodeOf(item) { it.id == episodeId } ?: return PlaybackSource.NotFound("Podcast episode not found")
    return localOrBroken(listOf(item to episode))
  }

  private fun localOrBroken(candidates: List<Pair<LocalLibraryItem, PodcastEpisode?>>): PlaybackSource {
    val (onStorage, storageOut) = candidates.partition { (item, _) -> storageAvailable(item) }
    // Files on a removed card look missing: such a copy is not reported as broken
    val playable = onStorage.firstOrNull { (item, episode) -> !item.isInvalid && hasTracks(item, episode) }
            ?: return storageOut.firstOrNull()?.let { PlaybackSource.StorageUnavailable(it.first) }
                    ?: PlaybackSource.LocalBroken(candidates.first().first)
    return PlaybackSource.Local(playable.first, playable.second)
  }

  private fun episodeOf(item: LocalLibraryItem, match: (PodcastEpisode) -> Boolean): PodcastEpisode? =
          (item.media as? Podcast)?.episodes?.find(match)
}

/**
 * A request to start an item, from any start point: the app's Play, the queue, Android Auto, a resume after the server
 * came back, a switch to a finished download. [serverConfigId] null means the active server.
 */
data class StartRequest(
        val libraryItemId: String,
        val episodeId: String?,
        val ino: String? = null,
        val serverConfigId: String? = null,
        /** Explicit position; null takes the newer of `media_progress` and the source's own (see [chooseStartTime]). */
        val startTime: Double? = null,
        val playWhenReady: Boolean,
        val playbackRate: Float? = null,
        /** Stream even when downloaded: a cast device cannot play a file on the phone. */
        val forceStream: Boolean = false,
        /** The server's track count of a book, when known: a copy with fewer tracks is partial and streams. */
        val expectedTracks: Int? = null,
        /** Take the start position from the stream still playing, read just before the file replaces it. */
        val followPlayer: Boolean = false,
        /** A stream without the server is refused at once (Android Auto), instead of a play request that fails or times out. */
        val offlineFailsFast: Boolean = false
) {
  companion object {
    fun forQueueItem(item: PlayerNotificationService.PlaylistQueueItem, playWhenReady: Boolean, playbackRate: Float? = null) =
            StartRequest(item.libraryItemId, item.episodeId, item.ino, item.serverConnectionConfigId,
                    playWhenReady = playWhenReady, playbackRate = playbackRate, expectedTracks = item.expectedTracks)

    /** An Android Auto browse-tree item: a server item (with its server episode) or a device copy. */
    fun forWrapper(wrapper: LibraryItemWrapper, episode: PodcastEpisode?, playWhenReady: Boolean, playbackRate: Float?) =
            StartRequest(wrapper.id, episode?.id, (wrapper as? LibraryItem)?.ino, playWhenReady = playWhenReady,
                    playbackRate = playbackRate, expectedTracks = (wrapper as? LibraryItem)?.serverTrackCount(),
                    offlineFailsFast = true)
  }
}

/** A server book's track count, by which a start and the Android Auto download mark both tell a partial copy. */
fun LibraryItem.serverTrackCount(): Int? = (media as? Book)?.let { book -> book.numTracks?.takeIf { it > 0 } ?: book.tracks?.size?.takeIf { it > 0 } }

/**
 * What [request] plays from. A forced stream of a `local_*` id streams the copy's server item when the copy belongs to
 * the active server (the stream comes from it); another server's copy stays a file.
 */
fun planStart(request: StartRequest, activeServerConfigId: String?, resolver: PlaybackSourceResolver): PlaybackSource {
  val isLocalId = request.libraryItemId.startsWith("local")
  if (request.forceStream && !isLocalId) return PlaybackSource.Stream(request.libraryItemId, request.episodeId)
  val source = resolver.resolve(request.serverConfigId ?: activeServerConfigId, request.libraryItemId, request.episodeId, request.ino, request.expectedTracks)
  if (!request.forceStream) return source
  return when (source) {
    is PlaybackSource.Local -> source.item.libraryItemId
            ?.takeIf { source.item.serverConnectionConfigId != null && source.item.serverConnectionConfigId == activeServerConfigId }
            ?.let { PlaybackSource.Stream(it, source.episode?.serverEpisodeId) } ?: source
    else -> source
  }
}

/**
 * [plan]'s copy read again under its item lock and pinned for playback when it can play; otherwise why it cannot, and
 * the previous pin stays.
 */
fun pinLocalSource(
        plan: PlaybackSource.Local,
        read: (String) -> LocalLibraryItem?,
        availability: (LocalLibraryItem, PodcastEpisode?) -> LocalAvailability
): PlaybackSource {
  var source: PlaybackSource = PlaybackSource.NotFound("Local library item not found")
  LocalItemLocks.readForPlayback(plan.item.id, { read(plan.item.id) }) { item ->
    val episode = plan.episode?.let { wanted -> (item.media as? Podcast)?.episodes?.find { it.id == wanted.id } }
    source = if (plan.episode != null && episode == null) {
      PlaybackSource.NotFound("Podcast episode not found")
    } else {
      when (availability(item, episode)) {
        LocalAvailability.OK -> PlaybackSource.Local(item, episode)
        LocalAvailability.STORAGE_UNAVAILABLE -> PlaybackSource.StorageUnavailable(item)
        LocalAvailability.NO_FILES -> PlaybackSource.LocalBroken(item)
      }
    }
    source is PlaybackSource.Local
  }
  return source
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

/**
 * The start of a downloaded copy: the newest of its own row, its Paper progress and, for a copy the resolver found by
 * `ino` for another server item, that requested item's row ([requestedRow]; listened to under that id).
 */
fun chooseLocalStartTime(copyRow: MediaProgressEntity?, requestedRow: MediaProgressEntity?, paper: ProgressPoint?): Double? {
  val row = if (requestedRow != null && (copyRow == null || requestedRow.lastUpdate > copyRow.lastUpdate)) requestedRow else copyRow
  return chooseStartTime(row, paper)
}
