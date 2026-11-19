package app.absplus.android.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceData
import app.absplus.android.data.DeviceSettings
import app.absplus.android.managers.DbManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepTimerHeadsetExtendSettingsTest {
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
  fun settingsSavedBeforeTheOptionReadAsOff() {
    assertFalse(mapper.readValue<DeviceSettings>(settingsJson()).enableSleepTimerHeadsetExtend)
    assertFalse(DeviceSettings.default().enableSleepTimerHeadsetExtend)
  }

  @Test
  fun aSettingsSaveKeepsTheOptionThroughParseAndStorage() {
    val parsed = mapper.readValue<DeviceSettings>(settingsJson(""","enableSleepTimerHeadsetExtend":true"""))
    assertTrue(parsed.enableSleepTimerHeadsetExtend)

    val ctx: Context = ApplicationProvider.getApplicationContext()
    DbManager.initialize(ctx)
    DbManager().saveDeviceData(DeviceData(mutableListOf(), null, parsed, null))
    val stored = DbManager().getDeviceData().deviceSettings!!
    assertTrue(stored.enableSleepTimerHeadsetExtend)
    assertEquals(true, mapper.readTree(mapper.writeValueAsString(stored))["enableSleepTimerHeadsetExtend"].asBoolean())
  }
}
