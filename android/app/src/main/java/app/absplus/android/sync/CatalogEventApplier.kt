package app.absplus.android.sync

import androidx.room.withTransaction
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.db.replica.SeriesEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Socket events that change the catalog replica. */
val CATALOG_EVENTS: Set<String> = setOf(
        "item_added", "item_updated", "items_added", "items_updated", "item_removed",
        "library_added", "library_updated", "library_removed",
        "series_added", "series_updated", "series_removed",
        "rss_feed_open", "rss_feed_closed", "author_updated", "backup_applied")

enum class CatalogEventResult { APPLIED, IGNORED, NEEDS_RECONCILE }

/**
 * Applies the server's socket deltas to replica.db. An item payload older than the row (an event delayed behind a
 * reconcile page) is ignored; item payloads come without `rssFeed`, so the row keeps the feed it has.
 */
class CatalogEventApplier(
        private val db: ReplicaDatabase,
        private val notify: (Collection<String>) -> Unit,
        private val onReplicaChanged: () -> Unit,
        private val beforeItemWrite: suspend () -> Unit = {}
) {
  private val mapper = CatalogJson.mapper

  suspend fun apply(serverConfigId: String, event: String, payload: JsonNode): CatalogEventResult {
    val topics = LinkedHashSet<String>()
    val result = when (event) {
      "item_added", "item_updated" -> applyItem(serverConfigId, payload, topics)
      "items_added", "items_updated" -> {
        val list = if (payload.isArray) payload.toList() else listOf(payload)
        list.map { applyItem(serverConfigId, it, topics) }.let { results ->
          if (CatalogEventResult.APPLIED in results) CatalogEventResult.APPLIED else CatalogEventResult.IGNORED
        }
      }
      "item_removed" -> removeItem(serverConfigId, payload, topics)
      "library_added", "library_updated" -> upsertLibrary(serverConfigId, payload, topics)
      "library_removed" -> removeLibrary(serverConfigId, payload, topics)
      "series_added", "series_updated" -> upsertSeries(serverConfigId, payload, topics)
      "series_removed" -> removeSeries(serverConfigId, payload, topics)
      "rss_feed_open" -> setFeed(serverConfigId, payload, open = true, topics)
      "rss_feed_closed" -> setFeed(serverConfigId, payload, open = false, topics)
      "author_updated" -> renameAuthor(serverConfigId, payload, topics)
      "backup_applied" -> CatalogEventResult.NEEDS_RECONCILE
      else -> CatalogEventResult.IGNORED
    }
    if (topics.isNotEmpty()) {
      onReplicaChanged()
      notify(topics)
    }
    return result
  }

  private fun JsonNode.text(key: String): String? = get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }

  private suspend fun applyItem(sc: String, payload: JsonNode, topics: MutableSet<String>): CatalogEventResult {
    val id = payload.text("id") ?: return CatalogEventResult.IGNORED
    val libraryId = payload.text("libraryId") ?: return CatalogEventResult.IGNORED
    return db.withTransaction {
      if (db.libraries().get(sc, libraryId) == null) return@withTransaction CatalogEventResult.IGNORED
      val old = db.items().get(sc, id)
      if (old != null && payload.path("updatedAt").asLong() < old.updatedAt) return@withTransaction CatalogEventResult.IGNORED
      val item = payload.deepCopy<JsonNode>() as ObjectNode
      if (!item.has("rssFeed") && old?.rssFeedJson != null) item.set<JsonNode>("rssFeed", mapper.readTree(old.rssFeedJson))
      beforeItemWrite()
      db.items().upsertAll(listOf(CatalogJson.itemEntity(sc, item)))
      topics += "items:$libraryId"
      val series = item.path("media").path("metadata").path("series")
      if (item.path("mediaType").asText() == "book" && series.isArray) {
        val known = series.mapNotNull { s -> s.text("id")?.let { it to (s.text("name") ?: "") } }
        val missing = known.filter { (seriesId, _) -> db.series().get(sc, seriesId) == null }
        if (missing.isNotEmpty()) {
          db.series().upsertAll(missing.map { (seriesId, name) ->
            SeriesEntity(sc, seriesId, libraryId, name, name, 0, 0,
                    mapper.writeValueAsString(mapper.createObjectNode().put("id", seriesId).put("name", name)))
          })
        }
        val before = db.series().membershipOfItem(sc, id)
        val after = CatalogJson.seriesMembershipOf(sc, item)
        if (before.toSet() != after.toSet() || missing.isNotEmpty()) {
          db.series().replaceMembershipOfItem(sc, id, after)
          topics += "series:$libraryId"
        }
      }
      CatalogEventResult.APPLIED
    }
  }

  private suspend fun removeItem(sc: String, payload: JsonNode, topics: MutableSet<String>): CatalogEventResult {
    val id = payload.text("id") ?: return CatalogEventResult.IGNORED
    val old = db.items().get(sc, id) ?: return CatalogEventResult.IGNORED
    db.items().deleteByIds(sc, listOf(id))
    topics += "items:${old.libraryId}"
    return CatalogEventResult.APPLIED
  }

  private suspend fun upsertLibrary(sc: String, payload: JsonNode, topics: MutableSet<String>): CatalogEventResult {
    val id = payload.text("id") ?: return CatalogEventResult.IGNORED
    db.withTransaction {
      val all = db.libraries().all(sc)
      val old = all.firstOrNull { it.id == id }
      val position = old?.position ?: ((all.maxOfOrNull { it.position } ?: -1) + 1)
      val fresh = CatalogJson.libraryEntity(sc, position, payload, null)
              .copy(filterDataJson = old?.filterDataJson, issues = old?.issues ?: 0, numUserPlaylists = old?.numUserPlaylists ?: 0)
      db.libraries().upsertAll(listOf(fresh))
    }
    topics += "libraries"
    topics += "library:$id"
    return CatalogEventResult.APPLIED
  }

  private suspend fun removeLibrary(sc: String, payload: JsonNode, topics: MutableSet<String>): CatalogEventResult {
    val id = payload.text("id") ?: return CatalogEventResult.IGNORED
    if (db.libraries().get(sc, id) == null) return CatalogEventResult.IGNORED
    db.deleteLibraryCascade(sc, id)
    topics += "libraries"
    return CatalogEventResult.APPLIED
  }

  private suspend fun upsertSeries(sc: String, payload: JsonNode, topics: MutableSet<String>): CatalogEventResult {
    val libraryId = payload.text("libraryId") ?: return CatalogEventResult.IGNORED
    payload.text("id") ?: return CatalogEventResult.IGNORED
    if (db.libraries().get(sc, libraryId) == null) return CatalogEventResult.IGNORED
    db.series().upsertAll(listOf(CatalogJson.seriesEntity(sc, payload)))
    topics += "series:$libraryId"
    return CatalogEventResult.APPLIED
  }

  private suspend fun removeSeries(sc: String, payload: JsonNode, topics: MutableSet<String>): CatalogEventResult {
    val id = payload.text("id") ?: return CatalogEventResult.IGNORED
    val old = db.series().get(sc, id) ?: return CatalogEventResult.IGNORED
    db.series().deleteByIds(sc, listOf(id))
    topics += "series:${old.libraryId}"
    return CatalogEventResult.APPLIED
  }

  /** Only feeds of library items; series and collection feeds are not part of an item. */
  private suspend fun setFeed(sc: String, payload: JsonNode, open: Boolean, topics: MutableSet<String>): CatalogEventResult {
    if (payload.text("entityType") != "libraryItem") return CatalogEventResult.IGNORED
    val itemId = payload.text("entityId") ?: return CatalogEventResult.IGNORED
    return db.withTransaction {
      val old = db.items().get(sc, itemId) ?: return@withTransaction CatalogEventResult.IGNORED
      val feedJson = if (open) mapper.writeValueAsString(payload) else null
      if (old.rssFeedJson == feedJson) return@withTransaction CatalogEventResult.IGNORED
      val item = mapper.readTree(old.json) as ObjectNode
      if (open) item.set<JsonNode>("rssFeed", payload)
      db.items().upsertAll(listOf(old.copy(rssFeedJson = feedJson, contentHash = CatalogJson.contentHash(item))))
      topics += "items:${old.libraryId}"
      CatalogEventResult.APPLIED
    }
  }

  /** The author filter compares names: the cached filterdata takes the new one, the books follow with their own events. */
  private suspend fun renameAuthor(sc: String, payload: JsonNode, topics: MutableSet<String>): CatalogEventResult {
    val id = payload.text("id") ?: return CatalogEventResult.IGNORED
    val name = payload.text("name") ?: return CatalogEventResult.IGNORED
    db.withTransaction {
      for (library in db.libraries().all(sc)) {
        val filterData = library.filterDataJson?.let { mapper.readTree(it) as? ObjectNode } ?: continue
        val authors = filterData.path("authors") as? ArrayNode ?: continue
        var changed = false
        authors.forEach { author ->
          if (author is ObjectNode && author.text("id") == id && author.text("name") != name) {
            author.put("name", name)
            changed = true
          }
        }
        if (changed) {
          db.libraries().upsertAll(listOf(library.copy(filterDataJson = mapper.writeValueAsString(filterData))))
          topics += "library:${library.id}"
        }
      }
    }
    return if (topics.isEmpty()) CatalogEventResult.IGNORED else CatalogEventResult.APPLIED
  }
}
