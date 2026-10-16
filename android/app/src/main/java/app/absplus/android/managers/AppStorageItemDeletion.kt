package app.absplus.android.managers

import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.device.AppStorage
import app.absplus.android.device.SafeTree
import app.absplus.android.device.StorageIo
import app.absplus.android.device.VolumeProbe
import java.io.File

/** The user deleting a download kept in app storage (internal or the SD card). */
object AppStorageItemDeletion {
  enum class Outcome { DELETED, STORAGE_UNAVAILABLE, OUTSIDE_APP_STORAGE, FAILED }

  /**
   * Deletes the item's folder, which must be a direct, safely named child of its storage's own downloads folder. An
   * SD card that is out, or another card under its UUID, deletes nothing and reports it, so the item and its files
   * are kept instead of being reported deleted while they stay on the card (L01). Links are removed, never followed.
   */
  fun delete(item: LocalLibraryItem, probe: VolumeProbe, io: StorageIo): Outcome {
    if (!AppStorage.isVerified(item.folderId, probe)) return Outcome.STORAGE_UNAVAILABLE
    val root = AppStorage.downloadsRoot(item.folderId, probe) ?: return Outcome.STORAGE_UNAVAILABLE
    val folder = File(item.absolutePath)
    if (item.absolutePath.isEmpty() || !DownloadIdentifiers.isSafe(folder.name) ||
            folder.parentFile?.let { AppStorage.normalizedPath(it) } != AppStorage.normalizedPath(root)) {
      return Outcome.OUTSIDE_APP_STORAGE
    }
    return if (SafeTree.delete(io, folder)) Outcome.DELETED else Outcome.FAILED
  }
}
