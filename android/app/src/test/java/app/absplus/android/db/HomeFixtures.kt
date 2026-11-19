package app.absplus.android.db

import app.absplus.android.db.replica.AuthorEntity
import app.absplus.android.db.replica.EpisodeEntity
import app.absplus.android.db.replica.EpisodeKey
import app.absplus.android.db.replica.ItemRow
import app.absplus.android.db.replica.LibraryItemEntity
import app.absplus.android.db.replica.SeriesEntity
import app.absplus.android.db.replica.SeriesIndex
import app.absplus.android.db.replica.SeriesItemEntity
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.sync.CatalogJson
import com.fasterxml.jackson.databind.JsonNode

/**
 * The libraries "Home Books" and "Home Podcasts" as Audiobookshelf 2.37.1 answered for the synthetic user "home",
 * recorded by `~/scripts/one-off/abs-home-parity.ts` into resources/catalog/home, turned into replica rows.
 */
object HomeFixtures {
  fun json(name: String): JsonNode = CatalogJson.mapper.readTree(javaClass.getResource("/catalog/home/$name") ?: error("no fixture $name"))

  fun LibraryItemEntity.row() = ItemRow(serverConfigId, id, libraryId, mediaType, updatedAt, contentHash, title,
          titleIgnorePrefix, authorName, authorNameLF, narratorName, seriesName, publisher, language, publishedYear, explicit,
          abridged, genres, tags, addedAt, size, duration, birthtimeMs, mtimeMs, numTracks, numEpisodes, ebookFormat, isMissing,
          isInvalid, rssFeedJson, detailLevel)

  val cases: JsonNode by lazy { json("cases.json") }
  val searchCases: JsonNode by lazy { json("search-cases.json") }
  val booksLibraryId: String get() = cases.path("booksLibraryId").asText()
  val podcastsLibraryId: String get() = cases.path("podcastsLibraryId").asText()

  val bookItems: List<LibraryItemEntity> by lazy { json("items.json").path("results").map { CatalogJson.itemEntity("srv", it) } }
  val podcastItems: List<LibraryItemEntity> by lazy { json("podcast-items.json").path("results").map { CatalogJson.itemEntity("srv", it) } }

  val series: List<SeriesEntity> by lazy {
    json("series.json").map { s ->
      SeriesEntity("srv", s.path("id").asText(), s.path("libraryId").asText(), s.path("name").asText(), s.path("nameIgnorePrefix").asText(),
              s.path("addedAt").asLong(), s.path("updatedAt").asLong(),
              CatalogJson.mapper.writeValueAsString(CatalogJson.mapper.createObjectNode().put("id", s.path("id").asText())
                      .put("name", s.path("name").asText()).put("libraryId", s.path("libraryId").asText())
                      .put("addedAt", s.path("addedAt").asLong()).put("updatedAt", s.path("updatedAt").asLong())))
    }
  }
  val members: List<SeriesItemEntity> by lazy {
    json("series.json").flatMap { s ->
      s.path("members").map { m ->
        SeriesItemEntity("srv", s.path("id").asText(), m.path("libraryItemId").asText(), m.path("sequence").takeIf { !it.isNull }?.asText())
      }
    }
  }
  val seriesIndex: SeriesIndex by lazy { SeriesIndex.of(series, members) }
  val authors: List<AuthorEntity> by lazy { json("authors.json").path("results").map { CatalogJson.authorEntity("srv", it) } }

  val episodes: List<EpisodeEntity> by lazy {
    json("episodes.json").flatMap { p ->
      val podcast = CatalogJson.mapper.createObjectNode().put("id", p.path("libraryItemId").asText())
      podcast.putObject("media").set<JsonNode>("episodes", p.path("episodes"))
      CatalogJson.episodeEntities("srv", podcastsLibraryId, podcast)!!
    }
  }
  val episodeKeys: List<EpisodeKey> by lazy { episodes.map { EpisodeKey(it.id, it.libraryItemId, it.publishedAt, it.addedAt) } }

  private fun JsonNode.longOrNull(key: String) = path(key).takeIf { !it.isNull && !it.isMissingNode }?.asLong()

  val progress: List<MediaProgressEntity> by lazy {
    json("progress.json").map {
      MediaProgressEntity(
              serverConfigId = "srv", libraryItemId = it.path("libraryItemId").asText(),
              episodeId = it.path("episodeId").takeIf { n -> n.isTextual }?.asText() ?: "", serverProgressId = it.path("id").asText(),
              duration = it.path("duration").asDouble(), progress = it.path("progress").asDouble(),
              currentTime = it.path("currentTime").asDouble(), isFinished = it.path("isFinished").asBoolean(),
              hideFromContinueListening = it.path("hideFromContinueListening").asBoolean(), ebookLocation = null,
              ebookProgress = it.path("ebookProgress").takeIf { n -> !n.isNull && !n.isMissingNode }?.asDouble(),
              lastUpdate = it.path("lastUpdate").asLong(), startedAt = it.path("startedAt").asLong(),
              finishedAt = it.longOrNull("finishedAt"), source = "server", pending = false)
    }
  }

  val user: JsonNode by lazy { json("user.json") }
  val hiddenSeriesIds: Set<String> by lazy { user.path("seriesHideFromContinueListening").map { it.asText() }.toSet() }
  val narratorNames: List<String> by lazy { json("filterdata.json").path("narrators").map { it.asText() } }
}
