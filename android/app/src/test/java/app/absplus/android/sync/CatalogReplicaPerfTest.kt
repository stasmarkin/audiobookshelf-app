package app.absplus.android.sync

import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.seriesRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Times of the catalog on a synthetic 10 000-item library, printed for the release notes. JVM and Robolectric
 * SQLite are not the phone: the limits are loose, only a gross regression fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogReplicaPerfTest {
  private val db = inMemoryReplica()
  private val reader = CatalogReader(db, { emptyList() }, { _, _ -> emptyList() }, { 0L }, { 1_000L })

  @After
  fun tearDown() = db.close()

  private inline fun timed(label: String, block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    val ms = (System.nanoTime() - start) / 1_000_000
    println("CatalogReplicaPerfTest: $label: $ms ms")
    return ms
  }

  @Test
  fun tenThousandItems() = runBlocking {
    db.libraries().upsertAll(listOf(libraryRow("srv", "lib1")))
    val genres = listOf("Fantasy", "Sci-Fi", "Mystery", "History")
    timed("insert 10 000 rows in pages of 500") {
      (0 until 10_000).chunked(ITEMS_PAGE_SIZE).forEach { page ->
        db.items().upsertAll(page.map {
          itemEntity("srv", "li$it", title = "Book ${(it * 7919) % 10_000}", addedAt = it.toLong())
                  .copy(genres = """["${genres[it % genres.size]}"]""", seriesName = if (it % 10 < 3) "Series ${it / 10}" else "")
        })
      }
    }
    db.series().upsertAll((0 until 1_000).map { seriesRow("srv", "s$it", name = "Series $it") })
    db.series().insertMembers((0 until 10_000).filter { it % 10 < 3 }.map { member("srv", "s${it / 10}", "li$it", "${it % 10 + 1}") })

    val queries = listOf(
            "title" to ItemsRequest("lib1", "media.metadata.title", false, null, false, false, 100, 0),
            "addedAt desc" to ItemsRequest("lib1", "addedAt", true, null, false, false, 100, 0),
            "genre filter" to ItemsRequest("lib1", "media.metadata.title", false, "genres.RmFudGFzeQ%3D%3D", false, false, 100, 0),
            "collapse series" to ItemsRequest("lib1", "media.metadata.title", false, null, true, false, 100, 0))
    for ((label, request) in queries) {
      reader.onReplicaChanged()
      val first = timed("first page, $label (rows + orderItems + 100 JSON)") { reader.items("srv", request) }
      val next = timed("next page, $label (from the cached order)") { reader.items("srv", request.copy(page = 1)) }
      assert(first < 20_000 && next < 5_000)
    }
    assertEquals(10_000, reader.items("srv", queries[0].second).getInt("total"))
    assertEquals(2_500, reader.items("srv", queries[2].second).getInt("total"))
  }
}
