package app.absplus.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.absplus.android.MainActivity
import app.absplus.android.R
import app.absplus.android.models.DownloadItemPart
import app.absplus.android.plugins.AbsLogger

/** Android-owned foreground lifecycle for transfers that must outlive the WebView and Activity. */
class DownloadService : Service() {
  // Set once Android refused or ended the foreground service; this instance only stops
  @Volatile private var stopping = false

  override fun onCreate() {
    super.onCreate()
    ensureChannel(this)
    if (startForegroundWithType(DownloadServiceHost.notificationStrings(this).preparing)) {
      DownloadServiceHost.attachService(this)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CANCEL -> DownloadServiceHost.cancelAll(this)
      else -> {
        // Not sticky when refused: Android would restart the service only to be refused again
        if (stopping || !startForegroundWithType(DownloadServiceHost.notificationStrings(this).preparing)) {
          return START_NOT_STICKY
        }
        DownloadServiceHost.startWork(this)
      }
    }
    return if (stopping) START_NOT_STICKY else START_STICKY
  }

  // Android 15+ limits dataSync foreground services: stop within seconds; transfers pause and resume later
  override fun onTimeout(startId: Int, fgsType: Int) {
    AbsLogger.info(TAG, "Downloads reached the foreground service time limit; pausing them")
    stopBySystem()
  }

  override fun onTimeout(startId: Int) {
    onTimeout(startId, 0)
  }

  override fun onDestroy() {
    DownloadServiceHost.detachService(this)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  fun onPartUpdate(part: DownloadItemPart) {
    if (stopping) return
    val strings = DownloadServiceHost.notificationStrings(this)
    val text =
            if (part.waitingForSpace) strings.waitingForStorage
            else strings.downloadingFile.replace("{0}", part.filename)
    val progress = part.progress.coerceIn(0L, 100L).toInt()
    val notification = notification(text, progress, part.fileSize > 0L)
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
  }

  fun onQueueChanged(hasWork: Boolean) {
    if (!hasWork) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private fun startForegroundWithType(text: String): Boolean {
    val notification = notification(text)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
      return true
    } catch (e: RuntimeException) {
      // Android 15+ refuses dataSync once its daily time limit is used up, until the app is in the foreground again
      AbsLogger.error(TAG, "Download service could not enter the foreground (${e.javaClass.simpleName}: ${e.message}); pausing downloads")
      stopBySystem()
      return false
    }
  }

  private fun stopBySystem() {
    stopping = true
    DownloadServiceHost.pauseBySystem(this)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun notification(text: String, progress: Int = 0, determinate: Boolean = false): Notification {
    val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), pendingIntentFlags())
    return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(DownloadServiceHost.notificationStrings(this).downloads)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, !determinate)
            .addAction(0, DownloadServiceHost.notificationStrings(this).cancel, cancelIntent)
            .build()
  }

  private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

  companion object {
    const val CHANNEL_ID = "downloads"
    private const val NOTIFICATION_ID = 11
    private const val PAUSED_NOTIFICATION_ID = 13
    private const val TAG = "DownloadService"
    private const val ACTION_CANCEL = "app.absplus.android.download.CANCEL"
    fun intent(context: Context) = Intent(context, DownloadService::class.java)

    fun ensureChannel(context: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(
              NotificationChannel(
                      CHANNEL_ID,
                      DownloadServiceHost.notificationStrings(context).downloads,
                      NotificationManager.IMPORTANCE_LOW))
    }

    fun showPausedBySystem(context: Context) {
      val strings = DownloadServiceHost.notificationStrings(context)
      val open = PendingIntent.getActivity(
              context, 0,
              Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val notification = NotificationCompat.Builder(context, CHANNEL_ID)
              .setSmallIcon(R.drawable.icon)
              .setContentTitle(strings.downloads)
              .setContentText(strings.pausedBySystem)
              .setStyle(NotificationCompat.BigTextStyle().bigText(strings.pausedBySystem))
              .setContentIntent(open)
              .setAutoCancel(true)
              .build()
      // Without the notification permission Android drops it; the paused downloads stay queued either way
      (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(PAUSED_NOTIFICATION_ID, notification)
    }

    fun hidePausedBySystem(context: Context) {
      (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(PAUSED_NOTIFICATION_ID)
    }
  }
}
