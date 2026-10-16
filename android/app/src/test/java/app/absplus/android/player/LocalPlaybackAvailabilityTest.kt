package app.absplus.android.player

import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.device.AppVolume
import app.absplus.android.device.VolumeProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class LocalPlaybackAvailabilityTest {
  private class FakeProbe(private val volumes: List<AppVolume>) : VolumeProbe {
    override fun volumes() = volumes
    override fun internalFilesDir() = File("/data/user/0/app/files")
  }

  private fun sdVolume(mounted: Boolean) =
          AppVolume("79EC-9B33", File("/storage/79EC-9B33/Android/data/app/files"), true, mounted, mounted)

  private fun item(folderId: String) =
          LocalLibraryItem(
                  "local_1", folderId, "", "", "", false, "podcast",
                  Podcast(PodcastMetadata("Podcast", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0),
                  mutableListOf(), null, null, true, null, null, null, null
          )

  @Test
  fun unavailableBeforeNoFiles() {
    var asked = false
    val hasTracks = { asked = true; false }
    val sd = item("appsd-79EC-9B33-podcast")

    assertEquals(LocalAvailability.STORAGE_UNAVAILABLE, LocalPlaybackAvailability.check(sd, FakeProbe(listOf(sdVolume(mounted = false))), hasTracks))
    assertEquals(LocalAvailability.STORAGE_UNAVAILABLE, LocalPlaybackAvailability.check(sd, FakeProbe(emptyList()), hasTracks))
    assertFalse("files are not checked on a missing card", asked)
  }

  @Test
  fun internalMissingFiles() {
    assertEquals(LocalAvailability.NO_FILES, LocalPlaybackAvailability.check(item("internal-book"), FakeProbe(emptyList())) { false })
  }

  @Test
  fun availableWithFiles() {
    val probe = FakeProbe(listOf(sdVolume(mounted = true)))
    assertEquals(LocalAvailability.OK, LocalPlaybackAvailability.check(item("appsd-79EC-9B33-podcast"), probe) { true })
    assertEquals(LocalAvailability.OK, LocalPlaybackAvailability.check(item("internal-book"), probe) { true })
  }
}
