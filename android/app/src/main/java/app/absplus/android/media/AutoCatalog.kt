package app.absplus.android.media

import android.content.Context
import android.util.Log
import app.absplus.android.data.Book
import app.absplus.android.data.ItemInProgress
import app.absplus.android.data.Library
import app.absplus.android.data.LibraryAuthorItem
import app.absplus.android.data.LibraryCollection
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.LibraryItemSearchResultType
import app.absplus.android.data.LibraryItemWithEpisode
import app.absplus.android.data.LibrarySeriesItem
import app.absplus.android.data.LibraryShelfBookEntity
import app.absplus.android.data.LibraryShelfEpisodeEntity
import app.absplus.android.data.LibraryShelfType
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.db.replica.DEFAULT_SEARCH_LIMIT
import app.absplus.android.db.replica.FilterCodec
import app.absplus.android.db.replica.MAX_SHELF_LIMIT
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DeviceManager
import app.absplus.android.player.PlaybackSource
import app.absplus.android.player.PlaybackSourceResolver
import app.absplus.android.sync.ActiveServer
import app.absplus.android.sync.AuthorsRequest
import app.absplus.android.sync.CatalogReader
import app.absplus.android.sync.CollectionBooksRequest
import app.absplus.android.sync.CollectionsRequest
import app.absplus.android.sync.ItemsRequest
import app.absplus.android.sync.MAX_COLLECTIONS_LIMIT
import app.absplus.android.sync.MAX_READ_LIMIT
import app.absplus.android.sync.PersonalizedRequest
import app.absplus.android.sync.SearchRequest
import app.absplus.android.sync.SeriesRequest
import app.absplus.android.sync.SyncEngine
import app.absplus.android.sync.UserDataStore
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** The id of the downloaded copy (for an episode: the copy's episode) the browse tree points to, or null. */
typealias DownloadMark = (copies: List<LocalLibraryItem>, sc: String, item: LibraryItem, episodeId: String?) -> String?

/** A node answer from the replica; [reconciled] false: the replica may miss part of it (see [replicaOr]). */
data class AutoAnswer<out T>(val value: T, val reconciled: Boolean)

/** The Android Auto root as the replica of the active server and its progress give it. */
data class AutoSnapshot(
        val serverConfigId: String,
        val libraries: List<Library>,
        val readyLibraryIds: Set<String>,
        val discoveryLibraryIds: Set<String>,
        val continueItems: List<ItemInProgress>,
        /** By [MediaProgress.mediaItemId]. */
        val progress: Map<String, MediaProgress>,
        /** Grows with every recompute: the key of cached search results. */
        val generation: Long
)

const val AUTO_CONTINUE_LIMIT = 25

/**
 * Android Auto nodes read from the catalog replica of the active server ([CatalogReader], the rules of the app's tabs)
 * and parsed into the classes the server's answers give, with the download mark of [downloadMark]. Every call returns
 * null when there is no active server or no replica.
 */
class AutoCatalog(
        private val reader: () -> CatalogReader?,
        private val activeServerId: () -> String?,
        private val progressRows: suspend (sc: String) -> List<MediaProgressEntity>,
        private val hasAudio: suspend (sc: String, libraryId: String) -> Boolean,
        private val episodeOwner: suspend (sc: String, episodeId: String) -> String?,
        private val localItems: () -> List<LocalLibraryItem>,
        private val downloadMark: DownloadMark
) {
  private val tag = "AutoCatalog"
  private val mapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
  private val generations = AtomicLong()

  fun activeServer(): String? = activeServerId()

  private suspend fun <T> read(block: suspend (CatalogReader, String) -> T): T? {
    val sc = activeServerId() ?: return null
    val r = reader() ?: return null
    return block(r, sc)
  }

  suspend fun snapshot(): AutoSnapshot? = read { r, sc ->
    val libraries = parseList<Library>(r.libraries(sc).optJSONArray("libraries")).filter { hasAudio(sc, it.id) }
    val progress = progressMapOf(sc)
    val copies = localItems()
    val ready = mutableSetOf<String>()
    val discovery = mutableSetOf<String>()
    val continueItems = mutableListOf<ItemInProgress>()
    for (library in libraries) {
      // The items answer of the library carries the same reconcile time, without ordering every row
      if (reconciled(r.library(sc, library.id))) ready += library.id
      val shelves = r.personalized(sc, PersonalizedRequest(library.id, MAX_SHELF_LIMIT)).optJSONArray("shelves") ?: JSONArray()
      for (i in 0 until shelves.length()) {
        val shelf = shelves.optJSONObject(i) ?: continue
        val entities = shelf.optJSONArray("entities") ?: continue
        when (shelf.optString("id")) {
          "discover" -> if (entities.length() > 0) discovery += library.id
          "continue-listening" -> parseList<LibraryItem>(entities).filter { it.checkHasTracks() }.forEach { item ->
            val episode = item.recentEpisode
            if (episode != null) episode.localEpisodeId = downloadMark(copies, sc, item, episode.id)
            else item.localLibraryItemId = downloadMark(copies, sc, item, null)
            val key = if (episode == null) item.id else "${item.id}-${episode.id}"
            continueItems += ItemInProgress(item, episode, progress[key]?.lastUpdate ?: 0L, false)
          }
        }
      }
    }
    AutoSnapshot(sc, libraries, ready, discovery, continueItems.sortedByDescending { it.progressLastUpdate }.take(AUTO_CONTINUE_LIMIT),
            progress, generations.incrementAndGet())
  }

  suspend fun progressMap(): Map<String, MediaProgress>? = read { _, sc -> progressMapOf(sc) }

  private suspend fun progressMapOf(sc: String): Map<String, MediaProgress> = progressRows(sc).associate { row ->
    val episodeId = row.episodeId.ifEmpty { null }
    val mediaItemId = if (episodeId == null) row.libraryItemId else "${row.libraryItemId}-$episodeId"
    // A row written on this device has no server id yet (or only the marker that the server has a record)
    mediaItemId to MediaProgress(UserDataStore.realServerProgressId(row.serverProgressId) ?: "local:$mediaItemId", row.libraryItemId, episodeId, row.duration, row.progress,
            row.currentTime, row.isFinished, row.ebookLocation, row.ebookProgress, row.lastUpdate, row.startedAt, row.finishedAt)
  }

  private fun titleOrder(libraryId: String, filter: String? = null, collapse: Boolean = false) = { page: Int ->
    ItemsRequest(libraryId, "media.metadata.title", false, filter, collapse, false, MAX_READ_LIMIT, page)
  }

  private suspend fun items(r: CatalogReader, sc: String, request: (Int) -> ItemsRequest): AutoAnswer<List<LibraryItem>> {
    val (items, reconciled) = allPages(page = { r.items(sc, request(it)) }) { parseList<LibraryItem>(it.optJSONArray("results")) }
    return AutoAnswer(markItems(sc, items.filter { it.checkHasTracks() }), reconciled)
  }

  suspend fun podcasts(libraryId: String): AutoAnswer<List<LibraryItem>>? = read { r, sc -> items(r, sc, titleOrder(libraryId)) }

  suspend fun seriesWithAudio(libraryId: String): AutoAnswer<List<LibrarySeriesItem>>? = read { r, sc ->
    val (series, reconciled) = allPages(page = { r.series(sc, SeriesRequest(libraryId, "name", false, false, MAX_READ_LIMIT, it)) }) {
      parseList<LibrarySeriesItem>(it.optJSONArray("results"))
    }
    AutoAnswer(series.filter { it.audiobookCount > 0 }, reconciled)
  }

  suspend fun seriesBooks(libraryId: String, seriesId: String): AutoAnswer<List<LibraryItem>>? = read { r, sc ->
    items(r, sc, titleOrder(libraryId, "series.${FilterCodec.encode(seriesId)}"))
  }

  suspend fun authorsWithBooks(libraryId: String): AutoAnswer<List<LibraryAuthorItem>>? = read { r, sc ->
    val (authors, reconciled) = allPages(page = { r.authors(sc, AuthorsRequest(libraryId, MAX_READ_LIMIT, it)) }) {
      parseList<LibraryAuthorItem>(it.optJSONArray("results"))
    }
    AutoAnswer(authors.filter { (it.numBooks ?: 0) > 0 }.sortedBy { it.name }, reconciled)
  }

  suspend fun authorBooks(libraryId: String, authorId: String): AutoAnswer<List<LibraryItem>>? = read { r, sc ->
    val answer = items(r, sc, titleOrder(libraryId, "authors.${FilterCodec.encode(authorId)}", collapse = true))
    answer.value.forEach { it.collapsedSeries?.libraryId = libraryId }
    answer
  }

  suspend fun collectionsWithAudio(libraryId: String): AutoAnswer<List<LibraryCollection>>? = read { r, sc ->
    var allReconciled = true
    val (json, reconciled) = allPages(MAX_COLLECTIONS_LIMIT, { r.collections(sc, CollectionsRequest(libraryId, MAX_COLLECTIONS_LIMIT, it)) }) { page ->
      val list = page.optJSONArray("results") ?: JSONArray()
      (0 until list.length()).mapNotNull { list.optJSONObject(it) }
    }
    val collections = json.mapNotNull { collection ->
      // The answer's books are cover previews, which do not parse as items: the collection takes its full book list
      val (books, booksReconciled) = collectionBookJson(r, sc, collection.optString("id"))
      if (!booksReconciled) allReconciled = false
      parseOne<LibraryCollection>(collection.put("books", books))
    }
    val withAudio = collections.filter { c -> c.books.orEmpty().any { it.checkHasTracks() } }
    withAudio.forEach { c -> c.books?.let { markItems(sc, it) } }
    AutoAnswer(withAudio, reconciled && allReconciled)
  }

  private suspend fun collectionBookJson(r: CatalogReader, sc: String, collectionId: String): Pair<JSONArray, Boolean> {
    val out = JSONArray()
    val (_, reconciled) = allPages(page = { r.collectionBooks(sc, CollectionBooksRequest(collectionId, MAX_READ_LIMIT, it)) }) { page ->
      page.optJSONArray("results")?.let { list -> for (i in 0 until list.length()) out.put(list.get(i)) }
      emptyList<Unit>()
    }
    return out to reconciled
  }

  suspend fun collectionBooks(libraryId: String, collectionId: String): AutoAnswer<List<LibraryItem>>? = read { r, sc ->
    val (json, reconciled) = collectionBookJson(r, sc, collectionId)
    AutoAnswer(markItems(sc, parseList<LibraryItem>(json).filter { it.checkHasTracks() }), reconciled)
  }

  private suspend fun shelves(r: CatalogReader, sc: String, libraryId: String): AutoAnswer<List<LibraryShelfType>> {
    val answer = r.personalized(sc, PersonalizedRequest(libraryId, MAX_SHELF_LIMIT))
    return AutoAnswer(markShelves(sc, parseList<LibraryShelfType>(answer.optJSONArray("shelves"))), reconciled(answer))
  }

  suspend fun discovery(libraryId: String): AutoAnswer<List<LibraryItem>>? = read { r, sc ->
    val answer = shelves(r, sc, libraryId)
    val discover = answer.value.firstOrNull { it.id == "discover" && it.type == "book" } as? LibraryShelfBookEntity
    AutoAnswer(discover?.entities.orEmpty().filter { it.checkHasTracks() }, answer.reconciled)
  }

  /** The shelves the Recent node lists, in the server's order (as `MediaManager` picked them from the server's answer). */
  suspend fun recentShelves(libraryId: String): AutoAnswer<List<LibraryShelfType>>? = read { r, sc ->
    val answer = shelves(r, sc, libraryId)
    AutoAnswer(answer.value.filter { (it.type to it.id) in RECENT_SHELVES }, answer.reconciled)
  }

  /** The podcast with every episode the replica has; a podcast whose episodes were never read counts as not reconciled. */
  suspend fun podcastWithEpisodes(podcastId: String): AutoAnswer<LibraryItem?>? = read { r, sc ->
    val answer = r.libraryItem(sc, podcastId)
    val item = libraryItemOf(answer)
    val podcast = item?.media as? Podcast
    podcast?.episodes?.let { markEpisodes(sc, item, it) }
    val episodesKnown = podcast == null || !podcast.episodes.isNullOrEmpty() || (podcast.numEpisodes ?: 0) == 0
    AutoAnswer(item, reconciled(answer) && episodesKnown)
  }

  suspend fun search(libraryId: String, query: String): AutoAnswer<LibraryItemSearchResultType>? = read { r, sc ->
    val answer = r.search(sc, SearchRequest(libraryId, query, DEFAULT_SEARCH_LIMIT))
    val result = answer.optJSONObject("results")?.let { parseOne<LibraryItemSearchResultType>(it) }
            ?: LibraryItemSearchResultType(null, null, null, null)
    markItems(sc, (result.book.orEmpty() + result.podcast.orEmpty()).map { it.libraryItem })
    AutoAnswer(result, reconciled(answer))
  }

  suspend fun itemForPlay(id: String): LibraryItem? = read { r, sc ->
    libraryItemOf(r.libraryItem(sc, id))
  }

  suspend fun episodeForPlay(episodeId: String): LibraryItemWithEpisode? = read { r, sc ->
    val owner = episodeOwner(sc, episodeId) ?: return@read null
    val podcast = libraryItemOf(r.libraryItem(sc, owner)) ?: return@read null
    val episode = (podcast.media as? Podcast)?.episodes?.find { it.id == episodeId } ?: return@read null
    LibraryItemWithEpisode(podcast, episode)
  }

  /**
   * Sets the download mark of each book of [items]; the copies are read once. A podcast is not marked: its node would
   * then open the copy, which lists only the downloaded episodes.
   */
  fun markItems(sc: String, items: List<LibraryItem>): List<LibraryItem> {
    val books = items.filter { it.mediaType != "podcast" }
    if (books.isEmpty()) return items
    val copies = localItems()
    books.forEach { it.localLibraryItemId = downloadMark(copies, sc, it, null) }
    return items
  }

  fun markEpisodes(sc: String, podcast: LibraryItem, episodes: List<PodcastEpisode>) {
    if (episodes.isEmpty()) return
    val copies = localItems()
    episodes.forEach { it.localEpisodeId = downloadMark(copies, sc, podcast, it.id) }
  }

  fun markShelves(sc: String, shelves: List<LibraryShelfType>): List<LibraryShelfType> {
    val copies by lazy { localItems() }
    shelves.forEach { shelf ->
      when (shelf) {
        is LibraryShelfBookEntity -> shelf.entities?.forEach { it.localLibraryItemId = downloadMark(copies, sc, it, null) }
        is LibraryShelfEpisodeEntity -> shelf.entities?.forEach { item ->
          item.recentEpisode?.let { it.localEpisodeId = downloadMark(copies, sc, item, it.id) }
        }
        else -> {}
      }
    }
    return shelves
  }

  /** The item of a [CatalogReader.libraryItem] answer; an author the replica could not tell apart by name has no id. */
  private fun libraryItemOf(answer: JSONObject): LibraryItem? {
    val item = answer.optJSONObject("libraryItem") ?: return null
    val metadata = item.optJSONObject("media")?.optJSONObject("metadata")
    metadata?.optJSONArray("authors")?.let { authors ->
      metadata.put("authors", JSONArray((0 until authors.length()).mapNotNull { authors.optJSONObject(it) }.filter { it.opt("id") is String }))
    }
    return parseOne<LibraryItem>(item)
  }

  private fun reconciled(answer: JSONObject): Boolean =
          answer.optJSONObject("replica")?.let { !it.isNull("reconciledAt") } ?: false

  internal suspend fun <T> allPages(limit: Int = MAX_READ_LIMIT, page: suspend (Int) -> JSONObject, parse: (JSONObject) -> List<T>): Pair<List<T>, Boolean> {
    val out = ArrayList<T>()
    var allReconciled = true
    var index = 0
    while (true) {
      val answer = page(index)
      if (!reconciled(answer)) allReconciled = false
      val size = answer.optJSONArray("results")?.length() ?: 0
      out += parse(answer)
      if (size < limit || (index + 1).toLong() * limit >= answer.optLong("total", 0)) break
      index++
    }
    return out to allReconciled
  }

  private inline fun <reified T> parseOne(json: JSONObject): T? = try {
    mapper.readValue<T>(json.toString())
  } catch (e: Exception) {
    Log.w(tag, "Skipping an unparsable ${T::class.java.simpleName}: ${e.message}")
    null
  }

  private inline fun <reified T> parseList(list: JSONArray?): List<T> =
          if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let { json -> parseOne<T>(json) } }

  companion object {
    private val RECENT_SHELVES = setOf("book" to "recently-added", "series" to "recent-series", "episode" to "newest-episodes",
            "podcast" to "recently-added", "authors" to "newest-authors")

    fun create(ctx: Context): AutoCatalog {
      val app = ctx.applicationContext
      return AutoCatalog(
              reader = { SyncEngine.catalog },
              activeServerId = { ActiveServer.current()?.id },
              progressRows = { sc -> SyncEngine.store?.progressRows(sc).orEmpty() },
              // The replica has no library stats, by which the server's list drops libraries without audio
              hasAudio = { sc, libraryId -> ReplicaDatabase.get(app).items().rows(sc, libraryId).any { it.numTracks > 0 || it.numEpisodes > 0 } },
              episodeOwner = { sc, id -> ReplicaDatabase.get(app).episodes().byIds(sc, listOf(id)).firstOrNull()?.libraryItemId },
              localItems = { DeviceManager.dbManager.getLocalLibraryItems() },
              downloadMark = resolverDownloadMark(
                      { !AppStorage.isAppStorage(it.folderId) || AppStorage.isAvailable(it.folderId, AndroidVolumeProbe(app)) }) { copy, episode ->
                copy.hasTracks(app, episode)
              })
    }
  }
}

/** The start's rule ([PlaybackSourceResolver]): a copy marks an item only when a start of it would play that copy. */
internal fun resolverDownloadMark(
        storageAvailable: (LocalLibraryItem) -> Boolean = { true },
        hasTracks: (LocalLibraryItem, PodcastEpisode?) -> Boolean
): DownloadMark = { copies, sc, item, episodeId ->
  val resolver = PlaybackSourceResolver({ copies }, { id -> copies.find { it.id == id } }, storageAvailable, hasTracks)
  val book = item.media as? Book
  when (val source = resolver.resolve(sc, item.id, episodeId, item.ino, book?.let { it.numTracks ?: it.tracks?.size })) {
    is PlaybackSource.Local -> if (episodeId == null) source.item.id else source.episode?.id
    else -> null
  }
}
