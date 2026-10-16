package app.absplus.android.managers

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.MainActivity
import app.absplus.android.data.DeviceData
import app.absplus.android.data.DeviceSettings
import app.absplus.android.device.DeviceManager
import app.absplus.android.services.StorageMoveService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageMoveServiceTriggerTest {
  @Test
  fun startDecision() {
    assertEquals("an unfinished pass resumes even with nothing to move", true, StorageMovePass.shouldStart(true, true))
    assertEquals(true, StorageMovePass.shouldStart(false, false))
    assertEquals("nothing to do", false, StorageMovePass.shouldStart(false, true))
    assertEquals(true, StorageMovePass.shouldStart(true, false))
  }

  @Test
  fun finishedDownloadDoesNotForceAPass() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    DbManager.initialize(ctx)
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    MainActivity.isForeground = false

    StorageMoveService.requestReconcile(ctx)
    shadowOf(Looper.getMainLooper()).idle()

    assertFalse("only a pass with something to move starts the service", DeviceManager.deviceData.reconcileNeeded)
    assertFalse(DbManager().getDeviceData().reconcileNeeded)
  }
}
