package app.absplus.android.player

import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import app.absplus.android.sync.ServerState
import app.absplus.android.db.userdata.MediaProgressEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
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

  // The service's side of a queue start that waits: the real AwaitingServer and presentation
  private val awaiting = AwaitingServer()
  private var server = ServerState.OFFLINE
  private var queueCurrent: PlaylistQueueItem? = itemB
  private var resumed: AwaitingAction? = null
  private val answers = mutableListOf<AwaitingStart?>()

  private suspend fun queueStart(read: suspend () -> AwaitingDisplay?) = startAwaiting(
    read = read,
    stillCurrent = { queueCurrent == itemB },
    block = { display ->
      awaiting.onQueueBlocked(itemB, "srv")
      presentation.begin(itemB, display)
      true
    },
    answer = { answers.add(it) },
    resumeIfOnline = { resumed = awaiting.onServerState(server, "srv") }
  )

  @Test
  fun aQueueStartWithASlowReplicaReadAnswersWithTheDisplay() = runBlocking {
    val read = CompletableDeferred<AwaitingDisplay?>()
    val start = async { queueStart { read.await() } }
    yield()
    assertEquals("nothing is shown before the replica answered", emptyList<Pair<Boolean, AwaitingDisplay?>>(), events)
    read.complete(displayB)
    start.await()
    assertEquals(listOf<AwaitingStart?>(AwaitingStart(displayB)), answers)
    assertEquals(listOf(true to displayB), events)
    assertEquals(displayB, presentation.display)
  }

  @Test
  fun anItemMissingFromTheReplicaAnswersWithoutADisplay() = runBlocking {
    queueStart { null }
    assertNull("the app shows its toast", answers.single()!!.display)
    assertEquals(listOf(true to null), events)
  }

  @Test
  fun aReplacedStartAnswersNothing() = runBlocking {
    startAwaiting({ displayB }, { true }, { false }, { answers.add(it) }, { resumed = AwaitingAction.GiveUp })
    assertEquals(listOf<AwaitingStart?>(null), answers)
    assertNull(resumed)
    assertEquals(emptyList<Pair<Boolean, AwaitingDisplay?>>(), events)
  }

  @Test
  fun aServerBackDuringTheReadResumesAtOnce() = runBlocking {
    val read = CompletableDeferred<AwaitingDisplay?>()
    val start = async { queueStart { read.await() } }
    yield()
    // The online event came and went before the wait existed: nothing else will resume it
    server = ServerState.ONLINE
    read.complete(displayB)
    start.await()
    assertEquals(AwaitingStart(displayB), answers.single())
    assertEquals(itemB, (resumed as AwaitingAction.ResumeStream).queueItem)
  }

  @Test
  fun aQueueReplacedDuringTheReadDoesNotWait() = runBlocking {
    val read = CompletableDeferred<AwaitingDisplay?>()
    val start = async { queueStart { read.await() } }
    yield()
    queueCurrent = itemC
    read.complete(displayB)
    start.await()
    assertEquals(listOf<AwaitingStart?>(null), answers)
    assertNull("the removed item is not awaited", awaiting.blockedItem)
    assertEquals(emptyList<Pair<Boolean, AwaitingDisplay?>>(), events)
  }

  @Test
  fun theAwaitedItemIsPublishedPausedWithPlayOnly() {
    val state = awaitingPlaybackState(displayB)
    assertEquals(PlaybackStateCompat.STATE_PAUSED, state.state)
    assertEquals(42_000L, state.position)
    assertEquals(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP, state.actions)
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
    val cover = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    assertEquals("the cover decoded off the main thread", cover, nowPlayingMetadata(ctx, sessionA(), displayB, "Waiting for server", cover)
      .getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART))

    assertEquals("Book A", nowPlayingMetadata(ctx, sessionA(), null, "Waiting for server").getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
    assertNull(nowPlayingMetadata(ctx, null, null, "Waiting for server").getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
  }

  @Test
  fun aSlowReadLetsTheStartGoOnAndShowsTheDisplayLate() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default)
    val late = CompletableDeferred<AwaitingDisplay?>()
    // A blocking read (a cover on an SD card, a slow Paper query) is not cut off by the start's timeout
    val now = readWithin(scope, 50, { Thread.sleep(300); displayB }) { late.complete(it) }
    assertNull("the start does not wait for it", now)
    assertEquals("it is shown once it arrives", displayB, withTimeout(2_000) { late.await() })
  }

  @Test
  fun aReadWithinTheTimeoutIsNotDeliveredTwice() = runBlocking {
    var late = 0
    assertEquals(displayB, readWithin(this, 1_000, { displayB }) { late++ })
    yield()
    assertEquals(0, late)
  }

  @Test
  fun aBusyWriteQueueLosesOnlyThePosition() = runBlocking {
    val never = CompletableDeferred<MediaProgressEntity?>()
    val shown = readAwaitingDisplayRow(50, { never.await() }) { progress -> displayB.copy(currentTime = progress?.currentTime ?: 0.0) }
    assertEquals(displayB.copy(currentTime = 0.0), shown)
  }

  @Test
  fun aCoverThatFailsOrComesLateNeverHidesTheDisplay() = runBlocking {
    presentation.begin(itemB, displayB)
    val applied = mutableListOf<AwaitingCover>()
    loadAwaitingCover(displayB, { throw java.io.IOException("card gone") }, { presentation.display }) { applied.add(it) }
    assertEquals(displayB, presentation.display)
    val cover = AwaitingCover(android.net.Uri.parse("file:///c.jpg"), null)
    presentation.end()
    loadAwaitingCover(displayB, { cover }, { presentation.display }) { applied.add(it) }
    assertEquals("not for an item no longer shown", emptyList<AwaitingCover>(), applied)
    presentation.begin(itemB, displayB)
    loadAwaitingCover(displayB, { cover }, { presentation.display }) { applied.add(it) }
    assertEquals(listOf(cover), applied)
  }
}
