package app.absplus.android.sync

import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.replica.ItemsQuery
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.orderItems
import app.absplus.android.db.seriesRow
import app.absplus.android.db.userdata.MediaProgressEntity
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
}
