package app.absplus.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.player.PLAYMETHOD_DIRECTPLAY
import app.absplus.android.server.ApiHandler.LocalSessionSyncResult
import io.paperdb.Paper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What stays in Paper after `local-all` answered, and the progress the server skipped. Synthetic ids only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSessionsCleanupTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = inMemoryUserData()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val api = FakeApi()

  @Before
  fun setUp() {
    Paper.init(context)
    Paper.book("playbackSession").destroy()
    SyncEngine.initForTest(SyncDeps(
            db = db, http = syncHttp(api), activeServer = { fakeServer() }, serverState = MutableStateFlow(ServerState.OFFLINE), onlineEntries = MutableStateFlow(0L),
            serverId = MutableStateFlow("srv"), isForeground = { false }, clock = { 1L }, scope = scope, log = {},
            notifyWindowMs = 10, unsentSessionKeys = PaperProgressMirror::unsentSessionKeys))
  }

  @After
  fun tearDown() {
    SyncEngine.resetForTest()
    scope.cancel()
    db.close()
  }

  private fun session(id: String, updatedAt: Long = 100L, libraryItemId: String = "li1") = PlaybackSession(
          id, "user", libraryItemId, null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
          emptyList(), "Book", "Author", null, 1000.0, PLAYMETHOD_DIRECTPLAY, 1L, updatedAt, 15L, mutableListOf(),
          500.0, null, null, null, "srv", null, null)

  private fun ok(id: String, progressSynced: Boolean = true) = LocalSessionSyncResult(id, true, progressSynced, null)
  private fun failed(id: String, error: String) = LocalSessionSyncResult(id, false, null, error)

  private fun saved(vararg sessions: PlaybackSession) = sessions.toList().onEach { DeviceManager.dbManager.savePlaybackSession(it) }
  private fun paperIds() = DeviceManager.dbManager.getPlaybackSessions().map { it.id }.toSet()

  private fun settle(sent: List<PlaybackSession>, requestOk: Boolean, results: List<LocalSessionSyncResult>) = runBlocking {
    val done = CompletableDeferred<Unit>()
    LocalSessionsCleanup.settle(sent, requestOk, results) { done.complete(Unit) }
    withTimeout(5_000) { done.await() }
    // The cleanup runs in the write queue, after what is queued before it
    SyncEngine.write { }
  }

  @Test
  fun removesOnlySucceededAndPermanentFailures() {
    val results = listOf(ok("a"), failed("b", "Media item not found"), failed("c", "boom"), ok("x"))
    assertEquals(setOf("a", "b"), LocalSessionsCleanup.idsToRemove(listOf("a", "b", "c", "d"), results))
    assertEquals(setOf("e"), LocalSessionsCleanup.idsToRemove(listOf("e"), listOf(failed("e", "Library not found"))))
    assertEquals("the server's two errors only, as they are", setOf("f"), LocalSessionsCleanup.idsToRemove(listOf("f", "g", "h"),
            listOf(failed("f", " media item NOT found "), failed("g", "Episode file not found on disk"), failed("h", "not found"))))

    val sent = saved(session("a"), session("b"), session("c"), session("d"))
    settle(sent, true, results)
    assertEquals(setOf("c", "d"), paperIds())
  }

  @Test
  fun wholeRequestFailureRemovesNothing() {
    val sent = saved(session("a"), session("b"))
    settle(sent, false, emptyList())
    assertEquals(setOf("a", "b"), paperIds())
  }

  @Test
  fun sessionUpdatedDuringRequestIsKept() {
    val sent = saved(session("a", updatedAt = 100L))
    // A tick saved the session again while local-all was on the wire
    DeviceManager.dbManager.savePlaybackSession(session("a", updatedAt = 115L))
    settle(sent, true, listOf(ok("a")))
    assertEquals(setOf("a"), paperIds())
    assertEquals(115L, DeviceManager.dbManager.getPlaybackSession("a")!!.updatedAt)
  }

  @Test
  fun sessionGivenMoreTimeDuringRequestIsKept() {
    val sent = saved(session("a", updatedAt = 100L).also { it.timeListening = 15L })
    // A late failed /sync added its time to the offline session and kept its newer position
    DeviceManager.dbManager.savePlaybackSession(session("a", updatedAt = 100L).also { it.timeListening = 30L })
    settle(sent, true, listOf(ok("a")))
    assertEquals(30L, DeviceManager.dbManager.getPlaybackSession("a")!!.timeListening)
  }

  @Test
  fun theCallIsAnsweredEvenWhenTheWriteQueueNeverRunsTheCleanup() = runBlocking {
    val release = CompletableDeferred<Unit>()
    SyncEngine.launchWrite { release.await() } // a sync stopped or stuck before the cleanup's turn
    val done = CompletableDeferred<Unit>()
    LocalSessionsCleanup.settle(saved(session("a")), true, listOf(ok("a"))) { done.complete(Unit) }
    try {
      withTimeout(2_000) { done.await() }
    } finally {
      release.complete(Unit)
    }
  }

  @Test
  fun progressNotSyncedQueuesPatchAndPendingClearsAfter2xx() = runBlocking {
    db.mediaProgress().upsert(progressRow(700.0, lastUpdate = 200L, pending = false))
    val sent = saved(session("a"), session("b"))
    settle(sent, true, listOf(ok("a", progressSynced = false), ok("b", progressSynced = false)))

    assertTrue(paperIds().isEmpty())
    assertTrue("the row is protected by pending once the sessions are gone", db.mediaProgress().get("srv", "li1", "")!!.pending)
    val queued = db.outbox().byItemKey("srv", progressItemKey("li1", null))
    assertEquals("one patch for the item", 1, queued.size)
    assertEquals(OutboxKind.PROGRESS_PATCH, queued.single().kind)

    // A newer server value pulled before the patch went out does not replace the row
    SyncEngine.store!!.applyServerProgress("srv", listOf(ServerProgress("mp1", "li1", null, 1000.0, 0.9, 900.0, false, false, null, null, 300L, 1L, null)))
    assertEquals(700.0, db.mediaProgress().get("srv", "li1", "")!!.currentTime, 0.0)

    OutboxPusher(db, syncHttp(api), SyncEngine.store!!, { 2L }, {}).pushAll(fakeServer())
    assertEquals(listOf("PATCH /api/me/progress/li1"), api.paths())
    val body = JSONObject(api.requests.single().body!!)
    assertEquals(700.0, body.getDouble("currentTime"), 0.0)
    assertEquals(0.7, body.getDouble("progress"), 0.0)
    assertEquals(200L, body.getLong("lastUpdate"))
    assertFalse(db.mediaProgress().get("srv", "li1", "")!!.pending)
    assertNull(db.outbox().head("srv"))
  }

  @Test
  fun progressSyncedTrueQueuesNothing() = runBlocking {
    db.mediaProgress().upsert(progressRow(700.0, lastUpdate = 200L, pending = false))
    val sent = saved(session("a"), session("b", libraryItemId = "li-without-row"))
    settle(sent, true, listOf(ok("a", progressSynced = true), ok("b", progressSynced = false)))

    assertTrue(paperIds().isEmpty())
    assertNull("nothing to send for a synced session or an item without a row", db.outbox().head("srv"))
    assertFalse(db.mediaProgress().get("srv", "li1", "")!!.pending)
  }
}
