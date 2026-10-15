package app.absplus.android.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells the WebView what changed, at most once per [windowMs] per scope: a burst of writes (a merge of many bookmarks,
 * playback ticks) becomes one event with the ids of all of them. Scopes: "bookmarks", "progress", "playback" (ticks), "user", "sync".
 */
class DataChangeNotifier(
        private val windowMs: Long = 500,
        private val scope: CoroutineScope,
        private val emit: (scope: String, ids: List<String>) -> Unit
) {
  private val waiting = HashMap<String, LinkedHashSet<String>>()

  fun notify(scope: String, ids: Collection<String>) {
    synchronized(waiting) {
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
