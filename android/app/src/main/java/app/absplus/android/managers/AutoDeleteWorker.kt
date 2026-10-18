package app.absplus.android.managers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.absplus.android.data.DeviceSettings
import app.absplus.android.device.DeviceManager
import app.absplus.android.plugins.AbsLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Keeps the periodic auto delete work in sync with the device settings. It is its own work, with no network or charging
 * requirement: the podcast auto download work keeps its own limits and does not clean up.
 */
object AutoDeleteScheduler {
  private const val tag = "AutoDeleteScheduler"
  private const val WORK_NAME = "auto-delete-cleanup"
  private const val INTERVAL_HOURS = 6L

  // Serialized so the last caller reads the latest settings and its enqueue lands last
  @Synchronized
  fun reschedule(context: Context) {
    DbManager.initialize(context)
    val settings = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()
    val workManager = WorkManager.getInstance(context.applicationContext)
    if (!AutoDeleteManager.isCleanupEnabled(settings)) {
      workManager.cancelUniqueWork(WORK_NAME)
      return
    }
    // UPDATE keeps the last run time, so rescheduling on every app start does not reset the period
    workManager.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<AutoDeleteWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build())
    AbsLogger.info(tag, "Auto delete scheduled every ${INTERVAL_HOURS}h")
  }
}

class AutoDeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val tag = "AutoDeleteWorker"

  override suspend fun doWork(): Result {
    try {
      val status = AutoDownloadManager.check(applicationContext, AutoDownloadManager.Trigger.CLEANUP)
      AbsLogger.info(tag, "Auto delete check finished with status ${status.jsValue}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      AbsLogger.error(tag, "Auto delete check failed: ${e.message}")
    }
    return Result.success()
  }
}
