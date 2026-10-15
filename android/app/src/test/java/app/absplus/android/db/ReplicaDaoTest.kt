package app.absplus.android.db

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
    assertEquals("LibraryItemDao and SeriesDao delete through inChunks", 2, deletes)
  }
}
