package app.absplus.android.sync

import app.absplus.android.db.userdata.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What `AbsData.getUserData` and `getMediaProgress` hand to the WebView. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDataJsonTest {
  @Test
  fun progressHasTheServerShape() {
    val book = UserDataJson.progress(progressRow(300.0, lastUpdate = 50L).copy(serverProgressId = null))
    assertTrue("a record the server has not named yet", book.isNull("id"))
    assertTrue("a book has no episode", book.isNull("episodeId"))
    assertTrue(book.isNull("ebookLocation"))
    assertTrue(book.isNull("finishedAt"))
    assertEquals(300.0, book.getDouble("currentTime"), 0.0)
    assertEquals(50L, book.getLong("lastUpdate"))
    assertFalse(book.getBoolean("isFinished"))
    assertFalse("no device-only fields", book.has("pending") || book.has("source") || book.has("serverConfigId"))

    val episode = UserDataJson.progress(progressRow(1.0, lastUpdate = 1L, episodeId = "ep1"))
    assertEquals("ep1", episode.getString("episodeId"))
    assertEquals("mp1", episode.getString("id"))
  }

  @Test
  fun userDataWithAndWithoutAUser() {
    val bookmarks = listOf(BookmarkEntity("srv", "li1", 754.0, "Mark", 5L, null, false))
    val none = UserDataJson.userData(null, emptyList(), bookmarks)
    assertTrue(none.isNull("user"))
    assertEquals(0, none.getJSONArray("mediaProgress").length())
    assertEquals("Mark", none.getJSONArray("bookmarks").getJSONObject(0).getString("title"))

    val known = UserDataJson.userData("""{"id":"user","permissions":{"download":true}}""", listOf(progressRow(1.0, 1L)), emptyList())
    assertTrue(known.getJSONObject("user").getJSONObject("permissions").getBoolean("download"))
    assertEquals(1, known.getJSONArray("mediaProgress").length())
    assertTrue("an unreadable user object is no user", UserDataJson.userData("not json", emptyList(), emptyList()).isNull("user"))
  }
}
