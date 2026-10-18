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
  fun failedLazyFetchGivesAnEmptyBody() {
    fetchAnswer = { null }

    val response = route("/_absplus/cover/li1?ts=5")!!
    assertEquals(200, response.statusCode)
    assertEquals(0, response.data.readBytes().size)
  }

  @Test
  fun lazyFetchStoresAndServesTheCover() {
    val response = route("/_absplus/cover/li1?ts=5")!!
    assertEquals(0, fetches.size)

    assertArrayEquals(JPEG, response.data.readBytes())
    assertEquals(listOf("li1" to false), fetches.toList())
    assertNotNull(store.find("li1", 5))
  }

  @Test
  fun requestWithoutVersionChecksTheReplica() {
    seed("A", 1)
    replica = mapOf("A" to 2L)

    val response = route("/_absplus/cover/A")!!
    assertArrayEquals(JPEG, response.data.readBytes())
    assertEquals(listOf("A" to false), fetches.toList())
    assertEquals(2L, store.newest("A")!!.updatedAt)
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
