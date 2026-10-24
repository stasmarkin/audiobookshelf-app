package app.absplus.android.managers

import app.absplus.android.data.PodcastEpisode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDownloadLimitWiringTest {
  private val manager = File("src/main/java/app/absplus/android/managers/AutoDownloadManager.kt").readText()

  @Test
  fun anEpisodeThatCannotBeDownloadedIsRefusedBeforeRoomIsMadeForIt() {
    val enqueue = manager.substring(manager.indexOf("WindowAction.ENQUEUE ->"))
    val refusal = enqueue.indexOf("destinationRefusal(")
    val fits = enqueue.indexOf("fitsLimit(")
    assertTrue(refusal in 0 until fits)
  }

  @Test
  fun anEpisodeWithoutAudioMetadataIsRefused() {
    assertEquals(LibraryItemDownloadStarter.EPISODE_REFUSAL, LibraryItemDownloadStarter.episodeRefusal(null))
    val noTrack = PodcastEpisode("e", 1, null, null, "e", null, null, null, null, null, null, null, 10.0, 3, "se", "e")
    assertEquals(LibraryItemDownloadStarter.EPISODE_REFUSAL, LibraryItemDownloadStarter.episodeRefusal(noTrack))
  }

  @Test
  fun theDestinationCheckRunsTheSameEpisodeValidatorAsTheStarter() {
    val starter = File("src/main/java/app/absplus/android/managers/LibraryItemDownloadStarter.kt").readText()
    val refusal = starter.substring(starter.indexOf("fun destinationRefusal("))
    assertTrue(refusal.substring(0, refusal.indexOf("val probe")).contains("episodeRefusal(episode)"))
    assertTrue(starter.substring(starter.indexOf("// Refuse before anything is queued")).take(200).contains("episodeRefusal(episode)"))
  }

  @Test
  fun theAutoDownloadWorkDoesNotCleanUp() {
    val preflight = manager.substring(manager.indexOf("private fun preflight("), manager.indexOf("private fun constraintsMet("))
    assertTrue(preflight.contains("trigger == Trigger.APP || trigger == Trigger.CLEANUP"))
    assertNull(Regex("Trigger\\.WORKER.*cleanup").find(preflight.lineSequence().first { it.contains("val cleanup") }))
  }

  @Test
  fun aRecentCleanupIsSkippedWithoutHoldingBackTheDownloadPart() {
    val preflight = manager.substring(manager.indexOf("private fun preflight("), manager.indexOf("private fun constraintsMet("))
    assertTrue(preflight.lineSequence().first { it.contains("val cleanup") }.contains("!skipCleanup"))
    assertTrue(preflight.lineSequence().first { it.contains("val download") }.contains("autoDownload"))
  }
}
