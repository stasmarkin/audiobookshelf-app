package app.absplus.android.db

import app.absplus.android.db.replica.AuthorEntity
import app.absplus.android.db.replica.EpisodeKey
import app.absplus.android.db.replica.ItemRow
import app.absplus.android.db.replica.RECENT_WINDOW_MS
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.SeriesItemEntity
import app.absplus.android.db.replica.Shelf
import app.absplus.android.db.replica.ShelfEntity
import app.absplus.android.db.replica.ShelvesInput
import app.absplus.android.db.replica.personalizedShelves
import app.absplus.android.db.userdata.MediaProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizedShelvesTest {
  private val now = 1_000_000_000_000L

  private fun book(id: String, numTracks: Int = 1, ebookFormat: String? = null, addedAt: Long = now): ItemRow =
          itemRow(id, addedAt = addedAt, ebookFormat = ebookFormat).copy(numTracks = numTracks)

  private fun progress(itemId: String, lastUpdate: Long, currentTime: Double = 0.0, isFinished: Boolean = false, hidden: Boolean = false,
                       ebookProgress: Double? = null, episodeId: String = "") = MediaProgressEntity(
          serverConfigId = "srv", libraryItemId = itemId, episodeId = episodeId, serverProgressId = null, duration = 100.0,
          progress = 0.0, currentTime = currentTime, isFinished = isFinished, hideFromContinueListening = hidden,
          ebookLocation = null, ebookProgress = ebookProgress, lastUpdate = lastUpdate, startedAt = 0, finishedAt = null,
          source = "server", pending = false)

  private fun input(rows: List<ItemRow>, progress: List<MediaProgressEntity> = emptyList(), members: List<SeriesItemEntity> = emptyList(),
                    limit: Int = 10, mediaType: String = "book", hidden: Set<String> = emptySet(), onlyLater: Boolean = false,
                    episodes: List<EpisodeKey> = emptyList(), seed: Long = 1, authors: List<AuthorEntity> = emptyList()) = ShelvesInput(
          mediaType = mediaType, now = now, limit = limit, rows = rows, progress = progress,
          series = SeriesIndex.of(members.map { it.seriesId }.distinct().map { seriesRow("srv", it).copy(addedAt = 0) }, members),
          hiddenSeriesIds = hidden, onlyLaterBooksInContinueSeries = onlyLater, hideSingleBookSeries = false, authors = authors,
          episodes = episodes, discoverSeed = seed)

  private fun List<Shelf>.ids(shelf: String) = firstOrNull { it.id == shelf }?.entities?.map { (it as ShelfEntity.Item).itemId }

  @Test
  fun progressWithoutReplicaItemIsSkipped() {
    val shelves = personalizedShelves(input(listOf(book("b1", addedAt = 0)),
            listOf(progress("gone", 9, currentTime = 5.0), progress("b1", 5, currentTime = 5.0), progress("gone2", 8, isFinished = true))))
    assertEquals(listOf("b1"), shelves.ids("continue-listening"))
    assertEquals(1, shelves.first { it.id == "continue-listening" }.total)
    assertNull(shelves.ids("listen-again"))
  }

  @Test
  fun episodeProgressWithoutEpisodeIsSkipped() {
    val shelves = personalizedShelves(input(listOf(book("p1", addedAt = 0).copy(mediaType = "podcast")),
            listOf(progress("p1", 9, currentTime = 5.0, episodeId = "missing"), progress("p1", 5, currentTime = 5.0, episodeId = "e1")),
            mediaType = "podcast", episodes = listOf(EpisodeKey("e1", "p1", null, 0))))
    val shelf = shelves.first { it.id == "continue-listening" }
    assertEquals(listOf(ShelfEntity.Item("p1", "e1")), shelf.entities)
    assertEquals(1, shelf.total)
    assertEquals("episode", shelf.type)
  }

  @Test
  fun audioAndEbookShareOneLimit() {
    val rows = listOf(book("a1", addedAt = 0), book("a2", addedAt = 0), book("e1", numTracks = 0, ebookFormat = "epub", addedAt = 0),
            book("x1", numTracks = 0, addedAt = 0))
    val shelves = personalizedShelves(input(rows, listOf(progress("a1", 40, 5.0), progress("e1", 30, ebookProgress = 0.2),
            progress("x1", 20, 5.0), progress("a2", 10, 5.0)), limit = 3))
    assertEquals(listOf("a1"), shelves.ids("continue-listening"))
    assertEquals(listOf("e1"), shelves.ids("continue-reading"))
    assertEquals(4, shelves.first { it.id == "continue-listening" }.total)
    assertEquals(4, shelves.first { it.id == "continue-reading" }.total)
  }

  @Test
  fun audioAndEbookSplitAfterTheLimit() {
    val rows = listOf(book("a1", addedAt = 0), book("a2", addedAt = 0), book("e1", numTracks = 0, ebookFormat = "epub", addedAt = 0),
            book("x1", numTracks = 0, addedAt = 0))
    val shelves = personalizedShelves(input(rows, listOf(progress("a1", 40, 5.0), progress("a2", 35, 5.0),
            progress("e1", 30, ebookProgress = 0.2), progress("x1", 20, 5.0)), limit = 3))
    assertEquals(listOf("a1", "a2"), shelves.ids("continue-listening"))
    assertEquals(listOf("e1"), shelves.ids("continue-reading"))
  }

  private fun continueSeries(sequences: List<Pair<String?, String>>, onlyLater: Boolean = false, hidden: Set<String> = emptySet()): ShelfEntity.Item? {
    // (sequence, state): f finished, p in progress, - nothing
    val rows = sequences.indices.map { book("b$it", addedAt = 0) }
    val members = sequences.mapIndexed { i, (seq, _) -> member("srv", "s1", "b$i", seq) }
    val progress = sequences.mapIndexedNotNull { i, (_, state) ->
      when (state) {
        "f" -> progress("b$i", 10L + i, isFinished = true)
        "p" -> progress("b$i", 10L + i, currentTime = 5.0)
        else -> null
      }
    }
    val shelf = personalizedShelves(input(rows, progress, members, onlyLater = onlyLater, hidden = hidden)).firstOrNull { it.id == "continue-series" }
    return shelf?.entities?.single() as ShelfEntity.Item?
  }

  private fun picked(sequences: List<Pair<String?, String>>, onlyLater: Boolean = false) =
          continueSeries(sequences, onlyLater)?.seriesMember?.sequence

  @Test
  fun continueSeriesPicksLowestUnfinishedSequence() {
    assertEquals("2", picked(listOf("1" to "f", "2" to "-", "10" to "-")))
    assertEquals("abc casts to 0", "abc", picked(listOf("1" to "f", "2" to "-", "abc" to "-")))
    assertNull("a member in progress blocks the series", picked(listOf("1" to "f", "2" to "p", "3" to "-")))
    assertNull("a hidden series", continueSeries(listOf("1" to "f", "2" to "-"), hidden = setOf("s1")))
    assertEquals("4", picked(listOf("3" to "f", "2" to "-", "4" to "-"), onlyLater = true))
    assertEquals("the highest finished is MAX of the text, \"9\", not 10", "9.5", picked(listOf("10" to "f", "9" to "f", "5" to "-", "9.5" to "-"), onlyLater = true))
    assertEquals("parseFloat(\"4a\") is 4", "4a", picked(listOf("3" to "f", "4a" to "-"), onlyLater = true))
    assertEquals("parseFloat(\"4-5\") is 4", "4-5", picked(listOf("3" to "f", "4-5" to "-"), onlyLater = true))
    assertNull("\"abc\" and null are never later", picked(listOf("3" to "f", "abc" to "-", null to "-"), onlyLater = true))
    assertEquals("4E2", picked(listOf("10" to "f", "4E2" to "-"), onlyLater = true))
    assertNull("finished books without a sequence", picked(listOf(null to "f", "2" to "-"), onlyLater = true))
    assertEquals("without onlyLater they do not matter", "2", picked(listOf(null to "f", "2" to "-")))
  }

  @Test
  fun ebookProgressDoesNotBlockTheSeries() {
    val rows = listOf(book("b0", addedAt = 0), book("b1", addedAt = 0))
    val members = listOf(member("srv", "s1", "b0", "1"), member("srv", "s1", "b1", "2"))
    val shelves = personalizedShelves(input(rows, listOf(progress("b0", 1, isFinished = true), progress("b1", 2, ebookProgress = 0.4)), members))
    assertEquals(listOf("b1"), shelves.ids("continue-series"))
  }

  @Test
  fun discoverIsStableForTheSameSeed() {
    val rows = (1..30).map { book("b%02d".format(it), addedAt = 0) }
    val first = personalizedShelves(input(rows, seed = 7)).ids("discover")
    assertEquals(first, personalizedShelves(input(rows.shuffled(), seed = 7)).ids("discover"))
    assertNotEquals(first, personalizedShelves(input(rows, seed = 8)).ids("discover"))
    assertEquals(10, first!!.size)
    assertEquals(30, personalizedShelves(input(rows, seed = 7)).first { it.id == "discover" }.total)
  }

  @Test
  fun recentWindowIsInclusive() {
    val rows = listOf(book("edge", addedAt = now - RECENT_WINDOW_MS), book("old", addedAt = now - RECENT_WINDOW_MS - 1))
    assertEquals(listOf("edge"), personalizedShelves(input(rows)).ids("recently-added"))
  }

  @Test
  fun emptyShelvesAreOmitted() {
    assertTrue(personalizedShelves(input(emptyList())).isEmpty())
    val shelves = personalizedShelves(input(listOf(book("b1", addedAt = 0)), listOf(progress("b1", 1, 5.0))))
    assertEquals(listOf("continue-listening"), shelves.map { it.id })
    assertFalse(shelves.any { it.entities.isEmpty() })
  }
}
