package app.absplus.android.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.utils.MediaConstants
import app.absplus.android.BuildConfig
import app.absplus.android.R
import app.absplus.android.device.DeviceManager
import app.absplus.android.player.CoverArt
import app.absplus.android.player.autoCoverUri
import app.absplus.android.player.serverAway
import app.absplus.android.sync.ActiveServer
import app.absplus.android.sync.ServerReachability
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class LibraryItem(
  id:String,
  var ino:String,
  var libraryId:String,
  var folderId:String,
  var path:String,
  var relPath:String,
  var mtimeMs:Long,
  var ctimeMs:Long,
  var birthtimeMs:Long,
  var addedAt:Long,
  var updatedAt:Long,
  var lastScan:Long?,
  var scanVersion:String?,
  var isMissing:Boolean,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaType,
  var libraryFiles:MutableList<LibraryFile>?,
  var userMediaProgress:MediaProgress?, // Only included when requesting library item with progress (for downloads)
  var collapsedSeries: CollapsedSeries?,
  var localLibraryItemId:String?, // For Android Auto
  val recentEpisode: PodcastEpisode?  // Podcast episode shelf uses this
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title: String
    get() {
      if (collapsedSeries != null) {
        return collapsedSeries!!.title
      }
      return media.metadata.title
    }
  @get:JsonIgnore
  val authorName get() = media.metadata.getAuthorDisplayName()

  /**
   * With [ctx] (Android Auto): the cover cache's file first, the active server's URL unless it is away, else the app
   * icon rather than a URL the host cannot fetch ([autoCoverUri]). Without it: the live session's URL.
   */
  @JsonIgnore
  fun getCoverUri(ctx: Context? = null): Uri {
    val icon = Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
    if (media.coverPath == null) {
      return icon
    }
    if (ctx != null) {
      // The saved active server: a process Auto started has no live session until its root load
      val serverUri = ActiveServer.current()?.let { coverUrl(it.address, it.version.orEmpty(), it.token) }
      return autoCoverUri(CoverArt.cachedCoverUri(ctx, id, updatedAt), serverUri, !serverAway(ServerReachability.state.value), icon)
    }
    return coverUrl(DeviceManager.serverAddress, DeviceManager.serverVersion, DeviceManager.token)
  }

  private fun coverUrl(address: String, version: String, token: String): Uri {
    // As of v2.17.0 token is not needed with cover image requests
    if (DeviceManager.isVersionAtLeast(version, "2.17.0")) {
      return Uri.parse("$address/api/items/$id/cover")
    }

    return Uri.parse("$address/api/items/$id/cover?token=$token")
  }

  @JsonIgnore
  fun checkHasTracks():Boolean {
    return media.checkHasTracks()
  }

  @get:JsonIgnore
  val seriesSequence: String
    get() {
      if (mediaType != "podcast") {
        return ((media as Book).metadata as BookMetadata).series?.get(0)?.sequence.orEmpty()
      } else {
        return ""
      }
    }

  @get:JsonIgnore
  val seriesSequenceParts: List<String>
    get() {
      if (seriesSequence.isEmpty()) {
        return listOf("")
      }
      return seriesSequence.split(".", limit = 2)
    }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?, showSeriesNumber: Boolean?, groupTitle: String?): MediaDescriptionCompat {
    val extras = Bundle()

    if (collapsedSeries == null) {
      if (localLibraryItemId != null) {
        extras.putLong(
          MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
          MediaDescriptionCompat.STATUS_DOWNLOADED
        )
      }

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
        extras.putLong(
          MediaConstants.METADATA_KEY_IS_EXPLICIT,
          MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT
        )
      }
    }
    if (groupTitle !== null) {
      extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
    }

    val mediaId = if (localLibraryItemId != null) {
      localLibraryItemId
    } else if (collapsedSeries != null) {
      if (authorId != null) {
        "__LIBRARY__${libraryId}__AUTHOR_SERIES__${authorId}__${collapsedSeries!!.id}"
      } else {
        "__LIBRARY__${libraryId}__SERIES__${collapsedSeries!!.id}"
      }
    } else {
      id
    }
    var subtitle = authorName
    if (collapsedSeries != null) {
      subtitle = "${collapsedSeries!!.numBooks} books"
    }
    var itemTitle = title
    if (showSeriesNumber == true && seriesSequence != "") {
      itemTitle = "$seriesSequence. $itemTitle"
    }
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(itemTitle)
      .setIconUri(getCoverUri(ctx))
      .setSubtitle(subtitle)
      .setExtras(extras)
      .build()
  }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?, showSeriesNumber: Boolean?): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, authorId, showSeriesNumber, null)
  }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, authorId, null, null)
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    /*
    This is needed so Android auto library hierarchy for author series can be implemented
     */
    return getMediaDescription(progress, ctx, null, null, null)
  }
}
