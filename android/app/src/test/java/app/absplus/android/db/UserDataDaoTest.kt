package app.absplus.android.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.db.userdata.BookmarkEntity
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.db.userdata.OutboxEntity
import app.absplus.android.db.userdata.UserDataDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** userdata.db v1 DAOs on Room under Robolectric. Synthetic ids only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDataDaoTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val db = Room.inMemoryDatabaseBuilder(context, UserDataDatabase::class.java).build()

  @After
  fun tearDown() = db.close()

  private fun progress(episodeId: String, currentTime: Double) = MediaProgressEntity(
          serverConfigId = "srv", libraryItemId = "li1", episodeId = episodeId, serverProgressId = null,
          duration = 1000.0, progress = currentTime / 1000.0, currentTime = currentTime, isFinished = false,
          hideFromContinueListening = false, ebookLocation = null, ebookProgress = null, lastUpdate = 1L,
          startedAt = 1L, finishedAt = null, source = "device", pending = false)

  private fun bookmark(time: Double, deletedAt: Long? = null) =
          BookmarkEntity("srv", "li1", time, "Mark $time", createdAt = 1L, deletedAt = deletedAt, pending = deletedAt != null)

  private fun outbox(itemKey: String, nextAttemptAt: Long) =
          OutboxEntity(serverConfigId = "srv", kind = "bookmark_add", itemKey = itemKey, payload = "{}",
                  createdAt = 1L, nextAttemptAt = nextAttemptAt)

  @Test
  fun progressPrimaryKeySeparatesEpisodes() = runBlocking {
    val dao = db.mediaProgress()
    dao.upsert(progress("", 10.0))
    dao.upsert(progress("ep1", 20.0))

    assertEquals(2, dao.getAll("srv").size)
    assertEquals(10.0, dao.get("srv", "li1", "")!!.currentTime, 0.0)
    assertEquals(20.0, dao.get("srv", "li1", "ep1")!!.currentTime, 0.0)
  }

  @Test
  fun visibleBookmarksExcludeTombstones() = runBlocking {
    val dao = db.bookmarks()
    dao.upsert(bookmark(10.0))
    dao.upsert(bookmark(20.0, deletedAt = 5L))

    assertEquals(listOf(10.0), dao.getVisible("srv").map { it.time })
    assertEquals(listOf(10.0, 20.0), dao.getAll("srv").map { it.time })
    assertEquals(listOf(10.0, 20.0), dao.getForItem("srv", "li1").map { it.time })
  }

  @Test
  fun outboxNextDueRespectsOrderAndBackoff() = runBlocking {
    val dao = db.outbox()
    val first = dao.insert(outbox("li1@1", nextAttemptAt = 1_000))
    dao.insert(outbox("li1@2", nextAttemptAt = 0))

    // The first row waits for its backoff, so the second, already due, must not overtake it
    assertNull(dao.nextDue("srv", now = 500))
    assertEquals(first, dao.nextDue("srv", now = 1_000)!!.id)
    assertNull(dao.nextDue("other", now = 1_000))
    assertEquals(2, dao.count("srv"))
  }

  @Test
  fun dataSurvivesReopeningTheDatabaseFile() = runBlocking {
    val name = "userdata-reopen-test.db"
    context.deleteDatabase(name)
    val first = UserDataDatabase.open(context, name)
    first.bookmarks().upsert(bookmark(754.0))
    first.outbox().insert(outbox("li1@754", nextAttemptAt = 0))
    first.close()

    // A cold start without a network: the bookmark and its unsent action come back from the file
    val reopened = UserDataDatabase.open(context, name)
    try {
      assertEquals(listOf(754.0), reopened.bookmarks().getVisible("srv").map { it.time })
      assertNotNull(reopened.outbox().nextDue("srv", now = 0))
    } finally {
      reopened.close()
      context.deleteDatabase(name)
    }
  }
}
