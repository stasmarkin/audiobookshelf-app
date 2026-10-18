package app.absplus.android.media

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.player.prefetchAutoCovers
import app.absplus.android.managers.DbManager
import app.absplus.android.sync.ActiveServer
import app.absplus.android.sync.DataChange
import app.absplus.android.sync.ServerReachability
import app.absplus.android.sync.ServerState
import app.absplus.android.sync.SyncEngine
import app.absplus.android.server.ApiHandler
import com.getcapacitor.JSObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.atomic.AtomicInteger

/**
 * [replica] when it is reconciled; otherwise the server while it is reachable ([online]), else what the replica has.
 * A replica that fails or has no answer (no active server) falls back to the server only when it is reachable.
 */
internal suspend fun <T> replicaOr(online: () -> Boolean, replica: suspend () -> AutoAnswer<T>?, server: ((T) -> Unit) -> Unit): T? {
  val answer = try {
    replica()
  } catch (e: Exception) {
    null
  }
  if (answer != null && (answer.reconciled || !online())) return answer.value
  if (!online()) return null
  return suspendCancellableCoroutine { cont ->
    // Some server paths call back twice (an empty answer, then the cached one): the first answer counts
    val answered = AtomicBoolean(false)
    server { value -> if (answered.compareAndSet(false, true)) cont.resume(value) }
  }
}

/** The server fallback's caches are written from OkHttp threads and read by concurrent browse requests; lists keep their order. */
private fun <K, V> orderedMap(): MutableMap<K, V> = Collections.synchronizedMap(LinkedHashMap())

/** Without the server a downloaded match is preferred, since only it can play. */
internal fun voicePick(found: List<LibraryItem>, online: Boolean): LibraryItem? =
        if (online) found.firstOrNull() else found.firstOrNull { it.localLibraryItemId != null } ?: found.firstOrNull()

/**
 * Play without a choice resumes the latest started item of the replica, then one the server reported in progress,
 * then the first downloaded book. Without the server a downloaded candidate comes first, since only it can play.
 */
internal fun firstPlayableOf(
        continueItems: List<ItemInProgress>,
        serverItems: List<ItemInProgress>,
        localBooks: List<LibraryItemWrapper>,
        online: Boolean = true
): Pair<LibraryItemWrapper, PodcastEpisode?>? {
  val candidates = continueItems + serverItems
  fun downloaded(item: ItemInProgress) = item.libraryItemWrapper is LocalLibraryItem ||
          (if (item.episode != null) item.episode.localEpisodeId != null else (item.libraryItemWrapper as? LibraryItem)?.localLibraryItemId != null)
  val pick = if (online) candidates.firstOrNull() else candidates.firstOrNull(::downloaded)
  return pick?.let { it.libraryItemWrapper to it.episode }
          ?: localBooks.firstOrNull()?.let { it to null }
          ?: candidates.firstOrNull()?.let { it.libraryItemWrapper to it.episode }
}

/**
 * Continue: the replica's items, plus those the server reported for libraries the replica does not have; one entry per
 * item (episode), the latest progress first.
 */
internal fun mergeContinue(
        replica: List<ItemInProgress>,
        server: List<ItemInProgress>,
        replicaLibraryIds: Set<String>,
        serverListOf: String?,
        activeServerId: String?
): List<ItemInProgress> {
  // The server's list of another server (or account) is history of that one, not of the active server
  val fromServer = if (serverListOf == null || serverListOf != activeServerId) emptyList() else server.filter { (it.libraryItemWrapper as? LibraryItem)?.libraryId !in replicaLibraryIds }
  return (replica + fromServer).distinctBy { it.libraryItemWrapper.id to it.episode?.id }
          .sortedByDescending { it.progressLastUpdate }.take(AUTO_CONTINUE_LIMIT)
}

/** Sets the download mark of every item (episode) of [items] anew; [mark] null clears a mark whose copy is gone. */
internal fun markItemsInProgress(items: List<ItemInProgress>, mark: (LibraryItem, String?) -> String?) {
  items.forEach { inProgress ->
    val item = inProgress.libraryItemWrapper as? LibraryItem ?: return@forEach
    val episode = inProgress.episode
    if (episode != null) episode.localEpisodeId = mark(item, episode.id)
    else item.localLibraryItemId = mark(item, null)
  }
}

/** Only the latest of overlapping play requests starts: a slow earlier resolve must not replace a later choice. */
internal class LatestRequest {
  private val counter = AtomicLong()
  fun next(): Long = counter.incrementAndGet()
  fun isLatest(request: Long): Boolean = counter.get() == request
}

/**
 * When [MediaManager.refreshSnapshot] recomputes the snapshot, only rereads its progress, or keeps it. A change of the
 * catalog, the user or the downloaded copies, and a switch of the active server, recompute it. A progress change
 * recomputes at most every [everyMs] (Continue moves), its progress map is read at once; a playback tick only rereads
 * the progress, at most every [everyMs].
 */
internal class SnapshotGate(private val clock: () -> Long, private val everyMs: Long = 10_000) {
  enum class Action { RECOMPUTE, PROGRESS, NONE }
  data class Decision(val action: Action, val catalog: Long, val progress: Long, val ticks: Long, val copies: Long)

  private var catalog = 1L
  private var progress = 0L
  private var ticks = 0L
  private var builtCatalog = 0L
  private var builtProgress = 0L
  private var builtCopies = 0L
  private var builtServer: String? = null
  private var readProgress = 0L
  private var readTicks = 0L
  private var recomputedAt = 0L
  private var progressReadAt = 0L

  @Synchronized fun onData(scope: String) {
    when (scope) {
      "catalog", "user" -> catalog++
      "progress" -> progress++
      "playback" -> ticks++
    }
  }

  /** [copiesVersion]: the version of the downloaded copies, which the download marks follow. */
  @Synchronized fun decide(activeServerId: String?, copiesVersion: Long = 0): Decision {
    val now = clock()
    val action = when {
      builtCatalog != catalog || builtServer != activeServerId || builtCopies != copiesVersion -> Action.RECOMPUTE
      builtProgress != progress && now - recomputedAt >= everyMs -> Action.RECOMPUTE
      readProgress != progress -> Action.PROGRESS
      ticks != readTicks && now - progressReadAt >= everyMs -> Action.PROGRESS
      else -> Action.NONE
    }
    return Decision(action, catalog, progress, ticks, copiesVersion)
  }

  /** [decision] was carried out for [activeServerId]; changes that came meanwhile stay pending. */
  @Synchronized fun done(decision: Decision, activeServerId: String?) {
    if (decision.action == Action.NONE) return
    val now = clock()
    if (decision.action == Action.RECOMPUTE) {
      builtCatalog = decision.catalog
      builtProgress = decision.progress
      builtCopies = decision.copies
      builtServer = activeServerId
      recomputedAt = now
    }
    readProgress = decision.progress
    readTicks = decision.ticks
    progressReadAt = now
  }
}

/** [snapshot] with a newer progress map; Continue follows its order (the item switched to comes first). */
internal fun withProgress(snapshot: AutoSnapshot, progress: Map<String, MediaProgress>): AutoSnapshot {
  val continueItems = snapshot.continueItems.map { item ->
    val key = item.episode?.let { "${item.libraryItemWrapper.id}-${it.id}" } ?: item.libraryItemWrapper.id
    progress[key]?.let { item.copy(progressLastUpdate = it.lastUpdate) } ?: item
  }.sortedByDescending { it.progressLastUpdate }
  return snapshot.copy(progress = progress, continueItems = continueItems)
}

/** The current [AutoSnapshot], refreshed as [gate] decides; recomputes never overlap. */
internal class SnapshotCache(
        private val gate: SnapshotGate,
        private val activeServer: () -> String?,
        private val compute: suspend () -> AutoSnapshot?,
        private val progress: suspend () -> Map<String, MediaProgress>?,
        private val copiesVersion: () -> Long = { 0L },
        private val onError: (Exception) -> Unit = {}
) {
  @Volatile var current: AutoSnapshot? = null
    private set
  private val mutex = Mutex()

  suspend fun refresh(): AutoSnapshot? = mutex.withLock {
    val active = activeServer()
    val decision = gate.decide(active, copiesVersion())
    try {
      when (decision.action) {
        SnapshotGate.Action.RECOMPUTE -> current = compute()
        SnapshotGate.Action.PROGRESS -> progress()?.let { map -> current = current?.let { withProgress(it, map) } }
        SnapshotGate.Action.NONE -> {}
      }
      gate.done(decision, active)
    } catch (e: Exception) {
      onError(e)
      // Stale for the next call; a snapshot of another server is not kept
      current = current?.takeIf { it.serverConfigId == active }
    }
    current
  }
}

class MediaManager(private var apiHandler: ApiHandler, var ctx: Context, private val catalog: AutoCatalog = AutoCatalog.create(ctx)) {
  val tag = "MediaManager"

  // Items the server answers brought, by id: what a selected node starts without reading it again
  private var serverLibraryItems: MutableMap<String, LibraryItem> = ConcurrentHashMap()

  private var cachedLibraryAuthors : MutableMap<String, MutableMap<String, LibraryAuthorItem>> = ConcurrentHashMap()
  private var cachedLibraryAuthorItems : MutableMap<String, MutableMap<String, List<LibraryItem>>> = ConcurrentHashMap()
  private var cachedLibraryAuthorSeriesItems : MutableMap<String, MutableMap<String, List<LibraryItem>>> = ConcurrentHashMap()
  private var cachedLibrarySeries : MutableMap<String, List<LibrarySeriesItem>> = ConcurrentHashMap()
  private var cachedLibrarySeriesItem : MutableMap<String, MutableMap<String, List<LibraryItem>>> = ConcurrentHashMap()
  private var cachedLibraryCollections : MutableMap<String, MutableMap<String, LibraryCollection>> = ConcurrentHashMap()
  private var cachedLibraryRecentShelves : MutableMap<String, MutableList<LibraryShelfType>> = ConcurrentHashMap()
  private var cachedLibraryDiscovery : MutableMap<String, MutableList<LibraryItem>> = ConcurrentHashMap()
  private var cachedLibraryPodcasts : MutableMap<String, MutableMap<String, LibraryItem>> = ConcurrentHashMap()
  private var isLibraryPodcastsCached : MutableMap<String, Boolean> = ConcurrentHashMap()
  var allLibraryPersonalizationsDone : Boolean = false
  private var libraryPersonalizationsDone : Int = 0

  private var selectedPodcast:Podcast? = null
  private var selectedLibraryItemId:String? = null
  private var podcastEpisodeLibraryItemMap: MutableMap<String, LibraryItemWithEpisode> = ConcurrentHashMap()
  private var serverConfigIdUsed:String? = null
  private var serverConfigLastPing:Long = 0L
  // Read only for the next unfinished episode after a podcast ends; browse progress is media_progress (progressOf)
  var serverUserMediaProgress:MutableList<MediaProgress> = mutableListOf()
  var serverItemsInProgress = listOf<ItemInProgress>()
  // The server the in-progress list was read from
  @Volatile private var serverItemsInProgressOf: String? = null
  // The version of the downloaded copies its marks were computed against
  @Volatile private var serverItemsInProgressMarkedAt = -1L
  var serverLibraries = listOf<Library>()

  var userSettingsPlaybackRate:Float? = null
  var onPlaybackError: ((String) -> Unit)? = null

  // Android Auto browses on the media browser service main thread, so the server pings
  // and authorize call must not run there or they block it and trigger an ANR
  private val androidAutoIOScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val mainHandler = Handler(Looper.getMainLooper())

  // Android Auto re-requests the browse root every ~2s and multiple clients do so at once,
  // so overlapping loads are coalesced into one to avoid racing on the shared server state
  private var isLoadingAndroidAutoItems = false
  private val pendingAndroidAutoLoadCallbacks = mutableListOf<() -> Unit>()
  private val androidAutoLoadLock = Any()

  private val snapshotGate = SnapshotGate({ System.currentTimeMillis() })
  private val snapshots = SnapshotCache(snapshotGate, { catalog.activeServer() }, { catalog.snapshot() }, { catalog.progressMap() },
          { DbManager.localLibraryItemsVersion }) {
    Log.e(tag, "refreshSnapshot: replica read failed", it)
  }
  private val playRequests = LatestRequest()
  private val watchingDataChanges = AtomicBoolean(false)

  /** Each change of [SyncEngine.dataChanges], once the snapshot knows it is stale: a reload it starts reads fresh data. */
  @Volatile var onDataChange: ((DataChange) -> Unit)? = null

  /** The Android Auto root from the replica of the active server; see [refreshSnapshot]. */
  val snapshot: AutoSnapshot? get() = snapshots.current

  private val online = { ServerReachability.state.value == ServerState.ONLINE }

  /**
   * The snapshot, recomputed only when stale (first call, a catalog, progress or user change, another active server);
   * after playback ticks only its progress is read again, at most every 10 s. Auto asks for the root about every 2 s.
   */
  suspend fun refreshSnapshot(): AutoSnapshot? {
    if (watchingDataChanges.compareAndSet(false, true)) {
      androidAutoIOScope.launch {
        SyncEngine.dataChanges.collect {
          snapshotGate.onData(it.scope)
          onDataChange?.invoke(it)
        }
      }
    }
    return snapshots.refresh()
  }

  /**
   * The snapshot for a deep node: the current one of the same server at once (a refresh runs behind it), so a node does
   * not wait for a recompute; without one, or after a server switch, the fresh one.
   */
  suspend fun snapshotForNode(): AutoSnapshot? {
    val current = snapshot
    if (current == null || current.serverConfigId != catalog.activeServer()) return refreshSnapshot()
    androidAutoIOScope.launch { refreshSnapshot() }
    return current
  }

  /** Continue: the snapshot's items and the server's for libraries the replica lacks. */
  fun continueItems(snap: AutoSnapshot?): List<ItemInProgress> {
    val active = catalog.activeServer()
    if (serverItemsInProgressOf != null && serverItemsInProgressOf != active) {
      serverItemsInProgress = listOf()
      serverItemsInProgressOf = null
    }
    // A copy downloaded or deleted since the list was read changes its marks
    if (serverItemsInProgress.isNotEmpty() && serverItemsInProgressMarkedAt != DbManager.localLibraryItemsVersion) markInProgress(serverItemsInProgress)
    return mergeContinue(snap?.continueItems.orEmpty(), serverItemsInProgress, snap?.libraries.orEmpty().mapTo(HashSet()) { it.id },
            serverItemsInProgressOf, active)
  }

  /** The libraries of the snapshot, then the server's libraries the snapshot lacks (never reconciled). */
  private fun autoLibraries(snap: AutoSnapshot? = snapshot): List<Library> {
    val fromReplica = snap?.libraries.orEmpty()
    val ids = fromReplica.mapTo(HashSet()) { it.id }
    return fromReplica + serverLibraries.filter { it.id !in ids }
  }

  fun getIsLibrary(id:String) : Boolean = getLibrary(id) != null

  /**
   * Check if there is discovery shelf for [libraryId]: a library of the snapshot by its replica,
   * another one by the server's personalized shelves (populated when not yet).
   */
  fun getHasDiscovery(libraryId: String) : Boolean {
    snapshot?.takeIf { snap -> snap.libraries.any { it.id == libraryId } }?.let { return libraryId in it.discoveryLibraryIds }
    if (cachedLibraryDiscovery.containsKey(libraryId)) {
      if (cachedLibraryDiscovery[libraryId]!!.isNotEmpty()) {
        return true
      }
    } else {
      populatePersonalizedDataForLibrary(libraryId){}
    }
    return false
  }

  fun getLibrary(id:String) : Library? = autoLibraries().find { it.id == id }

  /**
   * Add [libraryItem] to [serverLibraryItems] if it is not already added
   */
  private fun addServerLibrary(libraryItem: LibraryItem) {
    serverLibraryItems.putIfAbsent(libraryItem.id, libraryItem)
  }

  /** The progress of an item (an episode) for its browse description: media_progress of the active server. */
  fun progressOf(libraryItemId: String, episodeId: String?): MediaProgress? =
          snapshot?.progress?.get(if (episodeId.isNullOrEmpty()) libraryItemId else "$libraryItemId-$episodeId")

  /** Download marks of server answers, by the rule of the replica's answers. */
  fun markDownloads(items: List<LibraryItem>): List<LibraryItem> {
    val sc = catalog.activeServer() ?: return items
    return catalog.markItems(sc, items)
  }

  /** Download marks of the server's in-progress books and episodes, by the replica's rule; the copies are read once. */
  private fun markInProgress(items: List<ItemInProgress>) {
    val version = DbManager.localLibraryItemsVersion
    val sc = catalog.activeServer()
    val copies by lazy { DeviceManager.dbManager.getLocalLibraryItems() }
    markItemsInProgress(items) { item, episodeId -> sc?.let { catalog.downloadMarkOf(copies, it, item, episodeId) } }
    serverItemsInProgressMarkedAt = version
  }

  private fun markShelves(shelves: List<LibraryShelfType>): List<LibraryShelfType> {
    val sc = catalog.activeServer() ?: return shelves
    return catalog.markShelves(sc, shelves)
  }

  /** A node's answer off the browser's thread: the replica, or the server path for a part never reconciled. */
  private fun <T> loadNode(cb: (T) -> Unit, empty: T, replica: suspend () -> AutoAnswer<T>?, server: ((T) -> Unit) -> Unit) {
    androidAutoIOScope.launch { cb(replicaOr(online, replica, server) ?: empty) }
  }

  fun loadLibraryPodcasts(libraryId: String, cb: (List<LibraryItem>?) -> Unit) =
          loadNode<List<LibraryItem>?>(cb, emptyList(), { catalog.podcasts(libraryId) }) { done -> serverLibraryPodcasts(libraryId) { done(it?.let(::markDownloads)) } }

  fun loadLibrarySeriesWithAudio(libraryId: String, cb: (List<LibrarySeriesItem>) -> Unit) =
          loadNode(cb, emptyList(), { catalog.seriesWithAudio(libraryId) }) { done -> serverLibrarySeriesWithAudio(libraryId, done) }

  /** The series of a letter group (paging). */
  fun loadLibrarySeriesWithAudio(libraryId: String, seriesFilter: String, cb: (List<LibrarySeriesItem>) -> Unit) =
          loadLibrarySeriesWithAudio(libraryId) { series -> cb(series.filter { it.title.uppercase().startsWith(seriesFilter) }) }

  fun loadLibrarySeriesItemsWithAudio(libraryId: String, seriesId: String, cb: (List<LibraryItem>) -> Unit) =
          loadNode(cb, emptyList(), { catalog.seriesBooks(libraryId, seriesId)?.let { it.copy(value = sortSeriesBooks(it.value)) } }) { done ->
            serverLibrarySeriesItemsWithAudio(libraryId, seriesId) { done(markDownloads(it)) }
          }

  fun loadAuthorsWithBooks(libraryId: String, cb: (List<LibraryAuthorItem>) -> Unit) =
          loadNode(cb, emptyList(), { catalog.authorsWithBooks(libraryId) }) { done -> serverAuthorsWithBooks(libraryId, done) }

  /** The authors of a letter group (paging). */
  fun loadAuthorsWithBooks(libraryId: String, authorFilter: String, cb: (List<LibraryAuthorItem>) -> Unit) =
          loadAuthorsWithBooks(libraryId) { authors -> cb(authors.filter { it.name.uppercase().startsWith(authorFilter) }) }

  fun loadAuthorBooksWithAudio(libraryId: String, authorId: String, cb: (List<LibraryItem>) -> Unit) =
          loadNode(cb, emptyList(), { catalog.authorBooks(libraryId, authorId) }) { done ->
            serverAuthorBooksWithAudio(libraryId, authorId) { done(markDownloads(it)) }
          }

  /** The author's books of one series: the series' books written by the author, as the server path filters them. */
  fun loadAuthorSeriesBooksWithAudio(libraryId: String, authorId: String, seriesId: String, cb: (List<LibraryItem>) -> Unit) =
          loadNode(cb, emptyList(), {
            val books = catalog.seriesBooks(libraryId, seriesId)
            val authors = catalog.authorsWithBooks(libraryId)
            if (books == null || authors == null) null else {
              val name = authors.value.find { it.id == authorId }?.name ?: ""
              AutoAnswer(sortSeriesBooks(books.value.filter { it.authorName.indexOf(name, ignoreCase = true) >= 0 }), books.reconciled && authors.reconciled)
            }
          }) { done -> serverAuthorSeriesBooksWithAudio(libraryId, authorId, seriesId) { done(markDownloads(it)) } }

  fun loadLibraryCollectionsWithAudio(libraryId: String, cb: (List<LibraryCollection>) -> Unit) =
          loadNode(cb, emptyList(), { catalog.collectionsWithAudio(libraryId) }) { done -> serverLibraryCollectionsWithAudio(libraryId, done) }

  fun loadLibraryCollectionBooksWithAudio(libraryId: String, collectionId: String, cb: (List<LibraryItem>) -> Unit) =
          loadNode(cb, emptyList(), { catalog.collectionBooks(libraryId, collectionId) }) { done ->
            serverLibraryCollectionBooksWithAudio(libraryId, collectionId) { done(markDownloads(it)) }
          }

  fun loadLibraryDiscoveryBooksWithAudio(libraryId: String, cb: (List<LibraryItem>) -> Unit) =
          loadNode(cb, emptyList(), { catalog.discovery(libraryId) }) { done ->
            serverLibraryDiscoveryBooksWithAudio(libraryId) { done(markDownloads(it)) }
          }

  fun getLibraryRecentShelfs(libraryId: String, cb: (List<LibraryShelfType>) -> Unit) =
          loadNode(cb, emptyList(), { catalog.recentShelves(libraryId) }) { done ->
            serverLibraryRecentShelfs(libraryId) { done(markShelves(it)) }
          }

  /** The Recent shelf of [type] for [libraryId], or null. */
  fun getLibraryRecentShelfByType(libraryId: String, type: String, cb: (LibraryShelfType?) -> Unit) =
          getLibraryRecentShelfs(libraryId) { shelves -> cb(shelves.firstOrNull { it.type == type.lowercase() }) }

  /**
   * The item behind a selected (or remembered) node: a device copy, an item the browse already brought, then the
   * replica, then the server while reachable. [cb] runs on the main thread.
   */
  fun resolveForPlay(mediaId: String, cb: (LibraryItemWrapper?, PodcastEpisode?) -> Unit) {
    val request = playRequests.next()
    androidAutoIOScope.launch {
      val (item, episode) = resolveNow(mediaId)
      postIfLatest(request) { cb(item, episode) }
    }
  }

  private fun postIfLatest(request: Long, block: () -> Unit) {
    mainHandler.post { if (playRequests.isLatest(request)) block() else Log.d(tag, "A later play request replaced request $request") }
  }

  private suspend fun resolveNow(mediaId: String): Pair<LibraryItemWrapper?, PodcastEpisode?> {
    if (mediaId.startsWith("local")) {
      DeviceManager.dbManager.getLocalLibraryItemWithEpisode(mediaId)?.let { return it.libraryItemWrapper to it.episode }
      return DeviceManager.dbManager.getLocalLibraryItem(mediaId) to null
    }
    podcastEpisodeLibraryItemMap[mediaId]?.let { return it.libraryItemWrapper to it.episode }
    serverLibraryItems[mediaId]?.let { return it to null }
    val fromReplica = try {
      catalog.episodeForPlay(mediaId)?.let { it.libraryItemWrapper to it.episode } ?: catalog.itemForPlay(mediaId)?.let { it to null }
    } catch (e: Exception) {
      Log.w(tag, "resolveForPlay: replica read failed: ${e.message}")
      null
    }
    if (fromReplica != null) return fromReplica
    if (!online()) return null to null
    return suspendCancellableCoroutine { cont -> apiHandler.getLibraryItem(mediaId) { cont.resume(it to null) } }
  }

  /** What a spoken "play <query>" starts; an empty query is Play without a choice. [cb] runs on the main thread. */
  fun findForVoice(query: String?, cb: (LibraryItemWrapper?, PodcastEpisode?) -> Unit) {
    if (query.isNullOrEmpty()) return firstPlayable(cb)
    val request = playRequests.next()
    androidAutoIOScope.launch {
      val found = autoLibraries(snapshot ?: refreshSnapshot()).flatMap { library ->
        val result = search(library.id, query) ?: return@flatMap emptyList()
        (result.book.orEmpty() + result.podcast.orEmpty()).map { it.libraryItem }.filter { it.checkHasTracks() }
      }
      val pick = voicePick(found, online())
      postIfLatest(request) { cb(pick, null) }
    }
  }

  /** Play without a choice. [cb] runs on the main thread. */
  fun firstPlayable(cb: (LibraryItemWrapper?, PodcastEpisode?) -> Unit) {
    val request = playRequests.next()
    androidAutoIOScope.launch {
      val snap = snapshot ?: refreshSnapshot()
      val replicaItems = snap?.continueItems.orEmpty()
      val serverItems = continueItems(snap).filter { it !in replicaItems }
      val first = firstPlayableOf(replicaItems, serverItems, DeviceManager.dbManager.getLocalLibraryItems("book"), online())
      postIfLatest(request) { cb(first?.first, first?.second) }
    }
  }

  private suspend fun search(libraryId: String, query: String): LibraryItemSearchResultType? =
          replicaOr(online, { catalog.search(libraryId, query) }) { done ->
            apiHandler.getSearchResults(libraryId, query) { result ->
              result?.let { markDownloads((it.book.orEmpty() + it.podcast.orEmpty()).map { r -> r.libraryItem }) }
              done(result ?: LibraryItemSearchResultType(null, null, null, null))
            }
          }

  /** Search of every library with audio: books, podcasts, series and authors, grouped by kind. */
  suspend fun searchAll(query: String): Map<String, List<MediaBrowserCompat.MediaItem>> {
    val found = mutableMapOf<String, MutableList<MediaBrowserCompat.MediaItem>>()
    for (library in autoLibraries(snapshot ?: refreshSnapshot())) {
      // Skip searching library if it doesn't have any audio files
      if (library.stats?.numAudioFiles == 0) continue
      val result = search(library.id, query) ?: continue
      prefetchAutoCovers((result.book.orEmpty() + result.podcast.orEmpty()).map { it.libraryItem })
      searchItems(library, result).forEach { (kind, items) -> found.getOrPut(kind) { mutableListOf() }.addAll(items) }
    }
    return found
  }

  fun getSavedPlaybackRate():Float {
    if (userSettingsPlaybackRate != null) {
      return userSettingsPlaybackRate ?: 1f
    }

    val sharedPrefs = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    if (sharedPrefs != null) {
      val userSettingsPref = sharedPrefs.getString("userSettings", null)
      if (userSettingsPref != null) {
        try {
          val userSettings = JSObject(userSettingsPref)
          if (userSettings.has("playbackRate")) {
            userSettingsPlaybackRate = userSettings.getDouble("playbackRate").toFloat()
            return userSettingsPlaybackRate ?: 1f
          }
        } catch(je:JSONException) {
          Log.e(tag, "Failed to parse userSettings JSON ${je.localizedMessage}")
        }
      }
    }
    return 1f
  }

  fun setSavedPlaybackRate(newRate: Float) {
    val sharedPrefs = ctx.getSharedPreferences("CapacitorStorage", Activity.MODE_PRIVATE)
    val sharedPrefEditor = sharedPrefs.edit()
    if (sharedPrefs != null) {
      val userSettingsPref = sharedPrefs.getString("userSettings", null)
      if (userSettingsPref != null) {
        try {
          val userSettings = JSObject(userSettingsPref)
          // toString().toDouble() to prevent float conversion issues (ex 1.2f becomes 1.2000000476837158d)
          userSettings.put("playbackRate", newRate.toString().toDouble())
          sharedPrefEditor.putString("userSettings", userSettings.toString())
          sharedPrefEditor.apply()
          userSettingsPlaybackRate = newRate
          Log.d(tag, "Saved userSettings JSON from Android Auto with playbackRate=$newRate")
        } catch(je:JSONException) {
          Log.e(tag, "Failed to save userSettings JSON ${je.localizedMessage}")
        }
      } else {
        // Not sure if this is the best place for this, but if a user has not changed any user settings in the app
        // the object will not exist yet, could be moved to a centralized place or created on first app load
        val userSettings = JSONObject()
        userSettings.put("playbackRate", newRate.toString().toDouble())
        sharedPrefEditor.putString("userSettings", userSettings.toString())
        userSettingsPlaybackRate = newRate
        Log.d(tag, "Created and saved userSettings JSON from Android Auto with playbackRate=$newRate")
      }
    }
  }

  fun checkResetServerItems():Boolean {
    // When opening android auto need to check if still connected to server
    //   and reset any server data already set
    val serverConnConfig = if (DeviceManager.isConnectedToServer) DeviceManager.serverConnectionConfig else DeviceManager.deviceData.getLastServerConnectionConfig()

    if (!DeviceManager.isConnectedToServer || !DeviceManager.checkConnectivity(ctx) || serverConnConfig == null || serverConnConfig.id != serverConfigIdUsed) {
      podcastEpisodeLibraryItemMap = ConcurrentHashMap()
      serverLibraries = listOf()
      serverLibraryItems = ConcurrentHashMap()
      cachedLibraryAuthors = ConcurrentHashMap()
      cachedLibraryAuthorItems = ConcurrentHashMap()
      cachedLibraryAuthorSeriesItems = ConcurrentHashMap()
      cachedLibrarySeries = ConcurrentHashMap()
      cachedLibrarySeriesItem = ConcurrentHashMap()
      cachedLibraryCollections = ConcurrentHashMap()
      cachedLibraryRecentShelves = ConcurrentHashMap()
      cachedLibraryDiscovery = ConcurrentHashMap()
      cachedLibraryPodcasts = ConcurrentHashMap()
      isLibraryPodcastsCached = ConcurrentHashMap()
      serverItemsInProgress = listOf()
      allLibraryPersonalizationsDone = false
      libraryPersonalizationsDone = 0
      return true
    }
    return false
  }

  private fun loadItemsInProgressForAllLibraries(cb: (List<ItemInProgress>) -> Unit) {
    if (serverItemsInProgress.isNotEmpty()) {
      cb(serverItemsInProgress)
    } else {
      val requestedFrom = DeviceManager.serverConnectionConfigId
      apiHandler.getAllItemsInProgress { itemsInProgress ->
        serverItemsInProgress = itemsInProgress.filter {
          val libraryItem = it.libraryItemWrapper as LibraryItem
          libraryItem.checkHasTracks()
        }
        markInProgress(serverItemsInProgress)
        serverItemsInProgressOf = requestedFrom.takeIf { it.isNotEmpty() }
        cb(serverItemsInProgress)
      }
    }
  }

  /**
   * Load personalized shelves from server for all libraries.
   * [cb] resolves when all libraries are processed
   */
  fun populatePersonalizedDataForAllLibraries(cb: () -> Unit) {
    val remaining = AtomicInteger(serverLibraries.size)

    serverLibraries.forEach { lib ->
      Log.d(tag, "Loading personalization for library ${lib.name}")
      populatePersonalizedDataForLibrary(lib.id) {
        Log.d(tag, "Loaded personalization for library ${lib.name}")
        if (remaining.decrementAndGet() == 0) {
          Log.d(tag, "Finished loading all library personalization data")
          allLibraryPersonalizationsDone = true
          cb()
        }
      }
    }
  }

  /**
   * Get personalized shelves from server for selected [libraryId].
   * Populates [cachedLibraryRecentShelves] and [cachedLibraryDiscovery].
   */
  private fun populatePersonalizedDataForLibrary(libraryId: String, cb: () -> Unit) {
    apiHandler.getLibraryPersonalized(libraryId) { shelves ->
      Log.d(tag, "populatePersonalizedDataForLibrary $libraryId")
      if (shelves === null) return@getLibraryPersonalized
      shelves.map { shelf ->
        Log.d(tag, "$shelf")
        if (shelf.type == "book") {
          if (shelf.id == "continue-listening") return@map
          else if (shelf.id == "listen-again") return@map
          else if (shelf.id == "recently-added") {
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = CopyOnWriteArrayList()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }
          }
          else if (shelf.id == "discover") {
            if (!cachedLibraryDiscovery.containsKey(libraryId)) {
              cachedLibraryDiscovery[libraryId] = CopyOnWriteArrayList()
            }
            (shelf as LibraryShelfBookEntity).entities?.map {
              cachedLibraryDiscovery[libraryId]!!.add(it)
            }
          }
          else if (shelf.id == "continue-reading") return@map
          else if (shelf.id == "continue-series") return@map
          shelf as LibraryShelfBookEntity
        } else if (shelf.type == "series") {
          if (shelf.id == "recent-series") {
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = CopyOnWriteArrayList()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }
          }
        } else if (shelf.type == "episode") {
          if (shelf.id == "continue-listening") return@map
          else if (shelf.id == "listen-again") return@map
          else if (shelf.id == "newest-episodes") {
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = CopyOnWriteArrayList()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }

            val podcastLibraryItemIds = mutableListOf<String>()
            (shelf as LibraryShelfEpisodeEntity).entities?.forEach { libraryItem ->
              if (!podcastLibraryItemIds.contains(libraryItem.id)) {
                podcastLibraryItemIds.add(libraryItem.id)
                loadPodcastItem(libraryItem.libraryId, libraryItem.id) {}
              }
            }
          }
        } else if (shelf.type == "podcast") {
          if (shelf.id == "recently-added"){
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = CopyOnWriteArrayList()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }
          }
          else if (shelf.id == "discover"){
            return@map
          }
        } else if (shelf.type =="authors") {
          if (shelf.id == "newest-authors") {
            if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
              cachedLibraryRecentShelves[libraryId] = CopyOnWriteArrayList()
            }
            if (cachedLibraryRecentShelves[libraryId]?.find { it.id == shelf.id } == null) {
              cachedLibraryRecentShelves[libraryId]!!.add(shelf)
            }
          }
        }

      }
      Log.d(tag, "populatePersonalizedDataForLibrary $libraryId DONE")
      cb()
    }
  }

  /**
   * Returns podcasts for selected library.
   * If data is not found from local cache it is loaded from server
   */
  private fun serverLibraryPodcasts(libraryId:String, cb: (List<LibraryItem>?) -> Unit) {
    // Without this there is possibility that only recent podcasts get loaded
    // Loading recent podcasts will also create cachedLibraryPodcasts entry for library
    if (!isLibraryPodcastsCached.containsKey(libraryId)) {
      isLibraryPodcastsCached[libraryId] = false
    }
    // Ensure that there is map for library
    if (!cachedLibraryPodcasts.containsKey(libraryId)) {
      cachedLibraryPodcasts[libraryId] = orderedMap()
    }
    if (isLibraryPodcastsCached.getOrElse(libraryId) {false}) {
      Log.d(tag, "loadLibraryPodcasts: Found from cache: $libraryId")
      cb(cachedLibraryPodcasts[libraryId]?.let { synchronized(it) { it.values.toList() } }?.sortedBy { libraryItem -> (libraryItem.media as Podcast).metadata.title })
    } else {
      apiHandler.getLibraryItems(libraryId) { libraryItems ->
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        libraryItemsWithAudio.forEach { libraryItem ->
          cachedLibraryPodcasts[libraryId]?.set(libraryItem.id, libraryItem)
          addServerLibrary(libraryItem)
        }
        isLibraryPodcastsCached[libraryId] = true
        Log.d(tag, "loadLibraryPodcasts: loaded from server: $libraryId")
        cb(libraryItemsWithAudio.sortedBy { libraryItem -> (libraryItem.media as Podcast).metadata.title })
      }
    }
  }

  /**
   *  Returns series with audio books from selected library.
   *  If data is not found from local cache then it will be fetched from server
   */
  private fun serverLibrarySeriesWithAudio(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    // Check "cache" first
    if (cachedLibrarySeries.containsKey(libraryId)) {
      Log.d(tag, "Series with audio found from cache | Library $libraryId ")
      cb(cachedLibrarySeries[libraryId] as List<LibrarySeriesItem>)
    } else {
      apiHandler.getLibrarySeries(libraryId) { seriesItems ->
        Log.d(tag, "Series with audio loaded from server | Library $libraryId")
        val seriesItemsWithAudio = seriesItems.filter { si -> si.audiobookCount > 0 }

        cachedLibrarySeries[libraryId] = seriesItemsWithAudio

        cb(seriesItemsWithAudio)
      }
    }
  }

  /**
   * Sorts books in series. Assumes that sequence is main.minor
   */
  private fun sortSeriesBooks(seriesBooks: List<LibraryItem>) : List<LibraryItem> {
    val sortingLogic = compareBy<LibraryItem> { it.seriesSequenceParts[0].length }
      .thenBy { it.seriesSequenceParts[0].ifEmpty { "" } }
      .thenBy { it.seriesSequenceParts.getOrElse(1) { "" }.length }
      .thenBy { it.seriesSequenceParts.getOrElse(1) { "" } }
    return seriesBooks.sortedWith(sortingLogic)
  }

  /**
   * Returns books for series from library.
   * If data is not found from local cache then it will be fetched from server
   */
  private fun serverLibrarySeriesItemsWithAudio(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    // Check "cache" first
    if (!cachedLibrarySeriesItem.containsKey(libraryId)) {
      cachedLibrarySeriesItem[libraryId] = orderedMap()
    }
    if (cachedLibrarySeriesItem[libraryId]!!.containsKey(seriesId)) {
      Log.d(tag, "Items for series $seriesId found from cache | Library $libraryId")
      cachedLibrarySeriesItem[libraryId]!![seriesId]?.let { cb(it) }
    } else {
      apiHandler.getLibrarySeriesItems(libraryId, seriesId) { libraryItems ->
        Log.d(tag, "Items for series $seriesId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        val sortedLibraryItemsWithAudio = sortSeriesBooks(libraryItemsWithAudio)
        cachedLibrarySeriesItem[libraryId]!![seriesId] = sortedLibraryItemsWithAudio

        sortedLibraryItemsWithAudio.forEach { libraryItem ->
          addServerLibrary(libraryItem)
        }
        cb(sortedLibraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns authors with books from library.
   * If data is not found from local cache then it will be fetched from server
   */
  private fun serverAuthorsWithBooks(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    // Check "cache" first
    if (cachedLibraryAuthors.containsKey(libraryId)) {
      Log.d(tag, "Authors with books found from cache | Library $libraryId ")
      cb(cachedLibraryAuthors[libraryId]!!.let { synchronized(it) { it.values.toList() } })
    } else {
      // Fetch data from server and add it to local "cache"
      apiHandler.getLibraryAuthors(libraryId) { authorItems ->
        Log.d(tag, "Authors with books loaded from server | Library $libraryId ")
        // TO-DO: This check won't ensure that there is audiobooks. Current API won't offer ability to do so
        var authorItemsWithBooks = authorItems.filter { li -> li.bookCount != null && li.bookCount!! > 0 }
        authorItemsWithBooks = authorItemsWithBooks.sortedBy { it.name }
        // Ensure that there is map for library
        cachedLibraryAuthors[libraryId] = orderedMap()
        // Cache authors
        authorItemsWithBooks.forEach {
          if (!cachedLibraryAuthors[libraryId]!!.containsKey(it.id)) {
            cachedLibraryAuthors[libraryId]!![it.id] = it
          }
        }
        cb(authorItemsWithBooks)
      }
    }
  }

  /**
   * Returns audiobooks for author from library
   * If data is not found from local cache then it will be fetched from server
   */
  private fun serverAuthorBooksWithAudio(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    // Ensure that there is map for library
    if (!cachedLibraryAuthorItems.containsKey(libraryId)) {
        cachedLibraryAuthorItems[libraryId] = orderedMap()
    }
    // Check "cache" first
    if (cachedLibraryAuthorItems[libraryId]!!.containsKey(authorId)) {
      Log.d(tag, "Items for author $authorId found from cache | Library $libraryId")
      cachedLibraryAuthorItems[libraryId]!![authorId]?.let { cb(it) }
    } else {
      apiHandler.getLibraryItemsFromAuthor(libraryId, authorId) { libraryItems ->
        Log.d(tag, "Items for author $authorId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }

        cachedLibraryAuthorItems[libraryId]!![authorId]  = libraryItemsWithAudio

        libraryItemsWithAudio.forEach { libraryItem ->
          addServerLibrary(libraryItem)
        }

        cb(libraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns audiobooks for author from specified series within library
   * If data is not found from local cache then it will be fetched from server
   */
  private fun serverAuthorSeriesBooksWithAudio(libraryId:String, authorId:String, seriesId: String, cb: (List<LibraryItem>) -> Unit) {
    val authorSeriesKey = "$authorId|$seriesId"
    // Ensure that there is map for library
    if (!cachedLibraryAuthorSeriesItems.containsKey(libraryId)) {
      cachedLibraryAuthorSeriesItems[libraryId] = orderedMap()
    }
    // Check "cache" first
    if (cachedLibraryAuthorSeriesItems[libraryId]!!.containsKey(authorSeriesKey)) {
      Log.d(tag, "Items for series $seriesId with author $authorId found from cache | Library $libraryId")
      cachedLibraryAuthorSeriesItems[libraryId]!![authorSeriesKey]?.let { cb(it) }
    } else {
      apiHandler.getLibrarySeriesItems(libraryId, seriesId) { libraryItems ->
        Log.d(tag, "Items for series $seriesId with author $authorId loaded from server | Library $libraryId")
        val libraryItemsWithAudio = libraryItems.filter { li -> li.checkHasTracks() }
        if (!cachedLibraryAuthors[libraryId]!!.containsKey(authorId)) {
          Log.d(tag, "Author data is missing")
        }
        val authorName = cachedLibraryAuthors[libraryId]!![authorId]?.name ?: ""
        Log.d(tag, "Using author name: $authorName")
        val libraryItemsFromAuthorWithAudio = libraryItemsWithAudio.filter { li -> li.authorName.indexOf(authorName, ignoreCase = true) >= 0 }

        val sortedLibraryItemsWithAudio = sortSeriesBooks(libraryItemsFromAuthorWithAudio)
        cachedLibraryAuthorSeriesItems[libraryId]!![authorId] = sortedLibraryItemsWithAudio

        sortedLibraryItemsWithAudio.forEach { libraryItem ->
          addServerLibrary(libraryItem)
        }

        cb(sortedLibraryItemsWithAudio)
      }
    }
  }

  /**
   * Returns collections with audiobooks from library
   * If data is not found from local cache then it will be fetched from server
   */
  private fun serverLibraryCollectionsWithAudio(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    if (cachedLibraryCollections.containsKey(libraryId)) {
      Log.d(tag, "Collections with books found from cache | Library $libraryId ")
      cb(cachedLibraryCollections[libraryId]!!.let { synchronized(it) { it.values.toList() } })
    } else {
      apiHandler.getLibraryCollections(libraryId) { libraryCollections ->
        Log.d(tag, "Collections with books loaded from server | Library $libraryId ")
        val libraryCollectionsWithAudio = libraryCollections.filter { lc -> lc.audiobookCount > 0 }

        // Cache collections
        cachedLibraryCollections[libraryId] = orderedMap()
        libraryCollectionsWithAudio.forEach {
          if (!cachedLibraryCollections[libraryId]!!.containsKey(it.id)) {
            cachedLibraryCollections[libraryId]!![it.id] = it
          }
        }
        cb(libraryCollectionsWithAudio)
      }
    }
  }

  /**
   * Returns audiobooks for collection from library
   * If data is not found from local cache then it will be fetched from server
   */
  private fun serverLibraryCollectionBooksWithAudio(libraryId: String, collectionId: String, cb: (List<LibraryItem>) -> Unit) {
    // The collections are read first (a node asked right after a restart): reading the cache before they arrive threw
    if (!cachedLibraryCollections.containsKey(libraryId)) {
      serverLibraryCollectionsWithAudio(libraryId) { serverLibraryCollectionBooksWithAudio(libraryId, collectionId, cb) }
      return
    }
    Log.d(tag, "Trying to find collection $collectionId items from from cache | Library $libraryId ")
    val libraryCollectionBookswithAudio = cachedLibraryCollections[libraryId]?.get(collectionId)?.books.orEmpty()
    libraryCollectionBookswithAudio.forEach { libraryItem -> addServerLibrary(libraryItem) }
    cb(libraryCollectionBookswithAudio)
  }

  /**
   * Returns audiobooks from discovery shelf for [libraryId]
   * If data is not found from local cache then it will be fetched from server
   */
  private fun serverLibraryDiscoveryBooksWithAudio(libraryId: String, cb: (List<LibraryItem>) -> Unit) {
    if (!cachedLibraryDiscovery.containsKey(libraryId)) {
      cb(listOf())
      return
    }
    val libraryItemsWithAudio = cachedLibraryDiscovery[libraryId]?.filter { li -> li.checkHasTracks() }
    libraryItemsWithAudio?.forEach { libraryItem -> addServerLibrary(libraryItem) }
    cb(libraryItemsWithAudio as List<LibraryItem>)
  }

  /**
   * Returns recent shelves for [libraryId]
   * If data is not shelves are found returns empty list
   */
  private fun serverLibraryRecentShelfs(libraryId: String, cb: (List<LibraryShelfType>) -> Unit) {
    if (!cachedLibraryRecentShelves.containsKey(libraryId)) {
      Log.d(tag, "getLibraryRecentShelfs: No shelves $libraryId")
      cb(listOf())
      return
    }
    cb(cachedLibraryRecentShelves[libraryId] as List<LibraryShelfType>)
  }

  /**
   * Loads podcasts for newest episodes shelf
   */
  private fun loadPodcastItem(libraryId: String, libraryItemId: String, cb: (LibraryItem?) -> Unit) {
    // Ensure that there is map for library
    if (!cachedLibraryPodcasts.containsKey(libraryId)) {
      cachedLibraryPodcasts[libraryId] = orderedMap()
    }
    if (cachedLibraryPodcasts[libraryId]!!.containsKey(libraryItemId)) {
      Log.d(tag, "loadPodcastItem: Podcast found from cache | Library $libraryItemId ")
      cb(cachedLibraryPodcasts[libraryId]?.get(libraryItemId))
    } else {
      Log.d(tag, "loadPodcastItem: Calling getLibraryItem $libraryItemId")
      apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
        if (libraryItem !== null) {
          Log.d(tag, "loadPodcastItem: Got library item ${libraryItem.id} ${libraryItem.media.metadata.title}")
          val podcast = libraryItem.media as Podcast
          podcast.episodes?.forEach { podcastEpisode ->
            podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItem, podcastEpisode)
          }
          cachedLibraryPodcasts[libraryId]?.set(libraryItemId, libraryItem)
          cb(libraryItem)
        }
      }
    }
  }

  fun loadPodcastEpisodeMediaBrowserItems(libraryItemId:String, ctx:Context, cb: (MutableList<MediaBrowserCompat.MediaItem>) -> Unit) {
    androidAutoIOScope.launch {
      val libraryItemWrapper: LibraryItemWrapper? = if (libraryItemId.startsWith("local")) {
        DeviceManager.dbManager.getLocalLibraryItem(libraryItemId)
      } else {
        replicaOr(online, { catalog.podcastWithEpisodes(libraryItemId) }) { done ->
          Log.d(tag, "loadLibraryItem: $libraryItemId")
          apiHandler.getLibraryItem(libraryItemId) { libraryItem ->
            Log.d(tag, "loadLibraryItem: Got library item $libraryItem")
            val sc = catalog.activeServer()
            val episodes = (libraryItem?.media as? Podcast)?.episodes
            if (sc != null && episodes != null) catalog.markEpisodes(sc, libraryItem, episodes)
            done(libraryItem)
          }
        }
      }
      Log.d(tag, "Loaded Podcast library item $libraryItemWrapper")

      if (libraryItemWrapper is LocalLibraryItem) { // Local podcast episodes
        if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
          cb(mutableListOf())
        } else {
          val podcast = libraryItemWrapper.media as Podcast
          selectedLibraryItemId = libraryItemWrapper.id
          selectedPodcast = podcast

          val children = podcast.episodes?.map { podcastEpisode ->
            Log.d(tag, "Local Podcast Episode ${podcastEpisode.title} | ${podcastEpisode.id}")

            val progress = DeviceManager.dbManager.getLocalMediaProgress("${libraryItemWrapper.id}-${podcastEpisode.id}")
            val description = podcastEpisode.getMediaDescription(libraryItemWrapper, progress, ctx)

            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
          }
          children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
        }
      } else if (libraryItemWrapper is LibraryItem) { // Server podcast episodes
        prefetchAutoCovers(listOf(libraryItemWrapper))
        if (libraryItemWrapper.mediaType != "podcast" || libraryItemWrapper.media.getAudioTracks().isEmpty()) {
          cb(mutableListOf())
        } else {
          val podcast = libraryItemWrapper.media as Podcast
          podcast.episodes?.forEach { podcastEpisode ->
            podcastEpisodeLibraryItemMap[podcastEpisode.id] = LibraryItemWithEpisode(libraryItemWrapper, podcastEpisode)
          }
          selectedLibraryItemId = libraryItemWrapper.id
          selectedPodcast = podcast
          val episodes = podcast.episodes?.sortedByDescending { it.publishedAt }
          val children = episodes?.map { podcastEpisode ->
            val progress = progressOf(libraryItemWrapper.id, podcastEpisode.id)
            val description = podcastEpisode.getMediaDescription(libraryItemWrapper, progress, ctx)
            MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
          }
          children?.let { cb(children as MutableList) } ?: cb(mutableListOf())
        }
      } else {
        cb(mutableListOf())
      }
    }
  }

  /**
   * Loads libraries for selected server with stats
   */
  private fun loadLibraries(cb: (List<Library>) -> Unit) {
    if (serverLibraries.isNotEmpty()) {
      cb(serverLibraries)
    } else {
      apiHandler.getLibraries { loadedLibraries ->
        serverLibraries = loadedLibraries
        cb(serverLibraries)
      }
    }
  }

  private suspend fun checkServerConnection(config:ServerConnectionConfig) : Boolean {
    var successfulPing = false
    suspendCoroutine { cont ->
      apiHandler.pingServer(config) {
        Log.d(tag, "checkServerConnection: Checked server conn for ${config.address} result = $it")
        successfulPing = it
        cont.resume(it)
      }
    }
    return successfulPing
  }

  private suspend fun authorize(config:ServerConnectionConfig) : MutableList<MediaProgress> {
    var mediaProgress:MutableList<MediaProgress> = mutableListOf()
    suspendCoroutine { cont ->
      apiHandler.authorize(config) {
        Log.d(tag, "authorize: Authorized server config ${config.address} result = $it")
        if (!it.isNullOrEmpty()) {
          mediaProgress = it
        }
        cont.resume(mediaProgress)
      }
    }
    return mediaProgress
  }

  // Runs on [androidAutoIOScope] rather than blocking the caller: this is reached from
  // onLoadChildren on the media browser service main thread, and the pings/authorize below
  // are network calls, so runBlocking here caused ANRs while browsing in Android Auto
  private fun checkSetValidServerConnectionConfig(cb: (Boolean) -> Unit) = androidAutoIOScope.launch {
    Log.d(tag, "checkSetValidServerConnectionConfig | serverConfigIdUsed=$serverConfigIdUsed | lastServerConnectionConfigId=${DeviceManager.deviceData.lastServerConnectionConfigId}")

    coroutineScope {
      if (!DeviceManager.checkConnectivity(ctx)) {
        serverUserMediaProgress = mutableListOf()
        Log.d(tag, "checkSetValidServerConnectionConfig: No connectivity")
        cb(false)
      } else if (DeviceManager.deviceData.lastServerConnectionConfigId.isNullOrBlank()) { // If in offline mode last server connection config is unset
        serverUserMediaProgress = mutableListOf()
        Log.d(tag, "checkSetValidServerConnectionConfig: No last server connection config")
        cb(false)
      } else {
        var hasValidConn = false
        var lookupMediaProgress = true

        if (!serverConfigIdUsed.isNullOrEmpty() && serverConfigLastPing > 0L && System.currentTimeMillis() - serverConfigLastPing < 5000) {
            Log.d(tag, "checkSetValidServerConnectionConfig last ping less than a 5 seconds ago")
          hasValidConn = true
          lookupMediaProgress = false
        } else {
          serverUserMediaProgress = mutableListOf()
        }

        if (!hasValidConn) {
          // First check if the current selected config is pingable
          DeviceManager.serverConnectionConfig?.let {
            hasValidConn = checkServerConnection(it)
            Log.d(
              tag,
              "checkSetValidServerConnectionConfig: Current config ${DeviceManager.serverAddress} is pingable? $hasValidConn"
            )
          }
        }

        if (!hasValidConn) {
          // Loop through available configs and check if can connect
          for (config: ServerConnectionConfig in DeviceManager.deviceData.serverConnectionConfigs) {
            val result = checkServerConnection(config)

            if (result) {
              hasValidConn = true
              DeviceManager.serverConnectionConfig = config
              ActiveServer.remember(config.id)
              Log.d(tag, "checkSetValidServerConnectionConfig: Set server connection config ${DeviceManager.serverConnectionConfigId}")
              break
            }
          }
        }

        if (hasValidConn) {
          serverConfigLastPing = System.currentTimeMillis()

          if (lookupMediaProgress) {
            Log.d(tag, "Has valid conn now get user media progress")
            DeviceManager.serverConnectionConfig?.let {
              serverUserMediaProgress = authorize(it)
            }
          }
        }

        cb(hasValidConn)
      }
    }

  }

  fun loadServerUserMediaProgress(cb: () -> Unit) {
    Log.d(tag, "Loading server media progress")
    if (DeviceManager.serverConnectionConfig == null) {
      return cb()
    }

    DeviceManager.serverConnectionConfig?.let { config ->
      apiHandler.authorize(config) {
        Log.d(tag, "loadServerUserMediaProgress: Authorized server config ${config.address} result = $it")
        if (!it.isNullOrEmpty()) {
          serverUserMediaProgress = it
        }
        cb()
      }
    }
  }

  fun initializeInProgressItems(cb: () -> Unit) {
    Log.d(tag, "Initializing inprogress items")

    loadItemsInProgressForAllLibraries { itemsInProgress ->
      itemsInProgress.forEach {
        val libraryItem = it.libraryItemWrapper as LibraryItem
        addServerLibrary(libraryItem)

        if (it.episode != null) {
          podcastEpisodeLibraryItemMap[it.episode.id] = LibraryItemWithEpisode(it.libraryItemWrapper, it.episode)
        }
      }
      Log.d(tag, "Initializing inprogress items done")
      cb()
    }
  }

  fun loadAndroidAutoItems(cb: () -> Unit) {
    Log.d(tag, "Load android auto items")

    // Coalesce overlapping calls into a single load, every caller still gets its callback
    synchronized(androidAutoLoadLock) {
      pendingAndroidAutoLoadCallbacks.add(cb)
      if (isLoadingAndroidAutoItems) {
        Log.d(tag, "loadAndroidAutoItems: Load already in progress, queued callback")
        return
      }
      isLoadingAndroidAutoItems = true
    }

    val onLoadFinished = {
      val callbacks: List<() -> Unit>
      synchronized(androidAutoLoadLock) {
        isLoadingAndroidAutoItems = false
        callbacks = pendingAndroidAutoLoadCallbacks.toList()
        pendingAndroidAutoLoadCallbacks.clear()
      }
      callbacks.forEach { it() }
    }

    // Check if any valid server connection if not use locally downloaded books
    checkSetValidServerConnectionConfig { isConnected ->
      if (isConnected) {
        serverConfigIdUsed = DeviceManager.serverConnectionConfigId
        Log.d(tag, "loadAndroidAutoItems: Connected to server config id=$serverConfigIdUsed")

        loadLibraries { libraries ->
          if (libraries.isEmpty()) {
            Log.w(tag, "No libraries returned from server request")
            onLoadFinished()
          } else {
            onLoadFinished() // Fully loaded
          }
        }
      } else { // Not connected to server
        Log.d(tag, "loadAndroidAutoItems: Not connected to server")
        onLoadFinished()
      }
    }
  }

  /** Browse items of one library's search result, grouped by kind (book, podcast, series, authors). */
  fun searchItems(library: Library, searchResult: LibraryItemSearchResultType): Map<String, List<MediaBrowserCompat.MediaItem>> {
    val foundItems: MutableMap<String, List<MediaBrowserCompat.MediaItem>> = mutableMapOf()

    // Books
    if (searchResult.book !== null && searchResult.book!!.isNotEmpty()) {
      Log.d(tag, "searchLocalCache: found ${searchResult.book!!.size} books")
      val children = searchResult.book!!.filter { it.libraryItem.checkHasTracks() }.map { bookResult ->
        val libraryItem = bookResult.libraryItem
        addServerLibrary(libraryItem)
        val progress = progressOf(libraryItem.id, null)
        val description = libraryItem.getMediaDescription(progress, ctx, null, null, "Books (${library.name})")
        MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
      }
      foundItems["book"] = children
    }
    if (searchResult.podcast !== null && searchResult.podcast!!.isNotEmpty()) {
      Log.d(tag, "onSearch: found ${searchResult.podcast!!.size} podcasts")
      val children = searchResult.podcast!!.filter { it.libraryItem.checkHasTracks() }.map { podcastResult ->
        val libraryItem = podcastResult.libraryItem
        addServerLibrary(libraryItem)
        val description = libraryItem.getMediaDescription(null, ctx, null, null, "Podcasts (${library.name})")
        MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
      }
      foundItems["podcast"] = children
    }
    if (searchResult.series !== null && searchResult.series!!.isNotEmpty()) {
      Log.d(tag, "onSearch: found ${searchResult.series!!.size} series")
      val children = searchResult.series!!.map { seriesResult ->
        val seriesItem = seriesResult.series
        seriesItem.books = seriesResult.books.orEmpty().toMutableList()
        val description = seriesItem.getMediaDescription(null, ctx, "Series (${library.name})")
        MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
      }
      foundItems["series"] = children
    }
    if (searchResult.authors !== null && searchResult.authors!!.isNotEmpty()) {
      Log.d(tag, "onSearch: found ${searchResult.authors!!.size} authors")
      val children = searchResult.authors!!.map { authorItem ->
        val description = authorItem.getMediaDescription(null, ctx, "Authors (${library.name})")
        MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
      }
      foundItems["authors"] = children
    }
    return foundItems
  }

  private fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = Array(lhsLength) { it }
    var newCost = Array(lhsLength) { 0 }

    for (i in 1..rhsLength-1) {
      newCost[0] = i

      for (j in 1..lhsLength-1) {
        val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

        val costReplace = cost[j - 1] + match
        val costInsert = cost[j] + 1
        val costDelete = newCost[j - 1] + 1

        newCost[j] = Math.min(Math.min(costInsert, costDelete), costReplace)
      }

      val swap = cost
      cost = newCost
      newCost = swap
    }

    return cost[lhsLength - 1]
  }
}
