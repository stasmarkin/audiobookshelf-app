package app.absplus.android.managers

import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart

/**
 * The user's download controls (Retry, Cancel, Clear) as pure decisions, applied by
 * [DownloadItemManager].
 *
 * Retry restarts every unfinished file from byte zero; files already finished in the destination
 * are kept. (Byte-range continuation only happens inside one service session, for the automatic
 * retries after a transfer error.) Cancel and Clear both remove the download: they stop its
 * transfers, drop it from the queue and delete the app's own partial (staging) files. Finished
 * files the download already placed in a device folder are kept there (a later download of the item
 * reuses them, and Rescan Folder can link them); finished files in app storage (internal or SD card)
 * are deleted, since nothing else can reach them. A file a saved local item uses, or one that existed
 * before this download, is never deleted. A download whose SD card is missing can't be retried until
 * the card is back.
 */
object DownloadControls {
  enum class Refusal {
    /** Every file is in place and the download is becoming a local item. */
    FINISHING,
    /** A finished file is being copied into the device folder; removing now would race it. */
    MOVING
  }

  /** Why the download can't be cancelled or cleared right now, or null when it can. */
  fun removalRefusal(item: DownloadItem, isFinalizing: Boolean): Refusal? =
          when {
            isFinalizing || item.isDownloadFinished -> Refusal.FINISHING
            item.downloadItemParts.any { it.isMoving } -> Refusal.MOVING
            else -> null
          }

  /** What a Cancel or Clear deletes ([delete], app-owned paths) and the finished files it keeps. */
  data class RemovalPlan(val delete: List<String>, val keptFinishedFiles: List<String>)

  fun removalPlan(item: DownloadItem, isUsedByLocalItem: (DownloadItemPart) -> Boolean): RemovalPlan {
    val delete = mutableListOf<String>()
    val kept = mutableListOf<String>()
    item.downloadItemParts.forEach { part ->
      // The staging file lives in app storage and is never anything but this download's partial data
      delete.add(part.destinationPath)
      if (!part.moved) return@forEach
      val keep = part.reusedExistingFile || isUsedByLocalItem(part) || !part.isAppStorage
      if (keep) kept.add(part.filename) else delete.add(part.finalDestinationPath)
    }
    return RemovalPlan(delete, kept)
  }

  /** Retry needs every transfer of the item stopped, and an item that is not already finished. */
  fun canRetry(item: DownloadItem, isActive: (DownloadItemPart) -> Boolean): Boolean =
          !item.isDownloadFinished && item.downloadItemParts.none { isActive(it) || it.isMoving }

  /**
   * Device folders the item still has to write to but can't (lost or revoked access): retrying
   * those would only fail again, so the user must re-select the folder first.
   */
  fun foldersWithoutAccess(item: DownloadItem, hasAccess: (folderUrl: String) -> Boolean): List<String> =
          item.downloadItemParts
                  .filter { !it.moved && !it.isAppStorage }
                  .filter { !hasAccess(it.localFolderUrl) }
                  .map { it.localFolderName }
                  .distinct()

  /** Retry such a download only once its SD card is back. */
  fun storageUnavailable(item: DownloadItem, volumeMissing: () -> Boolean): Boolean =
          item.isAppStorage && item.downloadItemParts.any { !it.moved } && volumeMissing()
}
