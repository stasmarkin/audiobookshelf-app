package app.absplus.android.player

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.media.MediaProgressSyncer
import app.absplus.android.server.ApiHandler
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import io.paperdb.Paper
import java.lang.reflect.Proxy
import java.security.Provider
import java.security.Security
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Audit F1 (2026-10-20): preparing the next item (queue or series advance, a podcast's next episode, Android Auto,
 * media buttons, the widget) seeks the player to the new item's start while the progress syncer still holds the
 * previous item until play() switches it. That seek must never be saved as the previous item's position. Real syncer,
 * real Paper and real PlayerListener; the Player proxy supplies the position, and a real ExoPlayer shows that the
 * prepare-time seek is reported as a SEEK discontinuity. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StaleSessionSeekTest {
  private lateinit var ctx: Context
  private lateinit var service: PlayerNotificationService
  private lateinit var syncer: MediaProgressSyncer
  private var position = 0L

  @Before fun setup() {
    Security.addProvider(object : Provider("StaleSeekFixture", 1.0, "Empty test-only keystore") {
      init { put("KeyStore.AndroidKeyStore", SeekProgressPersistenceTest.EmptyKeyStore::class.java.name) }
    })
    ctx = ApplicationProvider.getApplicationContext()
    DbManager.initialize(ctx)
    listOf("localMediaProgress", "playbackSession", "mediaItemHistory", "device").forEach { Paper.book(it).destroy() }
    DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
    DeviceManager.serverConnectionConfig = null
    DeviceManager.widgetUpdater = null
    service = PlayerNotificationService()
    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }.invoke(service, ctx)
    service.currentPlayer = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
      when (method.name) {
        "getCurrentPosition" -> position
        "getMediaItemCount" -> 1
        "isPlaying" -> false
        else -> throw UnsupportedOperationException(method.name)
      }
    } as Player
    syncer = MediaProgressSyncer(service, ApiHandler(ctx))
    service.mediaProgressSyncer = syncer
  }

  @After fun teardown() { Security.removeProvider("StaleSeekFixture") }

  private fun local(id: String, type: String) = LocalLibraryItem("local_$id", "internal-$id", "", "", "", false, type,
          MediaType(MediaTypeMetadata("Synthetic $id", false), null), mutableListOf(), null, null, true, null, null, null, "server-$id")

  private fun session(id: String, duration: Double, currentTime: Double, item: LocalLibraryItem? = local(id, "book"),
                      localEpisodeId: String? = null, playMethod: Int = PLAYMETHOD_LOCAL) =
          PlaybackSession("session-$id", "user", item?.libraryItemId ?: "server-$id", localEpisodeId?.let { "ep-$it" }, item?.mediaType ?: "book",
                  MediaTypeMetadata("Synthetic $id", false), DeviceInfo("d", "m", "x", 35, "0"), emptyList(), "Synthetic $id", null, null,
                  duration, playMethod, 1L, 1L, 0L, mutableListOf(AudioTrack(1, 0.0, duration, "Synthetic $id", "", "audio/wav", null, true, "file", 1)),
                  currentTime, null, item, localEpisodeId, null, null, null)

  /** The previous item as the syncer holds it after it ended or was paused (pause keeps the session). */
  private fun finished(s: PlaybackSession): LocalMediaProgress {
    syncer.currentPlaybackSession = s.clone()
    syncer.currentLocalMediaProgress = null
    val p = s.getNewLocalMediaProgress().apply { currentTime = s.duration; progress = 1.0; isFinished = true; finishedAt = 5L; lastUpdate = 5L }
    DeviceManager.dbManager.saveLocalMediaProgress(p)
    return p
  }

  /** What preparePlayer(next) does: the service holds the next session, then the player seeks to its start. */
  private fun prepareNext(next: PlaybackSession, startMs: Long) {
    service.currentPlaybackSession = next
    position = startMs
    val info = Player.PositionInfo(null, 0, null, null, 0, startMs, startMs, -1, -1)
    PlayerListener(service).onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_SEEK)
  }

  private fun progress(id: String) = DbManager().getLocalMediaProgress(id)

  @Test fun bookQueueAdvanceKeepsThePreviousBookFinished() {
    val a = session("a", 120.0, 120.0)
    finished(a)
    prepareNext(session("b", 600.0, 10.0), 10_000L)
    val after = progress("local_a")!!
    assertTrue(after.isFinished)
    assertEquals(120.0, after.currentTime, 0.0)
    assertEquals(5L, after.lastUpdate)
    assertNull("the new item gets no record from its prepare-time seek", progress("local_b"))
    assertTrue("no playback session was saved for either item", DbManager().getPlaybackSessions().isEmpty())
  }

  @Test fun podcastNextEpisodeKeepsThePreviousEpisodeFinished() {
    val show = local("show", "podcast")
    val e1 = session("e1", 300.0, 300.0, show, localEpisodeId = "e1")
    finished(e1)
    prepareNext(session("e2", 900.0, 0.0, show, localEpisodeId = "e2"), 0L)
    val after = progress("local_show-e1")!!
    assertTrue(after.isFinished)
    assertEquals(300.0, after.currentTime, 0.0)
    assertNull(progress("local_show-e2"))
  }

  @Test fun pausedItemIsUntouchedWhenAnotherItemIsStartedFromOutsideTheApp() {
    // Android Auto / media buttons: A paused mid-book, B started natively at its own saved position
    val a = session("a", 3600.0, 1500.0)
    syncer.currentPlaybackSession = a.clone()
    DeviceManager.dbManager.saveLocalMediaProgress(a.getNewLocalMediaProgress().apply { lastUpdate = 7L })
    prepareNext(session("b", 600.0, 42.0), 42_000L)
    val after = progress("local_a")!!
    assertEquals(1500.0, after.currentTime, 0.0)
    assertEquals(7L, after.lastUpdate)
  }

  @Test fun streamedPreviousItemIsNotResentWithTheNextItemsPosition() {
    val a = session("a", 120.0, 120.0, item = null, playMethod = PLAYMETHOD_DIRECTPLAY)
    syncer.currentPlaybackSession = a.clone()
    prepareNext(session("b", 600.0, 10.0, item = null, playMethod = PLAYMETHOD_DIRECTPLAY), 10_000L)
    assertEquals(120.0, syncer.currentPlaybackSession!!.currentTime, 0.0)
    assertTrue(DbManager().getPlaybackSessions().isEmpty())
  }

  @Test fun noSyncedSessionMeansNothingIsSaved() {
    // The app's own item switch resets the syncer before preparing: the prepare-time seek has nothing to save into
    prepareNext(session("b", 600.0, 10.0), 10_000L)
    assertNull(progress("local_b"))
    assertNull(syncer.currentPlaybackSession)
  }

  @Test fun afterTheSwitchSeeksInTheNewItemAreSavedToTheNewItem() {
    val a = session("a", 120.0, 120.0)
    finished(a)
    val b = session("b", 600.0, 10.0)
    prepareNext(b, 10_000L)
    syncer.play(b) // the player started B
    syncer.pause {}
    position = 200_000L
    val info = Player.PositionInfo(null, 0, null, null, 0, position, position, -1, -1)
    PlayerListener(service).onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_SEEK)
    assertEquals(200.0, progress("local_b")!!.currentTime, 0.0)
    assertTrue(progress("local_a")!!.isFinished)
  }

  @Test fun prepareTimeSeekIsReportedAsASeekByExoPlayer() {
    val reasons = mutableListOf<Int>()
    val player = ExoPlayer.Builder(ctx).setLooper(Looper.getMainLooper()).build()
    player.addListener(object : Player.Listener {
      override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { reasons += reason }
    })
    player.setMediaItem(MediaItem.fromUri("file:///synthetic/b.wav"))
    player.seekTo(10_000L)
    shadowOf(Looper.getMainLooper()).idle()
    player.release()
    assertTrue("why this guard is needed: $reasons", reasons.contains(Player.DISCONTINUITY_REASON_SEEK))
  }
}
