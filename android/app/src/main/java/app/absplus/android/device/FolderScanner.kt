package app.absplus.android.device

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.*
import app.absplus.android.data.*
import app.absplus.android.managers.LocalItemLocks
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import app.absplus.android.plugins.AbsLogger
import java.io.File

/** Creates local-library records from a completed download manifest. */
class FolderScanner(private val ctx: Context) {
  private val tag = "FolderScanner"

  data class DownloadItemScanResult(
          val localLibraryItem: LocalLibraryItem,
          var localMediaProgress: LocalMediaProgress?
  )

  private fun localLibraryItemId(mediaItemId: String) =
          "local_${DeviceManager.getBase64Id(mediaItemId)}"

  private fun createLocalFile(
          part: DownloadItemPart,
          externalFile: DocumentFile? = null
  ): LocalFile? {
    if (part.isAppStorage) {
      val file = File(part.finalDestinationPath)
      if (!file.exists()) return null
      return LocalFile(
              DeviceManager.getBase64Id(file.name),
              file.name,
              Uri.fromFile(file).toString(),
              file.getBasePath(ctx),
              file.absolutePath,
              file.mimeType,
              file.length()
      )
    }

    part.completedDestinationUri?.let { contentUrl ->
      val uri = Uri.parse(contentUrl)
      val size =
              try {
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                  descriptor.statSize.coerceAtLeast(0L)
                }
                        ?: 0L
              } catch (e: Exception) {
                Log.e(tag, "Could not open completed SAF file: $contentUrl", e)
                return null
              }
      // Android 10 DownloadsProvider may not reconstruct a DocumentFile for an audio URI even
      // though the URI remains readable. Keep the URI as the authoritative local-file location.
      return LocalFile(
              DeviceManager.getBase64Id(contentUrl),
              part.filename,
              contentUrl,
              part.localFolderName,
              part.finalDestinationPath,
              mimeTypeFor(part),
              size
      )
    }

    // Do not reconstruct a DocumentFile from an absolute path: on Android 10 that becomes a
    // file:// URI, which DocumentsContract rejects. The caller resolves this from the persisted
    // SAF tree grant instead.
    val document = externalFile
    if (document == null || !document.exists()) {
      Log.e(tag, "Could not resolve downloaded SAF file: ${part.finalDestinationPath}")
      return null
    }
    return LocalFile(
            DeviceManager.getBase64Id(document.id),
            document.name,
            document.uri.toString(),
            document.getBasePath(ctx),
            document.getAbsolutePath(ctx),
            document.mimeType,
            document.length()
    )
  }

  private fun newLocalLibraryItem(
          id: String,
          downloadItem: DownloadItem,
          basePath: String,
          absolutePath: String,
          contentUrl: String
  ) =
          LocalLibraryItem(
                  id,
                  downloadItem.localFolder.id,
                  basePath,
                  absolutePath,
                  contentUrl,
                  false,
                  downloadItem.mediaType,
                  downloadItem.media.getLocalCopy(),
                  mutableListOf(),
                  null,
                  null,
                  true,
                  downloadItem.serverConnectionConfigId,
                  downloadItem.serverAddress,
                  downloadItem.serverUserId,
                  downloadItem.libraryItemId,
                  downloadItem.ino
          )

  /**
   * Runs [scan] (read the saved item, update it, save it) under the item's lock, so a concurrent
   * write such as an episode delete is not overwritten; [callback] runs after the lock is released.
   */
  private fun scanLocked(
          id: String,
          callback: (DownloadItemScanResult?) -> Unit,
          scan: ((DownloadItemScanResult?) -> Unit) -> Unit
  ) {
    var result: DownloadItemScanResult? = null
    LocalItemLocks.withItem(id) { scan { result = it } }
    callback(result)
  }

  private fun scanParts(
          item: DownloadItem,
          localItem: LocalLibraryItem,
          externalFolder: DocumentFile? = null,
          keepLocalProgress: Boolean = false,
          onIncomplete: () -> Unit = {},
          callback: (DownloadItemScanResult?) -> Unit
  ) {
    val tracks = mutableListOf<AudioTrack>()
    var foundEbook = false
    var localEpisodeId: String? = null

    // Every part is resolved before anything is changed, so the saved item is built from one set of observations
    val resolved = item.downloadItemParts.map { part ->
      val externalFile =
              if (part.isAppStorage) {
                null
              } else {
                part.completedDestinationUri?.let { DocumentFileCompat.fromUri(ctx, Uri.parse(it)) }
                        ?: resolveExternalFile(externalFolder, part)
              }
      Log.d(tag, "Resolve part ${part.filename}: externalFile=${externalFile?.uri}")
      part to createLocalFile(part, externalFile)
    }
    // On an SD card a part that can't be found is not proof that it is gone (the card may be leaving or be another
    // card): never save a partial item from removable storage; the download waits and is finalized complete (H02)
    if (item.localFolder.id.startsWith(AppStorage.APPSD_PREFIX) && resolved.any { it.second == null }) {
      AbsLogger.info(tag, "Not saving ${item.id}: ${resolved.count { it.second == null }} of ${resolved.size} files not readable on the SD card")
      onIncomplete()
      callback(null)
      return
    }

    resolved.forEach { (part, resolvedFile) ->
      val localFile = resolvedFile ?: return@forEach
      when {
        part.audioTrack != null -> {
          val serverTrack = part.audioTrack
          localItem.localFiles.removeAll { it.id == localFile.id }
          localItem.localFiles.add(localFile)
          val metadata =
                  FileMetadata(
                          localFile.filename ?: "",
                          File(localFile.filename ?: "").extension,
                          localFile.absolutePath,
                          localFile.basePath,
                          localFile.size
                  )
          val track =
                  AudioTrack(
                          serverTrack.index,
                          serverTrack.startOffset,
                          serverTrack.duration,
                          localFile.filename ?: "",
                          localFile.contentUrl,
                          localFile.mimeType ?: "",
                          metadata,
                          true,
                          localFile.id,
                          serverTrack.index
                  )
          tracks.add(track)
          Log.d(tag, "Added local audio track ${track.contentUrl} (${track.metadata?.path})")
          part.episode?.let { episode ->
            val podcast = localItem.media as Podcast
            localEpisodeId = podcast.addEpisode(track, episode).id
          }
        }
        part.ebookFile != null -> {
          foundEbook = true
          localItem.localFiles.removeAll { it.id == localFile.id }
          localItem.localFiles.add(localFile)
          (localItem.media as Book).ebookFile =
                  EBookFile(
                          part.ebookFile.ino,
                          part.ebookFile.metadata,
                          part.ebookFile.ebookFormat,
                          true,
                          localFile.id,
                          localFile.contentUrl
                  )
        }
        else -> {
          localItem.coverAbsolutePath = localFile.absolutePath
          localItem.coverContentUrl = localFile.contentUrl
          localItem.localFiles.removeAll { it.id == localFile.id }
          localItem.localFiles.add(localFile)
        }
      }
    }

    if (tracks.isEmpty() && !foundEbook) {
      callback(null)
      return
    }
    if (item.mediaType == "book") {
      tracks.sortBy { it.index }
      var expectedIndex = 1
      var offset = 0.0
      tracks.forEach { track ->
        track.index = expectedIndex++
        track.startOffset = offset
        offset += track.duration
      }
      localItem.media.setAudioTracks(tracks)
    }

    val result = DownloadItemScanResult(localItem, null)
    // A relinked item keeps its own local progress, re-pointed to the corrected server item, instead of
    // being overwritten by that item's server progress (it may hold listening not yet synced anywhere)
    val savedProgress = if (keepLocalProgress) DeviceManager.dbManager.getLocalMediaProgress(localItem.id) else null
    if (savedProgress != null) {
      result.localMediaProgress =
              repointLocalProgress(savedProgress, item.libraryItemId, item.serverConnectionConfigId, item.serverAddress, item.serverUserId)
      DeviceManager.dbManager.saveLocalMediaProgressLocked(savedProgress)
    } else item.userMediaProgress?.let { progress ->
      val progressId =
              if (item.episodeId.isNullOrEmpty()) localItem.id
              else "${localItem.id}-$localEpisodeId"
      result.localMediaProgress =
              LocalMediaProgress(
                      progressId,
                      localItem.id,
                      localEpisodeId,
                      progress.duration,
                      progress.progress,
                      progress.currentTime,
                      progress.isFinished,
                      progress.ebookLocation,
                      progress.ebookProgress,
                      progress.lastUpdate,
                      progress.startedAt,
                      progress.finishedAt,
                      item.serverConnectionConfigId,
                      item.serverAddress,
                      item.serverUserId,
                      item.libraryItemId,
                      item.episodeId
              )
      DeviceManager.dbManager.saveLocalMediaProgressLocked(result.localMediaProgress!!)
    }
    DeviceManager.dbManager.saveLocalLibraryItem(localItem)
    callback(result)
  }

  private fun findFolderByPath(root: DocumentFile, subPath: String): DocumentFile? {
    if (subPath.isBlank()) return root
    var current = root
    subPath.split('/').filter { it.isNotBlank() }.forEach { segment ->
      if (segment == "." || segment == "..") return null
      current = current.findFile(segment) ?: return null
    }
    return current
  }

  /**
   * DownloadsProvider on Android 10 may expose an audio document without its extension through
   * DocumentFile.findFile(). Match the manifest first, then match the provider-normalized base
   * filename. MIME type and server-reported size are unreliable for Opus on this platform.
   */
  private fun resolveExternalFile(folder: DocumentFile?, part: DownloadItemPart): DocumentFile? {
    if (folder == null) return null
    folder.findFile(part.filename)?.let {
      return it
    }
    val expectedBaseName = part.filename.substringBeforeLast('.')
    return folder.listFiles().firstOrNull { document ->
      document.name == part.filename ||
              document.fullName == part.filename ||
              (part.audioTrack != null &&
                      document.isFile &&
                      (document.name ?: "").substringBeforeLast('.') == expectedBaseName) ||
              (part.audioTrack != null &&
                      document.isFile &&
                      document.fullName.substringBeforeLast('.') == expectedBaseName)
    }
  }

  private fun mimeTypeFor(part: DownloadItemPart): String? {
    return part.audioTrack?.mimeType
            ?: when (part.ebookFile?.ebookFormat?.lowercase()) {
              "epub" -> "application/epub+zip"
              "pdf" -> "application/pdf"
              else -> "image/jpeg"
            }
  }

  // Item filenames could be the same if they are in sub-folders, this will make them unique.
  // Mirrors AbsDownloader's filename derivation so rescanned folders match real download output.
  private fun getFilenameFromRelPath(relPath: String): String {
    var cleanedRelPath = relPath.replace("\\", "_").replace("/", "_")
    cleanedRelPath = cleanStringForFileSystem(cleanedRelPath)
    return if (cleanedRelPath.startsWith("_")) cleanedRelPath.substring(1) else cleanedRelPath
  }

  private fun cleanStringForFileSystem(str: String): String {
    val reservedCharacters = listOf("?", "\"", "*", "|", "/", "\\", "<", ">")
    var newTitle = str.replace(":", " -")
    reservedCharacters.forEach { newTitle = newTitle.replace(it, "") }
    return newTitle
  }

  private fun makeExistingPart(
          downloadItemId: String,
          filename: String,
          subfolder: String,
          localFolder: LocalFolder,
          ebookFile: EBookFile?,
          audioTrack: AudioTrack?
  ): DownloadItemPart {
    val dummyFile = File(filename)
    val part =
            DownloadItemPart.make(
                    downloadItemId,
                    filename,
                    0L,
                    dummyFile,
                    dummyFile,
                    subfolder,
                    "",
                    localFolder,
                    ebookFile,
                    audioTrack,
                    null
            )
    // Not from an active download -- resolveExternalFile() (called inside scanParts) still has
    // to find the real file on disk by this filename for the part to end up populated.
    part.completed = true
    part.moved = true
    return part
  }

  /**
   * Builds a LocalLibraryItem for a book whose files already exist on disk (not from an active
   * download) by matching [libraryItem]'s expected track/ebook/cover filenames against what's
   * actually present in [itemFolder]. Reuses scanParts so missing files are skipped the same way
   * an in-progress download would, and a folder with none of the expected files resolves to null.
   */
  private fun scanExistingFolder(
          itemFolder: DocumentFile,
          libraryItem: LibraryItem,
          localFolder: LocalFolder,
          relink: Boolean,
          callback: (DownloadItemScanResult?) -> Unit
  ) {
    val bookTitle = cleanStringForFileSystem(libraryItem.media.metadata.title)
    val bookAuthor = cleanStringForFileSystem(libraryItem.media.metadata.getAuthorDisplayName())
    val itemSubfolder = "$bookAuthor/$bookTitle"

    val downloadItem =
            DownloadItem(
                    libraryItem.id,
                    libraryItem.id,
                    null,
                    libraryItem.userMediaProgress,
                    DeviceManager.serverConnectionConfig?.id ?: "",
                    DeviceManager.serverAddress,
                    DeviceManager.serverUserId,
                    libraryItem.mediaType,
                    itemFolder.getAbsolutePath(ctx),
                    localFolder,
                    bookTitle,
                    itemSubfolder,
                    libraryItem.media,
                    mutableListOf(),
                    libraryItem.ino
            )

    val book = libraryItem.media as Book
    book.ebookFile?.let { ebookFile ->
      val destinationFilename = getFilenameFromRelPath(ebookFile.metadata?.relPath ?: "")
      downloadItem.downloadItemParts.add(
              makeExistingPart(
                      downloadItem.id,
                      destinationFilename,
                      itemSubfolder,
                      localFolder,
                      ebookFile,
                      null
              )
      )
    }

    libraryItem.media.getAudioTracks().forEach { audioTrack ->
      val destinationFilename = getFilenameFromRelPath(audioTrack.relPath)
      downloadItem.downloadItemParts.add(
              makeExistingPart(
                      downloadItem.id,
                      destinationFilename,
                      itemSubfolder,
                      localFolder,
                      null,
                      audioTrack
              )
      )
    }

    if (!libraryItem.media.coverPath.isNullOrEmpty()) {
      val destinationFilename = "cover-${libraryItem.id}.jpg"
      downloadItem.downloadItemParts.add(
              makeExistingPart(
                      downloadItem.id,
                      destinationFilename,
                      itemSubfolder,
                      localFolder,
                      null,
                      null
              )
      )
    }

    val id = localLibraryItemId(itemFolder.id)
    scanLocked(id, callback) { done ->
      val savedItem = DeviceManager.dbManager.getLocalLibraryItem(id)
      val localItem =
              savedItem
                      ?: newLocalLibraryItem(
                              id,
                              downloadItem,
                              itemFolder.getBasePath(ctx),
                              itemFolder.getAbsolutePath(ctx),
                              itemFolder.uri.toString()
                      )
      if (savedItem != null && relink) {
        // Same local item (its id, folder and files are unchanged); only the server link and the media
        // copy are replaced with those of the item the folder's cover file names
        Log.i(tag, "rescanFolder: relinking ${savedItem.id} from ${savedItem.libraryItemId} to ${libraryItem.id}")
        savedItem.libraryItemId = libraryItem.id
        savedItem.ino = libraryItem.ino
        savedItem.serverConnectionConfigId = downloadItem.serverConnectionConfigId
        savedItem.serverAddress = downloadItem.serverAddress
        savedItem.serverUserId = downloadItem.serverUserId
        savedItem.media = downloadItem.media.getLocalCopy()
      }
      scanParts(downloadItem, localItem, itemFolder, keepLocalProgress = relink, callback = done)
    }
  }

  /**
   * Walks [localFolder] two levels deep (author/title, the same layout used for downloads) and
   * matches each book folder to a server item in [itemsByLibrary] by the Rescan Folder policy
   * ([resolveRescanMatch]): a `cover-<server item id>.jpg` in the folder names the exact copy it
   * was downloaded from (and relinks a saved item linked to another copy); otherwise author/title
   * must match a single book in [currentLibraryId]. Builds or updates a LocalLibraryItem for every
   * match; folders already linked without such proof are left as they are.
   * Podcasts are not supported by rescan (out of scope -- per-episode local items in a shared
   * folder are a materially different matching problem).
   *
   * [onProgress] reports the folder total once it is known and every checked folder, with the
   * item just matched (already saved, so final). [callback] runs exactly once; its error is a
   * [RescanError] when the folder can't be read, a matched item can't be fetched, or a step
   * throws, so a failure is never reported as an empty success.
   */
  fun rescanFolder(
          localFolder: LocalFolder,
          itemsByLibrary: Map<String, List<LibraryItem>>,
          currentLibraryId: String?,
          fetchFullItem: (libraryItemId: String, cb: (LibraryItem?) -> Unit) -> Unit,
          onProgress: (RescanProgress, LocalLibraryItem?) -> Unit,
          callback: (RescanOutcome<LocalLibraryItem>) -> Unit
  ) {
    val folders: List<Pair<DocumentFile, String>>
    val savedByPath: Map<String, LocalLibraryItem>
    // Every book with the library it was loaded from (the catalog key), by id and by author/title
    val books = itemsByLibrary.flatMap { (libraryId, items) -> items.filter { it.mediaType == "book" }.map { libraryId to it } }
    val byId = books.associateBy { it.second.id }
    val byAuthorTitle = books.groupBy { authorTitleKey(it.second) }
    try {
      val root = DocumentFileCompat.fromUri(ctx, Uri.parse(localFolder.contentUrl))
      if (root == null || !root.canRead()) {
        Log.e(tag, "rescanFolder: Invalid or unreadable SAF root ${localFolder.contentUrl}")
        callback(RescanOutcome(emptyList(), emptyList(), RescanError.FOLDER))
        return
      }
      folders = authorTitleFolders(root)
      savedByPath = DeviceManager.dbManager.getLocalLibraryItemsInFolder(localFolder.id).associateBy { it.absolutePath }
    } catch (e: Exception) {
      Log.e(tag, "rescanFolder: Failed to read ${localFolder.contentUrl}", e)
      callback(RescanOutcome(emptyList(), emptyList(), RescanError.FOLDER))
      return
    }

    val total = folders.size
    onProgress(RescanProgress(localFolder.id, RescanPhase.CHECKING, total = total), null)
    runRescan(
            folders,
            match = { folder, relPath ->
              val saved = savedByPath[folder.getAbsolutePath(ctx)]
              resolveRescanMatch(
                      coverItemIds(folder.listFiles().filter { it.isFile }.mapNotNull { it.name }),
                      saved != null,
                      saved?.libraryItemId,
                      relPath,
                      currentLibraryId,
                      byId = { id -> byId[id] },
                      matchesByAuthorTitle = { key -> byAuthorTitle[key] ?: emptyList() },
                      idOf = { it.second.id },
                      libraryOf = { it.first }
              )
            },
            fetchFull = { (libraryId, item), cb -> fetchFullItem(item.id) { full -> cb(full?.let { libraryId to it }) } },
            scan = { folder, (_, fullItem), relink, cb ->
              scanExistingFolder(folder, fullItem, localFolder, relink) { cb(it?.localLibraryItem) }
            },
            onChecked = { checked, matched, notMatched, latest ->
              onProgress(
                      RescanProgress(
                              localFolder.id,
                              RescanPhase.CHECKING,
                              checked,
                              total,
                              matched.size,
                              notMatched,
                              latest?.media?.metadata?.title
                      ),
                      latest
              )
            },
            done = callback
    )
  }

  private fun authorTitleKey(item: LibraryItem): String {
    val author = cleanStringForFileSystem(item.media.metadata.getAuthorDisplayName())
    val title = cleanStringForFileSystem(item.media.metadata.title)
    return "$author/$title"
  }

  /** The author/title subfolders of [root] (the layout downloads use), with their relative paths. */
  private fun authorTitleFolders(root: DocumentFile): List<Pair<DocumentFile, String>> {
    val candidates = mutableListOf<Pair<DocumentFile, String>>()
    root.listFiles().filter { it.isDirectory }.forEach { authorFolder ->
      authorFolder.listFiles().filter { it.isDirectory }.forEach { titleFolder ->
        candidates.add(titleFolder to "${authorFolder.name}/${titleFolder.name}")
      }
    }
    return candidates
  }

  /** [onIncomplete] runs when a removable-storage item was not saved because some of its files could not be read. */
  fun scanDownloadItem(item: DownloadItem, onIncomplete: () -> Unit = {}, callback: (DownloadItemScanResult?) -> Unit) {
    if (item.isAppStorage) {
      val id = "local_${item.libraryItemId}"
      scanLocked(id, callback) { done ->
        val localItem =
                DeviceManager.dbManager.getLocalLibraryItem(id)
                        ?: newLocalLibraryItem(id, item, item.itemFolderPath, item.itemFolderPath, "")
        scanParts(item, localItem, onIncomplete = onIncomplete, callback = done)
      }
      return
    }

    val root = DocumentFileCompat.fromUri(ctx, Uri.parse(item.localFolder.contentUrl))
    if (root == null) {
      Log.e(tag, "Invalid SAF root: ${item.localFolder.contentUrl}")
      callback(null)
      return
    }
    val itemFolder = findFolderByPath(root, item.itemSubfolder)
    if (itemFolder == null) {
      Log.e(tag, "SAF item folder not found: ${item.itemSubfolder}")
      callback(null)
      return
    }
    val id = localLibraryItemId(itemFolder.id)
    scanLocked(id, callback) { done ->
      val localItem =
              DeviceManager.dbManager.getLocalLibraryItem(id)
                      ?: newLocalLibraryItem(
                              id,
                              item,
                              itemFolder.getBasePath(ctx),
                              itemFolder.getAbsolutePath(ctx),
                              itemFolder.uri.toString()
                      )
      scanParts(item, localItem, itemFolder, onIncomplete = onIncomplete, callback = done)
    }
  }
}
