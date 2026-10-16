package app.absplus.android.db.replica

import androidx.room.Entity
import androidx.room.Index

/** A library as `GET /api/libraries` lists it, plus the filterdata of `GET /api/libraries/:id?include=filterdata`. */
@Entity(tableName = "libraries", primaryKeys = ["serverConfigId", "id"])
data class LibraryEntity(
        val serverConfigId: String,
        val id: String,
        /** Index in the server's list. */
        val position: Int,
        val mediaType: String,
        /** The library's `toOldJSON`. */
        val json: String,
        val filterDataJson: String?,
        val issues: Int,
        val numUserPlaylists: Int
)

/** How far the replica of a library got; [libraryId] "" is the library list itself. */
@Entity(tableName = "library_state", primaryKeys = ["serverConfigId", "libraryId"])
data class LibraryStateEntity(
        val serverConfigId: String,
        val libraryId: String,
        val itemsReconciledAt: Long?,
        /** False when the last pass saw the item count change twice and deleted nothing. */
        val itemsComplete: Boolean,
        val seriesReconciledAt: Long?,
        val authorsReconciledAt: Long?,
        val collectionsReconciledAt: Long?,
        val playlistsReconciledAt: Long?,
        val lastError: String?,
        /** JSON array of series ids whose membership still has to be read from the server. */
        val pendingSeriesQueries: String = "[]",
        /** When a pass last gave every podcast of the library a try at its episodes; null until then. */
        val episodesReconciledAt: Long? = null
)

/** [LibraryItemEntity.detailLevel]. */
object DetailLevel {
  /** Only the minified item is known. */
  const val MINIFIED = "minified"
  /** [LibraryItemEntity.expandedJson] (and a podcast's episodes) belong to the row's `updatedAt`. */
  const val EXPANDED = "expanded"
  /** The row changed since [LibraryItemEntity.expandedJson] was taken; it is kept until it is read again. */
  const val STALE = "stale"
}

/** A library item in the server's minified shape ([json]) with the columns the catalog sorts and filters on. */
@Entity(
        tableName = "library_items",
        primaryKeys = ["serverConfigId", "id"],
        indices = [Index("serverConfigId", "libraryId")]
)
data class LibraryItemEntity(
        val serverConfigId: String,
        val id: String,
        val libraryId: String,
        val mediaType: String,
        val updatedAt: Long,
        val contentHash: String,
        val title: String,
        val titleIgnorePrefix: String,
        /** For a podcast, `media.metadata.author`. */
        val authorName: String,
        val authorNameLF: String,
        val narratorName: String,
        val seriesName: String,
        val publisher: String?,
        val language: String?,
        val publishedYear: String?,
        val explicit: Boolean,
        val abridged: Boolean,
        /** JSON array. */
        val genres: String,
        /** JSON array. */
        val tags: String,
        val addedAt: Long,
        val size: Long,
        val duration: Double,
        val birthtimeMs: Long,
        val mtimeMs: Long,
        val numTracks: Int,
        val numEpisodes: Int,
        val ebookFormat: String?,
        val isMissing: Boolean,
        val isInvalid: Boolean,
        val rssFeedJson: String?,
        /** The minified item without `rssFeed`. */
        val json: String,
        /** See `searchKey` in CatalogJson; "" or another version prefix is recomputed on search. */
        val searchKey: String = "",
        val detailLevel: String = DetailLevel.MINIFIED,
        /** The expanded item without user, feed and list extras; a podcast's without `media.episodes`. */
        val expandedJson: String? = null
)

/** An episode of a podcast in [LibraryItemEntity]; [libraryId] is the podcast's. */
@Entity(
        tableName = "podcast_episodes",
        primaryKeys = ["serverConfigId", "id"],
        indices = [Index("serverConfigId", "libraryItemId"), Index("serverConfigId", "libraryId", "addedAt")]
)
data class EpisodeEntity(
        val serverConfigId: String,
        val id: String,
        val libraryItemId: String,
        val libraryId: String,
        val title: String,
        val searchKey: String,
        val publishedAt: Long?,
        /** The episode's `addedAt` (its createdAt). */
        val addedAt: Long,
        val index: Int?,
        /** The episode's `toOldJSONExpanded`. */
        val json: String
)

@Entity(tableName = "series", primaryKeys = ["serverConfigId", "id"], indices = [Index("serverConfigId", "libraryId")])
data class SeriesEntity(
        val serverConfigId: String,
        val id: String,
        val libraryId: String,
        val name: String,
        val nameIgnorePrefix: String,
        val addedAt: Long,
        val updatedAt: Long,
        /** The series' `toOldJSON` without books. */
        val json: String
)

@Entity(
        tableName = "series_items",
        primaryKeys = ["serverConfigId", "seriesId", "libraryItemId"],
        indices = [Index("serverConfigId", "libraryItemId")]
)
data class SeriesItemEntity(
        val serverConfigId: String,
        val seriesId: String,
        val libraryItemId: String,
        /** A string as the server keeps it ("1.5", "10"). */
        val sequence: String?
)

@Entity(tableName = "authors", primaryKeys = ["serverConfigId", "id"], indices = [Index("serverConfigId", "libraryId")])
data class AuthorEntity(
        val serverConfigId: String,
        val id: String,
        val libraryId: String,
        val name: String,
        val lastFirst: String?,
        val addedAt: Long,
        val updatedAt: Long,
        val numBooks: Int,
        val json: String
)

@Entity(tableName = "collections", primaryKeys = ["serverConfigId", "id"], indices = [Index("serverConfigId", "libraryId")])
data class CollectionEntity(
        val serverConfigId: String,
        val id: String,
        val libraryId: String,
        val position: Int,
        /** `toOldJSON` without books. */
        val json: String
)

@Entity(tableName = "collection_items", primaryKeys = ["serverConfigId", "collectionId", "position"])
data class CollectionItemEntity(
        val serverConfigId: String,
        val collectionId: String,
        val position: Int,
        val libraryItemId: String
)

@Entity(tableName = "playlists", primaryKeys = ["serverConfigId", "id"], indices = [Index("serverConfigId", "libraryId")])
data class PlaylistEntity(
        val serverConfigId: String,
        val id: String,
        val libraryId: String,
        val position: Int,
        /** Without items. */
        val json: String
)

@Entity(tableName = "playlist_items", primaryKeys = ["serverConfigId", "playlistId", "position"])
data class PlaylistItemEntity(
        val serverConfigId: String,
        val playlistId: String,
        val position: Int,
        val libraryItemId: String,
        /** "" for a book. */
        val episodeId: String,
        val episodeJson: String?
)

/** [LibraryItemEntity] without its JSON: what sorting and filtering need for a whole library. */
data class ItemRow(
        val serverConfigId: String,
        val id: String,
        val libraryId: String,
        val mediaType: String,
        val updatedAt: Long,
        val contentHash: String,
        val title: String,
        val titleIgnorePrefix: String,
        val authorName: String,
        val authorNameLF: String,
        val narratorName: String,
        val seriesName: String,
        val publisher: String?,
        val language: String?,
        val publishedYear: String?,
        val explicit: Boolean,
        val abridged: Boolean,
        val genres: String,
        val tags: String,
        val addedAt: Long,
        val size: Long,
        val duration: Double,
        val birthtimeMs: Long,
        val mtimeMs: Long,
        val numTracks: Int,
        val numEpisodes: Int,
        val ebookFormat: String?,
        val isMissing: Boolean,
        val isInvalid: Boolean,
        val rssFeedJson: String?,
        val detailLevel: String
)

data class IdJson(val id: String, val json: String, val rssFeedJson: String?)

data class IdHash(val id: String, val updatedAt: Long, val contentHash: String)

data class EpisodeKey(val id: String, val libraryItemId: String, val publishedAt: Long?, val addedAt: Long)

data class IdExpanded(val id: String, val updatedAt: Long, val detailLevel: String, val expandedJson: String?, val numEpisodes: Int)

data class IdSearchJson(val id: String, val searchKey: String, val json: String)
