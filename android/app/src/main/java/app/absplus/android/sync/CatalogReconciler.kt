package app.absplus.android.sync

import androidx.room.withTransaction
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.replica.FilterCodec
import app.absplus.android.db.replica.LibraryEntity
import app.absplus.android.db.replica.LibraryItemEntity
import app.absplus.android.db.replica.LibraryStateEntity
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.db.replica.SeriesEntity
import app.absplus.android.db.replica.SeriesItemEntity
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

const val ITEMS_PAGE_SIZE = 500
const val SERIES_PAGE_SIZE = 100

data class CatalogResult(val libraries: Int, val failedLibraries: List<String>, val stoppedBy: HttpOutcome?)

fun emptyLibraryState(sc: String, libraryId: String) = LibraryStateEntity(sc, libraryId, null, false, null, null, null, null, null)

/**
 * One pass over the catalog of a server into replica.db: libraries with their filterdata, series and items, page by
 * page, a Room transaction per page. Rows missing on the server are deleted only after a walk in which the total did
 * not move; otherwise the walk is repeated once, and a second unstable walk keeps what it saw without deleting.
 * Series membership comes from the items' `seriesName`; names that cannot be read with certainty are asked from the
 * server per series ([LibraryStateEntity.pendingSeriesQueries], kept across interrupted passes).
 * Downloaded copies (Paper) are never touched; expanded items are never requested.
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
    val answer = get(config, "/api/libraries").path("libraries")
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
    updateState(sc, id) { it.copy(lastError = null) }
  }

  private class Walk(val consistent: Boolean, val seen: Set<String>)

  /** Pages until the total is reached; a moving total or a repeated id restarts once. */
  private suspend fun walk(config: ServerConnectionConfig, what: String, path: (page: Int) -> String, onPage: suspend (JsonNode) -> Unit): Walk {
    var attempt = 0
    while (true) {
      val seen = HashSet<String>()
      var total = -1
      var page = 0
      var consistent = true
      while (true) {
        val answer = get(config, path(page))
        val results = answer.path("results")
        val pageTotal = answer.path("total").asInt(0)
        onPage(results)
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
    }) { results -> writeItemsPage(sc, libraryId, results, idsByName) }
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

  private suspend fun writeItemsPage(sc: String, libraryId: String, results: JsonNode, idsByName: Map<String, List<String>>?) {
    if (results.size() == 0) return
    val known = db.items().hashes(sc, results.map { it.path("id").asText() }).associateBy { it.id }
    val changed: List<LibraryItemEntity> = results.mapNotNull { node ->
      val old = known[node.path("id").asText()]
      val updatedAt = node.path("updatedAt").asLong()
      if (old != null && old.updatedAt == updatedAt && old.contentHash == CatalogJson.contentHash(node)) null
      else CatalogJson.itemEntity(sc, node)
    }
    if (changed.isEmpty()) return
    db.withTransaction {
      db.items().upsertAll(changed)
      if (idsByName != null) {
        val state = db.libraryState().get(sc, libraryId) ?: emptyLibraryState(sc, libraryId)
        val pending = pendingOf(state).toMutableSet()
        for (item in changed) {
          when (val parse = CatalogJson.parseSeriesName(item.seriesName, idsByName)) {
            is SeriesNameParse.Parsed -> db.series().replaceMembershipOfItem(sc, item.id,
                    parse.members.map { (seriesId, sequence) -> SeriesItemEntity(sc, seriesId, item.id, sequence) })
            is SeriesNameParse.Ambiguous -> {
              pending += parse.candidateSeriesIds
              // The book may have left a series that is not among the candidates
              pending += db.series().membershipOfItem(sc, item.id).map { it.seriesId }
            }
          }
        }
        if (pending != pendingOf(state)) db.libraryState().upsert(state.copy(pendingSeriesQueries = pendingJson(pending)))
      }
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
      walk(config, "series $seriesId", { page ->
        "/api/libraries/$libraryId/items?filter=series.${FilterCodec.encode(seriesId)}&limit=$ITEMS_PAGE_SIZE&page=$page&minified=1"
      }) { results ->
        results.forEach { item -> members += CatalogJson.seriesMembershipOf(sc, item).filter { it.seriesId == seriesId } }
      }
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
    }) { results ->
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
              mapper.writeValueAsString(mapper.createObjectNode().put("id", id).put("name", name)))
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
