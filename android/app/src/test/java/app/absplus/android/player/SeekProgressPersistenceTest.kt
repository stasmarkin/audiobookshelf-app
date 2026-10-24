package app.absplus.android.player

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.DbManager
import app.absplus.android.media.MediaProgressSyncer
import app.absplus.android.server.ApiHandler
import com.google.android.exoplayer2.Player
import io.paperdb.Paper
import java.lang.reflect.Proxy
import java.security.*
import java.security.cert.Certificate
import java.io.InputStream
import java.io.OutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual syncer and Paper; the Player supplies only confirmed position/play-state observations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeekProgressPersistenceTest {
  // This fixture never uses refresh tokens. Robolectric has no AndroidKeyStore, but ApiHandler
  // initializes it eagerly; provide only the empty keystore needed for that unrelated constructor.
  class EmptyKeyStore : KeyStoreSpi() {
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineGetKey(alias: String?, password: CharArray?): Key? = null
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): java.util.Date? = null
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<Certificate>?) { error("Not used by seek fixture") }
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<Certificate>?) { error("Not used by seek fixture") }
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) { error("Not used by seek fixture") }
    override fun engineDeleteEntry(alias: String?) { error("Not used by seek fixture") }
    override fun engineAliases(): java.util.Enumeration<String> = java.util.Collections.emptyEnumeration()
    override fun engineContainsAlias(alias: String?) = false
    override fun engineSize() = 0
    override fun engineIsKeyEntry(alias: String?) = false
    override fun engineIsCertificateEntry(alias: String?) = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
  }
  private lateinit var service: PlayerNotificationService
  private lateinit var syncer: MediaProgressSyncer
  private var position = 40_000L
  private var playing = false
  private val session get() = syncer.currentPlaybackSession!!

  @Before fun setup() {
    Security.addProvider(object : Provider("SeekFixture", 1.0, "Empty test-only keystore") {
      init { put("KeyStore.AndroidKeyStore", EmptyKeyStore::class.java.name) }
    })
    val ctx = ApplicationProvider.getApplicationContext<Context>()
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
        "isPlaying" -> playing
        else -> throw UnsupportedOperationException(method.name)
      }
    } as Player
    syncer = MediaProgressSyncer(service, ApiHandler(ctx))
    service.mediaProgressSyncer = syncer
    prepare("book")
  }

  @After fun teardown() { Security.removeProvider("SeekFixture") }

  private fun prepare(mediaType: String) {
    val local = LocalLibraryItem("local_$mediaType", "internal-$mediaType", "", "", "", false, mediaType,
      MediaType(MediaTypeMetadata("Synthetic", false), null), mutableListOf(), null, null, true, null, null, null, "server-item")
    val live = PlaybackSession("session-$mediaType", "user", "server-item", if (mediaType == "podcast") "episode" else null,
      mediaType, MediaTypeMetadata("Synthetic", false), DeviceInfo("d", "m", "x", 35, "0"), emptyList(), "Synthetic", null, null,
      120.0, PLAYMETHOD_LOCAL, 1L, 1L, 0L,
      mutableListOf(AudioTrack(1, 0.0, 120.0, "Synthetic", "", "audio/wav", null, true, "file", 1)),
      5.799, null, local, if (mediaType == "podcast") "local-episode" else null, null, null, null)
    service.currentPlaybackSession = live
    syncer.currentPlaybackSession = live.clone()
    syncer.currentLocalMediaProgress = null
    DeviceManager.deviceData.lastPlaybackSession = live
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    DeviceManager.dbManager.saveLocalMediaProgress(live.getNewLocalMediaProgress())
  }

  private fun persisted() = DbManager().getLocalMediaProgress(session.localMediaProgressId)!!
  private fun lastSync(at: Long) = MediaProgressSyncer::class.java.getDeclaredField("lastSyncTime").apply { isAccessible = true }.setLong(syncer, at)

  @Test fun pausedBookSeekPersistsWithoutTimerAndWithoutCountingPausedTime() {
    lastSync(System.currentTimeMillis() - 60_000)
    syncer.seek()
    assertEquals(40.0, persisted().currentTime, 0.0)
    assertEquals(40.0, DbManager().getDeviceData().lastPlaybackSession!!.currentTime, 0.0)
    assertEquals(40.0, DbManager().getPlaybackSessions().single().currentTime, 0.0)
    assertEquals(0L, session.timeListening)
    assertTrue(persisted().lastUpdate > 1L)
  }

  @Test fun pausedEpisodeSeekPreservesEpisodeIdentityAcrossFreshOwners() {
    prepare("podcast")
    syncer.seek()
    val reread = DbManager().getLocalMediaProgress("local_podcast-local-episode")!!
    assertEquals(40.0, reread.currentTime, 0.0)
    assertEquals("local-episode", reread.localEpisodeId)
    assertEquals("episode", reread.episodeId)
  }

  @Test fun seekBypassesTheOneSecondPeriodicDebounceAndImmediatePauseCannotLoseIt() {
    playing = true
    syncer.listeningTimerRunning = true
    lastSync(System.currentTimeMillis())
    syncer.seek()
    playing = false
    syncer.pause {}
    assertEquals(40.0, persisted().currentTime, 0.0)
  }

  @Test fun explicitBackwardSeekAndZeroArePersistedWithAutoRewindEnabledOrDisabled() {
    for (disabled in listOf(false, true)) {
      DeviceManager.deviceData.deviceSettings!!.disableAutoRewind = disabled
      position = 40_000; syncer.seek()
      position = 2_000; syncer.seek()
      assertEquals(2.0, persisted().currentTime, 0.0)
      position = 0; syncer.seek()
      assertEquals(0.0, persisted().currentTime, 0.0)
      assertFalse(persisted().isFinished)
    }
  }

  @Test fun playingProgressStillUsesNormalDurablePathAfterASeek() {
    syncer.seek()
    playing = true
    syncer.listeningTimerRunning = true
    lastSync(System.currentTimeMillis() - 2_000)
    position = 43_000
    syncer.sync(false, 43.0) {}
    assertEquals(43.0, persisted().currentTime, 0.0)
    assertTrue(session.timeListening >= 2L)
    assertEquals(43.0, DbManager().getPlaybackSessions().single().currentTime, 0.0)
  }

  @Test fun playerSeekCallbackClearsAutoRewindPauseMarker() {
    PlayerListener.lastPauseTime = System.currentTimeMillis() - 60_000
    val info = Player.PositionInfo(null, 0, null, null, 0, 40_000L, 40_000L, -1, -1)
    PlayerListener(service).onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_SEEK)
    assertEquals(0L, PlayerListener.lastPauseTime)
    assertEquals(40.0, persisted().currentTime, 0.0)
  }
}
