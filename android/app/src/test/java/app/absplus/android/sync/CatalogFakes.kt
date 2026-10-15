package app.absplus.android.sync

import app.absplus.android.db.replica.FilterCodec
import com.fasterxml.jackson.databind.node.ObjectNode

class FakeBook(
        val id: String,
        val libraryId: String,
        var title: String,
        var updatedAt: Long = 1L,
        val addedAt: Long = 1L,
        /** (seriesId, sequence) in the order the book lists them. */
        var series: List<Pair<String, String?>> = emptyList(),
        var rssFeedId: String? = null
)

class FakeSeries(val id: String, val libraryId: String, var name: String, var hidden: Boolean = false, val addedAt: Long = 1L)

class FakeAuthor(val id: String, val libraryId: String, var name: String, var numBooks: Int = 1, var updatedAt: Long = 1L, val addedAt: Long = 1L)

/** One `authors` answer before it is sent: a hook may change [results] and [total]. */
class AuthorsPage(val libraryId: String, val page: Int, val call: Int, var results: List<FakeAuthor>, var total: Int)

/** One `items` answer before it is sent: a hook may change [results] and [total] or throw IOException. */
class ItemsPage(val libraryId: String, val page: Int, val filter: String?, val call: Int, var results: List<FakeBook>, var total: Int)

/**
 * A synthetic catalog server for [FakeApi]: libraries, books, series and filterdata answered the way 2.37.1 pages
 * them, with each book's `seriesName` built from its series like the server's `Book.seriesName`.
 */
class FakeCatalogServer {
  val libraries = mutableListOf<Pair<String, String>>()
  val books = LinkedHashMap<String, FakeBook>()
  val series = LinkedHashMap<String, FakeSeries>()
  val authors = LinkedHashMap<String, FakeAuthor>()
  var onItemsPage: (ItemsPage) -> Unit = {}
  var onAuthorsPage: (AuthorsPage) -> Unit = {}
  var legacyAuthors = false
  var notFound: Set<String> = emptySet()
  private val calls = HashMap<String, Int>()
  private val mapper = CatalogJson.mapper

  fun library(id: String, mediaType: String = "book") = apply { libraries += id to mediaType }

  fun book(id: String, libraryId: String = "lib1", title: String = "Title $id", series: List<Pair<String, String?>> = emptyList(), addedAt: Long = books.size.toLong()) =
          FakeBook(id, libraryId, title, series = series, addedAt = addedAt).also { books[id] = it }

  fun series(id: String, name: String, libraryId: String = "lib1", hidden: Boolean = false) =
          FakeSeries(id, libraryId, name, hidden, addedAt = series.size.toLong()).also { series[id] = it }

  fun author(id: String, name: String, libraryId: String = "lib1", numBooks: Int = 1) =
          FakeAuthor(id, libraryId, name, numBooks, addedAt = authors.size.toLong()).also { authors[id] = it }

  fun seriesName(book: FakeBook) = book.series.joinToString(", ") { (id, seq) ->
    val name = series.getValue(id).name
    if (seq.isNullOrEmpty()) name else "$name #$seq"
  }

  private fun mediaTypeOf(libraryId: String) = libraries.firstOrNull { it.first == libraryId }?.second ?: "book"

  fun itemJson(book: FakeBook, seriesFilter: String? = null): ObjectNode {
    val podcast = mediaTypeOf(book.libraryId) == "podcast"
    val metadata = mapper.createObjectNode().put("title", book.title)
    if (podcast) metadata.put("author", "Author") else metadata.put("authorName", "Author").put("seriesName", seriesName(book))
    if (seriesFilter != null) {
      val seq = book.series.first { it.first == seriesFilter }.second
      metadata.set<ObjectNode>("series", mapper.createObjectNode().put("id", seriesFilter).put("name", series.getValue(seriesFilter).name).put("sequence", seq))
    }
    val item = mapper.createObjectNode().put("id", book.id).put("libraryId", book.libraryId).put("mediaType", if (podcast) "podcast" else "book")
            .put("updatedAt", book.updatedAt).put("addedAt", book.addedAt)
    item.set<ObjectNode>("media", mapper.createObjectNode().set<ObjectNode>("metadata", metadata))
    book.rssFeedId?.let { item.set<ObjectNode>("rssFeed", mapper.createObjectNode().put("id", it).put("entityType", "libraryItem").put("entityId", book.id)) }
    return item
  }

  private fun params(query: String?): Map<String, String> =
          query.orEmpty().split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

  fun handle(request: FakeRequest): Pair<Int, String> {
    val path = request.path
    val q = params(request.query)
    val segments = path.removePrefix("/api/libraries").trim('/').split('/').filter { it.isNotEmpty() }
    if (request.method != "GET" || !path.startsWith("/api/libraries")) return 404 to "Not found"
    if (segments.isEmpty()) {
      val list = mapper.createArrayNode()
      libraries.forEachIndexed { i, (id, type) -> list.addObject().put("id", id).put("name", "Library $id").put("mediaType", type).put("displayOrder", i) }
      return 200 to mapper.writeValueAsString(mapper.createObjectNode().set<ObjectNode>("libraries", list))
    }
    val libraryId = segments[0]
    if (libraries.none { it.first == libraryId } || libraryId in notFound) return 404 to "Not found"
    return when (segments.getOrNull(1)) {
      null -> {
        val filterSeries = mapper.createArrayNode()
        series.values.filter { it.libraryId == libraryId }.forEach { filterSeries.addObject().put("id", it.id).put("name", it.name) }
        val filterdata = mapper.createObjectNode().set<ObjectNode>("series", filterSeries)
        filterdata.set<ObjectNode>("authors", mapper.createArrayNode())
        200 to mapper.writeValueAsString(mapper.createObjectNode().set<ObjectNode>("filterdata", filterdata).put("issues", 0).put("numUserPlaylists", 0))
      }
      "items" -> {
        val limit = q["limit"]?.toInt() ?: 0
        val page = q["page"]?.toInt() ?: 0
        val filter = q["filter"]
        val seriesId = FilterCodec.parse(filter)?.takeIf { it.group == "series" }?.value
        val all = books.values.filter { it.libraryId == libraryId && (seriesId == null || it.series.any { s -> s.first == seriesId }) }
                .sortedBy { it.addedAt }
        val call = calls.merge("$libraryId $filter", 1, Int::plus)!! - 1
        val slice = if (limit == 0) all else all.drop(page * limit).take(limit)
        val answer = ItemsPage(libraryId, page, filter, call, slice, all.size)
        onItemsPage(answer)
        val results = mapper.createArrayNode()
        answer.results.forEach { results.add(itemJson(it, seriesId)) }
        200 to mapper.writeValueAsString(mapper.createObjectNode().put("total", answer.total).put("limit", limit).put("page", page)
                .set<ObjectNode>("results", results))
      }
      "series" -> {
        val limit = q["limit"]?.toInt() ?: 0
        val page = q["page"]?.toInt() ?: 0
        val all = series.values.filter { it.libraryId == libraryId && !it.hidden }
        val results = mapper.createArrayNode()
        all.drop(page * limit).take(limit).forEach {
          results.addObject().put("id", it.id).put("name", it.name).put("nameIgnorePrefix", it.name).put("libraryId", libraryId)
                  .put("addedAt", it.addedAt).put("updatedAt", it.addedAt).set<ObjectNode>("books", mapper.createArrayNode())
        }
        200 to mapper.writeValueAsString(mapper.createObjectNode().put("total", all.size).set<ObjectNode>("results", results))
      }
      "authors" -> {
        val limit = q["limit"]?.toInt() ?: 0
        val page = q["page"]?.toInt() ?: 0
        val all = authors.values.filter { it.libraryId == libraryId }.sortedBy { it.addedAt }
        val call = calls.merge("$libraryId authors", 1, Int::plus)!! - 1
        // Servers before 2.15.0 ignore limit and page and send every author without numBooks or lastFirst
        if (legacyAuthors) {
          val list = mapper.createArrayNode()
          all.forEach { list.addObject().put("id", it.id).put("name", it.name).put("libraryId", libraryId).put("addedAt", it.addedAt).put("updatedAt", it.updatedAt) }
          return 200 to mapper.writeValueAsString(mapper.createObjectNode().set<ObjectNode>("authors", list))
        }
        val answer = AuthorsPage(libraryId, page, call, all.drop(page * limit).take(limit), all.size)
        onAuthorsPage(answer)
        val results = mapper.createArrayNode()
        answer.results.forEach {
          results.addObject().put("id", it.id).putNull("asin").put("name", it.name).putNull("description").putNull("imagePath")
                  .put("libraryId", libraryId).put("addedAt", it.addedAt).put("updatedAt", it.updatedAt).put("numBooks", it.numBooks)
                  .put("lastFirst", it.name.substringAfterLast(' ') + ", " + it.name.substringBeforeLast(' '))
        }
        200 to mapper.writeValueAsString(mapper.createObjectNode().put("total", answer.total).put("limit", limit).put("page", page)
                .set<ObjectNode>("results", results))
      }
      else -> 404 to "Not found"
    }
  }
}
