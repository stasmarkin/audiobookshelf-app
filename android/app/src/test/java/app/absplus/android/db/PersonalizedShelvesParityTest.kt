package app.absplus.android.db

import app.absplus.android.db.HomeFixtures.row
import app.absplus.android.db.replica.Shelf
import app.absplus.android.db.replica.ShelfEntity
import app.absplus.android.db.replica.ShelvesInput
import app.absplus.android.db.replica.personalizedShelves
import com.fasterxml.jackson.databind.JsonNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/libraries/:id/personalized` of a real server for the same synthetic libraries, recorded by
 * `~/scripts/one-off/abs-home-parity.ts` (`npx tsx abs-home-parity.ts http://127.0.0.1:13380 absplus-parity-abs
 * <resources>/catalog/home`) on 2026-10-17 from Audiobookshelf 2.37.1 in Docker: 26 books in 8 series (one book in two,
 * a hidden series, a blocked one, single-book and unstarted ones), two ebook-only books, progress in every state (hidden,
 * finished, ebook), and 2 podcasts of 3 episodes with episode progress; limits 10 and 3, `onlyShowLaterBooksInContinueSeries`
 * and `hideSingleBookSeries`. Discover is random on the server: only its invariants are compared.
 */
class PersonalizedShelvesParityTest {
  private fun input(case: JsonNode, now: Long, limit: Int = case.path("limit").asInt()): ShelvesInput {
    val podcast = case.path("mediaType").asText() == "podcast"
    val settings = case.path("settings")
    return ShelvesInput(
            mediaType = if (podcast) "podcast" else "book", now = now, limit = limit,
            rows = (if (podcast) HomeFixtures.podcastItems else HomeFixtures.bookItems).map { it.row() },
            progress = HomeFixtures.progress, series = HomeFixtures.seriesIndex, hiddenSeriesIds = HomeFixtures.hiddenSeriesIds,
            onlyLaterBooksInContinueSeries = settings.path("onlyShowLaterBooksInContinueSeries").asBoolean(),
            hideSingleBookSeries = settings.path("hideSingleBookSeries").asBoolean(),
            authors = HomeFixtures.authors, episodes = HomeFixtures.episodeKeys, discoverSeed = 42)
  }

  private fun keyOf(entity: ShelfEntity): List<Any?> = when (entity) {
    is ShelfEntity.Item -> listOf(entity.itemId, entity.episodeId, entity.seriesMember?.seriesId, entity.seriesMember?.sequence)
    is ShelfEntity.Series -> listOf(entity.seriesId, entity.bookIds)
    is ShelfEntity.Author -> listOf(entity.authorId)
  }

  private fun expectedKey(shelf: JsonNode, e: JsonNode): List<Any?> = when (shelf.path("type").asText()) {
    "series" -> listOf(e.path("id").asText(), e.path("bookIds").map { it.asText() })
    "authors" -> listOf(e.path("id").asText())
    else -> listOf(e.path("id").asText(), e.path("episodeId").takeIf { it.isTextual }?.asText(),
            e.path("seriesId").takeIf { it.isTextual }?.asText(), e.path("sequence").takeIf { it.isTextual }?.asText())
  }

  private val episodeAddedAt = HomeFixtures.episodeKeys.associate { it.id to it.addedAt }

  /**
   * Episodes of one podcast are created in one batch with the same createdAt, and the server orders such ties at will:
   * runs of equal keys are compared as sets.
   */
  private fun canonical(shelfId: String, keys: List<List<Any?>>): List<Any> {
    if (shelfId != "newest-episodes") return keys
    val runs = ArrayList<MutableList<List<Any?>>>()
    var last: Long? = null
    for (key in keys) {
      val addedAt = episodeAddedAt.getValue(key[1] as String)
      if (addedAt != last || runs.isEmpty()) runs += mutableListOf<List<Any?>>()
      runs.last() += key
      last = addedAt
    }
    return runs.map { run -> run.map { it.toString() }.toSet() }
  }

  @Test
  fun matchesRecordedServerShelves() {
    val recorded = HomeFixtures.cases
    assertEquals("2.37.1", recorded.path("server").asText())
    val now = recorded.path("recordedAt").asLong()
    val cases = recorded.path("cases")
    assertEquals(6, cases.size())
    for (case in cases) {
      val name = case.path("name").asText()
      val ours: List<Shelf> = personalizedShelves(input(case, now))
      val expected = case.path("shelves")
      assertEquals("$name: shelves", expected.map { it.path("id").asText() }, ours.map { it.id })
      for ((i, shelf) in expected.withIndex()) {
        val mine = ours[i]
        val where = "$name/${mine.id}"
        assertEquals("$where label", shelf.path("label").asText(), mine.label)
        assertEquals("$where labelStringKey", shelf.path("labelStringKey").asText(), mine.labelStringKey)
        assertEquals("$where type", shelf.path("type").asText(), mine.type)
        assertEquals("$where total", shelf.path("total").asInt(), mine.total)
        val keys = shelf.path("entities").map { expectedKey(shelf, it) }
        if (mine.id == "discover") {
          val all = personalizedShelves(input(case, now, limit = 1000)).first { it.id == "discover" }.entities.map(::keyOf).toSet()
          assertEquals("$where size", keys.size, mine.entities.size)
          assertTrue("$where: the server's picks are candidates: $keys", all.containsAll(keys))
          assertTrue("$where: ours are candidates", all.containsAll(mine.entities.map(::keyOf)))
        } else {
          assertEquals(where, canonical(mine.id, keys), canonical(mine.id, mine.entities.map(::keyOf)))
        }
      }
    }
  }
}
