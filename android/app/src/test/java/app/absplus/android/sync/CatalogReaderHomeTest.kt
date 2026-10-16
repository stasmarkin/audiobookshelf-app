package app.absplus.android.sync

import app.absplus.android.db.HomeFixtures
import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.replica.RECENT_WINDOW_MS
import app.absplus.android.db.userdata.MediaProgressEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home and search of [CatalogReader] over the recorded libraries of [HomeFixtures]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogReaderHomeTest {
  private val db = inMemoryReplica()
  private val books = HomeFixtures.booksLibraryId
  private val pods = HomeFixtures.podcastsLibraryId
  private var now = HomeFixtures.cases.path("recordedAt").asLong()
  private var progressRows: List<MediaProgressEntity> = HomeFixtures.progress
  private var progressCalls = 0
  private var version = 0L
  private var user: String? = HomeFixtures.user.toString()
  private val reader = CatalogReader(db, { progressCalls++; progressRows }, { _, ids -> progressRows.filter { it.libraryItemId in ids } },
          { version }, { now }, { user })

  private fun state(libraryId: String, items: Long? = 10, series: Long? = 20, authors: Long? = 30, episodes: Long? = 40) =
          LibraryStateEntity("srv", libraryId, items, true, series, authors, null, null, null, "[]", episodes)

  @Before
  fun setUp() = runBlocking {
    val settings = """"settings":{"onlyShowLaterBooksInContinueSeries":false,"hideSingleBookSeries":false}"""
    db.libraries().upsertAll(listOf(
            libraryRow("srv", books).copy(json = """{"id":"$books","mediaType":"book",$settings}""",
                    filterDataJson = HomeFixtures.json("filterdata.json").toString()),
            libraryRow("srv", pods, 1, "podcast").copy(json = """{"id":"$pods","mediaType":"podcast"}""")))
    db.items().upsertAll(HomeFixtures.bookItems + HomeFixtures.podcastItems)
    db.series().upsertAll(HomeFixtures.series)
    db.series().insertMembers(HomeFixtures.members)
    db.authors().upsertAll(HomeFixtures.authors)
    HomeFixtures.episodes.groupBy { it.libraryItemId }.forEach { (podcast, list) -> db.episodes().replaceForPodcast("srv", podcast, list) }
    db.libraryState().upsert(state(books))
    db.libraryState().upsert(state(pods))
  }

  @After
  fun tearDown() = db.close()

  private fun home(libraryId: String = books, limit: Int = 10, sc: String = "srv") = runBlocking { reader.personalized(sc, PersonalizedRequest(libraryId, limit)) }

  private fun search(q: String, libraryId: String = books, sc: String = "srv") = runBlocking { reader.search(sc, SearchRequest(libraryId, q, 12)) }

  private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

  private fun shelf(answer: JSONObject, id: String) = answer.getJSONArray("shelves").objects().firstOrNull { it.getString("id") == id }

  private fun ids(answer: JSONObject, id: String) = shelf(answer, id)?.getJSONArray("entities")?.objects()?.map { it.getString("id") } ?: emptyList()

  private fun titleOf(id: String) = (HomeFixtures.bookItems + HomeFixtures.podcastItems).first { it.id == id }.title

  private fun idOf(title: String) = HomeFixtures.bookItems.first { it.title == title }.id

  private fun JSONObject.keySet() = keys().asSequence().toSet()

  @Test
  fun shelvesHaveTheServerShape() {
    val samples = HomeFixtures.cases.path("cases")
    for ((caseIndex, libraryId) in listOf(0 to books, 4 to pods)) {
      val sample = samples[caseIndex].path("sample")
      val answer = home(libraryId)
      assertEquals(setOf("serverConfigId", "libraryId", "shelves", "replica"), answer.keySet())
      for (shelf in answer.getJSONArray("shelves").objects()) {
        val id = shelf.getString("id")
        assertEquals(setOf("id", "label", "labelStringKey", "type", "entities", "total"), shelf.keySet())
        val ours = shelf.getJSONArray("entities").getJSONObject(0)
        val theirs = sample.path(id)
        val theirKeys = theirs.fieldNames().asSequence().toSet()
        when (shelf.getString("type")) {
          "series" -> {
            assertTrue(ours.keySet().containsAll(listOf("id", "name", "libraryId", "addedAt", "books")))
            assertEquals(theirs.path("books").size(), ours.getJSONArray("books").length())
          }
          "authors" -> assertEquals("$id: $theirKeys", theirKeys - "lastFirst", ours.keySet() - "lastFirst")
          else -> {
            assertEquals("$id keys", theirKeys, ours.keySet())
            assertEquals("$id media keys", theirs.path("media").fieldNames().asSequence().toSet(), ours.getJSONObject("media").keySet())
            if (shelf.getString("type") == "episode") {
              assertEquals(theirs.path("recentEpisode").path("id").asText().isNotEmpty(), ours.getJSONObject("recentEpisode").has("id"))
              assertTrue(ours.getJSONObject("recentEpisode").has("audioFile"))
            }
          }
        }
        if (id == "continue-series") {
          val series = ours.getJSONObject("media").getJSONObject("metadata").getJSONObject("series")
          assertEquals(setOf("id", "name", "sequence"), series.keySet())
          assertEquals(theirs.path("media").path("metadata").path("series").path("name").asText(), series.getString("name"))
        }
      }
    }
  }

  @Test
  fun progressChangeRecomputesTheShelves() {
    val first = home()
    assertEquals(1, progressCalls)
    home()
    assertEquals("from the cache", 1, progressCalls)
    val quiet = idOf("Quiet Discovery")
    progressRows = progressRows + HomeFixtures.progress.first().copy(libraryItemId = quiet, isFinished = false, currentTime = 5.0, lastUpdate = now)
    home()
    assertEquals("the same progress version: still the cache", 1, progressCalls)
    version++
    val second = home()
    assertEquals(2, progressCalls)
    assertFalse(quiet in ids(first, "continue-listening"))
    assertEquals(quiet, ids(second, "continue-listening").first())
  }

  @Test
  fun podcastLibraryWithPendingEpisodesReadsAsNotReconciled() = runBlocking {
    db.libraryState().upsert(state(pods, episodes = null))
    assertTrue(home(pods).getJSONObject("replica").isNull("reconciledAt"))
    assertTrue(search("deep", pods).getJSONObject("replica").isNull("reconciledAt"))
    db.libraryState().upsert(state(pods, items = 10, episodes = 5))
    assertEquals(5L, home(pods).getJSONObject("replica").getLong("reconciledAt"))
    assertEquals(5L, search("deep", pods).getJSONObject("replica").getLong("reconciledAt"))
  }

  @Test
  fun bookLibraryIsReadyOnlyWithSeriesAndAuthors() = runBlocking {
    db.libraryState().upsert(state(books, authors = null))
    assertTrue(home().getJSONObject("replica").isNull("reconciledAt"))
    assertTrue(search("alpha").getJSONObject("replica").isNull("reconciledAt"))
    db.libraryState().upsert(state(books, series = null))
    assertTrue(home().getJSONObject("replica").isNull("reconciledAt"))
    db.libraryState().upsert(state(books, items = 50, series = 20, authors = 30))
    assertEquals("the oldest of the three", 20L, home().getJSONObject("replica").getLong("reconciledAt"))
    assertEquals(20L, search("alpha").getJSONObject("replica").getLong("reconciledAt"))
  }

  private fun hiddenArcBook() = idOf("Hidden Second")

  @Test
  fun hiddenSeriesComeFromTheCachedUser() {
    assertFalse(hiddenArcBook() in ids(home(), "continue-series"))
    user = null
    version++
    assertTrue(hiddenArcBook() in ids(home(), "continue-series"))
  }

  @Test
  fun hiddenSeriesChangeBypassesTheCache() {
    val withHidden = user
    user = null
    assertTrue(hiddenArcBook() in ids(home(), "continue-series"))
    user = withHidden
    assertFalse(hiddenArcBook() in ids(home(), "continue-series"))
  }

  @Test
  fun recentWindowMovesWithTheClock() = runBlocking {
    db.libraries().upsertAll(listOf(libraryRow("srv", "edge")))
    db.items().upsertAll(listOf(itemEntity("srv", "old", "edge", addedAt = now - RECENT_WINDOW_MS)))
    assertEquals(listOf("old"), ids(home("edge"), "recently-added"))
    now += HOME_CACHE_BUCKET_MS
    assertEquals(emptyList<String>(), ids(home("edge"), "recently-added"))
  }

  @Test
  fun emptyQueryReadsNothing() = runBlocking {
    val id = idOf("Alpha Saga One")
    db.items().setSearchKey("srv", id, "")
    val answer = search("\u0301")
    val results = answer.getJSONObject("results")
    assertEquals(setOf("book", "podcast", "episodes", "series", "authors", "narrators", "tags", "genres"), results.keySet())
    results.keySet().forEach { assertEquals(it, 0, results.getJSONArray(it).length()) }
    assertEquals(10L, answer.getJSONObject("replica").getLong("reconciledAt"))
    assertEquals("", db.items().get("srv", id)!!.searchKey)
  }

  @Test
  fun rowsWithStaleKeyAreFoundAndBackfilled() = runBlocking {
    val id = idOf("Alpha Saga One")
    db.items().setSearchKey("srv", id, "")
    val found = search("saga one").getJSONObject("results").getJSONArray("book").objects().map { it.getJSONObject("libraryItem").getString("id") }
    assertEquals(listOf(id), found)
    assertTrue(db.items().get("srv", id)!!.searchKey.startsWith("1\u0001"))
  }

  @Test
  fun searchAnswersInTheServerShape() {
    val results = search("deep", pods).getJSONObject("results")
    val episodes = results.getJSONArray("episodes").objects()
    assertEquals(setOf("Deep Dive", "Deep Water"), episodes.map { it.getJSONObject("libraryItem").getJSONObject("recentEpisode").getString("title") }.toSet())
    val alpha = search("alpha").getJSONObject("results")
    val series = alpha.getJSONArray("series").objects().single()
    assertEquals("Alpha Saga", series.getJSONObject("series").getString("name"))
    assertEquals(3, series.getJSONArray("books").length())
    assertEquals(listOf("Nar Alpha" to 3), alpha.getJSONArray("narrators").objects().map { it.getString("name") to it.getInt("numBooks") })
    assertEquals(setOf("Alpha Saga One", "Alpha Saga Two", "Alpha Saga Ten"),
            alpha.getJSONArray("book").objects().map { titleOf(it.getJSONObject("libraryItem").getString("id")) }.toSet())
    val author = search("archer").getJSONObject("results").getJSONArray("authors").objects().single()
    assertEquals(3, author.getInt("numBooks"))
  }

  @Test
  fun otherServerRowsAreInvisible() = runBlocking {
    db.libraries().upsertAll(listOf(libraryRow("other", books)))
    db.items().upsertAll(listOf(itemEntity("other", "intruder", books, title = "Alpha Intruder", addedAt = now).let {
      it.copy(searchKey = CatalogJson.searchKey(CatalogJson.mapper.readTree(it.json)))
    }))
    assertFalse("intruder" in ids(home(), "recently-added"))
    assertFalse(search("alpha").getJSONObject("results").getJSONArray("book").toString().contains("intruder"))
    assertEquals(listOf("intruder"), ids(home(sc = "other"), "recently-added"))
    assertTrue(home(sc = "other").getJSONObject("replica").isNull("reconciledAt"))
    assertEquals(0, search("saga", sc = "other").getJSONObject("results").getJSONArray("book").length())
  }
}
