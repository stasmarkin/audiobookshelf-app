package app.absplus.android.player

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import app.absplus.android.media.offlineSubtitle
import app.absplus.android.sync.ServerState

/** A playable Android Auto item with [offlineSubtitle] applied; a browsable one or a downloaded one as it is. */
internal fun withOfflineMark(item: MediaBrowserCompat.MediaItem, state: ServerState, label: String): MediaBrowserCompat.MediaItem {
  if (!item.isPlayable) return item
  val description = item.description
  // A device copy's episodes carry no download status, but their ids are the copy's own (local_*)
  val downloaded = description.mediaId?.startsWith("local") == true ||
          description.extras?.getLong(MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS) == MediaDescriptionCompat.STATUS_DOWNLOADED
  val subtitle = offlineSubtitle(description.subtitle, downloaded, state, label)
  if (subtitle == description.subtitle) return item
  val marked = MediaDescriptionCompat.Builder()
          .setMediaId(description.mediaId)
          .setTitle(description.title)
          .setSubtitle(subtitle)
          .setDescription(description.description)
          .setIconUri(description.iconUri)
          .setIconBitmap(description.iconBitmap)
          .setMediaUri(description.mediaUri)
          .setExtras(description.extras)
          .build()
  return MediaBrowserCompat.MediaItem(marked, item.flags)
}
