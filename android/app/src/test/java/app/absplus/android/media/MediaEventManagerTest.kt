package app.absplus.android.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.player.PLAYMETHOD_DIRECTPLAY
import app.absplus.android.player.PLAYMETHOD_LOCAL
import app.absplus.android.player.PlayerNotificationService
import io.paperdb.Paper
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaEventManagerTest {
  private val day = 24L * 60 * 60 * 1000
  private val now = 100L * day
  private val posted = mutableListOf<() -> Unit>()

  @Before fun setup() {
    val ctx: Context = ApplicationProvider.getApplicationContext()
    DbManager.initialize(ctx)
    Paper.book("mediaItemHistory").destroy()
    MediaEventManager.clock = { now }
    MediaEventManager.store = MediaEventManager.DbHistoryStore
    MediaEventManager.mainPoster = { posted.add(it) }
    MediaEventManager.ioRunner = { it() }
    MediaEventManager.emptyKeyChecked = false
    MediaEventManager.clientEventEmitter = null
  }

  @After fun teardown() {
    MediaEventManager.clock = System::currentTimeMillis
    MediaEventManager.store = MediaEventManager.DbHistoryStore
  }

  companion object {
    fun local(id: String, libraryItemId: String? = null, serverAddress: String? = null) =
            LocalLibraryItem(id, "internal-$id", "", "", "", false, "book", MediaType(MediaTypeMetadata("Synthetic $id", false), null),
                    mutableListOf(), null, null, true, null, serverAddress, null, libraryItemId)

    fun session(
            libraryItemId: String?,
            episodeId: String? = null,
            localItem: LocalLibraryItem? = null,
            localEpisodeId: String? = null,
            playMethod: Int = PLAYMETHOD_DIRECTPLAY,
            serverAddress: String? = "https://abs",
            configId: String? = "cfg-https",
            userId: String? = "user-1",
            currentTime: Double = 10.0,
            id: String = "session-${UUID.randomUUID()}"
    ) = PlaybackSession(id, userId, libraryItemId, episodeId, "book", MediaTypeMetadata("Synthetic", false),
            DeviceInfo("d", "m", "x", 35, "0"), emptyList(), "Synthetic", null, null, 3600.0, playMethod, 1L, 1L, 0L,
            mutableListOf(AudioTrack(1, 0.0, 3600.0, "Synthetic", "", "audio/wav", null, true, "file", 1)),
            currentTime, null, localItem, localEpisodeId, configId, serverAddress, null)
  }

  private fun history(key: String) = DeviceManager.dbManager.getMediaItemHistory(key)

  private fun event(name: String, timestamp: Long) = MediaItemEvent(name, "Playback", "", 1.0, false, null, null, timestamp, UUID.randomUUID().toString())

  private fun seed(key: String, vararg events: MediaItemEvent) {
    DeviceManager.dbManager.saveMediaItemHistory(MediaItemHistory(key, "Seed", key, null, false, null, null, null, 1L, events.toMutableList()))
  }

  @Test fun legacyEventWithoutIdIsReadable() {
    val fixture = javaClass.classLoader!!.getResourceAsStream("history/legacy-mediaItemHistory.pt")!!.readBytes()
    File(Paper.book("mediaItemHistory").path).mkdirs()
    File(Paper.book("mediaItemHistory").path, "legacy.pt").writeBytes(fixture)
    val event = history("legacy")!!.events.single()
    assertNull(event.id)
    assertEquals("Pause", event.name)
    assertEquals(42.0, event.currentTime!!.toDouble(), 0.0)
    assertEquals(1000L, event.timestamp)
  }

  @Test fun historyKeyCoversServerLinkedLocalAndEpisode() {
    assertEquals("li_1", MediaEventManager.historyKey(session("li_1")))
    assertEquals("li_1-ep_1", MediaEventManager.historyKey(session("li_1", episodeId = "ep_1")))
    assertEquals("li_1", MediaEventManager.historyKey(session("li_1", localItem = local("local_1", "li_1"), playMethod = PLAYMETHOD_LOCAL)))
    assertEquals("local_1", MediaEventManager.historyKey(session(null, localItem = local("local_1"), playMethod = PLAYMETHOD_LOCAL)))
    assertEquals("local_1-lep_1", MediaEventManager.historyKey(session(null, localItem = local("local_1"), localEpisodeId = "lep_1", playMethod = PLAYMETHOD_LOCAL)))
  }

  @Test fun recordEventUsesSnapshotNotLiveSession() {
    val s = session("li_1", currentTime = 5.0)
    val snapshot = MediaEventManager.snapshot(s, 100.0)
    s.currentTime = 500.0
    val id = MediaEventManager.recordEvent("Pause", snapshot)
    val event = history("li_1")!!.events.single()
    assertEquals(100.0, event.currentTime!!.toDouble(), 0.0)
    assertEquals(now, event.timestamp)
    assertEquals(id, event.id)
    assertEquals(id, UUID.fromString(id).toString())
  }

  @Test fun attachSyncResultUpdatesEventById() {
    val snapshot = MediaEventManager.snapshot(session("li_1"), 1.0)
    val id = MediaEventManager.recordEvent("Pause", snapshot)
    MediaEventManager.attachSyncResult("li_1", id, SyncResult(true, true, "ok"))
    val event = history("li_1")!!.events.single()
    assertEquals(true, event.serverSyncAttempted)
    assertEquals(true, event.serverSyncSuccess)
    assertEquals("ok", event.serverSyncMessage)
  }

  @Test fun attachSyncResultIgnoresMissingEvent() {
    MediaEventManager.recordEvent("Pause", MediaEventManager.snapshot(session("li_1"), 1.0))
    posted.clear()
    MediaEventManager.attachSyncResult("li_1", "missing", SyncResult(true, true, "ok"))
    assertEquals(false, history("li_1")!!.events.single().serverSyncAttempted)
    assertTrue(posted.isEmpty())
  }

  @Test fun attachSyncResultDropsExpiredEvent() {
    val id = MediaEventManager.recordEvent("Pause", MediaEventManager.snapshot(session("li_1"), 1.0))
    posted.clear()
    MediaEventManager.clock = { now + 30 * day + 1 }
    MediaEventManager.attachSyncResult("li_1", id, SyncResult(true, true, "ok"))
    assertTrue(history("li_1")!!.events.isEmpty())
    assertEquals(1, posted.size)
  }

  @Test fun localSessionIsStoredAsLocalHistory() {
    MediaEventManager.recordEvent("Play", MediaEventManager.snapshot(session(null, localItem = local("local_1"), playMethod = PLAYMETHOD_LOCAL), 1.0))
    val h = history("local_1")!!
    assertEquals("local_1", h.libraryItemId)
    assertTrue(h.isLocal)
  }

  @Test fun serverAddressFollowsServerPlaybackOnly() {
    val copy = local("local_1", "li_1", "http://lan")
    MediaEventManager.recordEvent("Play", MediaEventManager.snapshot(session("li_1", localItem = copy, playMethod = PLAYMETHOD_LOCAL, serverAddress = "http://lan", configId = "cfg-lan"), 1.0))
    assertEquals("http://lan", history("li_1")!!.serverAddress)
    MediaEventManager.recordEvent("Play", MediaEventManager.snapshot(session("li_1", serverAddress = "https://abs", configId = "cfg-https", userId = "user-2"), 1.0))
    history("li_1")!!.let {
      assertEquals("https://abs", it.serverAddress)
      assertEquals("cfg-https", it.serverConnectionConfigId)
      assertEquals("user-2", it.serverUserId)
    }
    MediaEventManager.recordEvent("Play", MediaEventManager.snapshot(session("li_1", localItem = copy, playMethod = PLAYMETHOD_LOCAL, serverAddress = "http://lan", configId = "cfg-lan"), 1.0))
    assertEquals("https://abs", history("li_1")!!.serverAddress)
    assertEquals("cfg-https", history("li_1")!!.serverConnectionConfigId)
  }

  @Test fun recordSessionSaveFillsOnlyEmptyServerFields() {
    MediaEventManager.recordSessionSave(session("li_1", serverAddress = "https://abs", configId = "cfg-https", userId = "user-1"), SyncResult(true, true, "x"))
    history("li_1")!!.let {
      assertEquals("https://abs", it.serverAddress)
      assertEquals("cfg-https", it.serverConnectionConfigId)
      assertEquals("user-1", it.serverUserId)
    }
  }

  @Test fun recordSessionSaveKeepsFilledServerFields() {
    DeviceManager.dbManager.saveMediaItemHistory(MediaItemHistory("li_1", "Seed", "li_1", null, false, "cfg-https", "https://abs", null, 1L, mutableListOf()))
    MediaEventManager.recordSessionSave(session("li_1", serverAddress = "http://lan", configId = "cfg-lan", userId = "user-1", currentTime = 77.0), SyncResult(true, true, "synced"))
    val h = history("li_1")!!
    assertEquals("https://abs", h.serverAddress)
    assertEquals("cfg-https", h.serverConnectionConfigId)
    assertEquals("user-1", h.serverUserId)
    val save = h.events.single()
    assertEquals("Save", save.name)
    assertEquals(77.0, save.currentTime!!.toDouble(), 0.0)
    assertEquals(now, save.timestamp)
    assertEquals("synced", save.serverSyncMessage)
    assertNotNull(save.id)
  }

  @Test fun writePrunesExpiredAndDropsEmptyKey() {
    seed("li_1", event("Play", now - 30 * day), event("Play", now - 30 * day - 1))
    seed("", event("Play", now))
    MediaEventManager.recordEvent("Pause", MediaEventManager.snapshot(session("li_1"), 1.0))
    val events = history("li_1")!!.events
    assertEquals(listOf(now - 30 * day, now), events.map { it.timestamp })
    assertFalse(DeviceManager.dbManager.hasMediaItemHistory(""))
  }

  @Test fun emptyKeyDeleteFailureIsLoggedAndWriteSucceeds() {
    seed("", event("Play", now))
    MediaEventManager.store = object : MediaEventManager.HistoryStore by MediaEventManager.DbHistoryStore {
      override fun delete(id: String) = throw IllegalStateException("synthetic delete failure")
    }
    MediaEventManager.recordEvent("Pause", MediaEventManager.snapshot(session("li_1"), 1.0))
    assertEquals(1, history("li_1")!!.events.size)
  }

  @Test fun journalFailureIsContained() {
    MediaEventManager.store = object : MediaEventManager.HistoryStore by MediaEventManager.DbHistoryStore {
      override fun read(id: String) = throw IllegalStateException("synthetic corrupt journal")
    }
    val id = MediaEventManager.recordEvent("Pause", MediaEventManager.snapshot(session("li_1"), 1.0))
    MediaEventManager.attachSyncResult("li_1", id, SyncResult(true, true, "ok"))
    assertTrue(posted.isEmpty())
  }

  @Test fun unchangedSyncResultDoesNotRewrite() {
    val id = MediaEventManager.recordEvent("Save", MediaEventManager.snapshot(session("li_1"), 1.0))
    posted.clear()
    MediaEventManager.attachSyncResult("li_1", id, SyncResult(false, null, null))
    assertTrue(posted.isEmpty())
  }

  @Test fun sessionWithoutAnyItemIdIsNotJournaled() {
    MediaEventManager.recordEvent("Play", MediaEventManager.snapshot(session(null), 1.0))
    assertFalse(DeviceManager.dbManager.hasMediaItemHistory(""))
  }

  @Test fun syncEventGoesThroughLockAndPrune() {
    seed("li_1", event("Play", now - 31 * day))
    MediaEventManager.syncEvent(MediaProgress("p", "li_1", null, 3600.0, 0.5, 20.0, false, null, null, 1L, 1L, null), "test")
    val sync = history("li_1")!!.events.single()
    assertEquals("Sync", sync.name)
    assertNotNull(sync.id)
  }

  @Test fun concurrentRecordAndAttachDoNotLoseEvents() {
    val firstId = MediaEventManager.recordEvent("Play", MediaEventManager.snapshot(session("li_1"), 1.0))
    val start = CountDownLatch(1)
    val threads = (0 until 2).map {
      Thread {
        start.await()
        repeat(100) {
          MediaEventManager.recordEvent("Save", MediaEventManager.snapshot(session("li_1"), 2.0))
          MediaEventManager.attachSyncResult("li_1", firstId, SyncResult(true, true, "ok"))
        }
      }.apply { start() }
    }
    start.countDown()
    threads.forEach { it.join(20000) }
    val events = history("li_1")!!.events
    assertEquals(201, events.size)
    assertEquals("ok", events.first { it.id == firstId }.serverSyncMessage)
  }

  @Test fun notificationsArePostedInWriteOrderAsDeepCopies() {
    val seen = mutableListOf<Int>()
    MediaEventManager.clientEventEmitter = java.lang.reflect.Proxy.newProxyInstance(
            PlayerNotificationService.ClientEventEmitter::class.java.classLoader, arrayOf(PlayerNotificationService.ClientEventEmitter::class.java)) { _, method, args ->
      if (method.name == "onMediaItemHistoryUpdated") seen.add((args[0] as MediaItemHistory).events.size)
      null
    } as PlayerNotificationService.ClientEventEmitter
    repeat(3) { MediaEventManager.recordEvent("Play", MediaEventManager.snapshot(session("li_1"), 1.0)) }
    val first = posted.first()
    DeviceManager.dbManager.saveMediaItemHistory(history("li_1")!!.apply { events.clear() })
    first()
    posted.drop(1).forEach { it() }
    assertEquals(listOf(1, 2, 3), seen)
  }

  @Test fun filterForReadKeepsBoundaryDropsOlder() {
    val h = MediaItemHistory("li_1", "Seed", "li_1", null, false, null, null, null, 1L,
            mutableListOf(event("Play", now - 30 * day), event("Play", now - 30 * day - 1)))
    assertEquals(listOf(now - 30 * day), MediaEventManager.filterForRead(h, now)!!.events.map { it.timestamp })
    assertEquals(2, h.events.size)
    assertTrue(MediaEventManager.filterForRead(h, now + day)!!.events.isEmpty())
  }
}
