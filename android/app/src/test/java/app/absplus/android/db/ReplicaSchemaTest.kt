package app.absplus.android.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.db.replica.ReplicaDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** replica.db v1 holds every table of release 4, and a schema it does not know is dropped, never migrated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplicaSchemaTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val schemaFile = File("schemas/app.absplus.android.db.replica.ReplicaDatabase/1.json")

  @Test
  fun schemaV1IsExported() {
    assertTrue("missing ${schemaFile.absolutePath}", schemaFile.isFile)
    val database = JSONObject(schemaFile.readText()).getJSONObject("database")
    assertEquals(1, database.getInt("version"))
    val entities = database.getJSONArray("entities")
    val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
    assertEquals(setOf("libraries", "library_state", "library_items", "series", "series_items", "authors", "collections",
            "collection_items", "playlists", "playlist_items"), tables)
  }

  /** A file written by a newer schema: version 2 and a table this version has never heard of. */
  private fun newerFile(name: String) {
    val file = context.getDatabasePath(name)
    file.parentFile?.mkdirs()
    file.delete()
    SQLiteDatabase.openOrCreateDatabase(file, null).use {
      it.execSQL("CREATE TABLE future_catalog (id TEXT PRIMARY KEY)")
      it.execSQL("INSERT INTO future_catalog VALUES ('x')")
      it.version = 2
    }
  }

  @Test
  fun downgradeWipesInsteadOfCrashing() = runBlocking {
    newerFile("replica-test.db")
    val db = ReplicaDatabase.open(context, "replica-test.db")
    try {
      assertTrue(db.libraries().all("srv").isEmpty())
      db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'future_catalog'").use {
        assertEquals("the unknown table is dropped too", 0, it.count)
      }
    } finally {
      db.close()
    }
  }

  @Test
  fun withoutTheFallbackTheSameFileFails() = runBlocking {
    newerFile("replica-strict.db")
    val db = Room.databaseBuilder(context, ReplicaDatabase::class.java, "replica-strict.db").build()
    try {
      db.libraries().all("srv")
      fail("a downgrade without a destructive fallback must not open")
    } catch (expected: IllegalStateException) {
    } finally {
      db.close()
    }
  }
}
