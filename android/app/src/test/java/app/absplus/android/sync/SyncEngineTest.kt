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
  private val serverId = MutableStateFlow<String?>("srv")
  @Volatile private var foreground = false
  @Volatile private var now = 100_000_000L
  private var refresh: RefreshResult = RefreshResult.Transient
  private val store get() = SyncEngine.store!!

  private val serverBookmarks = """{"bookmarks":[{"libraryItemId":"li7","time":1,"title":"A","createdAt":1},
    {"libraryItemId":"li8","time":2,"title":"B","createdAt":1}]}"""

  /** Starts the engine and lets its APP_START run finish, so later state changes are separate requests. */
  private suspend fun start() {
    api.handler = { request -> if (request.method == "GET") 200 to serverBookmarks else 200 to "{}" }
    SyncEngine.initForTest(SyncDeps(
            db = db,
            http = SyncHttp(api.client, { _, onResult -> onResult(refresh) }, { "token" }, NoopSink()),
            activeServer = { active },
            serverState = state,
            serverId = serverId,
            isForeground = { foreground },
            clock = { now },
            scope = scope,
            log = {},
            notifyWindowMs = 10))
    settle()
  }

  @After
  fun tearDown() {
    SyncEngine.resetForTest()
    scope.cancel()
    db.close()
  }

  /** Lets queued engine work start and finish. */
  private suspend fun settle() {
    repeat(3) {
      delay(30)
      SyncEngine.awaitIdleForTest()
    }
  }

  private fun gets() = api.paths().count { it.startsWith("GET") }

  @Test
  fun offlineThenReconnectSendsQueuedBookmark() = runBlocking {
    start()
    store.addBookmark("srv", "li1", 754.0, "Offline")
    SyncEngine.onLocalWrite()
    settle()
    assertTrue("nothing is sent while the server is unreachable", api.requests.isEmpty())
    eventually("pending count 1") { SyncEngine.syncState.value.pendingCount == 1 }

    state.value = ServerState.ONLINE

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

    state.value = ServerState.ONLINE

    eventually("pull and push") { api.requests.size == 2 && db.outbox().count("srv") == 0 }
    assertEquals(listOf("GET /api/me/bookmarks", "POST /api/me/item/li1/bookmark"), api.paths())
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
    state.value = ServerState.ONLINE
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
    state.value = ServerState.ONLINE
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

    state.value = ServerState.ONLINE
    eventually("the push stops") { SyncEngine.syncState.value.lastError == "unauthorized" }
    assertEquals(1, db.outbox().count("srv"))
    assertEquals(1, db.bookmarks().getVisible("srv").size)

    // ServerReachability goes UNAUTHORIZED on the rejected refresh, and ONLINE again on reportSignedIn
    state.value = ServerState.UNAUTHORIZED
    settle()
    api.handler = { 200 to "{}" }
    state.value = ServerState.ONLINE

    eventually("the bookmark is sent after signing in") { db.outbox().count("srv") == 0 }
    assertEquals(2, api.requests.count { it.method == "POST" })
  }

  @Test
  fun localWriteOnlyPushes() = runBlocking {
    foreground = true
    start()
    state.value = ServerState.ONLINE
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
    state.value = ServerState.ONLINE
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
    state.value = ServerState.ONLINE
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
      SyncEngine.syncNow(reason)
      settle()
      assertEquals("$reason in the foreground reconciles", 1, gets())
      store.removeBookmark("srv", "li1", 754.0)
      SyncEngine.syncNow(SyncReason.LOCAL_WRITE)
      eventually("cleanup") { db.outbox().count("srv") == 0 }
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
}
