package app.absplus.android.db

import app.absplus.android.db.replica.FilterCodec
import app.absplus.android.db.replica.ItemRow
import app.absplus.android.db.replica.ItemsQuery
import app.absplus.android.db.replica.LibraryItemEntity
import app.absplus.android.db.replica.QueryEntry
import app.absplus.android.db.replica.SeriesEntity
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.SeriesItemEntity
import app.absplus.android.db.replica.orderItems
import app.absplus.android.db.replica.prepareItems
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.sync.CatalogJson
import com.fasterxml.jackson.databind.JsonNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Answers of a real server for the same synthetic library, recorded by `~/scripts/one-off/abs-catalog-parity.ts`
 * (`npx tsx abs-catalog-parity.ts http://127.0.0.1:13380 <resources>/catalog/parity`) on 2026-10-15 from
 * Audiobookshelf 2.37.1 in Docker: 50 books with series (one book in two), authors sharing a name prefix, Cyrillic,
 * empty and partial published years, an ebook, an open feed, explicit/abridged flags and the user's progress.
 * Keys the server leaves unordered (ties, random) are compared by membership only.
 */
class LibraryQueryParityTest {
  private fun fixture(name: String): JsonNode =
          CatalogJson.mapper.readTree(javaClass.getResource("/catalog/parity/$name") ?: error("no fixture $name"))

  private fun LibraryItemEntity.row() = ItemRow(serverConfigId, id, libraryId, mediaType, updatedAt, contentHash, title,
          titleIgnorePrefix, authorName, authorNameLF, narratorName, seriesName, publisher, language, publishedYear, explicit,
          abridged, genres, tags, addedAt, size, duration, birthtimeMs, mtimeMs, numTracks, numEpisodes, ebookFormat, isMissing,
          isInvalid, rssFeedJson, detailLevel)

  private val rows = fixture("items.json").path("results").map { CatalogJson.itemEntity("srv", it).row() }
  private val authors = fixture("filterdata.json").path("authors").associate { it.path("id").asText() to it.path("name").asText() }
  private val series = fixture("series.json").let { list ->
    SeriesIndex.of(
            list.map { SeriesEntity("srv", it.path("id").asText(), "lib", it.path("name").asText(), it.path("nameIgnorePrefix").asText(),
                    it.path("addedAt").asLong(), 0, "{}") },
            list.flatMap { s ->
              s.path("members").map { m ->
                SeriesItemEntity("srv", s.path("id").asText(), m.path("libraryItemId").asText(),
                        m.path("sequence").takeIf { !it.isNull }?.asText())
              }
            })
  }
  private val progress = fixture("progress.json").associate {
    it.path("libraryItemId").asText() to MediaProgressEntity(
            serverConfigId = "srv", libraryItemId = it.path("libraryItemId").asText(), episodeId = "", serverProgressId = null,
            duration = it.path("duration").asDouble(), progress = it.path("progress").asDouble(),
            currentTime = it.path("currentTime").asDouble(), isFinished = it.path("isFinished").asBoolean(),
            hideFromContinueListening = false, ebookLocation = null,
            ebookProgress = it.path("ebookProgress").takeIf { n -> !n.isNull && !n.isMissingNode }?.asDouble(),
            lastUpdate = it.path("lastUpdate").asLong(), startedAt = it.path("startedAt").asLong(),
            finishedAt = it.path("finishedAt").takeIf { n -> !n.isNull && !n.isMissingNode }?.asLong(), source = "server",
            pending = false)
  }

  @Test
  fun matchesRecordedServerAnswers() {
    val recorded = fixture("cases.json")
    assertEquals("2.37.1", recorded.path("server").asText())
    val now = recorded.path("recordedAt").asLong()
    val cases = recorded.path("cases")
    assertTrue(cases.size() > 60)
    for (case in cases) {
      val name = case.path("name").asText()
      val query = case.path("query")
      val q = ItemsQuery(
              sort = query.path("sort").takeIf { !it.isNull }?.asText(),
              desc = query.path("desc").asBoolean(),
              filter = FilterCodec.parse(query.path("filter").takeIf { !it.isNull }?.asText()),
              collapseSeries = query.path("collapseseries").asBoolean(),
              sortingIgnorePrefix = query.path("sortingIgnorePrefix").asBoolean(),
              mediaType = "book")
      val expected = case.path("ids").map { it.asText() }
      val ours = orderItems(rows, q, progress, series, authors, now, Random(7))

      assertEquals("$name: total", case.path("total").asInt(), ours.size)
      assertEquals("$name: the same items", expected.toSet(), ours.map { it.itemId }.toSet())

      val collapsedExpected = case.path("collapsed").map {
        listOf(it.path("itemId").asText(), it.path("seriesId").asText(), it.path("numBooks").asInt(),
                it.path("libraryItemIds").map { id -> id.asText() }.toSet(), it.path("sequence").takeIf { s -> !s.isNull }?.asText())
      }.toSet()
      val collapsedOurs = ours.filterIsInstance<QueryEntry.Collapsed>()
              .map { listOf(it.itemId, it.seriesId, it.numBooks, it.libraryItemIds.toSet(), it.sequence) }.toSet()
      assertEquals("$name: collapsed series", collapsedExpected, collapsedOurs)

      if (q.sort == "random") continue
      // The server's order must be one our sort keys allow: only entries with equal keys may be swapped
      val prepared = prepareItems(rows, q, progress, series, authors, now)
      val byId = prepared.entries.associateBy { it.entry.itemId }
      expected.zipWithNext().forEach { (a, b) ->
        assertTrue("$name: $a before $b", prepared.primary.compare(byId.getValue(a), byId.getValue(b)) <= 0)
      }
    }
  }
}
