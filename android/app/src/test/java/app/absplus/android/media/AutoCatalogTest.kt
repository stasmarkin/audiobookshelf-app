package app.absplus.android.media

import app.absplus.android.data.AudioTrack
import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.db.authorRow
import app.absplus.android.db.inMemoryReplica
import app.absplus.android.db.member
import app.absplus.android.db.replica.CollectionEntity
import app.absplus.android.db.replica.EpisodeEntity
import app.absplus.android.db.replica.LibraryEntity
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.replica.SeriesEntity
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.sync.CatalogJson
import app.absplus.android.sync.CatalogReader
import app.absplus.android.sync.UserDataStore
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoCatalogTest {
  private val db = inMemoryReplica()
  private var progress = emptyList<MediaProgressEntity>()
  private var active: String? = "srv"
  private var hasReader = true
  private var copies = emptyList<LocalLibraryItem>()
  private var localItemsCalls = 0
  private val reader = CatalogReader(db, { sc -> progress.filter { it.serverConfigId == sc } },
          { sc, ids -> progress.filter { it.serverConfigId == sc && it.libraryItemId in ids } }, { progress.hashCode().toLong() },
          { NOW })
  private val catalog = AutoCatalog(
          reader = { if (hasReader) reader else null },
          activeServerId = { active },
          progressRows = { sc -> progress.filter { it.serverConfigId == sc } },
          hasAudio = { sc, lib -> db.items().rows(sc, lib).any { it.numTracks > 0 || it.numEpisodes > 0 } },
          episodeOwner = { sc, id -> db.episodes().byIds(sc, listOf(id)).firstOrNull()?.libraryItemId },
          localItems = { localItemsCalls++; copies },
          downloadMark = resolverDownloadMark { _, _ -> true })

  @After fun tearDown() = db.close()

  private fun library(id: String, mediaType: String = "book", sc: String = "srv", position: Int = 0, reconciled: Boolean = true) = runBlocking {
    db.libraries().upsertAll(listOf(LibraryEntity(sc, id, position, mediaType,
            """{"id":"$id","name":"Library $id","folders":[],"icon":"database","mediaType":"$mediaType"}""", null, 0, 0)))
    val at = if (reconciled) 1L else null
    db.libraryState().upsert(LibraryStateEntity(sc, id, at, true, at, at, at, at, null, "[]", at))
  }

  private fun book(id: String, lib: String = "books", title: String = "Title $id", numTracks: Int = 3, ebook: Boolean = false,
                   author: String = "Author", seriesName: String = "", sc: String = "srv", addedAt: Long = NOW) = runBlocking {
    val json = """{"id":"$id","ino":"ino-$id","libraryId":"$lib","folderId":"f","path":"/a/$id","relPath":"$id","isFile":false,
      "mtimeMs":1,"ctimeMs":1,"birthtimeMs":1,"addedAt":$addedAt,"updatedAt":1,"isMissing":false,"isInvalid":false,"mediaType":"book",
      "media":{"id":"m-$id","metadata":{"title":"$title","titleIgnorePrefix":"$title","subtitle":null,"authorName":"$author",
      "authorNameLF":"$author","narratorName":"","seriesName":"$seriesName","genres":[],"publishedYear":null,"publishedDate":null,
      "publisher":null,"description":null,"isbn":null,"asin":null,"language":null,"explicit":false,"abridged":false},
      "coverPath":null,"tags":[],"numTracks":$numTracks,"numAudioFiles":$numTracks,"numChapters":0,"duration":7,"size":1
      ${if (ebook) ",\"ebookFormat\":\"epub\"" else ""}},"numFiles":1,"size":1}"""
    db.items().upsertAll(listOf(CatalogJson.itemEntity(sc, CatalogJson.mapper.readTree(json))))
  }

  private fun podcast(id: String, lib: String = "pods", title: String = "Podcast $id", episodes: List<String> = emptyList(), sc: String = "srv") = runBlocking {
    val json = """{"id":"$id","ino":"ino-$id","libraryId":"$lib","folderId":"f","path":"/p/$id","relPath":"$id","isFile":false,
      "mtimeMs":1,"ctimeMs":1,"birthtimeMs":1,"addedAt":$NOW,"updatedAt":1,"isMissing":false,"isInvalid":false,"mediaType":"podcast",
      "media":{"id":"m-$id","metadata":{"title":"$title","author":"Pod Author","description":null,"genres":[],"feedUrl":null,
      "explicit":false,"titleIgnorePrefix":"$title"},"coverPath":null,"tags":[],"numEpisodes":${maxOf(episodes.size, 1)},
      "autoDownloadEpisodes":false,"size":1},"numFiles":1,"size":1}"""
    db.items().upsertAll(listOf(CatalogJson.itemEntity(sc, CatalogJson.mapper.readTree(json))))
    if (episodes.isNotEmpty()) db.episodes().insertAll(episodes.mapIndexed { i, e ->
      EpisodeEntity(sc, e, id, lib, "Episode $e", CatalogJson.episodeSearchKey("Episode $e"), null, NOW, i + 1,
              """{"id":"$e","libraryItemId":"$id","index":${i + 1},"title":"Episode $e"}""")
    })
  }

  private fun series(id: String, name: String, books: List<String>, lib: String = "books") = runBlocking {
    db.series().upsertAll(listOf(SeriesEntity("srv", id, lib, name, name, NOW, 1L, """{"id":"$id","name":"$name","addedAt":$NOW,"updatedAt":1}""")))
    db.series().insertMembers(books.mapIndexed { i, b -> member("srv", id, b, "${i + 1}") })
  }

  private fun collection(id: String, books: List<String>, lib: String = "books", position: Int = 0) = runBlocking {
    db.collections().replace(CollectionEntity("srv", id, lib, position, """{"id":"$id","libraryId":"$lib","name":"Collection $id"}"""), books)
  }

  private fun progressRow(id: String, lastUpdate: Long, episodeId: String = "", serverId: String? = "p-$id", sc: String = "srv") =
          MediaProgressEntity(sc, id, episodeId, serverId, 10.0, 0.5, 5.0, false, false, null, null, lastUpdate, 1, null, "server", false)

  private fun localBook(id: String, serverId: String, config: String = "srv", tracks: Int = 3) = LocalLibraryItem(
          id, "internal", "", "", "", false, "book",
          Book(BookMetadata("Book", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null),
                  null, emptyList(), null, null, (0 until tracks).map { AudioTrack(it, 0.0, 1.0, "t$it", "", null, null, true, null, null) }.toMutableList(),
                  null, null, null, tracks),
          mutableListOf(), null, null, true, config, null, null, serverId, null)

  private fun localPodcast(id: String, serverId: String, vararg serverEpisodes: String) = LocalLibraryItem(
          id, "internal", "", "", "", false, "podcast",
          Podcast(PodcastMetadata("Podcast", null, null, mutableListOf(), false), null, mutableListOf(),
                  serverEpisodes.map { PodcastEpisode("local_ep_$it", 1, null, null, "E", null, null, null, null, null, null, null, null, null, it, null) }
                          .toMutableList(), false, null),
          mutableListOf(), null, null, true, "srv", null, null, serverId, null)

  private fun <T> run(block: suspend AutoCatalog.() -> T): T = runBlocking { catalog.block() }

  @Test fun snapshotOfAReconciledReplica() {
    library("books", position = 0)
    library("ebooks", position = 1)
    library("pods", "podcast", position = 2)
    book("b1"); book("b2"); book("b3")
    book("e1", lib = "ebooks", numTracks = 0, ebook = true)
    podcast("p1", episodes = listOf("ep1"))
    progress = listOf(progressRow("b1", lastUpdate = 100), progressRow("b2", lastUpdate = 300))

    val snapshot = run { snapshot() }!!
    assertEquals("srv", snapshot.serverConfigId)
    assertEquals(listOf("books", "pods"), snapshot.libraries.map { it.id })
    assertEquals(setOf("books", "pods"), snapshot.readyLibraryIds)
    assertEquals(listOf("b2", "b1"), snapshot.continueItems.map { it.libraryItemWrapper.id })
    assertEquals(setOf("b1", "b2"), snapshot.progress.keys)
    assertEquals("p-b2", snapshot.progress["b2"]!!.id)

    hasReader = false
    assertNull(run { snapshot() })
    hasReader = true
    active = null
    assertNull(run { snapshot() })
  }

  @Test fun listsAreReadAcrossPages() {
    library("books"); library("pods", "podcast", position = 1)
    (0 until 130).forEach { podcast("p$it") }
    (0 until 260).forEach { book("b$it") }
    runBlocking { db.authors().upsertAll((0 until 250).map { authorRow("srv", "a$it", "books", "Author $it") }) }
    (0 until 130).forEach { series("s$it", "Series $it", listOf("b$it", "b${it + 130}")) }
    (0 until 25).forEach { collection("c$it", listOf("b$it"), position = it) }

    assertEquals(130, run { podcasts("pods") }!!.value.size)
    assertEquals(250, run { authorsWithBooks("books") }!!.value.size)
    assertEquals(130, run { seriesWithAudio("books") }!!.value.size)
    assertEquals(25, run { collectionsWithAudio("books") }!!.value.size)
  }

  @Test fun collectionBooksReplaceThePreview() {
    library("books")
    (1..6).forEach { book("b$it") }
    collection("c1", (1..6).map { "b$it" })
    val collection = run { collectionsWithAudio("books") }!!.value.single()
    assertEquals(6, collection.books!!.size)
    assertEquals("Title b1", collection.books!![0].media.metadata.title)
    assertEquals(6, run { collectionBooks("books", "c1") }!!.value.size)
  }

  @Test fun authorBooksCollapseSeries() {
    library("books")
    book("b1", seriesName = "S #1"); book("b2", seriesName = "S #2"); book("b3")
    series("S", "S", listOf("b1", "b2"))
    runBlocking { db.authors().upsertAll(listOf(authorRow("srv", "a1", "books", "Author"))) }
    val items = run { authorBooks("books", "a1") }!!.value
    assertEquals(2, items.size)
    assertEquals("S", items.single { it.collapsedSeries != null }.collapsedSeries!!.id)
    assertEquals("books", items.single { it.collapsedSeries != null }.collapsedSeries!!.libraryId)
  }

  @Test fun collectionsWithAudioCountTracksOfMinifiedBooks() {
    library("books")
    book("b1", numTracks = 3); book("e1", numTracks = 0, ebook = true)
    collection("audio", listOf("b1"), position = 0)
    collection("ebooks", listOf("e1"), position = 1)
    val shown = run { collectionsWithAudio("books") }!!.value
    assertEquals(listOf("audio"), shown.map { it.id })
    assertNull("a minified book has no tracks list", (shown.single().books!!.single().media as Book).tracks)
  }

  @Test fun unreconciledAnswersSayNotReconciled() {
    library("pods", "podcast", reconciled = false)
    podcast("p1")
    assertFalse(run { podcasts("pods") }!!.reconciled)

    library("books")
    book("b1")
    series("S", "S", listOf("b1"))
    assertTrue(run { seriesBooks("books", "S") }!!.reconciled)
    runBlocking { db.libraryState().upsert(LibraryStateEntity("srv", "books", 1L, true, 1L, 1L, 1L, 1L, null, """["S"]""", 1L)) }
    assertFalse(run { seriesBooks("books", "S") }!!.reconciled)
  }

  @Test fun itemForPlayReadsTheReplica() {
    library("books")
    book("b1", title = "Dune")
    assertEquals("Dune", run { itemForPlay("b1") }!!.media.metadata.title)
    assertNull(run { itemForPlay("nope") })
  }

  @Test fun episodeForPlayFindsItsPodcast() {
    library("pods", "podcast")
    podcast("p1", episodes = listOf("e1", "e2"))
    val found = run { episodeForPlay("e1") }!!
    assertEquals("p1", found.libraryItemWrapper.id)
    assertEquals("e1", found.episode.id)

    library("pods", "podcast", sc = "other")
    podcast("p9", episodes = listOf("x1"), sc = "other")
    assertNull("an episode of another server", run { episodeForPlay("x1") })
  }

  @Test fun downloadMarkFollowsTheResolver() {
    library("books")
    book("b1", numTracks = 3)
    val item = run { itemForPlay("b1") }!!
    val mark = resolverDownloadMark { _, _ -> true }
    assertNull("another account's copy", mark(listOf(localBook("local_x", "b1", config = "other")), "srv", item, null))
    assertEquals("local_own", mark(listOf(localBook("local_own", "b1")), "srv", item, null))
    assertNull("a partial copy", mark(listOf(localBook("local_part", "b1", tracks = 2)), "srv", item, null))

    library("pods", "podcast", position = 1)
    podcast("p1", episodes = listOf("e1"))
    val pod = run { itemForPlay("p1") }!!
    assertEquals("local_ep_e1", mark(listOf(localPodcast("local_p", "p1", "e1")), "srv", pod, "e1"))

    copies = listOf(localBook("local_own", "b1"), localPodcast("local_p", "p1", "e1"))
    (2..5).forEach { book("b$it") }
    series("S", "S", (1..5).map { "b$it" })
    localItemsCalls = 0
    val books = run { seriesBooks("books", "S") }!!.value
    assertEquals("the copies are read once per answer", 1, localItemsCalls)
    assertEquals("local_own", books.single { it.id == "b1" }.localLibraryItemId)
    assertNull(books.single { it.id == "b2" }.localLibraryItemId)
    assertNull("a podcast node keeps its server id", run { podcasts("pods") }!!.value.single { it.id == "p1" }.localLibraryItemId)
  }

  @Test fun localProgressRowHasAnId() {
    library("books")
    book("b1"); book("b2")
    progress = listOf(progressRow("b1", 5, serverId = null), progressRow("b2", 5, serverId = UserDataStore.SERVER_RECORD_SEEN))
    val snapshot = run { snapshot() }!!
    assertEquals("local:b1", snapshot.progress.getValue("b1").id)
    assertEquals("local:b2", snapshot.progress.getValue("b2").id)
  }

  @Test fun snapshotFollowsTheActiveServer() {
    library("books")
    book("b1")
    series("S", "S", listOf("b1"))
    library("other-lib", sc = "srv2")
    book("o1", lib = "other-lib", sc = "srv2")
    val first = run { snapshot() }!!
    active = "srv2"
    val second = run { snapshot() }!!
    assertEquals("srv2", second.serverConfigId)
    assertEquals(listOf("other-lib"), second.libraries.map { it.id })
    assertTrue(second.generation > first.generation)
    val series = run { seriesWithAudio("books") }!!
    assertTrue(series.value.isEmpty())
    assertFalse(series.reconciled)
  }

  @Test fun searchFindsByTitleAuthorAndSeries() {
    library("books")
    book("b1", title = "Dune Messiah", author = "Frank Herbert", seriesName = "Dune Chronicles #2")
    series("s1", "Dune Chronicles", listOf("b1"))
    runBlocking { db.authors().upsertAll(listOf(authorRow("srv", "a1", "books", "Frank Herbert"))) }
    assertTrue(run { search("books", "Messiah") }!!.value.book!!.isNotEmpty())
    assertTrue(run { search("books", "Herbert") }!!.value.authors!!.isNotEmpty())
    assertTrue(run { search("books", "Chronicles") }!!.value.series!!.isNotEmpty())

    library("pods", "podcast", position = 1)
    podcast("p1", title = "Science Weekly")
    val found = run { search("pods", "Science") }!!
    assertNotNull(found.value.podcast)
    assertEquals("p1", found.value.podcast!!.single().libraryItem.id)
  }

  @Test fun allPagesStopsAtTheTotal() = runBlocking {
    var calls = 0
    val (list, reconciled) = catalog.allPages(limit = 2, page = { p ->
      calls++
      val results = org.json.JSONArray().apply { repeat(if (p < 2) 2 else 0) { put(p) } }
      org.json.JSONObject().put("results", results).put("total", 4).put("replica", org.json.JSONObject().put("reconciledAt", 1))
    }) { page -> (0 until page.getJSONArray("results").length()).map { page.getJSONArray("results").getInt(it) } }
    assertEquals(listOf(0, 0, 1, 1), list)
    assertEquals(2, calls)
    assertTrue(reconciled)
  }

  private companion object {
    const val NOW = 1_800_000_000_000L
  }
}
