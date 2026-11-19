package app.absplus.android.db

import app.absplus.android.db.replica.AuthorEntity
import app.absplus.android.db.replica.EpisodeKey
import app.absplus.android.db.replica.ItemRow
import app.absplus.android.db.replica.SearchInput
import app.absplus.android.db.replica.SearchResult
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.SeriesItemEntity
import app.absplus.android.db.replica.searchPattern
import app.absplus.android.db.replica.searchReplica
import app.absplus.android.sync.CatalogJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The replica's search: SQL candidates by search key, then the groups of [searchReplica]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplicaSearchTest {
  private val db = inMemoryReplica()

  @After
  fun tearDown() = db.close()

  private fun input(rows: List<ItemRow>, q: String, matched: List<String> = emptyList(), members: List<SeriesItemEntity> = emptyList(),
                    narrators: List<String> = emptyList(), episodes: List<EpisodeKey> = emptyList(), authors: List<AuthorEntity> = emptyList(),
                    mediaType: String = "book") = SearchInput(
          mediaType = mediaType, query = CatalogJson.normalizeForSearch(q), limit = 12, matchedItemIds = matched, rows = rows,
          series = SeriesIndex.of(members.map { it.seriesId }.distinct().map { seriesRow("srv", it, name = "Saga $it") }, members),
          authors = authors, narratorNames = narrators, matchedEpisodes = episodes)

  private fun titles(vararg titles: String) = runBlocking {
    db.items().upsertAll(titles.mapIndexed { i, t ->
      val e = itemEntity("srv", "li$i", title = t, addedAt = i.toLong())
      e.copy(searchKey = CatalogJson.searchKey(CatalogJson.mapper.readTree(e.json)))
    })
  }

  private fun found(q: String): List<String> = runBlocking {
    val pattern = searchPattern(q) ?: return@runBlocking emptyList()
    val ids = db.items().searchCandidates("srv", "lib1", pattern, 12).map { it.id }
    ids.map { db.items().get("srv", it)!!.title }
  }

  @Test
  fun normalizesLikeTheServerWithUnaccent() {
    titles("Книга", "Ёлка", "Café", "100% Pure", "a_b", "axb", "O'Brien", "Zebra")
    assertEquals(listOf("Книга"), found("книга"))
    assertEquals(listOf("Книга"), found("КНИГА"))
    assertEquals(listOf("Книга"), found("  книга  "))
    assertEquals(listOf("Ёлка"), found("елка"))
    assertEquals(listOf("Ёлка"), found("ЁЛКА"))
    assertEquals(listOf("Café"), found("cafe"))
    assertEquals(listOf("Café"), found("CAFÉ"))
    assertEquals("% is literal", listOf("100% Pure"), found("%"))
    assertEquals("_ is literal", listOf("a_b"), found("_"))
    assertEquals(listOf("O'Brien"), found("o'b"))
    assertEquals(emptyList<String>(), found("   "))
  }

  @Test
  fun emptyQueryHasNoPattern() {
    assertNull(searchPattern("   "))
    assertNull(searchPattern("\t"))
    assertNull("a lone combining mark is empty once normalized", searchPattern("\u0301"))
    assertEquals("1\u0001%a\\%%", searchPattern("a%"))
    assertEquals("1\u0001%a\\_b\\\\%", searchPattern("A_b\\"))
  }

  @Test
  fun episodesOfTwoPodcastsKeepTheirPodcast() {
    val rows = listOf(itemRow("p1", mediaType = "podcast"), itemRow("p2", mediaType = "podcast"))
    val result = searchReplica(input(rows, "deep", mediaType = "podcast",
            episodes = listOf(EpisodeKey("e1", "p2", null, 1), EpisodeKey("e2", "p1", null, 2), EpisodeKey("e3", "gone", null, 3))))
    assertEquals(listOf("p2" to "e1", "p1" to "e2"), result.episodes)
    assertTrue(result.series.isEmpty() && result.authors.isEmpty() && result.narrators.isEmpty() && result.books.isEmpty())
  }

  @Test
  fun seriesWithoutAccessibleBooksIsDropped() {
    val rows = listOf(itemRow("b1"))
    val result: SearchResult = searchReplica(input(rows, "saga",
            members = listOf(member("srv", "s1", "b1", "1"), member("srv", "s2", "hidden", "1"))))
    assertEquals(listOf("s1" to listOf("b1")), result.series)
  }

  @Test
  fun narratorWithACommaStaysWhole() {
    val rows = listOf(itemRow("b1", narratorName = "Doe, Jane"), itemRow("b2", narratorName = "Doe, Jane, Bob"), itemRow("b3", narratorName = "Ann"))
    val known = listOf("Doe, Jane", "Bob")
    assertEquals(listOf("Doe, Jane" to 2), searchReplica(input(rows, "jane", narrators = known)).narrators)
    assertEquals(listOf("Bob" to 1), searchReplica(input(rows, "bob", narrators = known)).narrators)
    assertEquals("a narrator not in filterdata yet", listOf("Ann" to 1), searchReplica(input(rows, "ann", narrators = known)).narrators)
    assertEquals("a known name without books", emptyList<Pair<String, Int>>(), searchReplica(input(rows, "zed", narrators = known + "Zed")).narrators)
  }

  @Test
  fun tagsAndGenresCountItemsAndSortByCount() {
    val rows = listOf(itemRow("b1", tags = """["fav","x"]""", genres = """["Fantasy"]"""), itemRow("b2", tags = """["fav"]""", genres = """["Fantasy","Fable"]"""),
            itemRow("b3", tags = """["favorite"]"""))
    val result = searchReplica(input(rows, "fa"))
    assertEquals(listOf("fav" to 2, "favorite" to 1), result.tags)
    assertEquals(listOf("Fantasy" to 2, "Fable" to 1), result.genres)
  }

  @Test
  fun narratorInsideAWholeNameIsNotCounted() {
    val rows = listOf(itemRow("b1", narratorName = "Doe, Jane"), itemRow("b2", narratorName = "Jane, Bob"), itemRow("b3", narratorName = "Doe, Jane, Jane"))
    val known = listOf("Doe", "Doe, Jane", "Jane", "Bob")
    assertEquals(listOf("Doe, Jane" to 2, "Jane" to 2), searchReplica(input(rows, "jane", narrators = known)).narrators)
    assertEquals("the longer name wins", listOf("Doe, Jane" to 2), searchReplica(input(rows, "doe", narrators = known)).narrators)
  }
}
