package app.absplus.android.sync

import app.absplus.android.db.userdata.BookmarkEntity
import app.absplus.android.db.userdata.MediaProgressEntity
import org.json.JSONArray
import org.json.JSONObject

/** The user's data as the WebView reads it: progress in the server's `MediaProgress` shape, as `/api/me` has it. */
object UserDataJson {
  fun progress(row: MediaProgressEntity): JSONObject = JSONObject()
          .put("id", row.serverProgressId ?: JSONObject.NULL)
          .put("libraryItemId", row.libraryItemId)
          .put("episodeId", row.episodeId.ifEmpty { null } ?: JSONObject.NULL)
          .put("duration", row.duration)
          .put("progress", row.progress)
          .put("currentTime", row.currentTime)
          .put("isFinished", row.isFinished)
          .put("hideFromContinueListening", row.hideFromContinueListening)
          .put("ebookLocation", row.ebookLocation ?: JSONObject.NULL)
          .put("ebookProgress", row.ebookProgress ?: JSONObject.NULL)
          .put("lastUpdate", row.lastUpdate)
          .put("startedAt", row.startedAt)
          .put("finishedAt", row.finishedAt ?: JSONObject.NULL)

  fun progressList(rows: List<MediaProgressEntity>): JSONArray = JSONArray().also { list -> rows.forEach { list.put(progress(it)) } }

  fun bookmarks(rows: List<BookmarkEntity>): JSONArray = JSONArray().also { list ->
    rows.forEach {
      list.put(JSONObject().put("libraryItemId", it.libraryItemId).put("time", it.time).put("title", it.title).put("createdAt", it.createdAt))
    }
  }

  /** [userJson]: the stored user object, null when none is known or it cannot be read. */
  fun userData(userJson: String?, progress: List<MediaProgressEntity>, bookmarks: List<BookmarkEntity>): JSONObject {
    val user = userJson?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    return JSONObject()
            .put("user", user ?: JSONObject.NULL)
            .put("mediaProgress", progressList(progress))
            .put("bookmarks", bookmarks(bookmarks))
  }
}
