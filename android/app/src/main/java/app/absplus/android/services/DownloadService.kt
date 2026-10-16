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
import app.absplus.android.R
import app.absplus.android.models.DownloadItemPart

/** Android-owned foreground lifecycle for transfers that must outlive the WebView and Activity. */
class DownloadService : Service() {
  override fun onCreate() {
    super.onCreate()
    ensureChannel(this)
    startForegroundWithType(DownloadServiceHost.notificationStrings(this).preparing)
    DownloadServiceHost.attachService(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CANCEL -> DownloadServiceHost.cancelAll(this)
      else -> {
        startForegroundWithType(DownloadServiceHost.notificationStrings(this).preparing)
        DownloadServiceHost.startWork(this)
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    DownloadServiceHost.detachService(this)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  fun onPartUpdate(part: DownloadItemPart) {
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

  private fun startForegroundWithType(text: String) {
    val notification = notification(text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
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
  }
}
