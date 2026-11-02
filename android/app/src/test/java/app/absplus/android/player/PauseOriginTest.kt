package app.absplus.android.player

import app.absplus.android.player.AwaitingAction.ResumeStream
import app.absplus.android.sync.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service pauses a stream that lost its server from inside onPlayerError; ExoPlayer delivers that playWhenReady
 * change after the wait was set. Replays the sequence the way PlayerNotificationService wires it.
 */
class PauseOriginTest {
  private fun deliver(origin: PauseOrigin, waiting: AwaitingServer) {
    if (origin.isUserPause()) waiting.onUserPause()
  }

  @Test fun theServicesOwnPauseKeepsTheIntentToPlay() {
    val origin = PauseOrigin()
    val waiting = AwaitingServer()
    origin.beforeSelfPause(playWhenReady = true)
    waiting.onStreamLost("li1", null, 1298.0, true, "a")
    deliver(origin, waiting) // the queued onPlayWhenReadyChanged(false) of that pause
    origin.settle()
    assertEquals(true, (waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream).playWhenReady)
  }

  @Test fun aUserPauseDuringTheWaitStillCounts() {
    val origin = PauseOrigin()
    val waiting = AwaitingServer()
    origin.beforeSelfPause(playWhenReady = true)
    waiting.onStreamLost("li1", null, 100.0, true, "a")
    deliver(origin, waiting)
    origin.settle()
    waiting.onUserPlay(ServerState.OFFLINE, "a")
    deliver(origin, waiting) // a media key, the notification or audio becoming noisy
    assertEquals(false, (waiting.onServerState(ServerState.ONLINE, "a") as ResumeStream).playWhenReady)
  }

  @Test fun aSelfPauseOfAPausedPlayerExpectsNoEvent() {
    val origin = PauseOrigin()
    origin.beforeSelfPause(playWhenReady = false)
    assertTrue("no event comes, so the next pause is the user's", origin.isUserPause())
    origin.beforeSelfPause(playWhenReady = true)
    origin.settle() // the event never came (player gone): nothing is left to swallow a later pause
    assertTrue(origin.isUserPause())
    origin.beforeSelfPause(playWhenReady = true)
    assertFalse(origin.isUserPause())
  }
}
