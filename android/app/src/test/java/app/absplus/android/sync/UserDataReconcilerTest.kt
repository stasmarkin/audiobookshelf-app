package app.absplus.android.sync

import android.os.Looper
import app.absplus.android.db.userdata.BookmarkEntity
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
  private val changes = java.util.Collections.synchronizedList(mutableListOf<String>())
  /** Whether the merge of a user object ever ran on the main thread (the provider is called inside the merge). */
  @Volatile private var mergedOnMain = false
  private val store = UserDataStore(db, DataChangeNotifier(10, scope) { name, _ -> changes.add(name) }, { 1L }, { null },
          unsentSessionKeys = { mergedOnMain = mergedOnMain || Looper.getMainLooper().isCurrentThread; emptySet() })
  private val reconciler = UserDataReconciler(syncHttp(api), store, db, { 1L })

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

    assertTrue(reconciler.reconcileUser(fakeServer(version = "2.35.1")).isSuccess)

    assertEquals(listOf("GET /api/me"), api.paths())
    assertEquals(listOf("li1" to 754.0, "li2" to 12.5), visible())
  }

  @Test
  fun bookmarksFromSplitEndpointFrom2_36() = runBlocking {
    api.handler = { request -> 200 to if (request.path == "/api/me/progress") """{"mediaProgress":[]}""" else """{"bookmarks":$twoBookmarks}""" }
    assertTrue(reconciler.reconcileUser(fakeServer(version = "2.36.0")).isSuccess)
    assertEquals(listOf("GET /api/me/progress", "GET /api/me/bookmarks"), api.paths())
    assertEquals(2, visible().size)

    api.handler = { 200 to "[]" }
    assertTrue("a bare array is read too", reconciler.reconcileUser(fakeServer(version = "v2.40.1-beta")).isSuccess)
    assertTrue(visible().isEmpty())
  }

  @Test
  fun failedPullChangesNothing() = runBlocking {
    db.bookmarks().upsert(BookmarkEntity("srv", "li1", 1.0, "Synced", 1L, null, pending = false))
    for (answer in listOf(500 to "", 200 to """{"id":"user"}""", 200 to "not json")) {
      api.handler = { answer }
      assertFalse(answer.toString(), reconciler.reconcileUser(fakeServer()).isSuccess)
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

  @Test
  fun missingSplitEndpointFallsBackToApiMe() = runBlocking {
    api.handler = { request ->
      when (request.path) {
        "/api/me/progress" -> 200 to "[]"
        "/api/me/bookmarks" -> 404 to "Not Found"
        else -> 200 to """{"bookmarks":$twoBookmarks,"mediaProgress":[]}"""
      }
    }

    assertTrue(reconciler.reconcileUser(fakeServer(version = "2.36.0")).isSuccess)

    assertEquals(listOf("GET /api/me/progress", "GET /api/me/bookmarks", "GET /api/me"), api.paths())
    assertEquals(2, visible().size)
  }

  private fun progressJson(libraryItemId: String, currentTime: Double, lastUpdate: Long, id: String = "mp-$libraryItemId") =
          """{"id":"$id","libraryItemId":"$libraryItemId","episodeId":null,"duration":1000,"progress":${currentTime / 1000},
            "currentTime":$currentTime,"isFinished":false,"hideFromContinueListening":false,"ebookLocation":null,
            "ebookProgress":null,"lastUpdate":$lastUpdate,"startedAt":1,"finishedAt":null}"""

  private suspend fun progress(libraryItemId: String = "li1") = db.mediaProgress().get("srv", libraryItemId, "")

  @Test
  fun usesSplitEndpointsFrom2_36() = runBlocking {
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 5L, pending = false))
    api.handler = { request ->
      200 to if (request.path == "/api/me/progress") """{"mediaProgress":[${progressJson("li1", 300.0, 50L)}]}""" else "[]"
    }

    assertTrue(reconciler.reconcileUser(fakeServer(version = "2.36.0")).isSuccess)

    assertEquals(listOf("GET /api/me/progress", "GET /api/me/bookmarks"), api.paths())
    assertEquals(300.0, progress()!!.currentTime, 0.0)
    assertNull("no user object on this path", db.syncState().get("srv"))
  }

  @Test
  fun fallsBackToApiMeBelow2_36() = runBlocking {
    api.handler = { 200 to """{"id":"user","username":"u","token":"t-legacy","accessToken":"t-access","refreshToken":"t-refresh",
      "permissions":{"download":true},"mediaProgress":[${progressJson("li1", 300.0, 50L)}],"bookmarks":$twoBookmarks}""" }

    assertTrue(reconciler.reconcileUser(fakeServer(version = "2.35.1")).isSuccess)

    assertEquals(listOf("GET /api/me"), api.paths())
    assertEquals(300.0, progress()!!.currentTime, 0.0)
    assertEquals(2, visible().size)
    val state = db.syncState().get("srv")!!
    assertEquals(1L, state.lastMeAt)
    val user = JSONObject(state.userJson!!)
    assertEquals("u", user.getString("username"))
    assertTrue(user.getJSONObject("permissions").getBoolean("download"))
    for (gone in listOf("mediaProgress", "bookmarks", "token", "accessToken", "refreshToken")) assertFalse(gone, user.has(gone))
  }

  @Test
  fun missingProgressEndpointFallsBackToApiMe() = runBlocking {
    api.handler = { request ->
      if (request.path == "/api/me/progress") 404 to "Not Found"
      else 200 to """{"id":"user","mediaProgress":[${progressJson("li1", 300.0, 50L)}],"bookmarks":[]}"""
    }

    assertTrue(reconciler.reconcileUser(fakeServer(version = "2.36.0")).isSuccess)

    assertEquals(listOf("GET /api/me/progress", "GET /api/me"), api.paths())
    assertNotNull(progress())
  }

  @Test
  fun answerWithoutProgressDeletesNothing() = runBlocking {
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 5L, pending = false))
    api.handler = { 200 to """{"id":"user","bookmarks":[]}""" }

    assertFalse(reconciler.reconcileUser(fakeServer(version = "2.35.1")).isSuccess)

    assertNotNull(progress())
  }

  @Test
  fun fullPullDeletesProgressGoneFromTheServer() = runBlocking {
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 5L, pending = false))
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 5L, pending = true).copy(libraryItemId = "li2"))
    api.handler = { 200 to """{"id":"user","mediaProgress":[],"bookmarks":[]}""" }

    assertTrue(reconciler.reconcileUser(fakeServer(version = "2.35.1")).isSuccess)

    assertNull("deleted by another client", progress())
    assertNotNull("an unsent change stays", progress("li2"))
  }

  @Test
  fun userUpdatedDoesNotOverwritePendingRow() = runBlocking {
    db.mediaProgress().upsert(progressRow(500.0, lastUpdate = 100L, pending = true))
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 100L, pending = false).copy(libraryItemId = "li2"))
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 100L, pending = false).copy(libraryItemId = "li3"))
    val user = """{"id":"user","mediaProgress":[${progressJson("li1", 30.0, 999L)},${progressJson("li2", 700.0, 999L)}]}"""

    reconciler.applyUserObject("srv", user)

    assertEquals("the pending row is not touched", 500.0, progress()!!.currentTime, 0.0)
    assertEquals(100L, progress()!!.lastUpdate)
    assertEquals("the rest is merged", 700.0, progress("li2")!!.currentTime, 0.0)
    assertNotNull("a snapshot deletes nothing", progress("li3"))
    assertFalse("merged off the main thread", mergedOnMain)
    assertTrue(api.requests.isEmpty())
  }

  @Test
  fun userObjectIsKeptWithoutTokensAndChangesAreAnnounced() = runBlocking {
    val user = """{"id":"user","accessToken":"secret","settings":{"a":1},"mediaProgress":[],"bookmarks":[]}"""
    reconciler.applyUserObject("srv", user)
    eventually("user change announced") { changes.contains("user") }
    val state = db.syncState().get("srv")!!
    assertFalse(state.userJson!!.contains("secret"))
    assertNull("only a full pull sets lastMeAt", state.lastMeAt)

    changes.clear()
    reconciler.applyUserObject("srv", user)
    kotlinx.coroutines.delay(50)
    assertFalse("the same user again is not a change", changes.contains("user"))
  }

  @Test
  fun userItemProgressUpdatedMergesSingleRow() = runBlocking {
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 100L, pending = false))
    val event = """{"id":"x","sessionId":"s","deviceDescription":"d","data":${progressJson("li1", 400.0, 200L)}}"""

    assertTrue(reconciler.applyProgressEvent("srv", event))
    assertEquals(400.0, progress()!!.currentTime, 0.0)

    val older = """{"data":${progressJson("li1", 50.0, 150L)}}"""
    assertFalse(reconciler.applyProgressEvent("srv", older))
    assertEquals(400.0, progress()!!.currentTime, 0.0)
    assertFalse(reconciler.applyProgressEvent("srv", "not json"))
  }
}
