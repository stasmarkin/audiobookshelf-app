package app.absplus.android.sync

import app.absplus.android.db.authorRow
import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.itemEntity
import app.absplus.android.db.libraryRow
import app.absplus.android.db.member
import app.absplus.android.db.replica.CollectionEntity
import app.absplus.android.db.replica.FilterCodec
import app.absplus.android.db.seriesRow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.io.File
import java.io.IOException
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogReconcilerTest {
  private val db = inMemoryReplica()
  private val server = FakeCatalogServer()
  private val api = FakeApi { server.handle(it) }
  private val config = fakeServer("srv")
  private val topics: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private var now = 1_000L
  private val reconciler = CatalogReconciler(db, syncHttp(api), { now }, { topics.addAll(it) }, {}, { logs.add(it) })

  @After
  fun tearDown() = db.close()

  private fun reconcile(first: String? = null) = runBlocking { reconciler.reconcile(config, first) }

  private fun itemRequests(libraryId: String = "lib1") =
          api.requests.filter { it.path == "/api/libraries/$libraryId/items" && it.query?.contains("filter=series.") != true }

  private fun pointRequests() = api.requests.filter { it.query?.contains("filter=series.") == true }

  private fun state(libraryId: String) = runBlocking { db.libraryState().get("srv", libraryId) }

  private fun membership(itemId: String) = runBlocking { db.series().membershipOfItem("srv", itemId) }.map { it.seriesId to it.sequence }.toSet()

  private fun ids(libraryId: String = "lib1") = runBlocking { db.items().ids("srv", libraryId) }.toSet()

  @Test
  fun firstPassFillsLibrariesItemsSeriesAndState() {
    server.library("lib1").library("lib2", "podcast")
    server.series("s1", "Мир")
    repeat(1200) { server.book("b$it", series = if (it < 2) listOf("s1" to "${it + 1}") else emptyList()) }
    repeat(30) { server.book("p$it", libraryId = "lib2") }

    val result = reconcile()

    assertEquals(CatalogResult(2, emptyList(), null), result)
    assertEquals(1200, ids().size)
    assertEquals(30, ids("lib2").size)
    assertEquals(listOf(0, 1), runBlocking { db.libraries().all("srv") }.map { it.position })
    assertEquals(listOf("lib1", "lib2"), runBlocking { db.libraries().all("srv") }.map { it.id })
    assertNotNull(runBlocking { db.libraries().get("srv", "lib1") }!!.filterDataJson)
    assertTrue(state("lib1")!!.itemsComplete)
    assertEquals(1_000L, state("lib1")!!.itemsReconciledAt)
    assertEquals(1_000L, state("lib1")!!.seriesReconciledAt)
    assertNotNull(state("")!!.itemsReconciledAt)
    assertEquals(setOf("s1" to "1"), membership("b0"))
    assertEquals(listOf("Мир"), runBlocking { db.series().forLibrary("srv", "lib1") }.map { it.name })
    val pages = itemRequests()
    assertEquals(3, pages.size)
    pages.forEachIndexed { i, r -> assertEquals("limit=500&page=$i&sort=addedAt&include=rssfeed&minified=1", r.query) }
    assertTrue("series pages are small", api.requests.filter { it.path.endsWith("/series") }.all { it.query!!.startsWith("limit=25&") })
    assertTrue(topics.containsAll(listOf("libraries", "library:lib1", "items:lib1", "series:lib1", "state:lib1", "items:lib2")))
  }

  @Test
  fun currentLibraryIsReconciledFirst() {
    server.library("lib1").library("lib2")
    server.book("a")
    server.book("b", libraryId = "lib2")
    reconcile("lib2")
    assertEquals("/api/libraries/lib2/items", api.requests.first { it.path.endsWith("/items") }.path)
  }

  @Test
  fun restartsOnceWhenTotalChanges() {
    server.library("lib1")
    repeat(1200) { server.book("b$it") }
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "liX"))) }
    server.onItemsPage = { if (it.call == 1) it.total = 1201 }

    reconcile()

    assertEquals("pages 0, 1, then the whole walk again", listOf(0, 1, 0, 1, 2), itemRequests().map { it.query!!.substringAfter("page=").substringBefore('&').toInt() })
    assertFalse("the second walk is stable, so deletes run", "liX" in ids())
    assertTrue(state("lib1")!!.itemsComplete)
  }

  @Test
  fun skipsDeletesWhenTotalChangesTwice() {
    server.library("lib1")
    repeat(1200) { server.book("b$it") }
    server.books.getValue("b3").title = "New title"
    server.books.getValue("b3").updatedAt = 5
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "liX"), itemEntity("srv", "b3", title = "Old title"))) }
    server.onItemsPage = { if (it.page == 1) it.total = 1201 + it.call }

    reconcile()

    assertTrue("nothing is deleted", "liX" in ids())
    assertEquals("New title", runBlocking { db.items().get("srv", "b3") }!!.title)
    assertFalse(state("lib1")!!.itemsComplete)
    assertEquals(1_000L, state("lib1")!!.itemsReconciledAt)
  }

  @Test
  fun duplicateIdAcrossPagesCountsAsChange() {
    server.library("lib1")
    repeat(1200) { server.book("b$it") }
    server.onItemsPage = { if (it.call == 1) it.results = listOf(server.books.getValue("b0")) + it.results.drop(1) }

    reconcile()

    assertEquals(5, itemRequests().size)
  }

  @Test
  fun deletesItemsMissingOnServer() {
    server.library("lib1")
    server.book("b1")
    runBlocking {
      db.items().upsertAll(listOf(itemEntity("srv", "liX")))
      db.series().upsertAll(listOf(seriesRow("srv", "sX")))
      db.series().insertMembers(listOf(member("srv", "sX", "liX")))
    }

    reconcile()

    assertEquals(setOf("b1"), ids())
    assertTrue(membership("liX").isEmpty())
    // Downloaded copies live in Paper, which the reconciler has no access to
    val source = File("src/main/java/app/absplus/android/sync/CatalogReconciler.kt").readText()
    assertFalse(source.contains("DeviceManager") || source.contains("Paper."))
  }

  @Test
  fun membershipComesFromSeriesNames() {
    server.library("lib1")
    server.series("mir", "Мир")
    server.series("tri", "Трилогия")
    server.book("b1", series = listOf("mir" to "2"))
    server.book("b2", series = listOf("mir" to "1", "tri" to "3"))

    reconcile()

    assertEquals(setOf("mir" to "2"), membership("b1"))
    assertEquals(setOf("mir" to "1", "tri" to "3"), membership("b2"))
    assertTrue(pointRequests().isEmpty())
  }

  @Test
  fun ambiguousNamesArePointQueried() {
    server.library("lib1")
    server.series("a", "A")
    server.series("ab", "A, B")
    server.series("b", "B")
    server.book("b1", series = listOf("ab" to "1"))
    server.book("b2", series = listOf("a" to "4"))

    reconcile()

    val queried = pointRequests().map { FilterCodec.parse(it.query!!.substringAfter("filter=").substringBefore('&'))!!.value }.toSet()
    assertEquals(setOf("a", "ab", "b"), queried)
    assertEquals(3, pointRequests().size)
    assertEquals(setOf("ab" to "1"), membership("b1"))
    assertEquals(setOf("a" to "4"), membership("b2"))
    assertEquals("[]", state("lib1")!!.pendingSeriesQueries)
    assertTrue(logs.any { it.contains("3 series membership queries") })
  }

  @Test
  fun interruptedPointQueriesResumeNextPass() {
    server.library("lib1")
    server.series("a", "A")
    server.series("ab", "A, B")
    server.series("b", "B")
    server.book("b1", series = listOf("ab" to "1"))
    var fail = true
    server.onItemsPage = { if (fail && it.filter == "series.${FilterCodec.encode("a")}") throw IOException("network down") }

    val first = reconcile()
    assertTrue(first.stoppedBy is HttpOutcome.Unreachable)
    assertTrue(state("lib1")!!.pendingSeriesQueries.contains("\"a\""))

    fail = false
    api.requests.clear()
    reconcile()

    assertTrue(pointRequests().any { it.query!!.contains("filter=series.${FilterCodec.encode("a")}") })
    assertEquals(setOf("ab" to "1"), membership("b1"))
    assertEquals("[]", state("lib1")!!.pendingSeriesQueries)
  }

  @Test
  fun ambiguousBookLeavingASeriesIsRequeried() {
    server.library("lib1")
    server.series("x", "X")
    server.series("a", "A")
    server.series("ab", "A, B")
    server.series("b", "B")
    val book = server.book("b1", series = listOf("x" to "1"))
    reconcile()
    assertEquals(setOf("x" to "1"), membership("b1"))

    book.series = listOf("ab" to "1")
    book.updatedAt = 2
    api.requests.clear()
    reconcile()

    assertTrue(pointRequests().any { it.query!!.contains("filter=series.${FilterCodec.encode("x")}") })
    assertEquals(setOf("ab" to "1"), membership("b1"))
  }

  @Test
  fun hiddenSingleBookSeriesKeepsMembership() {
    server.library("lib1")
    server.series("p", "Повесть", hidden = true)
    server.book("b1", series = listOf("p" to null))

    reconcile()
    reconcile()

    assertEquals(listOf("Повесть"), runBlocking { db.series().forLibrary("srv", "lib1") }.map { it.name })
    assertEquals(setOf("p" to null), membership("b1"))
  }

  @Test
  fun unchangedItemIsNotRewritten() {
    server.library("lib1")
    repeat(600) { server.book("b$it") }
    reconcile()
    topics.clear()
    api.requests.clear()

    reconcile()

    assertFalse(topics.contains("items:lib1"))
    assertEquals(2, itemRequests().size)
  }

  @Test
  fun changedContentWithSameUpdatedAtIsApplied() {
    server.library("lib1")
    val book = server.book("b1")
    reconcile()
    assertNull(runBlocking { db.items().get("srv", "b1") }!!.rssFeedJson)

    book.rssFeedId = "feed1"
    reconcile()

    assertTrue(runBlocking { db.items().get("srv", "b1") }!!.rssFeedJson!!.contains("feed1"))
  }

  @Test
  fun unreachableMidPassKeepsWrittenPagesAndDeletesNothing() {
    server.library("lib1")
    repeat(700) { server.book("b$it") }
    runBlocking { db.items().upsertAll(listOf(itemEntity("srv", "liX"))) }
    server.onItemsPage = { if (it.page == 1) throw IOException("gone") }

    val result = reconcile()

    assertTrue(result.stoppedBy is HttpOutcome.Unreachable)
    assertEquals(501, ids().size)
    assertTrue("liX" in ids())
    assertNull(state("lib1")?.itemsReconciledAt)
  }

  @Test
  fun notFoundLibrarySkipsOnlyIt() {
    server.library("lib1").library("lib2")
    server.book("a")
    server.book("b", libraryId = "lib2")
    server.notFound = setOf("lib2")

    val result = reconcile()

    assertEquals(listOf("lib2"), result.failedLibraries)
    assertNull(result.stoppedBy)
    assertTrue(state("lib1")!!.itemsComplete)
    assertNotNull(state("lib2")!!.lastError)
    assertTrue(ids("lib2").isEmpty())
  }

  @Test
  fun removedLibraryIsDeletedWithItsRows() {
    server.library("lib1")
    runBlocking {
      db.libraries().upsertAll(listOf(libraryRow("srv", "lib3")))
      db.items().upsertAll(listOf(itemEntity("srv", "old", libraryId = "lib3")))
    }

    reconcile()

    assertNull(runBlocking { db.libraries().get("srv", "lib3") })
    assertTrue(ids("lib3").isEmpty())
    assertTrue(topics.contains("libraries"))
  }

  @Test
  fun cancelledOnServerSwitch() = runBlocking {
    server.library("lib1")
    repeat(700) { server.book("b$it") }
    lateinit var pass: kotlinx.coroutines.Deferred<CatalogResult>
    server.onItemsPage = { if (it.page == 1) pass.cancel() }
    pass = async(Dispatchers.IO, start = CoroutineStart.LAZY) { reconciler.reconcile(config, null) }
    pass.start()
    runCatching { pass.await() }

    assertEquals("the page answered after the cancel is not written", 500, ids().size)
  }

  @Test
  fun neverRequestsExpandedItems() {
    server.library("lib1")
    server.series("a", "A")
    server.series("ab", "A, B")
    server.book("b1", series = listOf("ab" to "1"))
    reconcile()
    assertTrue(api.requests.none { it.path.startsWith("/api/items/") })
  }

  @Test
  fun tenThousandItemsInTwentyPages() {
    server.library("lib1")
    repeat(10_000) { server.book("b$it") }
    val started = System.nanoTime()
    reconcile()
    val ms = (System.nanoTime() - started) / 1_000_000
    println("CatalogReconcilerTest: first pass over 10 000 items took $ms ms on the JVM")

    assertEquals(20, itemRequests().size)
    assertEquals(10_000, ids().size)
  }

  /** Series «A», «A, B», «B»; book b1 is «A, B #1», so all three are asked from the server. */
  private fun ambiguousLibrary(): FakeBook {
    server.library("lib1")
    server.series("a", "A")
    server.series("ab", "A, B")
    server.series("b", "B")
    return server.book("b1", series = listOf("ab" to "1"))
  }

  private val abFilter = "series.${FilterCodec.encode("ab")}"

  @Test
  fun pointQueryRestartKeepsOnlyTheSecondWalk() {
    val book = ambiguousLibrary()
    server.onItemsPage = { p ->
      if (p.filter == abFilter) when (p.call) {
        // The first walk sees a stale sequence and a total that then moves
        0 -> {
          book.series = listOf("ab" to "9")
          p.total = 5
        }
        1 -> {
          book.series = listOf("ab" to "1")
          p.total = 1
        }
      }
    }

    reconcile()

    assertEquals(setOf("ab" to "1"), membership("b1"))
    assertEquals("[]", state("lib1")!!.pendingSeriesQueries)
  }

  @Test
  fun unstablePointQueryKeepsMembershipAndPending() {
    ambiguousLibrary()
    reconcile()
    assertEquals(setOf("ab" to "1"), membership("b1"))
    runBlocking { db.libraryState().upsert(state("lib1")!!.copy(pendingSeriesQueries = "[\"ab\"]")) }
    server.onItemsPage = { p -> if (p.filter == abFilter) p.total = 7 + p.call }

    reconcile()

    assertEquals("the old membership stays", setOf("ab" to "1"), membership("b1"))
    assertTrue(state("lib1")!!.pendingSeriesQueries.contains("\"ab\""))
  }

  @Test
  fun libraryListWithoutLibrariesDeletesNothing() {
    server.library("lib1")
    server.book("b1")
    reconcile()
    val handler = api.handler
    api.handler = { r -> if (r.path == "/api/libraries") 200 to "{}" else handler(r) }

    reconcile()

    assertEquals(listOf("lib1"), runBlocking { db.libraries().all("srv") }.map { it.id })
    assertEquals(setOf("b1"), ids())
  }

  @Test
  fun seriesUnknownToTheListsIsReadAgainNextPass() {
    server.library("lib1")
    val book = server.book("b1")
    // Created on the server after the series list and filterdata of this pass were read
    server.onItemsPage = { p ->
      if (p.filter == null && "new" !in server.series) {
        server.series("new", "New")
        book.series = listOf("new" to "1")
      }
    }
    reconcile()
    assertTrue(membership("b1").isEmpty())

    reconcile()

    assertEquals(setOf("new" to "1"), membership("b1"))
  }

  @Test
  fun partlyUnknownSeriesNameIsReadAgainNextPass() {
    server.library("lib1")
    server.series("known", "Known")
    val book = server.book("b1", series = listOf("known" to "1"))
    // "Known #1, New #1": New is created after this pass read the series list and filterdata
    server.onItemsPage = { p ->
      if (p.filter == null && "new" !in server.series) {
        server.series("new", "New")
        book.series = listOf("known" to "1", "new" to "1")
      }
    }
    reconcile()
    assertTrue("the known part is asked from the server", pointRequests().any { it.query!!.contains("filter=series.${FilterCodec.encode("known")}") })
    assertTrue(runBlocking { db.items().get("srv", "b1") }!!.contentHash.startsWith("unparsed:"))

    reconcile()

    assertEquals(setOf("known" to "1", "new" to "1"), membership("b1"))
  }

  @Test
  fun unparsableSeriesNameIsNotRewrittenEveryPass() {
    server.library("lib1")
    server.series("saga", "Saga")
    server.book("b1", series = listOf("saga" to "1"))
    // The server's name for the book does not match the series list: "Saga #1, 2"
    val handler = api.handler
    api.handler = { r ->
      val (code, body) = handler(r)
      code to body.replace("\"seriesName\":\"Saga #1\"", "\"seriesName\":\"Saga #1, 2\"")
    }
    reconcile()
    assertTrue(runBlocking { db.items().get("srv", "b1") }!!.contentHash.startsWith("unparsed:"))
    topics.clear()
    api.requests.clear()

    reconcile()

    assertFalse("the row is not rewritten", topics.contains("items:lib1"))
    assertTrue("its series are not asked again", pointRequests().isEmpty())

    // A change of the series lists reads it again
    server.series("other", "Other")
    topics.clear()
    reconcile()
    assertTrue(topics.contains("items:lib1"))
  }

  private fun authorIds(libraryId: String = "lib1") = runBlocking { db.authors().ids("srv", libraryId) }.toSet()

  private fun authorRequests(libraryId: String = "lib1") = api.requests.filter { it.path == "/api/libraries/$libraryId/authors" }

  @Test
  fun authorsPassUpsertsAndDeletesLikeItems() {
    server.library("lib1").library("pods", "podcast")
    server.book("b1")
    repeat(230) { server.author("a$it", "Author $it", numBooks = it) }
    runBlocking { db.authors().upsertAll(listOf(authorRow("srv", "gone"))) }
    server.onAuthorsPage = { if (it.call == 1) it.total = 231 }

    reconcile()

    assertEquals("pages 0, 1, then the whole walk again", listOf(0, 1, 0, 1, 2),
            authorRequests().map { it.query!!.substringAfter("page=").substringBefore('&').toInt() })
    authorRequests().forEach { assertTrue(it.query!!, it.query!!.matches(Regex("limit=100&page=\\d&sort=addedAt"))) }
    assertTrue("no authors pass for a podcast library", authorRequests("pods").isEmpty())
    assertEquals((0 until 230).map { "a$it" }.toSet(), authorIds())
    val row = runBlocking { db.authors().get("srv", "a7") }!!
    assertEquals(7, row.numBooks)
    assertEquals("7, Author", row.lastFirst)
    assertEquals(7, CatalogJson.mapper.readTree(row.json).path("numBooks").asInt())
    assertEquals(1_000L, state("lib1")!!.authorsReconciledAt)
    assertTrue(topics.contains("authors:lib1"))

    // Unchanged rows are not written again
    topics.clear()
    now = 2_000L
    server.onAuthorsPage = {}
    reconcile()
    assertFalse(topics.contains("authors:lib1"))
    assertEquals(2_000L, state("lib1")!!.authorsReconciledAt)

    // A total that moves twice keeps what was seen and deletes nothing
    server.authors.remove("a0")
    server.authors.getValue("a1").name = "Renamed"
    server.onAuthorsPage = { if (it.page == 1) it.total = 300 + it.call }
    reconcile()
    assertTrue("a0" in authorIds())
    assertEquals("Renamed", runBlocking { db.authors().get("srv", "a1") }!!.name)

    server.onAuthorsPage = {}
    reconcile()
    assertFalse("a stable walk deletes", "a0" in authorIds())
  }

  @Test
  fun authorsFailureDoesNotStopTheItems() {
    server.library("lib1")
    server.book("b1")
    server.author("a1", "Ann Lee")
    val handler = api.handler
    api.handler = { r -> if (r.path.endsWith("/authors")) 500 to "boom" else handler(r) }

    reconcile()

    assertEquals(setOf("b1"), ids())
    assertEquals(1_000L, state("lib1")!!.itemsReconciledAt)
    assertNull(state("lib1")!!.authorsReconciledAt)
    assertTrue(authorIds().isEmpty())
  }

  @Test
  fun firstAuthorsPassTellsTheTabEvenWithoutAuthors() {
    server.library("lib1")
    reconcile()
    assertEquals(1_000L, state("lib1")!!.authorsReconciledAt)
    assertTrue("a tab reading the server switches to the replica", topics.contains("authors:lib1"))
  }

  @Test
  fun serverWithoutAuthorPagesGivesTheWholeList() {
    server.library("lib1")
    repeat(150) { server.author("a$it", "Author $it") }
    server.legacyAuthors = true
    runBlocking { db.authors().upsertAll(listOf(authorRow("srv", "gone"))) }

    reconcile()

    assertEquals("the unpaged list is the whole list: read once", 1, authorRequests().size)
    assertEquals((0 until 150).map { "a$it" }.toSet(), authorIds())
    assertEquals(1_000L, state("lib1")!!.authorsReconciledAt)
  }

  @Test
  fun authorsAnswerWithoutAListDeletesNothing() {
    server.library("lib1")
    server.author("a1", "Ann Lee")
    runBlocking { db.authors().upsertAll(listOf(authorRow("srv", "kept"))) }
    val handler = api.handler
    api.handler = { r -> if (r.path.endsWith("/authors")) 200 to """{"something":"else"}""" else handler(r) }

    reconcile()

    assertEquals(setOf("kept"), authorIds())
    assertNull(state("lib1")!!.authorsReconciledAt)
    assertEquals(1_000L, state("lib1")!!.itemsReconciledAt)
  }

  @Test
  fun unstableAuthorsWalkKeepsTheReconcileTime() {
    server.library("lib1")
    repeat(150) { server.author("a$it", "Author $it") }
    server.onAuthorsPage = { if (it.page == 1) it.total = 300 + it.call }

    reconcile()

    assertNull("a list that kept moving is not taken for a read one", state("lib1")!!.authorsReconciledAt)
    assertTrue("what was seen is kept", authorIds().isNotEmpty())

    server.onAuthorsPage = {}
    reconcile()
    assertEquals(1_000L, state("lib1")!!.authorsReconciledAt)
    now = 2_000L
    server.onAuthorsPage = { if (it.page == 1) it.total = 300 + it.call }
    reconcile()
    assertEquals("the last complete read stays", 1_000L, state("lib1")!!.authorsReconciledAt)
  }

  private fun collectionRequests(libraryId: String = "lib1") = api.requests.filter { it.path == "/api/libraries/$libraryId/collections" }

  private fun collectionRows(libraryId: String = "lib1") = runBlocking { db.collections().forLibrary("srv", libraryId) }

  private fun collectionBookIds(id: String) = runBlocking { db.collections().itemIds("srv", id) }

  private val reader = CatalogReader(db, { emptyList() }, { _, _ -> emptyList() }, { 0L }, { 1L })

  private fun readBooks(id: String) = runBlocking { reader.collectionBooks("srv", CollectionBooksRequest(id, 100, 0)) }.getJSONArray("results")
          .let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }

  @Test
  fun collectionKeepsServerOrderOfBooks() {
    server.library("lib1")
    listOf("b1", "b2", "b3", "b4").forEach { server.book(it) }
    server.collection("c1", listOf("b3", "b1", "b4", "b2"))

    reconcile()

    assertEquals("the order of `books`, not of the items", listOf("b3", "b1", "b4", "b2"), collectionBookIds("c1"))
    assertEquals(listOf("b3", "b1", "b4", "b2"), readBooks("c1"))
    val row = collectionRows().single()
    assertFalse("the expanded books are not kept in the row", CatalogJson.mapper.readTree(row.json).has("books"))

    // Reordered in the web client: the event carries the new order
    val applier = CatalogEventApplier(db, {}, { reader.onReplicaChanged() })
    val moved = server.collection("c1", listOf("b2", "b4", "b1", "b3")).apply { lastUpdate = 2L }
    runBlocking { applier.apply("srv", "collection_updated", server.collectionJson(moved)) }
    assertEquals(listOf("b2", "b4", "b1", "b3"), readBooks("c1"))

    // And the next pass keeps it, or follows the server again
    server.collections.getValue("c1").books = listOf("b4", "b3")
    reconcile()
    assertEquals(listOf("b4", "b3"), collectionBookIds("c1"))
  }

  @Test
  fun collectionsPassDeletesMissing() {
    server.library("lib1").library("pods", "podcast")
    server.book("b1")
    repeat(45) { server.collection("c%02d".format(it), listOf("b1")) }
    runBlocking {
      db.collections().replace(CollectionEntity("srv", "gone", "lib1", 0, """{"id":"gone"}"""), listOf("b1", "b9"))
    }

    reconcile()

    assertEquals(listOf(0, 1, 2), collectionRequests().map { it.query!!.substringAfter("page=").toInt() })
    collectionRequests().forEach { assertTrue(it.query!!, it.query!!.matches(Regex("limit=20&page=\\d"))) }
    assertTrue("no collections pass for a podcast library", collectionRequests("pods").isEmpty())
    assertEquals("server order is the position", (0 until 45).map { "c%02d".format(it) }, collectionRows().map { it.id })
    assertEquals((0 until 45).toList(), collectionRows().map { it.position })
    assertTrue("the books of a deleted collection go with it", collectionBookIds("gone").isEmpty())
    assertEquals(1_000L, state("lib1")!!.collectionsReconciledAt)
    assertTrue(topics.contains("collections:lib1"))

    // Unchanged rows are not written again
    topics.clear()
    now = 2_000L
    reconcile()
    assertFalse(topics.contains("collections:lib1"))
    assertEquals(2_000L, state("lib1")!!.collectionsReconciledAt)

    // A moved collection takes its new position; a total that moves twice deletes nothing
    server.collections.remove("c00")
    server.onCollectionsPage = { if (it.page == 1) it.total = 100 + it.call }
    reconcile()
    assertTrue("c00" in collectionRows().map { it.id })
    assertEquals("the last complete read stays", 2_000L, state("lib1")!!.collectionsReconciledAt)
    server.onCollectionsPage = {}
    reconcile()
    assertFalse("a stable walk deletes", "c00" in collectionRows().map { it.id })
    assertEquals(0, collectionRows().first { it.id == "c01" }.position)
  }

  @Test
  fun collectionsAnswerWithoutResultsDeletesNothing() {
    server.library("lib1")
    server.book("b1")
    server.collection("c1", listOf("b1"))
    reconcile()
    val handler = api.handler
    api.handler = { r -> if (r.path.endsWith("/collections")) 200 to """{"total":0}""" else handler(r) }
    now = 2_000L

    val result = reconcile()

    assertEquals(listOf("c1"), collectionRows().map { it.id })
    assertEquals(listOf("b1"), collectionBookIds("c1"))
    assertEquals(1_000L, state("lib1")!!.collectionsReconciledAt)
    assertEquals(listOf("lib1"), result.failedLibraries)
    assertEquals(setOf("b1"), ids())
  }

  @Test
  fun firstCollectionsPassTellsTheTabEvenWithoutCollections() {
    server.library("lib1")
    reconcile()
    assertEquals(1_000L, state("lib1")!!.collectionsReconciledAt)
    assertTrue("a tab reading the server switches to the replica", topics.contains("collections:lib1"))
  }

  @Test
  fun collectionsFailureDoesNotStopTheItems() {
    server.library("lib1")
    server.book("b1")
    val handler = api.handler
    api.handler = { r -> if (r.path.endsWith("/collections")) 500 to "boom" else handler(r) }

    reconcile()

    assertEquals(setOf("b1"), ids())
    assertEquals(1_000L, state("lib1")!!.itemsReconciledAt)
    assertEquals(1_000L, state("lib1")!!.authorsReconciledAt)
    assertNull(state("lib1")!!.collectionsReconciledAt)
  }

  @Test
  fun itemsAnswerWithoutResultsDeletesNothing() {
    server.library("lib1")
    server.book("b1")
    reconcile()
    val handler = api.handler
    api.handler = { r -> if (r.path.endsWith("/items")) 200 to """{"total":0}""" else handler(r) }

    reconcile()

    assertEquals("an odd answer is a failed walk, not an empty library", setOf("b1"), ids())
  }
}
