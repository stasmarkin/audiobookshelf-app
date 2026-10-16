package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.absplus.android.data.AudioFile
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.DownloadUsingCellularSetting
import app.absplus.android.data.FileMetadata
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastAutoDownloadEntry
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.AutoDownloadPolicy.WindowAction
import app.absplus.android.managers.DownloadItemManager.QueuedState
import io.paperdb.Paper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR #23 integration corrections (Claude): manual/automatic download ownership, the auto transfer conditions, bounded
 * retries, retention that only ever removes auto-owned episodes, and the H01 guard on the extracted download starter
 * (manual and automatic entry). Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoDownloadSafetyTest {
  private lateinit var context: Context
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private var previousLast: app.absplus.android.data.PlaybackSession? = null
  private val server = "srvA"
  private val pod = "li_pod"

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    listOf("downloadItems", "localLibraryItems", "localMediaProgress", "autoDownload").forEach { Paper.book(it).destroy() }
    AutoDownloadStore.clearCacheForTests()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    previousLast = DeviceManager.deviceData.lastPlaybackSession
    val config = ServerConnectionConfig(server, 0, "Synthetic", "http://127.0.0.1:1", null, "alice", "synthetic", "synthetic-A", null)
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(config)
    DeviceManager.serverConnectionConfig = config
    DeviceManager.deviceData.lastPlaybackSession = null
    try {
      WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
    } catch (e: IllegalStateException) {
      // Already initialized by an earlier test in this process
    }
    File(context.filesDir, "downloads").deleteRecursively()
    File(context.filesDir, "download-staging").deleteRecursively()
  }

  @After
  fun tearDown() {
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    DeviceManager.deviceData.lastPlaybackSession = previousLast
    AutoDownloadStore.clearCacheForTests()
  }

  // ---- ownership ----

  @Test
  fun manualClaimWinsOverAutoAndIsNeverTakenBack() {
    assertTrue(AutoDownloadStore.markAutoQueued(server, pod, "ep_1"))
    assertTrue(AutoDownloadStore.isAutoOwned(server, pod, "ep_1"))
    // user requests the episode while it is queued/active automatically
    AutoDownloadStore.claimManual(server, pod, "ep_1")
    assertFalse(AutoDownloadStore.isAutoOwned(server, pod, "ep_1"))
    // a later (or concurrent) auto check can never turn it back into an auto download
    assertFalse(AutoDownloadStore.markAutoQueued(server, pod, "ep_1"))
    assertFalse(AutoDownloadStore.isAutoOwned(server, pod, "ep_1"))
    // survives process death: a fresh cache reads the persisted entry
    AutoDownloadStore.clearCacheForTests()
    assertFalse(AutoDownloadStore.isAutoOwned(server, pod, "ep_1"))
    assertTrue("ep_1" in AutoDownloadStore.get(server, pod).manualEpisodeIds)
  }

  @Test
  fun ownershipIsPerServerAndSurvivesRestart() {
    AutoDownloadStore.markAutoQueued(server, pod, "ep_2")
    AutoDownloadStore.clearCacheForTests()
    assertTrue(AutoDownloadStore.isAutoOwned(server, pod, "ep_2"))
    assertFalse(AutoDownloadStore.isAutoOwned("srvB", pod, "ep_2"))
    assertFalse(AutoDownloadStore.isAutoOwned(server, pod, null))
  }

  @Test
  fun failuresAreCountedOnlyForAutoOwnedEpisodesAndClearedOnSuccess() {
    AutoDownloadStore.recordAutoFailure(server, pod, "manual_ep")
    assertNull(AutoDownloadStore.get(server, pod).failedAttempts["manual_ep"])
    AutoDownloadStore.markAutoQueued(server, pod, "ep_3")
    AutoDownloadStore.recordAutoFailure(server, pod, "ep_3")
    AutoDownloadStore.recordAutoFailure(server, pod, "ep_3")
    assertEquals(2, AutoDownloadStore.get(server, pod).failedAttempts["ep_3"])
    AutoDownloadStore.recordAutoSuccess(server, pod, "ep_3")
    assertNull(AutoDownloadStore.get(server, pod).failedAttempts["ep_3"])
    assertTrue(AutoDownloadStore.isAutoOwned(server, pod, "ep_3"))
  }

  @Test
  fun aRecordWrittenByThePrsOriginalCodeOwnsNothing() {
    // PR #23's original code listed manually downloaded episodes as auto downloaded and had no manual list
    val legacy = PodcastAutoDownloadEntry("ON", 2, mutableListOf("manual_before", "auto_before"))
    PodcastAutoDownloadEntry::class.java.getDeclaredField("manualEpisodeIds").apply { isAccessible = true }.set(legacy, null)
    PodcastAutoDownloadEntry::class.java.getDeclaredField("failedAttempts").apply { isAccessible = true }.set(legacy, null)
    DeviceManager.dbManager.savePodcastAutoDownloadEntry("$server:$pod", legacy)
    AutoDownloadStore.clearCacheForTests()
    val read = AutoDownloadStore.get(server, pod)
    assertEquals(emptyList<String>(), read.autoDownloadedEpisodeIds)
    assertEquals("ON", read.enabled)
    assertEquals(2, read.keepCount)
    assertFalse(AutoDownloadStore.isAutoOwned(server, pod, "manual_before"))
    assertEquals(emptyList<String>(), AutoDownloadPolicy.retentionDeletions(read, emptySet(), setOf("manual_before", "auto_before"),
            listOf(AutoDownloadPolicy.LocalEpisode("manual_before", false, false, false), AutoDownloadPolicy.LocalEpisode("auto_before", false, false, false))))
  }

  @Test
  fun anEntryListingAnEpisodeInBothListsReadsAsManual() {
    DeviceManager.dbManager.savePodcastAutoDownloadEntry("$server:$pod",
            PodcastAutoDownloadEntry("ON", null, mutableListOf("x"), mutableListOf("x"), mutableMapOf()))
    assertEquals(emptyList<String>(), AutoDownloadStore.get(server, pod).autoDownloadedEpisodeIds)
    assertFalse(AutoDownloadStore.isAutoOwned(server, pod, "x"))
  }

  // ---- window decisions (duplicates, retries, manual priority) ----

  private fun entry(owned: List<String> = emptyList(), manual: List<String> = emptyList(), failed: Map<String, Int> = emptyMap()) =
          PodcastAutoDownloadEntry("ON", null, owned.toMutableList(), manual.toMutableList(), failed.toMutableMap())

  @Test
  fun windowActions() {
    // a manual episode already on the device before auto download was enabled: left alone, not claimed
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(), "e", QueuedState.NOT_QUEUED, onDevice = true))
    // a manual download queued by the user: not taken over
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(), "e", QueuedState.PENDING, onDevice = false))
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(), "e", QueuedState.FAILED, onDevice = false))
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(manual = listOf("e")), "e", QueuedState.NOT_QUEUED, onDevice = false))
    // new episode -> exactly one enqueue; once queued, no duplicate
    assertEquals(WindowAction.ENQUEUE, AutoDownloadPolicy.windowAction(entry(), "e", QueuedState.NOT_QUEUED, onDevice = false))
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(owned = listOf("e")), "e", QueuedState.PENDING, onDevice = false))
    // bounded retries of auto-owned failures (F23-4)
    assertEquals(WindowAction.RETRY, AutoDownloadPolicy.windowAction(entry(listOf("e"), failed = mapOf("e" to 2)), "e", QueuedState.FAILED, onDevice = false))
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(listOf("e"), failed = mapOf("e" to 3)), "e", QueuedState.FAILED, onDevice = false))
    // owned, completed and later deleted by the user (or cancelled): not downloaded again
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(listOf("e")), "e", QueuedState.NOT_QUEUED, onDevice = false))
    // owned failure whose queue record expired: retried below the cap
    assertEquals(WindowAction.ENQUEUE, AutoDownloadPolicy.windowAction(entry(listOf("e"), failed = mapOf("e" to 1)), "e", QueuedState.NOT_QUEUED, onDevice = false))
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(listOf("e"), failed = mapOf("e" to 3)), "e", QueuedState.NOT_QUEUED, onDevice = false))
  }

  // ---- Wi-Fi only / metered / offline / charging (F23-2) ----

  @Test
  fun transferConditions() {
    val wifiOnly = DeviceSettings.default().apply { autoDownloadWifiOnly = true }
    val anyNetwork = DeviceSettings.default().apply { autoDownloadWifiOnly = false; downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS }
    val askCellular = DeviceSettings.default().apply { autoDownloadWifiOnly = false; downloadUsingCellular = DownloadUsingCellularSetting.ASK }
    val wifi = AutoDownloadPolicy.Conditions(connected = true, unmetered = true, charging = false)
    val mobile = AutoDownloadPolicy.Conditions(connected = true, unmetered = false, charging = false)
    val offline = AutoDownloadPolicy.Conditions(connected = false, unmetered = false, charging = true)
    assertTrue(AutoDownloadPolicy.transfersAllowed(wifiOnly, wifi))
    assertFalse(AutoDownloadPolicy.transfersAllowed(wifiOnly, mobile))
    assertFalse(AutoDownloadPolicy.transfersAllowed(askCellular, mobile)) // no UI to ask from the background
    assertTrue(AutoDownloadPolicy.transfersAllowed(anyNetwork, mobile))
    assertFalse(AutoDownloadPolicy.transfersAllowed(anyNetwork, offline))
    val charging = DeviceSettings.default().apply { autoDownloadChargingOnly = true }
    assertFalse(AutoDownloadPolicy.transfersAllowed(charging, wifi))
    assertTrue(AutoDownloadPolicy.transfersAllowed(charging, wifi.copy(charging = true)))
  }

  // ---- retention (F23-1) ----

  private fun local(id: String, inProgress: Boolean = false, last: Boolean = false, queued: Boolean = false) =
          AutoDownloadPolicy.LocalEpisode(id, inProgress, last, queued)

  @Test
  fun retentionRemovesOnlyUnprotectedAutoOwnedEpisodesOutsideTheWindow() {
    val e = entry(owned = listOf("old", "playing", "started", "queued", "gone", "inWindow"), manual = listOf("mine"))
    val deleted = AutoDownloadPolicy.retentionDeletions(
            e,
            windowIds = setOf("inWindow"),
            serverEpisodeIds = setOf("old", "playing", "started", "queued", "inWindow", "mine", "preexisting"),
            local = listOf(local("old"), local("playing", last = true), local("started", inProgress = true), local("queued", queued = true),
                    local("gone"), local("inWindow"), local("mine"), local("preexisting")))
    // gone: no longer listed by the server -> missing metadata never deletes; mine/preexisting: not auto-owned
    assertEquals(listOf("old"), deleted)
  }

  private fun podcastLocal(localId: String, serverConfigId: String, vararg eps: Pair<String, File>): LocalLibraryItem {
    val episodes = eps.mapIndexed { i, (serverEp, file) ->
      val track = AudioTrack(i + 1, 0.0, 20.0, file.name, file.toURI().toString(), "audio/mpeg", null, true, "lf_$serverEp", null)
      PodcastEpisode("local_ep_$serverEp", i + 1, null, null, serverEp, null, null, null, i.toLong(), null, track, null, 20.0, file.length(), serverEp, "local_ep_$serverEp")
    }.toMutableList()
    val files = eps.map { (serverEp, file) -> LocalFile("lf_$serverEp", file.name, file.toURI().toString(), file.parent, file.absolutePath, "audio/mpeg", file.length()) }.toMutableList()
    val podcast = Podcast(PodcastMetadata("Synthetic", null, null, mutableListOf(), false), null, mutableListOf(), episodes, false, episodes.size)
    return LocalLibraryItem(localId, "internal-podcast", "", "", "", false, "podcast", podcast, files, null, null, true, serverConfigId, "http://127.0.0.1:1", "alice", pod)
  }

  private fun media(name: String) = File(context.filesDir, "downloads/$pod/$name").apply { parentFile?.mkdirs(); writeBytes(ByteArray(16) { 1 }) }

  @Test
  fun retentionExecutorDeletesAutoEpisodeFileButKeepsManualPreexistingOtherServerAndProgress() {
    val auto = media("auto.mp3"); val manual = media("manual.mp3"); val pre = media("pre.mp3"); val started = media("started.mp3")
    val item = podcastLocal("local_$pod", server, "auto" to auto, "manual" to manual, "pre" to pre, "started" to started)
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    // another server's copy of the same podcast must never be touched
    val otherFile = File(context.filesDir, "downloads/other/auto.mp3").apply { parentFile?.mkdirs(); writeBytes(ByteArray(8)) }
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_other", "srvB", "auto" to otherFile))
    DeviceManager.dbManager.saveLocalMediaProgress(LocalMediaProgress("local_$pod-local_ep_started", "local_$pod", "local_ep_started", 20.0, 0.5, 10.0, false, null, null, 1L, 1L, null, server, null, "alice", pod, "started"))
    DeviceManager.dbManager.saveLocalMediaProgress(LocalMediaProgress("local_$pod-local_ep_auto", "local_$pod", "local_ep_auto", 20.0, 1.0, 20.0, true, null, null, 1L, 1L, 2L, server, null, "alice", pod, "auto"))
    val e = entry(owned = listOf("auto", "started"), manual = listOf("manual"))
    // the store is the authority at removal time (each removal is rechecked there), so it holds the same ownership
    AutoDownloadStore.markAutoQueued(server, pod, "auto")
    AutoDownloadStore.markAutoQueued(server, pod, "started")
    AutoDownloadStore.claimManual(server, pod, "manual")

    val deleted = AutoDownloadRetention.apply(context, server, pod, e, windowIds = emptySet(),
            serverEpisodeIds = setOf("auto", "manual", "pre", "started"), serverProgress = emptyMap<String, MediaProgress>(), queuedEpisodeIds = emptySet())

    assertEquals(listOf("auto"), deleted)
    assertFalse(auto.exists())
    assertTrue(manual.exists() && pre.exists() && started.exists() && otherFile.exists())
    val saved = DeviceManager.dbManager.getLocalLibraryItem("local_$pod")!!
    assertEquals(listOf("manual", "pre", "started"), (saved.media as Podcast).episodes!!.map { it.serverEpisodeId })
    assertEquals(3, saved.localFiles.size)
    // listening history stays
    assertTrue(DeviceManager.dbManager.getLocalMediaProgress("local_$pod-local_ep_auto") != null)
    assertEquals(1, (DeviceManager.dbManager.getLocalLibraryItem("local_other")!!.media as Podcast).episodes!!.size)
  }

  @Test
  fun retentionNeverDeletesTheLastPlayedEpisodeOrAFileOutsideAppStorage() {
    val auto = media("auto.mp3")
    val outside = File(context.cacheDir, "outside.mp3").apply { writeBytes(ByteArray(4)) }
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "auto" to auto, "outside" to outside))
    DeviceManager.deviceData.lastPlaybackSession = app.absplus.android.data.PlaybackSession(
            "s", "alice", pod, "auto", "podcast", PodcastMetadata("Synthetic", null, null, mutableListOf(), false),
            app.absplus.android.data.DeviceInfo("d", "m", "b", 35, "v"), emptyList(), null, null, null, 20.0, 3, 1L, 1L, 0L,
            mutableListOf(), 0.0, null, null, null, server, null, null)
    val deleted = AutoDownloadRetention.apply(context, server, pod, entry(owned = listOf("auto", "outside")), emptySet(),
            setOf("auto", "outside"), emptyMap(), emptySet())
    assertEquals(emptyList<String>(), deleted)
    assertTrue(auto.exists() && outside.exists())
  }

  // ---- H01 on the extracted starter (manual and automatic entry), malformed metadata ----

  private fun libraryItem(id: String, episode: PodcastEpisode) = LibraryItem(
          id, "1", "lib", "folder", "/p", "p", 0, 0, 0, 0, 0, null, null, false, false, "podcast",
          Podcast(PodcastMetadata("Synthetic Show", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(episode), false, 1),
          mutableListOf(), null, null, null, null)

  private fun episode(id: String, relPath: String? = "ep1.mp3", ino: String? = "15481123719402600"): PodcastEpisode {
    val meta = relPath?.let { FileMetadata(it, ".mp3", "/podcasts/$it", it, 16L) }
    val track = meta?.let { AudioTrack(1, 0.0, 20.0, it.filename, "/api/items/x/file/1", "audio/mpeg", it, false, null, null) }
    val audioFile = if (meta != null && ino != null) AudioFile(1, ino, meta) else null
    return PodcastEpisode(id, 1, null, null, "Episode", null, null, null, 1L, audioFile, track, null, 20.0, 16L, null, null)
  }

  private fun start(item: LibraryItem, ep: PodcastEpisode, auto: Boolean): String? {
    val latch = CountDownLatch(1); var result: String? = "TIMEOUT"
    val cb: (String?) -> Unit = { result = it; latch.countDown() }
    if (auto) LibraryItemDownloadStarter.startPodcastEpisodeDownload(context, item, ep, cb)
    else LibraryItemDownloadStarter.startLibraryItemDownload(context, item, null, ep, cb)
    latch.await(10, TimeUnit.SECONDS)
    return result
  }

  private fun nothingWritten() {
    assertTrue(DeviceManager.dbManager.getDownloadItems().isEmpty())
    assertFalse(File(context.filesDir, "downloads").walkTopDown().any { it.isFile })
    assertFalse(File(context.filesDir, "download-staging").exists() && File(context.filesDir, "download-staging").list()!!.isNotEmpty())
  }

  @Test
  fun unsafeServerIdentifiersAreRejectedOnBothStarterEntriesBeforeAnyQueueOrFileWork() {
    for (auto in listOf(false, true)) {
      assertEquals("Invalid download identifier", start(libraryItem("../../shared_prefs", episode("ep_ok")), episode("ep_ok"), auto))
      assertEquals("Invalid download identifier", start(libraryItem(pod, episode("../x")), episode("../x"), auto))
      assertEquals("Invalid download identifier", start(libraryItem("a/b", episode("ep_ok")), episode("ep_ok"), auto))
    }
    nothingWritten()
  }

  @Test
  fun malformedEpisodeMetadataIsRejectedWithoutABrokenQueueRecord() {
    for (bad in listOf(episode("ep_a", relPath = null), episode("ep_b", ino = null), episode("ep_c", relPath = ".."), episode("ep_d", relPath = ""))) {
      assertEquals("Episode has no downloadable audio file", start(libraryItem(pod, bad), bad, auto = true))
    }
    nothingWritten()
  }

  @Test
  fun automaticEpisodeDownloadIsQueuedInInternalStorageForTheActiveServersIdentity() {
    val ep = episode("ep_ok")
    assertNull(start(libraryItem(pod, ep), ep, auto = true))
    val queued = DeviceManager.dbManager.getDownloadItems().single()
    assertEquals("internal-podcast", queued.localFolder.id)
    assertEquals(server, queued.serverConnectionConfigId)
    assertEquals("http://127.0.0.1:1", queued.serverAddress)
    assertEquals("alice", queued.serverUserId)
    assertTrue(queued.itemFolderPath.startsWith(File(context.filesDir, "downloads").path))
    // leave no queued transfer behind in the process-wide download host
    val removed = CountDownLatch(1)
    app.absplus.android.services.DownloadServiceHost.remove(context, queued.id, "test cleanup") { removed.countDown() }
    removed.await(10, TimeUnit.SECONDS)
  }
}
