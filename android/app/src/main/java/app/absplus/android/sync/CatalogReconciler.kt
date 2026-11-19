package app.absplus.android.sync

import androidx.room.withTransaction
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.replica.DetailLevel
import app.absplus.android.db.replica.EpisodeEntity
import app.absplus.android.db.replica.FilterCodec
import app.absplus.android.db.replica.LibraryEntity
import app.absplus.android.db.replica.LibraryItemEntity
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.db.replica.SeriesEntity
import app.absplus.android.db.replica.SeriesItemEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

const val ITEMS_PAGE_SIZE = 500
/** Small pages: a series answer carries every book of each series. */
const val SERIES_PAGE_SIZE = 25
const val AUTHORS_PAGE_SIZE = 100
/** The server builds the whole collection list with every book expanded and cuts the page from it. */
const val COLLECTIONS_PAGE_SIZE = 20
/** As collections: the server expands every item of every playlist of the user and cuts the page from it. */
const val PLAYLISTS_PAGE_SIZE = 20

/** Rows per transaction when search keys of an older version are recomputed. */
const val SEARCH_KEY_BACKFILL_CHUNK = 500

/**
 * Recomputes the search keys of a library's items and episodes written before [SEARCH_KEY_VERSION] (rows of 4.x keep
 * "" until they change). Only the key changes, nothing visible: no notice.
 */
internal suspend fun backfillSearchKeys(db: ReplicaDatabase, sc: String, libraryId: String): Int {
  val prefix = SEARCH_KEY_VERSION + SEARCH_KEY_SEPARATOR
  var count = 0
  // In pages by id: after an update every key is stale, and the whole library's JSON must not be in memory at once
  var after = ""
  while (true) {
    val page = db.items().staleSearchKeys(sc, libraryId, prefix, after, SEARCH_KEY_BACKFILL_CHUNK)
    if (page.isEmpty()) break
    db.withTransaction {
      page.forEach { db.items().replaceStaleSearchKey(sc, it.id, it.searchKey, CatalogJson.searchKey(CatalogJson.mapper.readTree(it.json))) }
    }
    count += page.size
    after = page.last().id
  }
  after = ""
  while (true) {
    val page = db.episodes().staleKeys(sc, libraryId, prefix, after, SEARCH_KEY_BACKFILL_CHUNK)
    if (page.isEmpty()) break
    db.withTransaction {
      page.forEach { db.episodes().replaceStaleSearchKey(sc, it.id, it.searchKey, it.title, CatalogJson.episodeSearchKey(it.title)) }
    }
    count += page.size
    after = page.last().id
  }
  return count
}

data class CatalogResult(val libraries: Int, val failedLibraries: List<String>, val stoppedBy: HttpOutcome?)

fun emptyLibraryState(sc: String, libraryId: String) = LibraryStateEntity(sc, libraryId, null, false, null, null, null, null, null)

/**
 * One pass over the catalog of a server into replica.db: libraries with their filterdata, series, items, authors,
 * collections and the user's playlists, page by page, a Room transaction per page. Rows missing on the server are deleted only after a walk in which the total did
 * not move; otherwise the walk is repeated once, and a second unstable walk keeps what it saw without deleting.
 * Series membership comes from the items' `seriesName`; names that cannot be read with certainty are asked from the
 * server per series ([LibraryStateEntity.pendingSeriesQueries], kept across interrupted passes).
 * A podcast whose episodes are not known for its current version is read expanded, one request per podcast; expanded
 * books are never requested. Downloaded copies (Paper) are never touched.
 */
class CatalogReconciler(
        private val db: ReplicaDatabase,
        private val http: SyncHttp,
        private val clock: () -> Long,
        private val notify: (topics: Collection<String>) -> Unit,
        private val onReplicaChanged: () -> Unit,
        private val log: (String) -> Unit
) {
  private val mapper = CatalogJson.mapper

  private class Stop(val outcome: HttpOutcome) : Exception()

  /** A library failed with an answer that does not stop the whole pass. */
  private class Skip(val reason: String) : Exception()

  suspend fun reconcile(config: ServerConnectionConfig, firstLibraryId: String?): CatalogResult {
    val sc = config.id
    val libraries = try {
      reconcileLibraryList(config)
    } catch (stop: Stop) {
      return CatalogResult(0, emptyList(), stop.outcome)
    } catch (skip: Skip) {
      log("Catalog ${config.id}: library list unusable: ${skip.reason}")
      return CatalogResult(0, emptyList(), null)
    }
    val ordered = libraries.sortedBy { if (it.id == firstLibraryId) -1 else it.position }
    val failed = mutableListOf<String>()
    var done = 0
    for (library in ordered) {
      currentCoroutineContext().ensureActive()
      try {
        reconcileLibrary(config, library)
        done++
      } catch (stop: Stop) {
        return CatalogResult(done, failed, stop.outcome)
      } catch (skip: Skip) {
        failed += library.id
        log("Catalog $sc library ${library.id} skipped: ${skip.reason}")
        updateState(sc, library.id) { it.copy(lastError = skip.reason) }
      }
    }
    return CatalogResult(done, failed, null)
  }

  private suspend fun get(config: ServerConnectionConfig, path: String): JsonNode {
    val outcome = http.send(config, "GET", path)
    // An answer that arrives after a server switch cancelled the pass is not written
    currentCoroutineContext().ensureActive()
    return when (outcome) {
      is HttpOutcome.Ok -> try {
        mapper.readTree(outcome.body)?.takeIf { it.isObject } ?: throw Skip("invalid answer to $path")
      } catch (e: Skip) {
        throw e
      } catch (e: Exception) {
        throw Skip("invalid answer to $path")
      }
      is HttpOutcome.HttpError -> throw Skip("HTTP ${outcome.code} for ${path.substringBefore('?')}")
      is HttpOutcome.Unreachable, HttpOutcome.Unauthorized -> throw Stop(outcome)
    }
  }

  private suspend fun reconcileLibraryList(config: ServerConnectionConfig): List<LibraryEntity> {
    val sc = config.id
    // Without the list nothing can be told missing: deleting on an odd answer would empty the replica
    val answer = get(config, "/api/libraries").path("libraries").takeIf { it.isArray } ?: throw Skip("no libraries in the answer")
    val existing = db.libraries().all(sc).associateBy { it.id }
    val fresh = answer.mapIndexed { position, library ->
      val old = existing[library.path("id").asText()]
      CatalogJson.libraryEntity(sc, position, library, null)
              .copy(filterDataJson = old?.filterDataJson, issues = old?.issues ?: 0, numUserPlaylists = old?.numUserPlaylists ?: 0)
    }.filter { it.id.isNotEmpty() }
    val removed = existing.keys - fresh.map { it.id }.toSet()
    val changed = fresh.filter { it != existing[it.id] }
    if (changed.isNotEmpty()) db.libraries().upsertAll(changed)
    removed.forEach { db.deleteLibraryCascade(sc, it) }
    updateState(sc, "") { it.copy(itemsReconciledAt = clock(), itemsComplete = true, lastError = null) }
    if (changed.isNotEmpty() || removed.isNotEmpty()) {
      onReplicaChanged()
      notify(listOf("libraries"))
    }
    return fresh
  }

  private suspend fun reconcileLibrary(config: ServerConnectionConfig, library: LibraryEntity) {
    val sc = config.id
    val id = library.id
    val filterResponse = get(config, "/api/libraries/$id?include=filterdata")
    val withFilter = CatalogJson.libraryEntity(sc, library.position, mapper.readTree(library.json), filterResponse)
    if (withFilter != db.libraries().get(sc, id)) {
      db.libraries().upsertAll(listOf(withFilter))
      onReplicaChanged()
      notify(listOf("library:$id"))
    }
    val book = library.mediaType == "book"
    val filterSeries = filterResponse.path("filterdata").path("series")
    // Series first: the item walk reads seriesName against the names they bring
    if (book) reconcileSeries(config, id, filterSeries)
    val idsByName = if (book) {
      (db.series().forLibrary(sc, id).map { it.id to it.name } +
              filterSeries.map { it.path("id").asText() to it.path("name").asText() })
              .distinct().groupBy({ it.second }, { it.first })
    } else null
    reconcileItems(config, id, idsByName)
    if (book) runPointQueries(config, id)
    // Last: a failed authors, collections or playlists read skips the library only after its items are in
    val skipped = try {
      if (book) reconcileAuthors(config, id)
      if (book) reconcileCollections(config, id)
      reconcilePlaylists(config, id)
      null
    } catch (skip: Skip) {
      // Kept for the end: episodes and search keys do not depend on these lists and must not wait for them
      skip
    }
    // After the cheap passes: a stop among a podcast's requests must not hold back playlists
    if (library.mediaType == "podcast") reconcileEpisodes(config, id)
    // Here and not on the first search: on 10 000 rows it takes seconds on a phone
    val backfilled = backfillSearchKeys(db, sc, id)
    if (backfilled > 0) log("Catalog $sc library $id: $backfilled search keys recomputed")
    if (skipped != null) throw skipped
    updateState(sc, id) { it.copy(lastError = null) }
  }

  private suspend fun reconcileAuthors(config: ServerConnectionConfig, libraryId: String) {
    val sc = config.id
    val existing = db.authors().forLibrary(sc, libraryId).associateBy { it.id }
    val firstPass = db.libraryState().get(sc, libraryId)?.authorsReconciledAt == null
    var changed = false
    // Servers before 2.15.0 answer every author at once as `authors`
    val walk = walk(config, "authors of $libraryId", { page ->
      "/api/libraries/$libraryId/authors?limit=$AUTHORS_PAGE_SIZE&page=$page&sort=addedAt"
    }, unpagedKey = "authors") { results, _ ->
      val rows = results.map { CatalogJson.authorEntity(sc, it) }
              .map { if (it.libraryId.isEmpty()) it.copy(libraryId = libraryId) else it }
              .filter { it.id.isNotEmpty() && it != existing[it.id] }
      if (rows.isNotEmpty()) {
        db.authors().upsertAll(rows)
        changed = true
      }
    }
    // A list that kept moving is not a read one: the tab keeps reading the server until a full read
    if (walk.consistent) {
      val missing = db.authors().ids(sc, libraryId).filter { it !in walk.seen }
      if (missing.isNotEmpty()) {
        db.authors().deleteByIds(sc, missing)
        changed = true
      }
      updateState(sc, libraryId) { it.copy(authorsReconciledAt = clock()) }
    }
    if (changed) onReplicaChanged()
    // The first pass also tells a tab that read the server meanwhile to read the replica now
    if (changed || (firstPass && walk.consistent)) notify(listOf("authors:$libraryId"))
  }

  /** Collections in the server's order, each with its books in the collection's order; the expanded books are not kept. */
  private suspend fun reconcileCollections(config: ServerConnectionConfig, libraryId: String) {
    val sc = config.id
    val existing = db.collections().forLibrary(sc, libraryId).associateBy { it.id }
    val firstPass = db.libraryState().get(sc, libraryId)?.collectionsReconciledAt == null
    var changed = false
    var position = 0
    var positionsOf = 0
    val walk = walk(config, "collections of $libraryId", { page ->
      "/api/libraries/$libraryId/collections?limit=$COLLECTIONS_PAGE_SIZE&page=$page"
    }) { results, attempt ->
      // A walk started again numbers anew
      if (attempt != positionsOf) {
        position = 0
        positionsOf = attempt
      }
      for (node in results) {
        val (entity, bookIds) = CatalogJson.collectionEntity(sc, node, position++)
        val row = if (entity.libraryId.isEmpty()) entity.copy(libraryId = libraryId) else entity
        if (row.id.isEmpty()) continue
        if (row == existing[row.id] && bookIds == db.collections().itemIds(sc, row.id)) continue
        db.collections().replace(row, bookIds)
        changed = true
      }
    }
    if (walk.consistent) {
      val missing = db.collections().ids(sc, libraryId).filter { it !in walk.seen }
      if (missing.isNotEmpty()) {
        db.collections().deleteByIds(sc, missing)
        changed = true
      }
      updateState(sc, libraryId) { it.copy(collectionsReconciledAt = clock()) }
    }
    if (changed) onReplicaChanged()
    // The first pass also tells a tab that read the server meanwhile to read the replica now
    if (changed || (firstPass && walk.consistent)) notify(listOf("collections:$libraryId"))
  }

  /**
   * The user's playlists in the server's order, each with its items (episodes with their JSON) in the playlist's order.
   * The items are compared whole: the server keeps `lastUpdate` when only they change.
   */
  private suspend fun reconcilePlaylists(config: ServerConnectionConfig, libraryId: String) {
    val sc = config.id
    val existing = db.playlists().forLibrary(sc, libraryId).associateBy { it.id }
    val firstPass = db.libraryState().get(sc, libraryId)?.playlistsReconciledAt == null
    var changed = false
    var position = 0
    var positionsOf = 0
    val walk = walk(config, "playlists of $libraryId", { page ->
      "/api/libraries/$libraryId/playlists?limit=$PLAYLISTS_PAGE_SIZE&page=$page"
    }) { results, attempt ->
      // A walk started again numbers anew
      if (attempt != positionsOf) {
        position = 0
        positionsOf = attempt
      }
      for (node in results) {
        val (entity, items) = CatalogJson.playlistEntity(sc, node, position++)
        val row = if (entity.libraryId.isEmpty()) entity.copy(libraryId = libraryId) else entity
        if (row.id.isEmpty()) continue
        if (row == existing[row.id] && items == db.playlists().itemsOf(sc, row.id)) continue
        db.playlists().replace(row, items)
        changed = true
      }
    }
    if (walk.consistent) {
      val missing = db.playlists().ids(sc, libraryId).filter { it !in walk.seen }
      if (missing.isNotEmpty()) {
        db.playlists().deleteByIds(sc, missing)
        changed = true
      }
      updateState(sc, libraryId) { it.copy(playlistsReconciledAt = clock()) }
    }
    if (changed) onReplicaChanged()
    val topics = mutableListOf<String>()
    // The first pass also tells a tab that read the server meanwhile to read the replica now
    if (changed || (firstPass && walk.consistent)) topics += "playlists:$libraryId"
    // The library's numUserPlaylists is read from the replica: the Playlists tab shows or hides with it
    if (db.playlists().count(sc, libraryId) != existing.size || (firstPass && walk.consistent)) topics += "library:$libraryId"
    if (topics.isNotEmpty()) notify(topics)
  }

  private class Walk(val consistent: Boolean, val seen: Set<String>)

  /**
   * Pages until the total is reached; a moving total or a repeated id restarts once. An answer without `results` fails
   * the walk (it would read as an empty list and delete everything), unless it carries the whole list under
   * [unpagedKey] (a server that does not page this list).
   */
  private suspend fun walk(
          config: ServerConnectionConfig,
          what: String,
          path: (page: Int) -> String,
          unpagedKey: String? = null,
          onPage: suspend (results: JsonNode, attempt: Int) -> Unit
  ): Walk {
    var attempt = 0
    while (true) {
      val seen = HashSet<String>()
      var total = -1
      var page = 0
      var consistent = true
      while (true) {
        val answer = get(config, path(page))
        val results = answer.path("results")
        if (!results.isArray) {
          val all = unpagedKey?.let { answer.path(it) }?.takeIf { it.isArray } ?: throw Skip("no $what in the answer")
          onPage(all, attempt)
          val ids = all.map { it.path("id").asText() }
          return Walk(ids.toSet().size == ids.size, ids.toSet())
        }
        val pageTotal = answer.path("total").asInt(0)
        onPage(results, attempt)
        if (total < 0) total = pageTotal else if (pageTotal != total) {
          consistent = false
          break
        }
        if (results.any { !seen.add(it.path("id").asText()) }) {
          consistent = false
          break
        }
        if (results.size() == 0 || seen.size >= total) break
        page++
      }
      if (consistent || attempt == 1) return Walk(consistent, seen)
      attempt++
      log("Catalog ${config.id}: $what changed during the walk, walking again")
    }
  }

  private suspend fun reconcileItems(config: ServerConnectionConfig, libraryId: String, idsByName: Map<String, List<String>>?) {
    val sc = config.id
    val walk = walk(config, "items of $libraryId", { page ->
      "/api/libraries/$libraryId/items?limit=$ITEMS_PAGE_SIZE&page=$page&sort=addedAt&include=rssfeed&minified=1"
    }) { results, _ -> writeItemsPage(sc, libraryId, results, idsByName) }
    val now = clock()
    if (walk.consistent) {
      val missing = db.items().ids(sc, libraryId).filter { it !in walk.seen }
      db.withTransaction {
        if (missing.isNotEmpty()) db.items().deleteByIds(sc, missing)
        updateState(sc, libraryId) { it.copy(itemsReconciledAt = now, itemsComplete = true) }
      }
      if (missing.isNotEmpty()) {
        onReplicaChanged()
        notify(listOf("items:$libraryId"))
      }
    } else {
      // The replica is filled even so: a total that keeps moving must not leave the library "never reconciled"
      updateState(sc, libraryId) { it.copy(itemsReconciledAt = now, itemsComplete = false) }
    }
    notify(listOf("state:$libraryId"))
  }

  /**
   * Episodes of each podcast that has none for its current version, one `GET /api/items/{id}?expanded=1` at a time.
   * A podcast that failed with an HTTP error stays a candidate for the next pass but does not hold the library: a pass
   * that gave every podcast a try sets [LibraryStateEntity.episodesReconciledAt].
   */
  private suspend fun reconcileEpisodes(config: ServerConnectionConfig, libraryId: String) {
    val sc = config.id
    var requests = 0
    val firstPass = db.libraryState().get(sc, libraryId)?.episodesReconciledAt == null
    for (podcastId in db.items().podcastsWithoutEpisodes(sc, libraryId)) {
      currentCoroutineContext().ensureActive()
      // Not through get(): any HTTP error there skips the whole library
      val outcome = http.send(config, "GET", "/api/items/$podcastId?expanded=1")
      requests++
      currentCoroutineContext().ensureActive()
      when (outcome) {
        is HttpOutcome.Ok -> {
          val answer = try {
            mapper.readTree(outcome.body)?.takeIf { it.isObject && it.path("id").asText() == podcastId }
          } catch (e: Exception) {
            null
          }
          val episodes = answer?.let { CatalogJson.episodeEntities(sc, libraryId, it) }
          if (answer == null || episodes == null) {
            log("Catalog $sc podcast $podcastId: no episodes in the answer")
            continue
          }
          val topics = writeEpisodes(sc, libraryId, podcastId, answer, episodes)
          if (topics.isNotEmpty()) {
            onReplicaChanged()
            notify(topics)
          }
        }
        is HttpOutcome.HttpError -> if (outcome.code == 404) {
          // Gone between the item walk and this request
          db.items().deleteByIds(sc, listOf(podcastId))
          onReplicaChanged()
          notify(listOf("items:$libraryId", "episodes:$libraryId"))
        } else {
          log("Catalog $sc podcast $podcastId: HTTP ${outcome.code}, episodes read again next pass")
        }
        is HttpOutcome.Unreachable, HttpOutcome.Unauthorized -> throw Stop(outcome)
      }
    }
    log("Catalog $sc library $libraryId: $requests episode requests")
    updateState(sc, libraryId) { it.copy(episodesReconciledAt = clock()) }
    // A page that read "not reconciled" on an early podcast's event learns here that the library is ready; a pass that
    // asked nothing changes nothing and keeps every cache
    if (requests > 0 || firstPass) {
      onReplicaChanged()
      notify(listOf("state:$libraryId"))
    }
  }

  /** The topics to announce; none when the row is gone or newer than the answer (a socket event got there first). */
  private suspend fun writeEpisodes(sc: String, libraryId: String, podcastId: String, answer: JsonNode, episodes: List<EpisodeEntity>): List<String> =
          db.withTransaction {
            val old = db.items().get(sc, podcastId) ?: return@withTransaction emptyList()
            val updatedAt = answer.path("updatedAt").asLong()
            if (old.updatedAt > updatedAt) return@withTransaction emptyList()
            val expanded = CatalogJson.expandedForStorage(answer).toString()
            db.episodes().replaceForPodcast(sc, podcastId, episodes)
            if (old.updatedAt == updatedAt) {
              db.items().setExpanded(sc, podcastId, updatedAt, DetailLevel.EXPANDED, expanded)
              return@withTransaction listOf("episodes:$libraryId")
            }
            // Asked without include=rssfeed: the row keeps its feed
            val item = answer.deepCopy<JsonNode>() as ObjectNode
            if (!item.has("rssFeed") && old.rssFeedJson != null) item.set<JsonNode>("rssFeed", mapper.readTree(old.rssFeedJson))
            db.items().upsertAll(listOf(CatalogJson.itemEntity(sc, item).copy(detailLevel = DetailLevel.EXPANDED, expandedJson = expanded)))
            listOf("items:$libraryId", "episodes:$libraryId")
          }

  /** Changes whenever the known series of a library (ids and names) change. */
  private fun seriesVersion(idsByName: Map<String, List<String>>): String {
    val text = idsByName.entries.sortedBy { it.key }.joinToString("\n") { (name, ids) -> name + "\u0000" + ids.sorted().joinToString(",") }
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
  }

  /**
   * The stored hash of a row whose series name did not parse against the known series: unchanged as long as neither
   * the item nor the series lists change, so such a row is not rewritten (and its series not asked again) every pass.
   */
  private fun unparsedHash(seriesVersion: String, contentHash: String) = "unparsed:$seriesVersion:$contentHash"

  private suspend fun writeItemsPage(sc: String, libraryId: String, results: JsonNode, idsByName: Map<String, List<String>>?) {
    val version = idsByName?.let { seriesVersion(it) }
    if (results.size() == 0) return
    val known = db.items().hashes(sc, results.map { it.path("id").asText() }).associateBy { it.id }
    val changed: List<LibraryItemEntity> = results.mapNotNull { node ->
      val old = known[node.path("id").asText()]
      val updatedAt = node.path("updatedAt").asLong()
      val hash = CatalogJson.contentHash(node)
      val same = old != null && old.updatedAt == updatedAt &&
              (old.contentHash == hash || (version != null && old.contentHash == unparsedHash(version, hash)))
      if (same) null
      else CatalogJson.itemEntity(sc, node)
    }
    if (changed.isEmpty()) return
    db.withTransaction {
      val expanded = db.items().expandedOf(sc, changed.map { it.id }).associateBy { it.id }
      val changed = changed.map { CatalogJson.carryExpanded(it, expanded[it.id]) }
      if (idsByName == null) {
        db.items().upsertAll(changed)
        return@withTransaction
      }
      val state = db.libraryState().get(sc, libraryId) ?: emptyLibraryState(sc, libraryId)
      val pending = pendingOf(state).toMutableSet()
      val rows = changed.map { item ->
        when (val parse = CatalogJson.parseSeriesName(item.seriesName, idsByName)) {
          is SeriesNameParse.Parsed -> {
            db.series().replaceMembershipOfItem(sc, item.id,
                    parse.members.map { (seriesId, sequence) -> SeriesItemEntity(sc, seriesId, item.id, sequence) })
            item
          }
          is SeriesNameParse.Ambiguous -> {
            pending += parse.candidateSeriesIds
            // The book may have left a series that is not among the candidates
            pending += db.series().membershipOfItem(sc, item.id).map { it.seriesId }
            // A part no list knows (yet): read again once the item or the series lists change
            if (parse.unknownPart) item.copy(contentHash = unparsedHash(version!!, item.contentHash)) else item
          }
        }
      }
      db.items().upsertAll(rows)
      if (pending != pendingOf(state)) db.libraryState().upsert(state.copy(pendingSeriesQueries = pendingJson(pending)))
    }
    onReplicaChanged()
    notify(listOf("items:$libraryId"))
  }

  private fun pendingOf(state: LibraryStateEntity): Set<String> = try {
    mapper.readTree(state.pendingSeriesQueries).map { it.asText() }.toSet()
  } catch (e: Exception) {
    emptySet()
  }

  private fun pendingJson(ids: Set<String>): String = mapper.writeValueAsString(ids.sorted())

  /** Membership with sequences of each pending series, from the items the server lists for a series filter. */
  private suspend fun runPointQueries(config: ServerConnectionConfig, libraryId: String) {
    val sc = config.id
    val pending = pendingOf(db.libraryState().get(sc, libraryId) ?: return)
    if (pending.isEmpty()) return
    var count = 0
    for (seriesId in pending) {
      val members = ArrayList<SeriesItemEntity>()
      var membersOf = 0
      val walk = walk(config, "series $seriesId", { page ->
        "/api/libraries/$libraryId/items?filter=series.${FilterCodec.encode(seriesId)}&limit=$ITEMS_PAGE_SIZE&page=$page&minified=1"
      }) { results, attempt ->
        // A walk started again collects anew: pages of the abandoned one may be out of date
        if (attempt != membersOf) {
          members.clear()
          membersOf = attempt
        }
        results.forEach { item -> members += CatalogJson.seriesMembershipOf(sc, item).filter { it.seriesId == seriesId } }
      }
      // An unstable answer keeps the old membership and the query for the next pass
      if (!walk.consistent) continue
      db.withTransaction {
        db.series().replaceMembersOfSeries(sc, seriesId, members.distinctBy { it.libraryItemId })
        updateState(sc, libraryId) { it.copy(pendingSeriesQueries = pendingJson(pendingOf(it) - seriesId)) }
      }
      count++
    }
    log("Catalog $sc library $libraryId: $count series membership queries")
    onReplicaChanged()
    notify(listOf("series:$libraryId", "items:$libraryId"))
  }

  private suspend fun reconcileSeries(config: ServerConnectionConfig, libraryId: String, filterSeries: JsonNode) {
    val sc = config.id
    val existing = db.series().forLibrary(sc, libraryId).associateBy { it.id }
    var changed = false
    val walk = walk(config, "series of $libraryId", { page ->
      "/api/libraries/$libraryId/series?limit=$SERIES_PAGE_SIZE&page=$page&sort=addedAt"
    }) { results, _ ->
      val rows = results.map { CatalogJson.seriesEntity(sc, it) }
              .map { if (it.libraryId.isEmpty()) it.copy(libraryId = libraryId) else it }
              .filter { it != existing[it.id] }
      if (rows.isNotEmpty()) {
        db.series().upsertAll(rows)
        changed = true
      }
    }
    // Series hidden from the list (hideSingleBookSeries) are still in filterdata: the series filter needs their name
    val hidden = filterSeries.mapNotNull { s ->
      val id = s.path("id").asText()
      if (id.isEmpty() || id in walk.seen) return@mapNotNull null
      val name = s.path("name").asText()
      val old = existing[id]
      if (old != null && old.name == name) null
      else SeriesEntity(sc, id, libraryId, name, old?.nameIgnorePrefix?.takeIf { old.name == name } ?: name, 0, 0,
              mapper.writeValueAsString(mapper.createObjectNode().put("id", id).put("name", name).put("libraryId", libraryId)))
    }
    if (hidden.isNotEmpty()) {
      db.series().upsertAll(hidden)
      changed = true
    }
    if (walk.consistent) {
      val keep = walk.seen + filterSeries.map { it.path("id").asText() }
      val missing = db.series().ids(sc, libraryId).filter { it !in keep }
      if (missing.isNotEmpty()) {
        db.series().deleteByIds(sc, missing)
        changed = true
      }
    }
    updateState(sc, libraryId) { it.copy(seriesReconciledAt = clock()) }
    if (changed) {
      onReplicaChanged()
      notify(listOf("series:$libraryId"))
    }
  }

  private suspend fun updateState(sc: String, libraryId: String, change: (LibraryStateEntity) -> LibraryStateEntity) {
    db.withTransaction {
      val state = db.libraryState().get(sc, libraryId) ?: emptyLibraryState(sc, libraryId)
      db.libraryState().upsert(change(state))
    }
  }
}
