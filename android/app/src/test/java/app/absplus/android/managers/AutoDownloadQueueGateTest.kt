package app.absplus.android.managers

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.absplus.android.data.LocalFolder
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
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
 * PR #23 integration corrections (Claude): auto-owned items in the shared download queue follow the auto download
 * conditions (Wi-Fi only / charging) on every path, manual downloads are never held back by them and go first, a
 * running auto transfer pauses (bytes kept) when conditions stop holding, and restore keeps all of that.
 * The owner server is a loopback socket that accepts connections and never answers, so a transfer that starts stays
 * running until the test pauses or removes it (a refused connection could fail it at any moment and race the checks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoDownloadQueueGateTest {
  private lateinit var context: Context
  private lateinit var root: File
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private lateinit var silent: java.net.ServerSocket
  private lateinit var owner: String

  private class FakeGate(val autoIds: Set<String>, var allowed: Boolean) : AutoTransferGate {
    override fun isAuto(item: DownloadItem) = item.id in autoIds
    override fun allowedNow() = allowed
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
    listOf("downloadItems", "localLibraryItems", "autoDownload").forEach { Paper.book(it).destroy() }
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
    root = File(context.cacheDir, "auto-gate").apply { deleteRecursively(); mkdirs() }
  }

  @After
  fun tearDown() {
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    AutoDownloadStore.clearCacheForTests()
    silent.close()
  }

  private fun part(itemId: String, bytes: Long = 0) = DownloadItemPart(
          id = "part-$itemId", downloadItemId = itemId, filename = "$itemId.mp3", fileSize = 10,
          destinationPath = File(root, "staging/$itemId/$itemId.mp3.part").path,
          finalDestinationPath = File(root, "final/$itemId/$itemId.mp3").path,
          serverPath = "/api/items/li_pod/file/1/download",
          localFolderName = "Internal App Storage", localFolderUrl = "", localFolderId = "internal-podcast",
          ebookFile = null, audioTrack = null, episode = null, completed = false, moved = false, isMoving = false,
          failed = false, uri = Uri.parse("$owner/x"), destinationUri = Uri.parse("file:///s/$itemId"),
          finalDestinationUri = Uri.parse("file:///f/$itemId"), completedDestinationUri = null,
          finalDestinationSubfolder = "Synthetic", downloadId = null, lastUpdateTime = null, progress = 0,
          bytesDownloaded = bytes).also {
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

  private fun started(p: DownloadItemPart) = p.downloadId == -1L || p.failed

  @Test
  fun blockedAutoItemWaitsUntouchedWhileAManualItemStarts() {
    val gate = FakeGate(setOf("auto1"), allowed = false)
    val m = manager(gate)
    val auto = part("auto1"); val manual = part("manual1")
    m.addDownloadItem(item("auto1", auto))
    m.addDownloadItem(item("manual1", manual))
    m.resumeWork()
    assertNull(auto.downloadId)
    assertFalse(auto.failed)
    assertFalse(auto.waitingForSpace)
    assertTrue(started(manual))
    m.removeDownloadItem("manual1", "test cleanup")
    // only blocked auto work left: the service/worker must not stay alive for it
    assertFalse(m.hasWork())
    gate.allowed = true
    assertTrue(m.hasWork())
    m.resumeWork()
    assertTrue(started(auto))
    m.removeDownloadItem("auto1", "test cleanup")
  }

  @Test
  fun switchingToAMeteredNetworkPausesARunningAutoTransferAndKeepsItsBytes() {
    val gate = FakeGate(setOf("auto2"), allowed = true)
    val m = manager(gate)
    val auto = part("auto2")
    m.addDownloadItem(item("auto2", auto))
    m.resumeWork()
    assertTrue(started(auto))
    if (auto.failed) return // refused before we could observe it running; covered by the device test
    File(auto.destinationPath).writeBytes(ByteArray(7) { 3 })
    gate.allowed = false
    m.pauseBlockedAutoTransfers()
    assertFalse(auto in m.currentDownloadItemParts)
    assertNull(auto.downloadId)
    assertFalse(auto.failed)
    assertEquals(7L, File(auto.destinationPath).length())
    assertEquals(7L, auto.bytesDownloaded)
    assertFalse(m.hasWork())
    m.removeDownloadItem("auto2", "test cleanup")
  }

  @Test
  fun manualDownloadsGoFirstEvenBehindAFullQueueOfAutoItems() {
    val ids = (1..3).map { "auto_p$it" }
    val gate = FakeGate(ids.toSet(), allowed = true)
    val m = manager(gate)
    val autos = ids.map { part(it).also { p -> m.addDownloadItem(item(it, p)) } }
    val manual = part("manual_p")
    m.addDownloadItem(item("manual_p", manual))
    m.resumeWork()
    assertTrue(started(manual))
    assertEquals(3 - 1, autos.count { started(it) })
    (ids + "manual_p").forEach { m.removeDownloadItem(it, "test cleanup") }
  }

  @Test
  fun afterProcessDeathABlockedAutoItemIsRestoredAndStaysPaused() {
    val p = part("auto3", bytes = 5)
    File(p.destinationPath).writeBytes(ByteArray(5) { 9 })
    DeviceManager.dbManager.saveDownloadItem(item("auto3", p))
    // ownership persisted by the store, read again by a fresh process
    AutoDownloadStore.markAutoQueued("A", "li_pod", "auto3")
    AutoDownloadStore.clearCacheForTests()
    val real = AutoTransferGate.forContext(context)
    val gate = object : AutoTransferGate {
      override fun isAuto(item: DownloadItem) = real.isAuto(item)
      override fun allowedNow() = false
    }
    val m = manager(gate)
    m.restoreQueue()
    val restored = m.downloadItemQueue.single()
    assertTrue(gate.isAuto(restored))
    m.resumeWork()
    assertNull(restored.downloadItemParts.single().downloadId)
    assertFalse(restored.downloadItemParts.single().failed)
    assertFalse(m.hasWork())
    // a manual request takes it over: no longer held by the auto conditions
    AutoDownloadStore.claimManual("A", "li_pod", "auto3")
    assertFalse(gate.isAuto(restored))
    assertTrue(m.hasWork())
    m.removeDownloadItem("auto3", "test cleanup")
  }
}
