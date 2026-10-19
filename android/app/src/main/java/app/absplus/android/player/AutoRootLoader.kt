package app.absplus.android.player

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browse nodes served before the first server load has finished. Always: the root, Downloads and the downloaded
 * (local) items under it, whose children come from the device database only. With a reconciled replica
 * ([readyLibraryIds]): Continue, Recent, Libraries and the nodes of those libraries, read from the replica; a node of
 * another library waits for the load. Other ids (a podcast's episodes) are served once any library is ready.
 */
internal fun servableWithoutServer(mediaId: String, readyLibraryIds: Set<String>): Boolean {
  if (mediaId == AUTO_BROWSE_ROOT || mediaId == DOWNLOADS_ROOT || mediaId.startsWith("local")) return true
  if (readyLibraryIds.isEmpty()) return false
  if (mediaId.startsWith("__LIBRARY__") || (mediaId.startsWith(RECENTLY_ROOT) && mediaId != RECENTLY_ROOT)) {
    return mediaId.split("__").getOrNull(2) in readyLibraryIds
  }
  return true
}

/**
 * The key of Android Auto's cached search results: the same query searches again after a reconcile, on another server,
 * once the server load finished (a never reconciled library is searched on the server) and after a download changed.
 */
internal fun autoSearchCacheKey(query: String, serverConfigId: String?, generation: Long?, firstLoadDone: Boolean, copiesVersion: Long): String =
  listOf(query, serverConfigId, generation, firstLoadDone, copiesVersion).joinToString("\u0000")

/**
 * Android Auto (its simulator, Automotive, or the Google app that carries a spoken "continue" in the car) is the
 * controller of a session command, not a headset or the widget.
 */
internal fun isAutoController(packageName: String?): Boolean =
  packageName == ANDROID_GSEARCH_PKG_NAME || packageName == ANDROID_AUTO_PKG_NAME || packageName == ANDROID_AUTO_SIMULATOR_PKG_NAME || packageName == ANDROID_AUTOMOTIVE_PKG_NAME

/** The nodes served without any replica: see [servableWithoutServer]. */
internal fun servableBeforeFirstLoad(mediaId: String): Boolean = servableWithoutServer(mediaId, emptySet())

/**
 * The Android Auto root's server load, run in the background so the root never waits for it (Discussion #30).
 *
 * The root is answered at once from the replica (at least Downloads). [reload] then runs [load] (server checks,
 * authorization and libraries, unchanged) at most once at a time: overlapping root requests while it runs start
 * nothing new and produce no extra refresh. When it finishes, [onLoaded] installs the real browse tree and
 * [notifyRootChanged] asks the client to fetch the root again.
 */
internal class AutoRootLoader(
  private val load: (onDone: () -> Unit) -> Unit,
  private val onLoaded: () -> Unit,
  private val notifyRootChanged: () -> Unit
) {
  private val inFlight = AtomicBoolean(false)

  val isLoading: Boolean get() = inFlight.get()

  /** Starts the load unless one is already running; true when this call started it. */
  fun reload(): Boolean {
    if (!inFlight.compareAndSet(false, true)) return false
    load {
      try {
        onLoaded()
      } finally {
        inFlight.set(false)
      }
      notifyRootChanged()
    }
    return true
  }
}
