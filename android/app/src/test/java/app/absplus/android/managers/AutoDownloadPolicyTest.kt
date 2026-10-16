package app.absplus.android.managers

import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.DownloadUsingCellularSetting
import app.absplus.android.data.LibraryItemsPage
import app.absplus.android.data.PodcastAutoDownloadEntry
import app.absplus.android.services.DownloadServiceHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDownloadPolicyTest {
  private data class Ep(val id: String, val publishedAt: Long?, val playable: Boolean = true)

  private fun entry(enabled: String, keepCount: Int? = null) = PodcastAutoDownloadEntry(enabled, keepCount, mutableListOf())

  private fun window(eps: List<Ep>, keep: Int) =
          AutoDownloadPolicy.newestWindow(eps, keep, { it.publishedAt }) { it.playable }.map { it.id }

  @Test
  fun `window keeps the newest playable episodes and treats a missing date as the oldest`() {
    val eps = listOf(Ep("old", 1), Ep("undated", null), Ep("new", 3), Ep("broken", 4, playable = false), Ep("mid", 2))
    assertEquals(listOf("new", "mid"), window(eps, 2))
    assertEquals(listOf("new", "mid", "old", "undated"), window(eps, 10))
    assertEquals(emptyList<String>(), window(eps, 0))
  }

  @Test
  fun `per-podcast choice overrides the all-podcasts default`() {
    val all = DeviceSettings.default().apply { autoDownloadAllPodcasts = true }
    val optIn = DeviceSettings.default().apply { autoDownloadAllPodcasts = false }
    assertTrue(AutoDownloadPolicy.isEnabledFor(entry(PodcastAutoDownloadEntry.ENABLED_DEFAULT), all))
    assertFalse(AutoDownloadPolicy.isEnabledFor(entry(PodcastAutoDownloadEntry.ENABLED_DEFAULT), optIn))
    assertTrue(AutoDownloadPolicy.isEnabledFor(entry(PodcastAutoDownloadEntry.ENABLED_ON), optIn))
    assertFalse(AutoDownloadPolicy.isEnabledFor(entry(PodcastAutoDownloadEntry.ENABLED_OFF), all))
  }

  @Test
  fun `per-podcast keep count falls back to the global one`() {
    val settings = DeviceSettings.default().apply { autoDownloadKeepCount = 5 }
    assertEquals(5, AutoDownloadPolicy.keepCountFor(entry(PodcastAutoDownloadEntry.ENABLED_ON), settings))
    assertEquals(1, AutoDownloadPolicy.keepCountFor(entry(PodcastAutoDownloadEntry.ENABLED_ON, keepCount = 1), settings))
  }

  @Test
  fun `cellular is allowed only when both the wifi-only switch and the cellular setting permit it`() {
    val settings = DeviceSettings.default().apply {
      autoDownloadWifiOnly = false
      downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS
    }
    assertFalse(AutoDownloadPolicy.requiresUnmeteredNetwork(settings))
    settings.downloadUsingCellular = DownloadUsingCellularSetting.ASK
    assertTrue(AutoDownloadPolicy.requiresUnmeteredNetwork(settings))
    settings.downloadUsingCellular = DownloadUsingCellularSetting.NEVER
    assertTrue(AutoDownloadPolicy.requiresUnmeteredNetwork(settings))
    settings.downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS
    settings.autoDownloadWifiOnly = true
    assertTrue(AutoDownloadPolicy.requiresUnmeteredNetwork(settings))
  }

  @Test
  fun `library page paging stops on an empty, short or final page`() {
    assertFalse(LibraryItemsPage(emptyList(), total = 250, page = 0, limit = 100, rawCount = 100).isLastPage)
    assertTrue(LibraryItemsPage(emptyList(), total = 250, page = 2, limit = 100, rawCount = 50).isLastPage)
    assertTrue(LibraryItemsPage(emptyList(), total = 250, page = 3, limit = 100, rawCount = 0).isLastPage)
    assertTrue(LibraryItemsPage(emptyList(), total = 0, page = 0, limit = 100, rawCount = 0).isLastPage)
    assertTrue(LibraryItemsPage(emptyList(), total = 10, page = 0, limit = 0, rawCount = 10).isLastPage)
  }

  @Test
  fun outcomeFor() {
    assertEquals(AutoDownloadPolicy.EpisodeOutcome.SKIPPED, AutoDownloadPolicy.outcomeFor(LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE))
    assertEquals(AutoDownloadPolicy.EpisodeOutcome.QUEUED, AutoDownloadPolicy.outcomeFor(null))
    assertEquals(AutoDownloadPolicy.EpisodeOutcome.QUEUED, AutoDownloadPolicy.outcomeFor(DownloadServiceHost.SERVICE_START_FAILED_MESSAGE))
    assertEquals(AutoDownloadPolicy.EpisodeOutcome.FAILED, AutoDownloadPolicy.outcomeFor("Server request failed"))
  }
}
