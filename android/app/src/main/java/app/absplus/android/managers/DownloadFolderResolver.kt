package app.absplus.android.managers

import app.absplus.android.data.LocalFolder
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.device.AppStorage
import app.absplus.android.device.StorageTarget
import app.absplus.android.device.VolumeProbe

sealed class FolderResolution {
  data class Existing(val folder: LocalFolder) : FolderResolution()
  /** An app storage folder that is not in the database yet. */
  data class Create(val folder: LocalFolder) : FolderResolution()
  object Unavailable : FolderResolution()
  object NotFound : FolderResolution()
}

/** Picks the folder a download goes to: an explicitly picked SAF folder, the item's own or queued folder, or the storage setting. */
object DownloadFolderResolver {
  fun resolveFolder(
          mediaType: String,
          explicitFolderId: String?,
          existing: LocalLibraryItem?,
          queuedFolderId: String?,
          setting: StorageTarget,
          probe: VolumeProbe,
          folderById: (String) -> LocalFolder?
  ): FolderResolution {
    val explicitSafId = explicitFolderId?.takeIf { it.isNotEmpty() && !AppStorage.isAppStorage(it) }
    if (explicitSafId != null) return folderById(explicitSafId)?.let { FolderResolution.Existing(it) } ?: FolderResolution.NotFound

    // Keep all files of an item on one volume: a second local item elsewhere would hide them in the UI
    val itemFolderId = existing?.folderId ?: queuedFolderId
    if (itemFolderId != null) return resolveItemFolder(itemFolderId, mediaType, probe, folderById)

    val target = AppStorage.folderFor(setting, mediaType)
    if (!AppStorage.isAvailable(target, probe)) return FolderResolution.Unavailable
    return folderById(target.id)?.let { FolderResolution.Existing(it) } ?: FolderResolution.Create(target)
  }

  private fun resolveItemFolder(id: String, mediaType: String, probe: VolumeProbe, folderById: (String) -> LocalFolder?): FolderResolution {
    if (!AppStorage.isAppStorage(id)) return folderById(id)?.let { FolderResolution.Existing(it) } ?: FolderResolution.NotFound
    val saved = folderById(id)
    val folder = saved ?: folderFromId(id, mediaType) ?: return FolderResolution.NotFound
    if (!AppStorage.isAvailable(folder, probe)) return FolderResolution.Unavailable
    return if (saved != null) FolderResolution.Existing(folder) else FolderResolution.Create(folder)
  }

  private fun folderFromId(id: String, mediaType: String): LocalFolder? {
    if (id.startsWith(AppStorage.INTERNAL_PREFIX)) return AppStorage.folderFor(StorageTarget.Internal, mediaType)
    val uuid = AppStorage.uuidOf(id) ?: return null
    return AppStorage.folderFor(StorageTarget.Sd(uuid), mediaType)
  }
}
