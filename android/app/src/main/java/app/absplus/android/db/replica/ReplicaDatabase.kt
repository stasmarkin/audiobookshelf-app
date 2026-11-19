package app.absplus.android.db.replica

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * replica.db: the catalog of each saved server as last reconciled. Every row carries its serverConfigId.
 * An upgrade migrates additively (v1 -> v2: `podcast_episodes`, `library_state.episodesReconciledAt`) and keeps the
 * catalog. Everything here can be read again from the server, so a downgrade, or an upgrade without a migration, drops
 * the file instead; the catalog is then empty until the next reconcile.
 */
@Database(
        entities = [
          LibraryEntity::class,
          LibraryStateEntity::class,
          LibraryItemEntity::class,
          SeriesEntity::class,
          SeriesItemEntity::class,
          AuthorEntity::class,
          CollectionEntity::class,
          CollectionItemEntity::class,
          PlaylistEntity::class,
          PlaylistItemEntity::class,
          EpisodeEntity::class
        ],
        version = 2,
        exportSchema = true,
        autoMigrations = [AutoMigration(from = 1, to = 2)]
)
abstract class ReplicaDatabase : RoomDatabase() {
  abstract fun libraries(): LibraryDao
  abstract fun libraryState(): LibraryStateDao
  abstract fun items(): LibraryItemDao
  abstract fun series(): SeriesDao
  abstract fun authors(): AuthorDao
  abstract fun collections(): CollectionDao
  abstract fun playlists(): PlaylistDao
  abstract fun cascade(): CascadeDao
  abstract fun episodes(): EpisodeDao

  /** A library with everything in it, including its reconcile state. */
  suspend fun deleteLibraryCascade(sc: String, libraryId: String) = withTransaction {
    val cascade = cascade()
    cascade.deleteSeriesMembers(sc, libraryId)
    cascade.deleteItemMembership(sc, libraryId)
    cascade.deleteSeries(sc, libraryId)
    cascade.deleteEpisodes(sc, libraryId)
    cascade.deleteItems(sc, libraryId)
    authors().deleteForLibrary(sc, libraryId)
    collections().deleteItemsForLibrary(sc, libraryId)
    collections().deleteForLibrary(sc, libraryId)
    playlists().deleteItemsForLibrary(sc, libraryId)
    playlists().deleteForLibrary(sc, libraryId)
    libraryState().delete(sc, libraryId)
    cascade.deleteLibrary(sc, libraryId)
  }

  companion object {
    const val FILE_NAME = "replica.db"

    @Volatile private var instance: ReplicaDatabase? = null

    fun get(context: Context): ReplicaDatabase =
            instance ?: synchronized(this) {
              instance ?: open(context, FILE_NAME).also { instance = it }
            }

    @Synchronized
    internal fun resetForTest() {
      instance?.close()
      instance = null
    }

    internal fun open(context: Context, name: String): ReplicaDatabase =
            Room.databaseBuilder(context.applicationContext, ReplicaDatabase::class.java, name)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
  }
}
