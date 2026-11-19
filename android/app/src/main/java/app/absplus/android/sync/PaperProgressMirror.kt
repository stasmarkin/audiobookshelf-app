package app.absplus.android.sync

import app.absplus.android.data.Book
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.Podcast
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.managers.LocalItemLocks
import app.absplus.android.media.MediaEventManager

/** Where accepted progress of a downloaded copy is also written during phase 1 (Paper stays readable on rollback). */
interface ProgressMirror {
  /**
   * Copies each row into the progress of the downloaded copy of its item on its server, if there is one. An existing
   * copy progress takes only [fields] (and `lastUpdate`); null copies every field. [unlessNewer]: an existing copy
   * progress written later than the row (by the player itself) is left as it is.
   */
  fun mirror(rows: Collection<MediaProgressEntity>, fields: Collection<String>? = null, unlessNewer: Boolean = false)

  /** Removes the progress of the downloaded copy of each row's item, if there is one. */
  fun remove(rows: Collection<MediaProgressEntity>)

  /** Server values that changed rows; a downloaded copy's listening history shows them (it did before offline-first). */
  fun noteServerValues(rows: Collection<MediaProgressEntity>) {}

  object None : ProgressMirror {
    override fun mirror(rows: Collection<MediaProgressEntity>, fields: Collection<String>?, unlessNewer: Boolean) {}
    override fun remove(rows: Collection<MediaProgressEntity>) {}
  }
}

/** The one entry that writes Room progress into Paper `LocalMediaProgress`. Blocking disk I/O: never on the main thread. */
object PaperProgressMirror : ProgressMirror {
  val MARK_FINISHED_FIELDS = listOf("isFinished", "progress", "currentTime", "finishedAt")
  val EBOOK_FIELDS = listOf("ebookLocation", "ebookProgress")

  @Volatile private var cachedCopies: Pair<Long, List<LocalLibraryItem>>? = null

  /**
   * Every downloaded copy, read from disk only after the list changed: a stream mirrors every 15 s, and a full pull can
   * take thousands of rows.
   */
  private fun copies(): List<LocalLibraryItem> {
    val version = DbManager.localLibraryItemsVersion
    cachedCopies?.let { (seen, list) -> if (seen == version) return list }
    return DeviceManager.dbManager.getLocalLibraryItems().also { cachedCopies = version to it }
  }

  override fun mirror(rows: Collection<MediaProgressEntity>, fields: Collection<String>?, unlessNewer: Boolean) {
    if (rows.isEmpty()) return
    val copies = copies()
    for (row in rows) {
      val (item, localEpisodeId) = copyOf(copies, row) ?: continue
      mirror(row, item, localEpisodeId, fields, unlessNewer)
    }
  }

  override fun noteServerValues(rows: Collection<MediaProgressEntity>) {
    if (rows.isEmpty()) return
    val copies = copies()
    for (row in rows) {
      if (copyOf(copies, row) == null) continue
      val progress = MediaProgress(UserDataStore.realServerProgressId(row.serverProgressId).orEmpty(), row.libraryItemId, row.episodeId.ifEmpty { null },
              row.duration, row.progress, row.currentTime, row.isFinished, row.ebookLocation, row.ebookProgress,
              row.lastUpdate, row.startedAt, row.finishedAt)
      // A no-op for an item never played here: the history exists only after playback
      MediaEventManager.syncEvent(progress, "Received from server")
    }
  }

  override fun remove(rows: Collection<MediaProgressEntity>) {
    if (rows.isEmpty()) return
    val copies = copies()
    for (row in rows) {
      val (item, localEpisodeId) = copyOf(copies, row) ?: continue
      DeviceManager.dbManager.removeLocalMediaProgressLocked(localProgressId(item, localEpisodeId))
    }
  }

  private fun mirror(row: MediaProgressEntity, item: LocalLibraryItem, localEpisodeId: String?, fields: Collection<String>?,
                     unlessNewer: Boolean): Unit = LocalItemLocks.withItem(item.id) {
    // The copies were read before the lock: an auto delete may have removed this one since, and its progress with it.
    // Checked before the progress lock, which the player's thread also takes
    if (copies().none { it.id == item.id }) return@withItem
    synchronized(DbManager.localMediaProgressLock) {
      val db = DeviceManager.dbManager
      val id = localProgressId(item, localEpisodeId)
      val existing = db.getLocalMediaProgress(id)
      if (unlessNewer && existing != null && existing.lastUpdate > row.lastUpdate) return@withItem
      val progress = if (existing == null) newProgress(id, item, localEpisodeId, row) else existing.also { lmp ->
        fun has(field: String) = fields == null || field in fields
        // A row created by an action alone has no duration yet; the copy knows it
        if (has("duration") && row.duration > 0) lmp.duration = row.duration
        if (has("progress")) lmp.progress = row.progress
        if (has("currentTime")) lmp.currentTime = row.currentTime
        if (has("isFinished")) lmp.isFinished = row.isFinished
        if (has("finishedAt")) lmp.finishedAt = row.finishedAt
        if (has("ebookLocation")) lmp.ebookLocation = row.ebookLocation
        if (has("ebookProgress")) lmp.ebookProgress = row.ebookProgress
        if (fields == null) lmp.startedAt = row.startedAt
        lmp.lastUpdate = row.lastUpdate
      }
      db.saveLocalMediaProgress(progress)
    }
  }

  /**
   * [progressItemKey]s of the server's Paper listening sessions not sent yet (a session is removed once `/sync` or
   * `local-all` accepted it).
   */
  fun unsentSessionKeys(serverConfigId: String): Set<String> =
          DeviceManager.dbManager.getPlaybackSessions()
                  .filter { it.serverConnectionConfigId == serverConfigId && !it.libraryItemId.isNullOrEmpty() }
                  .map { progressItemKey(it.libraryItemId!!, it.episodeId) }
                  .toSet()

  /** The copy of the row's item downloaded from the row's server and the local id of the episode; null without one. */
  private fun copyOf(copies: List<LocalLibraryItem>, row: MediaProgressEntity): Pair<LocalLibraryItem, String?>? {
    val item = copies.find { it.libraryItemId == row.libraryItemId && it.serverConnectionConfigId == row.serverConfigId } ?: return null
    if (row.episodeId.isEmpty()) return item to null
    val episode = (item.media as? Podcast)?.episodes?.find { it.serverEpisodeId == row.episodeId } ?: return null
    return item to episode.id
  }

  private fun localProgressId(item: LocalLibraryItem, localEpisodeId: String?) =
          if (localEpisodeId.isNullOrEmpty()) item.id else "${item.id}-$localEpisodeId"

  private fun newProgress(id: String, item: LocalLibraryItem, localEpisodeId: String?, row: MediaProgressEntity): LocalMediaProgress {
    val duration = row.duration.takeIf { it > 0 } ?: if (localEpisodeId == null) (item.media as? Book)?.duration ?: 0.0
    else (item.media as? Podcast)?.episodes?.find { it.id == localEpisodeId }?.duration ?: 0.0
    return LocalMediaProgress(
            id = id, localLibraryItemId = item.id, localEpisodeId = localEpisodeId, duration = duration,
            progress = row.progress, currentTime = row.currentTime, isFinished = row.isFinished,
            ebookLocation = row.ebookLocation, ebookProgress = row.ebookProgress, lastUpdate = row.lastUpdate,
            startedAt = row.startedAt, finishedAt = row.finishedAt, serverConnectionConfigId = item.serverConnectionConfigId,
            serverAddress = item.serverAddress, serverUserId = item.serverUserId, libraryItemId = item.libraryItemId,
            episodeId = row.episodeId.ifEmpty { null })
  }
}
