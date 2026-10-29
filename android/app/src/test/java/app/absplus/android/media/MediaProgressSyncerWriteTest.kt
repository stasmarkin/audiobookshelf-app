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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            notifyWindowMs = 10, mirror = PaperProgressMirror, recentlySettledMs = 0))
    // Not created: a tick touches neither the player nor the notification
    val service = Robolectric.buildService(PlayerNotificationService::class.java).get()
    syncer = MediaProgressSyncer(service) { throw AssertionError("a tick sends nothing") }
  }

  @After
  fun tearDown() {
    SyncEngine.resetForTest()
    // A write still running would land in the next test's Paper books
    runBlocking { withTimeout(10_000) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
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

  private fun stream(id: String = "srv-1") = session(PLAYMETHOD_DIRECTPLAY, null).also {
    it.id = id
    it.timeListening = 600 // what the server session already holds
  }

  private val noRequest: (String, MediaProgressSyncData, (Boolean, String?) -> Unit) -> Unit = { _, _, _ -> throw AssertionError("no request") }

  /** One stream tick as `sync` runs it, on a snapshot of the session. */
  private fun streamTick(session: PlaybackSession, ledger: ListeningLedger, time: Double, hasNetwork: Boolean,
                         shouldSyncServer: Boolean = true,
                         send: (String, MediaProgressSyncData, (Boolean, String?) -> Unit) -> Unit = noRequest) {
    tick(session, time)
    syncer.syncStream(session.clone(), ledger, 15, hasNetwork, shouldSyncServer, send = send) {}
  }

  private fun restartProcess() {
    syncer = MediaProgressSyncer(Robolectric.buildService(PlayerNotificationService::class.java).get()) { throw AssertionError() }
  }

  private fun unsettledKeys() = DeviceManager.dbManager.unsettledListeningKeys()

  private fun paperSessions() = DeviceManager.dbManager.getPlaybackSessions()

  @Test
  fun noNetworkTickAlsoGoesToSegment() {
    val ledger = ListeningLedger()
    streamTick(stream(), ledger, 100.0, hasNetwork = false)

    val saved = paperSessions().single()
    val segment = ledger.segment()!!
    assertNotEquals("srv-1", saved.id)
    assertEquals(segment.id, saved.id)
    assertEquals(15L, saved.timeListening)
    assertEquals(100.0, saved.currentTime, 0.0)

    // The process ends before the network is back: a new syncer finds only the segment
    restartProcess()
    assertEquals(listOf(segment.id), paperSessions().map { it.id })
  }

  @Test
  fun streamedFailureSavesSegmentNotServerSession() {
    val ledger = ListeningLedger()
    val session = stream()
    val fail: (String, MediaProgressSyncData, (Boolean, String?) -> Unit) -> Unit = { _, _, done -> done(false, "Failed to connect") }
    streamTick(session, ledger, 100.0, hasNetwork = true, send = fail)
    streamTick(session, ledger, 115.0, hasNetwork = true, send = fail)

    val saved = paperSessions().single()
    assertEquals(ledger.segment()!!.id, saved.id)
    assertEquals(30L, saved.timeListening)
    assertEquals(115.0, saved.currentTime, 0.0)

    // A session an earlier version saved under the server's id
    DeviceManager.dbManager.savePlaybackSession(session.clone())
    val sent = mutableListOf<Pair<String, Long>>()
    streamTick(session, ledger, 130.0, hasNetwork = true) { id, data, done -> sent.add(id to data.timeListened); done(true, null) }
    assertEquals("only the time since the last attempt", listOf("srv-1" to 15L), sent)
    assertEquals("an accepted /sync keeps the segment", listOf(saved.id), paperSessions().map { it.id })
    assertNull(ledger.segment())
  }

  @Test
  fun aRequestSettlesTheSessionItWasSentFor() {
    val ledger = ListeningLedger()
    val first = stream("srv-1")
    var answer: ((Boolean, String?) -> Unit)? = null
    streamTick(first, ledger, 100.0, hasNetwork = true) { _, _, done -> answer = done }

    // The player moved on to another streamed session before the answer came
    syncer.reset()
    DeviceManager.dbManager.savePlaybackSession(stream("srv-2"))
    answer!!(true, null)
    assertEquals(listOf("srv-2"), paperSessions().map { it.id })

    streamTick(first, ledger, 115.0, hasNetwork = true) { _, _, done -> answer = done }
    answer!!(false, null)
    val segment = paperSessions().single { it.id != "srv-2" }
    assertEquals("li1", segment.libraryItemId)
    assertEquals(15L, segment.timeListening)
  }

  @Test
  fun rollbackTo2bKeepsSegments() {
    val booksBefore = paperBooks()
    val prefsBefore = sharedPrefs()
    val ledger = ListeningLedger()
    streamTick(stream(), ledger, 100.0, hasNetwork = true) { _, _, done -> done(false, null) }

    // What 2b reads and sends through local-all (createPartialPlaybackSession)
    val segment = paperSessions().single()
    assertEquals("srv", segment.serverConnectionConfigId)
    assertEquals("li1", segment.libraryItemId)
    assertEquals(15L, segment.timeListening)
    assertEquals(PLAYMETHOD_DIRECTPLAY, segment.playMethod)
    // The tick's queued write reads Paper too, and a read recreates the empty folder of a book setUp destroyed
    runBlocking { SyncEngine.write { } }
    val written = (paperBooks() - booksBefore).filter { java.io.File(paperDir(), it).list().orEmpty().isNotEmpty() }
    assertTrue("the segment's book is seen as written: $written", "playbackSession" in written)
    assertTrue("no other new Paper books: $written", written.all { it == "playbackSession" || it == "listeningLedger" })
    assertEquals("no new preferences", prefsBefore, sharedPrefs())
  }

  @Test
  fun aProcessEndingInTheMeteredWindowOrWithARequestInFlightKeepsItsTime() {
    val ledger = ListeningLedger()
    val session = stream()
    streamTick(session, ledger, 100.0, hasNetwork = true) { _, _, done -> done(false, null) }
    streamTick(session, ledger, 115.0, hasNetwork = true, shouldSyncServer = false) // metered: not sent yet
    streamTick(session, ledger, 130.0, hasNetwork = true) { _, _, _ -> } // no answer before the process ends
    assertEquals("only the failed time is sent so far", 15L, paperSessions().single().timeListening)

    ListeningLedger.forgetProcessForTest()
    restartProcess()
    val recovered = paperSessions().single()
    assertEquals("one offline session holds all of it", ledger.segment()!!.id, recovered.id)
    assertEquals(45L, recovered.timeListening)
    assertEquals(130.0, recovered.currentTime, 0.0)
    assertTrue(unsettledKeys().isEmpty())
  }

  @Test
  fun aServiceCreatedAgainLeavesLiveLedgersToTheirAnswers() {
    val ledger = ListeningLedger()
    var answer: ((Boolean, String?) -> Unit)? = null
    streamTick(stream(), ledger, 100.0, hasNetwork = true) { _, _, done -> answer = done }

    // The service is destroyed and created again (a widget, a headset) while that /sync is on the wire
    restartProcess()
    assertTrue("its time is still the request's", paperSessions().isEmpty())
    answer!!(true, null)
    assertTrue("the server took it: nothing to send again", paperSessions().isEmpty())
    assertTrue(unsettledKeys().isEmpty())
  }

  @Test
  fun anAnswerOnAnotherThreadIsSettledOnTheMainThread() {
    var now = 1_000_000L
    var answer: ((Boolean, String?) -> Unit)? = null
    val streamed = MediaProgressSyncer(Robolectric.buildService(PlayerNotificationService::class.java).get(),
            apiHandler = { throw AssertionError() }, clock = { now }, hasNetwork = { true }, playerTime = { 100.0 },
            sendProgressSync = { _, _, done -> answer = done })
    streamed.start(stream("srv-1"))
    now += 15_000
    var stopped = false
    streamed.stop { stopped = true }
    Thread { answer!!(true, null) }.apply { start(); join() }
    assertEquals("nothing reset off the main thread", "srv-1", streamed.currentSessionId)
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    assertTrue(stopped)
    assertEquals("", streamed.currentSessionId)
  }

  @Test
  fun aDownloadedSessionsListeningTimeOnlyGrowsThroughPausePlayStopAndRestart() {
    DeviceManager.dbManager.saveLocalLibraryItem(copy)
    DeviceManager.serverConnectionConfig = fakeServer()
    try {
      var now = 1_000_000L
      val sent = mutableListOf<Long>()
      var succeed = true
      fun downloadedSyncer() = MediaProgressSyncer(Robolectric.buildService(PlayerNotificationService::class.java).get(),
              apiHandler = { throw AssertionError() }, clock = { now }, hasNetwork = { true }, playerTime = { 100.0 },
              sendLocalProgressSync = { session, done -> sent.add(session.timeListening); done(succeed, null) })
      // The player's own session object: its listening time never grows
      val playerSession = session(PLAYMETHOD_LOCAL, copy).also { it.id = "local-session" }
      var downloaded = downloadedSyncer()

      downloaded.start(playerSession)
      now += 15_000
      downloaded.sync(true, 100.0) {}
      now += 15_000
      downloaded.pause {}
      downloaded.start(playerSession) // play again
      now += 15_000
      downloaded.sync(true, 130.0) {}
      now += 15_000
      downloaded.stop {}
      succeed = false
      downloaded.start(playerSession) // the same item started again
      now += 15_000
      downloaded.sync(true, 160.0) {}
      assertEquals(listOf(15L, 30L, 45L, 60L, 75L), sent)
      assertEquals(75L, DeviceManager.dbManager.getPlaybackSession("local-session")!!.timeListening)

      downloaded.stop(false) {}
      downloaded = downloadedSyncer() // the process restarted
      downloaded.start(playerSession)
      now += 15_000
      downloaded.sync(true, 175.0) {}
      assertEquals(90L, sent.last())
      assertEquals(90L, DeviceManager.dbManager.getPlaybackSession("local-session")!!.timeListening)
      downloaded.stop(false) {}
    } finally {
      DeviceManager.serverConnectionConfig = null
    }
  }

  @Test
  fun anAcceptedSyncLetsAWebDeletionReachItsRow() = runBlocking {
    db.mediaProgress().upsert(progressRow(700.0, lastUpdate = 1L, pending = false).copy(serverProgressId = null))
    syncer.syncStream(stream().clone(), ListeningLedger(), 15, hasNetwork = true, shouldSyncServer = true,
            send = { _, _, done -> done(true, null) }) {}
    eventually("marked as seen by the server") { row()?.serverProgressId != null }
    SyncEngine.store!!.applyServerProgress("srv", emptyList(), deleteMissing = true)
    assertNull(row())
  }

  @Test
  fun timeNotSentYetGoesToTheSegmentWhenTheSessionEnds() {
    var now = 1_000_000L
    val streamed = MediaProgressSyncer(Robolectric.buildService(PlayerNotificationService::class.java).get(),
            apiHandler = { throw AssertionError() }, clock = { now }, hasNetwork = { true }, playerTime = { 100.0 }, sendProgressSync = noRequest)
    streamed.start(stream("srv-1"))
    now += 15_000
    streamed.sync(false, 100.0) {} // metered: kept for the next attempt
    streamed.start(stream("srv-2")) // the queue moved on
    now += 15_000
    streamed.sync(false, 200.0) {}
    streamed.stop(false) {} // e.g. a playback error: nothing is sent
    val sessions = paperSessions()
    assertEquals(listOf("li1", "li1"), sessions.map { it.libraryItemId })
    assertEquals(listOf(15L, 15L), sessions.map { it.timeListening })
    assertTrue(sessions.none { it.id.startsWith("srv-") })
    assertTrue(unsettledKeys().isEmpty())
  }

  @Test
  fun anAnswerToAnEarlierPauseOrStopDoesNotResetWhatStartedSince() {
    var now = 1_000_000L
    val answers = mutableListOf<(Boolean, String?) -> Unit>()
    val streamed = MediaProgressSyncer(Robolectric.buildService(PlayerNotificationService::class.java).get(),
            apiHandler = { throw AssertionError() }, clock = { now }, hasNetwork = { true }, playerTime = { 100.0 }, sendProgressSync = { _, _, done -> answers.add(done) })
    try {
      streamed.start(stream("srv-1"))
      now += 15_000
      streamed.pause {}
      streamed.start(stream("srv-1")) // play again before the pause's /sync answered
      answers[0](true, null)
      now += 15_000
      streamed.sync(true, 115.0) {}
      assertEquals("ticks still reach the server", 2, answers.size)
      answers[1](true, null)

      now += 15_000
      streamed.stop {}
      streamed.start(stream("srv-2")) // the queue moved on before the stop's /sync answered
      answers[2](true, null)
      assertEquals("srv-2", streamed.currentSessionId)
    } finally {
      streamed.stop(false) {}
    }
  }

  // Paper keeps each book at the folder of the test that opened it first, so this is not this test's filesDir
  private fun paperDir() = java.io.File(Paper.book("playbackSession").path).parentFile!!
  private fun paperBooks() = paperDir().list()!!.toSet()
  private fun sharedPrefs() = java.io.File(context.applicationInfo.dataDir, "shared_prefs").list()?.toSet().orEmpty()

  @Test
  fun aSeekOfADownloadedCopyMovesItsPaperProgressUnlessThePlayerWroteLater() = runBlocking {
    DeviceManager.dbManager.saveLocalLibraryItem(copy)
    val session = session(PLAYMETHOD_LOCAL, copy)
    tick(session, 100.0)
    eventually("the tick") { row()?.currentTime == 100.0 }

    syncer.recordSeek(session, 300.0, session.id)
    SyncEngine.write { } // the write, with its mirror, is over
    assertEquals("the copy starts from the seek next time", 300.0, DeviceManager.dbManager.getLocalMediaProgress("local_li1")!!.currentTime, 0.0)

    // The player's own tick lands in Paper before a slower mirror of an earlier seek
    val paper = DeviceManager.dbManager.getLocalMediaProgress("local_li1")!!
    paper.currentTime = 450.0
    paper.lastUpdate = System.currentTimeMillis() + 60_000
    DeviceManager.dbManager.saveLocalMediaProgress(paper)
    syncer.recordSeek(session, 320.0, session.id)
    SyncEngine.write { }
    assertEquals("the newer tick stays", 450.0, DeviceManager.dbManager.getLocalMediaProgress("local_li1")!!.currentTime, 0.0)
  }

  /** The opener's /sync is answered after the queue moved on to the sequel. */
  private fun answerAfterTheQueueMovedOn(success: Boolean) {
    val opener = stream("session-opener")
    val sequel = stream("session-sequel")
    // Saved under their server ids by an earlier version
    DeviceManager.dbManager.savePlaybackSession(opener.clone())
    DeviceManager.dbManager.savePlaybackSession(sequel.clone())
    var answer: ((Boolean, String?) -> Unit)? = null
    syncer.syncStream(opener.clone(), ListeningLedger(), 15, hasNetwork = true, shouldSyncServer = true, send = { _, _, done -> answer = done }) {}
    syncer.currentPlaybackSession = sequel
    answer!!(success, null)
  }

  @Test
  fun aSyncAnswerRemovesTheSessionThatWasSent() {
    answerAfterTheQueueMovedOn(success = true)
    assertEquals("the sequel's session stays for its own sync", listOf("session-sequel"), paperSessions().map { it.id })
  }

  @Test
  fun aLateSyncAnswerLeavesTheCurrentSessionsSyncClock() {
    val before = syncer.lastSyncTime
    answerAfterTheQueueMovedOn(success = true)
    // Each tick moves the clock by what it counted; no answer moves it
    assertEquals(before, syncer.lastSyncTime)
  }

  @Test
  fun aFailedSyncAnswerKeepsBothSessions() {
    answerAfterTheQueueMovedOn(success = false)
    assertTrue(paperSessions().map { it.id }.containsAll(listOf("session-opener", "session-sequel")))
  }
}
