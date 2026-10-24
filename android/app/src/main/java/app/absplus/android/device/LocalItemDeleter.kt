package app.absplus.android.device

import android.content.Context
import android.net.Uri
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.Podcast
import app.absplus.android.managers.AppStorageItemDeletion
import app.absplus.android.managers.ItemUpdate
import app.absplus.android.managers.LocalItemLocks
import app.absplus.android.managers.LocalTrackDeletion
import app.absplus.android.plugins.AbsLogger
import com.anggrayudi.storage.file.DocumentFileCompat

/** Shared byte deletion; automatic eviction keeps logical identity and listening history. */
object LocalItemDeleter {
  private const val TAG = "LocalItemDeleter"

  /**
   * What a delete did. Unavailable or failed operations retain records, including after partial byte removal.
   * [SKIPPED] is not an error and happens in auto mode only.
   */
  enum class Outcome { DELETED, FAILED, SKIPPED, STORAGE_UNAVAILABLE }

  /**
   * Deletes registered bytes; only explicit deletion removes the logical item after success. Paths come from the database
   * under the item lock, because what a page sent may predate a move to another storage. In a device folder only
   * the registered regular files go; folders are never deleted. With [auto] a
   * saved link that does not open, or a registered entry that is not a regular file, skips the delete; the manual
   * delete tries the link itself. [stillAllowed] is asked under the item's lock right before any file goes (auto delete
   * uses it to see that the listening progress it decided on has not changed since).
   */
  fun deleteItem(
          ctx: Context,
          localLibraryItemId: String,
          auto: Boolean,
          probe: VolumeProbe = AndroidVolumeProbe(ctx),
          io: StorageIo = AndroidStorageIo,
          onDetail: (FolderFileDeletion.Detail) -> Unit = {},
          stillAllowed: (LocalLibraryItem) -> Boolean = { true }
  ): Outcome =
          LocalItemLocks.withItem(localLibraryItemId) {
            val localLibraryItem = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
            if (localLibraryItem == null) {
              // Already gone (deleted elsewhere); its app-storage folder, if any, is left to orphan cleanup
              AbsLogger.info(TAG, "deleteItem: local item $localLibraryItemId does not exist")
              return@withItem Outcome.DELETED
            }
            if (!stillAllowed(localLibraryItem)) {
              AbsLogger.info(TAG, "Delete of \"${localLibraryItem.title}\" skipped: its state changed just before")
              return@withItem Outcome.SKIPPED
            }
            val outcome =
                    try {
                      deleteItemFiles(ctx, localLibraryItem, auto, probe, io, onDetail) { operation ->
                        if (!auto) operation()
                        else synchronized(DeviceManager.downloadAuthorizationLock) {
                          stillAllowed(localLibraryItem) && operation()
                        }
                      }
                    } finally {
                      LocalItemLocks.bump(localLibraryItemId)
                    }
            if (outcome == Outcome.DELETED && auto) {
              try {
                DeviceManager.dbManager.updateLocalLibraryItem(localLibraryItemId) {
                  it.isInvalid = true // existing availability flag; retain all identities, associations and history
                  ItemUpdate.Save(it)
                }
              } catch (_: Exception) {
                // A write may have committed before reporting an error. Reread, never remove the record.
                if (DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)?.isInvalid != true) return@withItem Outcome.FAILED
              }
            }
            if (outcome == Outcome.DELETED && !auto) {
              DeviceManager.dbManager.removeLocalLibraryItem(localLibraryItemId)
              DeviceManager.dbManager.removeLocalMediaProgressFor(localLibraryItemId)
              // Auto delete keeps a record per book; it goes with the book whoever deleted it
              DeviceManager.dbManager.removeAutoDeleteConfirmed(localLibraryItemId)
              DeviceManager.dbManager.removeAutoDeleteDownloadedAt(localLibraryItemId)
            }
            outcome
          }

  private fun deleteItemFiles(ctx: Context, item: LocalLibraryItem, auto: Boolean, probe: VolumeProbe, io: StorageIo,
                              onDetail: (FolderFileDeletion.Detail) -> Unit, guarded: (() -> Boolean) -> Boolean): Outcome {
    if (auto && AppStorage.isAppStorage(item.folderId)) {
      val root = AppStorage.downloadsRoot(item.folderId, probe) ?: return Outcome.STORAGE_UNAVAILABLE.also { onDetail(FolderFileDeletion.Detail.STORAGE_UNAVAILABLE) }
      val itemDir = java.io.File(item.absolutePath)
      if (itemDir.parentFile?.let { AppStorage.normalizedPath(it) } != AppStorage.normalizedPath(root)) return Outcome.FAILED
      if (itemDir.canonicalFile.parentFile != root.canonicalFile || io.kind(itemDir) != NodeKind.DIRECTORY) return Outcome.FAILED
      val docs = object : FolderFileDeletion.Docs {
        override fun folderReachable() = AppStorage.isVerified(item.folderId, probe) && io.kind(root) == NodeKind.DIRECTORY
        override fun kind(uri: String): FolderFileDeletion.Kind {
          val file = java.io.File(Uri.parse(uri).path ?: return FolderFileDeletion.Kind.UNKNOWN)
          if (!AppStorage.isUnder(file.canonicalFile, itemDir.canonicalFile) || file.canonicalFile == itemDir.canonicalFile) return FolderFileDeletion.Kind.UNKNOWN
          return when (io.kind(file)) {
            NodeKind.FILE -> FolderFileDeletion.Kind.FILE
            NodeKind.MISSING -> FolderFileDeletion.Kind.MISSING
            NodeKind.DIRECTORY -> FolderFileDeletion.Kind.DIRECTORY
            else -> FolderFileDeletion.Kind.UNKNOWN
          }
        }
        override fun deleteFile(uri: String): Boolean {
          val file = java.io.File(Uri.parse(uri).path ?: return false)
          return folderReachable() && kind(uri) == FolderFileDeletion.Kind.FILE && io.delete(file)
        }
      }
      val result = FolderFileDeletion.deleteFiles(item.localFiles.map { Uri.fromFile(java.io.File(it.absolutePath)).toString() }, docs, guarded, detail = onDetail)
      // App-private empty folder only, using the existing non-recursive filesystem operation.
      if (result == FolderFileDeletion.Result.DELETED) guarded {
        if (docs.folderReachable() && io.kind(itemDir) == NodeKind.DIRECTORY && io.list(itemDir)?.isEmpty() == true) io.delete(itemDir) else false
      }
      return deletionOutcome(result)
    }
    if (AppStorage.isAppStorage(item.folderId)) {
      return when (AppStorageItemDeletion.delete(item, probe, io)) {
        AppStorageItemDeletion.Outcome.DELETED -> Outcome.DELETED
        // A card that is out (or another card under its UUID): nothing is deleted and the item stays, instead of
        // reporting a deletion whose files remain on the card (L01)
        AppStorageItemDeletion.Outcome.STORAGE_UNAVAILABLE -> {
          AbsLogger.info(TAG, "deleteItem: ${item.id} is on an SD card that is not available; not deleted")
          onDetail(FolderFileDeletion.Detail.STORAGE_UNAVAILABLE)
          Outcome.STORAGE_UNAVAILABLE
        }
        AppStorageItemDeletion.Outcome.OUTSIDE_APP_STORAGE -> {
          AbsLogger.error(TAG, "deleteItem: ${item.id} path is not an app storage download folder; not deleting")
          onDetail(FolderFileDeletion.Detail.UNVERIFIED)
          Outcome.FAILED
        }
        AppStorageItemDeletion.Outcome.FAILED -> Outcome.FAILED.also { onDetail(FolderFileDeletion.Detail.DELETE_FAILED) }
      }
    }

    // Only the registered files, each only while it is a regular file; folders are never deleted, and nothing is matched
    // by name: a provider's directory delete is recursive and no listing proves a folder empty (R32-C01, C02, C03)
    val docs = FolderFileDeletion.AndroidDocs(ctx, DeviceManager.dbManager.getLocalFolder(item.folderId), item.contentUrl)
    return deletionOutcome(FolderFileDeletion.deleteFiles(item.localFiles.map { it.contentUrl }, docs, guarded, alreadyMissingIsDeleted = !auto, detail = onDetail))
  }

  private fun deletionOutcome(result: FolderFileDeletion.Result): Outcome = when (result) {
      FolderFileDeletion.Result.DELETED -> Outcome.DELETED
      // Access lost, including during a partial operation: all records are kept for a later retry.
      FolderFileDeletion.Result.UNAVAILABLE -> Outcome.STORAGE_UNAVAILABLE
      // A registered entry that is a folder or of unknown type, or a file that stayed: auto leaves it for later
      FolderFileDeletion.Result.FAILED -> Outcome.FAILED
    }

  /** What deleting one downloaded track did. */
  sealed class TrackResult {
    /** The track (or, for a file shared by two episodes, only the requested episode's record) is gone; [item] is the saved item. */
    class Deleted(val item: LocalLibraryItem, val episodeGone: Boolean) : TrackResult()
    /** The file is shared with another episode and the caller did not say which one to remove (R29-N02). */
    object RefusedShared : TrackResult()
    /** Nothing changed. */
    object Failed : TrackResult()
  }

  /**
   * Deletes one audio track file and drops it from the item, all under the item's lock. [localEpisodeId] is the
   * episode being removed: a file shared by two episodes then keeps its audio for the other one.
   */
  fun deleteTrack(
          ctx: Context,
          localLibraryItemId: String,
          trackLocalFileId: String,
          localEpisodeId: String?,
          probe: VolumeProbe = AndroidVolumeProbe(ctx),
          io: StorageIo = AndroidStorageIo,
          onDetail: (FolderFileDeletion.Detail) -> Unit = {}
  ): TrackResult {
    var outcome: LocalTrackDeletion.Outcome? = null
    val result = DeviceManager.dbManager.updateLocalLibraryItem(localLibraryItemId) { localLibraryItem ->
      val localFile = localLibraryItem.localFiles.find { it.id == trackLocalFileId }
      if (localFile == null) {
        AbsLogger.error(TAG, "deleteTrack: $localLibraryItemId has no local file $trackLocalFileId")
        return@updateLocalLibraryItem ItemUpdate.Keep
      }
      outcome = LocalTrackDeletion.delete(localLibraryItem, trackLocalFileId, localEpisodeId) {
        if (AppStorage.isAppStorage(localLibraryItem.folderId)) {
          // Only on the registered card, and only the regular file itself (L01, M01)
          val file = java.io.File(localFile.absolutePath)
          when {
            !AppStorage.isVerified(localLibraryItem.folderId, probe) -> false.also { onDetail(FolderFileDeletion.Detail.STORAGE_UNAVAILABLE) }
            io.kind(file) == NodeKind.DIRECTORY -> false.also { onDetail(FolderFileDeletion.Detail.NOT_A_FILE) }
            io.kind(file) != NodeKind.FILE -> false.also { onDetail(FolderFileDeletion.Detail.UNVERIFIED) }
            else -> io.delete(file).also { if (!it) onDetail(FolderFileDeletion.Detail.DELETE_FAILED) }
          }
        } else {
          // Only while it is a regular file: a folder in its place is never deleted with its contents (R32-C02). A file
          // that is verifiably gone already only loses its record, as the whole-item delete does
          val docs = FolderFileDeletion.AndroidDocs(ctx, DeviceManager.dbManager.getLocalFolder(localLibraryItem.folderId), localLibraryItem.contentUrl)
          FolderFileDeletion.deleteFiles(listOf(localFile.contentUrl), docs, alreadyMissingIsDeleted = true, detail = onDetail) == FolderFileDeletion.Result.DELETED
        }
      }
      when (outcome) {
        LocalTrackDeletion.Outcome.DELETED, LocalTrackDeletion.Outcome.RECORD_ONLY -> ItemUpdate.Save(localLibraryItem)
        else -> ItemUpdate.Keep
      }
    }
    return when {
      result == null -> {
        AbsLogger.error(TAG, "deleteTrack: LLI does not exist $localLibraryItemId")
        TrackResult.Failed
      }
      result is ItemUpdate.Save -> {
        val episodeGone = localEpisodeId != null && (result.item.media as? Podcast)?.episodes?.none { it.id == localEpisodeId } == true
        TrackResult.Deleted(result.item, episodeGone)
      }
      outcome == LocalTrackDeletion.Outcome.REFUSED_SHARED -> TrackResult.RefusedShared
      else -> TrackResult.Failed
    }
  }
}
