package app.absplus.android.sync

import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.server.RefreshResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [SyncEngine] with fake reachability, foreground flag, clock and server, on in-memory Room under Robolectric.
 * Room, OkHttp and the engine run on real threads, so the tests wait for conditions instead of virtual time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEngineTest {
  private val db = inMemoryUserData()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val api = FakeApi()
  private val serverA = fakeServer("srv")
  private var active: ServerConnectionConfig? = serverA
  private val state = MutableStateFlow(ServerState.OFFLINE)
  private val onlineEntries = MutableStateFlow(0L)
  private val serverId = MutableStateFlow<String?>("srv")
  @Volatile private var foreground = false
  @Volatile private var now = 100_000_000L
  private var refresh: RefreshResult = RefreshResult.Transient
  @Volatile private var failNextRun = false
  private val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
  private val store get() = SyncEngine.store!!

  private val serverBookmarks = """{"mediaProgress":[],"bookmarks":[{"libraryItemId":"li7","time":1,"title":"A","createdAt":1},
    {"libraryItemId":"li8","time":2,"title":"B","createdAt":1}]}"""

  /** Starts the engine and lets its APP_START run finish, so later state changes are separate requests. */
  private suspend fun start(unreachableRetryMs: List<Long> = UNREACHABLE_RETRY_MS, recentlySettledMs: Long = UserDataStore.RECENTLY_SETTLED_MS) {
    api.handler = { request -> if (request.method == "GET") 200 to serverBookmarks else 200 to "{}" }
    SyncEngine.initForTest(SyncDeps(
            db = db,
            http = SyncHttp(api.client, { _, onResult -> onResult(refresh) }, { "token" }, NoopSink()),
            activeServer = {
              if (failNextRun) {
                failNextRun = false
                throw IllegalStateException("database is full")
              }
              active
            },
            serverState = state,
            onlineEntries = onlineEntries,
            serverId = serverId,
            isForeground = { foreground || SyncRuntime.isForeground },
            clock = { now },
            scope = scope,
            log = {},
            notifyWindowMs = 10,
            logError = { errors.add(it) },
            unreachableRetryMs = unreachableRetryMs,
            recentlySettledMs = recentlySettledMs))
    settle()
  }

  @After
  fun tearDown() {
    SyncEngine.resetForTest()
    scope.cancel()
    db.close()
  }

  /** As ServerReachability publishes it: the state, and a count of the changes into ONLINE. */
  private fun setState(next: ServerState) {
    val was = state.value
    state.value = next
    if (next == ServerState.ONLINE && was != ServerState.ONLINE) onlineEntries.value++
  }

  /** Lets queued engine work start and finish. */
  private suspend fun settle() {
    repeat(3) {
      delay(30)
      SyncEngine.awaitIdleForTest()
    }
  }

  /** Reconciles: each starts with the progress list. */
  private fun gets() = api.paths().count { it == "GET /api/me/progress" }

  @Test
  fun offlineThenReconnectSendsQueuedBookmark() = runBlocking {
    start()
    store.addBookmark("srv", "li1", 754.0, "Offline")
    SyncEngine.onLocalWrite()
    settle()
    assertTrue("nothing is sent while the server is unreachable", api.requests.isEmpty())
    eventually("pending count 1") { SyncEngine.syncState.value.pendingCount == 1 }

    setState(ServerState.ONLINE)

    eventually("the queued bookmark is sent") { db.outbox().count("srv") == 0 }
    settle()
    assertEquals(listOf("POST /api/me/item/li1/bookmark"), api.paths())
    assertEquals(0, SyncEngine.syncState.value.pendingCount)
    assertTrue(db.bookmarks().getVisible("srv").none { it.pending })
  }

  @Test
  fun pullsServerBookmarksBeforePush() = runBlocking {
    foreground = true
    start()
    store.addBookmark("srv", "li1", 754.0, "Offline")

    setState(ServerState.ONLINE)

    eventually("pull and push") { api.requests.size == 3 && db.outbox().count("srv") == 0 }
    assertEquals(listOf("GET /api/me/progress", "GET /api/me/bookmarks", "POST /api/me/item/li1/bookmark"), api.paths())
    assertEquals(listOf("li1", "li7", "li8"), db.bookmarks().getVisible("srv").map { it.libraryItemId })
  }

  @Test
  fun concurrentSyncNowRunsOnceAndOnceMore() = runBlocking {
    foreground = true
    start()
    settle()
    val arrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    api.handler = { request ->
      if (api.requests.size == 1) {
        arrived.countDown()
        release.await(5, TimeUnit.SECONDS)
      }
      if (request.method == "GET") 200 to serverBookmarks else 200 to "{}"
    }
    setState(ServerState.ONLINE)
    assertTrue(arrived.await(5, TimeUnit.SECONDS))

    SyncEngine.syncNow(SyncReason.MANUAL)
    SyncEngine.syncNow(SyncReason.MANUAL)
    release.countDown()
    settle()

    assertEquals("one run, then exactly one more", 2, gets())
    assertEquals(1, api.maxConcurrent.get())
  }

  @Test
  fun serverSwitchCancelsRunningPush() = runBlocking {
    start()
    store.addBookmark("srv", "li1", 754.0, "On A")
    store.addBookmark("srvB", "li2", 5.0, "On B")
    val arrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    api.handler = {
      arrived.countDown()
      release.await(5, TimeUnit.SECONDS)
      200 to "{}"
    }
    setState(ServerState.ONLINE)
    assertTrue(arrived.await(5, TimeUnit.SECONDS))

    active = fakeServer("srvB")
    serverId.value = "srvB"
    eventually("the run is cancelled") { !SyncEngine.syncState.value.running }
    release.countDown()
    settle()

    val rowA = db.outbox().byItemKey("srv", bookmarkItemKey("li1", 754.0)).single()
    assertEquals("the late answer did not settle server A's row", 1, rowA.attempts)
    assertTrue(db.bookmarks().getVisible("srv").single().pending)
    val rowB = db.outbox().byItemKey("srvB", bookmarkItemKey("li2", 5.0)).single()
    assertEquals(0, rowB.attempts)
    assertEquals(1, api.requests.size)
  }

  @Test
  fun unauthorizedKeepsDataAndResumesAfterSignIn() = runBlocking {
    refresh = RefreshResult.Rejected
    start()
    store.addBookmark("srv", "li1", 754.0, "Mark")
    api.handler = { 401 to "Unauthorized" }

    setState(ServerState.ONLINE)
    eventually("the push stops") { SyncEngine.syncState.value.lastError == "unauthorized" }
    assertEquals(1, db.outbox().count("srv"))
    assertEquals(1, db.bookmarks().getVisible("srv").size)

    // ServerReachability goes UNAUTHORIZED on the rejected refresh, and ONLINE again on reportSignedIn
    setState(ServerState.UNAUTHORIZED)
    settle()
    api.handler = { 200 to "{}" }
    setState(ServerState.ONLINE)

    eventually("the bookmark is sent after signing in") { db.outbox().count("srv") == 0 }
    assertEquals(2, api.requests.count { it.method == "POST" })
  }

  @Test
  fun localWriteOnlyPushes() = runBlocking {
    foreground = true
    start()
    setState(ServerState.ONLINE)
    eventually("the first reconcile") { gets() == 1 }
    settle()
    api.requests.clear()

    store.addBookmark("srv", "li1", 754.0, "Mark")
    SyncEngine.onLocalWrite()

    eventually("the push") { db.outbox().count("srv") == 0 }
    settle()
    assertEquals(listOf("POST /api/me/item/li1/bookmark"), api.paths())
  }

  @Test
  fun foregroundReconcilesOnlyWhenNeeded() = runBlocking {
    foreground = true
    start()
    setState(ServerState.ONLINE)
    eventually("the first reconcile") { gets() == 1 }
    settle()

    now += 5 * 60_000L
    SyncEngine.syncNow(SyncReason.FOREGROUND)
    settle()
    assertEquals("a foreground return alone does not reconcile", 1, gets())

    foreground = false
    SyncEngine.syncNow(SyncReason.MANUAL)
    settle()
    foreground = true
    SyncEngine.syncNow(SyncReason.FOREGROUND)
    settle()
    assertEquals("a deferred reconcile runs on the foreground return", 2, gets())

    foreground = false
    SyncEngine.onSocketLost()
    now += 16 * 60_000L
    foreground = true
    SyncEngine.syncNow(SyncReason.FOREGROUND)
    settle()
    assertEquals("socket lost in the background and the last reconcile is old", 3, gets())

    foreground = false
    SyncEngine.onSocketLost()
    now += 10 * 60_000L
    foreground = true
    SyncEngine.syncNow(SyncReason.FOREGROUND)
    settle()
    assertEquals("socket lost, but the last reconcile is recent", 3, gets())
  }

  @Test
  fun backgroundOnlinePushesWithoutReconcile() = runBlocking {
    start()
    setState(ServerState.ONLINE)
    settle()
    for (reason in listOf(SyncReason.APP_START, SyncReason.ONLINE, SyncReason.SOCKET_INIT, SyncReason.MANUAL)) {
      foreground = false
      api.requests.clear()
      store.addBookmark("srv", "li1", 754.0, "In background")
      SyncEngine.syncNow(reason)
      eventually("$reason pushes") { db.outbox().count("srv") == 0 }
      settle()
      assertEquals("$reason in the background", listOf("POST /api/me/item/li1/bookmark"), api.paths())

      foreground = true
      SyncEngine.syncNow(SyncReason.FOREGROUND)
      settle()
      assertEquals("$reason: the deferred reconcile runs once on return", 1, gets())
      SyncEngine.syncNow(SyncReason.FOREGROUND)
      settle()
      assertEquals(1, gets())

      api.requests.clear()
      now += RECONCILE_MIN_INTERVAL_MS
      SyncEngine.syncNow(reason)
      settle()
      assertEquals("$reason in the foreground reconciles", 1, gets())
      store.removeBookmark("srv", "li1", 754.0)
      SyncEngine.syncNow(SyncReason.LOCAL_WRITE)
      eventually("cleanup") { db.outbox().count("srv") == 0 }
    }
  }

  @Test
  fun reconnectSignalsTogetherReconcileOnce() = runBlocking {
    foreground = true
    start()
    setState(ServerState.ONLINE)
    eventually("the reconnect reconcile") { gets() == 1 }
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    settle()
    assertEquals("the socket init right after ONLINE is covered", 1, gets())

    SyncEngine.syncNow(SyncReason.MANUAL)
    settle()
    assertEquals("Sync now always reconciles", 2, gets())

    now += RECONCILE_MIN_INTERVAL_MS
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    settle()
    assertEquals(3, gets())
  }

  @Test
  fun activityResumeRunsTheForegroundSync() = runBlocking {
    // MainActivity.onResume calls SyncRuntime.setForeground(true)
    SyncRuntime.resetForTest()
    setState(ServerState.ONLINE)
    start()
    try {
      settle()
      assertEquals("started in the background: reconcile deferred", 0, gets())

      SyncRuntime.setForeground(true)

      eventually("the deferred reconcile") { gets() == 1 }
    } finally {
      SyncRuntime.resetForTest()
    }
  }

  @Test
  fun eventOfAnotherServerIsDropped() = runBlocking {
    start()
    val user = """{"bookmarks":[{"libraryItemId":"li1","time":3,"title":"From socket","createdAt":1}]}"""

    assertEquals(false, SyncEngine.applyEvent("user_updated", user, "srvB"))
    assertTrue(db.bookmarks().getAll("srv").isEmpty())
    assertEquals(true, SyncEngine.applyEvent("user_updated", user, "srv"))
    assertEquals(listOf(3.0), db.bookmarks().getVisible("srv").map { it.time })
  }

  @Test
  fun staleUserObjectAfterPushKeepsTheSentBookmark() = runBlocking {
    start()
    store.addBookmark("srv", "li1", 754.0, "Offline")
    setState(ServerState.ONLINE)
    eventually("the push") { db.outbox().count("srv") == 0 }

    // The authorize answer was requested before the POST and arrives after it
    assertEquals(true, SyncEngine.applyEvent("user_updated", """{"bookmarks":[]}""", "srv"))

    assertEquals(listOf(754.0), db.bookmarks().getVisible("srv").map { it.time })
  }

  @Test
  fun failingRunNeitherCrashesNorStopsSyncing() = runBlocking {
    start()
    store.addBookmark("srv", "li1", 754.0, "Mark")
    failNextRun = true
    setState(ServerState.ONLINE)
    eventually("the failure is reported") { SyncEngine.syncState.value.lastError == "database is full" }
    assertTrue(errors.any { it.contains("database is full") })
    assertEquals(1, db.outbox().count("srv"))

    SyncEngine.syncNow(SyncReason.MANUAL)

    eventually("the next run works") { db.outbox().count("srv") == 0 }
  }

  @Test
  fun unreachableWhileOnlineRetriesByItself() = runBlocking {
    start(unreachableRetryMs = listOf(100L))
    store.addBookmark("srv", "li1", 754.0, "Mark")
    // A 401 whose refresh fails transiently: no answer to use, while reachability still says online
    api.handler = { 401 to "Unauthorized" }
    setState(ServerState.ONLINE)
    eventually("the push stops") { SyncEngine.syncState.value.lastError == "unreachable" }
    api.handler = { 200 to "{}" }

    eventually("sent without another trigger") { db.outbox().count("srv") == 0 }
  }

  @Test
  fun failedReconcileDoesNotCoverTheNextTrigger() = runBlocking {
    foreground = true
    start()
    api.handler = { request -> if (request.method == "GET") 500 to "" else 200 to "{}" }
    setState(ServerState.ONLINE)
    eventually("the failed reconcile") { gets() == 1 && SyncEngine.syncState.value.lastError != null }
    settle()
    api.handler = { request -> if (request.method == "GET") 200 to serverBookmarks else 200 to "{}" }

    // The server blinked: it comes back within RECONCILE_MIN_INTERVAL_MS of the failed attempt
    setState(ServerState.OFFLINE)
    settle()
    setState(ServerState.ONLINE)

    eventually("the next automatic trigger reconciles") { gets() == 2 }
    eventually("and its bookmarks arrive") { db.bookmarks().getVisible("srv").size == 2 }
  }

  @Test
  fun heldBackSnapshotIsFollowedByAFreshPull() = runBlocking {
    foreground = true
    start(recentlySettledMs = 300L)
    store.addBookmark("srv", "li1", 754.0, "Offline")
    // Another device deletes the bookmark right after it reaches the server: every list from the server is empty
    api.handler = { request -> if (request.method == "GET") 200 to """{"mediaProgress":[],"bookmarks":[]}""" else 200 to "{}" }
    setState(ServerState.ONLINE)
    eventually("pull and push") { gets() == 1 && db.outbox().count("srv") == 0 }

    SyncEngine.applyEvent("user_updated", """{"bookmarks":[]}""", "srv")
    assertEquals("held back by the settle window", 1, db.bookmarks().getVisible("srv").size)

    eventually("a pull after the window") { gets() == 2 }
    eventually("applies the deletion") { db.bookmarks().getVisible("srv").isEmpty() }
  }

  @Test
  fun heldBackSnapshotInTheBackgroundWaitsForTheForeground() = runBlocking {
    start(recentlySettledMs = 100L)
    store.addBookmark("srv", "li1", 754.0, "Offline")
    api.handler = { request -> if (request.method == "GET") 200 to """{"mediaProgress":[],"bookmarks":[]}""" else 200 to "{}" }
    setState(ServerState.ONLINE)
    eventually("push") { db.outbox().count("srv") == 0 }

    SyncEngine.applyEvent("user_updated", """{"bookmarks":[]}""", "srv")
    delay(300)
    settle()
    assertEquals("no pull in the background", 0, gets())

    foreground = true
    SyncEngine.syncNow(SyncReason.FOREGROUND)
    eventually("the deferred pull") { gets() == 1 && db.bookmarks().getVisible("srv").isEmpty() }
  }

  @Test
  fun offlineMarkFinishedIsSentOnReconnect() = runBlocking {
    start()
    db.mediaProgress().upsert(progressRow(400.0, lastUpdate = 5L, pending = false))
    store.markFinished("srv", "li1", null, true)
    SyncEngine.onLocalWrite()
    settle()
    assertTrue("nothing is sent while the server is unreachable", api.requests.isEmpty())
    eventually("pending count 1") { SyncEngine.syncState.value.pendingCount == 1 }

    setState(ServerState.ONLINE)

    eventually("the queued action is sent") { db.outbox().count("srv") == 0 }
    settle()
    assertEquals(listOf("PATCH /api/me/progress/li1"), api.paths())
    val body = org.json.JSONObject(api.requests.single().body!!)
    assertTrue(body.getBoolean("isFinished"))
    assertEquals(0, SyncEngine.syncState.value.pendingCount)
    val row = db.mediaProgress().get("srv", "li1", "")!!
    assertTrue(row.isFinished)
    assertTrue(!row.pending)
  }

  @Test
  fun reconcileThenPushOrder() = runBlocking {
    foreground = true
    start()
    store.markFinished("srv", "li1", null, true)

    setState(ServerState.ONLINE)

    eventually("pull and push") { db.outbox().count("srv") == 0 && api.requests.size == 3 }
    assertEquals(listOf("GET /api/me/progress", "GET /api/me/bookmarks", "PATCH /api/me/progress/li1"), api.paths())
  }

  @Test
  fun progressEventOfAnotherServerIsDropped() = runBlocking {
    start()
    val event = """{"data":{"id":"mp1","libraryItemId":"li1","currentTime":120,"duration":1000,"progress":0.12,"lastUpdate":50}}"""

    assertEquals(false, SyncEngine.applyEvent("user_item_progress_updated", event, "srvB"))
    assertTrue(db.mediaProgress().getAll("srv").isEmpty())
    assertEquals(true, SyncEngine.applyEvent("user_item_progress_updated", event, "srv"))
    assertEquals(120.0, db.mediaProgress().get("srv", "li1", "")!!.currentTime, 0.0)
    assertEquals("an older value is not applied", false, SyncEngine.applyEvent("user_item_progress_updated", event, "srv"))
  }

  @Test
  fun queuedWritesRunInTheirOrder() = runBlocking {
    start()
    val order = java.util.Collections.synchronizedList(mutableListOf<String>())
    // A slow first write (a tick waiting for the database) must not be overtaken by the next one
    SyncEngine.launchWrite { delay(100); order.add("earlier") }
    SyncEngine.launchWrite { order.add("later") }

    eventually("both writes ran") { order.size == 2 }
    assertEquals(listOf("earlier", "later"), order)
  }

  @Test
  fun quickReconnectStillPushes() = runBlocking {
    start()
    setState(ServerState.ONLINE)
    settle()
    // Queued without a push of its own, as by a write whose push found the server gone
    store.addBookmark("srv", "li1", 754.0, "During the blip")
    // Down and back before the engine looked: the state alone reads ONLINE as it did before
    setState(ServerState.OFFLINE)
    setState(ServerState.ONLINE)

    eventually("the bookmark is pushed") { db.outbox().count("srv") == 0 }
    assertEquals(listOf("POST /api/me/item/li1/bookmark"), api.paths())
  }

  @Test
  fun userWriteLandsAfterTheTicksQueuedBeforeIt() = runBlocking {
    start()
    val order = java.util.Collections.synchronizedList(mutableListOf<String>())
    SyncEngine.launchWrite { delay(100); order.add("tick") }
    val answer = SyncEngine.write { order.add("finished"); 42 }

    assertEquals(42, answer)
    assertEquals(listOf("tick", "finished"), order)
  }
}
