package app.absplus.android.sync

import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.replica.CatalogFilter
import app.absplus.android.db.replica.ItemsQuery
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.orderItems
import app.absplus.android.db.seriesRow
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogEventApplierTest {
  private val db = inMemoryReplica()
  private val topics = mutableListOf<String>()
  private var changes = 0
  private val applier = CatalogEventApplier(db, { topics.addAll(it) }, { changes++ })
  private val mapper = CatalogJson.mapper

  @Before
  fun setUp() = runBlocking {
    db.libraries().upsertAll(listOf(libraryRow("srv", "lib1").copy(
            filterDataJson = """{"authors":[{"id":"a1","name":"Old"},{"id":"a2","name":"Other"}],"series":[]}""")))
  }

  @After
  fun tearDown() = db.close()

  /** The expanded payload of socket item events (fixture recorded from 2.37.1), moved to lib1. */
  private fun item(id: String, title: String = "Title", updatedAt: Long = 100, series: List<Pair<String, String?>> = emptyList()): ObjectNode {
    val node = mapper.readTree(javaClass.getResource("/catalog/book-expanded.json")) as ObjectNode
    node.put("id", id).put("libraryId", "lib1").put("updatedAt", updatedAt)
    val metadata = node.path("media").path("metadata") as ObjectNode
    metadata.put("title", title)
    val list = mapper.createArrayNode()
    series.forEach { (sid, seq) -> list.addObject().put("id", sid).put("name", "Series $sid").put("sequence", seq) }
    metadata.set<JsonNode>("series", list)
    return node
  }

  private fun apply(event: String, payload: JsonNode) = runBlocking { applier.apply("srv", event, payload) }
  private fun json(text: String) = mapper.readTree(text)
  private fun row(id: String) = runBlocking { db.items().get("srv", id) }
  private fun membership(id: String) = runBlocking { db.series().membershipOfItem("srv", id) }.map { it.seriesId to it.sequence }.toSet()

  @Test
  fun eachEventChangesTheReplica() {
    data class Case(val event: String, val payload: JsonNode, val topics: Set<String>, val check: () -> Unit)
    runBlocking {
      db.items().upsertAll(listOf(itemEntity("srv", "gone"), itemEntity("srv", "fed")))
      db.series().upsertAll(listOf(seriesRow("srv", "sOld")))
      db.libraries().upsertAll(listOf(libraryRow("srv", "lib9", position = 4)))
    }
    val cases = listOf(
            Case("item_added", item("li1", "Added", series = listOf("s1" to "1")), setOf("items:lib1", "series:lib1")) {
              assertEquals("Added", row("li1")!!.title)
              assertEquals(setOf("s1" to "1"), membership("li1"))
              assertEquals("Series s1", runBlocking { db.series().get("srv", "s1") }!!.name)
            },
            Case("item_updated", item("li1", "Updated", updatedAt = 101, series = listOf("s1" to "1")), setOf("items:lib1")) {
              assertEquals("Updated", row("li1")!!.title)
            },
            Case("items_added", mapper.createArrayNode().add(item("li2")), setOf("items:lib1")) { assertNotNull(row("li2")) },
            Case("item_removed", json("""{"id":"gone","libraryId":"lib1"}"""), setOf("items:lib1")) { assertNull(row("gone")) },
            Case("library_added", json("""{"id":"lib2","name":"New","mediaType":"podcast"}"""), setOf("libraries", "library:lib2")) {
              assertEquals(5, runBlocking { db.libraries().get("srv", "lib2") }!!.position)
            },
            Case("library_updated", json("""{"id":"lib1","name":"Renamed","mediaType":"book"}"""), setOf("libraries", "library:lib1")) {
              val lib = runBlocking { db.libraries().get("srv", "lib1") }!!
              assertTrue(lib.json.contains("Renamed"))
              assertNotNull("filterdata is kept", lib.filterDataJson)
            },
            Case("library_removed", json("""{"id":"lib9"}"""), setOf("libraries")) {
              assertNull(runBlocking { db.libraries().get("srv", "lib9") })
            },
            Case("series_added", json("""{"id":"s7","name":"Seven","nameIgnorePrefix":"Seven","libraryId":"lib1","addedAt":5}"""), setOf("series:lib1")) {
              assertEquals("Seven", runBlocking { db.series().get("srv", "s7") }!!.name)
            },
            Case("series_updated", json("""{"id":"s7","name":"Seven!","nameIgnorePrefix":"Seven!","libraryId":"lib1","addedAt":5}"""), setOf("series:lib1")) {
              assertEquals("Seven!", runBlocking { db.series().get("srv", "s7") }!!.name)
            },
            Case("series_removed", json("""{"id":"sOld","libraryId":"lib1"}"""), setOf("series:lib1")) {
              assertNull(runBlocking { db.series().get("srv", "sOld") })
            },
            Case("rss_feed_open", json("""{"id":"f1","entityType":"libraryItem","entityId":"fed","feedUrl":"/feed/x"}"""), setOf("items:lib1")) {
              assertNotNull(row("fed")!!.rssFeedJson)
            },
            Case("rss_feed_closed", json("""{"id":"f1","entityType":"libraryItem","entityId":"fed","feedUrl":"/feed/x"}"""), setOf("items:lib1")) {
              assertNull(row("fed")!!.rssFeedJson)
            },
            Case("author_updated", json("""{"id":"a1","name":"New"}"""), setOf("library:lib1")) {})
    for (case in cases) {
      topics.clear()
      assertEquals(case.event, CatalogEventResult.APPLIED, apply(case.event, case.payload))
      assertEquals(case.event, case.topics, topics.toSet())
      case.check()
    }
  }

  @Test
  fun itemUpdatedMovesBookBetweenSeries() {
    apply("item_added", item("li1", series = listOf("s1" to "1")))
    apply("item_updated", item("li1", updatedAt = 101, series = listOf("s2" to "4")))
    assertEquals(setOf("s2" to "4"), membership("li1"))
  }

  @Test
  fun itemsUpdatedArrayAppliesEveryItem() {
    apply("items_added", mapper.createArrayNode().add(item("li1")).add(item("li2")))
    apply("items_updated", mapper.createArrayNode().add(item("li1", "One", 101)).add(item("li2", "Two", 101)))
    assertEquals("One", row("li1")!!.title)
    assertEquals("Two", row("li2")!!.title)
  }

  @Test
  fun olderItemPayloadDoesNotOverwriteNewerRow() {
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "li1", title = "Newer", updatedAt = 200))) }
    assertEquals(CatalogEventResult.IGNORED, apply("item_updated", item("li1", "Older", updatedAt = 100)))
    assertEquals("Newer", row("li1")!!.title)
  }

  @Test
  fun itemOfUnknownLibraryIsIgnored() {
    val payload = item("li1").put("libraryId", "nowhere")
    assertEquals(CatalogEventResult.IGNORED, apply("item_added", payload))
    assertNull(row("li1"))
  }

  @Test
  fun removedUnknownItemIsHarmless() {
    assertEquals(CatalogEventResult.IGNORED, apply("item_removed", json("""{"id":"nope","libraryId":"lib1"}""")))
  }

  @Test
  fun backupAppliedAsksForReconcile() {
    assertEquals(CatalogEventResult.NEEDS_RECONCILE, apply("backup_applied", json("{}")))
  }

  @Test
  fun itemUpdatedKeepsOpenFeed() {
    val listed = item("li1").put("updatedAt", 100)
    listed.set<JsonNode>("rssFeed", json("""{"id":"f1","entityType":"libraryItem","entityId":"li1","feedUrl":"/feed/x"}"""))
    runBlocking { db.items().upsertAll(listOf(CatalogJson.itemEntity("srv", listed))) }

    apply("item_updated", item("li1", "Renamed", updatedAt = 101))

    val kept = row("li1")!!
    assertEquals("Renamed", kept.title)
    assertNotNull(kept.rssFeedJson)
    assertEquals("the hash is the one a reconcile page with the feed gives", CatalogJson.contentHash(listed.put("updatedAt", 101).also {
      (it.path("media").path("metadata") as ObjectNode).put("title", "Renamed")
    }), kept.contentHash)
    val rows = runBlocking { db.items().rows("srv", "lib1") }
    val q = ItemsQuery("media.metadata.title", false, CatalogFilter("feed-open", null), false, false, "book")
    assertEquals(listOf("li1"), orderItems(rows, q, emptyMap(), SeriesIndex.EMPTY, emptyMap(), 0, Random(1)).map { it.itemId })

    apply("rss_feed_closed", json("""{"id":"f1","entityType":"libraryItem","entityId":"li1"}"""))
    assertNull(row("li1")!!.rssFeedJson)
    apply("rss_feed_open", json("""{"id":"f2","entityType":"libraryItem","entityId":"li1"}"""))
    assertTrue(row("li1")!!.rssFeedJson!!.contains("f2"))
    assertEquals(CatalogEventResult.IGNORED, apply("rss_feed_open", json("""{"id":"f3","entityType":"series","entityId":"s1"}""")))
  }

  @Test
  fun authorRenameUpdatesFilterData() {
    assertEquals(CatalogEventResult.APPLIED, apply("author_updated", json("""{"id":"a1","name":"New"}""")))
    val filterData = json(runBlocking { db.libraries().get("srv", "lib1") }!!.filterDataJson!!)
    assertEquals(listOf("New", "Other"), filterData.path("authors").map { it.path("name").asText() })
    assertEquals(listOf("library:lib1"), topics)
    assertTrue(changes > 0)
  }

  @Test
  fun removedItemTakesItsMembership() {
    apply("item_added", item("li1", series = listOf("s1" to "1")))
    runBlocking { db.series().insertMembers(listOf(member("srv", "s1", "li1", "1"))) }
    apply("item_removed", json("""{"id":"li1","libraryId":"lib1"}"""))
    assertTrue(membership("li1").isEmpty())
  }
}
