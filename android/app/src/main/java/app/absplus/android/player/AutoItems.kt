package app.absplus.android.player

import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.LibraryItemWrapper
import app.absplus.android.media.CoverCache
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

/**
 * Android Auto and Automotive in use: a reconcile may run (see [app.absplus.android.sync.SyncRuntime.onAutoActivity]).
 * Not a watch or the Google app, which may browse while the phone lies in a pocket.
 */
internal fun opensReconcileLease(packageName: String?): Boolean =
        packageName == ANDROID_AUTO_PKG_NAME || packageName == ANDROID_AUTO_SIMULATOR_PKG_NAME || packageName == ANDROID_AUTOMOTIVE_PKG_NAME

/**
 * An Android Auto icon: the cover cache's file when there is one, else the server's URL while it answers, else the app
 * icon (the host cannot fetch the URL without the server and would show nothing).
 */
fun autoCoverUri(cached: Uri?, serverUri: Uri?, online: Boolean, fallback: Uri): Uri = when {
  cached != null -> cached
  online && serverUri != null -> serverUri
  else -> fallback
}

/** The covers of a node's items to cache for later (item id, updatedAt); [app.absplus.android.media.CoverCache.prefetch] skips cached ones. */
internal fun coversToPrefetch(items: List<LibraryItemWrapper?>?): List<Pair<String, Long>> =
        items.orEmpty().filterIsInstance<LibraryItem>().filter { it.media.coverPath != null }.map { it.id to it.updatedAt }.distinct()

/** The Android Auto hosts that read a node's `content://` icons. */
internal val AUTO_HOST_PACKAGES = listOf(ANDROID_AUTO_PKG_NAME, ANDROID_AUTO_SIMULATOR_PKG_NAME, ANDROID_AUTOMOTIVE_PKG_NAME)

/** After a node's answer, online: the covers of its items the cache lacks are downloaded for later. */
internal fun prefetchAutoCovers(items: List<LibraryItemWrapper?>?) {
  val cache = CoverCache.instance ?: return
  if (cache.isOnline()) cache.prefetch(coversToPrefetch(items))
}
