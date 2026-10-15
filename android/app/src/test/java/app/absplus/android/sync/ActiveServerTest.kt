package app.absplus.android.sync

import android.content.Context
import app.absplus.android.device.DeviceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActiveServerTest {
  private lateinit var f: SyncTestFixture

  @Before fun setUp() { f = SyncTestFixture() }

  @After fun tearDown() = f.close()

  private fun storedId() = f.context.getSharedPreferences("absplus_sync", Context.MODE_PRIVATE).getString("activeServerConfigId", null)

  @Test
  fun seedsFromLastServerConnectionConfigOnUpgrade() {
    f.save(f.config("a"))
    DeviceManager.deviceData.lastServerConnectionConfigId = "a"
    ActiveServer.init(f.context)
    assertEquals("a", ActiveServer.current()?.id)
    assertEquals("a", storedId())

    ActiveServer.resetForTest()
    f.save()
    ActiveServer.init(f.context)
    assertNull("the last server is no longer saved", ActiveServer.current())
    assertNull(storedId())
  }

  @Test
  fun survivesLogoutAndRejection() {
    f.save(f.config("a"))
    ActiveServer.init(f.context)
    ActiveServer.remember("a")
    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData.lastServerConnectionConfigId = null
    assertEquals("a", ActiveServer.current()?.id)

    ActiveServer.forget("b")
    assertEquals("forgetting another server keeps this one", "a", ActiveServer.current()?.id)
    ActiveServer.forget("a")
    assertNull(ActiveServer.current())
    assertNull(storedId())
  }

  @Test
  fun rememberedServerSurvivesProcessRestart() {
    f.save(f.config("a"), f.config("b"))
    f.context.getSharedPreferences("absplus_sync", Context.MODE_PRIVATE).edit().putString("activeServerConfigId", "b").commit()
    DeviceManager.deviceData.lastServerConnectionConfigId = "a"
    ActiveServer.init(f.context)
    assertEquals("b", ActiveServer.current()?.id)
  }
}
