package app.absplus.android.media

import android.net.Uri
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.sync.ServerState
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverRouteTest {
  @get:Rule val tmp = TemporaryFolder()

  private val origin = Uri.parse("https://localhost")
  private val server = ServerConnectionConfig("srv", 0, "Home", "https://abs.example", "2.20.0", "u1", "user", "tok", null)
  @Volatile private var state = ServerState.ONLINE
  private var replica: Map<String, Long> = emptyMap()
  private val replicaReads = CopyOnWriteArrayList<String>()
  private val fetches = CopyOnWriteArrayList<Pair<String, Boolean>>()
  @Volatile private var fetchAnswer: () -> FetchedCover? = { FetchedCover("image/jpeg", "jpg", ByteArrayInputStream(JPEG), JPEG.size.toLong()) }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var maxBytes = MAX_COVER_CACHE_BYTES
  private val store by lazy { CoverStore(File(tmp.root, COVER_CACHE_DIR), maxBytes) }
  private val cache by lazy {
    CoverCache(store, { _, id, raw -> fetches += id to raw; fetchAnswer() }, { server }, { state },
      { _, id -> replicaReads += id; replica[id] }, scope)
  }

  @After fun stop() = scope.cancel()

  private fun route(path: String) = interceptCoverRoute(Uri.parse("https://localhost$path"), origin, cache)

  private fun seed(itemId: String, updatedAt: Long, bytes: ByteArray = JPEG, ext: String = "jpg") =
    store.put(itemId, updatedAt, ext) { it.write(bytes) }!!

  @Test
  fun unsafeIdsAreRejected() {
    val paths = listOf(
      "/_absplus/cover/..%2F..%2Fshared_prefs",
      "/_absplus/cover/",
      "/_absplus/cover/a/b",
      "/_absplus/cover/" + "a".repeat(257),
    )
    for (path in paths) {
      assertNull(path, parseCoverRoute(Uri.parse("https://localhost$path")))
      val response = route(path)!!
      assertEquals(path, 404, response.statusCode)
    }
    assertEquals(0, fetches.size)
    assertEquals(0, replicaReads.size)
  }

  @Test
  fun parsesVersionAndRaw() {
    assertEquals(CoverRequest("li_1", 1700000000000, true), parseCoverRoute(Uri.parse("https://localhost/_absplus/cover/li_1?ts=1700000000000&raw=1")))
    assertEquals(CoverRequest("li1", null, false), parseCoverRoute(Uri.parse("https://localhost/_absplus/cover/li1?ts=abc")))
    assertEquals(CoverRequest("li1", null, false), parseCoverRoute(Uri.parse("https://localhost/_absplus/cover/li1?ts=-5")))
    assertNull("not a cover route", route("/item/abc"))
  }

  @Test
  fun foreignHostIsNotIntercepted() {
    assertNull(interceptCoverRoute(Uri.parse("https://abs.example/_absplus/cover/li1?ts=1"), origin, cache))
    assertNull(interceptCoverRoute(Uri.parse("http://localhost/_absplus/cover/li1?ts=1"), origin, cache))
    assertNull(interceptCoverRoute(Uri.parse("https://localhost:8443/_absplus/cover/li1?ts=1"), origin, cache))
  }

  @Test
  fun cachedCoverIsServedFromTheFile() {
    seed("li1", 5, PNG, "png")

    val response = route("/_absplus/cover/li1?ts=5")!!
    assertEquals(200, response.statusCode)
    assertEquals("image/png", response.mimeType)
    assertEquals("no-store", response.responseHeaders["Cache-Control"])
    assertArrayEquals(PNG, response.data.readBytes())
    assertEquals(0, fetches.size)
  }

  @Test
  fun offlineMissIs404() {
    state = ServerState.OFFLINE

    assertEquals(404, route("/_absplus/cover/li1?ts=5")!!.statusCode)
    assertEquals(0, fetches.size)
  }

  @Test
  fun offlineServesTheCachedVersionWithoutVersion() {
    seed("li1", 3)
    state = ServerState.OFFLINE

    val response = route("/_absplus/cover/li1?raw=1")!!
    assertEquals(200, response.statusCode)
    assertArrayEquals(JPEG, response.data.readBytes())
    assertEquals(0, fetches.size)
  }

  @Test
  fun failedRawFetchGivesAnEmptyBody() {
    fetchAnswer = { null }

    val response = route("/_absplus/cover/li1?ts=5&raw=1")!!
    assertEquals(200, response.statusCode)
    assertEquals(0, response.data.readBytes().size)
  }

  private fun hangingFetches(): CountDownLatch {
    // Class loading and the index scan happen here, not inside the timed answer
    route("/_absplus/cover/")
    cache.latestFile("warm-up")
    val release = CountDownLatch(1)
    fetchAnswer = { release.await(10, TimeUnit.SECONDS); FetchedCover("image/jpeg", "jpg", ByteArrayInputStream(JPEG), JPEG.size.toLong()) }
    return release
  }

  @Test
  fun aMissNeverBlocksTheResponse(): Unit = runBlocking {
    val release = hangingFetches()
    try {
      val read = withTimeout(200) {
        withContext(Dispatchers.IO) {
          val response = route("/_absplus/cover/li1?ts=5")!!
          response.statusCode to response.data.readBytes().size
        }
      }
      assertEquals("a miss is a 404 at once: the card shows its title", 404 to 0, read)
      waitUntil { fetches.toList() == listOf("li1" to false) }
    } finally {
      release.countDown()
    }
  }

  @Test
  fun aHitIsServedWhileFetchesHang(): Unit = runBlocking {
    val release = hangingFetches()
    seed("hit", 1)
    try {
      for (i in 1..COVER_FETCH_PARALLELISM) route("/_absplus/cover/miss$i?ts=1")
      waitUntil { fetches.size == COVER_FETCH_PARALLELISM }

      val body = withTimeout(200) { withContext(Dispatchers.IO) { route("/_absplus/cover/hit?ts=1")!!.data.readBytes() } }
      assertArrayEquals(JPEG, body)
    } finally {
      release.countDown()
    }
  }

  @Test
  fun aFetchedCoverIsServedOnTheNextRequest() {
    assertEquals(404, route("/_absplus/cover/li1?ts=5")!!.statusCode)
    runBlocking { cache.awaitIdleForTest() }

    assertArrayEquals(JPEG, route("/_absplus/cover/li1?ts=5")!!.data.readBytes())
    assertEquals(listOf("li1" to false), fetches.toList())
  }

  @Test
  fun requestWithoutVersionChecksTheReplica() {
    seed("A", 1)
    replica = mapOf("A" to 2L)

    assertArrayEquals("the cached version at once", JPEG, route("/_absplus/cover/A")!!.data.readBytes())
    runBlocking { cache.awaitIdleForTest() }
    assertEquals(listOf("A" to false), fetches.toList())
    assertEquals(2L, store.newest("A")!!.updatedAt)
  }

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5000
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "timed out" }
      Thread.sleep(10)
    }
  }

  @Test
  fun routeHitKeepsTheFileFromEviction() {
    maxBytes = 3L * JPEG.size
    seed("A", 1); seed("B", 1); seed("C", 1)

    route("/_absplus/cover/A?ts=1")!!.data.close()
    seed("D", 1)

    assertNotNull(store.find("A", 1))
    assertNull(store.find("B", 1))
  }

  private companion object {
    val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 4, 5)
  }
}
