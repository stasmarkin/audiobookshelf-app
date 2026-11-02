package app.absplus.android.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * userdata.db version 1 is fixed for phase 1: a rollback to the previous app version has to open the same schema.
 * Reads the committed export from disk (the working directory of unit tests is android/app).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDataSchemaTest {
  private val schemaFile = File("schemas/app.absplus.android.db.userdata.UserDataDatabase/1.json")

  @Test
  fun schemaV1IsExported() {
    assertTrue("missing ${schemaFile.absolutePath}", schemaFile.isFile)
    val database = JSONObject(schemaFile.readText()).getJSONObject("database")
    assertEquals(1, database.getInt("version"))

    val entities = database.getJSONArray("entities")
    val columns = (0 until entities.length()).associate { i ->
      val entity = entities.getJSONObject(i)
      val fields = entity.getJSONArray("fields")
      entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }.toSet()
    }
    assertEquals(setOf("media_progress", "bookmarks", "outbox", "sync_state", "library_sync_state"), columns.keys)
    assertTrue(columns.getValue("media_progress").containsAll(listOf(
            "serverConfigId", "libraryItemId", "episodeId", "serverProgressId", "duration", "progress", "currentTime",
            "isFinished", "hideFromContinueListening", "ebookLocation", "ebookProgress", "lastUpdate", "startedAt",
            "finishedAt", "source", "pending")))
    assertTrue(columns.getValue("bookmarks").containsAll(listOf(
            "serverConfigId", "libraryItemId", "time", "title", "createdAt", "deletedAt", "pending")))
    assertTrue(columns.getValue("outbox").containsAll(listOf(
            "id", "serverConfigId", "kind", "itemKey", "payload", "createdAt", "attempts", "lastError", "nextAttemptAt")))
    assertTrue(columns.getValue("sync_state").containsAll(listOf("serverConfigId", "lastMeAt", "userJson", "schemaVersion")))
    assertTrue(columns.getValue("library_sync_state").containsAll(listOf(
            "serverConfigId", "libraryId", "lastReconcileAt", "lastReconcileOk", "lastError")))
  }
}
