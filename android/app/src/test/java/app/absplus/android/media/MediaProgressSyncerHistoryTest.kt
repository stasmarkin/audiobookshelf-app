package app.absplus.android.media

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.player.PLAYMETHOD_DIRECTPLAY
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.player.SeekProgressPersistenceTest
import app.absplus.android.server.ApiHandler
import com.google.android.exoplayer2.Player
import io.paperdb.Paper
import java.lang.reflect.Proxy
import java.security.Provider
import java.security.Security
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaProgressSyncerHistoryTest {
  private lateinit var service: PlayerNotificationService
  private lateinit var syncer: MediaProgressSyncer
  private var positionMs = 0L
  private val held = mutableListOf<(SyncResult?) -> Unit>()
  private var syncCalls = 0

  @Before fun setup() {
    Security.addProvider(object : Provider("HistorySyncerFixture", 1.0, "Empty test-only keystore") {
      init { put("KeyStore.AndroidKeyStore", SeekProgressPersistenceTest.EmptyKeyStore::class.java.name) }
    })
    val ctx: Context = ApplicationProvider.getApplicationContext()
    DbManager.initialize(ctx)
    listOf("localMediaProgress", "playbackSession", "mediaItemHistory", "device").forEach { Paper.book(it).destroy() }
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
    DeviceManager.serverConnectionConfig = null
    DeviceManager.widgetUpdater = null
    MediaEventManager.clock = System::currentTimeMillis
    MediaEventManager.store = MediaEventManager.DbHistoryStore
    MediaEventManager.mainPoster = { it() }
    MediaEventManager.ioRunner = { it() }
    MediaEventManager.clientEventEmitter = null
    service = PlayerNotificationService()
    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }.invoke(service, ctx)
    service.currentPlayer = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
      when (method.name) {
        "getCurrentPosition" -> positionMs
        "getMediaItemCount" -> 1
        "isPlaying" -> true
        else -> throw UnsupportedOperationException(method.name)
      }
    } as Player
    syncer = MediaProgressSyncer(service, apiHandler = { ApiHandler(ctx) })
    syncer.syncRunner = { _, _, _, cb -> syncCalls++; held.add(cb) }
    service.mediaProgressSyncer = syncer
  }

  @After fun teardown() { Security.removeProvider("HistorySyncerFixture") }

  private fun session(id: String, serverAddress: String = "https://abs", currentTime: Double = 0.0) =
          MediaEventManagerTest.session("li_$id", serverAddress = serverAddress, configId = "cfg-$serverAddress", currentTime = currentTime,
                  id = "session-$id", playMethod = PLAYMETHOD_DIRECTPLAY)

  private fun journal(id: String) = DeviceManager.dbManager.getMediaItemHistory("li_$id")?.events ?: mutableListOf()

  private fun play(s: PlaybackSession) {
    service.currentPlaybackSession = s
    syncer.play(s)
  }

  @Test fun playPositionIsPlayerPosition() {
    positionMs = 200_000
    play(session("a", currentTime = 185.0))
    assertEquals(200.0, journal("a").single { it.name == "Play" }.currentTime!!.toDouble(), 0.0)
  }

  @Test fun pauseIsRecordedBeforeSyncCallback() {
    play(session("a")); positionMs = 120_000
    var paused = false
    syncer.pause { paused = true }
    val pause = journal("a").single { it.name == "Pause" }
    assertEquals(120.0, pause.currentTime!!.toDouble(), 0.0)
    assertFalse(paused)
    held.single()(SyncResult(true, true, "ok"))
    assertTrue(paused)
    assertEquals("ok", journal("a").single { it.id == pause.id }.serverSyncMessage)
  }

  @Test fun pauseWithSkippedSyncIsRecorded() {
    syncer.syncRunner = { _, _, _, cb -> cb(null) }
    play(session("a")); positionMs = 50_000
    syncer.pause {}
    assertEquals(1, journal("a").count { it.name == "Pause" })
  }

  @Test fun pauseAtZeroIsRecordedWithoutSync() {
    play(session("a")); positionMs = 0
    var paused = false
    syncer.pause { paused = true }
    assertEquals(1, journal("a").count { it.name == "Pause" })
    assertEquals(0, syncCalls)
    assertTrue(paused)
  }

  @Test fun pauseWhenNotRunningWritesNothing() {
    play(session("a")); positionMs = 10_000
    syncer.pause {}; held.single()(null)
    syncer.pause {}
    assertEquals(1, journal("a").count { it.name == "Pause" })
  }

  @Test fun stopWhenNotRunningWritesNothingAndCallsCb() {
    var calls = 0
    syncer.stop { calls++ }
    assertEquals(1, calls)
    assertNull(DeviceManager.dbManager.getMediaItemHistory("li_a"))
  }

  @Test fun stopCallbackResetsOwnSession() {
    play(session("a")); positionMs = 10_000
    var calls = 0
    syncer.stop { calls++ }
    held.single()(null)
    assertNull(syncer.currentPlaybackSession)
    assertEquals(1, calls)
  }

  @Test fun lateStopCallbackDoesNotResetNextSession() {
    play(session("a")); positionMs = 10_000
    var calls = 0
    syncer.stop { calls++ }
    val lateStop = held.removeAt(0)
    play(session("b")); positionMs = 20_000
    lateStop(SyncResult(true, true, "late"))
    assertEquals("session-b", syncer.currentSessionId)
    assertEquals(1, calls)
    syncer.pause {}
    assertEquals(1, journal("b").count { it.name == "Pause" })
    assertEquals("late", journal("a").single { it.name == "Stop" }.serverSyncMessage)
  }

  @Test fun lateSaveTickIsWrittenToItsOwnBook() {
    play(session("a", currentTime = 5.0)); positionMs = 30_000
    syncer.saveTick(true, 30.0)
    val lateSave = held.removeAt(0)
    play(session("b"))
    lateSave(SyncResult(true, true, "saved"))
    val save = journal("a").single { it.name == "Save" }
    assertEquals(30.0, save.currentTime!!.toDouble(), 0.0)
    assertEquals("saved", save.serverSyncMessage)
    assertTrue(journal("b").none { it.name == "Save" })
  }

  @Test fun lateLanSaveDoesNotRevertHttpsAddress() {
    play(session("a", serverAddress = "http://lan")); positionMs = 30_000
    syncer.saveTick(true, 30.0)
    val lateSave = held.removeAt(0)
    play(session("a", serverAddress = "https://abs").also { it.id = "session-a2" })
    lateSave(SyncResult(true, true, "saved"))
    assertEquals("https://abs", DeviceManager.dbManager.getMediaItemHistory("li_a")!!.serverAddress)
  }

  @Test fun brokenJournalDoesNotStopProgressSync() {
    play(session("a")); positionMs = 40_000
    MediaEventManager.store = object : MediaEventManager.HistoryStore by MediaEventManager.DbHistoryStore {
      override fun read(id: String) = throw IllegalStateException("synthetic corrupt journal")
      override fun write(history: MediaItemHistory) = throw IllegalStateException("synthetic write failure")
    }
    try {
      var paused = false
      syncer.pause { paused = true }
      assertEquals(1, syncCalls)
      held.single()(SyncResult(true, true, "ok"))
      assertTrue(paused)
      var stopped = false
      play(session("b")); syncer.stop { stopped = true }
      held.last()(null)
      assertTrue(stopped)
    } finally {
      MediaEventManager.store = MediaEventManager.DbHistoryStore
    }
  }

  @Test fun sleepTimerPauseIsMarked() {
    play(session("a")); positionMs = 10_000
    syncer.markNextPauseAsSleepTimer()
    syncer.pause {}
    assertEquals(MediaEventManager.SLEEP_TIMER_DESCRIPTION, journal("a").last { it.name == "Pause" }.description)
    play(session("a"))
    syncer.pause {}
    assertEquals("", journal("a").last { it.name == "Pause" }.description)
  }

  @Test fun sleepTimerFlagClearedOnPlay() {
    syncer.markNextPauseAsSleepTimer()
    play(session("a")); positionMs = 10_000
    syncer.pause {}
    assertEquals("", journal("a").single { it.name == "Pause" }.description)
  }

  @Test fun sleepTimerFlagClearedOnStop() {
    syncer.markNextPauseAsSleepTimer()
    syncer.stop {}
    play(session("a")); positionMs = 10_000
    syncer.pause {}
    assertEquals("", journal("a").single { it.name == "Pause" }.description)
  }
}
