package app.absplus.android.db.userdata

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface MediaProgressDao {
  @Query("SELECT * FROM media_progress WHERE serverConfigId = :serverConfigId AND libraryItemId = :libraryItemId AND episodeId = :episodeId")
  suspend fun get(serverConfigId: String, libraryItemId: String, episodeId: String): MediaProgressEntity?

  @Query("SELECT * FROM media_progress WHERE serverConfigId = :serverConfigId")
  suspend fun getAll(serverConfigId: String): List<MediaProgressEntity>

  @Upsert
  suspend fun upsert(entity: MediaProgressEntity)

  @Upsert
  suspend fun upsertAll(entities: List<MediaProgressEntity>)

  @Query("DELETE FROM media_progress WHERE serverConfigId = :serverConfigId AND libraryItemId = :libraryItemId AND episodeId = :episodeId")
  suspend fun delete(serverConfigId: String, libraryItemId: String, episodeId: String)
}

@Dao
interface BookmarkDao {
  @Query("SELECT * FROM bookmarks WHERE serverConfigId = :serverConfigId AND deletedAt IS NULL ORDER BY libraryItemId, time")
  suspend fun getVisible(serverConfigId: String): List<BookmarkEntity>

  /** Including tombstones. */
  @Query("SELECT * FROM bookmarks WHERE serverConfigId = :serverConfigId ORDER BY libraryItemId, time")
  suspend fun getAll(serverConfigId: String): List<BookmarkEntity>

  /** Including tombstones. */
  @Query("SELECT * FROM bookmarks WHERE serverConfigId = :serverConfigId AND libraryItemId = :libraryItemId ORDER BY time")
  suspend fun getForItem(serverConfigId: String, libraryItemId: String): List<BookmarkEntity>

  @Upsert
  suspend fun upsert(entity: BookmarkEntity)

  @Query("DELETE FROM bookmarks WHERE serverConfigId = :serverConfigId AND libraryItemId = :libraryItemId AND time = :time")
  suspend fun delete(serverConfigId: String, libraryItemId: String, time: Double)
}

@Dao
interface OutboxDao {
  /**
   * The oldest row of the server when it is due, otherwise null: rows are sent strictly in order, so a row waiting
   * for its backoff also holds back every row after it.
   */
  @Query("""SELECT * FROM outbox WHERE serverConfigId = :serverConfigId AND nextAttemptAt <= :now
            AND id = (SELECT MIN(id) FROM outbox WHERE serverConfigId = :serverConfigId)""")
  suspend fun nextDue(serverConfigId: String, now: Long): OutboxEntity?

  /** The oldest row of the server, due or not. */
  @Query("SELECT * FROM outbox WHERE serverConfigId = :serverConfigId ORDER BY id LIMIT 1")
  suspend fun head(serverConfigId: String): OutboxEntity?

  /** Rows of every kind about one item, oldest first. */
  @Query("SELECT * FROM outbox WHERE serverConfigId = :serverConfigId AND itemKey = :itemKey ORDER BY id")
  suspend fun byItemKey(serverConfigId: String, itemKey: String): List<OutboxEntity>

  @Insert
  suspend fun insert(entity: OutboxEntity): Long

  @Update
  suspend fun update(entity: OutboxEntity)

  @Query("DELETE FROM outbox WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("SELECT COUNT(*) FROM outbox WHERE serverConfigId = :serverConfigId")
  suspend fun count(serverConfigId: String): Int
}

@Dao
interface SyncStateDao {
  @Query("SELECT * FROM sync_state WHERE serverConfigId = :serverConfigId")
  suspend fun get(serverConfigId: String): ServerSyncStateEntity?

  @Upsert
  suspend fun upsert(entity: ServerSyncStateEntity)

  @Query("SELECT * FROM library_sync_state WHERE serverConfigId = :serverConfigId AND libraryId = :libraryId")
  suspend fun getLibrary(serverConfigId: String, libraryId: String): LibrarySyncStateEntity?

  @Upsert
  suspend fun upsertLibrary(entity: LibrarySyncStateEntity)
}
