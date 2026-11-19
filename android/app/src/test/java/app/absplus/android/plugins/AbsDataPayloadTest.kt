package app.absplus.android.plugins

import app.absplus.android.sync.AuthorsRequest
import app.absplus.android.sync.CollectionBooksRequest
import app.absplus.android.sync.CollectionsRequest
import app.absplus.android.sync.ItemsRequest
import app.absplus.android.sync.OpenOutcome
import app.absplus.android.sync.OpenResult
import app.absplus.android.sync.RecentEpisodesRequest
import app.absplus.android.sync.PersonalizedRequest
import app.absplus.android.sync.SearchRequest
import app.absplus.android.sync.PlaylistItemsRequest
import app.absplus.android.sync.PlaylistsRequest
import app.absplus.android.sync.SeriesRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Argument parsing of the AbsData bridge, kept out of PluginCall so it can be tested. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AbsDataPayloadTest {
  @Test
  fun arrayPayloadIsAccepted() {
    val array = JSONObject().put("event", "items_added").put("payload", JSONArray().put(JSONObject().put("id", "li1")).put(JSONObject().put("id", "li2")))
    assertEquals("""[{"id":"li1"},{"id":"li2"}]""", eventPayloadJson(array))
    val single = JSONObject().put("event", "item_added").put("payload", JSONObject().put("id", "li1"))
    assertEquals("""{"id":"li1"}""", eventPayloadJson(single))
    assertNull(eventPayloadJson(JSONObject().put("event", "item_added")))
    assertNull(eventPayloadJson(JSONObject().put("payload", "text")))
  }

  @Test
  fun limitAbove100IsRejected() {
    for (limit in listOf(101, 0, -1)) {
      try {
        itemsRequest(JSONObject().put("libraryId", "lib1").put("limit", limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
  }

  @Test
  fun itemsRequestReadsTheShelfArguments() {
    val request = itemsRequest(JSONObject().put("libraryId", "lib1").put("limit", 100).put("page", 2).put("sort", "addedAt")
            .put("desc", 1).put("filter", "genres.Rm9v").put("collapseseries", true).put("sortingIgnorePrefix", "1"))
    assertEquals(ItemsRequest("lib1", "addedAt", true, "genres.Rm9v", true, true, 100, 2), request)
    val plain = itemsRequest(JSONObject().put("libraryId", "lib1").put("limit", 20).put("sort", ""))
    assertEquals(ItemsRequest("lib1", null, false, null, false, false, 20, 0), plain)
  }

  @Test
  fun seriesRequestReadsTheTabArgumentsAndRejectsMoreThan100() {
    assertEquals(SeriesRequest("lib1", "name", true, true, 100, 1),
            seriesRequest(JSONObject().put("libraryId", "lib1").put("sort", "name").put("desc", true).put("sortingIgnorePrefix", 1).put("limit", 100).put("page", 1)))
    for (limit in listOf(101, 0)) {
      try {
        seriesRequest(JSONObject().put("libraryId", "lib1").put("limit", limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
  }

  @Test
  fun authorsRequestRejectsMoreThan100() {
    assertEquals(AuthorsRequest("lib1", 100, 2), authorsRequest(JSONObject().put("libraryId", "lib1").put("limit", 100).put("page", 2)))
    for (limit in listOf(101, 0)) {
      try {
        authorsRequest(JSONObject().put("libraryId", "lib1").put("limit", limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
  }

  @Test
  fun collectionsRequestsRejectLimitsAboveTheirCap() {
    assertEquals(CollectionsRequest("lib1", 20, 3), collectionsRequest(JSONObject().put("libraryId", "lib1").put("limit", 20).put("page", 3)))
    for (limit in listOf(21, 0)) {
      try {
        collectionsRequest(JSONObject().put("libraryId", "lib1").put("limit", limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("each collection carries preview books", "limit must be 1..20", expected.message)
      }
    }
    assertEquals(CollectionBooksRequest("c1", 100, 2), collectionBooksRequest(JSONObject().put("id", "c1").put("limit", 100).put("page", 2)))
    for (limit in listOf(101, 0)) {
      try {
        collectionBooksRequest(JSONObject().put("id", "c1").put("limit", limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
    try {
      collectionBooksRequest(JSONObject().put("limit", 10))
      fail("no id")
    } catch (expected: IllegalArgumentException) {
      assertEquals("id is required", expected.message)
    }
  }

  @Test
  fun playlistsRequestsRejectLimitsAboveTheirCap() {
    assertEquals(PlaylistsRequest("lib1", 20, 3), playlistsRequest(JSONObject().put("libraryId", "lib1").put("limit", 20).put("page", 3)))
    for (limit in listOf(21, 0)) {
      try {
        playlistsRequest(JSONObject().put("libraryId", "lib1").put("limit", limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("each playlist carries preview items", "limit must be 1..20", expected.message)
      }
    }
    assertEquals(PlaylistItemsRequest("p1", 100, 2), playlistItemsRequest(JSONObject().put("id", "p1").put("limit", 100).put("page", 2)))
    for (limit in listOf(101, 0)) {
      try {
        playlistItemsRequest(JSONObject().put("id", "p1").put("limit", limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
    try {
      playlistItemsRequest(JSONObject().put("limit", 10))
      fail("no id")
    } catch (expected: IllegalArgumentException) {
      assertEquals("id is required", expected.message)
    }
  }

  @Test
  fun homeAndSearchArgumentsAreValidated() {
    assertEquals(PersonalizedRequest("lib1", 10), personalizedRequest(JSONObject().put("libraryId", "lib1")))
    assertEquals(PersonalizedRequest("lib1", 25), personalizedRequest(JSONObject().put("libraryId", "lib1").put("limit", 25)))
    assertEquals(SearchRequest("lib1", "abc", 12), searchRequest(JSONObject().put("libraryId", "lib1").put("q", "abc")))
    assertEquals("a blank query is allowed: an empty answer", SearchRequest("lib1", "  ", 3),
            searchRequest(JSONObject().put("libraryId", "lib1").put("q", "  ").put("limit", "3")))
    val rejected = listOf<Pair<String, () -> Unit>>(
            "limit must be 1..25" to { personalizedRequest(JSONObject().put("libraryId", "lib1").put("limit", 0)) },
            "limit must be 1..25" to { personalizedRequest(JSONObject().put("libraryId", "lib1").put("limit", 26)) },
            "limit must be 1..25" to { searchRequest(JSONObject().put("libraryId", "lib1").put("q", "a").put("limit", 26)) },
            "limit must be 1..25" to { searchRequest(JSONObject().put("libraryId", "lib1").put("q", "a").put("limit", 0)) },
            "q is required" to { searchRequest(JSONObject().put("libraryId", "lib1")) },
            "libraryId is required" to { personalizedRequest(JSONObject()) },
            "libraryId is required" to { searchRequest(JSONObject().put("q", "a")) })
    for ((message, call) in rejected) {
      try {
        call()
        fail(message)
      } catch (expected: IllegalArgumentException) {
        assertEquals(message, expected.message)
      }
    }
  }

  @Test
  fun recentEpisodesArgumentsAreValidated() {
    assertEquals(RecentEpisodesRequest("lib1", 50, 2), recentEpisodesRequest(JSONObject().put("libraryId", "lib1").put("limit", 50).put("page", 2)))
    assertEquals(RecentEpisodesRequest("lib1", 100, 0), recentEpisodesRequest(JSONObject().put("libraryId", "lib1").put("limit", "100")))
    for (limit in listOf(0, 101)) {
      try {
        recentEpisodesRequest(JSONObject().put("libraryId", "lib1").put("limit", limit))
        fail("limit $limit")
      } catch (expected: IllegalArgumentException) {
        assertEquals("limit must be 1..100", expected.message)
      }
    }
    try {
      recentEpisodesRequest(JSONObject().put("limit", 10))
      fail("no library")
    } catch (expected: IllegalArgumentException) {
      assertEquals("libraryId is required", expected.message)
    }
  }

  @Test
  fun itemAnswerPrefersTheServerItemAfterARefresh() {
    val replicaState = JSONObject().put("reconciledAt", 5).put("complete", true)
    val replicaAnswer = JSONObject().put("serverConfigId", "srv").put("detailLevel", "minified").put("replica", replicaState)
            .put("libraryItem", JSONObject().put("id", "li1").put("media", JSONObject().put("numChapters", 2)))
    val serverItem = """{"id":"li1","media":{"chapters":[{"id":0,"title":"One"},{"id":1,"title":"Two"}]}}"""

    for (result in listOf(OpenResult.UPDATED, OpenResult.NOT_IN_REPLICA)) {
      val answer = itemAnswer(OpenOutcome(result, serverItem), replicaAnswer)
      assertEquals(2, answer.getJSONObject("libraryItem").getJSONObject("media").getJSONArray("chapters").length())
      assertEquals("expanded", answer.getString("detailLevel"))
      assertTrue(answer.getBoolean("fromServer"))
      assertEquals("srv", answer.getString("serverConfigId"))
      assertEquals(5, answer.getJSONObject("replica").getInt("reconciledAt"))
    }

    val removed = itemAnswer(OpenOutcome(OpenResult.REMOVED), replicaAnswer)
    assertTrue("the replica row may still wait for the end of a pass", removed.isNull("libraryItem"))
    assertTrue(removed.getBoolean("removed"))
    assertEquals("srv", removed.getString("serverConfigId"))
    assertEquals(5, removed.getJSONObject("replica").getInt("reconciledAt"))

    for (outcome in listOf(OpenOutcome(OpenResult.UP_TO_DATE), OpenOutcome(OpenResult.SKIPPED), OpenOutcome(OpenResult.FAILED), null)) {
      val answer = itemAnswer(outcome, replicaAnswer)
      assertEquals(replicaAnswer.toString(), answer.toString())
    }
  }
}
