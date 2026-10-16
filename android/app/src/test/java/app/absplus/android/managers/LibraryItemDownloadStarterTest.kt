package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryItemDownloadStarterTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun episode(id: String) =
          PodcastEpisode(id, 1, "1", "full", "Episode", null, null, null, 1L, null, null, null, 60.0, 1000L, null, null)

  private fun podcastItem(id: String) =
          LibraryItem(
                  id, "ino", "lib", "folder", "/p", "p", 0, 0, 0, 0, 0, null, null, false, false, "podcast",
                  Podcast(PodcastMetadata("Show", "Host", "https://feed", mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0),
                  null, null, null, null, null
          )

  private fun start(itemId: String, episodeId: String): String? {
    var error: String? = "not called"
    LibraryItemDownloadStarter.startLibraryItemDownload(context, podcastItem(itemId), "internal-podcast", episode(episodeId)) { error = it }
    return error
  }

  @Test
  fun unsafeIdentifiersAreRejectedBeforeAnyPathIsBuilt() {
    assertEquals("Invalid download identifier", start("../escape", "ep-1"))
    assertEquals("Invalid download identifier", start("li-1", "ep/../1"))
    assertFalse(File(context.filesDir, "escape").exists())
    assertFalse(File(context.filesDir, "download-staging").exists())
  }
}
