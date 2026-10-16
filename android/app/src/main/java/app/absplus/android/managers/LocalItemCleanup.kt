package app.absplus.android.managers

import android.util.Log
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.Book
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.Podcast

/** Drops the files, tracks, episodes and cover of a saved local item that are gone from the device. */
object LocalItemCleanup {
  private const val TAG = "DbManager"

  /** Mutates [item] and returns how the store should apply the result. */
  fun clean(item: LocalLibraryItem, exists: (LocalFile) -> Boolean, skip: Boolean): ItemUpdate {
    // Files on a removed SD card are not deleted; cleaning now would erase the episodes and progress
    if (skip) return ItemUpdate.Keep
    var hasUpdates = false

    // Check local files
    item.localFiles =
            item.localFiles.filter { localFile ->
              val fileExists = exists(localFile)
              if (!fileExists) {
                Log.d(
                        TAG,
                        "cleanLocalLibraryItems: Local file ${localFile.absolutePath} was removed from library item ${item.media.metadata.title}"
                )
                hasUpdates = true
              }
              fileExists
            } as
                    MutableList<LocalFile>

    // Check audio tracks and episodes
    if (item.isPodcast) {
      val podcast = item.media as Podcast
      podcast.episodes =
              podcast.episodes?.filter { ep ->
                if (item.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } == null) {
                  Log.d(
                          TAG,
                          "cleanLocalLibraryItems: Podcast episode ${ep.title} was removed from library item ${item.media.metadata.title}"
                  )
                  hasUpdates = true
                }
                ep.audioTrack != null &&
                        item.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } != null
              } as
                      MutableList<PodcastEpisode>
    } else {
      val book = item.media as Book
      book.tracks =
              book.tracks?.filter { track ->
                if (item.localFiles.find { lf -> lf.id == track.localFileId } == null) {
                  Log.d(
                          TAG,
                          "cleanLocalLibraryItems: Audio track ${track.title} was removed from library item ${item.media.metadata.title}"
                  )
                  hasUpdates = true
                }
                item.localFiles.find { lf -> lf.id == track.localFileId } != null
              } as
                      MutableList<AudioTrack>
    }

    // Check cover still there
    item.coverAbsolutePath?.let {
      val coverExists = item.localFiles.any { localFile -> localFile.absolutePath == it && exists(localFile) }
      if (!coverExists) {
        Log.d(TAG, "cleanLocalLibraryItems: Cover $it was removed from library item ${item.media.metadata.title}")
        item.coverAbsolutePath = null
        item.coverContentUrl = null
        hasUpdates = true
      }
    }

    return if (item.serverConnectionConfigId == null) {
      // Local-only item support was removed in app version 0.9.67, remove any remaining local
      // only items beginning in 0.9.80
      Log.d(TAG, "cleanLocalLibraryItems: Local only item ${item.id} - removing from ABS")
      ItemUpdate.Delete
    } else if (hasUpdates) {
      Log.d(TAG, "cleanLocalLibraryItems: Saving local library item ${item.id}")
      ItemUpdate.Save(item)
    } else {
      ItemUpdate.Keep
    }
  }
}
