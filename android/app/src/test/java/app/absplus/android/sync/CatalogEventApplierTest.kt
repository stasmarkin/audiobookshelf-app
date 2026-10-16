package app.absplus.android.sync

import app.absplus.android.db.authorRow
import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.replica.CatalogFilter
import app.absplus.android.db.replica.DetailLevel
import app.absplus.android.db.replica.ItemsQuery
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.orderItems
import app.absplus.android.db.seriesRow
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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

  private fun podcast(updatedAt: Long = 100, episodes: Int? = null): ObjectNode {
    val node = mapper.readTree(javaClass.getResource("/catalog/podcast-item-updated.json")) as ObjectNode
    node.put("id", "p1").put("libraryId", "lib1").put("updatedAt", updatedAt)
    val media = node.path("media") as ObjectNode
    val list = media.path("episodes") as com.fasterxml.jackson.databind.node.ArrayNode
    if (episodes == null) return node
    while (list.size() > episodes) list.remove(list.size() - 1)
    return node
  }

  private fun episodeIds(podcastId: String) = runBlocking { db.episodes().forPodcast("srv", podcastId) }.map { it.id }

  @Test
  fun itemUpdatedStoresExpandedAndEpisodes() {
    apply("item_updated", item("li1"))
    val book = row("li1")!!
    assertEquals(DetailLevel.EXPANDED, book.detailLevel)
    assertTrue(mapper.readTree(book.expandedJson).path("media").path("chapters").isArray)

    topics.clear()
    val payload = podcast()
    apply("item_updated", payload)
    val all = payload.path("media").path("episodes").map { it.path("id").asText() }
    assertEquals(3, all.size)
    assertEquals(all.toSet(), episodeIds("p1").toSet())
    assertEquals(DetailLevel.EXPANDED, row("p1")!!.detailLevel)
    assertFalse("episodes are not kept twice", mapper.readTree(row("p1")!!.expandedJson).path("media").has("episodes"))
    assertTrue(topics.containsAll(listOf("items:lib1", "episodes:lib1")))

    apply("item_updated", podcast(updatedAt = 101, episodes = 2))
    assertEquals(all.take(2).toSet(), episodeIds("p1").toSet())
  }

  @Test
  fun podcastPayloadWithoutEpisodesMarksStale() {
    apply("item_updated", podcast())
    assertEquals(DetailLevel.EXPANDED, row("p1")!!.detailLevel)
    val without = podcast()
    (without.path("media") as ObjectNode).remove("episodes")

    apply("item_updated", without)
    assertEquals(DetailLevel.STALE, row("p1")!!.detailLevel)
    assertTrue("episodes stay until a reconcile reads them", episodeIds("p1").isNotEmpty())
    assertEquals(listOf("p1"), runBlocking { db.items().podcastsWithoutEpisodes("srv", "lib1") })

    val fresh = podcast().put("id", "p2")
    (fresh.path("media") as ObjectNode).remove("episodes")
    apply("item_added", fresh)
    assertEquals(DetailLevel.STALE, row("p2")!!.detailLevel)
    assertEquals(setOf("p1", "p2"), runBlocking { db.items().podcastsWithoutEpisodes("srv", "lib1") }.toSet())
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

  private fun author(id: String) = runBlocking { db.authors().get("srv", id) }

  @Test
  fun authorAddedKeepsCount() {
    val added = json("""{"id":"a9","asin":null,"name":"New One","description":null,"imagePath":null,"libraryId":"lib1","addedAt":5,"updatedAt":5}""")
    assertEquals(CatalogEventResult.APPLIED, apply("author_added", added))
    assertEquals(setOf("authors:lib1"), topics.toSet())
    assertEquals(0, author("a9")!!.numBooks)
    assertEquals(0, json(author("a9")!!.json).path("numBooks").asInt(-1))
    assertNull("the next reconcile brings it", author("a9")!!.lastFirst)

    runBlocking { db.authors().upsertAll(listOf(authorRow("srv", "a1", name = "Old", numBooks = 5, updatedAt = 4).copy(lastFirst = "Old, A"))) }
    apply("author_added", json("""{"id":"a1","name":"Old","libraryId":"lib1","addedAt":1,"updatedAt":6}"""))
    assertEquals("an event without numBooks keeps the count", 5, author("a1")!!.numBooks)
    assertEquals(5, json(author("a1")!!.json).path("numBooks").asInt())
    assertEquals("Old, A", author("a1")!!.lastFirst)

    topics.clear()
    assertEquals(CatalogEventResult.IGNORED, apply("author_added", json("""{"id":"a8","name":"X","libraryId":"nowhere","updatedAt":5}""")))
    assertNull(author("a8"))
    assertTrue(topics.isEmpty())
  }

  @Test
  fun authorUpdatedUpsertsTheRowAndRenamesTheFilter() {
    runBlocking { db.authors().upsertAll(listOf(authorRow("srv", "a1", name = "Old", numBooks = 2, updatedAt = 10).copy(lastFirst = "Old"))) }
    val updated = json("""{"id":"a1","name":"New Name","libraryId":"lib1","imagePath":"/x.jpg","addedAt":1,"updatedAt":11,"numBooks":3}""")
    assertEquals(CatalogEventResult.APPLIED, apply("author_updated", updated))
    assertEquals(setOf("authors:lib1", "library:lib1"), topics.toSet())
    val row = author("a1")!!
    assertEquals("New Name", row.name)
    assertEquals(3, row.numBooks)
    assertEquals("/x.jpg", json(row.json).path("imagePath").asText())
    assertEquals("kept until the next reconcile", "Old", row.lastFirst)
    assertEquals("New Name", json(runBlocking { db.libraries().get("srv", "lib1") }!!.filterDataJson!!).path("authors")[0].path("name").asText())

    topics.clear()
    val delayed = json("""{"id":"a1","name":"Older","libraryId":"lib1","addedAt":1,"updatedAt":9,"numBooks":1}""")
    assertEquals(CatalogEventResult.IGNORED, apply("author_updated", delayed))
    assertEquals("New Name", author("a1")!!.name)
    assertEquals(3, author("a1")!!.numBooks)
    assertTrue(topics.isEmpty())
  }

  @Test
  fun numBooksEventUpdatesOnlyCounts() {
    runBlocking {
      db.authors().upsertAll(listOf(authorRow("srv", "a1", name = "Kept", numBooks = 2, updatedAt = 10), authorRow("srv", "a2", name = "Other", numBooks = 7)))
    }
    val payload = json("""{"libraryId":"lib1","authors":[{"id":"a1","numBooks":5},{"id":"unknown","numBooks":1}]}""")
    assertEquals(CatalogEventResult.APPLIED, apply("authors_num_books_updated", payload))
    assertEquals(setOf("authors:lib1"), topics.toSet())
    val row = author("a1")!!
    assertEquals(5, row.numBooks)
    assertEquals(5, json(row.json).path("numBooks").asInt())
    assertEquals("Kept", row.name)
    assertEquals("Kept", json(row.json).path("name").asText())
    assertEquals(10L, row.updatedAt)
    assertEquals("other authors are untouched", 7, author("a2")!!.numBooks)
    assertNull("unknown ids are skipped", author("unknown"))

    topics.clear()
    assertEquals(CatalogEventResult.IGNORED, apply("authors_num_books_updated", json("""{"libraryId":"lib1","authors":[{"id":"unknown","numBooks":1}]}""")))
    assertTrue(topics.isEmpty())
  }

  @Test
  fun authorRemovedDeletesRow() {
    runBlocking { db.authors().upsertAll(listOf(authorRow("srv", "a1"), authorRow("srv", "a2"))) }
    assertEquals(CatalogEventResult.APPLIED, apply("author_removed", json("""{"id":"a1","libraryId":"lib1"}""")))
    assertNull(author("a1"))
    assertNotNull(author("a2"))
    assertEquals(setOf("authors:lib1"), topics.toSet())
    topics.clear()
    assertEquals(CatalogEventResult.IGNORED, apply("author_removed", json("""{"id":"a1","libraryId":"lib1"}""")))
    assertTrue(topics.isEmpty())
  }

  @Test
  fun authorEventsAreCatalogEvents() {
    assertTrue(CATALOG_EVENTS.containsAll(listOf("author_added", "author_updated", "author_removed", "authors_num_books_updated")))
  }

  /** A collection event payload (`toOldJSONExpanded`): every book expanded, here the recorded expanded book with its id. */
  private fun collection(id: String, books: List<String>, lastUpdate: Long = 100, libraryId: String = "lib1", name: String = "Collection $id"): ObjectNode {
    val node = mapper.createObjectNode().put("id", id).put("libraryId", libraryId).put("name", name).putNull("description")
            .put("lastUpdate", lastUpdate).put("createdAt", 1)
    val list = node.putArray("books")
    books.forEach { list.add(item(it)) }
    return node
  }

  private fun collectionRow(id: String) = runBlocking { db.collections().get("srv", id) }
  private fun collectionBooks(id: String) = runBlocking { db.collections().itemIds("srv", id) }

  @Test
  fun collectionEventsReplaceBooksKeepPlaceAndSkipDelayed() {
    runBlocking { db.libraries().upsertAll(listOf(libraryRow("srv", "lib2", position = 1))) }
    assertEquals(CatalogEventResult.APPLIED, apply("collection_added", collection("c1", listOf("li3", "li1"))))
    assertEquals(CatalogEventResult.APPLIED, apply("collection_added", collection("c2", listOf("li2"))))
    assertEquals(setOf("collections:lib1"), topics.toSet())
    assertEquals(listOf("li3", "li1"), collectionBooks("c1"))
    assertEquals("a new collection goes last", listOf(0, 1), listOf(collectionRow("c1")!!.position, collectionRow("c2")!!.position))
    assertFalse("the expanded books are not kept in the row", json(collectionRow("c1")!!.json).has("books"))
    assertNull("nor as library items", row("li3"))

    topics.clear()
    assertEquals(CatalogEventResult.APPLIED, apply("collection_updated", collection("c1", listOf("li1", "li4", "li3"), lastUpdate = 200, name = "Renamed")))
    assertEquals(listOf("li1", "li4", "li3"), collectionBooks("c1"))
    assertEquals("an updated collection keeps its place", 0, collectionRow("c1")!!.position)
    assertEquals("Renamed", json(collectionRow("c1")!!.json).path("name").asText())
    assertEquals(setOf("collections:lib1"), topics.toSet())

    topics.clear()
    assertEquals("delayed behind a newer write", CatalogEventResult.IGNORED, apply("collection_updated", collection("c1", listOf("li9"), lastUpdate = 150)))
    assertEquals(listOf("li1", "li4", "li3"), collectionBooks("c1"))
    assertTrue(topics.isEmpty())

    assertEquals("a library the replica lacks", CatalogEventResult.IGNORED, apply("collection_added", collection("c3", listOf("li1"), libraryId = "nowhere")))
    assertNull(collectionRow("c3"))
    assertEquals(CatalogEventResult.APPLIED, apply("collection_added", collection("c4", listOf("li1"), libraryId = "lib2")))
    assertEquals("another library's collection is written to it", "lib2", collectionRow("c4")!!.libraryId)
    assertEquals(0, collectionRow("c4")!!.position)
    assertEquals(setOf("collections:lib2"), topics.toSet())
  }

  @Test
  fun collectionRemovedEventDeletesItems() {
    apply("collection_added", collection("c1", listOf("li1", "li2")))
    apply("collection_added", collection("c2", listOf("li1")))
    topics.clear()
    assertEquals(CatalogEventResult.APPLIED, apply("collection_removed", collection("c1", listOf("li1", "li2"))))
    assertNull(collectionRow("c1"))
    assertTrue(collectionBooks("c1").isEmpty())
    assertEquals("other collections keep their books", listOf("li1"), collectionBooks("c2"))
    assertEquals(setOf("collections:lib1"), topics.toSet())
    topics.clear()
    assertEquals("an unknown collection", CatalogEventResult.IGNORED, apply("collection_removed", json("""{"id":"c1","libraryId":"lib1"}""")))
    assertTrue(topics.isEmpty())
    assertTrue(CATALOG_EVENTS.containsAll(listOf("collection_added", "collection_updated", "collection_removed")))
  }

  /** A playlist event payload (`toOldJSONExpanded`): [items] are (libraryItemId, episodeId or null). */
  private fun playlist(id: String, items: List<Pair<String, String?>>, lastUpdate: Long = 100, libraryId: String = "lib1", name: String = "Playlist $id"): ObjectNode {
    val node = mapper.createObjectNode().put("id", id).put("name", name).put("libraryId", libraryId).put("userId", "u1").putNull("description")
            .put("lastUpdate", lastUpdate).put("createdAt", 1)
    val list = node.putArray("items")
    items.forEach { (itemId, episodeId) ->
      val entry = list.addObject()
      if (episodeId != null) {
        entry.put("episodeId", episodeId)
        entry.putObject("episode").put("id", episodeId).put("duration", 12.5).putObject("audioFile").put("duration", 12.5)
      }
      entry.put("libraryItemId", itemId)
      entry.set<JsonNode>("libraryItem", item(itemId))
    }
    return node
  }

  private fun playlistRow(id: String) = runBlocking { db.playlists().get("srv", id) }
  private fun playlistItems(id: String) = runBlocking { db.playlists().itemsOf("srv", id) }.map { it.libraryItemId to it.episodeId }

  @Test
  fun playlistAddedAnnouncesTheLibrary() {
    assertEquals(CatalogEventResult.APPLIED, apply("playlist_added", playlist("pl1", listOf("li1" to null))))
    assertEquals("the tab shows through library:<id>", setOf("playlists:lib1", "library:lib1"), topics.toSet())
    assertEquals(0, playlistRow("pl1")!!.position)
    assertFalse("the expanded items are not kept in the row", json(playlistRow("pl1")!!.json).has("items"))
    assertNull("nor as library items", row("li1"))
    assertEquals("a library the replica lacks", CatalogEventResult.IGNORED, apply("playlist_added", playlist("pl9", listOf("li1" to null), libraryId = "nowhere")))
    assertNull(playlistRow("pl9"))
  }

  @Test
  fun playlistUpdatedReplacesItems() {
    apply("playlist_added", playlist("pl1", listOf("li1" to null, "pod" to "ep1", "li2" to null)))
    apply("playlist_added", playlist("pl2", listOf("li1" to null)))
    assertEquals("a new playlist goes last", 1, playlistRow("pl2")!!.position)
    topics.clear()

    // Removed li1, moved the episode last; the server keeps lastUpdate when only the items change
    assertEquals(CatalogEventResult.APPLIED, apply("playlist_updated", playlist("pl1", listOf("li2" to null, "pod" to "ep1"))))
    assertEquals(listOf("li2" to "", "pod" to "ep1"), playlistItems("pl1"))
    assertEquals("an updated playlist keeps its place", 0, playlistRow("pl1")!!.position)
    val episode = runBlocking { db.playlists().itemsOf("srv", "pl1") }[1].episodeJson!!
    assertEquals(12.5, json(episode).path("audioFile").path("duration").asDouble(), 0.0)
    assertEquals("the count did not change", setOf("playlists:lib1"), topics.toSet())

    topics.clear()
    assertEquals("delayed behind a newer write", CatalogEventResult.IGNORED, apply("playlist_updated", playlist("pl1", listOf("li9" to null), lastUpdate = 99)))
    assertEquals(listOf("li2" to "", "pod" to "ep1"), playlistItems("pl1"))
    assertTrue(topics.isEmpty())

    assertEquals("an update the replica never saw the add of", CatalogEventResult.APPLIED, apply("playlist_updated", playlist("pl3", listOf("li1" to null))))
    assertEquals(2, playlistRow("pl3")!!.position)
    assertEquals(setOf("playlists:lib1", "library:lib1"), topics.toSet())
  }

  @Test
  fun playlistRemovedDeletesIt() {
    apply("playlist_added", playlist("pl1", listOf("li1" to null, "li2" to null)))
    apply("playlist_added", playlist("pl2", listOf("li1" to null)))
    topics.clear()
    assertEquals(CatalogEventResult.APPLIED, apply("playlist_removed", playlist("pl1", listOf("li1" to null))))
    assertNull(playlistRow("pl1"))
    assertTrue(playlistItems("pl1").isEmpty())
    assertEquals("other playlists keep their items", listOf("li1" to ""), playlistItems("pl2"))
    assertEquals(setOf("playlists:lib1", "library:lib1"), topics.toSet())
    topics.clear()
    assertEquals("an unknown playlist", CatalogEventResult.IGNORED, apply("playlist_removed", json("""{"id":"pl1","libraryId":"lib1"}""")))
    assertEquals("a page that read it from the server leaves", setOf("playlists:lib1"), topics.toSet())
    topics.clear()
    apply("playlist_removed", json("""{"id":"pl1","libraryId":"nowhere"}"""))
    apply("playlist_removed", json("""{"id":"pl1"}"""))
    assertTrue("no library, no notice", topics.isEmpty())
    assertTrue(CATALOG_EVENTS.containsAll(listOf("playlist_added", "playlist_updated", "playlist_removed")))
  }
}
