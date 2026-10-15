package app.absplus.android.sync

import app.absplus.android.db.userdata.BookmarkEntity
import app.absplus.android.db.userdata.OutboxEntity
import app.absplus.android.server.RefreshResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [OutboxPusher] against a fake server, on in-memory Room under Robolectric. Synthetic ids only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxPusherTest {
  private val db = inMemoryUserData()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val api = FakeApi()
  private val config = fakeServer()
  private var now = 10_000L
  private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private lateinit var pusher: OutboxPusher
  private val store = UserDataStore(db, DataChangeNotifier(10, scope) { _, _ -> }, { now }, { pusher.inFlightId })

  private fun pusher(refresh: RefreshResult = RefreshResult.Transient) =
          OutboxPusher(db, syncHttp(api, refresh), store, { now }, { logs.add(it) }).also { pusher = it }

  init {
    pusher()
  }

  @After
  fun tearDown() {
    scope.cancel()
    db.close()
  }

  private suspend fun all(): List<OutboxEntity> {
    val keys = listOf(bookmarkItemKey("li1", 1.0), bookmarkItemKey("li1", 2.0), bookmarkItemKey("li1", 3.0),
            bookmarkItemKey("li1", 754.0), progressItemKey("li1", null))
    return keys.flatMap { db.outbox().byItemKey("srv", it) }.sortedBy { it.id }
  }

  private fun json(body: String?) = JSONObject(body!!)

  @Test
  fun sendsSequentiallyAndDeletesOnlyAfter2xx() = runBlocking {
    for (t in listOf(1.0, 2.0, 3.0)) store.addBookmark("srv", "li1", t, "Mark $t")
    val queuedWhenSent = mutableListOf<Int>()
    api.handler = { queuedWhenSent.add(runBlocking { db.outbox().count("srv") }); 200 to "{}" }

    val result = pusher.pushAll(config)

    assertEquals(PushResult(3, 0, 0, null), result)
    assertEquals(List(3) { "POST /api/me/item/li1/bookmark" }, api.paths())
    assertEquals(listOf(1.0, 2.0, 3.0), api.requests.map { json(it.body).getDouble("time") })
    assertEquals("each row is deleted only after its answer", listOf(3, 2, 1), queuedWhenSent)
    assertTrue(db.bookmarks().getVisible("srv").none { it.pending })
  }

  @Test
  fun notFoundDropsRowAndContinues() = runBlocking {
    store.addBookmark("srv", "li1", 1.0, "Gone item")
    db.mediaProgress().upsert(progressRow(10.0, lastUpdate = 5L, pending = false))
    store.queueProgressPatch("srv", "li1", null, listOf("currentTime"))
    store.addBookmark("srv", "li1", 2.0, "Fine")
    api.handler = { request -> if (request.body?.contains("Fine") == true) 200 to "{}" else 404 to "Not Found" }

    val result = pusher.pushAll(config)

    assertEquals(PushResult(1, 2, 0, null), result)
    assertEquals(listOf("POST /api/me/item/li1/bookmark", "PATCH /api/me/progress/li1", "POST /api/me/item/li1/bookmark"), api.paths())
    assertTrue(logs.any { it.contains(OutboxKind.BOOKMARK_ADD) && it.contains(bookmarkItemKey("li1", 1.0)) && it.contains("404") })
    assertTrue(logs.any { it.contains(OutboxKind.PROGRESS_PATCH) && it.contains(progressItemKey("li1", null)) })
    assertEquals("the rejected bookmark is gone", listOf(2.0), db.bookmarks().getAll("srv").map { it.time })
    assertFalse(db.mediaProgress().get("srv", "li1", "")!!.pending)
  }

  @Test
  fun otherClientErrorDropsLikeNotFound() = runBlocking {
    store.addBookmark("srv", "li1", 1.0, "Bad")
    api.handler = { 400 to "Bad Request" }

    assertEquals(PushResult(0, 1, 0, null), pusher.pushAll(config))
    assertTrue(db.bookmarks().getAll("srv").isEmpty())
  }

  @Test
  fun serverErrorBacksOff() = runBlocking {
    store.addBookmark("srv", "li1", 1.0, "Mark")
    api.handler = { 502 to "Bad Gateway" }

    val delays = mutableListOf<Long>()
    repeat(5) { i ->
      val result = pusher.pushAll(config)
      assertEquals(PushResult(0, 0, 1, StopReason.BACKOFF), result)
      val row = all().single()
      assertEquals(i + 1, row.attempts)
      assertEquals("HTTP 502", row.lastError)
      delays.add(row.nextAttemptAt - now)
      assertEquals("nothing is sent before the row is due", PushResult(0, 0, 1, StopReason.BACKOFF), pusher.pushAll(config))
      now = row.nextAttemptAt
    }
    assertEquals(listOf(1_000L, 5_000L, 30_000L, 300_000L, 300_000L), delays)
    assertEquals(5, api.requests.size)
  }

  @Test
  fun unreachableStopsQueue() = runBlocking {
    store.addBookmark("srv", "li1", 1.0, "One")
    store.addBookmark("srv", "li1", 2.0, "Two")
    api.handler = { throw IOException("connection refused") }

    val result = pusher.pushAll(config)

    assertEquals(PushResult(0, 0, 2, StopReason.UNREACHABLE), result)
    assertEquals(1, api.requests.size)
    val first = all().first()
    assertEquals(1, first.attempts)
    assertTrue("an unanswered row is due again as soon as the server is back", first.nextAttemptAt <= now)
  }

  @Test
  fun unauthorizedStopsAndKeepsRows() = runBlocking {
    store.addBookmark("srv", "li1", 1.0, "One")
    store.addBookmark("srv", "li1", 2.0, "Two")
    api.handler = { 401 to "Unauthorized" }
    pusher(refresh = RefreshResult.Rejected)

    val result = pusher.pushAll(config)

    assertEquals(PushResult(0, 0, 2, StopReason.UNAUTHORIZED), result)
    assertEquals(1, api.requests.size)
    assertEquals(listOf(1.0, 2.0), db.bookmarks().getVisible("srv").map { it.time })
    assertTrue(db.bookmarks().getVisible("srv").all { it.pending })
  }

  @Test
  fun patchBodyComesFromRowAtSendTime() = runBlocking {
    db.mediaProgress().upsert(progressRow(0.0, lastUpdate = 50L))
    store.queueProgressPatch("srv", "li1", null, listOf("currentTime", "progress"))
    db.mediaProgress().upsert(progressRow(600.0, lastUpdate = 70L))

    assertEquals(PushResult(1, 0, 0, null), pusher.pushAll(config))

    val body = json(api.requests.single().body)
    assertEquals(setOf("currentTime", "progress", "lastUpdate"), body.keys().asSequence().toSet())
    assertEquals(600.0, body.getDouble("currentTime"), 0.0)
    assertEquals(0.6, body.getDouble("progress"), 0.0)
    assertEquals(70L, body.getLong("lastUpdate"))

    // No row, or a row that is no longer pending: nothing to send
    store.queueProgressPatch("srv", "li1", null, listOf("currentTime"))
    db.mediaProgress().upsert(progressRow(600.0, lastUpdate = 70L, pending = false))
    store.queueProgressPatch("srv", "li1", "ep-gone", listOf("currentTime"))
    logs.clear()
    assertEquals(PushResult(0, 2, 0, null), pusher.pushAll(config))
    assertEquals(1, api.requests.size)
    assertEquals(2, logs.size)
  }

  @Test
  fun patchSendsOnlyListedFields() = runBlocking {
    db.mediaProgress().upsert(progressRow(600.0, lastUpdate = 70L).copy(isFinished = true, episodeId = "ep1"))
    store.queueProgressPatch("srv", "li1", "ep1", listOf("isFinished"))

    pusher.pushAll(config)

    val request = api.requests.single()
    assertEquals("PATCH /api/me/progress/li1/ep1", request.toString())
    val body = json(request.body)
    assertEquals(setOf("isFinished", "lastUpdate"), body.keys().asSequence().toSet())
    assertTrue(body.getBoolean("isFinished"))
  }

  @Test
  fun rowUpdatedDuringPatchQueuesFollowUp() = runBlocking {
    db.mediaProgress().upsert(progressRow(100.0, lastUpdate = 100L))
    store.queueProgressPatch("srv", "li1", null, listOf("currentTime"))
    api.handler = { request ->
      // A playback tick lands while the first PATCH is on the wire
      if (api.requests.size == 1) runBlocking { db.mediaProgress().upsert(progressRow(200.0, lastUpdate = 200L)) }
      200 to "{}"
    }

    val result = pusher.pushAll(config)

    assertEquals(PushResult(2, 0, 0, null), result)
    assertEquals(listOf(100L, 200L), api.requests.map { json(it.body).getLong("lastUpdate") })
    assertEquals(200.0, json(api.requests[1].body).getDouble("currentTime"), 0.0)
    assertEquals(setOf("currentTime", "lastUpdate"), json(api.requests[1].body).keys().asSequence().toSet())
    assertFalse(db.mediaProgress().get("srv", "li1", "")!!.pending)
  }

  @Test
  fun bookmarkRemovePathUsesIntegerTime() = runBlocking {
    db.bookmarks().upsert(BookmarkEntity("srv", "li1", 754.0, "Synced", 1L, null, pending = false))
    store.removeBookmark("srv", "li1", 754.0)

    assertEquals(PushResult(1, 0, 0, null), pusher.pushAll(config))

    assertEquals(listOf("DELETE /api/me/item/li1/bookmark/754"), api.paths())
    assertNull(api.requests.single().body)
    assertTrue(db.bookmarks().getAll("srv").isEmpty())
  }

  @Test
  fun renameIsAPatchWithTimeAndTitle() = runBlocking {
    db.bookmarks().upsert(BookmarkEntity("srv", "li1", 754.5, "Synced", 1L, null, pending = false))
    store.renameBookmark("srv", "li1", 754.5, "Renamed")

    pusher.pushAll(config)

    val request = api.requests.single()
    assertEquals("PATCH /api/me/item/li1/bookmark", request.toString())
    assertEquals(754.5, json(request.body).getDouble("time"), 0.0)
    assertEquals("Renamed", json(request.body).getString("title"))
    assertFalse(db.bookmarks().getVisible("srv").single().pending)
  }

  @Test
  fun progressDeleteTreatsNotFoundAsDone() = runBlocking {
    for (code in listOf(200, 404)) {
      db.outbox().insert(OutboxEntity(serverConfigId = "srv", kind = OutboxKind.PROGRESS_DELETE, itemKey = progressItemKey("li1", null),
              payload = OutboxPayload.progressDelete("mp1"), createdAt = 1L))
      api.handler = { code to "" }
      assertEquals("HTTP $code", PushResult(1, 0, 0, null), pusher.pushAll(config))
    }
    assertEquals(List(2) { "DELETE /api/me/progress/mp1" }, api.paths())
  }

  @Test
  fun unreadableAndUnknownRowsAreDroppedWithoutARequest() = runBlocking {
    db.outbox().insert(OutboxEntity(serverConfigId = "srv", kind = OutboxKind.PROGRESS_DELETE, itemKey = progressItemKey("li1", null),
            payload = "{}", createdAt = 1L))
    db.outbox().insert(OutboxEntity(serverConfigId = "srv", kind = "progress_from_the_future", itemKey = progressItemKey("li1", null),
            payload = "{}", createdAt = 2L))

    assertEquals(PushResult(0, 2, 0, null), pusher.pushAll(config))
    assertTrue(api.requests.isEmpty())
    assertTrue(logs.any { it.contains("progress_from_the_future") })
  }

  @Test
  fun actionDuringDelayedRequestIsSentAfterIt() = runBlocking {
    val serverBookmarks = Collections.synchronizedSet(mutableSetOf<String>())
    val arrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    api.handler = { request ->
      if (api.requests.size == 1) {
        arrived.countDown()
        release.await(5, TimeUnit.SECONDS)
      }
      when (request.method) {
        "POST" -> serverBookmarks.add(json(request.body).getDouble("time").toString())
        "DELETE" -> serverBookmarks.remove(request.path.substringAfterLast('/').toDouble().toString())
      }
      200 to "{}"
    }
    store.addBookmark("srv", "li1", 754.0, "Mark")
    val push = scope.async { pusher.pushAll(config) }
    assertTrue(arrived.await(5, TimeUnit.SECONDS))

    store.removeBookmark("srv", "li1", 754.0)
    release.countDown()

    assertEquals(PushResult(2, 0, 0, null), push.await())
    assertEquals(listOf("POST /api/me/item/li1/bookmark", "DELETE /api/me/item/li1/bookmark/754"), api.paths())
    assertTrue(serverBookmarks.isEmpty())
    assertTrue(db.bookmarks().getAll("srv").isEmpty())
  }

  @Test
  fun patchDuringDelayedPatchIsSentAfterIt() = runBlocking {
    val arrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pendingAtSecond = mutableListOf<Boolean>()
    api.handler = {
      if (api.requests.size == 1) {
        arrived.countDown()
        release.await(5, TimeUnit.SECONDS)
      } else {
        pendingAtSecond.add(runBlocking { db.mediaProgress().get("srv", "li1", "")!!.pending })
      }
      200 to "{}"
    }
    db.mediaProgress().upsert(progressRow(100.0, lastUpdate = 100L))
    store.queueProgressPatch("srv", "li1", null, listOf("currentTime"))
    val push = scope.async { pusher.pushAll(config) }
    assertTrue(arrived.await(5, TimeUnit.SECONDS))

    store.queueProgressPatch("srv", "li1", null, listOf("isFinished"))
    release.countDown()

    assertEquals(PushResult(2, 0, 0, null), push.await())
    assertEquals(listOf(true), pendingAtSecond)
    assertEquals(setOf("isFinished", "lastUpdate"), json(api.requests[1].body).keys().asSequence().toSet())
    assertFalse(db.mediaProgress().get("srv", "li1", "")!!.pending)
  }

  @Test
  fun rowOfAnotherUserOfTheSameServerIsDropped() = runBlocking {
    // The saved server was signed in again as another user under the same id: the token now belongs to them
    for ((owner, title) in listOf("someone-else" to "Theirs", "user" to "Mine")) {
      db.outbox().insert(OutboxEntity(serverConfigId = "srv", kind = OutboxKind.BOOKMARK_ADD, itemKey = bookmarkItemKey("li1", title.length.toDouble()),
              payload = OutboxPayload.withUserId(OutboxPayload.bookmark(title.length.toDouble(), title), owner), createdAt = 1L))
    }
    db.outbox().insert(OutboxEntity(serverConfigId = "srv", kind = OutboxKind.BOOKMARK_ADD, itemKey = bookmarkItemKey("li1", 9.0),
            payload = OutboxPayload.bookmark(9.0, "Queued before owners"), createdAt = 1L))

    assertEquals(PushResult(2, 1, 0, null), pusher.pushAll(config))

    assertEquals(listOf("Mine", "Queued before owners"), api.requests.map { json(it.body).getString("title") })
    assertTrue(logs.single().contains("another user"))
    assertTrue("no user id in the log", logs.none { it.contains("someone-else") })
  }
}
