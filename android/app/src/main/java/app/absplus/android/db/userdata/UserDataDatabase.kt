package app.absplus.android.db.userdata

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * userdata.db: the user's own data that cannot be rebuilt from the server (offline progress, bookmarks, the outbox).
 * Only versioned migrations: a destructive fallback would delete unsent changes. Version 1 must stay readable by the
 * previous app version, so phase 1 of offline-first does not bump it.
 */
@Database(
        entities = [
          MediaProgressEntity::class,
          BookmarkEntity::class,
          OutboxEntity::class,
          ServerSyncStateEntity::class,
          LibrarySyncStateEntity::class
        ],
        version = 1,
        exportSchema = true
)
abstract class UserDataDatabase : RoomDatabase() {
  abstract fun mediaProgress(): MediaProgressDao
  abstract fun bookmarks(): BookmarkDao
  abstract fun outbox(): OutboxDao
  abstract fun syncState(): SyncStateDao

  companion object {
    const val FILE_NAME = "userdata.db"

    @Volatile private var instance: UserDataDatabase? = null

    fun get(context: Context): UserDataDatabase =
            instance ?: synchronized(this) {
              instance ?: open(context, FILE_NAME).also { instance = it }
            }

    internal fun open(context: Context, name: String): UserDataDatabase =
            Room.databaseBuilder(context.applicationContext, UserDataDatabase::class.java, name).build()
  }
}
