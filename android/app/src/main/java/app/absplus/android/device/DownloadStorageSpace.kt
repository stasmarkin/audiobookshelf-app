package app.absplus.android.device

import app.absplus.android.data.LocalLibraryItem

object DownloadStorageSpace {
  // Headroom kept free on the target so a move never fills the storage to the last byte
  const val MOVE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024

  /** Bytes of app-storage downloads that are not on [target] yet; SAF folder items never move. */
  fun bytesToMove(items: List<LocalLibraryItem>, target: StorageTarget): Long =
          items.filter { AppStorage.isAppStorage(it.folderId) && !isOn(it.folderId, target) }
                  .sumOf { item -> item.localFiles.sumOf { it.size } }

  private fun isOn(folderId: String, target: StorageTarget): Boolean =
          when (target) {
            is StorageTarget.Internal -> folderId.startsWith(AppStorage.INTERNAL_PREFIX)
            is StorageTarget.Sd -> AppStorage.uuidOf(folderId) == target.uuid
          }
}
