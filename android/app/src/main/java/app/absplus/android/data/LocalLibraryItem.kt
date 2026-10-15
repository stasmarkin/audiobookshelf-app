package app.absplus.android.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import androidx.media.utils.MediaConstants
import app.absplus.android.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import app.absplus.android.player.CoverArt
import app.absplus.android.player.PLAYMETHOD_LOCAL
import java.io.File
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
class LocalLibraryItem(
  id:String,
  var folderId:String,
  var basePath:String,
  var absolutePath:String,
  var contentUrl:String,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaType,
  var localFiles:MutableList<LocalFile>,
  var coverContentUrl:String?,
  var coverAbsolutePath:String?,
  var isLocal:Boolean,
  // If local library item is linked to a server item
  var serverConnectionConfigId:String?,
  var serverAddress:String?,
  var serverUserId:String?,
  var libraryItemId:String?,
  var ino:String? = null
  ) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = media.metadata.title
  @get:JsonIgnore
  val authorName get() = media.metadata.getAuthorDisplayName()
  @get:JsonIgnore
  val isPodcast get() = mediaType == "podcast"

  @JsonIgnore
  fun getCoverUri(ctx:Context): Uri {
    return coverContentUrl?.let { CoverArt.localCoverUri(ctx, it) } ?: CoverArt.defaultUri()
  }

  @JsonIgnore
  fun getDuration():Double {
    var total = 0.0
    val audioTracks = media.getAudioTracks()
    audioTracks.forEach{ total += it.duration }
    return total
  }

  @JsonIgnore
  fun updateFromScan(audioTracks:MutableList<AudioTrack>, _localFiles:MutableList<LocalFile>) {
    localFiles = _localFiles
    media.setAudioTracks(audioTracks)

    if (coverContentUrl != null) {
      if (localFiles.find { it.contentUrl == coverContentUrl } == null) {
        // Cover was removed
        coverContentUrl = null
        coverAbsolutePath = null
        media.coverPath = null
      }
    }
  }

  @JsonIgnore
  fun hasTracks(ctx: Context, episode:PodcastEpisode?): Boolean {
    var audioTracks = media.getAudioTracks() as MutableList<AudioTrack>
    if (episode != null) { // Get podcast episode audio track
      episode.audioTrack?.let { at -> mutableListOf(at) }?.let { tracks -> audioTracks = tracks }
    }
    if (audioTracks.size == 0) return false
    audioTracks.forEach {
      // Check that metadata is not null
      if (it.metadata === null) {
        return false
      }
      if (!trackExists(ctx, it.contentUrl, it.metadata!!.path)) {
        return false
      }
    }
    return true
  }

  @JsonIgnore
  private fun trackExists(ctx: Context, contentUrl: String?, path: String): Boolean {
    return LocalFile("", null, contentUrl ?: "", "", path, null, 0).exists(ctx)
  }

  /** [startTimeOverride] is the position the caller chose (see chooseStartTime); null takes this copy's Paper progress. */
  @JsonIgnore
  fun getPlaybackSession(episode:PodcastEpisode?, deviceInfo:DeviceInfo, startTimeOverride:Double? = null):PlaybackSession {
    val localEpisodeId = episode?.id
    val sessionId = "${UUID.randomUUID()}"

    val currentTime = startTimeOverride ?: run {
      val mediaProgress = DeviceManager.dbManager.getLocalMediaProgress(localMediaProgressId(episode))
      if (mediaProgress?.isFinished == true) 0.0 else mediaProgress?.currentTime ?: 0.0
    }


    val mediaMetadata = media.metadata
    var chapters = if (mediaType == "book") (media as Book).chapters else mutableListOf()
    var audioTracks = media.getAudioTracks() as MutableList<AudioTrack>
    val authorName = mediaMetadata.getAuthorDisplayName()
    val displayTitle = episode?.title ?: mediaMetadata.title
    var duration = getDuration()
    if (episode != null) { // Get podcast episode audio track
      episode.audioTrack?.let { at -> mutableListOf(at) }?.let { tracks -> audioTracks = tracks }
      chapters = episode.chapters
      duration = episode.audioTrack?.duration ?: 0.0
      Log.d("LocalLibraryItem", "getPlaybackSession: Got podcast episode audio track ${audioTracks.size}")
    }

    val dateNow = System.currentTimeMillis()
    return PlaybackSession(sessionId,serverUserId,libraryItemId,episode?.serverEpisodeId, mediaType, mediaMetadata, deviceInfo,chapters ?: mutableListOf(), displayTitle, authorName,null,duration,PLAYMETHOD_LOCAL,dateNow,0L,0L, audioTracks,currentTime,null,this,localEpisodeId,serverConnectionConfigId, serverAddress, "exo-player")
  }

  /** The Paper LocalMediaProgress id of this copy (and [episode]). */
  @JsonIgnore
  fun localMediaProgressId(episode:PodcastEpisode?): String = if (episode?.id.isNullOrEmpty()) id else "$id-${episode!!.id}"

  @JsonIgnore
  fun removeLocalFile(localFileId:String) {
    localFiles.removeIf { it.id == localFileId }
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx:Context): MediaDescriptionCompat {
    val coverUri = getCoverUri(ctx)

    var bitmap:Bitmap? = null
    if (coverContentUrl != null) {
      bitmap = CoverArt.decodeLocal(ctx, coverUri, "browse")
    }

    val extras = Bundle()
    extras.putLong(
      MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
      MediaDescriptionCompat.STATUS_DOWNLOADED
    )
    if (progress != null) {
      if (progress.isFinished) {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        )
      } else {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        )
        extras.putDouble(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.progress
        )
      }
    } else if (mediaType != "podcast") {
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
      )
    }

    if (media.metadata.explicit) {
      extras.putLong(MediaConstants.METADATA_KEY_IS_EXPLICIT, MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT)
    }

    val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
      .setMediaId(id)
      .setTitle(title)
      .setIconUri(coverUri)
      .setSubtitle(authorName)
      .setExtras(extras)

    bitmap?.let {
      mediaDescriptionBuilder.setIconBitmap(bitmap)
    }

    return mediaDescriptionBuilder.build()
  }
}
