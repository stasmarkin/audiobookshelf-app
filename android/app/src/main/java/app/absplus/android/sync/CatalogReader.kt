package app.absplus.android.sync

import androidx.room.withTransaction
import app.absplus.android.db.replica.CollectionCounts
import app.absplus.android.db.replica.CollectionEntity
import app.absplus.android.db.replica.FilterCodec
import app.absplus.android.db.replica.ItemsQuery
import app.absplus.android.db.replica.QueryEntry
import app.absplus.android.db.replica.LibraryEntity
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.replica.PlaylistCounts
import app.absplus.android.db.replica.PlaylistEntity
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.db.replica.SeriesEntity
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.SeriesItemEntity
import app.absplus.android.db.replica.UnsupportedFilterException
import app.absplus.android.db.replica.naturalComparator
import app.absplus.android.db.replica.nocaseCompare
import app.absplus.android.db.replica.orderItems
import app.absplus.android.db.replica.withoutPrefix
import app.absplus.android.db.userdata.MediaProgressEntity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** The most entities one bridge call returns. */
const val MAX_READ_LIMIT = 100

/** Collections a list call returns: each carries up to [COLLECTION_PREVIEW_BOOKS] books, so a call stays within 100 items. */
const val MAX_COLLECTIONS_LIMIT = 20
const val COLLECTION_PREVIEW_BOOKS = 4

/** As collections: each playlist of a list call carries up to [PLAYLIST_PREVIEW_ITEMS] items. */
const val MAX_PLAYLISTS_LIMIT = 20
const val PLAYLIST_PREVIEW_ITEMS = 4

data class ItemsRequest(
        val libraryId: String,
        val sort: String?,
        val desc: Boolean,
        val filter: String?,
        val collapseSeries: Boolean,
        val sortingIgnorePrefix: Boolean,
        val limit: Int,
        val page: Int
)

/** `GET /api/libraries/:id/series` as the Series tab asks it: only the name order exists, any other sort falls back to it. */
data class SeriesRequest(
        val libraryId: String,
        val sort: String?,
        val desc: Boolean,
        val sortingIgnorePrefix: Boolean,
        val limit: Int,
        val page: Int
)

/** `GET /api/libraries/:id/authors`: always ordered by name. */
data class AuthorsRequest(val libraryId: String, val limit: Int, val page: Int)

data class CollectionsRequest(val libraryId: String, val limit: Int, val page: Int)

/** A page of the books of a collection. */
data class CollectionBooksRequest(val id: String, val limit: Int, val page: Int)

data class PlaylistsRequest(val libraryId: String, val limit: Int, val page: Int)

/** A page of the items of a playlist. */
data class PlaylistItemsRequest(val id: String, val limit: Int, val page: Int)

/**
 * Reads the catalog replica in the shape of the server's answers. The ordered ids of the last items query are kept,
 * so the pages of one list are slices of one order; any replica write ([onReplicaChanged]) and, for queries that
 * depend on it, any progress change drop them.
 */
class CatalogReader(
        private val db: ReplicaDatabase,
        private val progress: suspend (serverConfigId: String) -> List<MediaProgressEntity>,
        private val progressFor: suspend (serverConfigId: String, ids: List<String>) -> List<MediaProgressEntity>,
        private val progressVersion: () -> Long,
        private val clock: () -> Long
) {
  @Volatile private var generation = 0L
  private val lock = Any()
  private var cached: Pair<Any, List<QueryEntry>>? = null
  private var cachedSeries: Pair<Any, List<Pair<SeriesEntity, List<SeriesItemEntity>>>>? = null

  fun onReplicaChanged() {
    synchronized(lock) {
      generation++
      cached = null
      cachedSeries = null
    }
  }

  private fun hideSingleBookSeries(library: LibraryEntity?) =
          library?.let { JSONObject(it.json).optJSONObject("settings")?.optBoolean("hideSingleBookSeries") } ?: false

  private fun replica(reconciledAt: Long?, complete: Boolean) =
          JSONObject().put("reconciledAt", reconciledAt ?: JSONObject.NULL).put("complete", complete)

  suspend fun libraries(sc: String): JSONObject {
    val list = JSONArray()
    db.libraries().all(sc).forEach { list.put(JSONObject(it.json)) }
    val state = db.libraryState().get(sc, "")
    return JSONObject().put("serverConfigId", sc).put("libraries", list)
            .put("replica", replica(state?.itemsReconciledAt, state?.itemsComplete ?: false))
  }

  /**
   * `library: null` (and no reconcile time) for a library the replica does not have. `numUserPlaylists` counts the
   * playlists in the replica; until they were read once, at least the count the library read brought.
   */
  suspend fun library(sc: String, id: String): JSONObject {
    val library = db.libraries().get(sc, id)
    val state = library?.let { db.libraryState().get(sc, id) }
    // Every playlist, as the server's getNumPlaylistsForUserAndLibrary; only a change of this count announces library:<id>
    val playlists = library?.let { db.playlists().count(sc, id) } ?: 0
    val numUserPlaylists = if (state?.playlistsReconciledAt != null) playlists else maxOf(playlists, library?.numUserPlaylists ?: 0)
    return JSONObject().put("serverConfigId", sc)
            .put("library", library?.let { JSONObject(it.json) } ?: JSONObject.NULL)
            .put("filterdata", library?.filterDataJson?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("issues", library?.issues ?: 0)
            .put("numUserPlaylists", numUserPlaylists)
            .put("replica", replica(state?.itemsReconciledAt, state?.itemsComplete ?: false))
  }

  suspend fun items(sc: String, req: ItemsRequest): JSONObject {
    require(req.limit in 1..MAX_READ_LIMIT) { "limit must be 1..$MAX_READ_LIMIT" }
    require(req.page >= 0) { "page must not be negative" }
    val library = db.libraries().get(sc, req.libraryId)
    val mediaType = library?.mediaType ?: "book"
    val state = db.libraryState().get(sc, req.libraryId)
    val answer = JSONObject().put("serverConfigId", sc).put("limit", req.limit).put("page", req.page)
            .put("sortBy", req.sort ?: JSONObject.NULL).put("sortDesc", req.desc).put("filterBy", req.filter ?: JSONObject.NULL)
            .put("mediaType", mediaType).put("minified", true).put("collapseseries", req.collapseSeries)
            .put("include", "rssfeed,numepisodesincomplete")
    val filter = FilterCodec.parse(req.filter)
    // A series whose membership still waits for its point query, or one the replica lacks (new on the server), may
    // miss books: read as never reconciled
    val seriesId = filter?.takeIf { it.group == "series" && it.value != "no-series" }?.value
    val partialSeries = seriesId != null && state != null && (db.series().get(sc, seriesId) == null ||
            seriesId in JSONArray(state.pendingSeriesQueries).let { list -> (0 until list.length()).map { list.optString(it) } })
    answer.put("replica", replica(state?.itemsReconciledAt?.takeUnless { partialSeries }, state?.itemsComplete ?: false))
    val hideSingle = hideSingleBookSeries(library)
    val query = ItemsQuery(req.sort, req.desc, filter, req.collapseSeries, req.sortingIgnorePrefix, mediaType, hideSingle)
    val byProgress = req.sort?.startsWith("progress") == true || filter?.group == "progress"
    val key = listOf(sc, req.libraryId, query, if (byProgress) progressVersion() else null)

    val entries = try {
      ordered(sc, req.libraryId, query, key, byProgress, library?.filterDataJson)
    } catch (e: UnsupportedFilterException) {
      return answer.put("results", JSONArray()).put("total", 0).put("unsupportedFilter", true)
    }
    val slice = entries.drop(req.page * req.limit).take(req.limit)
    return answer.put("results", results(sc, slice, mediaType, filter?.takeIf { it.group == "series" }?.value)).put("total", entries.size)
  }

  private suspend fun ordered(sc: String, libraryId: String, query: ItemsQuery, key: List<Any?>, byProgress: Boolean, filterDataJson: String?): List<QueryEntry> {
    val generationAtStart: Long
    synchronized(lock) {
      cached?.let { (cachedKey, entries) -> if (cachedKey == key) return entries }
      generationAtStart = generation
    }
    val rows = db.items().rows(sc, libraryId)
    val progressMap = if (byProgress) {
      progress(sc).filter { it.episodeId.isEmpty() }.associateBy { it.libraryItemId }
    } else emptyMap()
    val needsSeries = query.collapseSeries || query.filter?.group == "series"
    val series = if (needsSeries) SeriesIndex.of(db.series().forLibrary(sc, libraryId), db.series().members(sc, libraryId)) else SeriesIndex.EMPTY
    val authorNames = if (query.filter?.group == "authors") {
      val cached = filterDataJson?.let { json ->
        JSONObject(json).optJSONArray("authors")?.let { list ->
          (0 until list.length()).mapNotNull { list.optJSONObject(it) }.associate { it.optString("id") to it.optString("name") }
        }
      } ?: emptyMap()
      // An author added (by an event) after the filterdata was cached is only in the authors table
      val id = query.filter.value
      val added = if (id != null && id !in cached) db.authors().get(sc, id)?.takeIf { it.libraryId == libraryId } else null
      if (added != null) cached + (added.id to added.name) else cached
    } else emptyMap()
    val narratorNames = if (query.filter?.group == "narrators" && filterDataJson != null) {
      JSONObject(filterDataJson).optJSONArray("narrators")?.let { list -> (0 until list.length()).mapNotNull { list.optString(it, null) } } ?: emptyList()
    } else emptyList()
    // One seed per query: the pages of one random list are slices of the same shuffle
    val entries = orderItems(rows, query, progressMap, series, authorNames, clock(), Random(key.hashCode()), narratorNames)
    synchronized(lock) {
      // A write while this was computed makes the order stale for the next call; this one still answers with it
      if (generation == generationAtStart) cached = key to entries
    }
    return entries
  }

  /** A page of series, each with all its books in the replica (minified, in the server's sequence order). */
  suspend fun series(sc: String, req: SeriesRequest): JSONObject {
    require(req.limit in 1..MAX_READ_LIMIT) { "limit must be 1..$MAX_READ_LIMIT" }
    require(req.page >= 0) { "page must not be negative" }
    val state = db.libraryState().get(sc, req.libraryId)
    val ordered = orderedSeries(sc, req.libraryId, req.desc, req.sortingIgnorePrefix, hideSingleBookSeries(db.libraries().get(sc, req.libraryId)))
    val slice = ordered.drop(req.page * req.limit).take(req.limit)
    val books = db.items().json(sc, slice.flatMap { (_, members) -> members.map { it.libraryItemId } }).associateBy { it.id }
    val results = JSONArray()
    for ((series, members) in slice) {
      val list = JSONArray()
      members.forEach { m -> books[m.libraryItemId]?.let { list.put(JSONObject(it.json)) } }
      results.put(seriesJson(series).put("books", list))
    }
    return JSONObject().put("serverConfigId", sc).put("results", results).put("total", ordered.size).put("limit", req.limit)
            .put("page", req.page).put("sortBy", req.sort ?: JSONObject.NULL).put("sortDesc", req.desc)
            .put("replica", replica(seriesReconciledAt(state), state?.itemsComplete ?: false))
  }

  /** Series are read before the books of a pass: until the books were read once, the series have none. */
  private fun seriesReconciledAt(state: LibraryStateEntity?) = state?.seriesReconciledAt?.takeIf { state.itemsReconciledAt != null }

  /** Series kept from filterdata (hidden single-book ones) were written without their library. */
  private fun seriesJson(series: SeriesEntity) = JSONObject(series.json).apply { if (!has("libraryId")) put("libraryId", series.libraryId) }

  /**
   * `series: null` when the replica lacks it; then `reconciledAt` is null while any library of the server never had its
   * series read (the series may be there), else the oldest series pass.
   */
  suspend fun seriesById(sc: String, id: String): JSONObject {
    val series = db.series().get(sc, id)
    val replica = if (series != null) {
      val state = db.libraryState().get(sc, series.libraryId)
      replica(seriesReconciledAt(state), state?.itemsComplete ?: false)
    } else {
      val states = db.libraryState().all(sc).associateBy { it.libraryId }
      val libraries = db.libraries().all(sc).map { states[it.id] }
      val times = libraries.map { seriesReconciledAt(it) }
      replica(if (times.isEmpty() || times.any { it == null }) null else times.filterNotNull().min(),
              libraries.isNotEmpty() && libraries.all { it?.itemsComplete == true })
    }
    return JSONObject().put("serverConfigId", sc).put("series", series?.let { seriesJson(it) } ?: JSONObject.NULL)
            .put("replica", replica)
  }

  /** A page of the library's authors as the server lists them, ordered by name (NOCASE), then id. */
  suspend fun authors(sc: String, req: AuthorsRequest): JSONObject {
    require(req.limit in 1..MAX_READ_LIMIT) { "limit must be 1..$MAX_READ_LIMIT" }
    require(req.page >= 0) { "page must not be negative" }
    val state = db.libraryState().get(sc, req.libraryId)
    val (page, total) = db.withTransaction {
      db.authors().pageByName(sc, req.libraryId, req.limit, minOf(req.page.toLong() * req.limit, Int.MAX_VALUE.toLong()).toInt()) to db.authors().count(sc, req.libraryId)
    }
    val results = JSONArray()
    page.forEach { results.put(JSONObject(it.json)) }
    return JSONObject().put("serverConfigId", sc).put("results", results).put("total", total).put("limit", req.limit)
            .put("page", req.page).put("sortBy", "name").put("sortDesc", false)
            .put("replica", replica(state?.authorsReconciledAt, state?.itemsComplete ?: false))
  }

  private fun offset(page: Int, limit: Int) = minOf(page.toLong() * limit, Int.MAX_VALUE.toLong()).toInt()

  /** As the server: a collection whose books are all out of the user's reach is not shown; an empty one is. */
  private fun shown(counts: CollectionCounts?) = counts == null || counts.visible > 0

  private fun collectionsReconciledAt(state: LibraryStateEntity?) = state?.collectionsReconciledAt?.takeIf { state.itemsReconciledAt != null }

  /**
   * A page of the library's collections in the server's order, each with `numBooks` and the first
   * [COLLECTION_PREVIEW_BOOKS] books it can show in a cut-down form (enough for a cover); the books themselves are
   * read with [collectionBooks].
   */
  suspend fun collections(sc: String, req: CollectionsRequest): JSONObject {
    require(req.limit in 1..MAX_COLLECTIONS_LIMIT) { "limit must be 1..$MAX_COLLECTIONS_LIMIT" }
    require(req.page >= 0) { "page must not be negative" }
    val state = db.libraryState().get(sc, req.libraryId)
    val results = JSONArray()
    val total = db.withTransaction {
      val counts = db.collections().counts(sc, req.libraryId).associateBy { it.collectionId }
      val shown = db.collections().forLibrary(sc, req.libraryId).filter { shown(counts[it.id]) }
      val slice = shown.drop(offset(req.page, req.limit)).take(req.limit)
      val previewIds = slice.associate { it.id to db.collections().visibleItemIds(sc, it.id, COLLECTION_PREVIEW_BOOKS, 0) }
      val books = db.items().json(sc, previewIds.values.flatten().distinct()).associateBy { it.id }
      for (collection in slice) {
        val previews = JSONArray()
        previewIds.getValue(collection.id).forEach { id -> books[id]?.let { previews.put(JSONObject(CatalogJson.previewItem(it.json).toString())) } }
        results.put(collectionJson(collection, counts[collection.id]).put("books", previews))
      }
      shown.size
    }
    return JSONObject().put("serverConfigId", sc).put("results", results).put("total", total).put("limit", req.limit)
            .put("page", req.page).put("replica", replica(collectionsReconciledAt(state), state?.itemsComplete ?: false))
  }

  private fun collectionJson(collection: CollectionEntity, counts: CollectionCounts?) =
          JSONObject(collection.json).put("numBooks", counts?.visible ?: 0)

  /** The collection to show, with its counts; null when the replica lacks it or the user may read none of its books. */
  private suspend fun shownCollection(sc: String, id: String): Pair<CollectionEntity, CollectionCounts?>? {
    val collection = db.collections().get(sc, id) ?: return null
    val counts = db.collections().countsOf(sc, id)
    return if (shown(counts)) collection to counts else null
  }

  /**
   * The replica state of a collection; for one the replica lacks, null while any book library of the server never had
   * its collections read (it may be there), else the oldest collections pass.
   */
  private suspend fun collectionReplica(sc: String, collection: CollectionEntity?): JSONObject {
    if (collection != null) {
      val state = db.libraryState().get(sc, collection.libraryId)
      return replica(collectionsReconciledAt(state), state?.itemsComplete ?: false)
    }
    val states = db.libraryState().all(sc).associateBy { it.libraryId }
    val libraries = db.libraries().all(sc).filter { it.mediaType == "book" }.map { states[it.id] }
    val times = libraries.map { collectionsReconciledAt(it) }
    return replica(if (times.isEmpty() || times.any { it == null }) null else times.filterNotNull().min(),
            libraries.isNotEmpty() && libraries.all { it?.itemsComplete == true })
  }

  /** `GET /api/collections/:id` without its books: `collection: null` when it is not to be shown. */
  suspend fun collection(sc: String, id: String): JSONObject {
    val (collection, replica) = db.withTransaction {
      val found = shownCollection(sc, id)
      found?.let { (c, counts) -> collectionJson(c, counts) } to collectionReplica(sc, found?.first)
    }
    return JSONObject().put("serverConfigId", sc).put("collection", collection ?: JSONObject.NULL).put("replica", replica)
  }

  /**
   * A page of the books of a collection the replica has, minified as the items list gives them, in the collection's
   * order; `found: false` (and no books) when the collection is not to be shown.
   */
  suspend fun collectionBooks(sc: String, req: CollectionBooksRequest): JSONObject {
    require(req.limit in 1..MAX_READ_LIMIT) { "limit must be 1..$MAX_READ_LIMIT" }
    require(req.page >= 0) { "page must not be negative" }
    val results = JSONArray()
    val (total, found, replica) = db.withTransaction {
      val found = shownCollection(sc, req.id)
      val ids = found?.let { db.collections().visibleItemIds(sc, req.id, req.limit, offset(req.page, req.limit)) } ?: emptyList()
      val json = db.items().json(sc, ids).associateBy { it.id }
      ids.forEach { id ->
        json[id]?.let { row -> results.put(JSONObject(row.json).apply { row.rssFeedJson?.let { put("rssFeed", JSONObject(it)) } }) }
      }
      Triple(found?.second?.visible ?: 0, found != null, collectionReplica(sc, found?.first))
    }
    return JSONObject().put("serverConfigId", sc).put("results", results).put("total", total).put("limit", req.limit)
            .put("page", req.page).put("found", found).put("replica", replica)
  }

  /** As the server, a playlist is never empty: one whose items are all out of the replica (the user's reach) is not shown. */
  private fun shown(counts: PlaylistCounts?) = counts == null || counts.visible > 0

  private fun playlistsReconciledAt(state: LibraryStateEntity?) = state?.playlistsReconciledAt?.takeIf { state.itemsReconciledAt != null }

  private fun itemKey(libraryItemId: String, episodeId: String) =
          JSONObject().put("libraryItemId", libraryItemId).put("episodeId", episodeId.ifEmpty { null } ?: JSONObject.NULL)

  /**
   * A page of the library's playlists in the server's order, each with `numItems`, the first [PLAYLIST_PREVIEW_ITEMS]
   * items it can show with their library item cut down to a cover, and `itemKeys`: every item it can show, ids only
   * (what "already in this playlist" needs). The items themselves are read with [playlistItems].
   */
  suspend fun playlists(sc: String, req: PlaylistsRequest): JSONObject {
    require(req.limit in 1..MAX_PLAYLISTS_LIMIT) { "limit must be 1..$MAX_PLAYLISTS_LIMIT" }
    require(req.page >= 0) { "page must not be negative" }
    val state = db.libraryState().get(sc, req.libraryId)
    val results = JSONArray()
    val total = db.withTransaction {
      val counts = db.playlists().counts(sc, req.libraryId).associateBy { it.playlistId }
      val shown = db.playlists().forLibrary(sc, req.libraryId).filter { shown(counts[it.id]) }
      val slice = shown.drop(offset(req.page, req.limit)).take(req.limit)
      val keys = slice.associate { it.id to db.playlists().visibleKeys(sc, it.id) }
      val previewIds = keys.values.flatMap { list -> list.take(PLAYLIST_PREVIEW_ITEMS).map { it.libraryItemId } }.distinct()
      val covers = db.items().json(sc, previewIds).associate { it.id to CatalogJson.previewItem(it.json).toString() }
      for (playlist in slice) {
        val previews = JSONArray()
        val itemKeys = JSONArray()
        keys.getValue(playlist.id).forEachIndexed { i, key ->
          itemKeys.put(itemKey(key.libraryItemId, key.episodeId))
          if (i < PLAYLIST_PREVIEW_ITEMS) {
            covers[key.libraryItemId]?.let { cover ->
              previews.put(itemKey(key.libraryItemId, key.episodeId).put("libraryItem", JSONObject(cover)).put("episode", JSONObject.NULL))
            }
          }
        }
        results.put(playlistJson(playlist, counts[playlist.id]).put("items", previews).put("itemKeys", itemKeys))
      }
      shown.size
    }
    return JSONObject().put("serverConfigId", sc).put("results", results).put("total", total).put("limit", req.limit)
            .put("page", req.page).put("replica", replica(playlistsReconciledAt(state), state?.itemsComplete ?: false))
  }

  private fun playlistJson(playlist: PlaylistEntity, counts: PlaylistCounts?) =
          JSONObject(playlist.json).put("numItems", counts?.visible ?: 0)

  private suspend fun shownPlaylist(sc: String, id: String): Pair<PlaylistEntity, PlaylistCounts?>? {
    val playlist = db.playlists().get(sc, id) ?: return null
    val counts = db.playlists().countsOf(sc, id)
    return if (shown(counts)) playlist to counts else null
  }

  /**
   * The replica state of a playlist; for one the replica lacks, null while any library of the server never had its
   * playlists read (it may be there), else the oldest playlists pass.
   */
  private suspend fun playlistReplica(sc: String, playlist: PlaylistEntity?): JSONObject {
    if (playlist != null) {
      val state = db.libraryState().get(sc, playlist.libraryId)
      return replica(playlistsReconciledAt(state), state?.itemsComplete ?: false)
    }
    val states = db.libraryState().all(sc).associateBy { it.libraryId }
    val libraries = db.libraries().all(sc).map { states[it.id] }
    val times = libraries.map { playlistsReconciledAt(it) }
    return replica(if (times.isEmpty() || times.any { it == null }) null else times.filterNotNull().min(),
            libraries.isNotEmpty() && libraries.all { it?.itemsComplete == true })
  }

  /** `GET /api/playlists/:id` without its items: `playlist: null` when it is not to be shown. */
  suspend fun playlist(sc: String, id: String): JSONObject {
    val (playlist, replica) = db.withTransaction {
      val found = shownPlaylist(sc, id)
      found?.let { (p, counts) -> playlistJson(p, counts) } to playlistReplica(sc, found?.first)
    }
    return JSONObject().put("serverConfigId", sc).put("playlist", playlist ?: JSONObject.NULL).put("replica", replica)
  }

  /**
   * A page of the items of a playlist the replica has, in its order: the library item minified as the items list gives
   * it, an episode as the server expanded it; `found: false` (and no items) when the playlist is not to be shown.
   */
  suspend fun playlistItems(sc: String, req: PlaylistItemsRequest): JSONObject {
    require(req.limit in 1..MAX_READ_LIMIT) { "limit must be 1..$MAX_READ_LIMIT" }
    require(req.page >= 0) { "page must not be negative" }
    val results = JSONArray()
    val (total, found, replica) = db.withTransaction {
      val found = shownPlaylist(sc, req.id)
      val items = found?.let { db.playlists().visibleItems(sc, req.id, req.limit, offset(req.page, req.limit)) } ?: emptyList()
      val json = db.items().json(sc, items.map { it.libraryItemId }.distinct()).associateBy { it.id }
      items.forEach { item ->
        val row = json[item.libraryItemId] ?: return@forEach
        val libraryItem = JSONObject(row.json).apply { row.rssFeedJson?.let { put("rssFeed", JSONObject(it)) } }
        results.put(itemKey(item.libraryItemId, item.episodeId).put("libraryItem", libraryItem)
                .put("episode", item.episodeJson?.let { JSONObject(it) } ?: JSONObject.NULL))
      }
      Triple(found?.second?.visible ?: 0, found != null, playlistReplica(sc, found?.first))
    }
    return JSONObject().put("serverConfigId", sc).put("results", results).put("total", total).put("limit", req.limit)
            .put("page", req.page).put("found", found).put("replica", replica)
  }

  private suspend fun orderedSeries(sc: String, libraryId: String, desc: Boolean, ignorePrefix: Boolean, hideSingle: Boolean):
          List<Pair<SeriesEntity, List<SeriesItemEntity>>> {
    val key = listOf(sc, libraryId, desc, ignorePrefix, hideSingle)
    val generationAtStart: Long
    synchronized(lock) {
      cachedSeries?.let { (cachedKey, list) -> if (cachedKey == key) return list }
      generationAtStart = generation
    }
    val itemIds = db.items().ids(sc, libraryId).toHashSet()
    val members = db.series().members(sc, libraryId).filter { it.libraryItemId in itemIds }.groupBy { it.seriesId }
    val natural = naturalComparator()
    // seriesFilters: natural order of the sequence, books without one last
    val bookOrder = Comparator<SeriesItemEntity> { a, b ->
      val x = a.sequence?.takeIf { it.isNotEmpty() }
      val y = b.sequence?.takeIf { it.isNotEmpty() }
      when {
        x == null && y == null -> 0
        x == null -> 1
        y == null -> -1
        else -> natural.compare(x, y)
      }
    }.thenBy { it.libraryItemId }
    val name: (SeriesEntity) -> String = if (ignorePrefix) { s -> withoutPrefix(s.name, s.nameIgnorePrefix) } else { s -> s.name }
    val byName = Comparator<Pair<SeriesEntity, List<SeriesItemEntity>>> { a, b -> nocaseCompare(name(a.first), name(b.first)) }
    val list = db.series().forLibrary(sc, libraryId).mapNotNull { series ->
      val books = members[series.id] ?: return@mapNotNull null
      if (hideSingle && books.size <= 1) null else series to books.sortedWith(bookOrder)
    }.sortedWith((if (desc) byName.reversed() else byName).thenBy { it.first.addedAt }.thenBy { it.first.id })
    synchronized(lock) {
      if (generation == generationAtStart) cachedSeries = key to list
    }
    return list
  }

  private suspend fun results(sc: String, slice: List<QueryEntry>, mediaType: String, seriesFilterId: String?): JSONArray {
    val ids = slice.map { it.itemId }
    val json = db.items().json(sc, ids).associateBy { it.id }
    val seriesName = seriesFilterId?.let { db.series().get(sc, it)?.name }
    val finishedEpisodes = if (mediaType == "podcast") {
      progressFor(sc, ids).filter { it.episodeId.isNotEmpty() && it.isFinished }.groupingBy { it.libraryItemId }.eachCount()
    } else emptyMap()
    val out = JSONArray()
    for (entry in slice) {
      val row = json[entry.itemId] ?: continue
      val item = JSONObject(row.json)
      row.rssFeedJson?.let { item.put("rssFeed", JSONObject(it)) }
      fun putSeries(member: SeriesItemEntity) {
        item.optJSONObject("media")?.optJSONObject("metadata")?.put("series", JSONObject()
                .put("id", member.seriesId).put("name", seriesName ?: JSONObject.NULL).put("sequence", member.sequence ?: JSONObject.NULL))
      }
      when (entry) {
        is QueryEntry.Collapsed -> if (entry.seriesSequenceList != null) {
          item.put("collapsedSeries", JSONObject()
                  .put("id", entry.seriesId).put("name", entry.name).put("nameIgnorePrefix", entry.nameIgnorePrefix)
                  .put("libraryItemIds", JSONArray(entry.libraryItemIds)).put("numBooks", entry.numBooks)
                  .put("seriesSequenceList", entry.seriesSequenceList))
          entry.filterMember?.let { putSeries(it) }
        } else {
          item.put("collapsedSeries", JSONObject()
                  .put("id", entry.seriesId).put("name", entry.name).put("nameIgnorePrefix", entry.nameIgnorePrefix)
                  .put("sequence", entry.sequence ?: JSONObject.NULL).put("numBooks", entry.numBooks)
                  .put("libraryItemIds", JSONArray(entry.libraryItemIds)))
        }
        is QueryEntry.Item -> entry.series?.let { putSeries(it) }
      }
      if (mediaType == "podcast") {
        val numEpisodes = item.optJSONObject("media")?.optInt("numEpisodes") ?: 0
        val incomplete = numEpisodes - (finishedEpisodes[entry.itemId] ?: 0)
        if (incomplete != 0) item.put("numEpisodesIncomplete", incomplete)
      }
      out.put(item)
    }
    return out
  }
}
