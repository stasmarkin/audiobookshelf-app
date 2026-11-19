package app.absplus.android.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceData
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.WidgetEventEmitter
import app.absplus.android.managers.DbManager
import app.absplus.android.player.AwaitingDisplay
import app.absplus.android.player.CancelReason
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import com.google.android.exoplayer2.Player
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetAwaitingTest {
  private val ctx: Context = ApplicationProvider.getApplicationContext()
  private lateinit var pns: PlayerNotificationService
  private var redraws = 0
  private val itemB = PlaylistQueueItem("book-b", null)
  private val displayB = AwaitingDisplay("book-b", null, "config-1", "Book B", "Author B", null, 600.0, 42.0)

  private fun session(id: String, title: String) = PlaybackSession(
    id, "user", "book-a", null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
    emptyList(), title, "Author A", null, 360.0, 0, 0L, 0L, 0L, mutableListOf(), 15.0, null, null, null, "config-1", null, null
  )

  // Only isPlaying and playWhenReady are read on these paths
  private fun player(): Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
    when (method.name) {
      "isPlaying", "getPlayWhenReady" -> false
      else -> throw UnsupportedOperationException(method.name)
    }
  } as Player

  @Before
  fun setUp() {
    DbManager.initialize(ctx)
    val bookA = session("session-a", "Book A")
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), bookA)
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    pns = PlayerNotificationService()
    pns.currentPlaybackSession = bookA
    pns.currentPlayer = player()
    DeviceManager.widgetUpdater = object : WidgetEventEmitter {
      override fun onPlayerChanged(pns: PlayerNotificationService) { redraws++ }
      override fun onPlaybackPosition(pns: PlayerNotificationService, positionMs: Long) = Unit
      override fun onPlayerClosed() = Unit
    }
  }

  @After
  fun tearDown() {
    DeviceManager.widgetUpdater = null
  }

  @Test
  fun theWidgetShowsTheAwaitedBookAndThenTheSessionAgain() {
    pns.awaiting.onQueueBlocked(itemB, "config-1")
    pns.publishAwaitingDisplay(itemB, displayB)
    assertEquals("setting the display redraws the widget", 1, redraws)
    val awaited = DeviceManager.widgetState(ctx, pns, null)
    assertEquals("Book B", awaited.title)
    assertEquals("Author B", awaited.author)
    assertFalse(awaited.isPlaying)
    assertEquals(42_000L, awaited.positionMs)
    assertEquals(600_000L, awaited.durationMs)

    pns.stopAwaiting(CancelReason.CLOSED)
    assertEquals("the reset redraws it too", 2, redraws)
    val after = DeviceManager.widgetState(ctx, pns, null)
    assertNull(after.awaiting)
    assertEquals("Book A", after.title)

    assertEquals("session-a", DeviceManager.deviceData.lastPlaybackSession?.id)
    assertEquals("Book A", DbManager().getDeviceData().lastPlaybackSession?.displayTitle)
  }
}
