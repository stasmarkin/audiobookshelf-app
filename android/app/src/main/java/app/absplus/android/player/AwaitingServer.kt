package app.absplus.android.player

import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
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
          val queueItem: PlaylistQueueItem? = null
  ) : AwaitingAction

  /** Resuming keeps failing although the server answers: stop as for any playback error. */
  object GiveUp : AwaitingAction
}

/**
 * "Waiting for the server": a stream that lost its server, or a queue that reached an item it can only stream while the
 * server is away. Decides when to start again; never touches the player. Not thread-safe: the service calls it on the
 * main thread. An action is handed out once and ends the wait.
 */
class AwaitingServer {
  private sealed interface Wait {
    val serverConfigId: String
    val playWhenReady: Boolean
  }

  private data class Lost(
          val libraryItemId: String,
          val episodeId: String?,
          val position: Double,
          override val playWhenReady: Boolean,
          override val serverConfigId: String
  ) : Wait

  private data class Blocked(val item: PlaylistQueueItem, override val serverConfigId: String, override val playWhenReady: Boolean) : Wait

  private var wait: Wait? = null
  // A ResumeStream is out and the player has not been ready since
  private var resumeInFlight = false
  private var failedResumes = 0

  val isAwaiting: Boolean get() = wait != null

  /** The server the wait is for. */
  val serverConfigId: String? get() = wait?.serverConfigId

  /** The queue item the queue stopped at, while it waits. */
  val blockedItem: PlaylistQueueItem? get() = (wait as? Blocked)?.item

  /** Waits for the stream's server; GiveUp when this is the third resume in a row that failed. */
  fun onStreamLost(libraryItemId: String, episodeId: String?, position: Double, wasPlaying: Boolean, serverConfigId: String): AwaitingAction? {
    if (countFailedResume()) return AwaitingAction.GiveUp
    wait = Lost(libraryItemId, episodeId, position, wasPlaying, serverConfigId)
    return null
  }

  fun onQueueBlocked(item: PlaylistQueueItem, serverConfigId: String, playWhenReady: Boolean = true): AwaitingAction? {
    if (countFailedResume()) return AwaitingAction.GiveUp
    wait = Blocked(item, serverConfigId, playWhenReady)
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
  fun onUserPlay(state: ServerState, activeServerConfigId: String?): AwaitingAction? {
    wait = when (val w = wait ?: return null) {
      is Lost -> w.copy(playWhenReady = true)
      is Blocked -> w.copy(playWhenReady = true)
    }
    return onServerState(state, activeServerConfigId)
  }

  fun onServerState(state: ServerState, activeServerConfigId: String?): AwaitingAction? {
    val w = wait ?: return null
    if (state != ServerState.ONLINE || activeServerConfigId != w.serverConfigId) return null
    wait = null
    resumeInFlight = true
    return when (w) {
      is Lost -> AwaitingAction.ResumeStream(w.libraryItemId, w.episodeId, w.position, w.playWhenReady, w.serverConfigId)
      is Blocked -> AwaitingAction.ResumeStream(w.item.libraryItemId, w.item.episodeId, null, w.playWhenReady, w.serverConfigId, w.item)
    }
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
  }
}
