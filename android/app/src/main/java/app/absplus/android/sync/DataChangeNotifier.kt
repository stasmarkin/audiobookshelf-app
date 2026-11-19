package app.absplus.android.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells the WebView what changed, at most once per [windowMs] per scope: a burst of writes (a merge of many bookmarks,
 * playback ticks) becomes one event with the ids of all of them. Scopes: "bookmarks", "progress", "playback" (ticks), "user", "sync",
 * "catalog" (ids are topics: `libraries`, `library:<id>`, `items:<id>`, `series:<id>`, `state:<id>`, ...).
 */
class DataChangeNotifier(
        private val windowMs: Long = 500,
        private val scope: CoroutineScope,
        private val emit: (scope: String, ids: List<String>) -> Unit
) {
  private val waiting = HashMap<String, LinkedHashSet<String>>()
  private val versions = HashMap<String, Long>()

  /** Grows with every [notify] of [scope] right away, before the coalesced event: cached reads compare it. */
  fun version(scope: String): Long = synchronized(waiting) { versions[scope] ?: 0L }

  fun notify(scope: String, ids: Collection<String>) {
    synchronized(waiting) {
      versions[scope] = (versions[scope] ?: 0L) + 1
      val queued = waiting[scope]
      if (queued != null) {
        queued.addAll(ids)
        return
      }
      waiting[scope] = LinkedHashSet(ids)
    }
    this.scope.launch {
      delay(windowMs)
      val ids = synchronized(waiting) { waiting.remove(scope) } ?: return@launch
      emit(scope, ids.toList())
    }
  }
}
