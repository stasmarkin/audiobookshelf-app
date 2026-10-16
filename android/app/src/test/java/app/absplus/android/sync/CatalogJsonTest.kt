package app.absplus.android.sync

import app.absplus.android.db.replica.PlaylistItemEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures in resources/catalog were recorded from a synthetic library on Audiobookshelf 2.37.1. */
class CatalogJsonTest {
  private fun fixture(name: String): JsonNode =
          CatalogJson.mapper.readTree(javaClass.getResource("/catalog/$name") ?: error("no fixture $name"))

  @Test
  fun itemEntityExtractsSortAndFilterColumns() {
    val book = CatalogJson.itemEntity("srv", fixture("book-minified.json"))
    assertEquals("Мир: начало", book.title)
    assertEquals("Мир: начало", book.titleIgnorePrefix)
    assertEquals("John Smith", book.authorName)
    assertEquals("Smith, John", book.authorNameLF)
    assertEquals("", book.narratorName)
    assertEquals("Мир #1, Трилогия #3", book.seriesName)
    assertEquals("2001", book.publishedYear)
    assertEquals("""["Фэнтези","Fantasy"]""", book.genres)
    assertEquals("""["fav","?ü"]""", book.tags)
    assertEquals(1792076683024L, book.addedAt)
    assertEquals(7461L, book.size)
    assertEquals(7.0, book.duration, 0.0)
    assertEquals(1, book.numTracks)
    assertNull(book.ebookFormat)
    assertFalse(book.isMissing)
    assertNull(book.rssFeedJson)

    val podcast = CatalogJson.itemEntity("srv", fixture("podcast-minified.json"))
    assertEquals("Pod Author", podcast.authorName)
    assertEquals(3, podcast.numEpisodes)
    assertEquals("""["daily"]""", podcast.tags)
  }

  @Test
  fun expandedPayloadMinifiesToTheSameItem() {
    for ((expanded, minified) in listOf("book-expanded.json" to "book-minified.json", "podcast-expanded.json" to "podcast-minified.json")) {
      assertEquals(expanded, fixture(minified), CatalogJson.minifyItem(fixture(expanded)))
      assertEquals(expanded, CatalogJson.contentHash(fixture(minified)), CatalogJson.contentHash(fixture(expanded)))
    }
  }

  @Test
  fun oldExpandedPayloadGetsComputedCounts() {
    val media = CatalogJson.minifyItem(fixture("book-expanded-2.20.json")).path("media")
    assertEquals(1, media.path("numTracks").asInt(-1))
    assertEquals(0, media.path("numChapters").asInt(-1))
    assertEquals(1, media.path("numAudioFiles").asInt(-1))
    assertEquals(fixture("book-minified.json"), CatalogJson.minifyItem(fixture("book-expanded-2.20.json")))
  }

  @Test
  fun hashChangesWithFeedAndAuthorNameButNotWithKeyOrder() {
    val item = fixture("book-minified.json") as ObjectNode
    val reordered = CatalogJson.mapper.createObjectNode()
    item.fieldNames().asSequence().toList().reversed().forEach { reordered.set<JsonNode>(it, item.get(it)) }
    assertEquals(CatalogJson.contentHash(item), CatalogJson.contentHash(reordered))

    val renamed = item.deepCopy()
    (renamed.path("media").path("metadata") as ObjectNode).put("authorName", "Johnny Smith")
    assertNotEquals(CatalogJson.contentHash(item), CatalogJson.contentHash(renamed))

    val withFeed = item.deepCopy().put("rssFeed", null as String?).also {
      it.set<JsonNode>("rssFeed", CatalogJson.mapper.readTree("""{"id":"f1","entityType":"libraryItem","entityId":"x"}"""))
    }
    assertNotEquals(CatalogJson.contentHash(item), CatalogJson.contentHash(withFeed))
    assertTrue(CatalogJson.itemEntity("srv", withFeed).rssFeedJson!!.contains("\"f1\""))
    assertFalse("the feed is not part of the item JSON", CatalogJson.itemEntity("srv", withFeed).json.contains("rssFeed"))
  }

  @Test
  fun seriesPageGivesSeriesOnly() {
    val series = CatalogJson.seriesPage("srv", fixture("series-page.json"))
    assertEquals(listOf("Мир", "Трилогия", "The Saga", "Повесть"), series.map { it.name })
    val saga = series.single { it.name == "The Saga" }
    assertEquals("Saga, The", saga.nameIgnorePrefix)
    assertTrue(saga.addedAt > 0)
    assertFalse("books are not kept in the series row", saga.json.contains("\"books\""))
  }

  private val ids = mapOf("Мир" to listOf("s1"), "Трилогия" to listOf("s2"))

  @Test
  fun seriesNameParsesIntoMembership() {
    val table = mapOf(
            "Мир #1.5" to listOf("s1" to "1.5"),
            "Мир #2, Трилогия" to listOf("s1" to "2", "s2" to null),
            "Мир" to listOf("s1" to null),
            "" to emptyList(),
            "Мир #10" to listOf("s1" to "10"),
            "Трилогия #3, Мир #1" to listOf("s2" to "3", "s1" to "1"))
    for ((name, expected) in table) {
      assertEquals(name, SeriesNameParse.Parsed(expected), CatalogJson.parseSeriesName(name, ids))
    }
  }

  @Test
  fun ambiguousSeriesNameAsksForPointQueries() {
    val overlapping = mapOf("A" to listOf("a"), "A, B" to listOf("ab"), "B" to listOf("b"))
    assertEquals(SeriesNameParse.Ambiguous(setOf("a", "ab", "b")), CatalogJson.parseSeriesName("A, B #1", overlapping))

    val sameName = mapOf("Мир" to listOf("s1", "s9"))
    assertEquals(SeriesNameParse.Ambiguous(setOf("s1", "s9")), CatalogJson.parseSeriesName("Мир #2", sameName))

    assertEquals(SeriesNameParse.Ambiguous(emptySet(), unknownPart = true), CatalogJson.parseSeriesName("Unknown #1", ids))
    assertEquals(SeriesNameParse.Ambiguous(setOf("s1"), unknownPart = true), CatalogJson.parseSeriesName("Мир #1, Unknown", ids))
  }

  @Test
  fun candidatesStartASegment() {
    val names = mapOf("Saga I" to listOf("s1"), "I" to listOf("s2"), "The Big One" to listOf("s3"), "Big" to listOf("s4"))
    // "I" and "Big" occur only inside a segment, so they are no candidates
    val parse = CatalogJson.parseSeriesName("Saga I, The Big Ones #1", names)
    assertEquals(SeriesNameParse.Ambiguous(setOf("s1", "s3"), unknownPart = true), parse)
  }

  @Test
  fun seriesRowKeepsNoBooks() {
    val page = fixture("series-page.json")
    val series = CatalogJson.seriesEntity("srv", page.path("results").first())
    assertFalse(series.json.contains("\"books\""))
    assertTrue("the other fields stay", series.json.contains("\"nameIgnorePrefix\""))
    assertTrue("the answer itself is not changed", page.path("results").first().has("books"))
  }

  @Test
  fun expandedItemSeriesBecomeMembership() {
    val members = CatalogJson.seriesMembershipOf("srv", fixture("book-expanded.json"))
    assertEquals(listOf("Мир" to "1", "Трилогия" to "3").map { it.second }, members.map { it.sequence })
    assertEquals(2, members.map { it.seriesId }.toSet().size)
    assertTrue(members.all { it.libraryItemId == "ccbae3d4-63eb-4eff-85b2-911093527fa5" && it.serverConfigId == "srv" })
  }

  /**
   * `collections-page.json` is made from the server's code (2.37.1 LibraryController.getCollectionsForLibrary /
   * Collection.toOldJSONExpanded), not recorded.
   */
  @Test
  fun collectionEntityKeepsTheCollectionWithoutItsBooks() {
    val page = fixture("collections-page.json").path("results")
    val (row, bookIds) = CatalogJson.collectionEntity("srv", page[0], 7)
    assertEquals("col-2", row.id)
    assertEquals("lib1", row.libraryId)
    assertEquals(7, row.position)
    assertEquals(listOf("li-3", "li-1", "li-2"), bookIds)
    val json = CatalogJson.mapper.readTree(row.json)
    assertEquals(setOf("id", "libraryId", "name", "description", "lastUpdate", "createdAt"), json.fieldNames().asSequence().toSet())
    assertEquals(emptyList<String>(), CatalogJson.collectionEntity("srv", page[1], 0).second)
  }

  @Test
  fun previewItemIsWhatACoverReads() {
    val minified = CatalogJson.itemEntity("srv", fixture("collections-page.json").path("results")[0].path("books")[0]).json
    val preview = CatalogJson.previewItem(minified)
    assertEquals(CatalogJson.mapper.readTree("""{"id":"li-3","ino":"9003","libraryId":"lib1","mediaType":"book","updatedAt":1792076690403,
      "media":{"coverPath":"/metadata/items/li-3/cover.jpg","metadata":{"title":"Третья","authorName":"Лев Толстой"}}}"""), preview)
    // Fields the item lacks are left out; the media and metadata objects are always there
    assertEquals(CatalogJson.mapper.readTree("""{"id":"x","media":{"metadata":{}}}"""), CatalogJson.previewItem("""{"id":"x","path":"/a"}"""))
  }

  /**
   * `playlists-page.json` is made from the server's code (2.37.1 LibraryController.getUserPlaylistsForLibrary /
   * Playlist.toOldJSONExpanded) and the recorded podcast fixtures, not recorded.
   */
  @Test
  fun playlistEntityKeepsEpisodesWithoutTheExpandedItems() {
    val page = fixture("playlists-page.json").path("results")
    val (row, items) = CatalogJson.playlistEntity("srv", page[0], 3)
    assertEquals("pl-2", row.id)
    assertEquals("ad4b6377-f8af-48be-b9d0-2e8fe044ddca", row.libraryId)
    assertEquals(3, row.position)
    val json = CatalogJson.mapper.readTree(row.json)
    assertEquals(setOf("id", "name", "libraryId", "userId", "description", "lastUpdate", "createdAt"), json.fieldNames().asSequence().toSet())
    assertEquals(listOf("40c8825a-0d97-466e-aa72-1ee8604f2585", "c44a5356-8178-4775-b183-9a68c55525c4"), items.map { it.episodeId })
    assertEquals(listOf(0, 1), items.map { it.position })
    assertTrue(items.all { it.libraryItemId == "5448c89f-8d2f-40a2-b310-b53b53602982" && it.playlistId == "pl-2" && it.serverConfigId == "srv" })
    val episode = CatalogJson.mapper.readTree(items[0].episodeJson)
    assertEquals("Episode 3", episode.path("title").asText())
    assertTrue(episode.path("audioFile").path("duration").isNumber)

    val book = CatalogJson.mapper.readTree("""{"id":"p","libraryId":"l","items":[{"libraryItemId":"b1","libraryItem":{"id":"b1"}},{"libraryItemId":""}]}""")
    val (_, bookItems) = CatalogJson.playlistEntity("srv", book, 0)
    assertEquals("a book has no episode; an entry without an item is dropped", listOf(PlaylistItemEntity("srv", "p", 0, "b1", "", null)), bookItems)
  }
}
