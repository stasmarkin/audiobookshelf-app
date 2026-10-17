package app.absplus.android.player

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AwaitingDisplayPublishTest {
  private val itemB = PlaylistQueueItem("book-b", null)
  private val itemC = PlaylistQueueItem("book-c", null)
  private val displayB = AwaitingDisplay("book-b", null, "srv", "Book B", "Author B", null, 600.0, 42.0)
  private val events = mutableListOf<Pair<Boolean, AwaitingDisplay?>>()
  private val presentation = AwaitingPresentation { awaiting, display -> events.add(awaiting to display) }

  private fun sessionA() = PlaybackSession(
    "session-a", "user", "book-a", null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
    emptyList(), "Book A", "Author A", null, 360.0, 0, 0L, 0L, 0L, mutableListOf(), 15.0, null, null, null, "srv", null, null
  )

  @Test
  fun aQueueStartWithASlowReplicaReadAnswersWithTheDisplay() = runBlocking {
    val read = CompletableDeferred<AwaitingDisplay?>()
    val start = async { startAwaiting({ read.await() }) { display -> presentation.begin(itemB, display); true } }
    yield()
    assertEquals("nothing is shown before the replica answered", emptyList<Pair<Boolean, AwaitingDisplay?>>(), events)
    read.complete(displayB)
    assertEquals(displayB, start.await()!!.display)
    assertEquals(listOf(true to displayB), events)
    assertEquals(displayB, presentation.display)
  }

  @Test
  fun anItemMissingFromTheReplicaAnswersWithoutADisplay() = runBlocking {
    val start = startAwaiting({ null }) { display -> presentation.begin(itemB, display); true }
    assertNull("the app shows its toast", start!!.display)
    assertEquals(listOf(true to null), events)
  }

  @Test
  fun aReplacedStartAnswersNothing() = runBlocking {
    assertNull(startAwaiting({ displayB }) { false })
    assertEquals(emptyList<Pair<Boolean, AwaitingDisplay?>>(), events)
  }

  @Test
  fun aDisplayReadAfterTheWaitBeganIsPublishedAgain() {
    presentation.begin(itemB, null)
    assertEquals(true, presentation.onRead(itemB, displayB, blocked = itemB))
    assertEquals(listOf(true to null, true to displayB), events)
  }

  @Test
  fun aReadThatFinishesAfterCancelOrAnotherItemIsNotPublished() {
    presentation.begin(itemB, null)
    presentation.end()
    assertFalse(presentation.onRead(itemB, displayB, blocked = null))
    presentation.begin(itemC, null)
    assertFalse(presentation.onRead(itemB, displayB, blocked = itemC))
    assertEquals(listOf(true to null, false to null, true to null), events)
    assertNull(presentation.display)
  }

  @Test
  fun aResumeKeepsTheDisplayUntilItEndsAndAFailedResumeShowsItAgain() {
    presentation.begin(itemB, displayB)
    presentation.resuming()
    presentation.begin(itemB, null)
    presentation.end()
    assertEquals(listOf(true to displayB, false to displayB, true to displayB, false to null), events)
  }

  @Test
  fun aLostStreamShowsNoDisplay() {
    presentation.begin(itemB, displayB)
    presentation.begin(null, null)
    assertEquals(listOf(true to displayB, true to null), events)
  }

  @Test
  fun mediaSessionMetadataIsTheAwaitedBookWhileBookAIsOpen() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val awaited = nowPlayingMetadata(ctx, sessionA(), displayB, "Waiting for server")
    assertEquals("Book B", awaited.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
    assertEquals("Book B", awaited.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
    assertEquals("Waiting for server", awaited.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE))
    assertEquals("Author B", awaited.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
    assertEquals(600_000L, awaited.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))

    assertEquals("Book A", nowPlayingMetadata(ctx, sessionA(), null, "Waiting for server").getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
    assertNull(nowPlayingMetadata(ctx, null, null, "Waiting for server").getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
  }
}
