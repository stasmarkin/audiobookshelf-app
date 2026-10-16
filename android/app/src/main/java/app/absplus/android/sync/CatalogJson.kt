package app.absplus.android.sync

import app.absplus.android.db.replica.AuthorEntity
import app.absplus.android.db.replica.CollectionEntity
import app.absplus.android.db.replica.LibraryEntity
import app.absplus.android.db.replica.LibraryItemEntity
import app.absplus.android.db.replica.PlaylistEntity
import app.absplus.android.db.replica.PlaylistItemEntity
import app.absplus.android.db.replica.SeriesEntity
import app.absplus.android.db.replica.SeriesItemEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest

/** How `seriesName` of a minified book maps onto known series. */
sealed interface SeriesNameParse {
  /** (seriesId, sequence) per series of the book. */
  data class Parsed(val members: List<Pair<String, String?>>) : SeriesNameParse
  /** The name cannot be read with certainty: the membership of these series has to be asked from the server. */
  /** [unknownPart]: no reading covers the whole string, so a series no list knows yet may be in it. */
  data class Ambiguous(val candidateSeriesIds: Set<String>, val unknownPart: Boolean = false) : SeriesNameParse
}

/** Catalog answers of the server (2.37.1 shapes) turned into replica rows. */
object CatalogJson {
  val mapper = jacksonObjectMapper()
  private val nodes = JsonNodeFactory.instance

  private val ITEM_KEYS = listOf("id", "ino", "oldLibraryItemId", "libraryId", "folderId", "path", "relPath", "isFile", "mtimeMs",
          "ctimeMs", "birthtimeMs", "addedAt", "updatedAt", "isMissing", "isInvalid", "mediaType")
  private val BOOK_METADATA_KEYS = listOf("title", "titleIgnorePrefix", "subtitle", "authorName", "authorNameLF", "narratorName",
          "seriesName", "genres", "publishedYear", "publishedDate", "publisher", "description", "isbn", "asin", "language", "explicit",
          "abridged")
  private val PODCAST_KEYS = listOf("autoDownloadEpisodes", "autoDownloadSchedule", "lastEpisodeCheck", "maxEpisodesToKeep",
          "maxNewEpisodesToDownload")

  /**
   * The minified item (`LibraryItem.toOldJSONMinified`) from a minified or expanded one, without list extras such as
   * `rssFeed`, `collapsedSeries` or `media.metadata.series`. Counts missing in payloads of older servers are computed.
   */
  fun minifyItem(item: JsonNode): ObjectNode {
    val out = nodes.objectNode()
    ITEM_KEYS.forEach { copy(item, out, it) }
    val media = item.path("media")
    if (media.isObject) out.set<JsonNode>("media", if (item.path("mediaType").asText() == "podcast") minifyPodcast(media) else minifyBook(media))
    when {
      item.has("numFiles") -> copy(item, out, "numFiles")
      item.path("libraryFiles").isArray -> out.put("numFiles", item.path("libraryFiles").size())
    }
    copy(item, out, "size")
    return out
  }

  private fun minifyBook(media: JsonNode): ObjectNode {
    val out = nodes.objectNode()
    copy(media, out, "id")
    val metadata = media.path("metadata")
    out.set<JsonNode>("metadata", nodes.objectNode().also { m -> BOOK_METADATA_KEYS.forEach { copy(metadata, m, it) } })
    copy(media, out, "coverPath")
    copy(media, out, "tags")
    count(media, out, "numTracks", "tracks")
    count(media, out, "numAudioFiles", "audioFiles")
    count(media, out, "numChapters", "chapters")
    copy(media, out, "duration")
    copy(media, out, "size")
    when {
      media.has("ebookFormat") -> copy(media, out, "ebookFormat")
      media.path("ebookFile").isObject -> copy(media.path("ebookFile"), out, "ebookFormat")
    }
    return out
  }

  private fun minifyPodcast(media: JsonNode): ObjectNode {
    val out = nodes.objectNode()
    copy(media, out, "id")
    copy(media, out, "metadata")
    copy(media, out, "coverPath")
    copy(media, out, "tags")
    count(media, out, "numEpisodes", "episodes")
    PODCAST_KEYS.forEach { copy(media, out, it) }
    copy(media, out, "size")
    return out
  }

  private fun copy(from: JsonNode, to: ObjectNode, key: String) {
    from.get(key)?.let { to.set<JsonNode>(key, it.deepCopy()) }
  }

  private fun count(from: JsonNode, to: ObjectNode, key: String, listKey: String) {
    when {
      from.has(key) -> copy(from, to, key)
      from.path(listKey).isArray -> to.put(key, from.path(listKey).size())
    }
  }

  /** SHA-256 of the minified item with its `rssFeed`, keys sorted: a renamed author or an opened feed counts as a change. */
  fun contentHash(item: JsonNode): String {
    val minified = minifyItem(item)
    item.get("rssFeed")?.takeIf { it.isObject }?.let { minified.set<JsonNode>("rssFeed", it) }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical(minified).toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun canonical(node: JsonNode): String = when {
    node.isObject -> node.fieldNames().asSequence().sorted()
            .joinToString(",", "{", "}") { mapper.writeValueAsString(it) + ":" + canonical(node.get(it)) }
    node.isArray -> node.joinToString(",", "[", "]") { canonical(it) }
    else -> mapper.writeValueAsString(node)
  }

  private fun JsonNode.text(key: String): String? = get(key)?.takeIf { !it.isNull }?.asText()

  fun itemEntity(serverConfigId: String, item: JsonNode): LibraryItemEntity {
    val minified = minifyItem(item)
    val media = minified.path("media")
    val metadata = media.path("metadata")
    val podcast = minified.path("mediaType").asText() == "podcast"
    val feed = item.get("rssFeed")?.takeIf { it.isObject }
    return LibraryItemEntity(
            serverConfigId = serverConfigId,
            id = minified.path("id").asText(),
            libraryId = minified.path("libraryId").asText(),
            mediaType = minified.path("mediaType").asText(),
            updatedAt = minified.path("updatedAt").asLong(),
            contentHash = contentHash(item),
            title = metadata.text("title") ?: "",
            titleIgnorePrefix = metadata.text("titleIgnorePrefix") ?: metadata.text("title") ?: "",
            authorName = (if (podcast) metadata.text("author") else metadata.text("authorName")) ?: "",
            authorNameLF = metadata.text("authorNameLF") ?: "",
            narratorName = metadata.text("narratorName") ?: "",
            seriesName = metadata.text("seriesName") ?: "",
            publisher = metadata.text("publisher"),
            language = metadata.text("language"),
            publishedYear = metadata.text("publishedYear"),
            explicit = metadata.path("explicit").asBoolean(false),
            abridged = metadata.path("abridged").asBoolean(false),
            genres = stringArray(metadata.path("genres")),
            tags = stringArray(media.path("tags")),
            addedAt = minified.path("addedAt").asLong(),
            size = minified.path("size").asLong(),
            duration = media.path("duration").asDouble(0.0),
            birthtimeMs = minified.path("birthtimeMs").asLong(),
            mtimeMs = minified.path("mtimeMs").asLong(),
            numTracks = media.path("numTracks").asInt(0),
            numEpisodes = media.path("numEpisodes").asInt(0),
            ebookFormat = media.text("ebookFormat"),
            isMissing = minified.path("isMissing").asBoolean(false),
            isInvalid = minified.path("isInvalid").asBoolean(false),
            rssFeedJson = feed?.let { mapper.writeValueAsString(it) },
            json = mapper.writeValueAsString(minified))
  }

  private fun stringArray(node: JsonNode): String {
    val out: ArrayNode = nodes.arrayNode()
    if (node.isArray) node.forEach { if (!it.isNull) out.add(it.asText()) }
    return mapper.writeValueAsString(out)
  }

  /** [library]: an entry of `GET /api/libraries`; [filterResponse]: `GET /api/libraries/:id?include=filterdata`. */
  fun libraryEntity(serverConfigId: String, position: Int, library: JsonNode, filterResponse: JsonNode?): LibraryEntity =
          LibraryEntity(
                  serverConfigId = serverConfigId,
                  id = library.path("id").asText(),
                  position = position,
                  mediaType = library.path("mediaType").asText("book"),
                  json = mapper.writeValueAsString(library),
                  filterDataJson = filterResponse?.get("filterdata")?.takeIf { it.isObject }?.let { mapper.writeValueAsString(it) },
                  issues = filterResponse?.path("issues")?.asInt(0) ?: 0,
                  numUserPlaylists = filterResponse?.path("numUserPlaylists")?.asInt(0) ?: 0)

  /** The series of a `GET /api/libraries/:id/series` page; its books carry no sequence, so membership is not read here. */
  fun seriesPage(serverConfigId: String, page: JsonNode): List<SeriesEntity> =
          page.path("results").map { seriesEntity(serverConfigId, it) }

  fun seriesEntity(serverConfigId: String, series: JsonNode): SeriesEntity {
    // Field by field: a series answer carries all its books, which are not kept and need no copy
    val json = nodes.objectNode()
    series.fields().forEach { (key, value) -> if (key != "books") json.set<JsonNode>(key, value) }
    val name = series.text("name") ?: ""
    return SeriesEntity(
            serverConfigId = serverConfigId,
            id = series.path("id").asText(),
            libraryId = series.path("libraryId").asText(),
            name = name,
            nameIgnorePrefix = series.text("nameIgnorePrefix") ?: name,
            addedAt = series.path("addedAt").asLong(0),
            updatedAt = series.path("updatedAt").asLong(0),
            json = mapper.writeValueAsString(json))
  }

  /**
   * An entry of `GET /api/libraries/:id/authors` (`toOldJSONExpanded` + `lastFirst`) or an author event payload, kept
   * whole as [AuthorEntity.json]; a payload without `numBooks` or `lastFirst` gives 0 and null.
   */
  fun authorEntity(serverConfigId: String, author: JsonNode): AuthorEntity = AuthorEntity(
          serverConfigId = serverConfigId,
          id = author.path("id").asText(),
          libraryId = author.path("libraryId").asText(),
          name = author.text("name") ?: "",
          lastFirst = author.text("lastFirst"),
          addedAt = author.path("addedAt").asLong(0),
          updatedAt = author.path("updatedAt").asLong(0),
          numBooks = author.path("numBooks").asInt(0),
          json = mapper.writeValueAsString(author))

  /**
   * A collection of `GET /api/libraries/:id/collections` or a collection event (`toOldJSONExpanded`): the row without
   * `books`, and the ids of its books in the collection's order. The expanded books themselves are not kept.
   */
  fun collectionEntity(serverConfigId: String, collection: JsonNode, position: Int): Pair<CollectionEntity, List<String>> {
    val json = nodes.objectNode()
    collection.fields().forEach { (key, value) -> if (key != "books") json.set<JsonNode>(key, value) }
    val bookIds = collection.path("books").mapNotNull { book -> book.text("id")?.takeIf { it.isNotEmpty() } }
    return CollectionEntity(serverConfigId, collection.path("id").asText(), collection.path("libraryId").asText(), position,
            mapper.writeValueAsString(json)) to bookIds
  }

  /**
   * A playlist of `GET /api/libraries/:id/playlists` or a playlist event (`toOldJSONExpanded`): the row without `items`,
   * and its items in the playlist's order, an episode with its JSON. The expanded library items are not kept.
   */
  fun playlistEntity(serverConfigId: String, playlist: JsonNode, position: Int): Pair<PlaylistEntity, List<PlaylistItemEntity>> {
    val json = nodes.objectNode()
    playlist.fields().forEach { (key, value) -> if (key != "items") json.set<JsonNode>(key, value) }
    val id = playlist.path("id").asText()
    val items = playlist.path("items").mapNotNull { item -> item.text("libraryItemId")?.takeIf { it.isNotEmpty() }?.let { it to item } }
            .mapIndexed { i, (libraryItemId, item) ->
              val episodeId = item.text("episodeId") ?: ""
              val episode = item.path("episode").takeIf { episodeId.isNotEmpty() && it.isObject }
              PlaylistItemEntity(serverConfigId, id, i, libraryItemId, episodeId, episode?.let { mapper.writeValueAsString(it) })
            }
    return PlaylistEntity(serverConfigId, id, playlist.path("libraryId").asText(), position, mapper.writeValueAsString(json)) to items
  }

  /** What a collection cover reads of a book, cut from a minified item; fields the item lacks are left out. */
  fun previewItem(minifiedJson: String): ObjectNode {
    val item = mapper.readTree(minifiedJson)
    val out = nodes.objectNode()
    listOf("id", "ino", "libraryId", "mediaType", "updatedAt").forEach { copy(item, out, it) }
    val media = item.path("media")
    val metadata = media.path("metadata")
    val outMedia = out.putObject("media")
    copy(media, outMedia, "coverPath")
    val outMetadata = outMedia.putObject("metadata")
    copy(metadata, outMetadata, "title")
    copy(metadata, outMetadata, "authorName")
    return out
  }

  /** `media.metadata.series` of an expanded item, or the single object a series-filtered list puts there. */
  fun seriesMembershipOf(serverConfigId: String, expandedItem: JsonNode): List<SeriesItemEntity> {
    val itemId = expandedItem.path("id").asText()
    val series = expandedItem.path("media").path("metadata").path("series")
    val list = when {
      series.isArray -> series.toList()
      series.isObject -> listOf(series)
      else -> emptyList()
    }
    return list.mapNotNull { s ->
      val id = s.text("id") ?: return@mapNotNull null
      SeriesItemEntity(serverConfigId, id, itemId, s.text("sequence"))
    }
  }

  /**
   * Reads `"Name"` / `"Name #seq"` parts joined by `", "` (the server's `Book.seriesName`, not escaped) against the
   * known series names. Backtracking from the left: exactly one complete reading where each name belongs to one series
   * is [SeriesNameParse.Parsed]; none, several or a name shared by several series is [SeriesNameParse.Ambiguous].
   */
  fun parseSeriesName(seriesName: String, idsByName: Map<String, List<String>>): SeriesNameParse {
    if (seriesName.isEmpty()) return SeriesNameParse.Parsed(emptyList())
    val readings = mutableListOf<List<Pair<String, String?>>>()
    fun walk(pos: Int, acc: List<Pair<String, String?>>) {
      if (readings.size > 1) return
      for (name in idsByName.keys) {
        if (name.isEmpty() || !seriesName.startsWith(name, pos)) continue
        val end = pos + name.length
        when {
          end == seriesName.length -> readings += acc + (name to null)
          seriesName.startsWith(", ", end) -> walk(end + 2, acc + (name to null))
          seriesName.startsWith(" #", end) -> {
            val seqStart = end + 2
            // A sequence ends at the nearest ", " (a sequence containing one is left to the server's answer)
            val sep = seriesName.indexOf(", ", seqStart)
            when {
              sep > seqStart -> walk(sep + 2, acc + (name to seriesName.substring(seqStart, sep)))
              sep < 0 && seriesName.length > seqStart -> readings += acc + (name to seriesName.substring(seqStart))
            }
          }
        }
      }
    }
    walk(0, emptyList())
    val single = readings.singleOrNull()
    if (single != null && single.all { (name, _) -> idsByName[name]?.size == 1 }) {
      return SeriesNameParse.Parsed(single.map { (name, sequence) -> idsByName.getValue(name).single() to sequence })
    }
    // A part starts the string or follows ", ": a name found elsewhere is inside another series' name
    val candidates = idsByName.filterKeys { it.isNotEmpty() && (seriesName.startsWith(it) || seriesName.contains(", $it")) }
            .values.flatten().toSet()
    return SeriesNameParse.Ambiguous(candidates, unknownPart = readings.isEmpty())
  }
}
