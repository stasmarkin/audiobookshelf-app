package app.absplus.android.managers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.absplus.android.data.DeviceSettings
import app.absplus.android.device.DeviceManager
import app.absplus.android.plugins.AbsLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Keeps the periodic podcast auto download work in sync with device settings. */
object AutoDownloadScheduler {
  private const val tag = "AutoDownloadScheduler"
  private const val WORK_NAME = "podcast-auto-download"
  private val VALID_INTERVAL_HOURS = setOf(1, 6, 12, 24)
  private const val DEFAULT_INTERVAL_HOURS = 6

  // Serialized so the last caller reads the latest settings and its enqueue lands last
  @Synchronized
  fun reschedule(context: Context) {
    DbManager.initialize(context)
    val settings = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()
    val workManager = WorkManager.getInstance(context.applicationContext)

    if (!settings.autoDownloadEnabled) {
      workManager.cancelUniqueWork(WORK_NAME)
      Log.d(tag, "Auto download disabled, periodic work cancelled")
      return
    }

    val interval =
            if (settings.autoDownloadIntervalHours in VALID_INTERVAL_HOURS)
                    settings.autoDownloadIntervalHours
            else DEFAULT_INTERVAL_HOURS
    val wifiOnly = AutoDownloadPolicy.requiresUnmeteredNetwork(settings)
    val chargingOnly = settings.autoDownloadChargingOnly
    val constraints =
            Constraints.Builder()
                    .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(chargingOnly)
                    .build()
    val request =
            PeriodicWorkRequestBuilder<AutoDownloadWorker>(interval.toLong(), TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()

    // UPDATE keeps the last run time, so rescheduling on every app start does not reset the period
    workManager.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    AbsLogger.info(
            tag,
            "Auto download scheduled every ${interval}h (wifiOnly=$wifiOnly, chargingOnly=$chargingOnly)")
  }
}

class AutoDownloadWorker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
  private val tag = "AutoDownloadWorker"

  override suspend fun doWork(): Result {
    try {
      val status = AutoDownloadManager.check(applicationContext, AutoDownloadManager.Trigger.WORKER)
      AbsLogger.info(tag, "Auto download check finished with status ${status.jsValue}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      AbsLogger.error(tag, "Auto download check failed: ${e.message}")
    }
    return Result.success()
  }
}
