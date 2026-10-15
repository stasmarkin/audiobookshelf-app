package app.absplus.android.db.replica

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** SQLite allows 999 bound variables before 3.32 (API 24 ships 3.9), so `IN (...)` lists are split. */
const val IN_CHUNK_SIZE = 500

internal inline fun <T> inChunks(ids: List<String>, block: (List<String>) -> T): List<T> =
        ids.chunked(IN_CHUNK_SIZE).map { block(it) }

@Dao
abstract class LibraryDao {
  @Query("SELECT * FROM libraries WHERE serverConfigId = :sc ORDER BY position")
  abstract suspend fun all(sc: String): List<LibraryEntity>

  @Query("SELECT * FROM libraries WHERE serverConfigId = :sc AND id = :id")
  abstract suspend fun get(sc: String, id: String): LibraryEntity?

  @Upsert
  abstract suspend fun upsertAll(list: List<LibraryEntity>)

  /** Only the library rows: [ReplicaDatabase.deleteLibraryCascade] removes a library with its content. */
  @Query("DELETE FROM libraries WHERE serverConfigId = :sc AND id NOT IN (:keepIds)")
  abstract suspend fun deleteMissing(sc: String, keepIds: List<String>)
}

@Dao
abstract class LibraryStateDao {
  @Query("SELECT * FROM library_state WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun get(sc: String, libraryId: String): LibraryStateEntity?

  @Query("SELECT * FROM library_state WHERE serverConfigId = :sc")
  abstract suspend fun all(sc: String): List<LibraryStateEntity>

  @Upsert
  abstract suspend fun upsert(e: LibraryStateEntity)

  @Query("DELETE FROM library_state WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun delete(sc: String, libraryId: String)
}

@Dao
abstract class LibraryItemDao {
  @Query("""SELECT serverConfigId, id, libraryId, mediaType, updatedAt, contentHash, title, titleIgnorePrefix, authorName,
            authorNameLF, narratorName, seriesName, publisher, language, publishedYear, explicit, abridged, genres, tags,
            addedAt, size, duration, birthtimeMs, mtimeMs, numTracks, numEpisodes, ebookFormat, isMissing, isInvalid,
            rssFeedJson, detailLevel FROM library_items WHERE serverConfigId = :sc AND libraryId = :libraryId""")
  abstract suspend fun rows(sc: String, libraryId: String): List<ItemRow>

  @Query("SELECT id FROM library_items WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun ids(sc: String, libraryId: String): List<String>

  @Query("SELECT * FROM library_items WHERE serverConfigId = :sc AND id = :id")
  abstract suspend fun get(sc: String, id: String): LibraryItemEntity?

  @Query("SELECT COUNT(*) FROM library_items WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun count(sc: String, libraryId: String): Int

  suspend fun json(sc: String, ids: List<String>): List<IdJson> = inChunks(ids) { jsonChunk(sc, it) }.flatten()

  /** For the ids of one page. */
  suspend fun hashes(sc: String, ids: List<String>): List<IdHash> = inChunks(ids) { hashesChunk(sc, it) }.flatten()

  @Upsert
  abstract suspend fun upsertAll(list: List<LibraryItemEntity>)

  /** The items and their series membership. */
  @Transaction
  open suspend fun deleteByIds(sc: String, ids: List<String>) {
    inChunks(ids) {
      deleteMembershipChunk(sc, it)
      deleteChunk(sc, it)
    }
  }

  @Query("SELECT id, json, rssFeedJson FROM library_items WHERE serverConfigId = :sc AND id IN (:ids)")
  abstract suspend fun jsonChunk(sc: String, ids: List<String>): List<IdJson>

  @Query("SELECT id, updatedAt, contentHash FROM library_items WHERE serverConfigId = :sc AND id IN (:ids)")
  abstract suspend fun hashesChunk(sc: String, ids: List<String>): List<IdHash>

  @Query("DELETE FROM library_items WHERE serverConfigId = :sc AND id IN (:ids)")
  abstract suspend fun deleteChunk(sc: String, ids: List<String>)

  @Query("DELETE FROM series_items WHERE serverConfigId = :sc AND libraryItemId IN (:ids)")
  abstract suspend fun deleteMembershipChunk(sc: String, ids: List<String>)
}

@Dao
abstract class SeriesDao {
  @Query("SELECT * FROM series WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun forLibrary(sc: String, libraryId: String): List<SeriesEntity>

  @Query("SELECT * FROM series WHERE serverConfigId = :sc AND id = :id")
  abstract suspend fun get(sc: String, id: String): SeriesEntity?

  @Query("""SELECT m.* FROM series_items m JOIN series s ON s.serverConfigId = m.serverConfigId AND s.id = m.seriesId
            WHERE m.serverConfigId = :sc AND s.libraryId = :libraryId""")
  abstract suspend fun members(sc: String, libraryId: String): List<SeriesItemEntity>

  @Query("SELECT * FROM series_items WHERE serverConfigId = :sc AND libraryItemId = :libraryItemId")
  abstract suspend fun membershipOfItem(sc: String, libraryItemId: String): List<SeriesItemEntity>

  @Upsert
  abstract suspend fun upsertAll(list: List<SeriesEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertMembers(list: List<SeriesItemEntity>)

  @Query("DELETE FROM series_items WHERE serverConfigId = :sc AND seriesId = :seriesId")
  abstract suspend fun deleteMembersOfSeries(sc: String, seriesId: String)

  @Query("DELETE FROM series_items WHERE serverConfigId = :sc AND libraryItemId = :libraryItemId")
  abstract suspend fun deleteMembershipOfItem(sc: String, libraryItemId: String)

  @Transaction
  open suspend fun replaceMembersOfSeries(sc: String, seriesId: String, members: List<SeriesItemEntity>) {
    deleteMembersOfSeries(sc, seriesId)
    if (members.isNotEmpty()) insertMembers(members)
  }

  @Transaction
  open suspend fun replaceMembershipOfItem(sc: String, libraryItemId: String, members: List<SeriesItemEntity>) {
    deleteMembershipOfItem(sc, libraryItemId)
    if (members.isNotEmpty()) insertMembers(members)
  }

  @Query("SELECT id FROM series WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun ids(sc: String, libraryId: String): List<String>

  /** The series and their membership. */
  @Transaction
  open suspend fun deleteByIds(sc: String, ids: List<String>) {
    inChunks(ids) {
      deleteMembersChunk(sc, it)
      deleteChunk(sc, it)
    }
  }

  @Query("DELETE FROM series WHERE serverConfigId = :sc AND id IN (:ids)")
  abstract suspend fun deleteChunk(sc: String, ids: List<String>)

  @Query("DELETE FROM series_items WHERE serverConfigId = :sc AND seriesId IN (:ids)")
  abstract suspend fun deleteMembersChunk(sc: String, ids: List<String>)
}

@Dao
abstract class AuthorDao {
  @Query("SELECT * FROM authors WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun forLibrary(sc: String, libraryId: String): List<AuthorEntity>

  /** NOCASE folds only ASCII and compares the rest by code point, as `nocaseCompare` and the server's sort. */
  @Query("""SELECT * FROM authors WHERE serverConfigId = :sc AND libraryId = :libraryId
            ORDER BY name COLLATE NOCASE, id LIMIT :limit OFFSET :offset""")
  abstract suspend fun pageByName(sc: String, libraryId: String, limit: Int, offset: Int): List<AuthorEntity>

  @Query("SELECT COUNT(*) FROM authors WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun count(sc: String, libraryId: String): Int

  @Query("SELECT * FROM authors WHERE serverConfigId = :sc AND id = :id")
  abstract suspend fun get(sc: String, id: String): AuthorEntity?

  @Query("SELECT id FROM authors WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun ids(sc: String, libraryId: String): List<String>

  @Upsert
  abstract suspend fun upsertAll(list: List<AuthorEntity>)

  @Transaction
  open suspend fun deleteByIds(sc: String, ids: List<String>) {
    inChunks(ids) { deleteChunk(sc, it) }
  }

  @Query("DELETE FROM authors WHERE serverConfigId = :sc AND id IN (:ids)")
  abstract suspend fun deleteChunk(sc: String, ids: List<String>)

  @Query("DELETE FROM authors WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun deleteForLibrary(sc: String, libraryId: String)
}

/** Books of a collection, all and those the replica has (the user may read). */
data class CollectionCounts(val collectionId: String, val total: Int, val visible: Int)

@Dao
abstract class CollectionDao {
  @Upsert
  abstract suspend fun upsertAll(list: List<CollectionEntity>)

  @Query("SELECT * FROM collections WHERE serverConfigId = :sc AND libraryId = :libraryId ORDER BY position, id")
  abstract suspend fun forLibrary(sc: String, libraryId: String): List<CollectionEntity>

  @Query("SELECT * FROM collections WHERE serverConfigId = :sc AND id = :id")
  abstract suspend fun get(sc: String, id: String): CollectionEntity?

  @Query("SELECT id FROM collections WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun ids(sc: String, libraryId: String): List<String>

  @Query("SELECT MAX(position) FROM collections WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun maxPosition(sc: String, libraryId: String): Int?

  @Query("SELECT libraryItemId FROM collection_items WHERE serverConfigId = :sc AND collectionId = :collectionId ORDER BY position")
  abstract suspend fun itemIds(sc: String, collectionId: String): List<String>

  /** Collections without books have no row. */
  @Query("""SELECT ci.collectionId AS collectionId, COUNT(*) AS total, COUNT(li.id) AS visible FROM collection_items ci
            JOIN collections c ON c.serverConfigId = ci.serverConfigId AND c.id = ci.collectionId
            LEFT JOIN library_items li ON li.serverConfigId = ci.serverConfigId AND li.id = ci.libraryItemId
            WHERE ci.serverConfigId = :sc AND c.libraryId = :libraryId GROUP BY ci.collectionId""")
  abstract suspend fun counts(sc: String, libraryId: String): List<CollectionCounts>

  @Query("""SELECT ci.collectionId AS collectionId, COUNT(*) AS total, COUNT(li.id) AS visible FROM collection_items ci
            LEFT JOIN library_items li ON li.serverConfigId = ci.serverConfigId AND li.id = ci.libraryItemId
            WHERE ci.serverConfigId = :sc AND ci.collectionId = :collectionId GROUP BY ci.collectionId""")
  abstract suspend fun countsOf(sc: String, collectionId: String): CollectionCounts?

  /** Ids of the books the replica has, in the collection's order. */
  @Query("""SELECT ci.libraryItemId FROM collection_items ci
            JOIN library_items li ON li.serverConfigId = ci.serverConfigId AND li.id = ci.libraryItemId
            WHERE ci.serverConfigId = :sc AND ci.collectionId = :collectionId ORDER BY ci.position LIMIT :limit OFFSET :offset""")
  abstract suspend fun visibleItemIds(sc: String, collectionId: String, limit: Int, offset: Int): List<String>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertItems(list: List<CollectionItemEntity>)

  @Query("DELETE FROM collection_items WHERE serverConfigId = :sc AND collectionId = :collectionId")
  abstract suspend fun deleteItemsOf(sc: String, collectionId: String)

  /** The collection with its books, in the order of [libraryItemIds]. */
  @Transaction
  open suspend fun replace(collection: CollectionEntity, libraryItemIds: List<String>) {
    upsertAll(listOf(collection))
    deleteItemsOf(collection.serverConfigId, collection.id)
    if (libraryItemIds.isNotEmpty()) {
      insertItems(libraryItemIds.mapIndexed { i, id -> CollectionItemEntity(collection.serverConfigId, collection.id, i, id) })
    }
  }

  /** The collections and their books. */
  @Transaction
  open suspend fun deleteByIds(sc: String, ids: List<String>) {
    inChunks(ids) {
      deleteItemsChunk(sc, it)
      deleteChunk(sc, it)
    }
  }

  @Query("DELETE FROM collections WHERE serverConfigId = :sc AND id IN (:ids)")
  abstract suspend fun deleteChunk(sc: String, ids: List<String>)

  @Query("DELETE FROM collection_items WHERE serverConfigId = :sc AND collectionId IN (:ids)")
  abstract suspend fun deleteItemsChunk(sc: String, ids: List<String>)

  @Query("""DELETE FROM collection_items WHERE serverConfigId = :sc
            AND collectionId IN (SELECT id FROM collections WHERE serverConfigId = :sc AND libraryId = :libraryId)""")
  abstract suspend fun deleteItemsForLibrary(sc: String, libraryId: String)

  @Query("DELETE FROM collections WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun deleteForLibrary(sc: String, libraryId: String)
}

/** Filled from release 4e on; here only what removing a library needs. */
@Dao
abstract class PlaylistDao {
  @Upsert
  abstract suspend fun upsertAll(list: List<PlaylistEntity>)

  @Query("""DELETE FROM playlist_items WHERE serverConfigId = :sc
            AND playlistId IN (SELECT id FROM playlists WHERE serverConfigId = :sc AND libraryId = :libraryId)""")
  abstract suspend fun deleteItemsForLibrary(sc: String, libraryId: String)

  @Query("DELETE FROM playlists WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun deleteForLibrary(sc: String, libraryId: String)
}

/** Whole-library deletes behind [ReplicaDatabase.deleteLibraryCascade]. */
@Dao
abstract class CascadeDao {
  @Query("""DELETE FROM series_items WHERE serverConfigId = :sc
            AND seriesId IN (SELECT id FROM series WHERE serverConfigId = :sc AND libraryId = :libraryId)""")
  abstract suspend fun deleteSeriesMembers(sc: String, libraryId: String)

  @Query("""DELETE FROM series_items WHERE serverConfigId = :sc
            AND libraryItemId IN (SELECT id FROM library_items WHERE serverConfigId = :sc AND libraryId = :libraryId)""")
  abstract suspend fun deleteItemMembership(sc: String, libraryId: String)

  @Query("DELETE FROM series WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun deleteSeries(sc: String, libraryId: String)

  @Query("DELETE FROM library_items WHERE serverConfigId = :sc AND libraryId = :libraryId")
  abstract suspend fun deleteItems(sc: String, libraryId: String)

  @Query("DELETE FROM libraries WHERE serverConfigId = :sc AND id = :libraryId")
  abstract suspend fun deleteLibrary(sc: String, libraryId: String)
}
