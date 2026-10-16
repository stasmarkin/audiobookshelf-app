package app.absplus.android.managers

import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DownloadStorageSpace
import app.absplus.android.device.StorageTarget
import app.absplus.android.device.VolumeProbe
import app.absplus.android.models.DownloadItem
import java.io.File

enum class WaitReason {
  PLAYING,
  DOWNLOADING,
  UNAVAILABLE,
  NO_SPACE,
  RETRY,
  /** Its previous move keeps the old copy until it is checked after the next device restart. */
  FINISHING
}

data class MoveStatus(
        val total: Int,
        val done: Int,
        val bytesTotal: Long,
        val bytesDone: Long,
        val waiting: Map<WaitReason, Int>,
        val running: Boolean,
        val lastError: String?
)

/** [waiting] is keyed by local library item id. */
data class MovePlan(val toMove: List<LocalLibraryItem>, val waiting: Map<String, WaitReason>)

/** Decides which app-storage downloads can move to the target storage now and why the rest wait. */
object StorageMovePlanner {
  fun plan(
          items: List<LocalLibraryItem>,
          queue: List<DownloadItem>,
          target: StorageTarget,
          probe: VolumeProbe,
          pinned: (String) -> Boolean,
          freeBytes: (File) -> Long
  ): MovePlan = plan(items, queue, target, probe, pinned, { false }, freeBytes)

  /** [journaled]: items with an unfinished move ([MoveJournal]); they wait until it has finished or been undone. */
  fun plan(
          items: List<LocalLibraryItem>,
          queue: List<DownloadItem>,
          target: StorageTarget,
          probe: VolumeProbe,
          pinned: (String) -> Boolean,
          journaled: (String) -> Boolean,
          freeBytes: (File) -> Long
  ): MovePlan {
    val toMove = mutableListOf<LocalLibraryItem>()
    val waiting = linkedMapOf<String, WaitReason>()
    val queuedServerIds = queue.map { it.libraryItemId }.toSet()
    val freeByRoot = mutableMapOf<File, Long>()
    val plannedByRoot = mutableMapOf<File, Long>()
    // Moves run in list order, so letting a later small item jump a big one would starve the big one
    var outOfSpace = false

    for (item in items) {
      if (!AppStorage.isAppStorage(item.folderId)) continue
      val targetFolder = AppStorage.folderFor(target, item.mediaType)
      if (item.folderId == targetFolder.id) {
        // Already on the target, but a missing card still has to be reported
        if (!AppStorage.isAvailable(item.folderId, probe)) waiting[item.id] = WaitReason.UNAVAILABLE
        continue
      }

      val reason =
              when {
                // One journaled move per item: the next starts only after the previous one finished or was undone
                journaled(item.id) -> WaitReason.FINISHING
                pinned(item.id) -> WaitReason.PLAYING
                item.libraryItemId != null && item.libraryItemId in queuedServerIds -> WaitReason.DOWNLOADING
                !AppStorage.isAvailable(item.folderId, probe) || !AppStorage.isAvailable(targetFolder, probe) ->
                        WaitReason.UNAVAILABLE
                else -> null
              }
      if (reason != null) {
        waiting[item.id] = reason
        continue
      }

      val root = AppStorage.downloadsRoot(targetFolder, probe)
      if (root == null) {
        waiting[item.id] = WaitReason.UNAVAILABLE
        continue
      }
      if (outOfSpace) {
        waiting[item.id] = WaitReason.NO_SPACE
        continue
      }
      val size = item.localFiles.sumOf { it.size }
      val free = freeByRoot.getOrPut(root) { freeBytes(root) }
      val planned = plannedByRoot[root] ?: 0L
      if (planned + size + DownloadStorageSpace.MOVE_SPACE_MARGIN_BYTES <= free) {
        plannedByRoot[root] = planned + size
        toMove.add(item)
      } else {
        outOfSpace = true
        waiting[item.id] = WaitReason.NO_SPACE
      }
    }
    return MovePlan(toMove, waiting)
  }
}
