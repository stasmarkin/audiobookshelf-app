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
    SyncEngine.initForTest(SyncDeps(
            db = db, http = syncHttp(api), activeServer = { fakeServer() }, serverState = MutableStateFlow(ServerState.OFFLINE),
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
  private fun tick(session: PlaybackSession, time: Double) {
    session.syncData(MediaProgressSyncData(15, session.duration, time))
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
}
