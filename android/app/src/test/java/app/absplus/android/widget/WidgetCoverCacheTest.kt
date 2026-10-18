package app.absplus.android.widget

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceData
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.media.COVER_CACHE_DIR
import app.absplus.android.media.CoverCache
import app.absplus.android.player.resetFileProviderRoots
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetCoverCacheTest {
  private val ctx: Context = ApplicationProvider.getApplicationContext()

  @After
  fun tearDown() {
    CoverCache.setForTest(null)
    File(ctx.filesDir, COVER_CACHE_DIR).deleteRecursively()
  }

  @Test
  fun launcherStartedWidgetDrawsTheCachedCover() {
    DbManager.initialize(ctx)
    resetFileProviderRoots()
    val session = PlaybackSession(
      "session-w", "user", "book-w", null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
      emptyList(), "Book W", "Author", "/metadata/items/book-w/cover.jpg", 360.0, 0, 0L, 0L, 0L, mutableListOf(), 15.0, null, null, null,
      "config-1", "https://abs.example", null
    )
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), session)
    File(ctx.filesDir, COVER_CACHE_DIR).mkdirs()
    File(ctx.filesDir, "$COVER_CACHE_DIR/book-w_9.jpg").writeBytes(byteArrayOf(1, 2, 3))
    CoverCache.setForTest(null)

    // WidgetRenderer is frozen (Gate F), so its private fallback state and artwork are read reflectively
    val state = WidgetRenderer::class.java.getDeclaredMethod("fallbackState", Context::class.java).apply { isAccessible = true }
      .invoke(WidgetRenderer, ctx) as WidgetRenderer.State
    val uri = WidgetRenderer::class.java.getDeclaredMethod("artworkUri", Context::class.java, WidgetRenderer.State::class.java)
      .apply { isAccessible = true }.invoke(WidgetRenderer, ctx, state) as Uri

    assertEquals("content", uri.scheme)
    assertTrue(uri.toString().contains("/cover_cache/book-w_9.jpg"))
  }
}
