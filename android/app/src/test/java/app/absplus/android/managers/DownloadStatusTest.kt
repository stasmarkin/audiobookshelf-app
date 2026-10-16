package app.absplus.android.managers

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.LocalFolder
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderScanner
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import com.getcapacitor.JSObject
import io.paperdb.Paper
import java.io.File
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
import org.robolectric.shadows.ShadowStatFs

/**
 * Download reliability (Claude): why a queued download waits (B/D/F/section 7), the background lease of auto transfers
 * (M03), duplicate local episode records on re-download (B) and local progress after an explicit deletion (C).
 * The owner server is a loopback socket that accepts and never answers, so a started transfer stays running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadStatusTest {
  private lateinit var context: Context
  private lateinit var root: File
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private lateinit var silent: java.net.ServerSocket
  private lateinit var owner: String

  private class FakeGate(val autoIds: Set<String>, var reason: String?) : AutoTransferGate {
    override fun isAuto(item: DownloadItem) = item.id in autoIds
    override fun allowedNow() = reason == null
    override fun blockedReason() = reason
  }

  private val emitter = object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) {}
    override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
    override fun onDownloadItemComplete(jsobj: JSObject) {}
    override fun onQueueChanged(hasWork: Boolean) {}
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    silent = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    owner = "http://127.0.0.1:${silent.localPort}"
    Paper.init(context)
    listOf("downloadItems", "localLibraryItems", "autoDownload", "localMediaProgress").forEach { Paper.book(it).destroy() }
    AutoDownloadStore.clearCacheForTests()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    val config = ServerConnectionConfig("A", 0, "Synthetic A", owner, null, "alice", "synthetic", "synthetic-A", null)
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(config)
    DeviceManager.serverConnectionConfig = config
    try {
      WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
    } catch (e: IllegalStateException) {
      // Already initialized by an earlier test in this process
    }
    root = File(context.cacheDir, "dl-status").apply { deleteRecursively(); mkdirs() }
  }

  @After
  fun tearDown() {
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    AutoDownloadStore.clearCacheForTests()
    silent.close()
  }

  private fun part(itemId: String) = DownloadItemPart(
          id = "part-$itemId", downloadItemId = itemId, filename = "$itemId.mp3", fileSize = 10,
          destinationPath = File(root, "staging/$itemId/$itemId.mp3.part").path,
          finalDestinationPath = File(root, "final/$itemId/$itemId.mp3").path,
          serverPath = "/api/items/li_pod/file/1/download",
          localFolderName = "Internal App Storage", localFolderUrl = "", localFolderId = "internal-podcast",
          ebookFile = null, audioTrack = null, episode = null, completed = false, moved = false, isMoving = false,
          failed = false, uri = Uri.parse("$owner/x"), destinationUri = Uri.parse("file:///s/$itemId"),
          finalDestinationUri = Uri.parse("file:///f/$itemId"), completedDestinationUri = null,
          finalDestinationSubfolder = "Synthetic", downloadId = null, lastUpdateTime = null, progress = 0,
          bytesDownloaded = 0).also {
    val dir = File(it.destinationPath).parentFile!!.apply { mkdirs() }
    ShadowStatFs.registerStats(dir.absolutePath, 1_000_000, 900_000, 900_000)
  }

  private fun item(id: String, p: DownloadItemPart) = DownloadItem(
          id = id, libraryItemId = "li_pod", episodeId = id, userMediaProgress = null,
          serverConnectionConfigId = "A", serverAddress = owner, serverUserId = "alice", mediaType = "podcast",
          itemFolderPath = File(root, "final/$id").path,
          localFolder = LocalFolder("internal-podcast", "Internal App Storage", "", "", "", "internal", "podcast"),
          itemTitle = "Synthetic", itemSubfolder = "Synthetic",
          media = MediaType(MediaTypeMetadata("Synthetic", false), null), downloadItemParts = mutableListOf(p))

  private fun manager(gate: AutoTransferGate) = DownloadItemManager(FolderScanner(context), context, emitter).also { it.autoGate = gate }

  // ---- why a download waits ----

  @Test
  fun theBlockedReasonNamesTheFirstUnmetAutoDownloadCondition() {
    val wifiAndCharger = DeviceSettings.default().apply { autoDownloadWifiOnly = true; autoDownloadChargingOnly = true }
    assertEquals("network", AutoDownloadPolicy.blockedReason(wifiAndCharger, AutoDownloadPolicy.Conditions(false, false, false)))
    assertEquals("wifi", AutoDownloadPolicy.blockedReason(wifiAndCharger, AutoDownloadPolicy.Conditions(true, false, true)))
    assertEquals("charging", AutoDownloadPolicy.blockedReason(wifiAndCharger, AutoDownloadPolicy.Conditions(true, true, false)))
    assertNull(AutoDownloadPolicy.blockedReason(wifiAndCharger, AutoDownloadPolicy.Conditions(true, true, true)))
    assertTrue(AutoDownloadPolicy.transfersAllowed(wifiAndCharger, AutoDownloadPolicy.Conditions(true, true, true)))
    assertFalse(AutoDownloadPolicy.transfersAllowed(wifiAndCharger, AutoDownloadPolicy.Conditions(true, false, true)))
  }

  @Test
  fun aWaitingAutoDownloadSaysWhyAndAManualOneNever() {
    val gate = FakeGate(setOf("auto1"), reason = "wifi")
    val m = manager(gate)
    val auto = item("auto1", part("auto1")); val manual = item("manual1", part("manual1"))
    m.addDownloadItem(auto); m.addDownloadItem(manual)
    assertEquals("wifi", m.waitingReason(auto))
    assertTrue(m.isAutoOwned(auto))
    assertNull(m.waitingReason(manual))
    assertFalse(m.isAutoOwned(manual))
    gate.reason = "charging"
    assertEquals("charging", m.waitingReason(auto))
    gate.reason = null
    assertNull(m.waitingReason(auto))
    listOf("auto1", "manual1").forEach { m.removeDownloadItem(it, "test cleanup") }
  }

  // ---- M03: the background lease of auto transfers ----

  @Test
  fun whenTheBackgroundWindowEndsAutoTransfersStopKeepTheirBytesAndWaitWhileManualOnesRun() {
    val gate = FakeGate(setOf("auto2"), reason = null)
    val m = manager(gate)
    val auto = part("auto2"); val manual = part("manual2")
    m.addDownloadItem(item("auto2", auto)); m.addDownloadItem(item("manual2", manual))
    m.resumeWork()
    assertTrue(auto in m.currentDownloadItemParts)
    File(auto.destinationPath).writeBytes(ByteArray(7) { 3 })

    m.setAutoTransfersHeld(true)
    assertFalse("the auto transfer stopped", auto in m.currentDownloadItemParts)
    assertNull(auto.downloadId)
    assertFalse(auto.failed)
    assertEquals(7L, File(auto.destinationPath).length())
    assertEquals(7L, auto.bytesDownloaded)
    assertEquals("background", m.waitingReason(m.downloadItemQueue.first { it.id == "auto2" }))
    assertTrue("the manual transfer keeps running", manual in m.currentDownloadItemParts)
    m.removeDownloadItem("manual2", "test cleanup")
    assertFalse("held auto work keeps nothing alive", m.hasWork())
    m.resumeWork()
    assertFalse("and does not start again while held", auto in m.currentDownloadItemParts)

    m.setAutoTransfersHeld(false)
    assertTrue(m.hasWork())
    m.resumeWork()
    assertTrue(auto in m.currentDownloadItemParts)
    m.removeDownloadItem("auto2", "test cleanup")
  }

  // ---- B: a re-downloaded episode is one record ----

  private fun track(file: String) = AudioTrack(1, 0.0, 60.0, file, "file:///$file", "audio/mpeg", null, true, "lf_$file", 1)

  private fun serverEpisode(id: String) = PodcastEpisode(id, 1, null, null, "Episode $id", null, null, null, null, null, null, null, 60.0, 1000L, null, null)

  @Test
  fun reDownloadingAnEpisodeReplacesItsRecordInPlaceAndKeepsOtherEpisodes() {
    val podcast = Podcast(PodcastMetadata("Synthetic", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0)
    podcast.addEpisode(track("e1.mp3"), serverEpisode("e1"))
    podcast.addEpisode(track("e2.mp3"), serverEpisode("e2"))
    val again = podcast.addEpisode(track("e1 (2).mp3"), serverEpisode("e1"))
    assertEquals(listOf("local_ep_e1", "local_ep_e2"), podcast.episodes!!.map { it.id })
    assertEquals("local_ep_e1", again.id)
    assertEquals("lf_e1 (2).mp3", podcast.episodes!![0].audioTrack!!.localFileId)
    assertEquals(listOf(1, 2), podcast.episodes!!.map { it.index })
  }

  @Test
  fun aSavedDuplicateIsCollapsedTheNextTimeThatEpisodeIsRecorded() {
    // Records written by earlier versions may already hold the episode twice
    val first = PodcastEpisode("local_ep_e1", 1, null, null, "Episode e1", null, null, null, null, null, track("e1.mp3"), null, 60.0, 1000L, "e1", "local_ep_e1")
    val dup = PodcastEpisode("local_ep_e1", 2, null, null, "Episode e1", null, null, null, null, null, track("e1.mp3"), null, 60.0, 1000L, "e1", "local_ep_e1")
    val podcast = Podcast(PodcastMetadata("Synthetic", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(first, dup), false, 2)
    podcast.addEpisode(track("e1.mp3"), serverEpisode("e1"))
    assertEquals(listOf("local_ep_e1"), podcast.episodes!!.map { it.id })
  }

  // ---- C: local progress of what the user deleted ----

  private fun progress(id: String, llid: String, episode: String?) = LocalMediaProgress(id, llid, episode, 60.0, 0.5, 30.0, false, null, null, 1L, 1L, null, "A", owner, "alice", "li_pod", episode?.removePrefix("local_ep_"))

  @Test
  fun deletingAnItemOrEpisodeRemovesOnlyItsLocalProgress() {
    val db = DeviceManager.dbManager
    db.saveLocalMediaProgress(progress("local_a-local_ep_e1", "local_a", "local_ep_e1"))
    db.saveLocalMediaProgress(progress("local_a-local_ep_e2", "local_a", "local_ep_e2"))
    db.saveLocalMediaProgress(progress("local_b", "local_b", null))
    db.removeLocalMediaProgressFor("local_a", "local_ep_e1")
    assertEquals(setOf("local_a-local_ep_e2", "local_b"), db.getAllLocalMediaProgress().map { it.id }.toSet())
    db.removeLocalMediaProgressFor("local_a")
    assertEquals(setOf("local_b"), db.getAllLocalMediaProgress().map { it.id }.toSet())
  }
}
