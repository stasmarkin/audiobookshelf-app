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

/** replica.db: an upgrade from v1 migrates additively and keeps the catalog, a downgrade drops the file instead of crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplicaSchemaTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val schemaFile = File("schemas/app.absplus.android.db.replica.ReplicaDatabase/1.json")
  private val schemaV2File = File("schemas/app.absplus.android.db.replica.ReplicaDatabase/2.json")

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

  @Test
  fun schemaV2IsExported() {
    assertTrue("1.json is read by the migration and must stay", schemaFile.isFile)
    assertTrue("missing ${schemaV2File.absolutePath}", schemaV2File.isFile)
    val database = JSONObject(schemaV2File.readText()).getJSONObject("database")
    assertEquals(2, database.getInt("version"))
    val entities = database.getJSONArray("entities")
    val columns = (0 until entities.length()).associate { i ->
      val entity = entities.getJSONObject(i)
      val fields = entity.getJSONArray("fields")
      entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }.toSet()
    }
    assertEquals(setOf("libraries", "library_state", "library_items", "series", "series_items", "authors", "collections",
            "collection_items", "playlists", "playlist_items", "podcast_episodes"), columns.keys)
    assertTrue(columns.getValue("library_state").contains("episodesReconciledAt"))
  }

  /** A v1 file as release 4 wrote it, built from the committed 1.json. */
  private fun v1File(name: String) {
    val file = context.getDatabasePath(name)
    file.parentFile?.mkdirs()
    file.delete()
    val database = JSONObject(schemaFile.readText()).getJSONObject("database")
    SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
      val entities = database.getJSONArray("entities")
      for (i in 0 until entities.length()) {
        val entity = entities.getJSONObject(i)
        val table = entity.getString("tableName")
        sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        val indices = entity.optJSONArray("indices") ?: continue
        for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
      }
      val setup = database.getJSONArray("setupQueries")
      for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
      sql.execSQL("""INSERT INTO library_items VALUES ('srv', 'li1', 'lib1', 'podcast', 5, 'h', 'Kept', 'Kept', '', '', '', '', NULL,
              NULL, NULL, 0, 0, '[]', '[]', 1, 0, 0.0, 0, 0, 0, 3, NULL, 0, 0, NULL, '{"id":"li1"}', '', 'minified', NULL)""")
      sql.execSQL("""INSERT INTO library_state VALUES ('srv', 'lib1', 7, 1, 7, 7, NULL, NULL, NULL, '[]')""")
      sql.version = 1
    }
  }

  @Test
  fun upgradeFromV1KeepsTheCatalog() = runBlocking {
    v1File("replica-v1.db")
    val db = ReplicaDatabase.open(context, "replica-v1.db")
    try {
      assertEquals("Kept", db.items().get("srv", "li1")?.title)
      val state = db.libraryState().get("srv", "lib1")
      assertEquals(7L, state?.itemsReconciledAt)
      assertEquals(null, state?.episodesReconciledAt)
      assertTrue(db.episodes().forPodcast("srv", "li1").isEmpty())
    } finally {
      db.close()
    }
  }

  /** A file written by a newer schema: a version above ours and a table this version has never heard of. */
  private fun newerFile(name: String) {
    val file = context.getDatabasePath(name)
    file.parentFile?.mkdirs()
    file.delete()
    SQLiteDatabase.openOrCreateDatabase(file, null).use {
      it.execSQL("CREATE TABLE future_catalog (id TEXT PRIMARY KEY)")
      it.execSQL("INSERT INTO future_catalog VALUES ('x')")
      it.version = 3
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
