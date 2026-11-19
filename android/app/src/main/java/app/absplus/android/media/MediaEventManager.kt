package app.absplus.android.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.player.PLAYMETHOD_LOCAL
import app.absplus.android.player.PlayerNotificationService
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The per-book listening journal shown on the History screen. Events are written the moment a playback transition
 * happens (position and time from a [TransitionSnapshot]); a later server sync result is attached to the event by id.
 */
object MediaEventManager {
  const val tag = "MediaEventManager"
  const val HISTORY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
  const val SLEEP_TIMER_DESCRIPTION = "sleep_timer"

  var clientEventEmitter: PlayerNotificationService.ClientEventEmitter? = null

  interface HistoryStore {
    fun read(id: String): MediaItemHistory?
    fun write(history: MediaItemHistory)
    fun delete(id: String)
    fun contains(id: String): Boolean
  }

  object DbHistoryStore : HistoryStore {
    override fun read(id: String) = DeviceManager.dbManager.getMediaItemHistory(id)
    override fun write(history: MediaItemHistory) = DeviceManager.dbManager.saveMediaItemHistory(history)
    override fun delete(id: String) = DeviceManager.dbManager.removeMediaItemHistory(id)
    override fun contains(id: String) = DeviceManager.dbManager.hasMediaItemHistory(id)
  }

  internal var clock: () -> Long = System::currentTimeMillis
  internal var store: HistoryStore = DbHistoryStore
  internal var mainPoster: ((() -> Unit) -> Unit) = { Handler(Looper.getMainLooper()).post(it) }
  // Journal IO runs off the player's thread, one write at a time in call order
  private val ioExecutor = Executors.newSingleThreadExecutor()
  internal var ioRunner: ((() -> Unit) -> Unit) = { ioExecutor.execute(it) }

  // Every read-modify-write of a book's journal holds it (also when a test runs writes inline from several threads)
  private val lock = Any()
  // Before per-book keys, every local-only book was written under "" mixed together; dropped once per process
  internal var emptyKeyChecked = false

  data class TransitionSnapshot(
          val key: String,
          val session: PlaybackSession,
          val sessionId: String,
          val currentTime: Double,
          val timestamp: Long,
          val description: String
  )

  /** The server item id when the book has one (also for a downloaded copy), else the local item id */
  fun historyKey(session: PlaybackSession): String {
    val libraryItemId = session.libraryItemId
    if (!libraryItemId.isNullOrEmpty()) {
      return if (session.episodeId.isNullOrEmpty()) libraryItemId else "$libraryItemId-${session.episodeId}"
    }
    val localId = session.localLibraryItem?.id ?: ""
    return if (session.localEpisodeId.isNullOrEmpty()) localId else "$localId-${session.localEpisodeId}"
  }

  fun snapshot(session: PlaybackSession, currentTime: Double, description: String = ""): TransitionSnapshot {
    return TransitionSnapshot(historyKey(session), session.clone(), session.id, currentTime, clock(), description)
  }

  /** Writes a playback event from [snapshot] and returns its id */
  fun recordEvent(name: String, snapshot: TransitionSnapshot, syncResult: SyncResult? = null): String {
    Log.i(tag, "$name Event for media \"${snapshot.session.displayTitle}\", currentTime=${snapshot.currentTime}")
    val event = newEvent(name, "Playback", snapshot.description, snapshot.currentTime, syncResult, snapshot.timestamp)
    if (snapshot.key.isEmpty()) {
      Log.w(tag, "recordEvent: session ${snapshot.sessionId} has no item id, not journaled")
      return event.id!!
    }
    write(snapshot.key, { createHistory(snapshot.key, snapshot.session) }) { history ->
      updateServerFields(history, snapshot.session, fillOnly = snapshot.session.playMethod == PLAYMETHOD_LOCAL)
      history.events.add(event)
      true
    }
    return event.id!!
  }

  /** A saved session's progress the server accepted later: its answer can be late, so it never replaces server fields */
  fun recordSessionSave(session: PlaybackSession, syncResult: SyncResult?) {
    Log.i(tag, "Save Event for saved session \"${session.displayTitle}\"")
    val key = historyKey(session)
    if (key.isEmpty()) return
    val event = newEvent("Save", "Playback", "", session.currentTime, syncResult, clock())
    write(key, { createHistory(key, session) }) { history ->
      updateServerFields(history, session, fillOnly = true)
      history.events.add(event)
      true
    }
  }

  fun attachSyncResult(key: String, eventId: String, syncResult: SyncResult?) {
    write(key, null) { history ->
      val event = history.events.find { it.id == eventId } ?: return@write false
      val attempted = syncResult?.serverSyncAttempted ?: false
      if (event.serverSyncAttempted == attempted && event.serverSyncSuccess == syncResult?.serverSyncSuccess &&
              event.serverSyncMessage == syncResult?.serverSyncMessage) return@write false
      event.serverSyncAttempted = attempted
      event.serverSyncSuccess = syncResult?.serverSyncSuccess
      event.serverSyncMessage = syncResult?.serverSyncMessage
      true
    }
  }

  fun syncEvent(mediaProgress: MediaProgressWrapper, description: String) {
    Log.i(tag, "Sync Event for media item id \"${mediaProgress.mediaItemId}\", currentTime=${mediaProgress.currentTime}")
    val event = newEvent("Sync", "Sync", description, mediaProgress.currentTime, null, clock())
    write(mediaProgress.mediaItemId, null) { history ->
      history.events.add(event)
      true
    }
  }

  /** A copy holding only the events inside the retention window; the stored history is not changed */
  fun filterForRead(history: MediaItemHistory?, now: Long): MediaItemHistory? {
    if (history == null) return null
    return copyOf(history).apply { events.removeAll { isExpired(it, now) } }
  }

  /**
   * On the journal thread, under the lock: drops the legacy "" key once, prunes expired events, applies [change] and
   * saves. [create] makes a missing history; without it a missing history is left alone. The history is saved when
   * [change] returns true or pruning removed events, and then a copy is posted to the screen. A journal failure is
   * logged and never reaches playback or progress sync.
   */
  private fun write(key: String, create: (() -> MediaItemHistory)?, change: (MediaItemHistory) -> Boolean) {
    ioRunner {
      try {
        writeNow(key, create, change)
      } catch (e: Exception) {
        Log.e(tag, "Listening journal write failed for $key", e)
      }
    }
  }

  private fun writeNow(key: String, create: (() -> MediaItemHistory)?, change: (MediaItemHistory) -> Boolean) {
    synchronized(lock) {
      dropLegacyEmptyKey()
      val history = store.read(key) ?: create?.invoke()
      if (history == null) {
        Log.w(tag, "write: Media Item History not created yet for media item id $key")
        return
      }
      val pruned = history.events.removeAll { isExpired(it, clock()) }
      val changed = change(history)
      if (!changed && !pruned) return
      store.write(history)
      val copy = copyOf(history)
      mainPoster { clientEventEmitter?.onMediaItemHistoryUpdated(copy) }
    }
  }

  private fun dropLegacyEmptyKey() {
    if (emptyKeyChecked) return
    emptyKeyChecked = true
    try {
      if (store.contains("")) store.delete("")
    } catch (e: Exception) {
      Log.w(tag, "Could not remove the legacy shared local history", e)
    }
  }

  private fun isExpired(event: MediaItemEvent, now: Long) = now - event.timestamp > HISTORY_RETENTION_MS

  private fun newEvent(name: String, type: String, description: String, currentTime: Double, syncResult: SyncResult?, timestamp: Long) =
          MediaItemEvent(name, type, description, currentTime, syncResult?.serverSyncAttempted ?: false, syncResult?.serverSyncSuccess,
                  syncResult?.serverSyncMessage, timestamp, UUID.randomUUID().toString())

  private fun createHistory(key: String, session: PlaybackSession): MediaItemHistory {
    Log.i(tag, "Creating new media item history for media \"${session.displayTitle}\"")
    val serverItemId = session.libraryItemId
    val isLocalOnly = serverItemId.isNullOrEmpty()
    return MediaItemHistory(
            id = key,
            mediaDisplayTitle = session.displayTitle ?: "Unset",
            libraryItemId = if (isLocalOnly) session.localLibraryItem?.id ?: "" else serverItemId!!,
            episodeId = if (isLocalOnly) session.localEpisodeId else session.episodeId,
            isLocal = isLocalOnly,
            serverConnectionConfigId = null,
            serverAddress = null,
            serverUserId = null,
            createdAt = clock(),
            events = mutableListOf()
    )
  }

  // A local session carries the address the copy was downloaded from, which may be stale, so it only fills gaps
  private fun updateServerFields(history: MediaItemHistory, session: PlaybackSession, fillOnly: Boolean) {
    if (!fillOnly && !session.serverAddress.isNullOrEmpty()) {
      history.serverAddress = session.serverAddress
      history.serverConnectionConfigId = session.serverConnectionConfigId
      history.serverUserId = session.userId
      return
    }
    if (history.serverAddress.isNullOrEmpty()) history.serverAddress = session.serverAddress
    if (history.serverConnectionConfigId.isNullOrEmpty()) history.serverConnectionConfigId = session.serverConnectionConfigId
    if (history.serverUserId.isNullOrEmpty()) history.serverUserId = session.userId
  }

  private fun copyOf(history: MediaItemHistory) = MediaItemHistory(
          history.id, history.mediaDisplayTitle, history.libraryItemId, history.episodeId, history.isLocal,
          history.serverConnectionConfigId, history.serverAddress, history.serverUserId, history.createdAt,
          history.events.map {
            MediaItemEvent(it.name, it.type, it.description, it.currentTime, it.serverSyncAttempted, it.serverSyncSuccess,
                    it.serverSyncMessage, it.timestamp, it.id)
          }.toMutableList()
  )
}
