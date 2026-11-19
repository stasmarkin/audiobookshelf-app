package app.absplus.android.player

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceData
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import app.absplus.android.sync.ServerState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A queue that waits for the server with nothing prepared, and the start Android 12+ refuses from the background. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AwaitingForegroundTest {
  private val ctx: Context = ApplicationProvider.getApplicationContext()
  private val itemB = PlaylistQueueItem("book-b", null)
  private var starts = 0
  private lateinit var pns: PlayerNotificationService

  private fun streamOfB() = PlaybackSession(
    "session-b", "user", "book-b", null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
    emptyList(), "Book B", "Author B", null, 360.0, 0, 0L, 0L, 0L, mutableListOf(), 15.0, null, null, null, "config-1", null, null
  )

  private fun refuseStarts() {
    pns.startSelfInForeground = { starts++; throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed due to mAllowStartForeground false") }
  }

  private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

  @Before
  fun setUp() {
    DbManager.initialize(ctx)
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
    PlayerNotificationService.isStarted = false
    PlayerNotificationListener.isForegroundService = false
    pns = Robolectric.buildService(PlayerNotificationService::class.java).get()
    pns.startSelfInForeground = { starts++ }
    pns.playlistQueue = listOf(itemB)
    pns.playlistQueueIndex = 0
  }

  @After
  fun tearDown() {
    PlayerNotificationService.isStarted = false
    PlayerNotificationListener.isForegroundService = false
  }

  @Test
  fun theServerComingBackWithTheAppInTheBackgroundWaitsForAPlayInsteadOfCrashing() {
    refuseStarts()
    // The resume's play request answered (a stale wait resumes paused): its session reaches preparePlayer with the
    // service never started
    pns.preparePlayer(streamOfB(), false, 1f)
    assertEquals("the start was tried once", 1, starts)
    assertNull("nothing is prepared", pns.currentPlaybackSession)
    assertEquals("the queue waits on its item again", itemB, pns.awaiting.blockedItem)
    val notice = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.single()
    assertEquals("Tap Play to continue", notice.text())
    // Three server returns in the background are refused three times: no failed resumes, the queue stays
    repeat(3) {
      assertTrue(pns.awaiting.onServerState(ServerState.ONLINE, "config-1") is AwaitingAction.ResumeStream)
      pns.preparePlayer(streamOfB(), false, 1f)
    }
    assertEquals(itemB, pns.awaiting.blockedItem)
    // The notification's Play reaches the wait, not the restore of the last session: the item then starts playing
    pns.play()
    assertEquals("the Play tried to make the service foreground", 5, starts)
    val resume = pns.awaiting.onServerState(ServerState.ONLINE, "config-1") as AwaitingAction.ResumeStream
    assertEquals(itemB, resume.queueItem)
    assertTrue(resume.playWhenReady)
  }

  @Test
  fun aRefusedDownloadStartsAgainOnPlayWithoutTheServer() {
    refuseStarts()
    pns.preparePlayer(streamOfB().apply { playMethod = PLAYMETHOD_LOCAL }, true, 1f)
    assertEquals(itemB, pns.awaiting.blockedItem)
    // The server is still away: the Play starts the queue's item again, which finds the download
    pns.play()
    assertNull(pns.awaiting.blockedItem)
  }

  @Test
  fun anotherBookStartedWhileTheWaitHoldsTheForegroundKeepsTheService() {
    pns.awaiting.onQueueBlocked(itemB, "config-1")
    pns.holdForegroundForWait()
    // What the start above makes Android deliver
    PlayerNotificationService.isStarted = true
    // For example Android Auto starts book C, which replaces the wait. The bare instance has no player: preparing
    // stops at it, after the wait has ended
    val c = streamOfB().apply { id = "session-c"; libraryItemId = "book-c" }
    val stoppedAt = runCatching { pns.preparePlayer(c, true, 1f) }.exceptionOrNull()
    assertTrue("reached the player, past the end of the wait: $stoppedAt", stoppedAt is UninitializedPropertyAccessException)
    assertNull(pns.awaiting.blockedItem)
    assertFalse("the service is not stopped under the new book", shadowOf(pns).isStoppedBySelf)
    assertFalse(shadowOf(pns).isForegroundStopped)
    assertTrue(PlayerNotificationListener.isForegroundService)
  }

  @Test
  fun aQueueWaitKeepsTheServiceForegroundUntilItEnds() {
    pns.awaiting.onQueueBlocked(itemB, "config-1")
    pns.holdForegroundForWait()
    assertEquals("started from the app, while it may", 1, starts)
    assertTrue(PlayerNotificationListener.isForegroundService)
    assertEquals("Waiting for server", shadowOf(pns).lastForegroundNotification.text())
    // The app clears the queue: the wait ends with nothing prepared
    pns.playlistQueue = emptyList()
    pns.playlistQueueIndex = -1
    pns.onQueueReplaced()
    assertNull(pns.awaiting.blockedItem)
    assertTrue("the foreground goes with the wait", shadowOf(pns).isForegroundStopped)
    assertFalse(PlayerNotificationListener.isForegroundService)
  }

  @Test
  fun aRefusedForegroundForTheWaitIsNotACrash() {
    refuseStarts()
    pns.awaiting.onQueueBlocked(itemB, "config-1")
    pns.holdForegroundForWait()
    assertFalse(PlayerNotificationListener.isForegroundService)
    assertNull(shadowOf(pns).lastForegroundNotification)
  }
}
