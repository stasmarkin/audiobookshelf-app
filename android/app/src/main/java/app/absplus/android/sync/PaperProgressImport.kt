package app.absplus.android.sync

import android.content.SharedPreferences
import androidx.room.withTransaction
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.db.userdata.UserDataDatabase
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Brings Paper `LocalMediaProgress` that is newer than userdata.db into it: on every start (time spent on a
 * rolled-back version, or before this release) and for a copy relinked to another server item. Each imported key is
 * remembered per server until that server's first successful user reconcile decides whether the server needs it
 * ([UserDataReconciler]); until then the row is never deleted for being missing on the server.
 */
class PaperProgressImport(private val db: UserDataDatabase, private val prefs: SharedPreferences) {
  private val mapper = jacksonObjectMapper()
  private val lock = Any()

  /** Imports every server-linked progress newer than its row; a pending row is never overwritten. @return how many */
  suspend fun import(paper: List<LocalMediaProgress>): Int {
    val imported = db.withTransaction {
      val dao = db.mediaProgress()
      paper.mapNotNull { lmp ->
        val serverConfigId = lmp.serverConnectionConfigId?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val libraryItemId = lmp.libraryItemId?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val episodeId = lmp.episodeId.orEmpty()
        val row = dao.get(serverConfigId, libraryItemId, episodeId)
        if (row != null && (row.pending || lmp.lastUpdate <= row.lastUpdate)) return@mapNotNull null
        if (lmp.progress.isNaN() || lmp.currentTime.isNaN()) return@mapNotNull null
        dao.upsert(MediaProgressEntity(
                serverConfigId = serverConfigId, libraryItemId = libraryItemId, episodeId = episodeId,
                serverProgressId = row?.serverProgressId, duration = lmp.duration, progress = lmp.progress,
                currentTime = lmp.currentTime, isFinished = lmp.isFinished,
                hideFromContinueListening = row?.hideFromContinueListening ?: false, ebookLocation = lmp.ebookLocation,
                ebookProgress = lmp.ebookProgress, lastUpdate = lmp.lastUpdate, startedAt = lmp.startedAt,
                finishedAt = lmp.finishedAt, source = PROGRESS_SOURCE_DEVICE, pending = false))
        serverConfigId to progressItemKey(libraryItemId, episodeId)
      }.also { keys ->
        // Before the rows commit: a crash in between leaves a remembered key without a newer row, which is harmless,
        // never a newer row the next reconcile could delete as missing on the server
        if (keys.isNotEmpty()) synchronized(lock) {
          val all = readUnsettled()
          keys.forEach { (server, key) -> all.getOrPut(server) { mutableSetOf() }.add(key) }
          writeUnsettled(all)
        }
      }
    }
    return imported.size
  }

  /** Imported keys of [serverConfigId] its server has not been reconciled against yet. */
  fun unsettled(serverConfigId: String): Set<String> = synchronized(lock) { readUnsettled()[serverConfigId].orEmpty().toSet() }

  /** After a successful reconcile of [serverConfigId]: forgets [keys] (those the reconcile saw), not later imports. */
  fun settle(serverConfigId: String, keys: Set<String>) = synchronized(lock) {
    val all = readUnsettled()
    val left = all[serverConfigId]?.apply { removeAll(keys) } ?: return@synchronized
    if (left.isEmpty()) all.remove(serverConfigId)
    writeUnsettled(all)
  }

  private fun readUnsettled(): MutableMap<String, MutableSet<String>> {
    val json = prefs.getString(KEY_UNSETTLED, null) ?: return mutableMapOf()
    return try {
      mapper.readValue<Map<String, List<String>>>(json).mapValuesTo(mutableMapOf()) { it.value.toMutableSet() }
    } catch (e: Exception) {
      mutableMapOf()
    }
  }

  private fun writeUnsettled(all: Map<String, Set<String>>) {
    prefs.edit().putString(KEY_UNSETTLED, mapper.writeValueAsString(all)).commit()
  }

  companion object {
    const val PREFS = "absplus_sync"
    const val KEY_UNSETTLED = "paperImportUnsettled"
    /** What an imported row that is newer than the server, or missing there, sends. */
    val IMPORT_FIELDS = listOf("duration", "progress", "currentTime", "isFinished", "ebookLocation", "ebookProgress")
  }
}
