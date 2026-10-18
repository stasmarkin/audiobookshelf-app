package app.absplus.android.player

import app.absplus.android.data.StreamingUsingCellularSetting
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import app.absplus.android.sync.LocalCopyReady
import app.absplus.android.sync.ServerState
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.upstream.HttpDataSource

// Bad gateway, unavailable, gateway timeout, and Cloudflare's "origin unreachable" family: the server itself is gone.
// A transcode would hit the same dead origin, so these wait for the server instead of falling back.
private val SERVER_GONE_STATUSES = setOf(502, 503, 504, 520, 521, 522, 523, 524, 530)

/** Whether a stream error means the server (or the way to it) is gone, rather than this file not playing. */
fun isServerLoss(errorCode: Int, httpStatus: Int?): Boolean = when (errorCode) {
  PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
  PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatus in SERVER_GONE_STATUSES
  else -> false
}

/** An HTTP status that means the server (behind a proxy) is down rather than refusing this request. */
fun isServerGoneStatus(httpStatus: Int?): Boolean = httpStatus in SERVER_GONE_STATUSES

/** The HTTP status of a source error, when the server answered one. */
fun httpStatusOf(error: PlaybackException): Int? {
  val source = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_SOURCE }?.sourceException ?: error.cause
  return (source as? HttpDataSource.InvalidResponseCodeException)?.responseCode
}

enum class CancelReason { CLOSED, OTHER_ITEM_STARTED, SERVER_SWITCHED }

sealed interface AwaitingAction {
  /** Start the item again: a lost stream at [position], a blocked queue item ([queueItem]) where media_progress has it. */
  data class ResumeStream(
          val libraryItemId: String,
          val episodeId: String?,
          val position: Double?,
          val playWhenReady: Boolean,
          val serverConfigId: String,
          val queueItem: PlaylistQueueItem? = null,
          /** The server's track count the stream started with, so a partial download does not replace it. */
          val expectedTracks: Int? = null
  ) : AwaitingAction

  /** The item finished downloading: play the copy from [position] (null: where media_progress has it). */
  data class SwitchToLocal(
          val libraryItemId: String,
          val episodeId: String?,
          val position: Double?,
          val playWhenReady: Boolean,
          /** The stream still plays: its position is read again just before the file replaces it. */
          val fromPlayer: Boolean = false
  ) : AwaitingAction

  /** Resuming keeps failing although the server answers: stop as for any playback error. */
  object GiveUp : AwaitingAction
}

/**
 * The server a play request can go to now: the reachable one, when the live session (whose address and token the request
 * uses) is for it. ONLINE comes from a ping, so on a cold start it can come before the app has signed in.
 */
fun readyServerConfigId(reachableServerConfigId: String?, liveSessionServerConfigId: String?): String? =
        reachableServerConfigId?.takeIf { it == liveSessionServerConfigId }

/**
 * A start of the item the queue is at that could only stream while the server is away (or not signed in yet): the
 * queue waits on it, as when it reaches that item by itself, instead of failing the start and losing the queue. Null:
 * start as usual (a single Play outside the queue, a downloaded copy, a cast start, the server ready or refusing the
 * sign-in).
 */
fun queueItemToAwait(request: StartRequest, plan: PlaybackSource, queue: List<PlaylistQueueItem>, index: Int, state: ServerState,
                     serverReady: Boolean = state == ServerState.ONLINE): PlaylistQueueItem? {
  // A server that refuses the sign-in answers: the start fails as before and the user signs in again
  if (plan !is PlaybackSource.Stream || request.forceStream || serverReady || state == ServerState.UNAUTHORIZED) return null
  val item = queue.getOrNull(index) ?: return null
  return item.takeIf { it.libraryItemId == plan.libraryItemId && it.episodeId.orEmpty() == plan.episodeId.orEmpty() }
}

/** Whether the server takes a play request now; one refusing the sign-in answers, and its start fails as before. */
fun serverTakesRequests(state: ServerState): Boolean = state == ServerState.ONLINE || state == ServerState.UNAUTHORIZED

/**
 * The server is known to be away: Android Auto marks and refuses what only streams. Not UNKNOWN, which is also a cold
 * start whose first probe has not answered yet: a play request may still work.
 */
fun serverAway(state: ServerState): Boolean = state == ServerState.OFFLINE

/**
 * An Android Auto start ([StartRequest.offlineFailsFast]) that could only stream while the server is away, and that no
 * queue waits on: refused at once. Without an active server there is nothing to stream from either.
 */
fun autoStreamRefusal(request: StartRequest, plan: PlaybackSource, state: ServerState, queued: PlaylistQueueItem?, hasActiveServer: Boolean = true): Boolean =
        request.offlineFailsFast && plan is PlaybackSource.Stream && queued == null && (serverAway(state) || !hasActiveServer)

/** A stream start: [refuse] answers [message] without asking the server, otherwise [play] sends the play request. */
fun streamStart(refuse: Boolean, message: String, play: () -> Unit, onResult: (PlayerNotificationService.StartResult) -> Unit) {
  if (refuse) onResult(PlayerNotificationService.StartResult.Failed(message)) else play()
}

/** Why an id Android Auto asked to play resolves to nothing: no server to ask, or the server does not have it. */
fun unknownItemMessage(state: ServerState, offline: String, notFound: String): String = if (serverTakesRequests(state)) notFound else offline

/** The stream the player has now (server ids), for a switch to its copy when the download finishes. */
data class CurrentStream(
        val libraryItemId: String,
        val episodeId: String?,
        val serverConfigId: String?,
        val position: Double,
        val playWhenReady: Boolean,
        val isCast: Boolean
)

/** Whether a resumed stream may play now: on a metered network the user's cellular streaming setting decides. */
data class StreamPermission(val metered: Boolean, val cellular: StreamingUsingCellularSetting) {
  companion object {
    val UNMETERED = StreamPermission(false, StreamingUsingCellularSetting.ALWAYS)
  }
}

/**
 * "Waiting for the server": a stream that lost its server, or a queue that reached an item it can only stream while the
 * server is away. Decides when to start again; never touches the player. Not thread-safe: the service calls it on the
 * main thread. An action is handed out once and ends the wait.
 */
class AwaitingServer(private val clock: () -> Long = System::currentTimeMillis) {
  private sealed interface Wait {
    val serverConfigId: String
    val playWhenReady: Boolean
  }

  private data class Lost(
          val libraryItemId: String,
          val episodeId: String?,
          val position: Double,
          override val playWhenReady: Boolean,
          override val serverConfigId: String,
          val expectedTracks: Int? = null
  ) : Wait

  private data class Blocked(val item: PlaylistQueueItem, override val serverConfigId: String, override val playWhenReady: Boolean) : Wait

  private var wait: Wait? = null
  // When the wait began; a resume that failed continues the same wait
  private var waitingSince = 0L
  // A ResumeStream is out and the player has not been ready since
  private var resumeInFlight = false
  private var failedResumes = 0

  val isAwaiting: Boolean get() = wait != null

  /** A lost stream waits (the player holds a dead stream), rather than a queue stopped before its next item. */
  val isStreamLost: Boolean get() = wait is Lost

  /** The server the wait is for. */
  val serverConfigId: String? get() = wait?.serverConfigId

  /** The queue item the queue stopped at, while it waits. */
  val blockedItem: PlaylistQueueItem? get() = (wait as? Blocked)?.item

  /** Waits for the stream's server; GiveUp when this is the third resume in a row that failed. */
  fun onStreamLost(libraryItemId: String, episodeId: String?, position: Double, wasPlaying: Boolean, serverConfigId: String,
                   expectedTracks: Int? = null): AwaitingAction? =
          startWait(Lost(libraryItemId, episodeId, position, wasPlaying, serverConfigId, expectedTracks))

  fun onQueueBlocked(item: PlaylistQueueItem, serverConfigId: String, playWhenReady: Boolean = true): AwaitingAction? =
          startWait(Blocked(item, serverConfigId, playWhenReady))

  /**
   * The play request of [resume] failed. The server answers but refuses: give up at once. It is away again, or a proxy
   * answered for it ([isServerGoneStatus]): wait as before (a failed resume; the third in a row gives up).
   * [resume]'s playWhenReady is the intent now (a Pause during the request).
   */
  fun onResumeFailed(resume: AwaitingAction.ResumeStream, state: ServerState, httpStatus: Int? = null): AwaitingAction? {
    if (state == ServerState.ONLINE && !isServerGoneStatus(httpStatus)) {
      cancel(CancelReason.CLOSED)
      return AwaitingAction.GiveUp
    }
    val again = resume.queueItem?.let { Blocked(it, resume.serverConfigId, resume.playWhenReady) }
            ?: Lost(resume.libraryItemId, resume.episodeId, resume.position ?: 0.0, resume.playWhenReady, resume.serverConfigId, resume.expectedTracks)
    return startWait(again)
  }

  private fun startWait(next: Wait): AwaitingAction? {
    val continuing = resumeInFlight
    if (countFailedResume()) return AwaitingAction.GiveUp
    if (!continuing) waitingSince = clock()
    wait = next
    return null
  }

  fun onUserPause() {
    wait = when (val w = wait) {
      is Lost -> w.copy(playWhenReady = false)
      is Blocked -> w.copy(playWhenReady = false)
      null -> null
    }
  }

  fun onUserSeek(position: Double) {
    (wait as? Lost)?.let { wait = it.copy(position = position) }
  }

  /** The user wants to play: now when the server is online, otherwise when it comes back (the service asks for a probe). */
  fun onUserPlay(state: ServerState, activeServerConfigId: String?, permission: StreamPermission = StreamPermission.UNMETERED): AwaitingAction? {
    wait = when (val w = wait ?: return null) {
      is Lost -> w.copy(playWhenReady = true)
      is Blocked -> w.copy(playWhenReady = true)
    }
    return resumeIfOnline(state, activeServerConfigId, permission, byUser = true)
  }

  fun onServerState(state: ServerState, activeServerConfigId: String?, permission: StreamPermission = StreamPermission.UNMETERED): AwaitingAction? =
          resumeIfOnline(state, activeServerConfigId, permission, byUser = false)

  private fun resumeIfOnline(state: ServerState, activeServerConfigId: String?, permission: StreamPermission, byUser: Boolean): AwaitingAction? {
    val w = wait ?: return null
    if (state != ServerState.ONLINE || activeServerConfigId != w.serverConfigId) return null
    // Never streams on cellular against the setting, as a restore does not: waits for an unmetered network
    if (permission.metered && permission.cellular == StreamingUsingCellularSetting.NEVER) return null
    // Asking is the app's dialog (the user's own Play confirms), and a long wait may have outlived the listener:
    // continue, but paused
    val confirmed = byUser || !(permission.metered && permission.cellular == StreamingUsingCellularSetting.ASK)
    val fresh = byUser || clock() - waitingSince <= STALE_WAIT_MS
    val playWhenReady = w.playWhenReady && confirmed && fresh
    wait = null
    resumeInFlight = true
    return when (w) {
      is Lost -> AwaitingAction.ResumeStream(w.libraryItemId, w.episodeId, w.position, playWhenReady, w.serverConfigId, expectedTracks = w.expectedTracks)
      is Blocked -> AwaitingAction.ResumeStream(w.item.libraryItemId, w.item.episodeId, null, playWhenReady, w.serverConfigId, w.item, w.item.expectedTracks)
    }
  }

  /**
   * A download finished. What waits for that item, or the stream playing it, switches to the copy: at the wait's
   * position (the player's is stale after a loss) or the stream's. A cast device cannot play the file.
   */
  fun onLocalCopyReady(ready: LocalCopyReady, current: CurrentStream?): AwaitingAction? {
    if (current?.isCast == true) return null
    fun same(libraryItemId: String, episodeId: String?, serverConfigId: String?) = ready.libraryItemId == libraryItemId &&
            ready.episodeId.orEmpty() == episodeId.orEmpty() && ready.serverConfigId == serverConfigId
    val action = when (val w = wait) {
      is Lost -> if (same(w.libraryItemId, w.episodeId, w.serverConfigId)) AwaitingAction.SwitchToLocal(w.libraryItemId, w.episodeId, w.position, w.playWhenReady) else null
      is Blocked -> if (same(w.item.libraryItemId, w.item.episodeId, w.serverConfigId)) AwaitingAction.SwitchToLocal(w.item.libraryItemId, w.item.episodeId, null, w.playWhenReady) else null
      null -> null
    }
    if (action != null) {
      cancel(CancelReason.OTHER_ITEM_STARTED)
      return action
    }
    if (wait != null || current == null || !same(current.libraryItemId, current.episodeId, current.serverConfigId)) return null
    return AwaitingAction.SwitchToLocal(current.libraryItemId, current.episodeId, current.position, current.playWhenReady, fromPlayer = true)
  }

  fun onPlaybackReady() {
    resumeInFlight = false
    failedResumes = 0
  }

  fun cancel(@Suppress("UNUSED_PARAMETER") reason: CancelReason) {
    wait = null
    resumeInFlight = false
    failedResumes = 0
  }

  private fun countFailedResume(): Boolean {
    if (!resumeInFlight) return false
    resumeInFlight = false
    failedResumes++
    if (failedResumes < MAX_FAILED_RESUMES) return false
    cancel(CancelReason.CLOSED)
    return true
  }

  private companion object {
    const val MAX_FAILED_RESUMES = 3
    const val STALE_WAIT_MS = 30 * 60_000L
  }
}
