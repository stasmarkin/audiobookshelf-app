package app.absplus.android.player

import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.sync.CatalogJson
import com.fasterxml.jackson.databind.JsonNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AwaitingDisplayTest {
  private fun fixture(name: String): JsonNode =
          CatalogJson.mapper.readTree(javaClass.getResource("/catalog/$name") ?: error("no fixture $name"))

  private fun progress(currentTime: Double, isFinished: Boolean = false, episodeId: String = "") = MediaProgressEntity(
          "srv", "li", episodeId, null, 7.0, 0.0, currentTime, isFinished, false, null, null, 1, 1, null, "device", false)

  @Test
  fun bookFromReplica() {
    val book = awaitingDisplayOf(fixture("book-minified.json"), null, progress(3.5), null, "srv")!!
    assertEquals("ccbae3d4-63eb-4eff-85b2-911093527fa5", book.libraryItemId)
    assertNull(book.episodeId)
    assertEquals("srv", book.serverConfigId)
    assertEquals("Мир: начало", book.title)
    assertEquals("John Smith", book.author)
    assertEquals(7.0, book.duration, 0.0)
    assertEquals(3.5, book.currentTime, 0.0)

    val podcast = fixture("podcast-minified.json")
    val episode = fixture("podcast-expanded.json")["media"]["episodes"][0]
    val shown = awaitingDisplayOf(podcast, episode, null, "content://cover", "srv")!!
    assertEquals(episode["id"].asText(), shown.episodeId)
    assertEquals("Episode 1", shown.title)
    assertEquals("the podcast is the episode's author", "Pod Author", shown.author)
    assertEquals(1.0, shown.duration, 0.0)
    assertEquals("no progress: from the start", 0.0, shown.currentTime, 0.0)
    assertEquals("content://cover", shown.coverUri)

    assertNull("no row in the replica: nothing to show", awaitingDisplayOf(null, null, progress(3.5), null, "srv"))
  }

  @Test
  fun aFinishedBookIsShownFromTheStart() {
    val book = awaitingDisplayOf(fixture("book-minified.json"), null, progress(7.0, isFinished = true), null, "srv")!!
    assertEquals(0.0, book.currentTime, 0.0)
  }

  @Test
  fun toJsonCarriesWhatThePlayerShows() {
    val json = awaitingDisplayOf(fixture("book-minified.json"), null, progress(2.0), null, "srv")!!.toJson()
    assertEquals("Мир: начало", json.getString("title"))
    assertEquals("John Smith", json.getString("author"))
    assertEquals("ccbae3d4-63eb-4eff-85b2-911093527fa5", json.getString("libraryItemId"))
    assertTrue(json.isNull("episodeId"))
    assertTrue(json.isNull("coverUri"))
    assertEquals(7.0, json.getDouble("duration"), 0.0)
    assertEquals(2.0, json.getDouble("currentTime"), 0.0)
  }
}
