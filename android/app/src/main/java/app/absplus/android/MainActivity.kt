package app.absplus.android

import app.absplus.android.diagnostics.DLog
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.view.updateLayoutParams
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.SimpleStorageHelper
import app.absplus.android.managers.DbManager
import app.absplus.android.player.PlayerNotificationService
import app.absplus.android.plugins.AbsAudioPlayer
import app.absplus.android.plugins.AbsDatabase
import app.absplus.android.plugins.AbsDownloader
import app.absplus.android.plugins.AbsFileSystem
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.plugins.AbsAppearance
import app.absplus.android.plugins.AbsThemeImport
import app.absplus.android.plugins.AbsUpdater
import app.absplus.android.plugins.AbsData
import app.absplus.android.sync.SyncRuntime
import app.absplus.android.services.DownloadServiceHost
import app.absplus.android.services.StorageMoveService
import com.getcapacitor.BridgeActivity


class MainActivity : BridgeActivity() {
  private val tag = "MainActivity"

  private var mBounded = false
  lateinit var foregroundService : PlayerNotificationService
  private lateinit var mConnection : ServiceConnection

  lateinit var pluginCallback : () -> Unit

  val storageHelper = SimpleStorageHelper(this)
  val storage = SimpleStorage(this)

  val REQUEST_PERMISSIONS = 1

  public override fun onCreate(savedInstanceState: Bundle?) {
    DbManager.initialize(applicationContext)
    SyncRuntime.ensureInit(applicationContext)

    registerPlugin(AbsAudioPlayer::class.java)
    registerPlugin(AbsDownloader::class.java)
    registerPlugin(AbsFileSystem::class.java)
    registerPlugin(AbsDatabase::class.java)
    registerPlugin(AbsLogger::class.java)
    registerPlugin(AbsUpdater::class.java)
    registerPlugin(AbsAppearance::class.java)
    registerPlugin(AbsThemeImport::class.java)
    registerPlugin(AbsData::class.java)

    super.onCreate(savedInstanceState)
    DLog.d(tag, "onCreate")
    DLog.marker("App UI started (Activity created)")

    // Update the margins to handle edge-to-edge enforced in SDK 35
    // See: https://developer.android.com/develop/ui/views/layout/edge-to-edge
    val webView: WebView = findViewById(R.id.webview)
    webView.setOnApplyWindowInsetsListener { v, insets ->
      val (left, top, right, sysBottom, imeBottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val sysInsets = insets.getInsets(WindowInsets.Type.systemBars())
        val imeInsets = insets.getInsets(WindowInsets.Type.ime())
        DLog.d(tag, "safe sysInsets: $sysInsets imeInsets: $imeInsets")
        arrayOf(sysInsets.left, sysInsets.top, sysInsets.right, sysInsets.bottom, imeInsets.bottom)
      } else {
        arrayOf(
          insets.systemWindowInsetLeft,
          insets.systemWindowInsetTop,
          insets.systemWindowInsetRight,
          insets.systemWindowInsetBottom,
          0
        )
      }
      // With edge-to-edge enforced (targetSdk 35+), the keyboard no longer resizes the window
      // on its own even with windowSoftInputMode="adjustResize" - it only shows up as an IME
      // inset. Fold it into the WebView's bottom margin so the WebView (and therefore CSS
      // viewport units/media queries) actually shrinks when the keyboard is open.
      val bottom = maxOf(sysBottom, imeBottom)

      // Inject as CSS variables
      // NOTE: Possibly able to use in the future to support edge-to-edge better.
      // Kept as the system bar inset only (not the keyboard) so it stays meaningful as a
      // "safe area" value if it's ever wired up to CSS.
       val js = """
       document.documentElement.style.setProperty('--safe-area-inset-top', '${top}px');
       document.documentElement.style.setProperty('--safe-area-inset-bottom', '${sysBottom}px');
       document.documentElement.style.setProperty('--safe-area-inset-left', '${left}px');
       document.documentElement.style.setProperty('--safe-area-inset-right', '${right}px');
      """.trimIndent()
      webView.evaluateJavascript(js, null)

      // Set margins
      v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        leftMargin = left
        bottomMargin = bottom
        rightMargin = right
        topMargin = top
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowInsets.CONSUMED
      } else {
        insets
      }
    }

    requestNeededPermissions()
  }

  private fun requestNeededPermissions() {
    val needed = mutableListOf<String>()
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      needed.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (needed.isNotEmpty()) {
      ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
    }
  }

  override fun onResume() {
    super.onResume()
    isForeground = true
    SyncRuntime.setForeground(true)
    StorageMoveService.startIfNeeded(this)
    DownloadServiceHost.resumeAwaitingStorage()
    DownloadServiceHost.releaseAutoHold(this)
  }

  override fun onPause() {
    isForeground = false
    SyncRuntime.setForeground(false)
    super.onPause()
  }

  override fun onDestroy() {
    DLog.marker("App UI closed (Activity destroyed, finishing=$isFinishing)")
    super.onDestroy()
  }

  override fun onPostCreate(savedInstanceState: Bundle?) {
    super.onPostCreate(savedInstanceState)
    DLog.d(tag, "onPostCreate MainActivity")

    mConnection = object : ServiceConnection {
      override fun onServiceDisconnected(name: ComponentName) {
        DLog.w(tag, "Service Disconnected $name")
        mBounded = false
      }

      override fun onServiceConnected(name: ComponentName, service: IBinder) {
        DLog.d(tag, "Service Connected $name")

        mBounded = true
        val mLocalBinder = service as PlayerNotificationService.LocalBinder
        foregroundService = mLocalBinder.getService()

        // Let NativeAudio know foreground service is ready and setup event listener
        pluginCallback()
      }
    }

    Intent(this, PlayerNotificationService::class.java).also { intent ->
      DLog.d(tag, "Binding PlayerNotificationService")
      bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }
  }

  fun isPlayerNotificationServiceInitialized():Boolean {
    return ::foregroundService.isInitialized
  }

  fun stopMyService() {
    if (mBounded) {
      mConnection.let { unbindService(it) };
      mBounded = false;
    }
    val stopIntent = Intent(this, PlayerNotificationService::class.java)
    stopService(stopIntent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    storageHelper.onSaveInstanceState(outState)
    super.onSaveInstanceState(outState)
    outState.clear()
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    storageHelper.onRestoreInstanceState(savedInstanceState)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    // Mandatory for Activity, but not for Fragment & ComponentActivity
    storageHelper.storage.onActivityResult(requestCode, resultCode, data)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    DLog.d(tag, "onRequestPermissionResult $requestCode")
    permissions.forEach { DLog.d(tag, "PERMISSION $it") }
    grantResults.forEach { DLog.d(tag, "GRANTREUSLTS $it") }
    // Mandatory for Activity, but not for Fragment & ComponentActivity
    storageHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
  }

  companion object {
    // Foreground services may only be started while the app is visible (Android 12+)
    @Volatile var isForeground = false
  }
}
