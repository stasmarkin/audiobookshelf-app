package app.absplus.android.managers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Per local library item locks and write versions. Every write of an item runs under its lock and
 * bumps its version, so a step that copied the item's files without the lock (moving downloads to
 * another storage) can commit under the lock only if the item is unchanged and not in playback.
 */
object LocalItemLocks {
  private val locks = ConcurrentHashMap<String, ReentrantLock>()
  private val versions = ConcurrentHashMap<String, Long>()

  @Volatile private var pinned: String? = null

  fun <T> withItem(id: String, block: () -> T): T {
    val lock = locks.computeIfAbsent(id) { ReentrantLock() }
    lock.lock()
    try {
      return block()
    } finally {
      lock.unlock()
    }
  }

  fun version(id: String): Long = versions[id] ?: 0L

  fun bump(id: String) {
    versions.merge(id, 1L, Long::plus)
  }

  fun pinForPlayback(id: String) {
    withItem(id) { pinned = id }
  }

  /** True when an item was pinned. */
  @Synchronized
  fun clearPlaybackPin(): Boolean {
    val had = pinned != null
    pinned = null
    return had
  }

  fun isPinned(id: String): Boolean = pinned == id

  /** Reads the item for playback and pins it only if [playable] accepts it; otherwise the previous pin stays. */
  fun <T : Any> readForPlayback(id: String, read: () -> T?, playable: (T) -> Boolean): T? =
          withItem(id) {
            val v = read() ?: return@withItem null
            if (playable(v)) {
              pinned = id
              v
            } else {
              null
            }
          }
}
