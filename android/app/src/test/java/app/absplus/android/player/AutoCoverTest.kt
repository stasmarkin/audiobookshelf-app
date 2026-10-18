package app.absplus.android.player

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.managers.DbManager
import app.absplus.android.media.COVER_CACHE_DIR
import app.absplus.android.media.COVER_PREFETCH_LIMIT
import app.absplus.android.media.CoverCache
import app.absplus.android.media.CoverFetcher
import app.absplus.android.media.CoverStore
import app.absplus.android.media.FetchedCover
import app.absplus.android.sync.ServerState
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android Auto icons from the cover cache, also without the server (release 7's cache). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoCoverTest {
  private val ctx: Context = ApplicationProvider.getApplicationContext()
  private val server = ServerConnectionConfig("srv", 0, "Home", "https://abs.example", "2.20.0", "u1", "user", "tok", null)
  @Volatile private var state = ServerState.ONLINE
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val fetched = CopyOnWriteArrayList<String>()
  private val store by lazy { CoverStore(File(ctx.filesDir, COVER_CACHE_DIR)) }
  private val cache by lazy {
    CoverCache(store, CoverFetcher { _, itemId, _ ->
      fetched += itemId
      FetchedCover("image/jpeg", "jpg", ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
    }, { server }, { state }, { _, _ -> null }, scope)
  }

  private val icon = Uri.parse("android.resource://app/icon")
  private val serverUri = Uri.parse("https://abs.example/api/items/li1/cover")
  private val cachedUri = Uri.parse("content://app.fileprovider/cover_cache/li1_5.jpg")

  private fun book(id: String, updatedAt: Long, coverPath: String? = "/metadata/items/$id/cover.jpg") =
    LibraryItem(id, "ino-$id", "lib", "f", "", "", 0L, 0L, 0L, 0L, updatedAt, null, null, false, false, "book",
      MediaType(MediaTypeMetadata("Book $id", false), coverPath), null, null, null, null, null)

  private suspend fun idle() {
    while (true) {
      val children = scope.coroutineContext[Job]!!.children.toList()
      if (children.isEmpty()) return
      children.joinAll()
    }
  }

  @Before fun setUp() {
    // DeviceManager reads Paper on first access (the server address of a cover URL)
    DbManager.initialize(ctx)
    resetFileProviderRoots()
    CoverCache.setForTest(cache)
  }

  @After fun tearDown() {
    CoverCache.setForTest(null)
    scope.cancel()
    File(ctx.filesDir, COVER_CACHE_DIR).deleteRecursively()
  }

  @Test fun cachedCoverWinsAndOfflineFallsBackToTheIcon() {
    assertEquals(cachedUri, autoCoverUri(cachedUri, serverUri, true, icon))
    assertEquals(cachedUri, autoCoverUri(cachedUri, serverUri, false, icon))
    assertEquals(serverUri, autoCoverUri(null, serverUri, true, icon))
    assertEquals("the host cannot fetch the URL offline", icon, autoCoverUri(null, serverUri, false, icon))
    assertEquals("no live session yet: no address", icon, autoCoverUri(null, null, true, icon))
  }

  @Test fun descriptionIconIsTheCachedFile() {
    store.put("li1", 5, "jpg") { it.write(byteArrayOf(1, 2, 3)) }!!
    val withCache = book("li1", 5).getMediaDescription(null, ctx).iconUri!!
    assertEquals("content", withCache.scheme)
    assertTrue(withCache.toString(), withCache.toString().endsWith("/li1_5.jpg"))
    val withoutCache = book("li2", 5).getMediaDescription(null, ctx).iconUri!!
    assertEquals("offline without a cached file: the app icon", "android.resource", withoutCache.scheme)
  }

  @Test fun prefetchAsksOnlyForMissingCovers() = runBlocking {
    store.put("li1", 5, "jpg") { it.write(byteArrayOf(1, 2, 3)) }!!
    cache.prefetch(coversToPrefetch(listOf(book("li1", 5), book("li2", 7), book("li3", 1, coverPath = null), null)))
    idle()
    assertEquals(listOf("li2"), fetched.toList())
    assertTrue("stored for offline", store.find("li2", 7) != null)

    state = ServerState.OFFLINE
    cache.prefetch(listOf("li4" to 1L))
    idle()
    assertEquals("offline nothing is fetched", listOf("li2"), fetched.toList())
  }

  @Test fun prefetchTakesTheTopOfALongList() = runBlocking {
    cache.prefetch((1..200).map { "li$it" to 1L })
    idle()
    assertEquals(COVER_PREFETCH_LIMIT, fetched.size)
    assertEquals((1..COVER_PREFETCH_LIMIT).map { "li$it" }.toSet(), fetched.toSet())
  }

  @Test fun aCoverAskedAfterASwitchIsNotJoinedToThePreviousServersPrefetch() {
    val paused = kotlinx.coroutines.test.StandardTestDispatcher()
    var active: ServerConnectionConfig? = server
    val other = ServerConnectionConfig("other", 1, "Other", "https://other.example", "2.20.0", "u2", "user", "tok2", null)
    val queued = CoverCache(store, CoverFetcher { config, itemId, _ ->
      fetched += "${config.id}:$itemId"
      FetchedCover("image/jpeg", "jpg", ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
    }, { active }, { state }, { _, _ -> null }, CoroutineScope(SupervisorJob() + paused))
    queued.prefetch(listOf("li1" to 1L))
    active = other
    var answer: app.absplus.android.media.CoverAnswer? = null
    CoroutineScope(paused).launch { answer = queued.standard("li1", 1L) }
    paused.scheduler.advanceUntilIdle()
    assertEquals(listOf("other:li1"), fetched.toList())
    assertTrue(answer.toString(), answer is app.absplus.android.media.CoverAnswer.Cached)
  }

  @Test fun prefetchQueuedForAnotherServerIsDropped() {
    val paused = kotlinx.coroutines.test.StandardTestDispatcher()
    var active: ServerConnectionConfig? = server
    val queued = CoverCache(store, CoverFetcher { _, itemId, _ ->
      fetched += itemId
      null
    }, { active }, { state }, { _, _ -> null }, CoroutineScope(SupervisorJob() + paused))
    queued.prefetch(listOf("li1" to 1L))
    active = server.let { ServerConnectionConfig("other", 1, "Other", "https://other.example", "2.20.0", "u2", "user", "tok2", null) }
    paused.scheduler.advanceUntilIdle()
    assertEquals("the item of the previous server is not asked of the new one", emptyList<String>(), fetched.toList())
  }
}
