package app.absplus.android.sync

import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.inMemoryReplica
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The catalog pass inside [SyncEngine]: when it runs, what runs beside it, and events that arrive during it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEngineCatalogTest {
  private val db = inMemoryUserData()
  private val replica = inMemoryReplica()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val server = FakeCatalogServer().library("lib1")
  private val api = FakeApi { request ->
    when {
      request.path.startsWith("/api/libraries") -> server.handle(request)
      request.method == "GET" && request.path == "/api/me/progress" -> 200 to """{"mediaProgress":[]}"""
      request.method == "GET" && request.path == "/api/me/bookmarks" -> 200 to """{"bookmarks":[]}"""
      else -> 200 to "{}"
    }
  }
  private var active: ServerConnectionConfig? = fakeServer("srv")
  private val state = MutableStateFlow(ServerState.OFFLINE)
  private val onlineEntries = MutableStateFlow(0L)
  private val serverId = MutableStateFlow<String?>("srv")
  @Volatile private var foreground = true
  @Volatile private var now = 100_000_000L
  @Volatile private var beforeEventWrite: suspend () -> Unit = {}
  private val store get() = SyncEngine.store!!

  /** Holds the items page [page] of the [pass]-th pass after the gate is set (from 0) until released. */
  private inner class Gate(val pass: Int = 0, val page: Int = 0) {
    val arrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val passes = AtomicInteger()

    init {
      server.onItemsPage = { p ->
        if (p.filter == null && p.page == 0) passes.incrementAndGet()
        if (p.filter == null && p.page == page && passes.get() - 1 == pass) {
          arrived.countDown()
          release.await(5, TimeUnit.SECONDS)
        }
      }
    }

    fun awaitArrived() = assertTrue("the pass reached the held page", arrived.await(5, TimeUnit.SECONDS))
  }

  private suspend fun start() {
    SyncEngine.initForTest(SyncDeps(
            db = db,
            http = syncHttp(api),
            activeServer = { active },
            serverState = state,
            onlineEntries = onlineEntries,
            serverId = serverId,
            isForeground = { foreground },
            clock = { now },
            scope = scope,
            log = {},
            notifyWindowMs = 10,
            catalog = CatalogDeps(replica, currentLibraryId = { "lib1" }, beforeEventWrite = { beforeEventWrite() })))
    settle()
  }

  @After
  fun tearDown() {
    SyncEngine.resetForTest()
    scope.cancel()
    db.close()
    replica.close()
  }

  private fun setState(next: ServerState) {
    val was = state.value
    state.value = next
    if (next == ServerState.ONLINE && was != ServerState.ONLINE) onlineEntries.value++
  }

  private suspend fun settle() {
    repeat(3) {
      delay(30)
      SyncEngine.awaitIdleForTest()
    }
  }

  private fun passes() = api.requests.count { it.path == "/api/libraries" }

  private suspend fun awaitPasses(count: Int) {
    eventually("$count catalog passes") { passes() >= count && !SyncEngine.syncState.value.catalogRunning }
    settle()
    delay(100)
    assertEquals(count, passes())
  }

  private suspend fun title(id: String) = replica.items().get("srv", id)?.title

  /** An online start whose first pass has finished, with the clock moved past the reconcile interval. */
  private suspend fun startOnline() {
    start()
    setState(ServerState.ONLINE)
    awaitPasses(1)
  }

  private fun later() {
    now += RECONCILE_MIN_INTERVAL_MS + 1
  }

  @Test
  fun catalogRunsAfterUserReconcileAndPush() = runBlocking {
    server.book("li1")
    start()
    store.addBookmark("srv", "li1", 10.0, "Mark")
    setState(ServerState.ONLINE)
    awaitPasses(1)
    assertEquals(listOf("GET /api/me/progress", "GET /api/me/bookmarks", "POST /api/me/item/li1/bookmark", "GET /api/libraries"),
            api.paths().take(4))
    assertEquals("Title li1", title("li1"))
  }

  @Test
  fun catalogPassDoesNotBlockUserEvents() = runBlocking {
    server.book("li1")
    val gate = Gate()
    start()
    setState(ServerState.ONLINE)
    gate.awaitArrived()

    val event = """{"data":{"id":"mp1","libraryItemId":"li1","currentTime":120,"duration":1000,"progress":0.12,"lastUpdate":50}}"""
    val applied = withTimeout(3_000) { SyncEngine.applyEvent("user_item_progress_updated", event, "srv") }

    assertTrue(applied)
    assertTrue(SyncEngine.syncState.value.catalogRunning)
    gate.release.countDown()
    awaitPasses(1)
  }

  @Test
  fun eventDuringPassIsAppliedAfterIt() = runBlocking {
    server.book("li1", title = "Old")
    server.book("li2")
    start()
    server.onItemsPage = { page ->
      if (page.page == 0 && page.filter == null) runBlocking {
        val newer = FakeBook("li1", "lib1", "New", updatedAt = 2)
        assertTrue(SyncEngine.applyEvent("item_updated", server.itemJson(newer).toString(), "srv"))
        assertTrue(SyncEngine.applyEvent("item_removed", """{"id":"li2","libraryId":"lib1"}""", "srv"))
      }
    }
    setState(ServerState.ONLINE)
    awaitPasses(1)

    assertEquals("New", title("li1"))
    assertNull("a removed item does not come back with the page read before the event", title("li2"))
  }

  @Test
  fun backgroundDefersCatalog() = runBlocking {
    server.book("li1")
    foreground = false
    start()
    setState(ServerState.ONLINE)
    settle()
    delay(100)
    assertEquals(0, passes())

    foreground = true
    SyncEngine.syncNow(SyncReason.FOREGROUND)
    awaitPasses(1)
  }

  @Test
  fun localWriteNeverTouchesCatalog() = runBlocking {
    server.book("li1")
    startOnline()
    later()
    store.addBookmark("srv", "li1", 10.0, "Mark")
    SyncEngine.onLocalWrite()
    eventually("pushed") { db.outbox().count("srv") == 0 }
    awaitPasses(1)
  }

  @Test
  fun requestDuringPassRunsOneMore() = runBlocking {
    server.book("li1")
    val gate = Gate()
    start()
    setState(ServerState.ONLINE)
    gate.awaitArrived()
    SyncEngine.syncNow(SyncReason.MANUAL)
    SyncEngine.syncNow(SyncReason.MANUAL)
    gate.release.countDown()
    awaitPasses(2)
  }

  @Test
  fun socketInitDuringPassDoesNotRepeat() = runBlocking {
    server.book("li1")
    val gate = Gate()
    start()
    setState(ServerState.ONLINE)
    gate.awaitArrived()
    later()
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    settle()
    gate.release.countDown()
    awaitPasses(1)
  }

  @Test
  fun socketReconnectDuringPassRunsOneMore() = runBlocking {
    // Dropped during the pass
    val book = server.book("li1", title = "Old")
    var gate = Gate()
    start()
    setState(ServerState.ONLINE)
    gate.awaitArrived()
    SyncEngine.onSocketLost()
    book.title = "New"
    book.updatedAt = 2
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    gate.release.countDown()
    awaitPasses(2)
    assertEquals("New", title("li1"))

    // Dropped before the pass that ONLINE started
    later()
    gate = Gate()
    SyncEngine.onSocketLost()
    SyncEngine.syncNow(SyncReason.ONLINE)
    gate.awaitArrived()
    book.title = "Newer"
    book.updatedAt = 3
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    gate.release.countDown()
    awaitPasses(4)
    assertEquals("Newer", title("li1"))
  }

  @Test
  fun socketReconnectAfterPassRunsAnother() = runBlocking {
    val book = server.book("li1", title = "v0")
    start()

    // Dropped before the ONLINE that started the pass
    SyncEngine.onSocketLost()
    setState(ServerState.ONLINE)
    awaitPasses(1)
    book.title = "v1"
    book.updatedAt = 2
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    awaitPasses(2)
    assertEquals("v1", title("li1"))

    // Dropped during a pass that then finished before the reconnect
    val gate = Gate()
    SyncEngine.syncNow(SyncReason.MANUAL)
    gate.awaitArrived()
    SyncEngine.onSocketLost()
    gate.release.countDown()
    awaitPasses(3)
    book.title = "v2"
    book.updatedAt = 3
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    awaitPasses(4)
    assertEquals("v2", title("li1"))

    // Dropped after the pass
    SyncEngine.onSocketLost()
    book.title = "v3"
    book.updatedAt = 4
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    awaitPasses(5)
    assertEquals("v3", title("li1"))

    // A reconnect without a drop within the interval does nothing
    book.title = "v4"
    book.updatedAt = 5
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    awaitPasses(5)
    assertEquals("v3", title("li1"))
  }

  @Test
  fun concurrentEventsForOneItemKeepTheNewer() = runBlocking {
    server.book("li1")
    startOnline()
    val firstWrite = CountDownLatch(1)
    val calls = AtomicInteger()
    beforeEventWrite = {
      if (calls.getAndIncrement() == 0) {
        firstWrite.countDown()
        delay(300)
      }
    }
    val older = async(Dispatchers.IO) {
      SyncEngine.applyEvent("item_updated", server.itemJson(FakeBook("li1", "lib1", "Older", updatedAt = 100)).toString(), "srv")
    }
    assertTrue(firstWrite.await(5, TimeUnit.SECONDS))
    val newer = async(Dispatchers.IO) {
      SyncEngine.applyEvent("item_updated", server.itemJson(FakeBook("li1", "lib1", "Newer", updatedAt = 200)).toString(), "srv")
    }
    older.await()
    newer.await()

    assertEquals(200L, replica.items().get("srv", "li1")!!.updatedAt)
    assertEquals("Newer", title("li1"))
  }

  @Test
  fun serverSwitchCancelsCatalogPass() = runBlocking {
    repeat(700) { server.book("b$it") }
    val gate = Gate(page = 1)
    start()
    setState(ServerState.ONLINE)
    gate.awaitArrived()

    active = fakeServer("other")
    serverId.value = "other"
    eventually("the pass is cancelled") { !SyncEngine.syncState.value.catalogRunning }
    gate.release.countDown()
    settle()
    delay(200)

    assertEquals(500, replica.items().ids("srv", "lib1").size)
    assertFalse(SyncEngine.syncState.value.catalogRunning)
  }

  @Test
  fun backupAppliedTriggersReconcile() = runBlocking {
    server.book("li1")
    startOnline()
    assertTrue(SyncEngine.applyEvent("backup_applied", "{}", "srv"))
    awaitPasses(2)
  }

  @Test
  fun syncStateReportsCatalogRunning() = runBlocking {
    server.book("li1")
    val gate = Gate()
    start()
    setState(ServerState.ONLINE)
    gate.awaitArrived()
    assertTrue(SyncEngine.syncState.value.catalogRunning)
    gate.release.countDown()
    awaitPasses(1)
    assertFalse(SyncEngine.syncState.value.catalogRunning)
  }

  @Test
  fun pushDuringPassKeepsCatalogRunning() = runBlocking {
    server.book("li1")
    val gate = Gate()
    start()
    setState(ServerState.ONLINE)
    gate.awaitArrived()

    store.addBookmark("srv", "li1", 10.0, "Mark")
    SyncEngine.onLocalWrite()
    eventually("pushed") { db.outbox().count("srv") == 0 && !SyncEngine.syncState.value.running }
    settle()

    assertTrue(SyncEngine.syncState.value.catalogRunning)
    gate.release.countDown()
    awaitPasses(1)
  }

  @Test
  fun catalogEventOfAnotherServerIsDropped() = runBlocking {
    server.book("li1")
    startOnline()
    val payload = server.itemJson(FakeBook("li1", "lib1", "Elsewhere", updatedAt = 9)).toString()
    assertFalse(SyncEngine.applyEvent("item_updated", payload, "srvB"))
    assertEquals("Title li1", title("li1"))
  }
}
