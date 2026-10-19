package app.absplus.android.player

import android.net.Uri
import app.absplus.android.player.AwaitingAction.ResumeStream
import app.absplus.android.player.AwaitingAction.SwitchToLocal
import app.absplus.android.data.StreamingUsingCellularSetting
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

  @Test fun startsRefusedByAndroidAreNoFailedResumes() {
    val waiting = AwaitingServer()
    val item = PlaylistQueueItem("li2", null)
    assertNull(waiting.onQueueBlocked(item, "a"))
    repeat(3) {
      assertTrue(waiting.onServerState(ServerState.ONLINE, "a") is ResumeStream)
      waiting.onStartRefused(item, "a", playWhenReady = true)
    }
    assertEquals(item, waiting.blockedItem)
    // A fourth failure that is a real one counts as the first
    assertTrue(waiting.onServerState(ServerState.ONLINE, "a") is ResumeStream)
    assertNull(waiting.onQueueBlocked(item, "a"))
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
    assertEquals(SwitchToLocal("li1", null, 600.0, true, fromPlayer = true), waiting.onLocalCopyReady(LocalCopyReady("a", "li1", null), streaming))
    assertNull("another item", waiting.onLocalCopyReady(LocalCopyReady("a", "li2", null), streaming))
    assertNull("another server", waiting.onLocalCopyReady(LocalCopyReady("b", "li1", null), streaming))
    assertNull("a cast device cannot play the file", waiting.onLocalCopyReady(LocalCopyReady("a", "li1", null), streaming.copy(isCast = true)))
    assertNull("nothing streams", waiting.onLocalCopyReady(LocalCopyReady("a", "li1", null), null))
    // An episode by its server episode id
    val episode = CurrentStream("p", "e1", "a", 42.0, playWhenReady = false, isCast = false)
    assertEquals(SwitchToLocal("p", "e1", 42.0, false, fromPlayer = true), waiting.onLocalCopyReady(LocalCopyReady("a", "p", "e1"), episode))
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

  private val metered = { setting: StreamingUsingCellularSetting -> StreamPermission(metered = true, cellular = setting) }

  @Test fun resumeFailedWhileOnlineGivesUp() {
    val waiting = AwaitingServer()
    waiting.onStreamLost("li1", null, 50.0, true, "a")
    val resume = waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream
    // The server answers but refuses the play request: nothing to wait for
    assertEquals(AwaitingAction.GiveUp, waiting.onResumeFailed(resume, ServerState.ONLINE))
    assertFalse(waiting.isAwaiting)

    val away = AwaitingServer()
    away.onStreamLost("li1", null, 50.0, true, "a")
    val again = away.onServerState(ServerState.ONLINE, "a") as ResumeStream
    assertNull(away.onResumeFailed(again, ServerState.OFFLINE))
    assertTrue("gone again: waits at the same position", away.isAwaiting)
    assertEquals(ResumeStream("li1", null, 50.0, true, "a"), away.onServerState(ServerState.ONLINE, "a"))
  }

  @Test fun cellularNeverKeepsWaitingUntilUnmetered() {
    val waiting = AwaitingServer()
    waiting.onStreamLost("li1", null, 50.0, true, "a")
    assertNull(waiting.onServerState(ServerState.ONLINE, "a", metered(StreamingUsingCellularSetting.NEVER)))
    assertNull("a Play does not stream either", waiting.onUserPlay(ServerState.ONLINE, "a", metered(StreamingUsingCellularSetting.NEVER)))
    assertTrue(waiting.isAwaiting)
    assertEquals(ResumeStream("li1", null, 50.0, true, "a"), waiting.onServerState(ServerState.ONLINE, "a", StreamPermission.UNMETERED))
  }

  @Test fun cellularAskResumesPaused() {
    val waiting = AwaitingServer()
    waiting.onStreamLost("li1", null, 50.0, true, "a")
    assertEquals(false, (waiting.onServerState(ServerState.ONLINE, "a", metered(StreamingUsingCellularSetting.ASK)) as ResumeStream).playWhenReady)
    val always = AwaitingServer()
    always.onQueueBlocked(PlaylistQueueItem("li2", null), "a")
    assertEquals(true, (always.onServerState(ServerState.ONLINE, "a", metered(StreamingUsingCellularSetting.ALWAYS)) as ResumeStream).playWhenReady)
    // The user's own Play is the confirmation Ask wants
    val user = AwaitingServer()
    user.onStreamLost("li1", null, 50.0, false, "a")
    assertEquals(true, (user.onUserPlay(ServerState.ONLINE, "a", metered(StreamingUsingCellularSetting.ASK)) as ResumeStream).playWhenReady)
  }

  @Test fun longWaitResumesPaused() {
    var now = 0L
    val waiting = AwaitingServer { now }
    waiting.onStreamLost("li1", null, 50.0, true, "a")
    now = 31 * 60_000L
    assertEquals(false, (waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream).playWhenReady)
    // An explicit Play after a long wait plays
    val user = AwaitingServer { now }
    now = 0L
    user.onStreamLost("li1", null, 50.0, true, "a")
    now = 31 * 60_000L
    assertEquals(true, (user.onUserPlay(ServerState.ONLINE, "a") as ResumeStream).playWhenReady)
    // A failed resume does not restart the clock
    val flapping = AwaitingServer { now }
    now = 0L
    flapping.onStreamLost("li1", null, 50.0, true, "a")
    now = 20 * 60_000L
    flapping.onResumeFailed(flapping.onServerState(ServerState.ONLINE, "a") as ResumeStream, ServerState.OFFLINE)
    now = 31 * 60_000L
    assertEquals(false, (flapping.onServerState(ServerState.ONLINE, "a") as ResumeStream).playWhenReady)
  }

  @Test fun lostStreamAndBlockedQueueAreTold() {
    val lost = AwaitingServer()
    lost.onStreamLost("li1", null, 50.0, true, "a")
    assertTrue(lost.isStreamLost)
    val blocked = AwaitingServer()
    blocked.onQueueBlocked(PlaylistQueueItem("li2", null), "a")
    assertFalse(blocked.isStreamLost)
  }

  @Test fun serverGoneStatusOnResumeWaitsAgain() {
    val waiting = AwaitingServer()
    waiting.onStreamLost("li1", null, 50.0, true, "a")
    repeat(2) {
      val resume = waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream
      // A proxy answers 502 for the play request: the server is still away
      assertNull(waiting.onResumeFailed(resume, ServerState.ONLINE, httpStatus = 502))
      assertTrue(waiting.isAwaiting)
    }
    val third = waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream
    assertEquals(AwaitingAction.GiveUp, waiting.onResumeFailed(third, ServerState.ONLINE, httpStatus = 530))
    for (status in listOf(404, 500, 501, null)) {
      val refused = AwaitingServer()
      refused.onStreamLost("li1", null, 50.0, true, "a")
      assertEquals("$status", AwaitingAction.GiveUp, refused.onResumeFailed(refused.onServerState(ServerState.ONLINE, "a") as ResumeStream, ServerState.ONLINE, status))
    }
  }

  @Test fun resumeKeepsTheServersTrackCount() {
    val waiting = AwaitingServer()
    waiting.onStreamLost("li1", null, 50.0, true, "a", expectedTracks = 3)
    val resume = waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream
    assertEquals(3, resume.expectedTracks)
    waiting.onResumeFailed(resume, ServerState.OFFLINE)
    assertEquals(3, (waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream).expectedTracks)
  }

  @Test fun pauseDuringAFailedResumeWaitsPaused() {
    val gate = StartGate()
    val waiting = AwaitingServer()
    waiting.onStreamLost("li1", null, 50.0, true, "a")
    val resume = waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream
    val ticket = gate.begin(resume.playWhenReady)
    gate.pause()
    // The service restores the wait with the intent the gate holds now
    waiting.onResumeFailed(resume.copy(playWhenReady = gate.admit(ticket)!!), ServerState.OFFLINE)
    assertEquals(false, (waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream).playWhenReady)
  }

  private fun queueStart(plan: PlaybackSource, queue: List<PlaylistQueueItem>, index: Int, state: ServerState, forceStream: Boolean = false) =
          queueItemToAwait(StartRequest(plan.let { (it as? PlaybackSource.Stream)?.libraryItemId ?: "li1" }, (plan as? PlaybackSource.Stream)?.episodeId,
                  playWhenReady = true, forceStream = forceStream), plan, queue, index, state)

  @Test fun queueStartOnAnUndownloadedBookOfflineWaitsForTheServer() {
    val queue = listOf(PlaylistQueueItem("li0", null), PlaylistQueueItem("li1", null, expectedTracks = 3), PlaylistQueueItem("pod", "ep1"))
    for (state in listOf(ServerState.OFFLINE, ServerState.UNKNOWN)) {
      assertEquals("$state: the queue waits on the item it starts", queue[1], queueStart(PlaybackSource.Stream("li1", null), queue, 1, state))
    }
    assertEquals("an episode too", queue[2], queueStart(PlaybackSource.Stream("pod", "ep1"), queue, 2, ServerState.OFFLINE))
    // As before
    assertNull("online it streams", queueStart(PlaybackSource.Stream("li1", null), queue, 1, ServerState.ONLINE))
    assertNull("a refused sign-in fails as before", queueStart(PlaybackSource.Stream("li1", null), queue, 1, ServerState.UNAUTHORIZED))
    assertNull("a single Play outside the queue", queueStart(PlaybackSource.Stream("li1", null), emptyList(), -1, ServerState.OFFLINE))
    assertNull("an item the queue is not at", queueStart(PlaybackSource.Stream("li1", null), queue, 0, ServerState.OFFLINE))
    assertNull("another episode of the podcast", queueStart(PlaybackSource.Stream("pod", "ep2"), queue, 2, ServerState.OFFLINE))
    assertNull("a cast start", queueStart(PlaybackSource.Stream("li1", null), queue, 1, ServerState.OFFLINE, forceStream = true))
    assertNull("a downloaded copy plays", queueItemToAwait(StartRequest("li0", null, playWhenReady = true), PlaybackSource.NotFound("x"), queue, 0, ServerState.OFFLINE))
  }

  /** runStart is not unit-tested (a service); its source shows a replaced start is not reported as waiting. */
  @Test fun aReplacedQueueStartIsNotReportedAsWaiting() {
    val source = java.io.File("src/main/java/app/absplus/android/player/PlayerNotificationService.kt").readText()
    assertTrue(source.contains("answer = { started -> onResult(if (started != null) StartResult.AwaitingServer(started.display) else StartResult.Replaced) }"))
    assertTrue(Regex("""private suspend fun awaitQueueStart\(ticket: StartGate.Ticket, item: PlaylistQueueItem, onResult: \(StartResult\) -> Unit\) \{\s*//[^\n]*\n\s*val playWhenReady = startGate.admit\(ticket\) \?: return onResult\(StartResult.Replaced\)""").containsMatchIn(source))
  }

  @Test fun aQueueWaitsThroughAPingedServerUntilTheSignIn() {
    val item = PlaylistQueueItem("li1", null)
    val waiting = AwaitingServer()
    assertNull(waiting.onQueueBlocked(item, "a"))
    assertNull("online, not signed in: kept", waiting.onServerState(ServerState.ONLINE, readyServerConfigId("a", null)))
    assertNull("signed in to another server: kept", waiting.onServerState(ServerState.ONLINE, readyServerConfigId("a", "b")))
    assertEquals(item, waiting.blockedItem)
    assertEquals(item, (waiting.onServerState(ServerState.ONLINE, readyServerConfigId("a", "a")) as ResumeStream).queueItem)
  }

  @Test fun aQueueStartBeforeTheSignInWaits() {
    val queue = listOf(PlaylistQueueItem("li1", null))
    val request = StartRequest("li1", null, playWhenReady = true)
    assertEquals(queue[0], queueItemToAwait(request, PlaybackSource.Stream("li1", null), queue, 0, ServerState.ONLINE, serverReady = false))
    assertNull(queueItemToAwait(request, PlaybackSource.Stream("li1", null), queue, 0, ServerState.ONLINE, serverReady = true))
  }
}
