package app.absplus.android.player

import app.absplus.android.data.LibraryItem
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.media.offlineSubtitle
import app.absplus.android.sync.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android Auto without the server: an item it cannot stream is refused at once and marked in the lists (Р18, Р19). */
class AutoPlayTest {
  private val label = "Not available offline"
  private val book = LibraryItem("li1", "42", "lib", "f", "", "", 0L, 0L, 0L, 0L, 0L, null, null, false, false, "book",
    MediaType(MediaTypeMetadata("Book", false), null), null, null, null, null, null)
  private val autoRequest = StartRequest.forWrapper(book, null, playWhenReady = true, playbackRate = 1f)
  private val stream = PlaybackSource.Stream("li1", null)
  private val queueItem = PlayerNotificationService.PlaylistQueueItem("li1", null, "42", "a")

  @Test fun offlineStreamFailsFastOnlyForAuto() {
    assertTrue(autoStreamRefusal(autoRequest, stream, ServerState.OFFLINE, null))
    assertFalse("a cold start before the first probe answers: the play request may work",
      autoStreamRefusal(autoRequest, stream, ServerState.UNKNOWN, null))
    assertTrue("no active server: nothing to stream from",
      autoStreamRefusal(autoRequest, stream, ServerState.UNKNOWN, null, hasActiveServer = false))
    assertFalse(autoStreamRefusal(autoRequest, stream, ServerState.ONLINE, null))
    assertFalse("the server answers: the start fails as before", autoStreamRefusal(autoRequest, stream, ServerState.UNAUTHORIZED, null))
    assertFalse("the app's queue waits instead", autoStreamRefusal(StartRequest.forQueueItem(queueItem, true), stream, ServerState.OFFLINE, null))
    assertFalse(autoStreamRefusal(autoRequest, stream, ServerState.OFFLINE, queueItem))
    val copy = PlaybackSource.Local(LocalLibraryItem("local_1", "internal-book", "", "", "", false, "book",
      MediaType(MediaTypeMetadata("Book", false), null), mutableListOf(), null, null, true, "a", null, null, "li1", "42"), null)
    assertFalse(autoStreamRefusal(autoRequest, copy, ServerState.OFFLINE, null))
  }

  @Test fun refusalSkipsTheNetwork() {
    var played = 0
    val results = mutableListOf<PlayerNotificationService.StartResult>()
    streamStart(refuse = true, message = label, play = { played++ }) { results += it }
    assertEquals(0, played)
    assertEquals(listOf(PlayerNotificationService.StartResult.Failed(label)), results)

    streamStart(refuse = false, message = label, play = { played++ }) { results += it }
    assertEquals(1, played)
    assertEquals(1, results.size)
  }

  @Test fun unknownIdOfflineReportsAnError() {
    assertEquals(label, unknownItemMessage(ServerState.OFFLINE, label, "Item not found"))
    assertEquals(label, unknownItemMessage(ServerState.UNKNOWN, label, "Item not found"))
    assertEquals("Item not found", unknownItemMessage(ServerState.ONLINE, label, "Item not found"))
  }

  @Test fun subtitleMarksOnlyUndownloadedOffline() {
    assertEquals("Author · $label", offlineSubtitle("Author", downloaded = false, ServerState.OFFLINE, label).toString())
    assertEquals(label, offlineSubtitle(null, downloaded = false, ServerState.OFFLINE, label).toString())
    assertEquals(label, offlineSubtitle("", downloaded = false, ServerState.OFFLINE, label).toString())
    assertEquals("the first probe has not answered yet", "Author", offlineSubtitle("Author", downloaded = false, ServerState.UNKNOWN, label))
    assertEquals("Author", offlineSubtitle("Author", downloaded = true, ServerState.OFFLINE, label))
    assertEquals("Author", offlineSubtitle("Author", downloaded = false, ServerState.ONLINE, label))
    assertEquals("Author", offlineSubtitle("Author", downloaded = false, ServerState.UNAUTHORIZED, label))
  }
}
