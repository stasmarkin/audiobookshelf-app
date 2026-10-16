package app.absplus.android.managers

import android.content.Context
import android.os.Environment
import app.absplus.android.data.*
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.DownloadStorageSetting
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.services.DownloadServiceHost
import java.io.File

object LibraryItemDownloadStarter {
  private const val tag = "AbsDownloader"
  const val STORAGE_UNAVAILABLE_MESSAGE = "SD card not available"
  const val FOLDER_NOT_FOUND_MESSAGE = "Local Folder Not Found"
  const val ANOTHER_SERVER_MESSAGE = "This item is already downloaded from another server. Remove that download first."

  fun startPodcastEpisodeDownload(context: Context, libraryItem: LibraryItem, episode: PodcastEpisode, callback: (String?) -> Unit) {
    if (libraryItem.mediaType != "podcast") {
      callback("Invalid library item not a podcast")
      return
    }
    startLibraryItemDownload(context, libraryItem, null, episode, callback)
  }

  private fun downloadedFromAnotherServer(libraryItemId: String): Boolean {
    val user = DeviceManager.serverUserId
    val config = DeviceManager.serverConnectionConfigId
    val local = DeviceManager.dbManager.getLocalLibraryItem("local_$libraryItemId")
    if (local != null && AppStorage.isAppStorage(local.folderId) &&
            !DownloadDestinationGuard.sameServer(local.serverUserId, local.serverConnectionConfigId, user, config)) return true
    return DeviceManager.dbManager.getDownloadItems().any {
      it.libraryItemId == libraryItemId && it.isAppStorage &&
              !DownloadDestinationGuard.sameServer(it.serverUserId, it.serverConnectionConfigId, user, config)
    }
  }

  // Item filenames could be the same if they are in sub-folders, this will make them unique
  private fun getFilenameFromRelPath(relPath: String): String {
    var cleanedRelPath = relPath.replace("\\", "_").replace("/", "_")
    cleanedRelPath = cleanStringForFileSystem(cleanedRelPath)
    return if (cleanedRelPath.startsWith("_")) cleanedRelPath.substring(1) else cleanedRelPath
  }

  // Replace characters that cant be used in the file system
  // Reserved characters: ?:\"*|/\\<>
  private fun cleanStringForFileSystem(str:String):String {
    val reservedCharacters = listOf("?", "\"", "*", "|", "/", "\\", "<", ">")
    var newTitle = str
    newTitle = newTitle.replace(":", " -") // Special case replace : with -

    reservedCharacters.forEach {
      newTitle = newTitle.replace(it, "")
    }
    return newTitle
  }

  fun startLibraryItemDownload(
          context: Context,
          libraryItem: LibraryItem,
          explicitFolderId: String?,
          episode: PodcastEpisode?,
          callback: (String?) -> Unit
  ) {
    if (!DownloadIdentifiers.isSafe(libraryItem.id) ||
            (episode != null && !DownloadIdentifiers.isSafe(episode.id))) {
      callback("Invalid download identifier")
      return
    }
    DownloadServiceHost.enqueueResolved(context, "local_${libraryItem.id}", { buildDownloadItem(context, libraryItem, explicitFolderId, episode) }, callback)
  }

  /** Runs under the item's lock, so the folder choice and the queued item see the same local item. */
  private fun buildDownloadItem(
          context: Context,
          libraryItem: LibraryItem,
          explicitFolderId: String?,
          episode: PodcastEpisode?
  ): Pair<DownloadItem?, String?> {
    val existing = DeviceManager.dbManager.getLocalLibraryItemByLId(libraryItem.id)
    val queuedFolderId = DownloadServiceHost.queuedFolderId(libraryItem.id)
    val setting = DownloadStorageSetting.parse(DeviceManager.deviceData.deviceSettings?.downloadStorage)
    val probe = AndroidVolumeProbe(context)
    val resolution = DownloadFolderResolver.resolveFolder(libraryItem.mediaType, explicitFolderId, existing, queuedFolderId, setting, probe) {
      DeviceManager.dbManager.getLocalFolder(it)
    }
    val localFolder = when (resolution) {
      is FolderResolution.NotFound -> return null to FOLDER_NOT_FOUND_MESSAGE
      is FolderResolution.Unavailable -> {
        AbsLogger.info(tag, "Download of ${libraryItem.id} not started: download storage unavailable")
        return null to STORAGE_UNAVAILABLE_MESSAGE
      }
      is FolderResolution.Create -> resolution.folder
      is FolderResolution.Existing -> resolution.folder
    }

    val isAppStorage = AppStorage.isAppStorage(localFolder.id)
    // App storage keeps one folder and one local item per library item id, on whichever volume it is. The same id from
    // another server would write into the first server's download (R29-N02): refuse it instead of merging or replacing
    if (isAppStorage && downloadedFromAnotherServer(libraryItem.id)) return null to ANOTHER_SERVER_MESSAGE
    var appItemFolderPath = ""
    val tempFolderPath: String
    if (isAppStorage) {
      // An SD card is bound to the app (marker + token) before its first download; another card is never written to
      if (!AppStorage.ensureRegistered(localFolder.id, probe)) return null to STORAGE_UNAVAILABLE_MESSAGE
      val downloadsRoot = AppStorage.downloadsRoot(localFolder, probe)
      val stagingRoot = AppStorage.stagingRoot(localFolder, probe)
      if (downloadsRoot == null || stagingRoot == null) {
        AbsLogger.info(tag, "Download of ${libraryItem.id} not started: ${localFolder.id} went away")
        return null to STORAGE_UNAVAILABLE_MESSAGE
      }
      appItemFolderPath = File(downloadsRoot, libraryItem.id).path
      tempFolderPath = File(stagingRoot, libraryItem.id).path
      if (resolution is FolderResolution.Create) {
        AbsLogger.info(tag, "Creating download folder ${localFolder.id}")
        DeviceManager.dbManager.saveLocalFolder(localFolder)
      }
    } else {
      tempFolderPath = "${context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir}/download-staging/${libraryItem.id}"
    }


    if (libraryItem.mediaType == "book") {
      val bookTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)
      val bookAuthor = cleanStringForFileSystem(libraryItem.media.metadata.getAuthorDisplayName())

      val tracks = libraryItem.media.getAudioTracks()
      AbsLogger.info(tag, "Queueing library item download with ${tracks.size} files")
      val itemSubfolder = "$bookAuthor/$bookTitle"
      val itemFolderPath = if (isAppStorage) appItemFolderPath else "${localFolder.absolutePath}/$itemSubfolder"
      val downloadItem = DownloadItem(libraryItem.id, libraryItem.id, null, libraryItem.userMediaProgress,DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, bookTitle, itemSubfolder, libraryItem.media, mutableListOf(), libraryItem.ino)

      val book = libraryItem.media as Book
      book.ebookFile?.let { ebookFile ->
        val fileSize = ebookFile.metadata?.size ?: 0
        val serverPath = "/api/items/${libraryItem.id}/file/${ebookFile.ino}/download"
        val destinationFilename = getFilenameFromRelPath(ebookFile.metadata?.relPath ?: "")
        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename.part")

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,ebookFile,null,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      // Create download item part for each audio track
      val audioFiles = (libraryItem.media as Book).audioFiles ?: mutableListOf()
      tracks.forEach { audioTrack ->
        val fileSize = audioTrack.metadata?.size ?: 0

        // TODO: Currently file ino is only stored on AudioFile. This should be updated server side to be in FileMetadata or on the AudioTrack
        val audioFileIno = audioFiles.find { it.metadata.path == audioTrack.metadata?.path }?.ino

        val serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
        val destinationFilename = getFilenameFromRelPath(audioTrack.relPath)

        val finalDestinationFile = File("$itemFolderPath/$destinationFilename")
        val destinationFile = File("$tempFolderPath/$destinationFilename.part")

        val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, fileSize, destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,audioTrack,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      if (downloadItem.downloadItemParts.isNotEmpty()) {
        // Add cover download item
        if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
          val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
          val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

          val serverPath = "/api/items/${libraryItem.id}/cover"
          val destinationFilename = "cover-${libraryItem.id}.jpg"
          val destinationFile = File("$tempFolderPath/$destinationFilename.part")
          val finalDestinationFile = File("$itemFolderPath/$destinationFilename")

          val downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename, coverFileSize,  destinationFile,finalDestinationFile,itemSubfolder,serverPath,localFolder,null,null,null)
          downloadItem.downloadItemParts.add(downloadItemPart)
        }

        return downloadItem to null
      }
      return null to "No downloadable files found"
    } else {
      // Podcast episode download
      val podcastTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)

      val audioTrack = episode?.audioTrack
      val audioFileIno = episode?.audioFile?.ino
      // Incomplete metadata would name the destination after the item folder itself (empty filename) or request
      // file "null"; refuse before anything is queued or written
      val episodeFilename = getFilenameFromRelPath(audioTrack?.relPath ?: "")
      if (episode == null || audioTrack == null || audioFileIno.isNullOrBlank() || episodeFilename.isBlank() ||
              episodeFilename == "." || episodeFilename == "..") {
        return null to "Episode has no downloadable audio file"
      }
      val fileSize = audioTrack?.metadata?.size ?: 0

      AbsLogger.info(tag, "Queueing podcast episode download")
      val itemFolderPath = if (isAppStorage) appItemFolderPath else "${localFolder.absolutePath}/$podcastTitle"
      val downloadItemId = "${libraryItem.id}-${episode?.id}"
      val downloadItem = DownloadItem(downloadItemId, libraryItem.id, episode?.id, libraryItem.userMediaProgress, DeviceManager.serverConnectionConfig?.id ?: "", DeviceManager.serverAddress, DeviceManager.serverUserId, libraryItem.mediaType, itemFolderPath, localFolder, podcastTitle, podcastTitle, libraryItem.media, mutableListOf(), libraryItem.ino)

      var serverPath = "/api/items/${libraryItem.id}/file/${audioFileIno}/download"
      var destinationFilename = getFilenameFromRelPath(audioTrack?.relPath ?: "")

      var destinationFile = File("$tempFolderPath/$destinationFilename.part")
      var finalDestinationFile = File("$itemFolderPath/$destinationFilename")
      var downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,fileSize, destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,audioTrack,episode)
      downloadItem.downloadItemParts.add(downloadItemPart)

      if (libraryItem.media.coverPath != null && libraryItem.media.coverPath?.isNotEmpty() == true) {
        val coverLibraryFile = libraryItem.libraryFiles?.find { it.metadata.path == libraryItem.media.coverPath }
        val coverFileSize = coverLibraryFile?.metadata?.size ?: 0

        serverPath = "/api/items/${libraryItem.id}/cover"
        destinationFilename = "cover.jpg"

        destinationFile = File("$tempFolderPath/$destinationFilename.part")
        finalDestinationFile = File("$itemFolderPath/$destinationFilename")

        downloadItemPart = DownloadItemPart.make(downloadItem.id, destinationFilename,coverFileSize,destinationFile,finalDestinationFile,podcastTitle,serverPath,localFolder,null,null,null)
        downloadItem.downloadItemParts.add(downloadItemPart)
      }

      return downloadItem to null
    }
  }
}
