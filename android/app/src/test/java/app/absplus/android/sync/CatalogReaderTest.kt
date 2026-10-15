package app.absplus.android.sync

import app.absplus.android.db.authorRow
import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.replica.FilterCodec
import app.absplus.android.db.replica.ItemsQuery
import app.absplus.android.db.replica.LibraryEntity
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.orderItems
import app.absplus.android.db.seriesRow
import app.absplus.android.db.userdata.MediaProgressEntity
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogReaderTest {
  private val db = inMemoryReplica()
  private var progressRows = emptyList<MediaProgressEntity>()
  private var progressCalls = 0
  private var version = 0L
  private val reader = CatalogReader(db, { progressCalls++; progressRows }, { _, ids -> progressRows.filter { it.libraryItemId in ids } },
          { version }, { 1_000L })

  @After
  fun tearDown() = db.close()

  private fun request(sort: String? = "media.metadata.title", filter: String? = null, limit: Int = 100, page: Int = 0, collapse: Boolean = false,
                      libraryId: String = "lib1") =
          ItemsRequest(libraryId, sort, false, filter, collapse, false, limit, page)

  private fun items(sc: String = "srv", req: ItemsRequest = request()) = runBlocking { reader.items(sc, req) }

  private fun ids(answer: JSONObject) = answer.getJSONArray("results").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }

  private fun progress(id: String, episodeId: String = "", isFinished: Boolean = false, lastUpdate: Long = 1) = MediaProgressEntity(
          "srv", id, episodeId, null, 10.0, 0.0, 1.0, isFinished, false, null, null, lastUpdate, 1, null, "server", false)

  private fun fill(count: Int, sc: String = "srv") = runBlocking {
    db.libraries().upsertAll(listOf(libraryRow(sc, "lib1")))
    db.items().upsertAll((0 until count).map { itemEntity(sc, "li$it", title = "Book ${(it * 7919) % count}", addedAt = it.toLong()) })
  }

  @Test
  fun limitAbove100IsRejected() {
    fill(1)
    for (limit in listOf(101, 0)) {
      try {
        items(req = request(limit = limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
  }

  @Test
  fun pagesAreSlicesOfOneOrder() {
    fill(250)
    val pages = (0..2).map { items(req = request(page = it)) }
    val all = pages.flatMap { ids(it) }
    assertEquals(250, all.toSet().size)
    val rows = runBlocking { db.items().rows("srv", "lib1") }
    val expected = orderItems(rows, ItemsQuery("media.metadata.title", false, null, false, false, "book"), emptyMap(), SeriesIndex.EMPTY,
            emptyMap(), 1_000L, Random(1)).map { it.itemId }
    assertEquals(expected, all)
    pages.forEach { assertEquals(250, it.getInt("total")) }
    val past = items(req = request(page = 3))
    assertEquals(0, past.getJSONArray("results").length())
    assertEquals(250, past.getInt("total"))
    assertEquals(100, past.getInt("limit"))
    assertEquals(3, past.getInt("page"))
  }

  @Test
  fun replicaWriteDropsTheCachedOrder() {
    fill(150)
    items()
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "first", title = "AAA"))) }
    reader.onReplicaChanged()
    assertEquals("first", ids(items())[0])
  }

  @Test
  fun progressChangeReordersOnlyProgressQueries() {
    fill(3)
    progressRows = listOf(progress("li2", lastUpdate = 5), progress("li0", lastUpdate = 9))
    assertEquals(listOf("li2", "li0", "li1"), ids(items(req = request(sort = "progress"))))
    items(req = request(sort = "progress"))
    assertEquals(1, progressCalls)

    progressRows = listOf(progress("li2", lastUpdate = 5), progress("li0", lastUpdate = 1))
    version++
    assertEquals(listOf("li0", "li2", "li1"), ids(items(req = request(sort = "progress"))))
    assertEquals(2, progressCalls)

    items(req = request(sort = "addedAt"))
    version++
    items(req = request(sort = "addedAt"))
    assertEquals("an addedAt list never reads progress", 2, progressCalls)
  }

  @Test
  fun numEpisodesIncompleteFromProgress() {
    runBlocking {
      db.libraries().upsertAll(listOf(libraryRow("srv", "pods", mediaType = "podcast")))
      db.items().upsertAll(listOf(itemEntity("srv", "pod", libraryId = "pods", mediaType = "podcast").copy(
              json = """{"id":"pod","libraryId":"pods","mediaType":"podcast","media":{"numEpisodes":10}}""")))
    }
    progressRows = (1..3).map { progress("pod", episodeId = "ep$it", isFinished = true) } + progress("pod", episodeId = "ep4")
    val first = items(req = request(libraryId = "pods")).getJSONArray("results").getJSONObject(0)
    assertEquals(7, first.getInt("numEpisodesIncomplete"))

    progressRows = (1..10).map { progress("pod", episodeId = "ep$it", isFinished = true) }
    val done = items(req = request(libraryId = "pods")).getJSONArray("results").getJSONObject(0)
    assertFalse(done.has("numEpisodesIncomplete"))
  }

  /** The key sets of the server's answers recorded in parity/cases.json. */
  private fun recordedKeys(case: String, path: (com.fasterxml.jackson.databind.JsonNode) -> com.fasterxml.jackson.databind.JsonNode): Set<String> {
    val cases = CatalogJson.mapper.readTree(javaClass.getResource("/catalog/parity/cases.json")).path("cases")
    return path(cases.first { it.path("name").asText() == case }.path("sample")).fieldNames().asSequence().toSet()
  }

  @Test
  fun seriesFilterAddsSequenceMetadata() {
    fill(2)
    runBlocking {
      db.series().upsertAll(listOf(seriesRow("srv", "s1", name = "Мир")))
      db.series().insertMembers(listOf(member("srv", "s1", "li1", "2")))
    }
    val result = items(req = request(sort = null, filter = "series.czE%3D")).getJSONArray("results").getJSONObject(0)
    val series = result.getJSONObject("media").getJSONObject("metadata").getJSONObject("series")
    assertEquals(JSONObject().put("id", "s1").put("name", "Мир").put("sequence", "2").toString(), series.toString())
    assertEquals(recordedKeys("series page Мир") { it.path("media").path("metadata").path("series") }, series.keys().asSequence().toSet())
  }

  @Test
  fun collapsedSeriesObjectShape() {
    fill(3)
    runBlocking {
      db.series().upsertAll(listOf(seriesRow("srv", "s1", name = "Мир")))
      db.series().insertMembers(listOf(member("srv", "s1", "li1", "1"), member("srv", "s1", "li2", "2")))
    }
    val answer = items(req = request(collapse = true))
    assertEquals(2, answer.getInt("total"))
    val card = (0 until 2).map { answer.getJSONArray("results").getJSONObject(it) }.single { it.has("collapsedSeries") }.getJSONObject("collapsedSeries")
    assertEquals(recordedKeys("collapse null") { it.path("collapsedSeries") }, card.keys().asSequence().toSet())
    assertEquals(2, card.getInt("numBooks"))
    assertEquals("li1", card.getJSONArray("libraryItemIds").getString(0))
  }

  @Test
  fun otherServerRowsAreInvisible() {
    fill(2, sc = "a")
    fill(5, sc = "b")
    assertEquals(2, items(sc = "a").getInt("total"))
    assertEquals(1, runBlocking { reader.libraries("a") }.getJSONArray("libraries").length())
    assertEquals(0, items(sc = "c").getInt("total"))
    assertTrue(runBlocking { reader.library("c", "lib1") }.isNull("library"))
  }

  @Test
  fun replicaStateIsReported() {
    fill(1)
    assertTrue(runBlocking { reader.libraries("srv") }.getJSONObject("replica").isNull("reconciledAt"))
    assertTrue(runBlocking { reader.library("srv", "lib1") }.getJSONObject("replica").isNull("reconciledAt"))
    assertTrue(items().getJSONObject("replica").isNull("reconciledAt"))

    runBlocking {
      db.libraryState().upsert(LibraryStateEntity("srv", "", 50L, true, null, null, null, null, null))
      db.libraryState().upsert(LibraryStateEntity("srv", "lib1", 60L, false, null, null, null, null, null))
    }
    assertEquals(50L, runBlocking { reader.libraries("srv") }.getJSONObject("replica").getLong("reconciledAt"))
    val library = runBlocking { reader.library("srv", "lib1") }
    assertEquals(60L, library.getJSONObject("replica").getLong("reconciledAt"))
    assertFalse(library.getJSONObject("replica").getBoolean("complete"))
    assertEquals(60L, items().getJSONObject("replica").getLong("reconciledAt"))
    val unknown = runBlocking { reader.library("srv", "nope") }
    assertTrue(unknown.isNull("library"))
    assertTrue(unknown.getJSONObject("replica").isNull("reconciledAt"))
  }

  @Test
  fun unsupportedFilterIsFlagged() {
    fill(3)
    val answer = items(req = request(filter = "ebooks.c3VwcGxlbWVudGFyeQ%3D%3D"))
    assertTrue(answer.getBoolean("unsupportedFilter"))
    assertEquals(0, answer.getInt("total"))
    assertEquals(0, answer.getJSONArray("results").length())
  }

  @Test
  fun openFeedIsPutBackIntoTheItem() {
    fill(1)
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "li0").copy(rssFeedJson = """{"id":"f1"}"""))) }
    reader.onReplicaChanged()
    assertEquals("f1", items().getJSONArray("results").getJSONObject(0).getJSONObject("rssFeed").getString("id"))
  }

  private fun library(sc: String = "srv", id: String = "lib1", hideSingle: Boolean = false) = LibraryEntity(sc, id, 0, "book",
          """{"id":"$id","mediaType":"book","settings":{"hideSingleBookSeries":$hideSingle}}""", null, 0, 0)

  private fun seriesRequest(sort: String? = "name", desc: Boolean = false, ignorePrefix: Boolean = false, limit: Int = 100, page: Int = 0) =
          SeriesRequest("lib1", sort, desc, ignorePrefix, limit, page)

  private fun series(sc: String = "srv", req: SeriesRequest = seriesRequest()) = runBlocking { reader.series(sc, req) }

  private fun names(answer: JSONObject) = answer.getJSONArray("results").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("name") } }

  /** A series with one book of its own per name; `nameIgnorePrefix` as the server's JSON has it. */
  private fun fillSeries(vararg names: Pair<String, String>, sc: String = "srv", hideSingle: Boolean = false) = runBlocking {
    db.libraries().upsertAll(listOf(library(sc, hideSingle = hideSingle)))
    names.forEachIndexed { i, (name, ignorePrefix) ->
      db.series().upsertAll(listOf(seriesRow(sc, "s$i", name = name).copy(nameIgnorePrefix = ignorePrefix, addedAt = i.toLong())))
      db.items().upsertAll(listOf(itemEntity(sc, "b$i")))
      db.series().insertMembers(listOf(member(sc, "s$i", "b$i", "1")))
    }
    reader.onReplicaChanged()
  }

  @Test
  fun seriesSortAndPaging() {
    fillSeries("The Saga" to "Saga, The", "apple" to "apple", "Banana" to "Banana", "Ёж" to "Ёж", "Zeta" to "Zeta", "saga 2" to "saga 2")
    assertEquals(listOf("apple", "Banana", "saga 2", "The Saga", "Zeta", "Ёж"), names(series()))
    assertEquals(listOf("Ёж", "Zeta", "The Saga", "saga 2", "Banana", "apple"), names(series(req = seriesRequest(desc = true))))
    assertEquals("the server sorts on the name without its prefix", listOf("apple", "Banana", "The Saga", "saga 2", "Zeta", "Ёж"),
            names(series(req = seriesRequest(ignorePrefix = true))))
    assertEquals("other sorts fall back to the name", names(series()), names(series(req = seriesRequest(sort = "addedAt"))))

    fillSeries(*(0 until 150).map { "Series %03d".format(it) to "Series %03d".format(it) }.toTypedArray(), sc = "big")
    val pages = (0..2).map { series(sc = "big", req = seriesRequest(page = it)) }
    assertEquals(listOf(100, 50, 0), pages.map { it.getJSONArray("results").length() })
    pages.forEach { assertEquals(150, it.getInt("total")) }
    assertEquals((0 until 150).map { "Series %03d".format(it) }, pages.flatMap { names(it) })
    assertEquals(2, pages[2].getInt("page"))
    assertEquals(100, pages[2].getInt("limit"))
    for (limit in listOf(101, 0)) {
      try {
        series(sc = "big", req = seriesRequest(limit = limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
  }

  @Test
  fun seriesPageHasBooksInServerOrder() {
    runBlocking {
      db.libraries().upsertAll(listOf(library()))
      db.items().upsertAll(listOf("a", "b", "c", "d").map { itemEntity("srv", it) })
      db.series().upsertAll(listOf(seriesRow("srv", "s1", name = "Мир")))
      db.series().insertMembers(listOf(member("srv", "s1", "a", "10"), member("srv", "s1", "b", "2"), member("srv", "s1", "c", "1.5"),
              member("srv", "s1", "d", null)))
    }
    val first = series().getJSONArray("results").getJSONObject(0)
    assertEquals("s1", first.getString("id"))
    val books = first.getJSONArray("books")
    assertEquals(listOf("c", "b", "a", "d"), (0 until books.length()).map { books.getJSONObject(it).getString("id") })
    assertEquals("the minified item", "Title c", books.getJSONObject(0).getJSONObject("media").getJSONObject("metadata").getString("title"))
  }

  @Test
  fun hideSingleBookSeriesFollowsLibrarySetting() {
    fun fill(sc: String, hideSingle: Boolean) = runBlocking {
      db.libraries().upsertAll(listOf(library(sc, hideSingle = hideSingle)))
      db.items().upsertAll(listOf("a", "b", "c").map { itemEntity(sc, it) })
      db.series().upsertAll(listOf(seriesRow(sc, "one", name = "One"), seriesRow(sc, "two", name = "Two")))
      db.series().insertMembers(listOf(member(sc, "one", "a"), member(sc, "two", "b"), member(sc, "two", "c")))
    }
    fill("shown", hideSingle = false)
    fill("hidden", hideSingle = true)
    assertEquals(listOf("One", "Two"), names(series(sc = "shown")))
    val hidden = series(sc = "hidden")
    assertEquals(listOf("Two"), names(hidden))
    assertEquals(1, hidden.getInt("total"))
  }

  @Test
  fun seriesWithoutVisibleBooksIsHidden() {
    runBlocking {
      db.libraries().upsertAll(listOf(library()))
      db.items().upsertAll(listOf(itemEntity("srv", "a")))
      db.series().upsertAll(listOf(seriesRow("srv", "kept", name = "Kept"), seriesRow("srv", "gone", name = "Gone"), seriesRow("srv", "empty", name = "Empty")))
      db.series().insertMembers(listOf(member("srv", "kept", "a"), member("srv", "gone", "not-in-replica")))
    }
    val answer = series()
    assertEquals(listOf("Kept"), names(answer))
    assertEquals(1, answer.getInt("total"))
  }

  @Test
  fun seriesListReportsSeriesReconciledAt() {
    fillSeries("A" to "A")
    assertTrue(series().getJSONObject("replica").isNull("reconciledAt"))
    runBlocking { db.libraryState().upsert(LibraryStateEntity("srv", "lib1", 60L, true, null, null, null, null, null)) }
    assertTrue("the items pass is not the series pass", series().getJSONObject("replica").isNull("reconciledAt"))
    runBlocking { db.libraryState().upsert(LibraryStateEntity("srv", "lib1", 60L, true, 70L, null, null, null, null)) }
    val replica = series().getJSONObject("replica")
    assertEquals(70L, replica.getLong("reconciledAt"))
    assertTrue(replica.getBoolean("complete"))
  }

  @Test
  fun seriesByIdOfAnotherServerIsNull() {
    fillSeries("Мир" to "Мир", sc = "a")
    runBlocking { db.libraryState().upsert(LibraryStateEntity("a", "lib1", 60L, true, 70L, null, null, null, null)) }
    val found = runBlocking { reader.seriesById("a", "s0") }
    assertEquals("a", found.getString("serverConfigId"))
    assertEquals("Мир", found.getJSONObject("series").getString("name"))
    assertFalse("no books, as /api/series/:id", found.getJSONObject("series").has("books"))
    assertEquals(70L, found.getJSONObject("replica").getLong("reconciledAt"))

    val other = runBlocking { reader.seriesById("b", "s0") }
    assertTrue(other.isNull("series"))
    assertTrue("server b has no reconciled library", other.getJSONObject("replica").isNull("reconciledAt"))

    val missing = runBlocking { reader.seriesById("a", "nope") }
    assertTrue(missing.isNull("series"))
    assertEquals("every library of the server reconciled its series", 70L, missing.getJSONObject("replica").getLong("reconciledAt"))
    runBlocking { db.libraries().upsertAll(listOf(library("a", id = "lib2"))) }
    assertTrue("a library whose series were never read may have it",
            runBlocking { reader.seriesById("a", "nope") }.getJSONObject("replica").isNull("reconciledAt"))
  }

  /** A case of parity/subseries.json in its own replica, and our answer to the request it describes. */
  private fun subSeriesCase(case: JsonNode, sc: String): JSONObject = runBlocking {
    db.libraries().upsertAll(listOf(library(sc, hideSingle = case.path("hideSingleBookSeries").asBoolean())))
    db.items().upsertAll(case.path("books").mapIndexed { i, b ->
      itemEntity(sc, b.path("id").asText(), title = b.path("title").asText(), addedAt = i.toLong()).copy(authorName = b.path("author").asText(""))
    })
    db.series().upsertAll(case.path("series").map { seriesRow(sc, it.path("id").asText(), name = it.path("name").asText()) })
    db.series().insertMembers(case.path("books").flatMap { b ->
      b.path("series").map { member(sc, it.path("id").asText(), b.path("id").asText(), it.path("sequence").takeIf { s -> s.isTextual }?.asText()) }
    })
    reader.onReplicaChanged()
    val sort = case.path("sort").takeIf { it.isTextual }?.asText()
    reader.items(sc, ItemsRequest("lib1", sort, case.path("desc").asBoolean(), "series." + FilterCodec.encode(case.path("seriesId").asText()), true, false, 100, 0))
  }

  private fun assertSubSeriesCases(names: (String) -> Boolean) {
    val cases = CatalogJson.mapper.readTree(javaClass.getResource("/catalog/parity/subseries.json")).path("cases").filter { names(it.path("name").asText()) }
    assertTrue(cases.isNotEmpty())
    cases.forEachIndexed { i, case ->
      val name = case.path("name").asText()
      val answer = subSeriesCase(case, "case$i")
      val expected = case.path("expected")
      assertEquals("$name: total", expected.path("total").asInt(), answer.getInt("total"))
      val results = answer.getJSONArray("results")
      assertEquals("$name: ids", expected.path("results").map { it.path("id").asText() }, (0 until results.length()).map { results.getJSONObject(it).getString("id") })
      expected.path("results").forEachIndexed { k, want ->
        val got = CatalogJson.mapper.readTree(results.getJSONObject(k).toString())
        if (want.has("series")) assertEquals("$name: series of ${want.path("id").asText()}", want.path("series"), got.path("media").path("metadata").path("series"))
        assertEquals("$name: card of ${want.path("id").asText()}", want.path("collapsedSeries"), got.path("collapsedSeries"))
      }
    }
  }

  @Test
  fun subSeriesCollapseMatchesServer() = assertSubSeriesCases { !it.contains("Повесть") && !it.startsWith("Library") && !it.startsWith("one card") }

  @Test
  fun subSeriesFollowLibrarySorts() = assertSubSeriesCases { it.startsWith("Library") }

  @Test
  fun aFlatSeriesPageKeepsTheServersOrder() = assertSubSeriesCases { it.startsWith("one card") }

  @Test
  fun hiddenSingleBookSeriesCarriesItsLibrary() {
    fillSeries("Мир" to "Мир")
    // As written from filterdata before 4b: no libraryId in the JSON
    runBlocking { db.series().upsertAll(listOf(seriesRow("srv", "s0", name = "Мир").copy(json = """{"id":"s0","name":"Мир"}"""))) }
    reader.onReplicaChanged()
    assertEquals("lib1", runBlocking { reader.seriesById("srv", "s0") }.getJSONObject("series").getString("libraryId"))
    assertEquals("lib1", series().getJSONArray("results").getJSONObject(0).getString("libraryId"))
  }

  @Test
  fun seriesReadBeforeTheBooksAreNotLoaded() {
    fillSeries("Мир" to "Мир")
    runBlocking { db.libraryState().upsert(LibraryStateEntity("srv", "lib1", null, false, 70L, null, null, null, null)) }
    assertTrue(series().getJSONObject("replica").isNull("reconciledAt"))
    assertTrue(runBlocking { reader.seriesById("srv", "s0") }.getJSONObject("replica").isNull("reconciledAt"))
    assertTrue(runBlocking { reader.seriesById("srv", "nope") }.getJSONObject("replica").isNull("reconciledAt"))
  }

  @Test
  fun aSeriesAwaitingItsPointQueryIsNotLoaded() {
    fillSeries("Мир" to "Мир", "Сага" to "Сага")
    runBlocking { db.libraryState().upsert(LibraryStateEntity("srv", "lib1", 60L, true, 70L, null, null, null, null, pendingSeriesQueries = """["s0"]""")) }
    reader.onReplicaChanged()
    assertTrue(items(req = request(sort = null, filter = "series." + FilterCodec.encode("s0"))).getJSONObject("replica").isNull("reconciledAt"))
    assertEquals(60L, items(req = request(sort = null, filter = "series." + FilterCodec.encode("s1"))).getJSONObject("replica").getLong("reconciledAt"))
    assertEquals(60L, items().getJSONObject("replica").getLong("reconciledAt"))
  }

  @Test
  fun aSeriesTheReplicaLacksIsNotLoaded() {
    fillSeries("Мир" to "Мир")
    runBlocking { db.libraryState().upsert(LibraryStateEntity("srv", "lib1", 60L, true, 70L, null, null, null, null)) }
    reader.onReplicaChanged()
    assertTrue("new on the server", items(req = request(sort = null, filter = "series." + FilterCodec.encode("new"))).getJSONObject("replica").isNull("reconciledAt"))
    assertEquals(60L, items(req = request(sort = null, filter = "series." + FilterCodec.encode("s0"))).getJSONObject("replica").getLong("reconciledAt"))
    assertEquals(60L, items(req = request(sort = null, filter = "series." + FilterCodec.encode("no-series"))).getJSONObject("replica").getLong("reconciledAt"))
  }

  @Test
  fun singleBookSubSeriesFollowsHideSetting() = assertSubSeriesCases { it.contains("Повесть") }

  private fun authors(sc: String = "srv", limit: Int = 100, page: Int = 0, libraryId: String = "lib1") =
          runBlocking { reader.authors(sc, AuthorsRequest(libraryId, limit, page)) }

  private fun authorIds(answer: JSONObject) = answer.getJSONArray("results").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }

  /** `KT-res/catalog/authors-page.json` is made from the server's code (2.37.1 LibraryController.getAuthors / Author.toOldJSONExpanded + lastFirst). */
  private fun fillAuthorsFromFixture(sc: String = "srv") = runBlocking {
    db.libraries().upsertAll(listOf(library(sc)))
    val page = CatalogJson.mapper.readTree(javaClass.getResource("/catalog/authors-page.json"))
    db.authors().upsertAll(page.path("results").map { CatalogJson.authorEntity(sc, it) })
    reader.onReplicaChanged()
  }

  @Test
  fun authorsAreOrderedByName() {
    fillAuthorsFromFixture()
    val answer = authors()
    assertEquals("name without case (alice before Bob), then id for equal names",
            listOf("au-3", "au-2", "au-1", "au-5", "au-4"), authorIds(answer))
    assertEquals(5, answer.getInt("total"))
    val alice = answer.getJSONArray("results").getJSONObject(0)
    assertEquals("the server's author with its count and lastFirst", 1, alice.getInt("numBooks"))
    assertEquals("Adams, alice", alice.getString("lastFirst"))
    assertEquals("/metadata/authors/au-3.jpg", alice.getString("imagePath"))

    // Pages of one order against a reference order
    runBlocking {
      db.authors().upsertAll((0 until 250).map { i ->
        val name = "author %03d".format((i * 37) % 250).let { if (i % 2 == 0) it.uppercase() else it }
        authorRow("big", "id%03d".format(i), name = name)
      })
    }
    reader.onReplicaChanged()
    val reference = runBlocking { db.authors().forLibrary("big", "lib1") }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id })).map { it.id }
    val pages = (0..3).map { authors(sc = "big", page = it) }
    assertEquals(listOf(100, 100, 50, 0), pages.map { it.getJSONArray("results").length() })
    pages.forEach { assertEquals(250, it.getInt("total")) }
    assertEquals(reference, pages.flatMap { authorIds(it) })
    assertEquals(3, pages[3].getInt("page"))
    assertEquals(100, pages[3].getInt("limit"))
    for (limit in listOf(101, 0)) {
      try {
        authors(sc = "big", limit = limit)
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
    assertEquals("another server's authors are invisible", 0, authors(sc = "other").getInt("total"))
  }

  @Test
  fun authorsListReportsAuthorsReconciledAt() {
    fillAuthorsFromFixture()
    val before = authors()
    assertEquals("srv", before.getString("serverConfigId"))
    assertTrue(before.getJSONObject("replica").isNull("reconciledAt"))
    runBlocking { db.libraryState().upsert(LibraryStateEntity("srv", "lib1", 60L, true, 65L, null, null, null, null)) }
    assertTrue("the items pass is not the authors pass", authors().getJSONObject("replica").isNull("reconciledAt"))
    runBlocking { db.libraryState().upsert(LibraryStateEntity("srv", "lib1", 60L, true, 65L, 70L, null, null, null)) }
    val replica = authors().getJSONObject("replica")
    assertEquals(70L, replica.getLong("reconciledAt"))
    assertTrue(replica.getBoolean("complete"))
  }

  @Test
  fun authorFilterFindsAuthorAddedByEvent() {
    runBlocking {
      db.libraries().upsertAll(listOf(library().copy(filterDataJson = """{"authors":[{"id":"a1","name":"Known"}]}""")))
      db.items().upsertAll(listOf(itemEntity("srv", "b1").copy(authorName = "New One, Known"), itemEntity("srv", "b2").copy(authorName = "Known")))
      // Added by author_added after the filterdata was cached
      db.authors().upsertAll(listOf(authorRow("srv", "a9", name = "New One")))
    }
    reader.onReplicaChanged()
    val answer = items(req = request(filter = "authors.${FilterCodec.encode("a9")}"))
    assertEquals(listOf("b1"), ids(answer))
    assertEquals(listOf("b1", "b2"), ids(items(req = request(filter = "authors.${FilterCodec.encode("a1")}"))))
  }
}
