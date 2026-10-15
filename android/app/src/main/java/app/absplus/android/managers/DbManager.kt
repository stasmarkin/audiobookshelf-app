package app.absplus.android.managers

import android.content.Context
import android.util.Log
import app.absplus.android.data.*
import app.absplus.android.models.DownloadItem
import app.absplus.android.plugins.AbsLogger
import io.paperdb.Paper

class DbManager {
  val tag = "DbManager"

  companion object {
    private var isDbInitialized = false
    private val playbackSessionLock = Any()

    /** Changes with every write or removal of a local library item, so a reader can keep the list between them. */
    @Volatile var localLibraryItemsVersion = 0L
      private set

    fun localLibraryItemsChanged() {
      localLibraryItemsVersion++
    }

    fun initialize(ctx: Context) {
      if (isDbInitialized) return
      Paper.init(ctx)
      isDbInitialized = true
      Log.i("DbManager", "Initialized Paper db")
      // Every process entry point (Activity, player service, widget, downloads) initializes the db
      // first, so this is also where persistent diagnostics start (after Paper, for log migration)
      app.absplus.android.diagnostics.DiagnosticLog.init(ctx)
    }
  }

  fun getDeviceData(): DeviceData {
    return Paper.book("device").read("data")
            ?: DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
  }
  fun saveDeviceData(deviceData: DeviceData) {
    Paper.book("device").write("data", deviceData)
  }

  fun getLocalLibraryItems(mediaType: String? = null): MutableList<LocalLibraryItem> {
    val localLibraryItems: MutableList<LocalLibraryItem> = mutableListOf()
    Paper.book("localLibraryItems").allKeys.forEach {
      val localLibraryItem: LocalLibraryItem? = Paper.book("localLibraryItems").read(it)
      if (localLibraryItem != null &&
                      (mediaType.isNullOrEmpty() || mediaType == localLibraryItem.mediaType)
      ) {
        localLibraryItems.add(localLibraryItem)
      }
    }
    return localLibraryItems
  }

  fun getLocalLibraryItemsInFolder(folderId: String): List<LocalLibraryItem> {
    val localLibraryItems = getLocalLibraryItems()
    return localLibraryItems.filter { it.folderId == folderId }
  }

  fun getLocalLibraryItemByLId(libraryItemId: String): LocalLibraryItem? {
    return getLocalLibraryItems().find { it.libraryItemId == libraryItemId }
  }

  // Cross-library fallback: the same physical book scanned into two libraries has a different
  // libraryItemId in each, but the server preserves the same filesystem ino for both.
  fun getLocalLibraryItemByIno(ino: String?): LocalLibraryItem? {
    if (ino.isNullOrEmpty()) return null
    return getLocalLibraryItems().find { it.ino == ino }
  }

  fun getLocalLibraryItemByLIdOrIno(libraryItemId: String, ino: String?): LocalLibraryItem? {
    return getLocalLibraryItemByLId(libraryItemId) ?: getLocalLibraryItemByIno(ino)
  }

  fun getLocalLibraryItem(localLibraryItemId: String): LocalLibraryItem? {
    return Paper.book("localLibraryItems").read(localLibraryItemId)
  }

  fun getLocalLibraryItemWithEpisode(podcastEpisodeId: String): LibraryItemWithEpisode? {
    var podcastEpisode: PodcastEpisode? = null
    val localLibraryItem =
            getLocalLibraryItems("podcast").find { localLibraryItem ->
              val podcast = localLibraryItem.media as Podcast
              podcastEpisode = podcast.episodes?.find { it.id == podcastEpisodeId }
              podcastEpisode != null
            }
    return if (localLibraryItem != null) {
      LibraryItemWithEpisode(localLibraryItem, podcastEpisode!!)
    } else {
      null
    }
  }

  fun removeLocalLibraryItem(localLibraryItemId: String) {
    Paper.book("localLibraryItems").delete(localLibraryItemId)
    localLibraryItemsChanged()
  }

  fun saveLocalLibraryItems(localLibraryItems: List<LocalLibraryItem>) {
    localLibraryItems.map { Paper.book("localLibraryItems").write(it.id, it) }
    localLibraryItemsChanged()
  }

  fun saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) {
    Paper.book("localLibraryItems").write(localLibraryItem.id, localLibraryItem)
    localLibraryItemsChanged()
  }

  fun saveLocalFolder(localFolder: LocalFolder) {
    Paper.book("localFolders").write(localFolder.id, localFolder)
  }

  fun getLocalFolder(folderId: String): LocalFolder? {
    return Paper.book("localFolders").read(folderId)
  }

  fun getAllLocalFolders(): List<LocalFolder> {
    val localFolders: MutableList<LocalFolder> = mutableListOf()
    Paper.book("localFolders").allKeys.forEach { localFolderId ->
      Paper.book("localFolders").read<LocalFolder>(localFolderId)?.let { localFolders.add(it) }
    }
    return localFolders
  }

  fun removeLocalFolder(folderId: String) {
    val localLibraryItems = getLocalLibraryItemsInFolder(folderId)
    localLibraryItems.forEach { Paper.book("localLibraryItems").delete(it.id) }
    localLibraryItemsChanged()
    Paper.book("localFolders").delete(folderId)
  }

  fun saveDownloadItem(downloadItem: DownloadItem) {
    Paper.book("downloadItems").write(downloadItem.id, downloadItem)
  }

  fun removeDownloadItem(downloadItemId: String) {
    Paper.book("downloadItems").delete(downloadItemId)
  }

  fun getDownloadItems(): List<DownloadItem> {
    val downloadItems: MutableList<DownloadItem> = mutableListOf()
    val book = Paper.book("downloadItems")
    book.allKeys.forEach { downloadItemId ->
      book.read<DownloadItem>(downloadItemId)?.let { stored ->
        val compact = stored.withoutPodcastEpisodes()
        // Rewrite entries saved by older versions so they shrink on disk too
        if (compact !== stored) book.write(downloadItemId, compact)
        downloadItems.add(compact)
      }
    }
    return downloadItems
  }

  /**
   * Validate persisted download queue entries before restoring them.
   *
   * Queue entries can become incompatible or malformed, for example after an app update. If any
   * entry cannot be read or fails the checks required for queue restoration, the entire persisted
   * queue is cleared so startup can continue without restoring invalid data.
   *
   * @return true if the queue passes validation or was successfully cleared; false if an invalid
   * queue could not be cleared
   */
  fun ensureValidDownloadQueue(): Boolean {
    val downloadBook = Paper.book("downloadItems")
    val queueIsValid =
            try {
              // One entry at a time: entries saved before compaction can each hold a whole podcast
              downloadBook.allKeys
                      .asSequence()
                      .map { downloadItemId ->
                        val item =
                                downloadBook.read<DownloadItem>(downloadItemId)
                                        ?: throw IllegalStateException(
                                                "Download queue entry $downloadItemId could not be read"
                                        )
                        if (item.id != downloadItemId) {
                          throw IllegalStateException(
                                  "Download queue entry $downloadItemId has a mismatched item ID"
                          )
                        }
                        item
                      }
                      .all { item ->
                        listOf<String?>(item.id, item.libraryItemId, item.localFolder.id).all {
                          !it.isNullOrBlank()
                        } &&
                                item.downloadItemParts.isNotEmpty() &&
                                item.downloadItemParts.all { part ->
                                  listOf<String?>(
                                                  part.id,
                                                  part.downloadItemId,
                                                  part.filename,
                                                  part.destinationPath,
                                                  part.finalDestinationPath,
                                                  part.serverPath,
                                                  part.localFolderId
                                          )
                                          .all { !it.isNullOrBlank() } &&
                                          part.downloadItemId == item.id
                                }
                      }
            } catch (readError: Exception) {
              AbsLogger.error(
                      tag,
                      "Could not read persisted download queue; clearing queue (${readError.message})"
              )
              false
            }

    if (queueIsValid) return true

    AbsLogger.error(tag, "Persisted download queue contains invalid data; clearing queue")
    return try {
      downloadBook.destroy()
      val remainingKeys = downloadBook.allKeys
      if (remainingKeys.isEmpty()) {
        AbsLogger.info(tag, "Cleared invalid persisted download queue")
        true
      } else {
        AbsLogger.error(
                tag,
                "Invalid persisted download queue still contains ${remainingKeys.size} entries after clearing"
        )
        false
      }
    } catch (clearError: Exception) {
      AbsLogger.error(
              tag,
              "Could not clear invalid persisted download queue (${clearError.message})"
      )
      false
    }
  }

  fun saveLocalMediaProgress(mediaProgress: LocalMediaProgress) {
    Paper.book("localMediaProgress").write(mediaProgress.id, mediaProgress)
  }
  // For books this will just be the localLibraryItemId for podcast episodes this will be
  // "{localLibraryItemId}-{episodeId}"
  fun getLocalMediaProgress(localMediaProgressId: String): LocalMediaProgress? {
    return Paper.book("localMediaProgress").read(localMediaProgressId)
  }
  fun getAllLocalMediaProgress(): List<LocalMediaProgress> {
    val mediaProgress: MutableList<LocalMediaProgress> = mutableListOf()
    Paper.book("localMediaProgress").allKeys.forEach { localMediaProgressId ->
      Paper.book("localMediaProgress").read<LocalMediaProgress>(localMediaProgressId)?.let {
        mediaProgress.add(it)
      }
    }
    return mediaProgress
  }
  fun removeLocalMediaProgress(localMediaProgressId: String) {
    Paper.book("localMediaProgress").delete(localMediaProgressId)
  }

  fun removeAllLocalMediaProgress() {
    Paper.book("localMediaProgress").destroy()
  }

  // Make sure all local file ids still exist
  fun cleanLocalLibraryItems(context: Context) {
    val localLibraryItems = getLocalLibraryItems()

    localLibraryItems.forEach { lli ->
      var hasUpdates = false

      // Check local files
      lli.localFiles =
              lli.localFiles.filter { localFile ->
                val exists = localFile.exists(context)
                if (!exists) {
                  Log.d(
                          tag,
                          "cleanLocalLibraryItems: Local file ${localFile.absolutePath} was removed from library item ${lli.media.metadata.title}"
                  )
                  hasUpdates = true
                }
                exists
              } as
                      MutableList<LocalFile>

      // Check audio tracks and episodes
      if (lli.isPodcast) {
        val podcast = lli.media as Podcast
        podcast.episodes =
                podcast.episodes?.filter { ep ->
                  if (lli.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } == null) {
                    Log.d(
                            tag,
                            "cleanLocalLibraryItems: Podcast episode ${ep.title} was removed from library item ${lli.media.metadata.title}"
                    )
                    hasUpdates = true
                  }
                  ep.audioTrack != null &&
                          lli.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } != null
                } as
                        MutableList<PodcastEpisode>
      } else {
        val book = lli.media as Book
        book.tracks =
                book.tracks?.filter { track ->
                  if (lli.localFiles.find { lf -> lf.id == track.localFileId } == null) {
                    Log.d(
                            tag,
                            "cleanLocalLibraryItems: Audio track ${track.title} was removed from library item ${lli.media.metadata.title}"
                    )
                    hasUpdates = true
                  }
                  lli.localFiles.find { lf -> lf.id == track.localFileId } != null
                } as
                        MutableList<AudioTrack>
      }

      // Check cover still there
      lli.coverAbsolutePath?.let {
        val coverExists =
                lli.localFiles.any { localFile ->
                  localFile.absolutePath == it && localFile.exists(context)
                }
        if (!coverExists) {
          Log.d(
                  tag,
                  "cleanLocalLibraryItems: Cover $it was removed from library item ${lli.media.metadata.title}"
          )
          lli.coverAbsolutePath = null
          lli.coverContentUrl = null
          hasUpdates = true
        }
      }

      if (lli.serverConnectionConfigId == null) {
        // Local-only item support was removed in app version 0.9.67, remove any remaining local
        // only items beginning in 0.9.80
        Log.d(tag, "cleanLocalLibraryItems: Local only item ${lli.id} - removing from ABS")
        Paper.book("localLibraryItems").delete(lli.id)
        localLibraryItemsChanged()
      } else if (hasUpdates) {
        Log.d(tag, "cleanLocalLibraryItems: Saving local library item ${lli.id}")
        Paper.book("localLibraryItems").write(lli.id, lli)
        localLibraryItemsChanged()
      }
    }
  }

  // Remove any local media progress where the local media item is not found
  fun cleanLocalMediaProgress() {
    val localMediaProgress = getAllLocalMediaProgress()
    val localLibraryItems = getLocalLibraryItems()
    localMediaProgress.forEach {
      val matchingLLI = localLibraryItems.find { lli -> lli.id == it.localLibraryItemId }
      if (!it.id.startsWith("local")) {
        // A bug on the server when syncing local media progress was replacing the media progress id
        // causing duplicate progress. Remove them.
        Log.d(
                tag,
                "cleanLocalMediaProgress: Invalid local media progress does not start with 'local' (fixed on server 2.0.24)"
        )
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI == null) {
        Log.d(
                tag,
                "cleanLocalMediaProgress: No matching local library item for local media progress ${it.id} - removing"
        )
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI.isPodcast) {
        if (it.localEpisodeId.isNullOrEmpty()) {
          Log.d(tag, "cleanLocalMediaProgress: Podcast media progress has no episode id - removing")
          Paper.book("localMediaProgress").delete(it.id)
        } else {
          val podcast = matchingLLI.media as Podcast
          val matchingLEp = podcast.episodes?.find { ep -> ep.id == it.localEpisodeId }
          if (matchingLEp == null) {
            Log.d(
                    tag,
                    "cleanLocalMediaProgress: Podcast media progress for episode ${it.localEpisodeId} not found - removing"
            )
            Paper.book("localMediaProgress").delete(it.id)
          }
        }
      }
    }
  }

  fun saveMediaItemHistory(mediaItemHistory: MediaItemHistory) {
    Paper.book("mediaItemHistory").write(mediaItemHistory.id, mediaItemHistory)
  }
  fun getMediaItemHistory(id: String): MediaItemHistory? {
    return Paper.book("mediaItemHistory").read(id)
  }

  fun savePlaybackSession(playbackSession: PlaybackSession) {
    synchronized(playbackSessionLock) { Paper.book("playbackSession").write(playbackSession.id, playbackSession) }
  }
  fun getPlaybackSession(playbackSessionId: String): PlaybackSession? =
          Paper.book("playbackSession").read<PlaybackSession>(playbackSessionId)
  /** Removes the session only if it is still the one that was sent: a later tick or more time is sent next time. */
  fun removePlaybackSessionIfUnchanged(sent: PlaybackSession): Boolean = synchronized(playbackSessionLock) {
    val saved = getPlaybackSession(sent.id) ?: return false
    if (saved.updatedAt != sent.updatedAt || saved.timeListening != sent.timeListening) return false
    Paper.book("playbackSession").delete(sent.id)
    true
  }

  /**
   * A stream's listening not settled with the server yet, as the offline session it would become; kept apart from
   * `playbackSession`, which is sent (also by older versions) while that time may still reach the server by `/sync`.
   */
  fun saveUnsettledListening(key: String, session: PlaybackSession) {
    Paper.book("listeningLedger").write(key, session)
  }
  fun getUnsettledListening(key: String): PlaybackSession? = Paper.book("listeningLedger").read<PlaybackSession>(key)
  fun removeUnsettledListening(key: String) {
    Paper.book("listeningLedger").delete(key)
  }
  fun unsettledListeningKeys(): List<String> = Paper.book("listeningLedger").allKeys
  fun removePlaybackSession(playbackSessionId: String) {
    Paper.book("playbackSession").delete(playbackSessionId)
  }
  fun getPlaybackSessions(): List<PlaybackSession> {
    val sessions: MutableList<PlaybackSession> = mutableListOf()
    Paper.book("playbackSession").allKeys.forEach { playbackSessionId ->
      Paper.book("playbackSession").read<PlaybackSession>(playbackSessionId)?.let {
        sessions.add(it)
      }
    }
    return sessions
  }
  // App logs now live in the rolling diagnostic log (DiagnosticLog), which migrates the old "log" book once
}
