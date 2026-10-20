package app.absplus.android.media

import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.sync.ServerState
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverCacheTest {
  @get:Rule val tmp = TemporaryFolder()

  private val server = ServerConnectionConfig("srv", 0, "Home", "https://abs.example/", "2.20.0", "u1", "user", "tok", mapOf("X-Proxy" to "p"))
  @Volatile private var state = ServerState.ONLINE
  @Volatile private var active: ServerConnectionConfig? = server
  private var replica: Map<String, Long> = emptyMap()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val fetcher = FakeFetcher()
  private val store by lazy { CoverStore(File(tmp.root, COVER_CACHE_DIR)) }
  private val announced = CopyOnWriteArrayList<String>()

  private val errors = CopyOnWriteArrayList<String>()

  private fun cache(fetch: CoverFetcher = fetcher, workers: Int = COVER_FETCH_PARALLELISM, queueMax: Int = COVER_QUEUE_MAX) =
    CoverCache(store, fetch, { active }, { state }, { _, id -> replica[id] }, scope, workers = workers, queueMax = queueMax,
      logError = { errors += it }).also { it.onCoverReady = { id -> announced += id } }

  private fun seed(itemId: String, updatedAt: Long) {
    store.put(itemId, updatedAt, "jpg") { it.write(byteArrayOf(1, 2, 3)) }!!
  }

  private fun files(): Set<String> = File(tmp.root, COVER_CACHE_DIR).list()?.toSet() ?: emptySet()

  private fun CoverCache.idle() = runBlocking { withTimeout(5_000) { awaitIdleForTest() } }

  @After fun stop() = scope.cancel()

  private class FakeFetcher : CoverFetcher {
    val calls = CopyOnWriteArrayList<Pair<String, Boolean>>()
    @Volatile var answer: (String, Boolean) -> FetchedCover? = { _, _ -> image() }

    override fun fetch(config: ServerConnectionConfig, itemId: String, raw: Boolean): FetchedCover? {
      calls += itemId to raw
      return answer(itemId, raw)
    }

    fun callsFor(itemId: String) = calls.count { it.first == itemId }
  }

  @Test
  fun offlineMissDoesNotFetch() {
    state = ServerState.OFFLINE
    val cache = cache()

    assertNull(cache.standard("A", 2))
    cache.idle()
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun offlineServesOlderVersion() {
    seed("A", 1)
    state = ServerState.OFFLINE

    assertEquals(1L, cache().standard("A", 2)!!.updatedAt)
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun olderRequestedVersionServesTheNewestWithoutFetching() {
    seed("A", 5)
    val cache = cache()

    assertEquals(5L, cache.standard("A", 2)!!.updatedAt)
    cache.idle()
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun aMissIsFetchedInTheBackground() {
    val cache = cache()

    assertNull("the answer does not wait for the network", cache.standard("A", 2))
    cache.idle()

    assertEquals(setOf("A_2.jpg"), files())
    assertEquals(2L, cache.standard("A", 2)!!.updatedAt)
  }

  @Test
  fun anOlderVersionIsServedWhileTheNewOneIsFetched() {
    seed("A", 1)
    fetcher.answer = { _, _ -> null }
    val cache = cache()

    assertEquals(1L, cache.standard("A", 2)!!.updatedAt)
    cache.idle()
    assertEquals("a failed fetch keeps the older version", setOf("A_1.jpg"), files())
  }

  @Test
  fun readyCoverIsAnnounced() {
    val cache = cache()

    cache.standard("A", 2)
    cache.idle()
    assertEquals("the refused cover is announced once", listOf("A"), announced.toList())

    cache.standard("A", 2)
    cache.idle()
    assertEquals("a hit is not announced", listOf("A"), announced.toList())
  }

  @Test
  fun aVersionCheckThatFindsNothingNewerIsSilent() {
    seed("B", 3)
    replica = mapOf("B" to 3L)
    val cache = cache()

    assertEquals(3L, cache.standard("B", null)!!.updatedAt)
    cache.idle()
    assertEquals(0, fetcher.calls.size)
    assertEquals(emptyList<String>(), announced.toList())
  }

  @Test
  fun repeatedRequestsFetchOnce() {
    val release = CountDownLatch(1)
    fetcher.answer = { _, _ -> release.await(5, TimeUnit.SECONDS); image() }
    val cache = cache()

    repeat(5) { cache.standard("A", 2) }
    waitUntil { fetcher.calls.size == 1 }
    repeat(5) { cache.standard("A", 2) }
    release.countDown()
    cache.idle()

    assertEquals("one network request", 1, fetcher.calls.size)
    assertEquals(listOf("A"), announced.toList())
  }

  @Test
  fun newestRequestsGoFirst() {
    val release = CountDownLatch(1)
    fetcher.answer = { id, _ -> if (id == "H") release.await(5, TimeUnit.SECONDS); image() }
    val cache = cache(workers = 1)

    cache.standard("H", 1)
    waitUntil { fetcher.calls.size == 1 }
    for (id in listOf("A", "B", "C")) cache.standard(id, 1)
    cache.standard("A", 1) // asked again: A moves to the front
    release.countDown()
    cache.idle()

    assertEquals(listOf("H", "A", "C", "B"), fetcher.calls.map { it.first })
  }

  @Test
  fun aFullQueueShedsTheOldestRequest() {
    val release = CountDownLatch(1)
    fetcher.answer = { id, _ -> if (id == "H") release.await(5, TimeUnit.SECONDS); image() }
    val cache = cache(workers = 1, queueMax = 2)

    cache.standard("H", 1)
    waitUntil { fetcher.calls.size == 1 }
    for (id in listOf("A", "B", "C")) cache.standard(id, 1)
    release.countDown()
    cache.idle()

    assertEquals("the request asked for longest ago is dropped", listOf("H", "C", "B"), fetcher.calls.map { it.first })
  }

  @Test
  fun aVersionedFetchIsAnnouncedAfterALatestCheckFoundNothingNewer() {
    val release = CountDownLatch(1)
    fetcher.answer = { id, _ -> if (id == "H") release.await(5, TimeUnit.SECONDS); image() }
    seed("B", 3)
    replica = mapOf("B" to 3L)
    val cache = cache(workers = 1)

    cache.standard("H", 1)
    waitUntil { fetcher.calls.size == 1 }
    cache.standard("B", 7)
    cache.standard("B", null) // newest first: this check runs before the versioned fetch and finds nothing newer
    release.countDown()
    cache.idle()

    assertEquals(setOf("B_7.jpg", "H_1.jpg"), files())
    assertEquals(listOf("H", "B"), announced.toList())
  }

  @Test
  fun rawSeedingTheStandardCoverIsNotAnnounced() = runBlocking {
    val cache = cache()
    cache.raw("A", 2)
    cache.idle()

    assertEquals(setOf("A_2.jpg"), files())
    assertEquals(emptyList<String>(), announced.toList())
  }

  @Test
  fun oneItemWithAndWithoutVersionIsFetchedOnce() {
    val release = CountDownLatch(1)
    fetcher.answer = { _, _ -> release.await(5, TimeUnit.SECONDS); FetchedCover("image/jpeg", "jpg", ByteArrayInputStream(ByteArray(4096) { 7 }), 4096) }
    replica = mapOf("A" to 7L)
    val cache = cache()

    cache.standard("A", 7)
    cache.standard("A", null)
    waitUntil { fetcher.calls.size == 1 }
    Thread.sleep(100)
    release.countDown()
    cache.idle()

    assertEquals("both resolve to version 7: one fetch", 1, fetcher.calls.size)
    assertEquals(4096L, File(tmp.root, "$COVER_CACHE_DIR/A_7.jpg").length())
    assertEquals(setOf("A_7.jpg"), files())
  }

  @Test
  fun aWorkerSurvivesAFailingJob() {
    fetcher.answer = { id, _ -> if (id == "X") throw AssertionError("boom") else image() }
    val cache = cache(workers = 1)

    cache.standard("X", 1)
    cache.idle()
    cache.standard("A", 1)
    cache.idle()

    assertEquals(setOf("A_1.jpg"), files())
    assertEquals(listOf("Cover fetch failed: AssertionError"), errors.toList())
  }

  @Test
  fun offlineWhileQueuedDoesNotFetch() {
    val release = CountDownLatch(1)
    fetcher.answer = { id, _ -> if (id == "H") release.await(5, TimeUnit.SECONDS); image() }
    val cache = cache(workers = 1)

    cache.standard("H", 1)
    waitUntil { fetcher.calls.size == 1 }
    cache.standard("F", 1)
    state = ServerState.OFFLINE
    release.countDown()
    cache.idle()

    assertEquals(0, fetcher.callsFor("F"))
  }

  private fun fakeServer(type: () -> String, seen: MutableList<Request> = mutableListOf()) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
    seen += chain.request()
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fake")
      .body(byteArrayOf(1, 2, 3, 4).toResponseBody(type().toMediaType())).build()
  }).build()

  @Test
  fun nonImageAnswerIsNotStored() {
    val seen = CopyOnWriteArrayList<Request>()
    var type = "text/html"
    val logged = CopyOnWriteArrayList<String>()
    val cache = cache(OkHttpCoverFetcher(fakeServer({ type }, seen), log = { logged += it }))

    cache.standard("A", 2)
    cache.idle()
    cache.standard("A", 2)
    cache.idle()
    assertEquals("a portal page is not a cover", emptySet<String>(), files())
    assertEquals("a refusal is logged once, without the URL or token", listOf("Cover not available: type text/html"), logged.toList())

    type = "image/webp"
    cache.standard("A", 2)
    cache.idle()
    assertEquals(setOf("A_2.webp"), files())
    assertEquals("image/webp", coverMimeOf(cache.standard("A", 2)!!.file))

    val request = seen.last()
    assertEquals("https://abs.example/api/items/A/cover", request.url.toString())
    assertEquals("Bearer tok", request.header("Authorization"))
    assertEquals("p", request.header("X-Proxy"))
    assertEquals("image/webp,image/*;q=0.8", request.header("Accept"))
  }

  @Test
  fun jpegAliasesAvifAndBmpAreStoredAndOtherTypesAreNotAskedAgain() {
    var type = "image/jpg"
    val seen = CopyOnWriteArrayList<Request>()
    val cache = cache(OkHttpCoverFetcher(fakeServer({ type }, seen), log = {}))

    cache.standard("A", 2)
    cache.idle()
    type = "image/avif"
    cache.standard("B", 2)
    cache.idle()
    type = "image/bmp"
    cache.standard("C", 2)
    cache.idle()
    assertEquals(setOf("A_2.jpg", "B_2.avif", "C_2.bmp"), files())
    assertEquals("image/avif", coverMimeOf(File(tmp.root, "$COVER_CACHE_DIR/B_2.avif")))

    type = "image/tiff"
    repeat(2) {
      cache.standard("D", 2)
      cache.idle()
    }
    assertEquals("a type the route can't serve back is not kept", 3, files().size)
    assertEquals("nor asked for again", 1, seen.count { it.url.encodedPath.contains("/D/") })
  }

  @Test
  fun rawOnlineIsNotStoredButSeedsStandard() = runBlocking {
    val cache = cache()
    val answer = cache.raw("A", 2)
    cache.idle()

    assertTrue(answer is CoverAnswer.Stream)
    assertEquals(setOf("A_2.jpg"), files())
    assertEquals(setOf("A" to true, "A" to false), fetcher.calls.toSet())
    assertEquals(2, fetcher.calls.size)
  }

  @Test
  fun rawWithTheExactVersionCachedDoesNotFetchTheStandardAgain() = runBlocking {
    seed("A", 2)
    val cache = cache()

    assertTrue(cache.raw("A", 2) is CoverAnswer.Stream)
    cache.idle()
    assertEquals(listOf("A" to true), fetcher.calls.toList())
  }

  @Test
  fun rawOfflineGetsStandard() = runBlocking {
    seed("A", 2)
    state = ServerState.OFFLINE

    val answer = cache().raw("A", 2) as CoverAnswer.Cached
    assertEquals(2L, answer.entry.updatedAt)
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun failedRawGetsTheCachedStandard() = runBlocking {
    seed("A", 2)
    fetcher.answer = { _, raw -> if (raw) null else image() }

    assertEquals(2L, (cache().raw("A", 2) as CoverAnswer.Cached).entry.updatedAt)
  }

  @Test
  fun aFailedRawGetsTheStandardCoverStoredMeanwhile() = runBlocking {
    fetcher.answer = { _, raw -> if (raw) { Thread.sleep(100); null } else image() }

    val answer = cache().raw("A", 2)

    assertEquals("the background stored it while the original failed", 2L, (answer as CoverAnswer.Cached).entry.updatedAt)
  }

  @Test
  fun unknownStateMayFetch() {
    state = ServerState.UNKNOWN
    val cache = cache()
    cache.standard("A", 2)
    cache.idle()
    assertEquals(1, fetcher.calls.size)

    active = null
    cache.standard("B", 2)
    cache.idle()
    assertEquals(1, fetcher.calls.size)
  }

  @Test
  fun requestWithoutVersionUsesTheReplicaRow() {
    replica = mapOf("A" to 7L)
    val cache = cache()
    assertNull(cache.standard("A", null))
    cache.idle()
    assertTrue(files().contains("A_7.jpg"))

    seed("B", 3)
    state = ServerState.OFFLINE
    assertEquals(3L, cache.standard("B", null)!!.updatedAt)
  }

  @Test
  fun requestWithoutVersionOrRowIsKeptAsTheLowestVersion() {
    val cache = cache()

    cache.standard("A", null)
    cache.idle()
    assertEquals(setOf("A_0.jpg"), files())

    cache.standard("A", 4)
    cache.idle()
    assertEquals("any versioned request replaces it", setOf("A_4.jpg"), files())
  }

  @Test
  fun unsafeIdIsAMissWithoutFetch() = runBlocking {
    val cache = cache()
    assertNull(cache.standard("../x", 1))
    assertSame(CoverAnswer.Miss, cache.raw("../x", 1))
    cache.idle()
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun requestBeforeWarmUpUsesTheDisk() {
    File(tmp.root, COVER_CACHE_DIR).mkdirs()
    File(tmp.root, "$COVER_CACHE_DIR/A_1.jpg").writeBytes(byteArrayOf(1, 2, 3))
    state = ServerState.OFFLINE

    val cache = cache()
    assertEquals("A_1.jpg", cache.latestFile("A")!!.name)
    assertEquals(1L, cache.standard("A", 1)!!.updatedAt)
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun latestFileKeepsThePlayingCoverFromEviction() {
    val small = CoverStore(File(tmp.root, "small"), maxBytes = 9)
    val cache = CoverCache(small, fetcher, { active }, { state }, { _, _ -> null }, scope)
    for (id in listOf("A", "B", "C")) small.put(id, 1, "jpg") { it.write(byteArrayOf(1, 2, 3)) }

    assertEquals("A_1.jpg", cache.latestFile("A")!!.name)
    small.put("D", 1, "jpg") { it.write(byteArrayOf(1, 2, 3)) }

    assertEquals("A_1.jpg", cache.latestFile("A")?.name)
    assertEquals(null, small.find("B", 1))
  }

  @Test
  fun rawIsNotCutWhileStreaming() {
    val seen = CopyOnWriteArrayList<Triple<Boolean, Int, Long>>()
    val client = coverHttpClient().newBuilder().addInterceptor(Interceptor { chain ->
      seen += Triple(chain.request().url.query.orEmpty().contains("raw=1"), chain.readTimeoutMillis(), chain.call().timeout().timeoutNanos())
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fake")
        .body(byteArrayOf(1, 2, 3).toResponseBody("image/jpeg".toMediaType())).build()
    }).build()
    val fetch = OkHttpCoverFetcher(client, log = {})

    fetch.fetch(server, "A", raw = true)!!.body.close()
    fetch.fetch(server, "A", raw = false)!!.body.close()

    val raw = seen.single { it.first }
    assertEquals("per read while streaming", COVER_RAW_READ_TIMEOUT_MS.toInt(), raw.second)
    assertEquals("no call timeout for the streamed original", 0L, raw.third)
    val standard = seen.single { !it.first }
    assertEquals(COVER_READ_TIMEOUT_MS.toInt(), standard.second)
    assertTrue("the standard cover keeps the call timeout", standard.third > 0L)
  }

  @Test
  fun rawWhoseHeadersDontArriveIsCancelled() {
    val socket = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    val accepted = CopyOnWriteArrayList<java.net.Socket>()
    val acceptor = Thread { runCatching { while (true) accepted += socket.accept() } }.apply { isDaemon = true; start() }
    try {
      val local = server.copy(address = "http://127.0.0.1:${socket.localPort}")
      val fetch = OkHttpCoverFetcher(coverHttpClient(), log = {}, rawHeaderTimeoutMs = 200)

      val started = System.nanoTime()
      val answer = fetch.fetch(local, "A", raw = true)
      val elapsedMs = (System.nanoTime() - started) / 1_000_000

      assertNull(answer)
      assertTrue("a server that accepts and never answers is given up on its headers, not the 20 s read: $elapsedMs ms", elapsedMs < 3_000)
    } finally {
      socket.close()
      accepted.forEach { it.close() }
      acceptor.interrupt()
    }
  }

  @Test
  fun standardCoverTimeouts() {
    val client = coverHttpClient()
    assertEquals(COVER_CONNECT_TIMEOUT_MS.toInt(), client.connectTimeoutMillis)
    assertEquals(COVER_READ_TIMEOUT_MS.toInt(), client.readTimeoutMillis)
    assertEquals(COVER_CALL_TIMEOUT_MS.toInt(), client.callTimeoutMillis)
  }

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5000
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "timed out" }
      Thread.sleep(10)
    }
  }
}

private fun image(): FetchedCover = FetchedCover("image/jpeg", "jpg", ByteArrayInputStream(byteArrayOf(9, 9, 9)), 3)
