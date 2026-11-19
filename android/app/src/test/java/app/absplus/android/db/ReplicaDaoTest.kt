package app.absplus.android.db

import app.absplus.android.db.replica.DetailLevel
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.replica.inChunks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** replica.db DAOs on Room under Robolectric. Synthetic ids only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplicaDaoTest {
  private val db = inMemoryReplica()

  @After
  fun tearDown() = db.close()

  private fun state(sc: String, libraryId: String) = LibraryStateEntity(sc, libraryId, 1L, true, 1L, null, null, null, null)

  @Test
  fun twoConfigsWithSameIdsDoNotOverwrite() = runBlocking {
    db.items().upsertAll(listOf(itemEntity("a", "li1", title = "From A"), itemEntity("b", "li1", title = "From B")))
    db.series().upsertAll(listOf(seriesRow("a", "s1"), seriesRow("b", "s1")))
    db.series().insertMembers(listOf(member("a", "s1", "li1", "1"), member("b", "s1", "li1", "2")))
    db.libraries().upsertAll(listOf(libraryRow("a", "lib1"), libraryRow("b", "lib1", position = 3)))

    assertEquals(listOf("From A"), db.items().rows("a", "lib1").map { it.title })
    assertEquals(listOf("From B"), db.items().rows("b", "lib1").map { it.title })
    assertEquals(listOf("1"), db.series().members("a", "lib1").map { it.sequence })
    assertEquals(listOf("2"), db.series().members("b", "lib1").map { it.sequence })
    assertEquals(0, db.libraries().get("a", "lib1")!!.position)
    assertEquals(3, db.libraries().get("b", "lib1")!!.position)
  }

  @Test
  fun deleteLibraryCascadesItsRows() = runBlocking {
    for (sc in listOf("a", "b")) {
      db.libraries().upsertAll(listOf(libraryRow(sc, "lib1"), libraryRow(sc, "lib2", 1)))
      db.items().upsertAll(listOf(itemEntity(sc, "li1", "lib1"), itemEntity(sc, "li2", "lib2")))
      db.series().upsertAll(listOf(seriesRow(sc, "s1", "lib1"), seriesRow(sc, "s2", "lib2")))
      db.series().insertMembers(listOf(member(sc, "s1", "li1"), member(sc, "s2", "li2")))
      db.libraryState().upsert(state(sc, "lib1"))
      db.libraryState().upsert(state(sc, "lib2"))
    }

    db.deleteLibraryCascade("a", "lib1")

    assertNull(db.libraries().get("a", "lib1"))
    assertTrue(db.items().rows("a", "lib1").isEmpty())
    assertTrue(db.series().forLibrary("a", "lib1").isEmpty())
    assertTrue(db.series().membershipOfItem("a", "li1").isEmpty())
    assertNull(db.libraryState().get("a", "lib1"))

    assertNotNull(db.libraries().get("a", "lib2"))
    assertEquals(1, db.items().rows("a", "lib2").size)
    assertEquals(1, db.series().members("a", "lib2").size)
    assertNotNull(db.libraryState().get("a", "lib2"))
    assertEquals(1, db.items().rows("b", "lib1").size)
    assertEquals(1, db.series().members("b", "lib1").size)
    assertNotNull(db.libraryState().get("b", "lib1"))
  }

  @Test
  fun chunkedDeleteHandlesMoreThan999Ids() = runBlocking {
    val ids = (1..1500).map { "li$it" }
    val seriesIds = (1..1500).map { "s$it" }
    db.items().upsertAll(ids.map { itemEntity("a", it) })
    db.series().upsertAll(seriesIds.map { seriesRow("a", it) })

    db.items().deleteByIds("a", ids)
    db.series().deleteByIds("a", seriesIds)

    assertEquals(0, db.items().count("a", "lib1"))
    assertTrue(db.series().ids("a", "lib1").isEmpty())
  }

  @Test
  fun inChunksSplitsBy500() {
    val sizes = inChunks((1..1500).map { "id$it" }) { it.size }
    assertEquals(listOf(500, 500, 500), sizes)

    // Robolectric's SQLite allows 32 766 variables, so only the source shows the DAO deletes go through inChunks
    val source = File("src/main/java/app/absplus/android/db/replica/ReplicaDaos.kt").readText()
    val deletes = Regex("""suspend fun deleteByIds\(sc: String, ids: List<String>\) \{\s+inChunks\(ids\)""").findAll(source).count()
    assertEquals("LibraryItemDao, SeriesDao, AuthorDao, CollectionDao and PlaylistDao delete through inChunks", 5, deletes)
  }

  @Test
  fun deletingAPodcastDeletesItsEpisodes() = runBlocking {
    db.items().upsertAll(listOf(itemEntity("a", "p1", mediaType = "podcast"), itemEntity("a", "p2", mediaType = "podcast"),
            itemEntity("b", "p1", mediaType = "podcast"), itemEntity("a", "p3", "lib2", mediaType = "podcast")))
    db.episodes().replaceForPodcast("a", "p1", listOf(episodeRow("a", "e1", "p1"), episodeRow("a", "e2", "p1")))
    db.episodes().replaceForPodcast("a", "p2", listOf(episodeRow("a", "e3", "p2")))
    db.episodes().replaceForPodcast("b", "p1", listOf(episodeRow("b", "e1", "p1")))
    db.episodes().replaceForPodcast("a", "p3", listOf(episodeRow("a", "e4", "p3", "lib2")))

    db.items().deleteByIds("a", listOf("p1"))

    assertTrue(db.episodes().forPodcast("a", "p1").isEmpty())
    assertEquals(listOf("e3"), db.episodes().forPodcast("a", "p2").map { it.id })
    assertEquals(listOf("e1"), db.episodes().forPodcast("b", "p1").map { it.id })

    db.deleteLibraryCascade("a", "lib1")
    assertTrue(db.episodes().forPodcast("a", "p2").isEmpty())
    assertEquals(listOf("e4"), db.episodes().forPodcast("a", "p3").map { it.id })
    assertEquals(listOf("e1"), db.episodes().forPodcast("b", "p1").map { it.id })
  }

  @Test
  fun replacingEpisodesDropsTheMissingOnes() = runBlocking {
    db.episodes().replaceForPodcast("a", "p1", listOf(episodeRow("a", "e1", "p1"), episodeRow("a", "e2", "p1")))
    db.episodes().replaceForPodcast("a", "p1", listOf(episodeRow("a", "e2", "p1", title = "New")))
    assertEquals(listOf("New"), db.episodes().forPodcast("a", "p1").map { it.title })
  }

  @Test
  fun expandedOfAnOlderVersionIsNotWritten() = runBlocking {
    db.items().upsertAll(listOf(itemEntity("a", "li1", updatedAt = 200)))

    assertEquals(0, db.items().setExpanded("a", "li1", 100, DetailLevel.EXPANDED, "{}"))
    assertEquals(DetailLevel.MINIFIED, db.items().get("a", "li1")!!.detailLevel)
    assertNull(db.items().get("a", "li1")!!.expandedJson)

    assertEquals(1, db.items().setExpanded("a", "li1", 200, DetailLevel.EXPANDED, "{}"))
    assertEquals(listOf(DetailLevel.EXPANDED to "{}"), db.items().expandedOf("a", listOf("li1")).map { it.detailLevel to it.expandedJson })
  }

  @Test
  fun podcastsWithoutEpisodesAreTheNotExpandedOnes() = runBlocking {
    db.items().upsertAll(listOf(itemEntity("a", "p1", mediaType = "podcast"), itemEntity("a", "p2", mediaType = "podcast"),
            itemEntity("a", "p3", mediaType = "podcast").copy(detailLevel = DetailLevel.STALE), itemEntity("a", "b1")))
    db.items().setExpanded("a", "p2", 1, DetailLevel.EXPANDED, "{}")
    assertEquals(setOf("p1", "p3"), db.items().podcastsWithoutEpisodes("a", "lib1").toSet())
  }

  @Test
  fun searchPatternEscapesWildcards() = runBlocking {
    db.items().upsertAll(listOf(itemEntity("a", "li1").copy(searchKey = "1\u0001a%b"), itemEntity("a", "li2").copy(searchKey = "1\u0001axb")))
    assertEquals(listOf("li1"), db.items().searchCandidates("a", "lib1", "1\u0001%a\\%b%", 10).map { it.id })
  }

  @Test
  fun versionPrefixIsNotSearched() = runBlocking {
    db.items().upsertAll(listOf(itemEntity("a", "li1", addedAt = 1).copy(searchKey = "1\u0001book 12"),
            itemEntity("a", "li2", addedAt = 2).copy(searchKey = "1\u0001alice")))
    db.episodes().replaceForPodcast("a", "p1", listOf(episodeRow("a", "e1", "p1", searchKey = "1\u0001book 12"),
            episodeRow("a", "e2", "p1", searchKey = "1\u0001alice")))

    assertEquals(listOf("li1"), db.items().searchCandidates("a", "lib1", "1\u0001%1%", 10).map { it.id })
    assertEquals(listOf("e1"), db.episodes().searchByTitle("a", "lib1", "1\u0001%1%", 10).map { it.id })
  }

  @Test
  fun staleSearchKeysAreThoseOfAnotherVersion() = runBlocking {
    db.items().upsertAll(listOf(itemEntity("a", "li1").copy(searchKey = "1\u0001x"), itemEntity("a", "li2"),
            itemEntity("a", "li3").copy(searchKey = "0\u0001x")))
    db.episodes().replaceForPodcast("a", "p1", listOf(episodeRow("a", "e1", "p1", searchKey = "1\u0001x"), episodeRow("a", "e2", "p1")))
    assertEquals(setOf("li2", "li3"), db.items().staleSearchKeys("a", "lib1", "1\u0001", "", 100).map { it.id }.toSet())
    assertEquals(listOf("e2"), db.episodes().staleKeys("a", "lib1", "1\u0001", "", 100).map { it.id })

    db.items().setSearchKey("a", "li2", "1\u0001y")
    db.episodes().setSearchKey("a", "e2", "1\u0001y")
    assertEquals(listOf("li3"), db.items().staleSearchKeys("a", "lib1", "1\u0001", "", 100).map { it.id })
    assertTrue(db.episodes().staleKeys("a", "lib1", "1\u0001", "", 100).isEmpty())
  }

  @Test
  fun staleKeysAreReadInPagesAfterAnId() = runBlocking {
    db.items().upsertAll((1..5).map { itemEntity("a", "li$it") })
    db.episodes().replaceForPodcast("a", "p1", (1..5).map { episodeRow("a", "e$it", "p1") })
    assertEquals(listOf("li1", "li2"), db.items().staleSearchKeys("a", "lib1", "1\u0001", "", 2).map { it.id })
    assertEquals(listOf("li3", "li4"), db.items().staleSearchKeys("a", "lib1", "1\u0001", "li2", 2).map { it.id })
    assertEquals(listOf("e5"), db.episodes().staleKeys("a", "lib1", "1\u0001", "e4", 2).map { it.id })
  }
}
