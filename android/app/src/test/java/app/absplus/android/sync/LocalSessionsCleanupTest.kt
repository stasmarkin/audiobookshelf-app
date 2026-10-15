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
    SyncEngine.store!!.applyServerProgress("srv", listOf(serverAt(300.0, lastUpdate = 300L)))
    assertEquals(700.0, db.mediaProgress().get("srv", "li1", "")!!.currentTime, 0.0)

    // The server is behind the device: the PATCH goes out
    serverAnswers(serverJson(300.0, lastUpdate = 300L))
    push()
    assertEquals(listOf("GET /api/me/progress/li1", "PATCH /api/me/progress/li1"), api.paths())
    val body = JSONObject(api.requests.last().body!!)
    assertEquals(700.0, body.getDouble("currentTime"), 0.0)
    assertEquals(0.7, body.getDouble("progress"), 0.0)
    assertEquals(200L, body.getLong("lastUpdate"))
    assertFalse(db.mediaProgress().get("srv", "li1", "")!!.pending)
    assertNull(db.outbox().head("srv"))
  }

  private fun serverAt(currentTime: Double, lastUpdate: Long, isFinished: Boolean = false) =
          ServerProgress("mp1", "li1", null, 1000.0, currentTime / 1000.0, currentTime, isFinished, false, null, null, lastUpdate, 1L, null)

  private fun serverJson(currentTime: Double, lastUpdate: Long, isFinished: Boolean = false) =
          """{"id":"mp1","libraryItemId":"li1","duration":1000,"progress":${currentTime / 1000.0},"currentTime":$currentTime,"isFinished":$isFinished,"lastUpdate":$lastUpdate,"startedAt":1}"""

  /** GET of the item's progress answers [progress] (null: no record, 404); anything else 200. */
  private fun serverAnswers(progress: String?) {
    api.handler = { request -> if (request.method == "GET") (if (progress == null) 404 to "Not Found" else 200 to progress) else 200 to "{}" }
  }

  private suspend fun push() = OutboxPusher(db, syncHttp(api), SyncEngine.store!!, { 2L }, {}).pushAll(fakeServer())

  private suspend fun skippedProgressQueued(rowTime: Double, rowLastUpdate: Long = 200L) {
    db.mediaProgress().upsert(progressRow(rowTime, lastUpdate = rowLastUpdate, pending = false))
    settle(saved(session("a")), true, listOf(ok("a", progressSynced = false)))
    assertTrue(db.mediaProgress().get("srv", "li1", "")!!.pending)
  }

  @Test
  fun aServerAheadOfTheDeviceKeepsItsPositionAndTheDeviceTakesIt() = runBlocking {
    skippedProgressQueued(700.0)
    serverAnswers(serverJson(500.0, lastUpdate = 300L, isFinished = true)) // behind, but finished elsewhere
    push()
    assertEquals("no PATCH", listOf("GET /api/me/progress/li1"), api.paths())
    val row = db.mediaProgress().get("srv", "li1", "")!!
    assertFalse(row.pending)
    assertTrue(row.isFinished)
    assertEquals(500.0, row.currentTime, 0.0)
    assertNull(db.outbox().head("srv"))
  }

  @Test
  fun anOfflineListenerBehindAnotherDeviceTakesItsPosition() = runBlocking {
    // A listened offline to 1:00; B reached 3:00 on the server later
    skippedProgressQueued(60.0, rowLastUpdate = 100L)
    serverAnswers(serverJson(180.0, lastUpdate = 200L))
    push()
    assertTrue("the server keeps 3:00", api.requests.none { it.method == "PATCH" })
    assertEquals(180.0, db.mediaProgress().get("srv", "li1", "")!!.currentTime, 0.0)
  }

  @Test
  fun noServerRecordIsPatched() = runBlocking {
    skippedProgressQueued(700.0)
    serverAnswers(null)
    push()
    assertEquals(listOf("GET /api/me/progress/li1", "PATCH /api/me/progress/li1"), api.paths())
    assertFalse(db.mediaProgress().get("srv", "li1", "")!!.pending)
  }

  @Test
  fun aFinishedMarkFoldedInIsSentAndThePositionStaysConditional() = runBlocking {
    skippedProgressQueued(700.0)
    SyncEngine.store!!.markFinished("srv", "li1", null, true)
    serverAnswers(serverJson(900.0, lastUpdate = 300L))
    push()
    assertEquals(listOf("GET /api/me/progress/li1", "PATCH /api/me/progress/li1"), api.paths())
    val ahead = JSONObject(api.requests.last().body!!)
    assertTrue(ahead.getBoolean("isFinished"))
    assertFalse("the server's further position stays", ahead.has("currentTime") || ahead.has("progress"))
  }

  @Test
  fun aFinishedMarkAndThePositionAreSentWhenTheServerIsBehind() = runBlocking {
    skippedProgressQueued(700.0)
    // "Not finished" keeps the position (marking finished moves it to the start, which no server is behind)
    SyncEngine.store!!.markFinished("srv", "li1", null, false)
    serverAnswers(serverJson(300.0, lastUpdate = 300L))
    push()
    val behind = JSONObject(api.requests.last().body!!)
    assertFalse(behind.getBoolean("isFinished"))
    assertEquals(700.0, behind.getDouble("currentTime"), 0.0)
  }

  @Test
  fun anEbookChangeFoldedInIsSentWithoutTheOlderAudioPosition() = runBlocking {
    skippedProgressQueued(700.0)
    SyncEngine.store!!.updateEbookProgress("srv", "li1", "epubcfi(/6/4)", 0.3)
    serverAnswers(serverJson(900.0, lastUpdate = 300L))
    push()
    assertEquals(listOf("GET /api/me/progress/li1", "PATCH /api/me/progress/li1"), api.paths())
    val body = JSONObject(api.requests.last().body!!)
    assertEquals("epubcfi(/6/4)", body.getString("ebookLocation"))
    assertFalse(body.has("currentTime") || body.has("progress") || body.has("duration"))
    assertNull(db.outbox().head("srv"))
  }

  @Test
  fun anExplicitChangeOfAConditionalFieldMakesItUnconditional() {
    fun patch(id: Long, payload: String) = app.absplus.android.db.userdata.OutboxEntity(id = id, serverConfigId = "srv",
            kind = OutboxKind.PROGRESS_PATCH, itemKey = progressItemKey("li1", null), payload = payload, createdAt = 1L)
    val skipped = patch(1, OutboxPayload.progressPatch(listOf("duration", "progress", "currentTime"),
            conditionalFields = listOf("duration", "progress", "currentTime")))
    val folded = coalesce(listOf(skipped), patch(0, OutboxPayload.progressPatch(listOf("currentTime"))), null).insert!!
    assertEquals(listOf("duration", "progress"), OutboxPayload.conditionalFields(folded.payload))
  }

  @Test
  fun aCheckTheServerCannotAnswerFallsBackToThePatch() = runBlocking {
    for ((code, text) in listOf(403 to "Forbidden", 200 to "not json")) {
      api.requests.clear()
      skippedProgressQueued(700.0)
      api.handler = { request -> if (request.method == "GET") code to text else 200 to "{}" }
      push()
      assertEquals("GET $code", listOf("GET /api/me/progress/li1", "PATCH /api/me/progress/li1"), api.paths())
      assertNull(db.outbox().head("srv"))
    }
  }

  @Test
  fun aServerErrorOnTheCheckIsRetried() = runBlocking {
    skippedProgressQueued(700.0)
    api.handler = { request -> if (request.method == "GET") 503 to "busy" else 200 to "{}" }
    assertEquals(StopReason.BACKOFF, push().stoppedBy)
    assertEquals(listOf("GET /api/me/progress/li1"), api.paths())
    assertTrue(db.mediaProgress().get("srv", "li1", "")!!.pending)
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
