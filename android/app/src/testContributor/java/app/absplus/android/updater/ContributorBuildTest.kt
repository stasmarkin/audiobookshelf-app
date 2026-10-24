package app.absplus.android.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContributorBuildTest {
  @Test fun packageAndVersionStayOnSeparateNonDebuggableChannel() {
    assertFalse(BuildConfig.DEBUG)
    assertEquals("app.absplus.android.contributor", BuildConfig.APPLICATION_ID)
    assertEquals(139, BuildConfig.VERSION_CODE)
    assertEquals("0.20.0-dev.2", BuildConfig.VERSION_NAME)
    assertEquals("0.20.0-dev.2", BuildConfig.PLUS_VERSION)
  }

  @Test fun testSourcesAndProductionDownloadsAreUnavailable() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    assertFalse(UpdaterConfig.TEST_SOURCES_SUPPORTED)
    assertNotNull(UpdaterConfig.setTestSource(ctx, "{}"))
    val source = UpdaterConfig.source(ctx)
    assertFalse(source.downloadsEnabled)
    assertEquals(ctx.packageName, source.targetPackage)
    assertFalse(source.trustedSignerSha256.contains(UpdateSource.PRODUCTION_SIGNER_SHA256))
    assertEquals(setOf("25802304ac05945342c5266666d43933dd93313836553c1e064f26782a5490a7"), source.trustedSignerSha256)
  }
}
