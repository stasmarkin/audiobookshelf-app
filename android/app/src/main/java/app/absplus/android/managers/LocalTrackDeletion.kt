package app.absplus.android.managers

import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.Podcast

/**
 * Manual deletion of one downloaded track (R29-N02). Records written before the collision fix can point two different
 * podcast episodes at one file; deleting that file for one episode would take the other episode's audio and record too.
 */
object LocalTrackDeletion {
  enum class Outcome {
    /** The file belonged to this track alone (or to duplicate entries of the same episode): file and records removed. */
    DELETED,
    /** The file is shared with another episode: only the requested episode's record was removed, the file kept. */
    RECORD_ONLY,
    /** The file is shared with another episode and the caller did not say which episode to remove: nothing changed. */
    REFUSED_SHARED,
    /** The file could not be deleted: nothing changed. */
    FILE_NOT_DELETED
  }

  /**
   * Applies the deletion to [item] (the caller saves it for DELETED and RECORD_ONLY). [deleteFile] is only called when
   * no other episode uses the file.
   */
  fun delete(item: LocalLibraryItem, trackLocalFileId: String, localEpisodeId: String?, deleteFile: () -> Boolean): Outcome {
    val podcast = item.media as? Podcast
    if (podcast != null) {
      val users = podcast.episodes.orEmpty().filter { it.audioTrack?.localFileId == trackLocalFileId }
      // Different episodes on one file (duplicates of one episode are the same owner); unknown ids count as distinct
      val owners = users.map { it.serverEpisodeId ?: "local:${it.id}" }.toSet()
      if (owners.size > 1) {
        val requested = users.firstOrNull { it.id == localEpisodeId } ?: return Outcome.REFUSED_SHARED
        val owner = requested.serverEpisodeId ?: "local:${requested.id}"
        podcast.episodes?.removeIf { (it.serverEpisodeId ?: "local:${it.id}") == owner && it.audioTrack?.localFileId == trackLocalFileId }
        podcast.episodes?.forEachIndexed { i, episode -> episode.index = i + 1 }
        return Outcome.RECORD_ONLY
      }
    }
    if (!deleteFile()) return Outcome.FILE_NOT_DELETED
    item.media.removeAudioTrack(trackLocalFileId)
    item.removeLocalFile(trackLocalFileId)
    return Outcome.DELETED
  }
}
