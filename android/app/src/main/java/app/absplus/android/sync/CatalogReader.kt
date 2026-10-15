package app.absplus.android.sync

import app.absplus.android.db.replica.FilterCodec
import app.absplus.android.db.replica.ItemsQuery
import app.absplus.android.db.replica.QueryEntry
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.UnsupportedFilterException
import app.absplus.android.db.replica.orderItems
import app.absplus.android.db.userdata.MediaProgressEntity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** The most entities one bridge call returns. */
const val MAX_READ_LIMIT = 100

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

  fun onReplicaChanged() {
    synchronized(lock) {
      generation++
      cached = null
    }
  }

  private fun replica(reconciledAt: Long?, complete: Boolean) =
          JSONObject().put("reconciledAt", reconciledAt ?: JSONObject.NULL).put("complete", complete)

  suspend fun libraries(sc: String): JSONObject {
    val list = JSONArray()
    db.libraries().all(sc).forEach { list.put(JSONObject(it.json)) }
    val state = db.libraryState().get(sc, "")
    return JSONObject().put("serverConfigId", sc).put("libraries", list)
            .put("replica", replica(state?.itemsReconciledAt, state?.itemsComplete ?: false))
  }

  /** `library: null` (and no reconcile time) for a library the replica does not have. */
  suspend fun library(sc: String, id: String): JSONObject {
    val library = db.libraries().get(sc, id)
    val state = library?.let { db.libraryState().get(sc, id) }
    return JSONObject().put("serverConfigId", sc)
            .put("library", library?.let { JSONObject(it.json) } ?: JSONObject.NULL)
            .put("filterdata", library?.filterDataJson?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("issues", library?.issues ?: 0)
            .put("numUserPlaylists", library?.numUserPlaylists ?: 0)
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
            .put("replica", replica(state?.itemsReconciledAt, state?.itemsComplete ?: false))
    val filter = FilterCodec.parse(req.filter)
    val hideSingle = library?.let { JSONObject(it.json).optJSONObject("settings")?.optBoolean("hideSingleBookSeries") } ?: false
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
    val authorNames = if (query.filter?.group == "authors" && filterDataJson != null) {
      JSONObject(filterDataJson).optJSONArray("authors")?.let { list ->
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.associate { it.optString("id") to it.optString("name") }
      } ?: emptyMap()
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
      when (entry) {
        is QueryEntry.Collapsed -> item.put("collapsedSeries", JSONObject()
                .put("id", entry.seriesId).put("name", entry.name).put("nameIgnorePrefix", entry.nameIgnorePrefix)
                .put("sequence", entry.sequence ?: JSONObject.NULL).put("numBooks", entry.numBooks)
                .put("libraryItemIds", JSONArray(entry.libraryItemIds)))
        is QueryEntry.Item -> entry.series?.let { member ->
          item.optJSONObject("media")?.optJSONObject("metadata")?.put("series", JSONObject()
                  .put("id", member.seriesId).put("name", seriesName ?: JSONObject.NULL).put("sequence", member.sequence ?: JSONObject.NULL))
        }
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
