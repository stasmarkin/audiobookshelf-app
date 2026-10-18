package app.absplus.android.media

import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.sync.ServerState
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
  private val now = AtomicLong(1_000_000L)
  private val announced = CopyOnWriteArrayList<String>()

  private fun cache(fetch: CoverFetcher = fetcher, workers: Int = COVER_FETCH_PARALLELISM) =
    CoverCache(store, fetch, { active }, { state }, { _, id -> replica[id] }, scope, clock = { now.get() }, workers = workers)
      .also { it.onCoverReady = { id -> announced += id } }

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
  fun staleQueuedJobsAreDropped() {
    val release = CountDownLatch(1)
    fetcher.answer = { id, _ -> if (id == "H") release.await(5, TimeUnit.SECONDS); image() }
    val cache = cache(workers = 1)

    cache.standard("H", 1)
    waitUntil { fetcher.calls.size == 1 }
    cache.standard("A", 1)
    now.addAndGet(COVER_QUEUE_MAX_AGE_MS + 1)
    cache.standard("B", 1)
    release.countDown()
    cache.idle()

    assertEquals("the card that asked a minute ago is gone", listOf("H", "B"), fetcher.calls.map { it.first })
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
  fun jpegAliasesAreStoredAndOtherImageTypesAreNot() {
    var type = "image/jpg"
    val cache = cache(OkHttpCoverFetcher(fakeServer({ type }), log = {}))

    cache.standard("A", 2)
    cache.idle()
    assertEquals(setOf("A_2.jpg"), files())

    type = "image/avif"
    cache.standard("B", 2)
    cache.idle()
    assertEquals("a type the cache can't serve back is not kept", setOf("A_2.jpg"), files())
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
  fun rawWaitsAtMostAFewSecondsForItsAnswerAndIsNotCutWhileStreaming() {
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
    assertEquals(COVER_RAW_FIRST_BYTE_MS.toInt(), raw.second)
    assertEquals("no call timeout for the streamed original", 0L, raw.third)
    val standard = seen.single { !it.first }
    assertEquals(COVER_READ_TIMEOUT_MS.toInt(), standard.second)
    assertTrue("the standard cover keeps the call timeout", standard.third > 0L)
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
