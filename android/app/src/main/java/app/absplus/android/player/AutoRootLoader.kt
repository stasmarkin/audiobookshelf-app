package app.absplus.android.player

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browse nodes that need no server data, served even before the first server load has finished: the root, Downloads
 * and the downloaded (local) items under it, such as a downloaded podcast's episodes. Their children come from the
 * device database only (MediaManager.loadLibraryItem / loadPodcastEpisodeMediaBrowserItems for "local" ids).
 * Every other node still waits for the first load.
 */
internal fun servableBeforeFirstLoad(mediaId: String): Boolean =
  mediaId == AUTO_BROWSE_ROOT || mediaId == DOWNLOADS_ROOT || mediaId.startsWith("local")

/**
 * The Android Auto root's server load, run in the background so the root never waits for it (Discussion #30).
 *
 * The root is answered at once with what needs no server (Downloads). [reload] then runs [load] (server checks,
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
