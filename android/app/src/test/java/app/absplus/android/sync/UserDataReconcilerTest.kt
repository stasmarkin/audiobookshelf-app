package app.absplus.android.sync

import app.absplus.android.db.userdata.BookmarkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [UserDataReconciler] against a fake server, on in-memory Room under Robolectric. Synthetic ids only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDataReconcilerTest {
  private val db = inMemoryUserData()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val api = FakeApi()
  private val store = UserDataStore(db, DataChangeNotifier(10, scope) { _, _ -> }, { 1L }, { null })
  private val reconciler = UserDataReconciler(syncHttp(api), store)

  private val twoBookmarks = """[{"libraryItemId":"li1","time":754,"title":"One","createdAt":5},
    {"libraryItemId":"li2","time":12.5,"title":"Two","createdAt":6}]"""

  @After
  fun tearDown() {
    scope.cancel()
    db.close()
  }

  private suspend fun visible() = db.bookmarks().getVisible("srv").map { it.libraryItemId to it.time }

  @Test
  fun bookmarksFromApiMeBelow2_36() = runBlocking {
    api.handler = { 200 to """{"id":"user","bookmarks":$twoBookmarks,"mediaProgress":[]}""" }

    assertTrue(reconciler.reconcileBookmarks(fakeServer(version = "2.35.1")).isSuccess)

    assertEquals(listOf("GET /api/me"), api.paths())
    assertEquals(listOf("li1" to 754.0, "li2" to 12.5), visible())
  }

  @Test
  fun bookmarksFromSplitEndpointFrom2_36() = runBlocking {
    api.handler = { 200 to """{"bookmarks":$twoBookmarks}""" }
    assertTrue(reconciler.reconcileBookmarks(fakeServer(version = "2.36.0")).isSuccess)
    assertEquals(listOf("GET /api/me/bookmarks"), api.paths())
    assertEquals(2, visible().size)

    api.handler = { 200 to "[]" }
    assertTrue("a bare array is read too", reconciler.reconcileBookmarks(fakeServer(version = "v2.40.1-beta")).isSuccess)
    assertTrue(visible().isEmpty())
  }

  @Test
  fun failedPullChangesNothing() = runBlocking {
    db.bookmarks().upsert(BookmarkEntity("srv", "li1", 1.0, "Synced", 1L, null, pending = false))
    for (answer in listOf(500 to "", 200 to """{"id":"user"}""", 200 to "not json")) {
      api.handler = { answer }
      assertFalse(answer.toString(), reconciler.reconcileBookmarks(fakeServer()).isSuccess)
    }
    assertEquals(listOf("li1" to 1.0), visible())
  }

  @Test
  fun userUpdatedAppliesBookmarks() = runBlocking {
    db.bookmarks().upsert(BookmarkEntity("srv", "li9", 1.0, "Deleted elsewhere", 1L, null, pending = false))
    db.bookmarks().upsert(BookmarkEntity("srv", "li8", 2.0, "Made offline", 1L, null, pending = true))

    reconciler.applyUserObject("srv", """{"id":"user","bookmarks":$twoBookmarks}""")

    assertEquals(listOf("li1" to 754.0, "li2" to 12.5, "li8" to 2.0), visible())
    assertTrue(api.requests.isEmpty())

    reconciler.applyUserObject("srv", """{"id":"user","permissions":{}}""")
    assertEquals("a user object without bookmarks deletes nothing", 3, visible().size)
  }

  @Test
  fun versionComparison() {
    assertTrue(versionAtLeast("2.36.0", "2.36.0"))
    assertTrue(versionAtLeast("2.37.1", "2.36.0"))
    assertTrue(versionAtLeast("3.0", "2.36.0"))
    assertTrue(versionAtLeast("v2.36.0-beta.1", "2.36.0"))
    assertFalse(versionAtLeast("2.35.9", "2.36.0"))
    assertFalse(versionAtLeast("2.4.0", "2.36.0"))
    assertFalse(versionAtLeast(null, "2.36.0"))
    assertFalse(versionAtLeast("", "2.36.0"))
  }
}
