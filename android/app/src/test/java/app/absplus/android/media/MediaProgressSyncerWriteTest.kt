package app.absplus.android.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.player.PLAYMETHOD_DIRECTPLAY
import app.absplus.android.player.PLAYMETHOD_LOCAL
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.sync.FakeApi
import app.absplus.android.sync.OutboxPusher
import app.absplus.android.sync.PaperProgressMirror
import app.absplus.android.sync.ServerState
import app.absplus.android.sync.SyncDeps
import app.absplus.android.sync.SyncEngine
import app.absplus.android.sync.eventually
import app.absplus.android.sync.fakeServer
import app.absplus.android.sync.inMemoryUserData
import app.absplus.android.sync.progressRow
import app.absplus.android.sync.syncHttp
import io.paperdb.Paper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Playback ticks of [MediaProgressSyncer] in userdata.db next to the Paper copy, under Robolectric. Synthetic ids only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaProgressSyncerWriteTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = inMemoryUserData()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val api = FakeApi()
  private lateinit var syncer: MediaProgressSyncer

  @Before
  fun setUp() {
    Paper.init(context)
    for (book in listOf("localLibraryItems", "localMediaProgress", "playbackSession")) Paper.book(book).destroy()
    DbManager.localLibraryItemsChanged()
    SyncEngine.initForTest(SyncDeps(
            db = db, http = syncHttp(api), activeServer = { fakeServer() }, serverState = MutableStateFlow(ServerState.OFFLINE), onlineEntries = MutableStateFlow(0L),
            serverId = MutableStateFlow("srv"), isForeground = { false }, clock = { 1L }, scope = scope, log = {},
            notifyWindowMs = 10, mirror = PaperProgressMirror))
    // Not created: a tick touches neither the player nor the notification
    val service = Robolectric.buildService(PlayerNotificationService::class.java).get()
    syncer = MediaProgressSyncer(service) { throw AssertionError("a tick sends nothing") }
  }

  @After
  fun tearDown() {
    SyncEngine.resetForTest()
    scope.cancel()
    db.close()
  }

  private val copy = LocalLibraryItem(
          "local_li1", "folder", "", "", "", false, "book", MediaType(MediaTypeMetadata("Book", false), null),
          mutableListOf(), null, null, true, "srv", "https://srv.test.invalid", "user", "li1")

  private fun session(playMethod: Int, localItem: LocalLibraryItem?, serverConfigId: String? = "srv", libraryItemId: String? = "li1") =
          PlaybackSession(
                  "session-$playMethod", "user", libraryItemId, null, "book", MediaTypeMetadata("Book", false),
                  DeviceInfo("d", "m", "x", 35, "0"), emptyList(), "Book", "Author", null, 1000.0, playMethod, 1L, 1L, 0L,
                  mutableListOf(AudioTrack(0, 0.0, 1000.0, "t", "", null, null, playMethod == PLAYMETHOD_LOCAL, null, null)),
                  0.0, null, localItem, null, serverConfigId, null, null)

  /** One tick as `sync` stores it: the session moves on, then everything is saved. */
  private var lastStamp = 0L

  private fun tick(session: PlaybackSession, time: Double) {
    session.syncData(MediaProgressSyncData(15, session.duration, time))
    // Real ticks are 15 s apart; these run within a millisecond, and a tick no newer than a pending row is dropped
    session.updatedAt = maxOf(session.updatedAt, lastStamp + 1).also { lastStamp = it }
    syncer.saveTick(session, time)
  }

  private suspend fun row() = db.mediaProgress().get("srv", "li1", "")

  @Test
  fun everyTickWritesMediaProgressForServerLinkedSessions() = runBlocking {
    DeviceManager.dbManager.saveLocalLibraryItem(copy)
    tick(session(PLAYMETHOD_LOCAL, copy), 120.0)
    eventually("downloaded copy in Room") { row()?.currentTime == 120.0 }
    assertEquals("and in Paper, as before", 120.0, DeviceManager.dbManager.getLocalMediaProgress("local_li1")!!.currentTime, 0.0)

    db.mediaProgress().delete("srv", "li1", "")
    syncer.reset()
    tick(session(PLAYMETHOD_DIRECTPLAY, null), 240.0)
    eventually("stream in Room") { row()?.currentTime == 240.0 }
    eventually("the stream is mirrored to the downloaded copy") {
      DeviceManager.dbManager.getLocalMediaProgress("local_li1")?.currentTime == 240.0
    }

    db.mediaProgress().delete("srv", "li1", "")
    syncer.reset()
    val unlinked = LocalLibraryItem("local_x", "folder", "", "", "", false, "book", MediaType(MediaTypeMetadata("X", false), null),
            mutableListOf(), null, null, true, null, null, null, null)
    tick(session(PLAYMETHOD_LOCAL, unlinked, serverConfigId = null, libraryItemId = null), 30.0)
    delay(200)
    assertTrue("a copy without a server has no Room row", db.mediaProgress().getAll("srv").isEmpty())
    assertEquals(30.0, DeviceManager.dbManager.getLocalMediaProgress("local_x")!!.currentTime, 0.0)
  }

  @Test
  fun paperAndRoomDoNotDiverge() = runBlocking {
    DeviceManager.dbManager.saveLocalLibraryItem(copy)
    val session = session(PLAYMETHOD_LOCAL, copy)
    // Five ticks, the last one at the very end: listened to the end offline
    for (time in listOf(100.0, 200.0, 300.0, 400.0, 995.0)) tick(session, time)

    eventually("the last tick in Room") { row()?.currentTime == 995.0 }
    val paper = DeviceManager.dbManager.getLocalMediaProgress("local_li1")!!
    val room = row()!!
    assertEquals(paper.currentTime, room.currentTime, 0.0)
    assertEquals(paper.progress, room.progress, 0.0)
    assertEquals(paper.isFinished, room.isFinished)
    assertTrue(room.isFinished)
    assertEquals(paper.finishedAt, room.finishedAt)
    assertEquals(paper.lastUpdate, room.lastUpdate)
  }

  @Test
  fun delayedPatchDoesNotRegressPlayedPosition() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 1L, pending = false))
    val store = SyncEngine.store!!
    store.resetProgress("srv", "li1", null)
    val session = session(PLAYMETHOD_DIRECTPLAY, null)
    for (time in listOf(200.0, 400.0, 600.0)) tick(session, time)
    eventually("ticks in Room") { row()?.currentTime == 600.0 }
    // The session reached the server through local-all and left the device
    DeviceManager.dbManager.removePlaybackSession(session.id)

    OutboxPusher(db, syncHttp(api), store, { 2L }, {}).pushAll(fakeServer())

    assertEquals(listOf("DELETE /api/me/progress/mp1", "PATCH /api/me/progress/li1"), api.paths())
    assertEquals(600.0, JSONObject(api.requests[1].body!!).getDouble("currentTime"), 0.0)
    assertNull(db.outbox().head("srv"))
  }

  @Test
  fun aSeekWhilePausedIsTheDevicesPosition() = runBlocking {
    val session = session(PLAYMETHOD_DIRECTPLAY, null)
    tick(session, 100.0)
    eventually("the tick") { row()?.currentTime == 100.0 }

    // Paused, the user moves the player on: no tick follows
    syncer.recordSeek(session, 300.0, session.id)
    eventually("the seek") { row()?.currentTime == 300.0 }

    // Refocus asks native for the row after merging an older server value: it is where the user put the player
    val result = SyncEngine.store!!.mergeServerProgress("srv",
            app.absplus.android.sync.ServerProgress("mp1", "li1", null, 1000.0, 0.1, 100.0, false, false, null, null, 2L, 1L, null))
    assertEquals(300.0, result.row!!.currentTime, 0.0)
  }

  @Test
  fun theNextItemsStartSeekIsNotWrittenToThePreviousItem() = runBlocking {
    val first = session(PLAYMETHOD_DIRECTPLAY, null)
    tick(first, 100.0)
    eventually("the tick") { row()?.currentTime == 100.0 }

    // The queue moved to B, whose start position the player seeks to while this syncer still holds A
    syncer.recordSeek(first, 2500.0, playerSessionId = "session-of-B")
    // Writes run in order: once this one ran, a write of that seek would have too
    SyncEngine.write { }
    assertEquals("A keeps its own position", 100.0, row()!!.currentTime, 0.0)

    syncer.recordSeek(first, 120.0, playerSessionId = first.id)
    eventually("a seek of A itself") { row()?.currentTime == 120.0 }
  }

  @Test
  fun aSeekOfADownloadedCopyLeavesItsPaperProgressToThePlayer() = runBlocking {
    DeviceManager.dbManager.saveLocalLibraryItem(copy)
    val session = session(PLAYMETHOD_LOCAL, copy)
    tick(session, 100.0)
    eventually("the tick") { row()?.currentTime == 100.0 }

    syncer.recordSeek(session, 300.0, session.id)
    eventually("the seek in Room") { row()?.currentTime == 300.0 }
    SyncEngine.write { } // the write, with any mirror of it, is over
    assertEquals("the player writes the copy's progress itself", 100.0, DeviceManager.dbManager.getLocalMediaProgress("local_li1")!!.currentTime, 0.0)
  }

  @Test
  fun aSyncAnswerRemovesTheSessionThatWasSent() {
    val opener = session(PLAYMETHOD_DIRECTPLAY, null).also { it.id = "session-opener" }
    val sequel = session(PLAYMETHOD_DIRECTPLAY, null).also { it.id = "session-sequel" }
    DeviceManager.dbManager.savePlaybackSession(opener)
    DeviceManager.dbManager.savePlaybackSession(sequel)
    // The queue moved on to the sequel before the opener's /sync was answered
    syncer.currentPlaybackSession = sequel

    syncer.serverSyncAnswered("session-opener", "Opener", 995.0, syncSuccess = true)

    assertEquals("the sequel's session stays for its own sync", listOf("session-sequel"), DeviceManager.dbManager.getPlaybackSessions().map { it.id })
  }

  @Test
  fun aLateSyncAnswerLeavesTheCurrentSessionsSyncClock() {
    val opener = session(PLAYMETHOD_DIRECTPLAY, null).also { it.id = "session-opener" }
    val sequel = session(PLAYMETHOD_DIRECTPLAY, null).also { it.id = "session-sequel" }
    syncer.currentPlaybackSession = sequel
    val before = syncer.lastSyncTime

    syncer.serverSyncAnswered("session-opener", "Opener", 995.0, syncSuccess = true)
    assertEquals("the sequel's listening since its last sync is still counted", before, syncer.lastSyncTime)

    syncer.serverSyncAnswered("session-sequel", "Sequel", 10.0, syncSuccess = true)
    assertTrue("its own answer moves it", syncer.lastSyncTime > before)
  }

  @Test
  fun aFailedSyncAnswerKeepsBothSessions() {
    val opener = session(PLAYMETHOD_DIRECTPLAY, null).also { it.id = "session-opener" }
    val sequel = session(PLAYMETHOD_DIRECTPLAY, null).also { it.id = "session-sequel" }
    DeviceManager.dbManager.savePlaybackSession(opener)
    DeviceManager.dbManager.savePlaybackSession(sequel)
    syncer.currentPlaybackSession = sequel

    syncer.serverSyncAnswered("session-opener", "Opener", 995.0, syncSuccess = false)

    assertEquals(setOf("session-opener", "session-sequel"), DeviceManager.dbManager.getPlaybackSessions().map { it.id }.toSet())
  }
}
