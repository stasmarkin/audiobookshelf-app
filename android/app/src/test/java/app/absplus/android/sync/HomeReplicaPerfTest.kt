package app.absplus.android.sync

import app.absplus.android.db.authorRow
import app.absplus.android.db.episodeRow
import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.seriesRow
import app.absplus.android.db.userdata.MediaProgressEntity
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home and search on a synthetic 10 000-book library (and 200 podcasts of 200 episodes), cold cache, JVM and
 * Robolectric SQLite: the release limits are 500 ms for the home and 300 ms for a search. Times are printed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeReplicaPerfTest {
  private val db = inMemoryReplica()
  private val now = 10_000_000_000L
  private var progress = emptyList<MediaProgressEntity>()
  private val reader = CatalogReader(db, { progress }, { _, ids -> progress.filter { it.libraryItemId in ids } }, { 0L }, { now }, { null })

  @After
  fun tearDown() = db.close()

  private inline fun timed(label: String, block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    val ms = (System.nanoTime() - start) / 1_000_000
    println("HomeReplicaPerfTest: $label: $ms ms")
    return ms
  }

  private fun row(id: String, episodeId: String = "", currentTime: Double, isFinished: Boolean, lastUpdate: Long) = MediaProgressEntity(
          "srv", id, episodeId, null, 100.0, 0.0, currentTime, isFinished, false, null, null, lastUpdate, 0, null, "server", false)

  private fun withKey(e: app.absplus.android.db.replica.LibraryItemEntity, numTracks: Int = 1) =
          e.copy(numTracks = numTracks, searchKey = CatalogJson.searchKey(CatalogJson.mapper.readTree(e.json)))

  @Test
  fun tenThousandItems() = runBlocking {
    db.libraries().upsertAll(listOf(libraryRow("srv", "lib1")))
    (0 until 10_000).chunked(ITEMS_PAGE_SIZE).forEach { page ->
      db.items().upsertAll(page.map {
        withKey(itemEntity("srv", "li$it", title = "Book $it", addedAt = now - it * 1_000_000L)
                .copy(genres = """["Genre ${it % 20}"]""", tags = """["tag ${it % 7}"]""", narratorName = "Narrator ${it % 300}"))
      })
    }
    db.series().upsertAll((0 until 1_000).map { seriesRow("srv", "s$it", name = "Series $it").copy(addedAt = now - it * 1_000L) })
    db.series().insertMembers((0 until 10_000).filter { it % 10 < 5 }.map { member("srv", "s${it / 10}", "li$it", "${it % 10 + 1}") })
    db.authors().upsertAll((0 until 500).map { authorRow("srv", "a$it", name = "Author $it", numBooks = 20).copy(addedAt = now - it * 1_000L) })
    progress = (0 until 3_000).map { row("li${it * 3}", currentTime = if (it % 3 == 0) 0.0 else 10.0, isFinished = it % 3 == 0, lastUpdate = now - it) }
    db.libraryState().upsert(LibraryStateEntity("srv", "lib1", 1, true, 1, 1, null, null, null))

    val home = timed("personalized, 10 000 books, cold") { reader.personalized("srv", PersonalizedRequest("lib1", 10)) }
    val search = timed("search \"book 12\", 10 000 books, cold") { reader.search("srv", SearchRequest("lib1", "book 12", 12)) }
    assertTrue("home $home ms", home < 500)
    assertTrue("search $search ms", search < 300)

    db.libraries().upsertAll(listOf(libraryRow("srv", "pods", 1, "podcast")))
    db.items().upsertAll((0 until 200).map { withKey(itemEntity("srv", "p$it", "pods", title = "Podcast $it", mediaType = "podcast", addedAt = now - it), numTracks = 0) })
    (0 until 200).forEach { p ->
      db.episodes().replaceForPodcast("srv", "p$p", (0 until 200).map { e ->
        episodeRow("srv", "p$p-e$e", "p$p", "pods", title = "Episode $e of $p", addedAt = now - (p * 200L + e) * 1_000L,
                searchKey = CatalogJson.episodeSearchKey("Episode $e of $p"))
      })
    }
    progress = progress + (0 until 2_000).map { row("p${it % 200}", "p${it % 200}-e${it / 200}", 10.0, it % 4 == 0, now - it) }
    val podcasts = timed("personalized, 200 podcasts x 200 episodes, cold") { reader.personalized("srv", PersonalizedRequest("pods", 10)) }
    assertTrue("podcast home $podcasts ms", podcasts < 500)

    // An update from 4.x offline: every key still "", the first search computes them once
    (0 until 10_000).chunked(500).forEach { chunk -> db.withTransaction { chunk.forEach { db.items().setSearchKey("srv", "li$it", "") } } }
    reader.onReplicaChanged()
    val first = timed("first search over 10 000 rows with stale keys") { reader.search("srv", SearchRequest("lib1", "book 12", 12)) }
    assertTrue("first search with stale keys $first ms", first < 3_000)
  }
}
