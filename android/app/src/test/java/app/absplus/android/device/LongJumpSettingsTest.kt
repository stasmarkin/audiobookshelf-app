package app.absplus.android.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceData
import app.absplus.android.data.DeviceSettings
import app.absplus.android.managers.DbManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Long jump lengths are set in the JS settings page and only stored natively. A settings save
 * (AbsDatabase.updateDeviceSettings) parses the JS object into DeviceSettings and persists it, so the
 * fields must survive that parse and the Paper round trip; settings saved before they existed read as Off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LongJumpSettingsTest {
  private val mapper = jacksonObjectMapper()

  private fun settingsJson(extra: String = "") = """
    {"disableAutoRewind":false,"enableAltView":true,"allowSeekingOnMediaControls":false,"jumpBackwardsTime":10,
     "jumpForwardTime":30,"enableMp3IndexSeeking":false,"disableShakeToResetSleepTimer":false,"shakeSensitivity":"MEDIUM",
     "lockOrientation":"NONE","hapticFeedback":"LIGHT","autoSleepTimer":false,"autoSleepTimerStartTime":"22:00",
     "autoSleepTimerEndTime":"06:00","autoSleepTimerAutoRewind":false,"autoSleepTimerAutoRewindTime":300000,
     "sleepTimerLength":900000,"disableSleepTimerFadeOut":false,"disableSleepTimerResetFeedback":false,
     "enableSleepTimerAlmostDoneChime":false,"languageCode":"en-us","downloadUsingCellular":"ALWAYS",
     "streamingUsingCellular":"ALWAYS","androidAutoBrowseLimitForGrouping":100,"androidAutoBrowseSeriesSequenceOrder":"ASC"$extra}
  """.trimIndent()

  @Test
  fun settingsSavedBeforeLongJumpsReadAsOff() {
    val settings = mapper.readValue<DeviceSettings>(settingsJson())
    assertEquals(0, settings.longJumpBackwardsTime)
    assertEquals(0, settings.longJumpForwardTime)
    assertEquals(30, settings.jumpForwardTime)
    assertEquals(0, DeviceSettings.default().longJumpBackwardsTime)
    assertEquals(0, DeviceSettings.default().longJumpForwardTime)
  }

  @Test
  fun aSettingsSaveKeepsLongJumpsThroughParseAndStorage() {
    val parsed = mapper.readValue<DeviceSettings>(settingsJson(""","longJumpBackwardsTime":300,"longJumpForwardTime":60"""))
    assertEquals(300, parsed.longJumpBackwardsTime)
    assertEquals(60, parsed.longJumpForwardTime)

    val ctx: Context = ApplicationProvider.getApplicationContext()
    DbManager.initialize(ctx)
    DbManager().saveDeviceData(DeviceData(mutableListOf(), null, parsed, null))
    val stored = DbManager().getDeviceData().deviceSettings!!
    assertEquals(300, stored.longJumpBackwardsTime)
    assertEquals(60, stored.longJumpForwardTime)

    // What JS receives back after the save
    val echoed = mapper.readTree(mapper.writeValueAsString(stored))
    assertEquals(300, echoed["longJumpBackwardsTime"].asInt())
    assertEquals(60, echoed["longJumpForwardTime"].asInt())
  }
}
