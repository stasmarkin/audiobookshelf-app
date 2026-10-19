package app.absplus.android.sync

import app.absplus.android.db.episodeRow
import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.replica.DetailLevel
import app.absplus.android.db.seriesRow
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpandedItemCacheTest {
  private val db = inMemoryReplica()
  private val mapper = CatalogJson.mapper
  private val topics = mutableListOf<String>()
  private val applier = CatalogEventApplier(db, { topics.addAll(it) }, {})
  /** What `GET /api/items/{id}` answers, by id; a missing id is a 404. */
  private val answers = HashMap<String, Pair<Int, String>>()
  @Volatile private var hold: CountDownLatch? = null
  private val arrived = CountDownLatch(1)
  private val api = FakeApi { request ->
    arrived.countDown()
    hold?.await(5, TimeUnit.SECONDS)
    answers[request.path.removePrefix("/api/items/")] ?: (404 to "Not found")
  }
  @Volatile private var online = true
  @Volatile private var active: String? = "srv"
  private val config = fakeServer("srv")
  private val cache = ExpandedItemCache(db, syncHttp(api), { online }, { active },
          { sc, event, payload -> applier.apply(sc, event, mapper.readTree(payload)) != CatalogEventResult.IGNORED }, {})

  @Before
  fun setUp() = runBlocking {
    db.libraries().upsertAll(listOf(libraryRow("srv", "lib1"), libraryRow("srv", "pods", position = 1, mediaType = "podcast")))
  }

  @After
  fun tearDown() = db.close()

  /** `GET /api/items/:id?expanded=1` of a book (fixture recorded from 2.37.1) with two chapters. */
  private fun expandedBook(id: String, updatedAt: Long = 100, libraryId: String = "lib1", progress: Boolean = false): ObjectNode {
    val node = mapper.readTree(javaClass.getResource("/catalog/book-expanded.json")) as ObjectNode
    node.put("id", id).put("libraryId", libraryId).put("updatedAt", updatedAt)
    val chapters = (node.path("media") as ObjectNode).putArray("chapters")
    chapters.addObject().put("id", 0).put("start", 0).put("end", 10).put("title", "One")
    chapters.addObject().put("id", 1).put("start", 10).put("end", 20).put("title", "Two")
    if (progress) node.putObject("userMediaProgress").put("id", "mp1").put("currentTime", 42.0).put("libraryItemId", id)
    node.putNull("rssFeed")
    return node
  }

  private fun serve(item: JsonNode) {
    answers[item.path("id").asText()] = 200 to mapper.writeValueAsString(item)
  }

  private fun open(id: String, force: Boolean = false) = runBlocking { cache.open(config, id, force) }
  private fun row(id: String) = runBlocking { db.items().get("srv", id) }
  private fun itemRequests() = api.requests.filter { it.path.startsWith("/api/items/") }

  @Test
  fun sameVersionIsNotRequestedAgain() {
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "li1"))) }
    serve(expandedBook("li1"))

    assertEquals(OpenResult.UPDATED, open("li1").result)
    assertEquals(listOf(FakeRequest("GET", "/api/items/li1", null, "expanded=1&include=progress,rssfeed")), itemRequests())
    assertEquals(DetailLevel.EXPANDED, row("li1")!!.detailLevel)

    val again = open("li1")
    assertEquals(OpenResult.UP_TO_DATE, again.result)
    assertNull(again.serverItem)
    assertEquals("the same version is not asked again", 1, itemRequests().size)
  }

  @Test
  fun staleIsRequested() {
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "li1").copy(detailLevel = DetailLevel.STALE, expandedJson = "{}"))) }
    serve(expandedBook("li1"))

    val outcome = open("li1")
    assertEquals(OpenResult.UPDATED, outcome.result)
    assertNotNull(outcome.serverItem)
    assertEquals(1, itemRequests().size)
    assertEquals(DetailLevel.EXPANDED, row("li1")!!.detailLevel)
  }

  @Test
  fun notFoundRemovesTheRow() {
    runBlocking {
      db.items().upsertAll(listOf(itemEntity("srv", "li1")))
      db.series().upsertAll(listOf(seriesRow("srv", "s1")))
      db.series().insertMembers(listOf(member("srv", "s1", "li1", "1")))
    }

    assertEquals(OpenResult.REMOVED, open("li1").result)
    assertNull(row("li1"))
    assertTrue(runBlocking { db.series().membershipOfItem("srv", "li1") }.isEmpty())
    assertTrue(topics.contains("items:lib1"))

    topics.clear()
    assertEquals("not in the replica either: still gone", OpenResult.REMOVED, open("li9").result)
    assertTrue(topics.isEmpty())
  }

  @Test
  fun offlineDoesNotRequest() {
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "li1"))) }
    serve(expandedBook("li1"))
    online = false

    assertEquals(OpenOutcome(OpenResult.SKIPPED), open("li1"))
    assertEquals(0, api.requests.size)
  }

  @Test
  fun concurrentOpensShareOneRequest() {
    runBlocking {
      db.items().upsertAll(listOf(itemEntity("srv", "li1"), itemEntity("srv", "li2"),
              itemEntity("srv", "li3", updatedAt = 100).copy(detailLevel = DetailLevel.EXPANDED, expandedJson = "{}")))
    }
    listOf("li1", "li2", "li3").forEach { serve(expandedBook(it)) }
    // (normal, normal), (normal, forced), (forced, normal on an expanded row)
    for ((id, forces) in listOf("li1" to (false to false), "li2" to (false to true), "li3" to (true to false))) {
      val latch = CountDownLatch(1)
      hold = latch
      val before = itemRequests().size
      runBlocking {
        val first = async(Dispatchers.IO) { cache.open(config, id, forces.first) }
        eventually("the first request reached the server") { itemRequests().size > before }
        val second = async(Dispatchers.IO) { cache.open(config, id, forces.second) }
        kotlinx.coroutines.delay(100)
        latch.countDown()
        assertEquals("$id first", OpenResult.UPDATED, first.await().result)
        assertEquals("$id second", OpenResult.UPDATED, second.await().result)
      }
      assertEquals("$id: one request", before + 1, itemRequests().size)
    }
  }

  @Test
  fun itemOfAnUnknownLibraryIsReturnedNotWritten() {
    serve(expandedBook("li1", libraryId = "lib9", progress = true))

    val outcome = open("li1")
    assertEquals(OpenResult.NOT_IN_REPLICA, outcome.result)
    val item = mapper.readTree(outcome.serverItem)
    assertEquals("li1", item.path("id").asText())
    assertFalse(item.has("userMediaProgress"))
    assertNull(row("li1"))
    assertEquals(0, runBlocking { db.items().count("srv", "lib9") })
  }

  /** The cache has no access to userdata.db: progress could only leak through the stored copy or the answer. */
  @Test
  fun progressInTheAnswerIsNotStoredOrMerged() {
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "li1"))) }
    serve(expandedBook("li1", progress = true))

    val outcome = open("li1")
    assertEquals(OpenResult.UPDATED, outcome.result)
    assertFalse(mapper.readTree(outcome.serverItem).has("userMediaProgress"))
    assertFalse(mapper.readTree(row("li1")!!.expandedJson).has("userMediaProgress"))
  }

  @Test
  fun answerAfterServerSwitchIsDropped() {
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "li1"))) }
    serve(expandedBook("li1"))
    val latch = CountDownLatch(1)
    hold = latch

    val outcome = runBlocking {
      val pending = async(Dispatchers.IO) { cache.open(config, "li1") }
      assertTrue(arrived.await(5, TimeUnit.SECONDS))
      active = "other"
      latch.countDown()
      pending.await()
    }
    assertEquals(OpenOutcome(OpenResult.FAILED), outcome)
    assertEquals(DetailLevel.MINIFIED, row("li1")!!.detailLevel)
  }

  @Test
  fun forcedOpenRequestsAnExpandedRow() {
    val podcast = mapper.readTree(javaClass.getResource("/catalog/podcast-expanded.json")) as ObjectNode
    podcast.put("id", "pod1").put("libraryId", "pods").put("updatedAt", 100)
    val episodes = (podcast.path("media") as ObjectNode).putArray("episodes")
    listOf("e1", "e2").forEachIndexed { i, id -> episodes.addObject().put("id", id).put("libraryItemId", "pod1").put("title", "Episode $id").put("addedAt", 10L + i) }
    runBlocking {
      db.items().upsertAll(listOf(itemEntity("srv", "pod1", libraryId = "pods", updatedAt = 100, mediaType = "podcast")
              .copy(detailLevel = DetailLevel.EXPANDED, expandedJson = "{}")))
      db.episodes().insertAll(listOf(episodeRow("srv", "e1", "pod1", libraryId = "pods")))
    }
    serve(podcast)

    assertEquals("an expanded podcast is not asked again", OpenResult.UP_TO_DATE, open("pod1").result)
    assertEquals(0, itemRequests().size)

    assertEquals(OpenResult.UPDATED, open("pod1", force = true).result)
    assertEquals(1, itemRequests().size)
    assertEquals(listOf("e1", "e2"), runBlocking { db.episodes().forPodcast("srv", "pod1") }.map { it.id })
  }

  @Test
  fun olderAnswerThanTheReplicaIsNotReturned() {
    val newer = itemEntity("srv", "li1", title = "Newer", updatedAt = 200)
    runBlocking { db.items().upsertAll(listOf(newer)) }
    serve(expandedBook("li1", updatedAt = 100))

    assertEquals(OpenOutcome(OpenResult.UP_TO_DATE), open("li1"))
    assertEquals(newer, row("li1"))
  }
}
