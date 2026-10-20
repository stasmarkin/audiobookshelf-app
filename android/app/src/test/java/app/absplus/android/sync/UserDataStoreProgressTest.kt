package app.absplus.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import io.paperdb.Paper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Progress writes of [UserDataStore] and their Paper mirror, on in-memory Room and Paper under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDataStoreProgressTest {
  private val db = inMemoryUserData()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val api = FakeApi()
  private val config = fakeServer()
  @Volatile private var now = 10_000L
  private lateinit var pusher: OutboxPusher
  /** Scopes the store announced. */
  private val changes = java.util.Collections.synchronizedList(mutableListOf<String>())
  private val store = UserDataStore(db, DataChangeNotifier(10, scope) { name, _ -> changes.add(name) }, { now }, { pusher.inFlightId },
          unsentSessionKeys = PaperProgressMirror::unsentSessionKeys, mirror = PaperProgressMirror)

  init {
    pusher = OutboxPusher(db, syncHttp(api), store, { now }, {})
  }

  @Before
  fun setUp() {
    Paper.init(ApplicationProvider.getApplicationContext<Context>())
    for (book in listOf("localLibraryItems", "localMediaProgress", "playbackSession", "mediaItemHistory")) Paper.book(book).destroy()
    // Emptied behind DbManager's back: the mirror must not keep the previous test's copies
    DbManager.localLibraryItemsChanged()
  }

  @After
  fun tearDown() {
    scope.cancel()
    db.close()
  }

  private suspend fun row(episodeId: String = "") = db.mediaProgress().get("srv", "li1", episodeId)
  private suspend fun outbox() = db.outbox().byItemKey("srv", progressItemKey("li1", null))
  private fun json(body: String?) = JSONObject(body!!)
  private fun keys(body: String?) = json(body).keys().asSequence().toSet()

  private fun server(currentTime: Double, lastUpdate: Long, id: String = "mp1", libraryItemId: String = "li1") = ServerProgress(
          id = id, libraryItemId = libraryItemId, episodeId = null, duration = 1000.0, progress = currentTime / 1000.0,
          currentTime = currentTime, isFinished = false, hideFromContinueListening = false, ebookLocation = null,
          ebookProgress = null, lastUpdate = lastUpdate, startedAt = 1L, finishedAt = null)

  /** A downloaded copy of li1 from [serverConfigId], with its Paper progress at [currentTime]. */
  private fun downloadedCopy(serverConfigId: String = "srv", currentTime: Double? = null, localId: String = "local_$serverConfigId"): String {
    DeviceManager.dbManager.saveLocalLibraryItem(LocalLibraryItem(
            localId, "folder", "", "", "", false, "book", MediaType(MediaTypeMetadata("Book", false), null),
            mutableListOf(), null, null, true, serverConfigId, "https://$serverConfigId.test.invalid", "user", "li1"))
    if (currentTime != null) DeviceManager.dbManager.saveLocalMediaProgress(LocalMediaProgress(
            localId, localId, null, 1000.0, currentTime / 1000.0, currentTime, false, null, null, 5L, 1L, null,
            serverConfigId, "https://$serverConfigId.test.invalid", "user", "li1", null))
    return localId
  }

  private fun paper(localId: String = "local_srv") = DeviceManager.dbManager.getLocalMediaProgress(localId)

  /** An unsent Paper listening session of li1 on [serverConfigId], as the player leaves it until `local-all`. */
  private fun unsentSession(serverConfigId: String = "srv") {
    DeviceManager.dbManager.savePlaybackSession(PlaybackSession(
            "session-1", "user", "li1", null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
            emptyList(), "Book", "Author", null, 1000.0, 3, 0L, 0L, 0L, mutableListOf(), 600.0, null, null, null,
            serverConfigId, null, null))
  }

  @Test
  fun markFinishedWritesPendingRowAndPatchOfTheFlag() = runBlocking {
    db.mediaProgress().upsert(progressRow(400.0, lastUpdate = 5L, pending = false))

    store.markFinished("srv", "li1", null, true)

    val row = row()!!
    assertTrue(row.isFinished)
    assertEquals(1.0, row.progress, 0.0)
    assertEquals("as the Paper copy: finished starts over", 0.0, row.currentTime, 0.0)
    assertEquals(now, row.finishedAt)
    assertEquals(now, row.lastUpdate)
    assertEquals("device", row.source)
    assertTrue(row.pending)
    assertEquals(listOf("isFinished"), OutboxPayload.progressPatchFields(outbox().single().payload))

    now += 1
    store.markFinished("srv", "li1", null, false)
    assertFalse(row()!!.isFinished)
    assertEquals(0.0, row()!!.progress, 0.0)
    assertNull(row()!!.finishedAt)
    assertEquals("folded into one row", 1, outbox().size)
  }

  @Test
  fun markFinishedWithoutARowCreatesOne() = runBlocking {
    store.markFinished("srv", "li1", "ep1", true)

    val row = row("ep1")!!
    assertTrue(row.isFinished && row.pending)
    assertEquals(0.0, row.duration, 0.0)
    assertEquals(now, row.startedAt)
    assertNull(row.serverProgressId)
  }

  @Test
  fun patchBodiesCarryOnlyTheChangedFields() = runBlocking {
    db.mediaProgress().upsert(progressRow(400.0, lastUpdate = 5L, pending = false))
    store.markFinished("srv", "li1", null, true)
    pusher.pushAll(config)
    assertEquals(setOf("isFinished", "lastUpdate"), keys(api.requests.single().body))
    assertEquals(now, json(api.requests.single().body).getLong("lastUpdate"))

    api.requests.clear()
    store.updateEbookProgress("srv", "li1", "epubcfi(/6/4)", 0.25)
    pusher.pushAll(config)
    val body = api.requests.single().body
    assertEquals(setOf("ebookLocation", "ebookProgress", "lastUpdate"), keys(body))
    assertEquals("epubcfi(/6/4)", json(body).getString("ebookLocation"))
    assertEquals(0.25, json(body).getDouble("ebookProgress"), 0.0)
    assertFalse(row()!!.pending)
  }

  @Test
  fun markFinishedOnDownloadedItemMirrorsToPaper() = runBlocking {
    downloadedCopy(currentTime = 300.0)

    store.markFinished("srv", "li1", null, true)

    val paper = paper()!!
    assertTrue(paper.isFinished)
    assertEquals(1.0, paper.progress, 0.0)
    assertEquals(0.0, paper.currentTime, 0.0)
    assertEquals(now, paper.lastUpdate)
    assertEquals("the copy keeps its duration", 1000.0, paper.duration, 0.0)
  }

  @Test
  fun ebookProgressMirrorsOnlyTheEbookFields() = runBlocking {
    downloadedCopy(currentTime = 300.0)
    store.updateEbookProgress("srv", "li1", "cfi", 0.5)

    val paper = paper()!!
    assertEquals("cfi", paper.ebookLocation)
    assertEquals(0.5, paper.ebookProgress!!, 0.0)
    assertEquals("the audio position of the copy is not touched", 300.0, paper.currentTime, 0.0)
  }

  @Test
  fun mirrorIgnoresCopyOfAnotherAccount() = runBlocking {
    downloadedCopy("a", currentTime = 100.0)
    downloadedCopy("b", currentTime = 100.0)

    store.markFinished("a", "li1", null, true)

    assertTrue(paper("local_a")!!.isFinished)
    assertFalse(paper("local_b")!!.isFinished)
    assertEquals(100.0, paper("local_b")!!.currentTime, 0.0)
  }

  @Test
  fun mirrorFindsTheEpisodeByItsServerId() = runBlocking {
    val episode = PodcastEpisode("local-ep", 1, "1", "full", "Episode", null, null, null, 1L, null, null, null, 60.0, 1L, "ep1", null)
    DeviceManager.dbManager.saveLocalLibraryItem(LocalLibraryItem(
            "local_pod", "folder", "", "", "", false, "podcast",
            Podcast(PodcastMetadata("Show", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(episode), false, 1),
            mutableListOf(), null, null, true, "srv", "https://srv.test.invalid", "user", "li1"))

    store.markFinished("srv", "li1", "ep1", true)

    val paper = DeviceManager.dbManager.getLocalMediaProgress("local_pod-local-ep")!!
    assertTrue(paper.isFinished)
    assertEquals("ep1", paper.episodeId)
    assertEquals("local-ep", paper.localEpisodeId)
    assertEquals("the duration comes from the copy's episode", 60.0, paper.duration, 0.0)
  }

  @Test
  fun recordPlaybackDoesNotEnqueue() = runBlocking {
    store.recordPlayback("srv", "li1", null, 600.0, 1000.0, 0.6, lastUpdate = 777L)

    assertTrue(outbox().isEmpty())
    val row = row()!!
    assertFalse(row.pending)
    assertEquals(600.0, row.currentTime, 0.0)
    assertEquals(777L, row.lastUpdate)
    assertEquals("a new row starts with the session", 777L, row.startedAt)
  }

  @Test
  fun recordPlaybackFinishesLikePaper() = runBlocking {
    store.recordPlayback("srv", "li1", null, 995.0, 1000.0, 0.995, lastUpdate = 800L)
    assertTrue(row()!!.isFinished)
    assertEquals(800L, row()!!.finishedAt)

    store.recordPlayback("srv", "li1", null, 10.0, 1000.0, 0.01, lastUpdate = 900L)
    assertFalse("listening again unmarks it", row()!!.isFinished)
    assertNull(row()!!.finishedAt)
    assertEquals("startedAt is kept", 800L, row()!!.startedAt)
  }

  @Test
  fun recordPlaybackKeepsPendingOfUnsentAction() = runBlocking {
    store.markFinished("srv", "li1", null, true)
    store.recordPlayback("srv", "li1", null, 42.0, 1000.0, 0.042, lastUpdate = now + 1)

    store.applyServerProgress("srv", listOf(server(900.0, lastUpdate = now + 1_000)))

    val row = row()!!
    assertTrue(row.pending)
    assertEquals("the tick's position, not the server's", 42.0, row.currentTime, 0.0)
    assertEquals(listOf("isFinished"), OutboxPayload.progressPatchFields(outbox().single().payload))

    store.onOutboxSent(outbox().single())
    assertFalse(row()!!.pending)
  }

  @Test
  fun applyServerProgressRespectsPending() = runBlocking {
    downloadedCopy(currentTime = 100.0)
    db.mediaProgress().upsert(progressRow(100.0, lastUpdate = 5L, pending = false))
    db.mediaProgress().upsert(progressRow(50.0, lastUpdate = 5L, pending = true).copy(libraryItemId = "li2"))

    val taken = store.applyServerProgress("srv", listOf(server(700.0, 50L), server(800.0, 50L, id = "mp2", libraryItemId = "li2")))

    assertEquals(listOf("li1"), taken.map { it.libraryItemId })
    assertEquals(700.0, row()!!.currentTime, 0.0)
    assertEquals("server", row()!!.source)
    assertEquals(50.0, db.mediaProgress().get("srv", "li2", "")!!.currentTime, 0.0)
    assertEquals("the accepted value is mirrored", 700.0, paper()!!.currentTime, 0.0)
    assertEquals(50L, paper()!!.lastUpdate)
  }

  @Test
  fun keptRowLearnsTheServerId() = runBlocking {
    db.mediaProgress().upsert(progressRow(100.0, lastUpdate = 500L, pending = true).copy(serverProgressId = null))

    assertFalse(store.applyServerProgressCandidate("srv", server(10.0, lastUpdate = 100L, id = "mp9")))

    val row = row()!!
    assertEquals("mp9", row.serverProgressId)
    assertEquals("nothing else changes", 500L, row.lastUpdate)
    assertEquals(100.0, row.currentTime, 0.0)
  }

  @Test
  fun onOutboxSentClearsProgressPending() = runBlocking {
    store.markFinished("srv", "li1", null, true)
    assertTrue(row()!!.pending)

    store.onOutboxSent(outbox().single())

    assertFalse(row()!!.pending)
    assertTrue(outbox().isEmpty())
  }

  @Test
  fun fullPullDeletesOnlyWhatIsSafeToDelete() = runBlocking {
    downloadedCopy(currentTime = 100.0)
    db.mediaProgress().upsert(progressRow(100.0, lastUpdate = 5L, pending = false))
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 5L, pending = true).copy(libraryItemId = "li2"))
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 5L, pending = false).copy(libraryItemId = "li3"))

    store.applyServerProgress("srv", emptyList())
    assertEquals("a snapshot never deletes", 3, db.mediaProgress().getAll("srv").size)

    store.applyServerProgress("srv", emptyList(), deleteMissing = true) { setOf(progressItemKey("li3", null)) }

    assertEquals(listOf("li2", "li3"), db.mediaProgress().getAll("srv").map { it.libraryItemId }.sorted())
    assertNull("the copy's progress goes too, or the next import would bring it back", paper())
  }

  @Test
  fun unsentPaperSessionProtectsRow() = runBlocking {
    downloadedCopy(currentTime = 600.0)
    db.mediaProgress().upsert(progressRow(600.0, lastUpdate = 100L, pending = false))
    unsentSession()

    // Another device streamed from an earlier position later
    assertFalse(store.applyServerProgressCandidate("srv", server(30.0, lastUpdate = 999L)))
    store.applyServerProgress("srv", listOf(server(30.0, lastUpdate = 999L)))
    assertEquals(600.0, row()!!.currentTime, 0.0)
    assertEquals(600.0, paper()!!.currentTime, 0.0)

    store.applyServerProgress("srv", emptyList(), deleteMissing = true)
    assertNotNull("a full pull does not delete it either", row())

    // local-all took the session: the protection is gone with it
    DeviceManager.dbManager.removePlaybackSession("session-1")
    assertTrue(store.applyServerProgressCandidate("srv", server(30.0, lastUpdate = 999L)))
    assertEquals(30.0, row()!!.currentTime, 0.0)
    assertEquals(30.0, paper()!!.currentTime, 0.0)
  }

  @Test
  fun sessionOfAnotherServerDoesNotProtect() = runBlocking {
    db.mediaProgress().upsert(progressRow(600.0, lastUpdate = 100L, pending = false))
    unsentSession("other")

    assertTrue(store.applyServerProgressCandidate("srv", server(30.0, lastUpdate = 999L)))
  }

  @Test
  fun resetThenPlaybackSendsDeleteThenPatch() = runBlocking {
    downloadedCopy(currentTime = 500.0)
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false))

    store.resetProgress("srv", "li1", null)
    assertNull("the copy's progress is removed, as before", paper())
    val reset = row()!!
    assertEquals(0.0, reset.currentTime, 0.0)
    assertEquals("mp1", reset.serverProgressId)
    assertTrue(reset.pending)
    assertEquals(listOf(OutboxKind.PROGRESS_DELETE), outbox().map { it.kind })

    for (t in 1..3) store.recordPlayback("srv", "li1", null, t * 20.0, 1000.0, t * 0.02, lastUpdate = now + t)
    assertEquals(listOf(OutboxKind.PROGRESS_DELETE, OutboxKind.PROGRESS_PATCH), outbox().map { it.kind })

    assertEquals(PushResult(2, 0, 0, null), pusher.pushAll(config))

    assertEquals(listOf("DELETE /api/me/progress/mp1", "PATCH /api/me/progress/li1"), api.paths())
    val patch = json(api.requests[1].body)
    assertEquals(60.0, patch.getDouble("currentTime"), 0.0)
    assertEquals(setOf("duration", "progress", "currentTime", "isFinished", "lastUpdate"), keys(api.requests[1].body))
    assertFalse(row()!!.pending)
    assertEquals("the deleted record's id is forgotten; the PATCH made a new record, id still unknown",
            UserDataStore.SERVER_RECORD_SEEN, row()!!.serverProgressId)
  }

  @Test
  fun tickDuringInFlightDeleteIsSentAfterIt() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false))
    store.resetProgress("srv", "li1", null)
    val arrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    api.handler = { request ->
      if (request.method == "DELETE") {
        arrived.countDown()
        release.await(5, TimeUnit.SECONDS)
      }
      200 to "{}"
    }
    val push = scope.async { pusher.pushAll(config) }
    assertTrue(arrived.await(5, TimeUnit.SECONDS))
    store.recordPlayback("srv", "li1", null, 90.0, 1000.0, 0.09, lastUpdate = now + 50)
    release.countDown()

    assertEquals(PushResult(2, 0, 0, null), push.await())
    assertEquals(listOf("DELETE /api/me/progress/mp1", "PATCH /api/me/progress/li1"), api.paths())
    assertEquals(90.0, json(api.requests[1].body).getDouble("currentTime"), 0.0)
    assertFalse(row()!!.pending)
  }

  @Test
  fun writeDuringInFlightDeleteGetsAFollowUpPatch() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false))
    store.resetProgress("srv", "li1", null)
    api.handler = { request ->
      // Not through the store: only the pusher's check after the answer can notice it
      if (request.method == "DELETE") runBlocking { db.mediaProgress().upsert(row()!!.copy(currentTime = 75.0, lastUpdate = now + 50)) }
      200 to "{}"
    }

    assertEquals(PushResult(2, 0, 0, null), pusher.pushAll(config))

    assertEquals(listOf("DELETE /api/me/progress/mp1", "PATCH /api/me/progress/li1"), api.paths())
    assertEquals(75.0, json(api.requests[1].body).getDouble("currentTime"), 0.0)
  }

  @Test
  fun resetWithoutListeningRemovesTheRowAfterTheDelete() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false))
    store.resetProgress("srv", "li1", null)
    api.handler = { 404 to "Not Found" }

    assertEquals(PushResult(1, 0, 0, null), pusher.pushAll(config))

    assertNull("no record on the server, none on the device", row())
    assertTrue(outbox().isEmpty())
  }

  @Test
  fun resetWithoutTheServerIdPatchesZeros() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false).copy(serverProgressId = null, ebookLocation = "cfi"))

    store.resetProgress("srv", "li1", null)
    pusher.pushAll(config)

    val body = api.requests.single()
    assertEquals("PATCH /api/me/progress/li1", body.toString())
    assertEquals(setOf("currentTime", "progress", "isFinished", "ebookLocation", "ebookProgress", "lastUpdate"), keys(body.body))
    assertEquals(0.0, json(body.body).getDouble("currentTime"), 0.0)
    assertTrue(json(body.body).isNull("ebookLocation"))
    assertNotNull("the row stays: the server keeps a zeroed record", row())
  }

  @Test
  fun resetWithoutARowDoesNothing() = runBlocking {
    store.resetProgress("srv", "li1", null)
    assertNull(row())
    assertTrue(outbox().isEmpty())
  }

  @Test
  fun downloadedCopyGetsTheNewerRoomProgress() = runBlocking {
    // The snapshot taken when the download was requested is older than what the device recorded since
    downloadedCopy(currentTime = 100.0)
    db.mediaProgress().upsert(progressRow(650.0, lastUpdate = 500L, pending = false))

    store.onCopyDownloaded("srv", "li1", null, server(100.0, lastUpdate = 5L))

    assertEquals(650.0, paper()!!.currentTime, 0.0)
    assertEquals(500L, paper()!!.lastUpdate)
    assertEquals("the row is not rolled back", 650.0, row()!!.currentTime, 0.0)
  }

  @Test
  fun newerDownloadSnapshotMergesIntoRoom() = runBlocking {
    downloadedCopy(currentTime = 800.0)
    db.mediaProgress().upsert(progressRow(100.0, lastUpdate = 5L, pending = false))

    store.onCopyDownloaded("srv", "li1", null, server(800.0, lastUpdate = 900L))

    assertEquals(800.0, row()!!.currentTime, 0.0)
    assertEquals(800.0, paper()!!.currentTime, 0.0)
  }

  @Test
  fun downloadWithoutSnapshotOrRowLeavesThePaperCopy() = runBlocking {
    downloadedCopy(currentTime = 40.0)

    store.onCopyDownloaded("srv", "li1", null, null)

    assertNull(row())
    assertEquals(40.0, paper()!!.currentTime, 0.0)
  }

  @Test
  fun itemRowsAreReadByTheirIds() = runBlocking {
    db.mediaProgress().upsert(progressRow(1.0, 1L))
    db.mediaProgress().upsert(progressRow(2.0, 1L, episodeId = "ep1").copy(libraryItemId = "li2"))
    db.mediaProgress().upsert(progressRow(3.0, 1L).copy(libraryItemId = "li3"))
    db.mediaProgress().upsert(progressRow(4.0, 1L).copy(serverConfigId = "other", libraryItemId = "li2"))

    val rows = store.progressRowsFor("srv", listOf("li2", "li3", "li2"))

    assertEquals(listOf("li2" to 2.0, "li3" to 3.0), rows.map { it.libraryItemId to it.currentTime }.sortedBy { it.first })
  }

  @Test
  fun mirrorReadsTheCopiesAgainOnlyAfterTheyChange() = runBlocking {
    downloadedCopy(currentTime = 100.0)
    store.recordPlayback("srv", "li1", null, 110.0, 1000.0, 0.11, lastUpdate = 10L)
    assertEquals(110.0, paper()!!.currentTime, 0.0)

    // A copy written behind DbManager's back is not seen: the list is not read from disk on every tick
    Paper.book("localLibraryItems").delete("local_srv")
    store.recordPlayback("srv", "li1", null, 120.0, 1000.0, 0.12, lastUpdate = 20L)
    assertEquals("still mirrored from the kept list", 120.0, paper()!!.currentTime, 0.0)

    DbManager.localLibraryItemsChanged()
    store.recordPlayback("srv", "li1", null, 130.0, 1000.0, 0.13, lastUpdate = 30L)
    assertEquals("a change of the copies is read", 120.0, paper()!!.currentTime, 0.0)
  }

  @Test
  fun serverEchoOfTheDevicesOwnValuesChangesNothing() = runBlocking {
    downloadedCopy(currentTime = 600.0)
    db.mediaProgress().upsert(progressRow(600.0, lastUpdate = 100L, pending = false))
    kotlinx.coroutines.delay(50)
    changes.clear()

    // What the server sends after this device's own /sync: the same position, stamped later by the server
    assertFalse(store.applyServerProgressCandidate("srv", server(600.0, lastUpdate = 200L)))

    assertEquals("the server's time is kept", 200L, row()!!.lastUpdate)
    assertEquals("the copy is not rewritten", 5L, paper()!!.lastUpdate)
    kotlinx.coroutines.delay(50)
    assertTrue("nothing to re-read", changes.isEmpty())

    // A snapshot older than the echo but newer than the device's own value
    assertFalse(store.applyServerProgressCandidate("srv", server(500.0, lastUpdate = 150L)))
    assertEquals(600.0, row()!!.currentTime, 0.0)
  }

  @Test
  fun finishedMarkAfterAResetWithoutTheServerIdIsKeptWhilePlaying() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false).copy(serverProgressId = null))
    store.resetProgress("srv", "li1", null)
    now += 1
    // Folded into the reset's PATCH, which also sends the position
    store.markFinished("srv", "li1", null, true)
    store.recordPlayback("srv", "li1", null, 300.0, 1000.0, 0.3, lastUpdate = now + 1)

    assertEquals(1, outbox().size)
    assertTrue(row()!!.isFinished)
    pusher.pushAll(config)
    val body = json(api.requests.single().body)
    assertTrue(body.getBoolean("isFinished"))
    assertEquals(300.0, body.getDouble("currentTime"), 0.0)
  }

  @Test
  fun resetWithoutTheServerIdThenListeningToTheEndIsFinished() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false).copy(serverProgressId = null))
    store.resetProgress("srv", "li1", null)
    store.recordPlayback("srv", "li1", null, 600.0, 1000.0, 0.6, lastUpdate = now + 1)
    store.recordPlayback("srv", "li1", null, 995.0, 1000.0, 0.995, lastUpdate = now + 2)

    assertTrue(row()!!.isFinished)
    pusher.pushAll(config)
    assertTrue(json(api.requests.single().body).getBoolean("isFinished"))
  }

  @Test
  fun markFinishedThenTickKeepsFinished() = runBlocking {
    db.mediaProgress().upsert(progressRow(400.0, lastUpdate = 5L, pending = false))
    store.markFinished("srv", "li1", null, true)
    // Playing on before the mark reached the server
    store.recordPlayback("srv", "li1", null, 450.0, 1000.0, 0.45, lastUpdate = now + 15_000)

    assertTrue(row()!!.isFinished)
    assertEquals(now, row()!!.finishedAt)
    assertEquals(450.0, row()!!.currentTime, 0.0)
    pusher.pushAll(config)
    assertTrue(json(api.requests.single().body).getBoolean("isFinished"))
  }

  @Test
  fun delayedTickAfterResetIsDropped() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false))
    store.resetProgress("srv", "li1", null)
    val reset = row()!!

    // Sampled before the reset, written after it
    store.recordPlayback("srv", "li1", null, 505.0, 1000.0, 0.505, lastUpdate = now - 1)

    assertEquals(reset, row())
    assertEquals(listOf(OutboxKind.PROGRESS_DELETE), outbox().map { it.kind })
  }

  @Test
  fun staleSnapshotAfterResetDoesNotBringTheRecordBack() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false))
    store.resetProgress("srv", "li1", null)
    val resetAt = now
    pusher.pushAll(config)
    assertNull(row())

    // A user object or socket event built before the DELETE still has the old record
    now += 1_000
    assertFalse(store.applyServerProgressCandidate("srv", server(500.0, lastUpdate = 50L)))
    store.applyServerProgress("srv", listOf(server(500.0, lastUpdate = resetAt)))
    assertNull("snapshots from before the reset are ignored", row())

    // Listening on another device after the reset is a new record
    assertTrue(store.applyServerProgressCandidate("srv", server(30.0, lastUpdate = resetAt + 10)))
    db.mediaProgress().delete("srv", "li1", "")

    // A fresh full pull is the server's current state: no marker applies
    store.applyServerProgress("srv", listOf(server(500.0, lastUpdate = 50L)), deleteMissing = true)
    assertEquals(500.0, row()!!.currentTime, 0.0)
  }

  @Test
  fun resetMarkerExpires() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 5L, pending = false))
    store.resetProgress("srv", "li1", null)
    pusher.pushAll(config)
    now += UserDataStore.RECENTLY_SETTLED_MS
    assertTrue(store.applyServerProgressCandidate("srv", server(500.0, lastUpdate = 50L)))
  }

  @Test
  fun mergedServerValueReportsTheRowAndWhetherTheDeviceKeepsIt() = runBlocking {
    db.mediaProgress().upsert(progressRow(9000.0, lastUpdate = 100L, pending = false))
    // A reconcile already took the server's position: nothing new, but the row is where the player should be
    val synced = store.mergeServerProgress("srv", server(9000.0, lastUpdate = 100L))
    assertEquals(CandidateResult(false, row(), false), synced)

    store.markFinished("srv", "li1", null, false)
    val pending = store.mergeServerProgress("srv", server(10.0, lastUpdate = now + 500))
    assertFalse(pending.applied)
    assertTrue(pending.protected)

  }

  @Test
  fun aServerValueTakenForAPlayedDownloadIsInItsHistory() = runBlocking {
    downloadedCopy(currentTime = 100.0)
    db.mediaProgress().upsert(progressRow(100.0, lastUpdate = 5L, pending = false))
    DeviceManager.dbManager.saveMediaItemHistory(app.absplus.android.data.MediaItemHistory(
            "li1", "Book", "li1", null, false, "srv", null, "user", 1L, mutableListOf()))

    store.applyServerProgressCandidate("srv", server(700.0, lastUpdate = 50L))
    // The server's echo of its own value again: no new entry
    store.applyServerProgressCandidate("srv", server(700.0, lastUpdate = 60L))

    val events = DeviceManager.dbManager.getMediaItemHistory("li1")!!.events
    assertEquals(listOf("Sync"), events.map { it.name })
    assertEquals(700.0, events.single().currentTime!!.toDouble(), 0.0)
  }

  @Test
  fun aFullPullKeepsRowsTheServerNeverHadAndDeletesRecordsGoneThere() = runBlocking {
    downloadedCopy(currentTime = 300.0)
    db.mediaProgress().upsert(progressRow(300.0, lastUpdate = 5L, pending = false).copy(serverProgressId = null))
    db.mediaProgress().upsert(progressRow(50.0, lastUpdate = 5L, pending = false).copy(libraryItemId = "li2"))

    repeat(2) { store.applyServerProgress("srv", emptyList(), deleteMissing = true) }

    assertNotNull("never seen by the server: stays", row())
    assertEquals(300.0, paper()!!.currentTime, 0.0)
    assertNull("its record deleted on the server: goes", db.mediaProgress().get("srv", "li2", ""))
  }

  @Test
  fun aRowTheServerTookByPatchIsDeletedOnceAnotherClientDeletesIt() = runBlocking {
    // Finished offline; the PATCH created the server record, whose id the device never learned
    store.markFinished("srv", "li1", null, true)
    pusher.pushAll(config)
    assertEquals(UserDataStore.SERVER_RECORD_SEEN, row()!!.serverProgressId)
    assertTrue("never handed out as an id", UserDataJson.progress(row()!!).isNull("id"))

    // Another client deleted it: the next full pull follows
    store.applyServerProgress("srv", emptyList(), deleteMissing = true)
    assertNull(row())
  }

  @Test
  fun aResetOfARowSeenWithoutItsIdZeroesItByPatch() = runBlocking {
    db.mediaProgress().upsert(progressRow(400.0, lastUpdate = 5L, pending = false).copy(serverProgressId = UserDataStore.SERVER_RECORD_SEEN))

    store.resetProgress("srv", "li1", null)

    assertEquals(listOf(OutboxKind.PROGRESS_PATCH), outbox().map { it.kind })
    // A pull brings the real id
    db.outbox().byItemKey("srv", progressItemKey("li1", null)).forEach { store.onOutboxSent(it) }
    store.applyServerProgressCandidate("srv", server(0.0, lastUpdate = 1L, id = "mp7"))
    assertEquals("mp7", row()!!.serverProgressId)
  }

  /** The scopes announced for one tick; their order depends on the dispatcher. */
  private fun scopesAfter(tick: suspend () -> Unit): Set<String> = runBlocking {
    changes.clear()
    tick()
    Thread.sleep(150)
    changes.toSet()
  }

  @Test
  fun playbackUnhidesFromContinueListening() = runBlocking {
    db.mediaProgress().upsert(progressRow(100.0, 5L, pending = false).copy(hideFromContinueListening = true))

    store.recordPlayback("srv", "li1", null, 100.0, 1000.0, 0.1, lastUpdate = 6L)
    assertTrue("the same position keeps it hidden", row()!!.hideFromContinueListening)

    store.recordPlayback("srv", "li1", null, 105.0, 1000.0, 0.105, lastUpdate = 7L)
    assertFalse(row()!!.hideFromContinueListening)
    assertTrue("the session carries it to the server", outbox().isEmpty())
  }

  @Test
  fun shelfChangingTickAlsoNotifiesProgress() {
    assertEquals("the first tick of a book", setOf("playback", "progress"),
            scopesAfter { store.recordPlayback("srv", "li1", null, 10.0, 1000.0, 0.01, lastUpdate = 1L) })
    assertEquals("an ordinary tick", setOf("playback"),
            scopesAfter { store.recordPlayback("srv", "li1", null, 20.0, 1000.0, 0.02, lastUpdate = 2L) })
    assertEquals("finishing", setOf("playback", "progress"),
            scopesAfter { store.recordPlayback("srv", "li1", null, 995.0, 1000.0, 0.995, lastUpdate = 3L) })
    runBlocking { db.mediaProgress().upsert(row()!!.copy(isFinished = false, hideFromContinueListening = true, pending = false)) }
    assertEquals("unhiding", setOf("playback", "progress"),
            scopesAfter { store.recordPlayback("srv", "li1", null, 30.0, 1000.0, 0.03, lastUpdate = 4L) })
  }
}
