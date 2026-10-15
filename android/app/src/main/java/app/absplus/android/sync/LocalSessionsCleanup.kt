package app.absplus.android.sync

import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.server.ApiHandler.LocalSessionSyncResult
import kotlinx.coroutines.CancellationException

/** What the device keeps of its saved listening sessions after the server answered `local-all`. */
object LocalSessionsCleanup {
  /**
   * The row's values the server did not take from a session (it held newer progress): sent by PATCH instead, only if
   * the device is further on than the server; otherwise the server's position is taken.
   */
  private val SKIPPED_PROGRESS_FIELDS = listOf("duration", "progress", "currentTime")

  /**
   * Sessions the server took, and those it never will: their item or library is gone (the only per-session errors it
   * returns). Any other failure, and a session missing from [results], is sent again next time.
   */
  fun idsToRemove(sent: List<String>, results: List<LocalSessionSyncResult>): Set<String> {
    val sentIds = sent.toSet()
    return results.filter { it.id in sentIds && (it.success || isItemGone(it.error)) }.map { it.id }.toSet()
  }

  private val ITEM_GONE_ERRORS = setOf("media item not found", "library not found")

  fun isItemGone(error: String?) = error?.trim()?.lowercase() in ITEM_GONE_ERRORS

  /**
   * After `local-all` ([requestOk]: the server answered it): queues a PATCH of the progress the server skipped, then
   * removes the sessions it settled. In that order, so the row stays protected from older server values throughout:
   * first by the unsent session, then by the queued PATCH. [done] runs once that is queued, without waiting for it:
   * a sync stopped meanwhile never runs it, and the sessions are then simply sent again.
   */
  fun settle(sent: List<PlaybackSession>, requestOk: Boolean, results: List<LocalSessionSyncResult>, done: () -> Unit) {
    if (!requestOk) return done()
    val remove = idsToRemove(sent.map { it.id }, results)
    val skipped = skippedProgress(sent, results)
    fun removeSettled() {
      // A tick may have saved a session again during the request: that newer one is sent next time
      sent.filter { it.id in remove }.forEach { DeviceManager.dbManager.removePlaybackSessionIfUnchanged(it) }
    }
    val queued = SyncEngine.launchWrite { store ->
      try {
        var patched = false
        for (item in skipped) {
          val hasRow = store.progressRowsFor(item.serverConfigId, listOf(item.libraryItemId)).any { it.episodeId == item.episodeId.orEmpty() }
          if (!hasRow) continue
          store.queueProgressPatch(item.serverConfigId, item.libraryItemId, item.episodeId, SKIPPED_PROGRESS_FIELDS, ifAhead = true)
          patched = true
        }
        removeSettled()
        if (patched) SyncEngine.onLocalWrite()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // The sessions stay, so their items stay protected and they are sent again
        AbsLogger.error("LocalSessionsCleanup", "settle: Failed to queue skipped progress (${e.message})")
      }
    }
    // Sync is not running: there is no userdata.db row to protect
    if (!queued) removeSettled()
    done()
  }

  private data class Item(val serverConfigId: String, val libraryItemId: String, val episodeId: String?)

  private fun skippedProgress(sent: List<PlaybackSession>, results: List<LocalSessionSyncResult>): Set<Item> {
    val skippedIds = results.filter { it.success && it.progressSynced == false }.map { it.id }.toSet()
    return sent.filter { it.id in skippedIds }.mapNotNull { session ->
      val serverConfigId = session.serverConnectionConfigId?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
      val libraryItemId = session.libraryItemId?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
      Item(serverConfigId, libraryItemId, session.episodeId?.takeIf { it.isNotEmpty() })
    }.toSet()
  }
}
