package app.absplus.android.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.LocalMediaProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [PaperProgressImport] and its settling on the first user reconcile, on in-memory Room under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaperProgressImportTest {
  private val db = inMemoryUserData()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val api = FakeApi()
  private val prefs: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
          .getSharedPreferences("paper-import-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
  private val import = PaperProgressImport(db, prefs)
  private val store = UserDataStore(db, DataChangeNotifier(10, scope) { _, _ -> }, { 1L }, { null })
  private val reconciler = UserDataReconciler(syncHttp(api), store, db, { 1L }, import)

  @After
  fun tearDown() {
    SyncEngine.resetForTest()
    scope.cancel()
    db.close()
  }

  private fun lmp(
          libraryItemId: String? = "li1", lastUpdate: Long = 200L, currentTime: Double = 300.0, isFinished: Boolean = false,
          serverConfigId: String? = "srv", episodeId: String? = null, ebookLocation: String? = null, ebookProgress: Double? = null
  ) = LocalMediaProgress(
          "local_${libraryItemId}_${serverConfigId}_$episodeId", "local_$libraryItemId", episodeId?.let { "local-$it" }, 1000.0,
          currentTime / 1000.0, currentTime, isFinished, ebookLocation, ebookProgress, lastUpdate, 1L, null, serverConfigId,
          serverConfigId?.let { "https://$it.test.invalid" }, "user", libraryItemId, episodeId)

  private fun serverProgress(libraryItemId: String, currentTime: Double, lastUpdate: Long) =
          """{"id":"mp-$libraryItemId","libraryItemId":"$libraryItemId","duration":1000,"progress":${currentTime / 1000},
            "currentTime":$currentTime,"isFinished":false,"lastUpdate":$lastUpdate,"startedAt":1}"""

  /** The server below 2.36: one GET /api/me with [progress]. */
  private fun serverHas(vararg progress: String) {
    api.handler = { 200 to """{"id":"user","mediaProgress":[${progress.joinToString(",")}],"bookmarks":[]}""" }
  }

  private suspend fun row(libraryItemId: String = "li1", serverConfigId: String = "srv", episodeId: String = "") =
          db.mediaProgress().get(serverConfigId, libraryItemId, episodeId)

  private suspend fun outbox(libraryItemId: String = "li1", serverConfigId: String = "srv") =
          db.outbox().byItemKey(serverConfigId, progressItemKey(libraryItemId, null))

  private suspend fun reconcile(server: String = "srv") = reconciler.reconcileUser(fakeServer(server, version = "2.35.1"))

  @Test
  fun importsOnlyServerLinkedProgressOnce() = runBlocking {
    val paper = listOf(lmp(), lmp(libraryItemId = null), lmp(serverConfigId = null), lmp(episodeId = "ep1"))

    assertEquals(2, import.import(paper))

    val book = row()!!
    assertEquals(300.0, book.currentTime, 0.0)
    assertEquals("device", book.source)
    assertFalse(book.pending)
    assertNotNull("the episode is keyed by its server id", row(episodeId = "ep1"))
    assertEquals(2, db.mediaProgress().getAll("srv").size)
    assertEquals("a second start without changes writes nothing", 0, import.import(paper))
  }

  @Test
  fun localEpisodeWithoutItsServerIdIsNotImported() = runBlocking {
    // An episode progress that lost its server episode id would otherwise land on the podcast's own row
    val orphan = LocalMediaProgress("local_pod-local-ep", "local_pod", "local-ep", 60.0, 0.5, 30.0, false, null, null, 200L, 1L,
            null, "srv", "https://srv.test.invalid", "user", "pod1", null)

    assertEquals(0, import.import(listOf(orphan)))
    assertNull(row("pod1"))
  }

  @Test
  fun importDoesNotOverwriteNewerRoomRow() = runBlocking {
    db.mediaProgress().upsert(progressRow(900.0, lastUpdate = 500L, pending = false))

    assertEquals(0, import.import(listOf(lmp(lastUpdate = 200L))))

    assertEquals(900.0, row()!!.currentTime, 0.0)
    assertTrue(import.unsettled("srv").isEmpty())
  }

  @Test
  fun reimportsNewerPaperOnEveryInit() = runBlocking {
    db.mediaProgress().upsert(progressRow(100.0, lastUpdate = 100L, pending = false))
    import.import(listOf(lmp(lastUpdate = 200L, currentTime = 300.0)))
    assertEquals(300.0, row()!!.currentTime, 0.0)

    // The rolled-back version moved the Paper copy on
    assertEquals(1, import.import(listOf(lmp(lastUpdate = 400L, currentTime = 450.0))))

    assertEquals(450.0, row()!!.currentTime, 0.0)
    assertEquals("the server record's id is kept", "mp1", row()!!.serverProgressId)
  }

  @Test
  fun importDoesNotOverwritePendingRow() = runBlocking {
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 100L, pending = true))

    assertEquals(0, import.import(listOf(lmp(lastUpdate = 999L))))

    assertEquals(10.0, row()!!.currentTime, 0.0)
  }

  @Test
  fun importQueuesNewerLocalFinished() = runBlocking {
    import.import(listOf(lmp(lastUpdate = 200L, isFinished = true)))
    serverHas(serverProgress("li1", 50.0, lastUpdate = 100L))

    assertTrue(reconcile().isSuccess)

    assertTrue(row()!!.pending)
    assertTrue(row()!!.isFinished)
    val fields = OutboxPayload.progressPatchFields(outbox().single().payload)!!
    assertEquals(PaperProgressImport.IMPORT_FIELDS, fields)
    assertTrue(import.unsettled("srv").isEmpty())

    assertTrue(reconcile().isSuccess)
    assertEquals("a second reconcile queues nothing new", 1, outbox().size)
  }

  @Test
  fun importQueuesNewerEbookProgress() = runBlocking {
    import.import(listOf(lmp(lastUpdate = 200L, ebookLocation = "epubcfi(/6/2)", ebookProgress = 0.4)))
    serverHas(serverProgress("li1", 50.0, lastUpdate = 100L))
    reconcile()

    OutboxPusher(db, syncHttp(api), store, { 2L }, {}).pushAll(fakeServer())

    val patch = api.requests.last()
    assertEquals("PATCH /api/me/progress/li1", patch.toString())
    val body = JSONObject(patch.body!!)
    assertEquals("epubcfi(/6/2)", body.getString("ebookLocation"))
    assertEquals(0.4, body.getDouble("ebookProgress"), 0.0)
    assertEquals(200L, body.getLong("lastUpdate"))
  }

  @Test
  fun importDoesNotPushOlderLocal() = runBlocking {
    import.import(listOf(lmp(lastUpdate = 200L, currentTime = 300.0)))
    serverHas(serverProgress("li1", 700.0, lastUpdate = 500L))

    assertTrue(reconcile().isSuccess)

    assertTrue(outbox().isEmpty())
    assertEquals(700.0, row()!!.currentTime, 0.0)
    assertEquals("server", row()!!.source)
  }

  @Test
  fun importedRowAbsentOnServerIsKeptButNotPushed() = runBlocking {
    import.import(listOf(lmp(lastUpdate = 200L)))
    serverHas()

    assertTrue(reconcile().isSuccess)

    assertNotNull("this reconcile spares it", row())
    assertFalse(row()!!.pending)
    assertTrue(outbox().isEmpty())
    assertTrue(import.unsettled("srv").isEmpty())
  }

  @Test
  fun progressResetInTheWebClientDoesNotComeBackFromAnOldPaperCopy() = runBlocking {
    // Synced before (the row knows its server record), then reset in the web client: the server has no record, and
    // the downloaded copy still has its old Paper progress, written later by the old version
    db.mediaProgress().upsert(progressRow(600.0, lastUpdate = 100L, pending = false))
    import.import(listOf(lmp(lastUpdate = 200L, currentTime = 700.0)))
    serverHas()

    assertTrue(reconcile().isSuccess)
    assertTrue(reconcile().isSuccess)

    OutboxPusher(db, syncHttp(api), store, { 2L }, {}).pushAll(fakeServer())
    assertTrue("no PATCH brings it back", api.paths().none { it.startsWith("PATCH") })
    assertNull("the next reconcile follows the server", row())
  }

  @Test
  fun progressTheServerNeverSawStaysOnTheDevice() = runBlocking {
    // "Finished" offline on the old version, for a book the server has no progress of
    import.import(listOf(lmp(lastUpdate = 200L, isFinished = true)))
    serverHas()

    assertTrue(reconcile().isSuccess)
    assertTrue(reconcile().isSuccess)

    assertTrue("kept after both reconciles", row()!!.isFinished)
    assertTrue("and not sent", outbox().isEmpty())
  }

  @Test
  fun importIsSettledPerServer() = runBlocking {
    import.import(listOf(lmp(serverConfigId = "a"), lmp(serverConfigId = "b")))

    serverHas(serverProgress("li1", 50.0, lastUpdate = 100L))
    assertTrue(reconciler.reconcileUser(fakeServer("a", version = "2.35.1")).isSuccess)

    assertEquals(1, outbox(serverConfigId = "a").size)
    assertTrue("b waits for its own reconcile", outbox(serverConfigId = "b").isEmpty())
    assertEquals(setOf(progressItemKey("li1", null)), import.unsettled("b"))
    assertTrue(import.unsettled("a").isEmpty())

    assertTrue(reconciler.reconcileUser(fakeServer("b", version = "2.35.1")).isSuccess)
    assertEquals(1, outbox(serverConfigId = "b").size)
    assertTrue(import.unsettled("b").isEmpty())
  }

  @Test
  fun failedReconcileKeepsTheImportUnsettled() = runBlocking {
    import.import(listOf(lmp()))
    api.handler = { 500 to "" }

    assertFalse(reconcile().isSuccess)

    assertEquals(1, import.unsettled("srv").size)
    assertTrue(outbox().isEmpty())
  }

  @Test
  fun importSurvivesARestart() = runBlocking {
    import.import(listOf(lmp()))
    assertEquals(1, PaperProgressImport(db, prefs).unsettled("srv").size)
  }

  @Test
  fun startImportsPaperBeforeTheFirstSync() = runBlocking {
    serverHas(serverProgress("li1", 50.0, lastUpdate = 100L))
    SyncEngine.initForTest(SyncDeps(
            db = db, http = syncHttp(api), activeServer = { fakeServer(version = "2.35.1") },
            serverState = MutableStateFlow(ServerState.ONLINE), onlineEntries = MutableStateFlow(1L),
            serverId = MutableStateFlow("srv"), isForeground = { true }, clock = { 1L }, scope = scope, log = {},
            notifyWindowMs = 10, paperImport = import, paperProgress = { listOf(lmp(lastUpdate = 200L, isFinished = true)) }))

    eventually("the imported progress is sent") { api.paths().contains("PATCH /api/me/progress/li1") }
    assertEquals("GET /api/me", api.paths().first())
    eventually("and settled") { row()?.pending == false }
  }
}
