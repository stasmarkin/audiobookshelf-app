package app.absplus.android.db

import app.absplus.android.db.HomeFixtures.row
import app.absplus.android.db.replica.SearchInput
import app.absplus.android.db.replica.searchPattern
import app.absplus.android.db.replica.searchReplica
import app.absplus.android.sync.CatalogJson
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `GET /api/libraries/:id/search?q=&limit=12` of Audiobookshelf 2.37.1 (with the nusqlite3 unaccent extension) for the
 * libraries of [HomeFixtures], recorded by `~/scripts/one-off/abs-home-parity.ts` on 2026-10-17. Queries match fewer
 * than 12 entries per group (the server has no order there), so groups are compared as sets; tags and genres by count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplicaSearchParityTest {
  private val db = inMemoryReplica()

  @After
  fun tearDown() = db.close()

  private fun strings(node: JsonNode) = node.map { it.asText() }.toSet()

  @Test
  fun matchesRecordedServerAnswers() = runBlocking {
    db.items().upsertAll(HomeFixtures.bookItems + HomeFixtures.podcastItems)
    HomeFixtures.episodes.groupBy { it.libraryItemId }.forEach { (podcast, list) -> db.episodes().replaceForPodcast("srv", podcast, list) }
    val recorded = HomeFixtures.searchCases
    assertTrue("the server folded case and accents", recorded.path("unaccent").asBoolean())
    val cases = recorded.path("cases")
    assertEquals(18, cases.size())
    for (case in cases) {
      val name = case.path("name").asText()
      val podcast = case.path("mediaType").asText() == "podcast"
      val libraryId = if (podcast) HomeFixtures.podcastsLibraryId else HomeFixtures.booksLibraryId
      val q = case.path("q").asText()
      val pattern = searchPattern(q)!!
      val result = searchReplica(SearchInput(
              mediaType = if (podcast) "podcast" else "book", query = CatalogJson.normalizeForSearch(q), limit = 12,
              matchedItemIds = db.items().searchCandidates("srv", libraryId, pattern, 12).map { it.id },
              rows = db.items().rows("srv", libraryId), series = HomeFixtures.seriesIndex, authors = HomeFixtures.authors,
              narratorNames = HomeFixtures.narratorNames, matchedEpisodes = db.episodes().searchByTitle("srv", libraryId, pattern, 12)))
      if (podcast) {
        assertEquals("$name podcast", strings(case.path("podcast")), result.podcasts.toSet())
        assertEquals("$name episodes", case.path("episodes").map { it.path("podcastId").asText() to it.path("episodeId").asText() }.toSet(),
                result.episodes.toSet())
      } else {
        assertEquals("$name book", strings(case.path("book")), result.books.toSet())
        assertEquals("$name series", case.path("series").associate { it.path("id").asText() to strings(it.path("bookIds")) },
                result.series.associate { it.first to it.second.toSet() })
        assertEquals("$name authors", strings(case.path("authors")), result.authors.toSet())
        assertEquals("$name narrators", case.path("narrators").map { it.path("name").asText() to it.path("numBooks").asInt() }.toSet(),
                result.narrators.toSet())
      }
      assertEquals("$name tags", case.path("tags").map { it.path("name").asText() to it.path("numItems").asInt() }, result.tags)
      assertEquals("$name genres", case.path("genres").map { it.path("name").asText() to it.path("numItems").asInt() }, result.genres)
    }
  }
}
