package app.absplus.android.managers

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.AppStorage
import app.absplus.android.device.DeviceManager
import app.absplus.android.models.DownloadItem
import app.absplus.android.plugins.AbsLogger
import java.io.File
import java.util.concurrent.TimeUnit

/** Removes only staging data for terminally failed downloads after their retention window. */
object IncompleteDownloadCleanup {
  private const val tag = "IncompleteDownloadCleanup"
  private const val RETENTION_MS = 24L * 60L * 60L * 1000L
  private const val WORK_PREFIX = "incomplete-download-"

  fun schedule(context: Context, item: DownloadItem) {
    val failedAt = item.terminalFailureAt ?: return
    if (item.stagingCleanupAt != null) return
    val delay = (failedAt + RETENTION_MS - System.currentTimeMillis()).coerceAtLeast(0L)
    val request = OneTimeWorkRequestBuilder<IncompleteDownloadCleanupWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_PREFIX + item.id, ExistingWorkPolicy.REPLACE, request)
  }

  fun cancel(context: Context, itemId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(WORK_PREFIX + itemId)
  }

  /** Cleans staging data retained longer than 24 hours when scheduled work did not run. */
  @Synchronized
  fun cleanupExpired(context: Context) {
    val now = System.currentTimeMillis()
    DeviceManager.dbManager.getDownloadItems()
            .filter { item -> isEligible(item, now, context) }
            .forEach { item ->
              deleteItem(context, item)
            }
  }

  private fun isEligible(item: DownloadItem, now: Long, context: Context): Boolean {
    // An authorization hold is not permission to discard the owner's partial media. This also
    // covers already-scheduled cleanup that runs before the download service restores its queue.
    val saved = DeviceManager.getServerConnectionConfig(item.serverConnectionConfigId)?.copy()
    if (item.downloadItemParts.any { part ->
          !part.moved && DownloadAuthorization.tokenFor(
                  item.serverConnectionConfigId, item.serverAddress, item.serverUserId,
                  saved?.id, saved?.address, saved?.userId, saved?.token,
                  "${item.serverAddress}${part.serverPath}") == null
        }) return false
    // Staging on an SD card that is out or not the registered card is left as it is, with its pending state (H02)
    if (item.localFolder.id.startsWith(AppStorage.APPSD_PREFIX) && !AppStorage.isVerified(item.localFolder.id, AndroidVolumeProbe(context))) return false
    val failedAt = item.terminalFailureAt ?: return false
    if (item.stagingCleanupAt != null) return false
    if (now - failedAt < RETENTION_MS) return false
    return item.downloadItemParts.all { part ->
      part.moved || (part.failed && !part.isMoving)
    }
  }

  private fun deleteItem(context: Context, item: DownloadItem) {
    val appRoots = AppStorage.appOwnedRoots(AndroidVolumeProbe(context))
    var allDeleted = true
    item.downloadItemParts.forEach { part ->
      if (!deleteAppOwnedFile(File(part.destinationPath), appRoots)) {
        allDeleted = false
        return@forEach
      }
      if (!part.moved) {
        part.bytesDownloaded = 0L
        part.completed = false
      }
    }
    // Cleanup is recorded only when it happened; otherwise the item stays pending for the next run
    if (!allDeleted) {
      DeviceManager.dbManager.saveDownloadItem(item)
      AbsLogger.error(tag, "Staging files of ${item.id} could not all be deleted; tried again later")
      return
    }
    item.stagingCleanupAt = System.currentTimeMillis()
    DeviceManager.dbManager.saveDownloadItem(item)
    cancel(context, item.id)
    AbsLogger.info(tag, "Deleted staging files for terminally failed download item ${item.id}")
  }

  /** True when the file is gone afterwards (or never existed). */
  private fun deleteAppOwnedFile(file: File, appRoots: List<File>): Boolean {
    val path = file.absolutePath
    if (appRoots.any { AppStorage.isUnder(file, it) }) {
      if (file.exists() && !file.delete()) {
        AbsLogger.error(tag, "Could not delete expired staging file $path")
        return false
      }
      file.parentFile?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
      return true
    } else {
      AbsLogger.error(tag, "Refusing to delete non-app-owned path $path")
      return false
    }
  }
}

class IncompleteDownloadCleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    DbManager.initialize(applicationContext)
    IncompleteDownloadCleanup.cleanupExpired(applicationContext)
    return Result.success()
  }
}
