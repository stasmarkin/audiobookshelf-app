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
  /** What `GET /api/items/{id}` answers, by id; a missing id is a 404. */
  private val itemAnswers = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()
  private val api = FakeApi { request ->
    when {
      request.path.startsWith("/api/libraries") -> server.handle(request)
      request.path.startsWith("/api/items/") -> itemAnswers[request.path.removePrefix("/api/items/")] ?: (404 to "Not found")
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
  @Volatile private var currentLibraryId: () -> String? = { "lib1" }
  /** What the user's requests told about the server; catalog requests have their own SyncHttp. */
  private val userReports = java.util.Collections.synchronizedList(mutableListOf<String>())
  private val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
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
            http = SyncHttp(api.client, { _, onResult -> onResult(app.absplus.android.server.RefreshResult.Transient) }, { "token" }, object : ReachabilitySink {
              override fun reportResponse(address: String, code: Int) {}
              override fun reportUnreachable(address: String) {
                userReports.add("unreachable")
              }
            }),
            activeServer = { active },
            serverState = state,
            onlineEntries = onlineEntries,
            serverId = serverId,
            isForeground = { foreground },
            clock = { now },
            scope = scope,
            log = {},
            logError = { errors.add(it) },
            notifyWindowMs = 10,
            catalog = CatalogDeps(replica, currentLibraryId = { currentLibraryId() }, http = syncHttp(api), minIntervalMs = CATALOG_INTERVAL_MS,
                    beforeEventWrite = { beforeEventWrite() })))
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
    startOnline()
    val applied = mutableListOf<Boolean>()
    // The server answers the page as it was before the events (the old title, li2 still there)
    server.onItemsPage = { page ->
      if (page.page == 0 && page.filter == null && applied.isEmpty()) runBlocking {
        val newer = FakeBook("li1", "lib1", "New", updatedAt = 2)
        applied += SyncEngine.applyEvent("item_updated", server.itemJson(newer).toString(), "srv")
        applied += SyncEngine.applyEvent("item_removed", """{"id":"li2","libraryId":"lib1"}""", "srv")
      }
    }
    SyncEngine.syncNow(SyncReason.MANUAL)
    awaitPasses(2)

    assertEquals(listOf(true, true), applied)
    assertTrue(replica.libraryState().get("srv", "lib1")!!.itemsComplete)
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

  @Test
  fun reconnectStormPassesOncePerInterval() = runBlocking {
    server.book("li1")
    startOnline()
    repeat(10) {
      SyncEngine.onSocketLost()
      SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    }
    awaitPasses(2)
    delay(CATALOG_INTERVAL_MS * 2)
    assertEquals("one more pass for the whole storm", 2, passes())

    // A later drop after the interval passes again
    SyncEngine.onSocketLost()
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    awaitPasses(3)
  }

  @Test
  fun delayedReconnectPassIsDroppedInTheBackground() = runBlocking {
    server.book("li1")
    startOnline()
    SyncEngine.onSocketLost()
    SyncEngine.syncNow(SyncReason.SOCKET_INIT)
    foreground = false
    delay(CATALOG_INTERVAL_MS * 2)
    assertEquals(1, passes())
  }

  @Test
  fun catalogTimeoutDoesNotMarkServerUnreachable() = runBlocking {
    repeat(700) { server.book("b$it") }
    replica.items().upsertAll(listOf(app.absplus.android.db.itemEntity("srv", "kept")))
    server.onItemsPage = { if (it.page == 1) throw java.io.IOException("timeout") }
    start()
    setState(ServerState.ONLINE)
    eventually("the pass stopped") { errors.any { it.startsWith("Catalog srv stopped") } }
    settle()

    assertTrue("the user's transport heard nothing", userReports.isEmpty())
    assertTrue("nothing is deleted", replica.items().get("srv", "kept") != null)
    assertEquals(500, replica.items().ids("srv", "lib1").count { it.startsWith("b") })
  }

  @Test
  fun failedPassStillEndsBuffering() = runBlocking {
    server.book("li1")
    replica.libraries().upsertAll(listOf(app.absplus.android.db.libraryRow("srv", "lib1")))
    currentLibraryId = { throw IllegalStateException("broken preferences") }
    start()
    setState(ServerState.ONLINE)
    eventually("the pass failed") { errors.any { it.startsWith("Catalog srv failed") } }
    settle()

    val payload = server.itemJson(FakeBook("li9", "lib1", "Applied at once", updatedAt = 5)).toString()
    assertTrue(SyncEngine.applyEvent("item_added", payload, "srv"))
    assertEquals("Applied at once", title("li9"))
  }

  @Test
  fun reconnectsDuringPassesKeepTheInterval() = runBlocking {
    server.book("li1")
    val starts = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val handler = api.handler
    api.handler = { r ->
      if (r.path == "/api/libraries") {
        starts.add(System.nanoTime() / 1_000_000)
        // Every pass sees a reconnect after a drop while it runs
        SyncEngine.onSocketLost()
        SyncEngine.syncNow(SyncReason.SOCKET_INIT)
      }
      handler(r)
    }
    start()
    setState(ServerState.ONLINE)
    eventually("three passes") { starts.size >= 3 }
    val gaps = starts.zipWithNext { a, b -> b - a }
    assertTrue("passes $gaps apart, interval $CATALOG_INTERVAL_MS", gaps.all { it >= CATALOG_INTERVAL_MS - 20 })
  }

  /** `GET /api/items/:id?expanded=1` of a book of lib1 (fixture recorded from 2.37.1) with a chapter. */
  private fun serveExpanded(id: String, updatedAt: Long) {
    val node = CatalogJson.mapper.readTree(javaClass.getResource("/catalog/book-expanded.json")) as com.fasterxml.jackson.databind.node.ObjectNode
    node.put("id", id).put("libraryId", "lib1").put("updatedAt", updatedAt)
    (node.path("media") as com.fasterxml.jackson.databind.node.ObjectNode).putArray("chapters").addObject().put("id", 0).put("start", 0).put("end", 10).put("title", "One")
    itemAnswers[id] = 200 to CatalogJson.mapper.writeValueAsString(node)
  }

  private suspend fun detailLevel(id: String) = replica.items().get("srv", id)?.detailLevel

  @Test
  fun openDuringACatalogPassIsWrittenAfterIt() = runBlocking {
    server.book("li1")
    startOnline()
    serveExpanded("li1", updatedAt = 1)
    val gate = Gate()
    SyncEngine.syncNow(SyncReason.MANUAL)
    gate.awaitArrived()

    val outcome = SyncEngine.openItem("srv", "li1")!!
    assertEquals(OpenResult.UPDATED, outcome.result)
    assertEquals("li1", CatalogJson.mapper.readTree(outcome.serverItem).path("id").asText())
    assertEquals("buffered until the pass ends", app.absplus.android.db.replica.DetailLevel.MINIFIED, detailLevel("li1"))

    gate.release.countDown()
    awaitPasses(2)
    assertEquals(app.absplus.android.db.replica.DetailLevel.EXPANDED, detailLevel("li1"))
  }

  @Test
  fun openForAServerNoLongerActiveIsSkipped() = runBlocking {
    server.book("li1")
    startOnline()
    serveExpanded("li1", updatedAt = 1)

    assertEquals(OpenOutcome(OpenResult.SKIPPED), SyncEngine.openItem("previous", "li1"))
    assertTrue(api.requests.none { it.path.startsWith("/api/items/") })
    assertEquals(OpenResult.UPDATED, SyncEngine.openItem("srv", "li1")!!.result)
  }

  @Test
  fun notFoundDuringACatalogPassIsRemovedAfterIt() = runBlocking {
    server.book("li1")
    startOnline()
    val gate = Gate()
    SyncEngine.syncNow(SyncReason.MANUAL)
    gate.awaitArrived()

    assertEquals(OpenOutcome(OpenResult.REMOVED), SyncEngine.openItem("srv", "li1"))
    gate.release.countDown()
    awaitPasses(2)
    assertNull(title("li1"))
  }

  @Test
  fun olderAnswerDuringACatalogPassIsNotReturned() = runBlocking {
    server.book("li1").updatedAt = 200
    startOnline()
    serveExpanded("li1", updatedAt = 100)
    val gate = Gate()
    SyncEngine.syncNow(SyncReason.MANUAL)
    gate.awaitArrived()

    assertEquals(OpenOutcome(OpenResult.UP_TO_DATE), SyncEngine.openItem("srv", "li1"))
    gate.release.countDown()
    awaitPasses(2)
    assertEquals(200L, replica.items().get("srv", "li1")!!.updatedAt)
    assertEquals(app.absplus.android.db.replica.DetailLevel.MINIFIED, detailLevel("li1"))
  }

  private companion object {
    const val CATALOG_INTERVAL_MS = 300L
  }
}
