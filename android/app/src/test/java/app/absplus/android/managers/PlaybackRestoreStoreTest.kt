package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.player.PLAYMETHOD_DIRECTPLAY
import app.absplus.android.player.PLAYMETHOD_LOCAL
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The state a widget/headset Play needs to rebuild playback after the player service (or the whole
 * process) is gone. The store is stateless over SharedPreferences, so reading it back through a fresh
 * call is what a recreated service sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackRestoreStoreTest {
  private lateinit var ctx: Context
  private val twelve = (0 until 12).map { PlaylistQueueItem("local_device_b$it", null) }

  private fun copy(id: String, serverId: String? = null, config: String? = null, ino: String? = null) = LocalLibraryItem(
    id, "internal-book", "", "", "", false, "book", MediaType(MediaTypeMetadata("Book", false), null),
    mutableListOf(), null, null, true, config, null, null, serverId, ino
  )

  /** The session restoreLastPlaybackSession rebuilds: a downloaded copy, or a stream when [copy] is null. */
  private fun session(serverId: String?, episodeId: String? = null, copy: LocalLibraryItem? = null, localEpisodeId: String? = null) = PlaybackSession(
    "s", null, serverId ?: copy?.libraryItemId, episodeId, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
    emptyList(), "Book", "Author", null, 360.0, if (copy != null) PLAYMETHOD_LOCAL else PLAYMETHOD_DIRECTPLAY, 0L, 0L, 0L,
    mutableListOf(), 0.0, null, copy, localEpisodeId, copy?.serverConnectionConfigId, null, null
  )

  private fun rawPrefs() = ctx.getSharedPreferences("absplus_playback_restore", Context.MODE_PRIVATE)

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    rawPrefs().edit().clear().commit()
  }

  @Test
  fun resumableDefaultsToFalseAndPersists() {
    assertFalse(PlaybackRestoreStore.isResumable(ctx))
    PlaybackRestoreStore.setResumable(ctx, true)
    assertTrue(PlaybackRestoreStore.isResumable(ctx))
    assertTrue(rawPrefs().getBoolean("resumable", false))
    // Player close (X) clears it
    PlaybackRestoreStore.setResumable(ctx, false)
    assertFalse(PlaybackRestoreStore.isResumable(ctx))
  }

  @Test
  fun twelveItemQueueAndIndexRoundTrip() {
    PlaybackRestoreStore.saveQueue(ctx, twelve, 7)
    val loaded = PlaybackRestoreStore.loadQueue(ctx)
    assertEquals(twelve, loaded.items)
    assertEquals(7, loaded.index)
  }

  @Test
  fun podcastEpisodeIdentityAndStreamingEntriesSurvive() {
    val mixed = listOf(PlaylistQueueItem("local_p", "local_e1"), PlaylistQueueItem("server-p", "e2"), PlaylistQueueItem("server-book", null))
    PlaybackRestoreStore.saveQueue(ctx, mixed, 1)
    assertEquals(PlaybackRestoreStore.SavedQueue(mixed, 1), PlaybackRestoreStore.loadQueue(ctx))
  }

  @Test
  fun missingSavedQueueLoadsAsEmpty() {
    assertEquals(PlaybackRestoreStore.SavedQueue(emptyList(), -1), PlaybackRestoreStore.loadQueue(ctx))
  }

  @Test
  fun clearedQueueLoadsAsEmpty() {
    PlaybackRestoreStore.saveQueue(ctx, twelve, 3)
    PlaybackRestoreStore.saveQueue(ctx, emptyList(), -1)
    assertEquals(PlaybackRestoreStore.SavedQueue(emptyList(), -1), PlaybackRestoreStore.loadQueue(ctx))
  }

  @Test
  fun malformedSavedQueueLoadsAsEmptyInsteadOfThrowing() {
    rawPrefs().edit().putString("queue", "{not json").putInt("queueIndex", 2).commit()
    assertEquals(PlaybackRestoreStore.SavedQueue(emptyList(), -1), PlaybackRestoreStore.loadQueue(ctx))
    rawPrefs().edit().putString("queue", """[{"episodeId":null}]""").commit()
    assertEquals(PlaybackRestoreStore.SavedQueue(emptyList(), -1), PlaybackRestoreStore.loadQueue(ctx))
  }

  @Test
  fun queueIsReattachedOnlyWhenItsCurrentEntryIsTheRestoredItem() {
    val saved = PlaybackRestoreStore.SavedQueue(twelve, 7)
    assertSame(saved, PlaybackRestoreStore.queueForRestoredItem(saved, session(null, copy = copy("local_device_b7"))))
    assertNull(PlaybackRestoreStore.queueForRestoredItem(saved, session(null, copy = copy("local_device_b6"))))
    assertNull(PlaybackRestoreStore.queueForRestoredItem(saved, session(null, copy = copy("local_device_b7"), localEpisodeId = "episode")))
    assertNull(PlaybackRestoreStore.queueForRestoredItem(PlaybackRestoreStore.SavedQueue(twelve, -1), session(null, copy = copy("local_device_b0"))))
    assertNull(PlaybackRestoreStore.queueForRestoredItem(PlaybackRestoreStore.SavedQueue(twelve, 12), session(null, copy = copy("local_device_b11"))))
    assertNull(PlaybackRestoreStore.queueForRestoredItem(PlaybackRestoreStore.SavedQueue(emptyList(), -1), session(null, copy = copy("local_device_b0"))))
    val episodes = PlaybackRestoreStore.SavedQueue(listOf(PlaylistQueueItem("local_p", "local_e1")), 0)
    assertSame(episodes, PlaybackRestoreStore.queueForRestoredItem(episodes, session(null, copy = copy("local_p"), localEpisodeId = "local_e1")))
  }

  @Test
  fun serverIdQueueIsReattachedToTheRestoredCopyOrStream() {
    val serverIds = PlaybackRestoreStore.SavedQueue(listOf(PlaylistQueueItem("li0", null), PlaylistQueueItem("li1", null)), 1)
    assertSame(serverIds, PlaybackRestoreStore.queueForRestoredItem(serverIds, session(null, copy = copy("local_1", "li1", "a"))))
    assertSame(serverIds, PlaybackRestoreStore.queueForRestoredItem(serverIds, session("li1")))
    assertNull(PlaybackRestoreStore.queueForRestoredItem(serverIds, session("li0")))
  }

  @Test
  fun queueWithInoMatchesRestoredLocalCopy() {
    val queue = listOf(PlaylistQueueItem("li0", null), PlaylistQueueItem("liA", null, "42", "a", expectedTracks = 3))
    PlaybackRestoreStore.saveQueue(ctx, queue, 1)
    val loaded = PlaybackRestoreStore.loadQueue(ctx)
    assertEquals(queue, loaded.items)
    // The book was downloaded through another library: the copy has another server id, the same ino
    assertSame(loaded, PlaybackRestoreStore.queueForRestoredItem(loaded, session(null, copy = copy("local_b", "liB", "a", ino = "42"))))
    assertNull(PlaybackRestoreStore.queueForRestoredItem(loaded, session(null, copy = copy("local_b", "liB", "other", ino = "42"))))
  }

  @Test
  fun queueSavedByAnOlderVersionStillLoads() {
    rawPrefs().edit().putString("queue", """[{"libraryItemId":"local_x","episodeId":null},{"libraryItemId":"p","episodeId":"e2"}]""").putInt("queueIndex", 0).commit()
    assertEquals(PlaybackRestoreStore.SavedQueue(listOf(PlaylistQueueItem("local_x", null), PlaylistQueueItem("p", "e2")), 0), PlaybackRestoreStore.loadQueue(ctx))
  }

  @Test
  fun restoredQueueSurvivesARecreatedServiceReadingPersistedState() {
    // What restoreLastPlaybackSession/finishRestore do after the process died: read everything back
    PlaybackRestoreStore.setResumable(ctx, true)
    PlaybackRestoreStore.saveQueue(ctx, twelve, 4)
    val recreated = ApplicationProvider.getApplicationContext<Context>()
    assertTrue(PlaybackRestoreStore.isResumable(recreated))
    val saved = PlaybackRestoreStore.loadQueue(recreated)
    assertEquals(12, PlaybackRestoreStore.queueForRestoredItem(saved, session(null, copy = copy("local_device_b4")))?.items?.size)
  }
}
