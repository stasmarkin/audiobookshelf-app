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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
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

  private fun cache(fetch: CoverFetcher = fetcher) =
    CoverCache(store, fetch, { active }, { state }, { _, id -> replica[id] }, scope)

  private fun seed(itemId: String, updatedAt: Long) {
    store.put(itemId, updatedAt, "jpg") { it.write(byteArrayOf(1, 2, 3)) }!!
  }

  private fun files(): Set<String> = File(tmp.root, COVER_CACHE_DIR).list()?.toSet() ?: emptySet()

  private suspend fun idle() {
    while (true) {
      val children = scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList()
      if (children.isEmpty()) return
      children.joinAll()
    }
  }

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
  fun offlineMissDoesNotFetch() = runBlocking {
    state = ServerState.OFFLINE

    assertSame(CoverAnswer.Miss, cache().standard("A", 2))
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun offlineServesOlderVersion() = runBlocking {
    seed("A", 1)
    state = ServerState.OFFLINE

    val answer = cache().standard("A", 2) as CoverAnswer.Cached
    assertEquals(1L, answer.entry.updatedAt)
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun failedFetchServesOlderVersion() = runBlocking {
    seed("A", 1)
    fetcher.answer = { _, _ -> null }

    val answer = cache().standard("A", 2) as CoverAnswer.Cached
    assertEquals(1L, answer.entry.updatedAt)
    assertEquals(setOf("A_1.jpg"), files())
  }

  @Test
  fun olderRequestedVersionServesTheNewestWithoutFetching() = runBlocking {
    seed("A", 5)

    val answer = cache().standard("A", 2) as CoverAnswer.Cached
    assertEquals(5L, answer.entry.updatedAt)
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun concurrentRequestsFetchOnce() = runBlocking {
    val release = CountDownLatch(1)
    fetcher.answer = { _, _ -> release.await(5, TimeUnit.SECONDS); image() }
    val cache = cache()

    val requests = (1..5).map { async(Dispatchers.IO) { cache.standard("A", 2) } }
    waitUntil { fetcher.calls.size >= 1 }
    Thread.sleep(100)
    release.countDown()
    val answers = requests.awaitAll()

    assertEquals("one network request", 1, fetcher.calls.size)
    val files = answers.map { (it as CoverAnswer.Cached).entry.file }.toSet()
    assertEquals(1, files.size)
    assertEquals(setOf("A_2.jpg"), files())
  }

  @Test
  fun offlineWhileQueuedDoesNotFetch() = runBlocking {
    val release = CountDownLatch(1)
    fetcher.answer = { _, _ -> release.await(5, TimeUnit.SECONDS); image() }
    val cache = cache()

    val busy = (1..COVER_FETCH_PARALLELISM).map { async(Dispatchers.IO) { cache.standard("B$it", 1) } }
    waitUntil { fetcher.calls.size == COVER_FETCH_PARALLELISM }
    val queued = async(Dispatchers.IO) { cache.standard("F", 1) }
    Thread.sleep(100)
    state = ServerState.OFFLINE
    release.countDown()
    busy.awaitAll()

    assertSame(CoverAnswer.Miss, queued.await())
    assertEquals(0, fetcher.callsFor("F"))
  }

  @Test
  fun nonImageAnswerIsNotStored() = runBlocking {
    val seen = CopyOnWriteArrayList<Request>()
    var type = "text/html"
    val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
      val request = chain.request()
      seen += request
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fake")
        .body(byteArrayOf(1, 2, 3, 4).toResponseBody(type.toMediaType())).build()
    }).build()
    val logged = CopyOnWriteArrayList<String>()
    val cache = cache(OkHttpCoverFetcher(client, log = { logged += it }))

    assertSame("a portal page is not a cover", CoverAnswer.Miss, cache.standard("A", 2))
    assertSame(CoverAnswer.Miss, cache.standard("A", 2))
    assertEquals(emptySet<String>(), files())
    assertEquals("a refusal is logged once, without the URL or token", listOf("Cover not available: type text/html"), logged.toList())

    type = "image/webp"
    val answer = cache.standard("A", 2) as CoverAnswer.Cached
    assertEquals("image/webp", answer.mime)
    assertEquals(setOf("A_2.webp"), files())

    val request = seen.last()
    assertEquals("https://abs.example/api/items/A/cover", request.url.toString())
    assertEquals("Bearer tok", request.header("Authorization"))
    assertEquals("p", request.header("X-Proxy"))
    assertEquals("image/webp,image/*;q=0.8", request.header("Accept"))
  }

  @Test
  fun rawOnlineIsNotStoredButSeedsStandard() = runBlocking {
    val answer = cache().raw("A", 2)
    idle()

    assertTrue(answer is CoverAnswer.Stream)
    assertEquals(setOf("A_2.jpg"), files())
    assertEquals(listOf("A" to true, "A" to false).toSet(), fetcher.calls.toSet())
    assertEquals(2, fetcher.calls.size)
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
  fun unknownStateMayFetch() = runBlocking {
    state = ServerState.UNKNOWN
    assertTrue(cache().standard("A", 2) is CoverAnswer.Cached)
    assertEquals(1, fetcher.calls.size)

    active = null
    assertSame(CoverAnswer.Miss, cache().standard("B", 2))
    assertEquals(1, fetcher.calls.size)
  }

  @Test
  fun requestWithoutVersionUsesTheReplicaRow() = runBlocking {
    replica = mapOf("A" to 7L)
    val answer = cache().standard("A", null) as CoverAnswer.Cached
    assertEquals(7L, answer.entry.updatedAt)
    assertTrue(files().contains("A_7.jpg"))

    seed("B", 3)
    state = ServerState.OFFLINE
    assertEquals(3L, (cache().standard("B", null) as CoverAnswer.Cached).entry.updatedAt)
  }

  @Test
  fun requestWithoutVersionOrRowStreamsOnline() = runBlocking {
    assertTrue(cache().standard("A", null) is CoverAnswer.Stream)
    assertEquals(emptySet<String>(), files())
  }

  @Test
  fun unsafeIdIsAMissWithoutFetch() = runBlocking {
    assertSame(CoverAnswer.Miss, cache().standard("../x", 1))
    assertSame(CoverAnswer.Miss, cache().raw("../x", 1))
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun requestBeforeWarmUpUsesTheDisk() = runBlocking {
    File(tmp.root, COVER_CACHE_DIR).mkdirs()
    File(tmp.root, "$COVER_CACHE_DIR/A_1.jpg").writeBytes(byteArrayOf(1, 2, 3))
    state = ServerState.OFFLINE

    val cache = cache()
    assertEquals("A_1.jpg", cache.latestFile("A")!!.name)
    assertTrue(cache.standard("A", 1) is CoverAnswer.Cached)
    assertEquals(0, fetcher.calls.size)
  }

  @Test
  fun cachedNeedsTheExactVersion() {
    seed("A", 1)
    val cache = cache()

    assertEquals(1L, cache.cached("A", 1)!!.updatedAt)
    assertEquals(null, cache.cached("A", 2))
  }

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5000
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "timed out" }
      Thread.sleep(10)
    }
  }

  private fun fakeServer(type: String, body: ByteArray) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fake")
      .body(body.toResponseBody(type.toMediaType())).build()
  }).build()

  @Test
  fun imageTypeTheCacheDoesNotKeepIsStreamedNotStored() = runBlocking {
    val avif = byteArrayOf(5, 6, 7, 8)
    val cache = cache(OkHttpCoverFetcher(fakeServer("image/avif", avif), log = {}))

    val answer = cache.standard("A", 2) as CoverAnswer.Stream
    assertEquals("image/avif", answer.mime)
    assertTrue(avif.contentEquals(answer.body.readBytes()))
    assertEquals(emptySet<String>(), files())
  }

  @Test
  fun rawWithTheExactVersionCachedDoesNotFetchTheStandardAgain() = runBlocking {
    seed("A", 2)

    assertTrue(cache().raw("A", 2) is CoverAnswer.Stream)
    idle()

    assertEquals(listOf("A" to true), fetcher.calls.toList())
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
  fun aTricklingServerCannotHoldAFetchSlotForever() {
    assertEquals(COVER_CALL_TIMEOUT_MS.toInt(), coverHttpClient().callTimeoutMillis)
  }
}

private fun image(): FetchedCover = FetchedCover("image/jpeg", "jpg", ByteArrayInputStream(byteArrayOf(9, 9, 9)), 3)
