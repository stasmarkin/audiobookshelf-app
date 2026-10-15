package app.absplus.android.db

import app.absplus.android.db.replica.CatalogFilter
import app.absplus.android.db.replica.ItemRow
import app.absplus.android.db.replica.ItemsQuery
import app.absplus.android.db.replica.QueryEntry
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.UnsupportedFilterException
import app.absplus.android.db.replica.nocaseCompare
import app.absplus.android.db.replica.orderItems
import app.absplus.android.db.userdata.MediaProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class LibraryQueryTest {
  private fun query(sort: String? = "media.metadata.title", desc: Boolean = false, filter: CatalogFilter? = null, collapse: Boolean = false) =
          ItemsQuery(sort, desc, filter, collapse, sortingIgnorePrefix = false, mediaType = "book")

  private fun order(
          rows: List<ItemRow>,
          q: ItemsQuery,
          progress: Map<String, MediaProgressEntity> = emptyMap(),
          series: SeriesIndex = SeriesIndex.EMPTY,
          authors: Map<String, String> = emptyMap()
  ) = orderItems(rows, q, progress, series, authors, now = 10_000_000_000L, random = Random(1))

  private fun ids(entries: List<QueryEntry>) = entries.map { it.itemId }

  private fun progress(id: String, currentTime: Double = 0.0, isFinished: Boolean = false, ebookProgress: Double? = null,
                       lastUpdate: Long = 1L, finishedAt: Long? = null) = MediaProgressEntity(
          serverConfigId = "srv", libraryItemId = id, episodeId = "", serverProgressId = null, duration = 100.0, progress = 0.0,
          currentTime = currentTime, isFinished = isFinished, hideFromContinueListening = false, ebookLocation = null,
          ebookProgress = ebookProgress, lastUpdate = lastUpdate, startedAt = lastUpdate, finishedAt = finishedAt, source = "server",
          pending = false)

  @Test
  fun nocaseFoldsOnlyAscii() {
    assertTrue(nocaseCompare("apple", "Banana") < 0)
    assertEquals(0, nocaseCompare("APPLE", "apple"))
    assertTrue("NOCASE does not fold Cyrillic", nocaseCompare("Ёлка", "ёлка") != 0)
    assertTrue("code points, not UTF-16 units", nocaseCompare("�", "😀") < 0)
    assertTrue(nocaseCompare(null, "") < 0)
    assertTrue(nocaseCompare("ab", "abc") < 0)
  }

  @Test
  fun publishedYearCastsLikeSqlite() {
    val rows = listOf(itemRow("a", publishedYear = "2001-05"), itemRow("b", publishedYear = ""), itemRow("c", publishedYear = null),
            itemRow("d", publishedYear = "1999"))
    assertEquals(listOf("c", "b", "d", "a"), ids(order(rows, query("media.metadata.publishedYear"))))
    assertEquals(listOf("a", "d", "b", "c"), ids(order(rows, query("media.metadata.publishedYear", desc = true))))
  }

  @Test
  fun progressSortsPutRowsWithoutProgressLastBothWays() {
    val rows = listOf(itemRow("none"), itemRow("old"), itemRow("new"))
    val p = mapOf("old" to progress("old", lastUpdate = 1, finishedAt = 5, isFinished = true), "new" to progress("new", lastUpdate = 2))
    assertEquals(listOf("old", "new", "none"), ids(order(rows, query("progress"), p)))
    assertEquals(listOf("new", "old", "none"), ids(order(rows, query("progress", desc = true), p)))
    assertEquals(listOf("old", "new", "none").take(1), ids(order(rows, query("progress.finishedAt"), p)).take(1))
    assertEquals("none", ids(order(rows, query("progress.finishedAt", desc = true), p)).last())
  }

  @Test
  fun progressFiltersFollowTheServerRules() {
    val rows = listOf("none", "zero", "listening", "ebook", "done").map { itemRow(it) }
    val p = mapOf(
            "zero" to progress("zero"),
            "listening" to progress("listening", currentTime = 5.0),
            "ebook" to progress("ebook", ebookProgress = 0.4),
            "done" to progress("done", isFinished = true))
    val expected = mapOf(
            "finished" to setOf("done"),
            "in-progress" to setOf("listening", "ebook"),
            "not-started" to setOf("none", "zero", "ebook"),
            "not-finished" to setOf("none", "zero", "listening", "ebook"))
    for ((value, set) in expected) {
      assertEquals(value, set, ids(order(rows, query(filter = CatalogFilter("progress", value)), p)).toSet())
    }
  }

  @Test
  fun authorFilterMatchesWholeNamesOnly() {
    val rows = listOf(itemRow("a", authorName = "John Smithson"), itemRow("b", authorName = "Ann Lee, John Smith"),
            itemRow("c", authorName = "John Smith"))
    val result = order(rows, query(filter = CatalogFilter("authors", "au1")), authors = mapOf("au1" to "John Smith"))
    assertEquals(setOf("b", "c"), ids(result).toSet())
    assertTrue(order(rows, query(filter = CatalogFilter("authors", "unknown")), authors = mapOf("au1" to "John Smith")).isEmpty())
  }

  @Test
  fun collapseSeriesKeepsFirstBySequenceAndCountsAllMembers() {
    val rows = listOf(itemRow("1", title = "Zed one", genres = """["x"]"""), itemRow("2", title = "Zed two", genres = """["g"]"""),
            itemRow("3", title = "Zed three", genres = """["g"]"""), itemRow("4", title = "Alpha", genres = """["g"]"""),
            itemRow("5", title = "Omega", genres = """["g"]"""))
    val series = SeriesIndex.of(listOf(seriesRow("srv", "s1", name = "Middle")),
            listOf(member("srv", "s1", "1", "1"), member("srv", "s1", "2", "2"), member("srv", "s1", "3", null)))

    val result = order(rows, query(filter = CatalogFilter("genres", "g"), collapse = true), series = series)

    assertEquals(listOf("4", "2", "5"), ids(result))
    val card = result[1] as QueryEntry.Collapsed
    assertEquals("s1", card.seriesId)
    assertEquals(3, card.numBooks)
    assertEquals(listOf("2", "3"), card.libraryItemIds)
    assertEquals("2", card.sequence)
  }

  @Test
  fun sequenceSortOnlyWithSeriesFilter() {
    val rows = listOf(itemRow("1", title = "B"), itemRow("2", title = "A"))
    val series = SeriesIndex.of(listOf(seriesRow("srv", "s1")), listOf(member("srv", "s1", "1", "1"), member("srv", "s1", "2", "2")))
    assertEquals(listOf("2", "1"), ids(order(rows, query("sequence"), series = series)))
    assertEquals(listOf("1", "2"), ids(order(rows, query("sequence", filter = CatalogFilter("series", "s1")), series = series)))
  }

  @Test
  fun seriesFilterWithoutSortFollowsSequence() {
    val rows = listOf(itemRow("c", title = "C"), itemRow("b", title = "B"), itemRow("a", title = "A"))
    val series = SeriesIndex.of(listOf(seriesRow("srv", "s1")),
            listOf(member("srv", "s1", "c", "1"), member("srv", "s1", "b", "2"), member("srv", "s1", "a", "3")))
    val result = order(rows, query(sort = null, filter = CatalogFilter("series", "s1")), series = series)
    assertEquals(listOf("c", "b", "a"), ids(result))
    assertEquals("2", (result[1] as QueryEntry.Item).series?.sequence)
  }

  @Test
  fun unsupportedFilterThrows() {
    for (filter in listOf(CatalogFilter("ebooks", "supplementary"), CatalogFilter("missing", "asin"), CatalogFilter("share-open", null))) {
      try {
        order(listOf(itemRow("a")), query(filter = filter))
        fail("$filter must not be answered from the replica")
      } catch (expected: UnsupportedFilterException) {
      }
    }
  }

  @Test
  fun podcastsSortByAuthorWithEmptyLast() {
    val rows = listOf(itemRow("a", authorName = "", mediaType = "podcast"), itemRow("b", authorName = "Zed", mediaType = "podcast"),
            itemRow("c", authorName = "amy", mediaType = "podcast"))
    val q = ItemsQuery("media.metadata.author", false, null, false, false, "podcast")
    assertEquals(listOf("c", "b", "a"), ids(order(rows, q)))
    assertEquals(listOf("a", "b", "c"), ids(order(rows, q.copy(desc = true))))
  }
}
