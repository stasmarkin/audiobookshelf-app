package app.absplus.android.sync

import app.absplus.android.db.authorRow
import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.replica.DetailLevel
import app.absplus.android.db.replica.EpisodeEntity
import app.absplus.android.db.replica.LibraryItemEntity
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.seriesRow
import app.absplus.android.db.userdata.MediaProgressEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The item page's reads of the replica: one item in the expanded shape, and a library's recent episodes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogReaderItemTest {
  private val db = inMemoryReplica()
  private val mapper = CatalogJson.mapper
  private var progressRows = emptyList<MediaProgressEntity>()
  private val reader = CatalogReader(db, { progressRows }, { _, ids -> progressRows.filter { it.libraryItemId in ids } }, { 0L }, { 1_000L })

  @After
  fun tearDown() = db.close()

  private fun fixture(name: String) = mapper.readTree(javaClass.getResource("/catalog/$name")) as ObjectNode

  /** A replica row of the minified book fixture as `li1` of lib1. */
  private fun bookRow(title: String, authorName: String, seriesName: String, coverPath: String, genres: List<String>): LibraryItemEntity {
    val item = fixture("book-minified.json")
    item.put("id", "li1").put("libraryId", "lib1").put("updatedAt", 200)
    val media = item.path("media") as ObjectNode
    media.put("coverPath", coverPath)
    val metadata = media.path("metadata") as ObjectNode
    metadata.put("title", title).put("authorName", authorName).put("seriesName", seriesName)
    metadata.putArray("genres").apply { genres.forEach { add(it) } }
    return CatalogJson.itemEntity("srv", item)
  }

  /** The expanded copy the row had: an older version with two chapters. */
  private fun oldCopy(): String {
    val item = fixture("book-expanded.json")
    item.put("id", "li1").put("libraryId", "lib1").put("updatedAt", 100)
    val media = item.path("media") as ObjectNode
    media.put("coverPath", "A")
    media.putArray("chapters").apply {
      addObject().put("id", 0).put("start", 0).put("end", 10).put("title", "One")
      addObject().put("id", 1).put("start", 10).put("end", 20).put("title", "Two")
    }
    val metadata = media.path("metadata") as ObjectNode
    metadata.put("title", "Old").put("authorName", "Old Author")
    metadata.putArray("authors").addObject().put("id", "a0").put("name", "Old Author")
    metadata.putArray("series").addObject().put("id", "s1").put("name", "S1").put("sequence", "1")
    metadata.putArray("genres").add("Old genre")
    return mapper.writeValueAsString(CatalogJson.expandedForStorage(item))
  }

  private fun read(id: String) = runBlocking { reader.libraryItem("srv", id) }
  private fun tree(json: JSONObject): JsonNode = mapper.readTree(json.toString())

  @Test
  fun expandedCopyWithFreshTopLevel() {
    runBlocking {
      db.libraries().upsertAll(listOf(libraryRow("srv", "lib1")))
      db.authors().upsertAll(listOf(authorRow("srv", "a9", name = "New Author")))
      db.series().upsertAll(listOf(seriesRow("srv", "s1", name = "S1"), seriesRow("srv", "s2", name = "S2")))
      db.series().insertMembers(listOf(member("srv", "s2", "li1", "2")))
      db.items().upsertAll(listOf(bookRow("New", "New Author", "S2 #2", "B", listOf("Sci-Fi"))
              .copy(detailLevel = DetailLevel.STALE, expandedJson = oldCopy())))
    }

    val answer = read("li1")
    assertEquals("srv", answer.getString("serverConfigId"))
    assertEquals("stale", answer.getString("detailLevel"))
    val item = tree(answer).path("libraryItem")
    val metadata = item.path("media").path("metadata")
    assertEquals("New", metadata.path("title").asText())
    assertEquals(200L, item.path("updatedAt").asLong())
    assertEquals(mapper.readTree("""[{"id":"a9","name":"New Author"}]"""), metadata.path("authors"))
    assertEquals(mapper.readTree("""[{"id":"s2","name":"S2","sequence":"2"}]"""), metadata.path("series"))
    assertEquals("B", item.path("media").path("coverPath").asText())
    assertEquals(mapper.readTree("""["Sci-Fi"]"""), metadata.path("genres"))
    assertEquals(listOf("One", "Two"), item.path("media").path("chapters").map { it.path("title").asText() })
    assertTrue("tracks of the copy", item.path("media").path("tracks").isArray)
    assertTrue(item.has("libraryFiles"))

    // The copy of the current version is the answer as it is
    runBlocking {
      db.items().upsertAll(listOf(db.items().get("srv", "li1")!!.copy(detailLevel = DetailLevel.EXPANDED, expandedJson = oldCopy())))
    }
    val expanded = read("li1")
    assertEquals("expanded", expanded.getString("detailLevel"))
    val copy = tree(expanded).path("libraryItem")
    assertEquals("Old", copy.path("media").path("metadata").path("title").asText())
    assertEquals(mapper.readTree("""[{"id":"s1","name":"S1","sequence":"1"}]"""), copy.path("media").path("metadata").path("series"))
    assertTrue(copy.has("rssFeed"))
  }

  @Test
  fun minifiedItemIsPseudoExpanded() {
    runBlocking {
      db.libraries().upsertAll(listOf(libraryRow("srv", "lib1")))
      db.authors().upsertAll(listOf(authorRow("srv", "a9", name = "New Author")))
      db.series().upsertAll(listOf(seriesRow("srv", "s2", name = "S2")))
      db.series().insertMembers(listOf(member("srv", "s2", "li1", "2")))
      db.items().upsertAll(listOf(bookRow("New", "New Author", "S2 #2", "B", emptyList())
              .copy(rssFeedJson = """{"id":"f1","entityType":"libraryItem","entityId":"li1"}""")))
    }
    val answer = read("li1")
    assertEquals("minified", answer.getString("detailLevel"))
    val item = tree(answer).path("libraryItem")
    assertEquals(mapper.readTree("""[{"id":"a9","name":"New Author"}]"""), item.path("media").path("metadata").path("authors"))
    assertEquals("s2", item.path("media").path("metadata").path("series").path(0).path("id").asText())
    assertFalse(item.path("media").has("chapters"))
    assertEquals("f1", item.path("rssFeed").path("id").asText())
  }

  @Test
  fun podcastCarriesItsEpisodes() {
    val podcast = fixture("podcast-minified.json").put("id", "pod1").put("libraryId", "pods")
    runBlocking {
      db.libraries().upsertAll(listOf(libraryRow("srv", "pods", mediaType = "podcast")))
      db.items().upsertAll(listOf(CatalogJson.itemEntity("srv", podcast)))
      db.episodes().insertAll(listOf("e2" to 2L, "e1" to 1L, "e3" to 3L).map { (id, addedAt) -> episode("srv", id, addedAt = addedAt) })
      db.episodes().insertAll(listOf(episode("other", "e1"), episode("other", "e4")))
    }

    val item = tree(read("pod1")).path("libraryItem")
    assertEquals(listOf("e1", "e2", "e3"), item.path("media").path("episodes").map { it.path("id").asText() })
    assertEquals(3, item.path("media").path("numEpisodes").asInt())
    assertEquals("Pod Author", item.path("media").path("metadata").path("author").asText())
  }

  @Test
  fun missingItemReportsReplicaState() {
    runBlocking {
      db.libraries().upsertAll(listOf(libraryRow("srv", "lib1"), libraryRow("srv", "lib2", position = 1)))
      db.libraryState().upsert(state("lib1", items = 5))
    }
    val answer = read("nope")
    assertTrue(answer.isNull("libraryItem"))
    assertTrue(answer.isNull("detailLevel"))
    assertTrue("a library never reconciled may have it", answer.getJSONObject("replica").isNull("reconciledAt"))

    runBlocking { db.libraryState().upsert(state("lib2", items = 7)) }
    assertEquals(5L, read("nope").getJSONObject("replica").getLong("reconciledAt"))
  }

  @Test
  fun recentEpisodesSkipFinishedAndOrderByPublished() {
    val podcast = fixture("podcast-minified.json").put("id", "pod1").put("libraryId", "pods")
    runBlocking {
      db.libraries().upsertAll(listOf(libraryRow("srv", "pods", mediaType = "podcast")))
      db.libraryState().upsert(state("pods", items = 5))
      db.items().upsertAll(listOf(CatalogJson.itemEntity("srv", podcast)))
      db.episodes().insertAll(listOf(
              episode("srv", "p3", publishedAt = 3, addedAt = 1),
              episode("srv", "p1", publishedAt = 1, addedAt = 2),
              episode("srv", "pNull", publishedAt = null, addedAt = 3),
              episode("srv", "p2", publishedAt = 2, addedAt = 4)))
    }
    progressRows = listOf(MediaProgressEntity("srv", "pod1", "p1", null, 10.0, 1.0, 10.0, true, false, null, null, 1, 1, 1, "server", false),
            MediaProgressEntity("srv", "pod1", "p2", null, 10.0, 0.5, 5.0, false, false, null, null, 1, 1, null, "server", false))

    val all = runBlocking { reader.recentEpisodes("srv", RecentEpisodesRequest("pods", 10, 0)) }
    val tree = tree(all)
    assertEquals(listOf("p3", "p2", "pNull"), tree.path("episodes").map { it.path("id").asText() })
    assertEquals(3, all.getInt("total"))
    val first = tree.path("episodes").path(0)
    assertEquals("pods", first.path("libraryId").asText())
    assertEquals("pod1", first.path("libraryItemId").asText())
    assertEquals("pod1", first.path("podcast").path("libraryItemId").asText())
    assertEquals("Pod Author", first.path("podcast").path("metadata").path("author").asText())
    assertEquals(0, first.path("podcast").path("episodes").size())
    assertTrue("episodes never reconciled", all.getJSONObject("replica").isNull("reconciledAt"))

    val pages = (0..1).map { page -> runBlocking { reader.recentEpisodes("srv", RecentEpisodesRequest("pods", 2, page)) } }
    assertEquals(listOf(listOf("p3", "p2"), listOf("pNull")), pages.map { p -> tree(p).path("episodes").map { it.path("id").asText() } })
    assertEquals(listOf(3, 3), pages.map { it.getInt("total") })

    runBlocking { db.libraryState().upsert(state("pods", items = 5, episodes = 6)) }
    assertEquals(5L, runBlocking { reader.recentEpisodes("srv", RecentEpisodesRequest("pods", 2, 0)) }.getJSONObject("replica").getLong("reconciledAt"))
  }

  private fun state(libraryId: String, items: Long?, episodes: Long? = null) =
          LibraryStateEntity("srv", libraryId, items, true, items, items, null, null, null, "[]", episodes)

  private fun episode(sc: String, id: String, publishedAt: Long? = null, addedAt: Long = 1L) =
          EpisodeEntity(sc, id, "pod1", "pods", "Episode $id", "", publishedAt, addedAt, null,
                  """{"id":"$id","title":"Episode $id","publishedAt":${publishedAt ?: "null"}}""")
}
