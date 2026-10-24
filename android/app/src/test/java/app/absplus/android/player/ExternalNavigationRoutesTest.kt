package app.absplus.android.player

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import com.google.android.exoplayer2.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalNavigationRoutesTest {
  private lateinit var service: PlayerNotificationService
  private lateinit var callback: MediaSessionCallback
  private var position = 50_000L
  private var index = 0
  private val seeks = mutableListOf<Pair<Int, Long>>()
  private val fileSkips = mutableListOf<String>()
  private val settings get() = DeviceManager.deviceData.deviceSettings!!
  private val session get() = service.currentPlaybackSession!!
  private var keyTime = 500L

  @Before fun setup() {
    DbManager.initialize(ApplicationProvider.getApplicationContext<Context>())
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
    service = PlayerNotificationService()
    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }
      .invoke(service, ApplicationProvider.getApplicationContext<Context>())
    val tracks = mutableListOf(AudioTrack(1, 0.0, 180.0, "Synthetic", "", "audio/wav", null, true, "file-1", 1))
    service.currentPlaybackSession = PlaybackSession("synthetic", null, "book", null, "book", MediaTypeMetadata("Synthetic", false),
      DeviceInfo("fixture", "fixture", "fixture", 35, "0"), emptyList(), "Synthetic", null, null, 180.0,
      0, 0L, 0L, 0L, tracks, 50.0, null, null, null, null, null, null)
    service.currentPlayer = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
      when (method.name) {
        "getMediaItemCount" -> tracks.size
        "getCurrentMediaItemIndex" -> index
        "getCurrentPosition" -> position
        "getPlayWhenReady", "isPlaying" -> false
        "seekTo" -> {
          if (args!!.size == 2) { index = args[0] as Int; position = args[1] as Long }
          else position = args[0] as Long
          seeks.add(index to position); null
        }
        "seekToNext", "seekToPrevious" -> { fileSkips.add(method.name); null }
        else -> throw UnsupportedOperationException(method.name)
      }
    } as Player
    callback = MediaSessionCallback(service)
  }

  private fun key(action: Int, code: Int, time: Long = 100, repeat: Int = 0) =
    Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(time, time, action, code, repeat))

  @Test fun defaultTransportNextMatchesHardwareNext() {
    callback.onSkipToNext()
    assertEquals(listOf(0 to 60_000L), seeks)
    assertTrue(fileSkips.isEmpty())
  }

  @Test fun hardwareNextActsOnceForDownUpAndDuplicateDelivery() {
    callback.onMediaButtonEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
    assertTrue(seeks.isEmpty())
    val up = key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
    callback.onMediaButtonEvent(up)
    callback.onMediaButtonEvent(up)
    assertEquals(listOf(0 to 60_000L), seeks)
  }

  private fun press(code: Int, time: Long = ++keyTime) {
    assertTrue(callback.onMediaButtonEvent(key(KeyEvent.ACTION_DOWN, code, time)))
    assertTrue(callback.onMediaButtonEvent(key(KeyEvent.ACTION_UP, code, time)))
  }

  private fun chapters() {
    session.chapters = listOf(BookChapter(1, 0.0, 60.0, "One"), BookChapter(2, 60.0, 120.0, "Two"), BookChapter(3, 120.0, 180.0, "Three"))
  }

  @Test fun longForwardJumpOnVeryShortMediaNeverProducesANegativePosition() {
    session.audioTracks.single().duration = 1.0
    position = 500
    service.seekForward(1_800_000)
    assertEquals(0L, position)
  }

  @Test fun longBackwardJumpClampsAtStartAndDoesNotChangeShortIncrements() {
    settings.longJumpBackwardsTime = 1800
    settings.longJumpForwardTime = 900
    settings.jumpBackwardsTime = 15
    settings.jumpForwardTime = 20
    service.seekBackward(1_800_000)
    assertEquals(0L, position)
    service.notificationControls(service.currentPlayer).seekForward()
    assertEquals(20_000L, position)
    service.notificationControls(service.currentPlayer).seekBack()
    assertEquals(5_000L, position)
  }

  @Test fun anExactEndJumpUsesTheSameGuardAsAnOvershoot() {
    position = 150_000
    service.seekForward(30_000)
    assertEquals(178_000L, position)
    service.seekForward(1_800_000)
    assertEquals(178_000L, position)
  }

  private fun twoFiles() {
    session.audioTracks[0].duration = 90.0
    session.audioTracks.add(AudioTrack(2, 90.0, 90.0, "Second", "", "audio/wav", null, true, "file-2", 2))
  }

  @Test fun everyNextPreviousRouteUsesTheSelectedMode() {
    chapters()
    twoFiles()
    listOf("time" to 60_000L, "chapters" to 60_000L, "files" to 90_000L).forEach { (mode, expected) ->
      settings.externalMediaNavigation = mode
      listOf<() -> Unit>({ callback.onSkipToNext() }, { press(KeyEvent.KEYCODE_MEDIA_NEXT) },
        { callback.onCustomAction(CUSTOM_ACTION_SKIP_FORWARD, null) }).forEach { command ->
        index = 0; position = 50_000; seeks.clear(); command()
        assertEquals(mode, expected, session.getTrackStartOffsetMs(index) + position)
        assertEquals(1, seeks.size)
      }
      listOf<() -> Unit>({ callback.onSkipToPrevious() }, { press(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
        { callback.onCustomAction(CUSTOM_ACTION_SKIP_BACKWARD, null) }).forEach { command ->
        index = 0; position = 50_000; seeks.clear(); command()
        assertEquals(if (mode == "time") 40_000L else 0L, position)
        assertEquals(1, seeks.size)
      }
    }
    assertTrue(fileSkips.isEmpty())
  }

  @Test fun fastForwardRewindAndNotificationAlwaysUseCurrentTimeIncrements() {
    settings.externalMediaNavigation = "chapters"
    chapters()
    settings.jumpForwardTime = 30
    settings.jumpBackwardsTime = 15
    callback.onFastForward(); assertEquals(80_000, position)
    callback.onRewind(); assertEquals(65_000, position)
    press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); assertEquals(95_000, position)
    press(KeyEvent.KEYCODE_MEDIA_REWIND); assertEquals(80_000, position)
    val notification = service.notificationControls(service.currentPlayer)
    settings.jumpForwardTime = 5
    assertEquals(5000, notification.seekForwardIncrement)
    notification.seekForward(); assertEquals(85_000, position)
    notification.seekBack(); assertEquals(70_000, position)
  }

  @Test fun chaptersWorkWithinSingleFileAndAcrossFiles() {
    settings.externalMediaNavigation = "chapters"; chapters()
    callback.onSkipToNext(); assertEquals(60_000, position)
    position = 62_000; callback.onSkipToPrevious(); assertEquals(0, position)
    position = 68_000; callback.onSkipToPrevious(); assertEquals(60_000, position)
    twoFiles(); position = 65_000
    callback.onSkipToNext(); assertEquals(1 to 30_000L, seeks.last())
    callback.onSkipToPrevious(); assertEquals(0 to 60_000L, seeks.last())
  }

  @Test fun fileNavigationIsAdjacentAndDoesNotLeaveTheCurrentItem() {
    settings.externalMediaNavigation = "files"; twoFiles()
    callback.onSkipToNext(); assertEquals(1 to 0L, seeks.last())
    val count = seeks.size
    callback.onSkipToNext(); assertEquals(count, seeks.size)
    callback.onSkipToPrevious(); assertEquals(0 to 0L, seeks.last())
    callback.onSkipToPrevious(); assertEquals(0 to 0L, seeks.last())
  }

  @Test fun malformedChaptersFallBackToTimeInsteadOfInventingChapters() {
    settings.externalMediaNavigation = "chapters"
    listOf(emptyList(), listOf(BookChapter(1, Double.NaN, 30.0, null)),
      listOf(BookChapter(1, 60.0, 30.0, null)), listOf(BookChapter(1, -1.0, 30.0, null)),
      listOf(BookChapter(1, 0.0, 200.0, null)),
      listOf(BookChapter(1, 0.0, 80.0, null), BookChapter(2, 60.0, 90.0, null))).forEach {
      session.chapters = it; position = 50_000
      callback.onSkipToNext(); assertEquals(60_000, position)
    }
  }

  @Test fun podcastsAndTranscodedFilesUseTimeFallbackAndDoNotAdvanceQueues() {
    settings.externalMediaNavigation = "chapters"; chapters()
    session.mediaType = "podcast"
    callback.onSkipToNext(); assertEquals(60_000, position)
    settings.externalMediaNavigation = "files"; twoFiles(); session.playMethod = PLAYMETHOD_TRANSCODE
    callback.onSkipToNext(); assertEquals(70_000, position)
    assertTrue(fileSkips.isEmpty())
  }

  @Test fun beginningEndPausedAndMissingSessionsAreSafe() {
    position = 1000; callback.onSkipToPrevious(); assertEquals(0, position)
    position = 179_000; val count = seeks.size
    callback.onSkipToNext(); assertEquals(count, seeks.size)
    service.currentPlaybackSession = null
    callback.onSkipToNext(); callback.onSkipToPrevious(); callback.onFastForward(); callback.onRewind()
    assertEquals(count, seeks.size)
    service = PlayerNotificationService(); callback = MediaSessionCallback(service)
    callback.onSkipToNext(); callback.onFastForward() // no initialized player
  }

  @Test fun repeatsCancelledKeysAndDuplicateIntentsDoNotAddNavigation() {
    press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 100)
    callback.onMediaButtonEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, repeat = 3))
    callback.onMediaButtonEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    press(KeyEvent.KEYCODE_MEDIA_NEXT, 200)
    callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT,
      KeyEvent(300, 300, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0, 0, 0, KeyEvent.FLAG_CANCELED)))
    assertEquals(listOf(0 to 60_000L, 0 to 70_000L), seeks)
    press(KeyEvent.KEYCODE_MEDIA_NEXT, 400) // a real subsequent press still works
    assertEquals(80_000, position)
  }

  @Test fun widgetKeysWithoutTimestampsActOnEveryPress() {
    // MediaButtonReceiver.buildMediaButtonPendingIntent (home widget) sends the same KeyEvent(ACTION_DOWN, code),
    // with no times, on every tap. Those deliveries cannot be told apart, so each one is a separate press.
    val widget = { code: Int -> Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code)) }
    repeat(3) { assertTrue(callback.onMediaButtonEvent(widget(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))) }
    assertEquals(80_000, position)
    repeat(2) { callback.onMediaButtonEvent(widget(KeyEvent.KEYCODE_MEDIA_REWIND)) }
    assertEquals(60_000, position)
  }

  @Test fun settingsPersistAndLegacyNullUnknownModesDefaultToTime() {
    assertEquals("time", DeviceSettings.default().externalMediaNavigation)
    settings.externalMediaNavigation = "chapters"
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    DeviceManager.deviceData = DeviceManager.dbManager.getDeviceData()
    assertEquals("chapters", settings.externalMediaNavigation)
    listOf(null, "", "unknown").forEach {
      settings.externalMediaNavigation = it; position = 50_000
      callback.onSkipToNext(); assertEquals(60_000, position)
    }
  }

  @Test fun restoredSessionAndPlaylistSurviveNavigationWithoutPlaybackOrQueueChanges() {
    twoFiles(); chapters(); settings.externalMediaNavigation = "chapters"
    service.playlistQueue = listOf(PlayerNotificationService.PlaylistQueueItem("book", null), PlayerNotificationService.PlaylistQueueItem("another", null))
    service.playlistQueueIndex = 0
    val queue = service.playlistQueue
    DeviceManager.deviceData.lastPlaybackSession = session
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    service.currentPlaybackSession = DeviceManager.dbManager.getDeviceData().lastPlaybackSession
    callback.onSkipToNext(); assertEquals(60_000, position)
    assertSame(queue, service.playlistQueue)
    assertEquals(0, service.playlistQueueIndex)
    assertFalse(service.currentPlayer.playWhenReady)
    // The on-screen explicit seek remains independent of the external mode.
    service.seekPlayer(125_000); assertEquals(1 to 35_000L, seeks.last())
  }
}
