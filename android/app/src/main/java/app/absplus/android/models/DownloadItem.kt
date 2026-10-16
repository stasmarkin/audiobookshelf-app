package app.absplus.android.models

import app.absplus.android.data.LocalFolder
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.MediaType
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.device.AppStorage
import com.fasterxml.jackson.annotation.JsonIgnore

data class DownloadItem(
        val id: String,
        val libraryItemId: String,
        val episodeId: String?,
        val userMediaProgress: MediaProgress?,
        val serverConnectionConfigId: String,
        val serverAddress: String,
        val serverUserId: String,
        val mediaType: String,
        val itemFolderPath: String,
        val localFolder: LocalFolder,
        val itemTitle: String,
        val itemSubfolder: String,
        val media: MediaType,
        val downloadItemParts: MutableList<DownloadItemPart>,
        val ino: String? = null,
        @JsonIgnore var terminalFailureAt: Long? = null,
        @JsonIgnore var stagingCleanupAt: Long? = null
) {
  @get:JsonIgnore
  val isAppStorage
    get() = AppStorage.isAppStorage(localFolder.id)

  @get:JsonIgnore
  val isDownloadFinished
    get() = downloadItemParts.isNotEmpty() && downloadItemParts.all {
      it.completed && it.moved && !it.isMoving && !it.failed
    }

  /** Only the podcast's metadata is read from a queued episode ([MediaType.getLocalCopy]); every episode in every queued item runs out of memory. */
  @JsonIgnore
  fun withoutPodcastEpisodes(): DownloadItem {
    val podcast = media as? Podcast ?: return this
    if (podcast.episodes.isNullOrEmpty()) return this
    val compactMedia =
            Podcast(
                    podcast.metadata as PodcastMetadata,
                    podcast.coverPath,
                    podcast.tags,
                    mutableListOf(),
                    podcast.autoDownloadEpisodes,
                    podcast.numEpisodes
            )
    return copy(media = compactMedia)
  }

  @JsonIgnore
  fun getNextDownloadItemParts(limit: Int): MutableList<DownloadItemPart> {
    val itemParts = mutableListOf<DownloadItemPart>()
    if (limit == 0) return itemParts

    for (it in downloadItemParts) {
      if (!it.completed && !it.failed && it.downloadId == null) {
        itemParts.add(it)
        if (itemParts.size >= limit) break
      }
    }

    return itemParts
  }
}
