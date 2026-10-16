package app.absplus.android.managers

import app.absplus.android.data.AudioTrack
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalItemCleanupTest {
  private fun file(id: String, name: String) =
          LocalFile(id, name, "", "", "/sd/Podcast/$name", if (name.endsWith(".jpg")) "image/jpeg" else "audio/mpeg", 10)

  private fun episode(id: String, localFileId: String) =
          PodcastEpisode(
                  id, 0, null, null, "Episode $id", null, null, null, null, null,
                  AudioTrack(0, 0.0, 10.0, "Episode $id", "", "audio/mpeg", null, true, localFileId, null),
                  null, 10.0, 10, id, null
          )

  private fun sdPodcast(): LocalLibraryItem {
    val files = mutableListOf(file("f1", "01.mp3"), file("f2", "02.mp3"), file("fc", "cover.jpg"))
    return LocalLibraryItem(
            "local_pod", "appsd-79EC-9B33-podcast", "", "", "", false, "podcast",
            Podcast(PodcastMetadata("Podcast", null, null, mutableListOf(), false), null, mutableListOf(),
                    mutableListOf(episode("e1", "f1"), episode("e2", "f2")), false, 2),
            files, null, "/sd/Podcast/cover.jpg", true, "server", null, null, "li_pod"
    )
  }

  private fun episodes(item: LocalLibraryItem) = (item.media as Podcast).episodes!!

  @Test
  fun skipsUnavailableSd() {
    val missing = sdPodcast()
    assertSame("a missing SD card leaves the item alone", ItemUpdate.Keep, LocalItemCleanup.clean(missing, { false }, skip = true))
    assertEquals(2, episodes(missing).size)
    assertEquals(3, missing.localFiles.size)
    assertEquals("/sd/Podcast/cover.jpg", missing.coverAbsolutePath)

    val deleted = sdPodcast()
    val update = LocalItemCleanup.clean(deleted, { false }, skip = false)
    assertTrue("files really gone are cleaned", update is ItemUpdate.Save)
    val saved = (update as ItemUpdate.Save).item
    assertEquals(0, episodes(saved).size)
    assertTrue(saved.localFiles.isEmpty())
    assertNull(saved.coverAbsolutePath)

    assertSame(ItemUpdate.Keep, LocalItemCleanup.clean(sdPodcast(), { true }, skip = false))
  }
}
