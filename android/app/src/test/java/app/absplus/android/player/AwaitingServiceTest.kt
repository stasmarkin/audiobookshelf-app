package app.absplus.android.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceData
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import app.absplus.android.managers.DbManager
import app.absplus.android.media.MediaProgressSyncer
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import app.absplus.android.sync.ServerState
import com.google.android.exoplayer2.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The service's own branches while a queue waits on an item it shows; no onCreate (no ExoPlayer, no media session). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AwaitingServiceTest {
  private val ctx: Context = ApplicationProvider.getApplicationContext()
  private val itemB = PlaylistQueueItem("book-b", null)
  private val displayB = AwaitingDisplay("book-b", null, "config-1", "Book B", "Author B", null, 600.0, 42.0)
  private val playerCalls = mutableListOf<String>()
  private lateinit var pns: PlayerNotificationService

  private fun sessionA() = PlaybackSession(
    "session-a", "user", "book-a", null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
    emptyList(), "Book A", "Author A", null, 360.0, 0, 0L, 0L, 0L, mutableListOf(), 15.0, null, null, null, "config-1", null, null
  )

  // Book A's player: every call is recorded, none should come while book B is shown
  private fun player(): Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
    playerCalls.add(method.name)
    when (method.returnType) {
      java.lang.Boolean.TYPE -> false
      java.lang.Integer.TYPE -> 1
      java.lang.Long.TYPE -> 0L
      java.lang.Float.TYPE -> 1f
      else -> null
    }
  } as Player

  @Before
  fun setUp() {
    DbManager.initialize(ctx)
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
    pns = Robolectric.buildService(PlayerNotificationService::class.java).get()
    pns.currentPlayer = player()
  }

  private fun showB() {
    pns.awaiting.onQueueBlocked(itemB, "config-1", playWhenReady = false)
    pns.publishAwaitingDisplay(itemB, displayB)
    assertEquals(displayB, pns.awaitingDisplay)
  }

  @Test
  fun mediaControlsDoNotMoveTheOpenBookWhileTheAwaitedOneShows() {
    pns.currentPlaybackSession = sessionA()
    showB()
    val callback = MediaSessionCallback(pns)
    callback.onSeekTo(10_000)
    callback.onFastForward()
    callback.onRewind()
    callback.onSkipToNext()
    callback.onSkipToPrevious()
    callback.onCustomAction(CUSTOM_ACTION_JUMP_FORWARD, null)
    callback.onCustomAction(CUSTOM_ACTION_SKIP_BACKWARD, null)
    assertEquals("book A's player is not touched", emptyList<String>(), playerCalls)
  }

  @Test
  fun aServerSwitchDuringAResumeDropsTheDisplay() {
    showB()
    // The server is back: the wait hands out its resume and the display stays while B starts
    assertTrue(pns.awaiting.onServerState(ServerState.ONLINE, "config-1") is AwaitingAction.ResumeStream)
    pns.awaitingPresentation.resuming()
    assertEquals(displayB, pns.awaitingDisplay)
    pns.onActiveServerChanged("config-1", "config-2")
    assertNull(pns.awaitingDisplay)
  }

  @Test
  fun playWhileAQueueWaitsWithoutASessionChecksTheServerInsteadOfRestoring() {
    showB()
    pns.play()
    // Play reached the wait (it now plays when the server is back), not the restore of the last session
    val resume = pns.awaiting.onServerState(ServerState.ONLINE, "config-1") as AwaitingAction.ResumeStream
    assertEquals(itemB, resume.queueItem)
    assertTrue(resume.playWhenReady)
  }

  @Test
  fun playWhileTheAwaitedItemStartsDoesNotPlayTheOpenBook() {
    pns.currentPlaybackSession = sessionA()
    showB()
    assertTrue(pns.awaiting.onServerState(ServerState.ONLINE, "config-1") is AwaitingAction.ResumeStream)
    pns.awaitingPresentation.resuming()
    playerCalls.clear()
    pns.play()
    assertEquals("book A's player is not started under book B's title", emptyList<String>(), playerCalls)
  }

  @Test
  fun stopWhileWaitingEndsTheWait() {
    pns.mediaProgressSyncer = MediaProgressSyncer(pns) { error("no server calls") }
    showB()
    MediaSessionCallback(pns).onStop()
    assertNull(pns.awaitingDisplay)
    assertFalse(pns.awaiting.isAwaiting)
    assertTrue(pns.playlistQueue.isEmpty())
  }

  @Test
  fun aConnectorChangeWhileWaitingKeepsTheAwaitedItemPublished() {
    val session = MediaSessionCompat(ctx, "test")
    val connector = MediaSessionConnector(session)
    val exo = ExoPlayer.Builder(ctx).build()
    connector.setPlayer(exo)
    pns.mediaSession = session
    pns.mediaSessionConnector = connector
    pns.currentPlayer = exo
    showB()
    assertEquals(PlaybackStateCompat.STATE_PAUSED, session.controller.playbackState.state)
    // A settings change (jump times) re-reads the connector's actions
    pns.setMediaSessionConnectorPlaybackActions()
    assertEquals(PlaybackStateCompat.STATE_PAUSED, session.controller.playbackState.state)
    assertEquals(PlaybackStateCompat.ACTION_PLAY, session.controller.playbackState.actions and PlaybackStateCompat.ACTION_PLAY)
    exo.release()
    session.release()
  }
}
