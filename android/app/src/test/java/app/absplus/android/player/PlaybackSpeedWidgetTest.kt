package app.absplus.android.player

import android.app.Activity
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
import app.absplus.android.media.MediaManager
import app.absplus.android.media.MediaProgressSyncer
import app.absplus.android.media.readSavedPlaybackRate
import app.absplus.android.server.ApiHandler
import app.absplus.android.widget.WidgetRenderer
import com.google.android.exoplayer2.Player
import java.lang.reflect.Proxy
import java.security.Provider
import java.security.Security
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackSpeedWidgetTest {
  private lateinit var ctx: Context
  private val prefs get() = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
  private var redraws = 0

  private fun session() = PlaybackSession(
    "session-a", "user", "server-item", null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
    emptyList(), "Book", "Author", null, 360.0, 0, 0L, 0L, 0L, mutableListOf(), 15.0, null, null, null, "config-1", null, null
  )

  private fun fallbackState(): WidgetRenderer.State =
    WidgetRenderer::class.java.getDeclaredMethod("fallbackState", Context::class.java).apply { isAccessible = true }.invoke(WidgetRenderer, ctx) as WidgetRenderer.State

  @Before fun setUp() {
    // ApiHandler opens the AndroidKeyStore eagerly; Robolectric has none
    Security.addProvider(object : Provider("SpeedFixture", 1.0, "Empty test-only keystore") {
      init { put("KeyStore.AndroidKeyStore", SeekProgressPersistenceTest.EmptyKeyStore::class.java.name) }
    })
    ctx = ApplicationProvider.getApplicationContext()
    prefs.edit().clear().commit()
    DbManager.initialize(ctx)
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), session())
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    DeviceManager.widgetUpdater = object : WidgetEventEmitter {
      override fun onPlayerChanged(pns: PlayerNotificationService) { redraws++ }
      override fun onPlaybackPosition(pns: PlayerNotificationService, positionMs: Long) = Unit
      override fun onPlayerClosed() = Unit
    }
  }

  @After fun tearDown() {
    DeviceManager.widgetUpdater = null
  }

  @Test fun aSpeedChangeRedrawsTheWidget() {
    val pns = PlayerNotificationService()
    pns.mediaManager = MediaManager(ApiHandler(ctx), ctx)
    pns.mediaProgressSyncer = MediaProgressSyncer(pns, ApiHandler(ctx))
    pns.currentPlayer = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
      if (method.name == "setPlaybackSpeed") null else throw UnsupportedOperationException(method.name)
    } as Player
    pns.setPlaybackSpeed(1.5f)
    assertEquals(1, redraws)
  }

  @Test fun aSpeedFromTheNotificationIsSavedEvenBeforeTheAppSavedAnySettings() {
    assertNull(readSavedPlaybackRate(ctx))
    MediaManager(ApiHandler(ctx), ctx).setSavedPlaybackRate(1.5f)
    assertEquals(1.5f, readSavedPlaybackRate(ctx)!!, 0f)
  }

  @Test fun theWidgetWithoutAPlayerDrawsAtTheSavedSpeed() {
    assertEquals(1f, fallbackState().speed, 0f)
    prefs.edit().putString("userSettings", """{"playbackRate":2,"mobileOrderBy":"addedAt"}""").commit()
    assertEquals(2f, fallbackState().speed, 0f)
    prefs.edit().putString("userSettings", "not json").commit()
    assertEquals(1f, fallbackState().speed, 0f)
  }
}
