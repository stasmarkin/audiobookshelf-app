package app.absplus.android.db.replica

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * replica.db: the catalog of each saved server as last reconciled. Everything here can be read again from the server,
 * so any schema change (upgrade or downgrade) drops it instead of migrating; the catalog is then empty until the next
 * reconcile. Every row carries its serverConfigId.
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
          PlaylistItemEntity::class
        ],
        version = 1,
        exportSchema = true
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

  /** A library with everything in it, including its reconcile state. */
  suspend fun deleteLibraryCascade(sc: String, libraryId: String) = withTransaction {
    val cascade = cascade()
    cascade.deleteSeriesMembers(sc, libraryId)
    cascade.deleteItemMembership(sc, libraryId)
    cascade.deleteSeries(sc, libraryId)
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
