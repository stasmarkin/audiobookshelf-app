package app.absplus.android.media

import app.absplus.android.player.AUTO_BROWSE_ROOT
import app.absplus.android.player.CONTINUE_ROOT
import app.absplus.android.player.serverAway
import app.absplus.android.sync.DataChange
import app.absplus.android.sync.ServerState

private const val MAX_SERVED_NODES = 50

/**
 * Which Android Auto nodes to tell to reload ([android.support.v4.media.MediaBrowserServiceCompat.notifyChildrenChanged])
 * when the data or the server changes. A catalog or user change reloads the root and the nodes Auto opened, at most
 * every [catalogEveryMs]; a progress change Continue and those nodes, at most every [progressEveryMs]; a change held
 * back by its interval is handed out by [due]. Playback ticks reload nothing: Auto would redraw its lists every few
 * seconds, and the playing item's percentage shows when its list opens again.
 */
class AutoRefresh(private val clock: () -> Long, private val catalogEveryMs: Long = 2_000, private val progressEveryMs: Long = 10_000) {
  private val servedNodes = LinkedHashSet<String>()
  private var catalogPending = false
  private var progressPending = false
  private var catalogAt: Long? = null
  private var progressAt: Long? = null
  // Whether the server was away when the lists were drawn; null before the first state
  private var serverWasAway: Boolean? = null

  val hasPending: Boolean
    @Synchronized get() = catalogPending || progressPending

  /** Auto opened [parentId]; the latest [MAX_SERVED_NODES] are remembered. */
  @Synchronized fun served(parentId: String) {
    servedNodes.remove(parentId)
    servedNodes.add(parentId)
    while (servedNodes.size > MAX_SERVED_NODES) servedNodes.remove(servedNodes.first())
  }

  @Synchronized fun onData(change: DataChange): Set<String> {
    when (change.scope) {
      // Hidden series of the user change the shelves
      "catalog", "user" -> catalogPending = true
      "progress" -> progressPending = true
      else -> return emptySet()
    }
    return dueNow()
  }

  /** The "Not available offline" marks follow whether the server is away ([serverAway]). */
  @Synchronized fun onServerState(state: ServerState): Set<String> {
    val away = serverAway(state)
    val previous = serverWasAway
    serverWasAway = away
    return if (previous != null && previous != away) servedNodes.toSet() else emptySet()
  }

  /** A download finished: its "Not available offline" mark goes (throttled with the catalog). */
  @Synchronized fun onLocalCopy(): Set<String> {
    catalogPending = true
    return dueNow()
  }

  /** Another active server: its root at once, and what is open answers for it. */
  @Synchronized fun onServerSwitched(): Set<String> {
    catalogPending = false
    catalogAt = clock()
    return setOf(AUTO_BROWSE_ROOT) + servedNodes
  }

  /** The held-back changes whose interval has passed. */
  @Synchronized fun due(): Set<String> = dueNow()

  private fun dueNow(): Set<String> {
    val now = clock()
    val notify = LinkedHashSet<String>()
    if (catalogPending && catalogAt.elapsed(now) >= catalogEveryMs) {
      catalogPending = false
      catalogAt = now
      notify += AUTO_BROWSE_ROOT
      notify += servedNodes
    }
    if (progressPending && progressAt.elapsed(now) >= progressEveryMs) {
      progressPending = false
      progressAt = now
      notify += CONTINUE_ROOT
      notify += servedNodes
    }
    return notify
  }

  private fun Long?.elapsed(now: Long): Long = if (this == null) Long.MAX_VALUE else now - this
}
