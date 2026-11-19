package app.absplus.android.player

import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueIdentityTest {
  private fun copy(id: String, serverId: String?, config: String? = "a", ino: String? = null) = LocalLibraryItem(
    id, "internal-book", "", "", "", false, "book", MediaType(MediaTypeMetadata("Book", false), null),
    mutableListOf(), null, null, true, config, null, null, serverId, ino
  )

  private fun session(serverId: String?, episodeId: String?, copy: LocalLibraryItem?, localEpisodeId: String? = null) = PlaybackSession(
    "s", null, serverId, episodeId, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
    emptyList(), "Book", "Author", null, 360.0, if (copy != null) PLAYMETHOD_LOCAL else PLAYMETHOD_DIRECTPLAY, 0L, 0L, 0L,
    mutableListOf(), 0.0, null, copy, localEpisodeId, copy?.serverConnectionConfigId ?: "a", null, null
  )

  @Test fun serverIdItemMatchesLocalSession() {
    val local = session("li1", null, copy("local_x", "li1"))
    assertTrue(PlaylistQueueItem("li1", null).matches(local))
    assertTrue("a queue saved by an older version names the copy", PlaylistQueueItem("local_x", null).matches(local))
    val inoCopy = session("liB", null, copy("local_b", "liB", ino = "42"))
    assertTrue("the copy the resolver found by ino", PlaylistQueueItem("liA", null, "42", "a").matches(inoCopy))
    assertFalse("the same ino of another account", PlaylistQueueItem("liA", null, "42", "b").matches(inoCopy))
    assertFalse("an item without a server is never matched by ino", PlaylistQueueItem("liA", null, "42", null).matches(inoCopy))
    assertFalse(PlaylistQueueItem("other", null).matches(local))
  }

  @Test fun streamedSessionMatchesItsServerItemAndEpisode() {
    val stream = session("p", "e1", null)
    assertTrue(PlaylistQueueItem("p", "e1").matches(stream))
    assertFalse(PlaylistQueueItem("p", "e2").matches(stream))
    assertFalse(PlaylistQueueItem("p", null).matches(stream))
  }

  @Test fun downloadedEpisodeMatchesByServerOrLocalEpisodeId() {
    val local = session("p", "e1", copy("local_p", "p"), localEpisodeId = "local_e1")
    assertTrue(PlaylistQueueItem("p", "e1").matches(local))
    assertTrue(PlaylistQueueItem("local_p", "local_e1").matches(local))
    assertFalse(PlaylistQueueItem("local_p", "e1").matches(local))
    assertFalse(PlaylistQueueItem("p", "local_e1").matches(local))
  }

  @Test fun localMediaWithoutServerMatchesByDeviceId() {
    val sideloaded = session(null, null, copy("local_s", null, config = null))
    assertTrue(PlaylistQueueItem("local_s", null).matches(sideloaded))
    assertFalse(PlaylistQueueItem("s", null).matches(sideloaded))
  }
}
