package app.absplus.android.player

import android.net.Uri
import app.absplus.android.player.AwaitingAction.ResumeStream
import app.absplus.android.player.AwaitingAction.SwitchToLocal
import app.absplus.android.sync.LocalCopyReady
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import app.absplus.android.sync.ServerState
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AwaitingServerTest {
  private val failed = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
  private val timeout = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
  private val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

  @Test fun isServerLossTable() {
    assertTrue(isServerLoss(failed, null))
    assertTrue(isServerLoss(timeout, null))
    // The origin behind a proxy or Cloudflare is down: a transcode would hit the same dead origin
    for (status in listOf(502, 503, 504, 520, 521, 522, 523, 524, 530)) assertTrue("$status", isServerLoss(badStatus, status))
    for (status in listOf(500, 501, 401, 403, 404, 416)) assertFalse("$status", isServerLoss(badStatus, status))
    assertFalse(isServerLoss(badStatus, null))
    assertFalse(isServerLoss(PlaybackException.ERROR_CODE_UNSPECIFIED, null))
  }

  @Test fun decoderErrorIsNot() {
    assertFalse(isServerLoss(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
    assertFalse(isServerLoss(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
  }

  @Test fun httpStatusComesFromTheSourceException() {
    val http = HttpDataSource.InvalidResponseCodeException(503, "Service Unavailable", null, emptyMap(), DataSpec(Uri.parse("https://abs.example/api/items/li1/file/1")), ByteArray(0))
    val error = ExoPlaybackException.createForSource(http, badStatus)
    assertEquals(503, httpStatusOf(error))
    assertTrue(isServerLoss(error.errorCode, httpStatusOf(error)))
    assertNull(httpStatusOf(ExoPlaybackException.createForSource(IOException("reset"), failed)))
    assertNull(httpStatusOf(PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED)))
  }

  @Test fun resumesAtSamePositionWhenOnline() {
    val waiting = AwaitingServer()
    assertNull(waiting.onStreamLost("li1", null, 1234.5, true, "a"))
    assertTrue(waiting.isAwaiting)
    assertNull(waiting.onServerState(ServerState.OFFLINE, "a"))
    assertEquals(ResumeStream("li1", null, 1234.5, true, "a"), waiting.onServerState(ServerState.ONLINE, "a"))
    assertFalse(waiting.isAwaiting)
    assertNull(waiting.onServerState(ServerState.ONLINE, "a"))
  }

  @Test fun pausedStreamResumesPaused() {
    val paused = AwaitingServer()
    paused.onStreamLost("li1", null, 10.0, false, "a")
    assertEquals(false, (paused.onServerState(ServerState.ONLINE, "a") as ResumeStream).playWhenReady)
    val pausedWhileWaiting = AwaitingServer()
    pausedWhileWaiting.onStreamLost("li1", null, 10.0, true, "a")
    pausedWhileWaiting.onUserPause()
    assertTrue("a pause keeps waiting", pausedWhileWaiting.isAwaiting)
    assertEquals(false, (pausedWhileWaiting.onServerState(ServerState.ONLINE, "a") as ResumeStream).playWhenReady)
  }

  @Test fun cancelledWaitNeverResumes() {
    for (reason in CancelReason.values()) {
      val waiting = AwaitingServer()
      waiting.onStreamLost("li1", null, 10.0, true, "a")
      waiting.cancel(reason)
      assertFalse(waiting.isAwaiting)
      assertNull(waiting.onServerState(ServerState.ONLINE, "a"))
    }
    val otherServer = AwaitingServer()
    otherServer.onStreamLost("li1", null, 10.0, true, "a")
    assertNull(otherServer.onServerState(ServerState.ONLINE, "b"))
    assertNull(otherServer.onServerState(ServerState.ONLINE, null))
  }

  @Test fun queueBlockedOfflineWaitsForServer() {
    val next = PlaylistQueueItem("li2", null, "7", "a")
    val waiting = AwaitingServer()
    assertNull(waiting.onQueueBlocked(next, "a"))
    assertEquals(next, waiting.blockedItem)
    assertNull(waiting.onServerState(ServerState.OFFLINE, "a"))
    assertNull(waiting.onServerState(ServerState.UNKNOWN, "a"))
    // No position: the book starts where media_progress has it (chooseStartTime)
    assertEquals(ResumeStream("li2", null, null, true, "a", next), waiting.onServerState(ServerState.ONLINE, "a"))
    assertNull(waiting.blockedItem)
  }

  @Test fun userPlayWhileOnlineResumesNow() {
    val waiting = AwaitingServer()
    waiting.onStreamLost("li1", null, 50.0, false, "a")
    assertNull("offline: the service asks for a probe and keeps waiting", waiting.onUserPlay(ServerState.OFFLINE, "a"))
    assertTrue(waiting.isAwaiting)
    assertEquals(ResumeStream("li1", null, 50.0, true, "a"), waiting.onUserPlay(ServerState.ONLINE, "a"))
    assertFalse(waiting.isAwaiting)
    assertNull(AwaitingServer().onUserPlay(ServerState.ONLINE, "a"))
  }

  @Test fun userSeekMovesResumePosition() {
    val waiting = AwaitingServer()
    waiting.onStreamLost("li1", "e1", 50.0, true, "a")
    waiting.onUserSeek(80.0)
    assertEquals(ResumeStream("li1", "e1", 80.0, true, "a"), waiting.onServerState(ServerState.ONLINE, "a"))
    // A blocked queue item has no position of its own to move
    val blocked = AwaitingServer()
    blocked.onQueueBlocked(PlaylistQueueItem("li2", null), "a")
    blocked.onUserSeek(30.0)
    assertNull((blocked.onServerState(ServerState.ONLINE, "a") as ResumeStream).position)
  }

  @Test fun threeFailedResumesGiveUp() {
    val waiting = AwaitingServer()
    assertNull(waiting.onStreamLost("li1", null, 50.0, true, "a"))
    repeat(2) {
      assertTrue(waiting.onServerState(ServerState.ONLINE, "a") is ResumeStream)
      // Lost again before the player got ready: a failed resume
      assertNull(waiting.onStreamLost("li1", null, 50.0, true, "a"))
    }
    assertTrue(waiting.onServerState(ServerState.ONLINE, "a") is ResumeStream)
    assertEquals(AwaitingAction.GiveUp, waiting.onStreamLost("li1", null, 50.0, true, "a"))
    assertFalse(waiting.isAwaiting)

    // A resume that got the player ready resets the count
    val recovered = AwaitingServer()
    recovered.onStreamLost("li1", null, 50.0, true, "a")
    repeat(5) {
      assertTrue(recovered.onServerState(ServerState.ONLINE, "a") is ResumeStream)
      recovered.onPlaybackReady()
      assertNull(recovered.onStreamLost("li1", null, 60.0, true, "a"))
    }
  }

  @Test fun localCopyOfCurrentStreamSwitchesAtSamePosition() {
    val streaming = CurrentStream("li1", null, "a", 600.0, playWhenReady = true, isCast = false)
    val waiting = AwaitingServer()
    assertEquals(SwitchToLocal("li1", null, 600.0, true), waiting.onLocalCopyReady(LocalCopyReady("a", "li1", null), streaming))
    assertNull("another item", waiting.onLocalCopyReady(LocalCopyReady("a", "li2", null), streaming))
    assertNull("another server", waiting.onLocalCopyReady(LocalCopyReady("b", "li1", null), streaming))
    assertNull("a cast device cannot play the file", waiting.onLocalCopyReady(LocalCopyReady("a", "li1", null), streaming.copy(isCast = true)))
    assertNull("nothing streams", waiting.onLocalCopyReady(LocalCopyReady("a", "li1", null), null))
    // An episode by its server episode id
    val episode = CurrentStream("p", "e1", "a", 42.0, playWhenReady = false, isCast = false)
    assertEquals(SwitchToLocal("p", "e1", 42.0, false), waiting.onLocalCopyReady(LocalCopyReady("a", "p", "e1"), episode))
    assertNull(waiting.onLocalCopyReady(LocalCopyReady("a", "p", "e2"), episode))
  }

  @Test fun localCopyEndsTheWaitForIt() {
    val lost = AwaitingServer()
    lost.onStreamLost("li1", null, 300.0, false, "a")
    lost.onUserSeek(320.0)
    // The player's own position is stale after the loss: the wait's position wins
    val stale = CurrentStream("li1", null, "a", 0.0, playWhenReady = false, isCast = false)
    assertEquals(SwitchToLocal("li1", null, 320.0, false), lost.onLocalCopyReady(LocalCopyReady("a", "li1", null), stale))
    assertFalse(lost.isAwaiting)

    val blocked = AwaitingServer()
    blocked.onQueueBlocked(PlaylistQueueItem("li2", null), "a")
    assertNull(blocked.onLocalCopyReady(LocalCopyReady("a", "li3", null), null))
    assertTrue(blocked.isAwaiting)
    assertEquals(SwitchToLocal("li2", null, null, true), blocked.onLocalCopyReady(LocalCopyReady("a", "li2", null), null))
    assertFalse(blocked.isAwaiting)
  }
}
