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
 * Android 15+ ends or refuses the download service at the dataSync time limit: transfers pause with their bytes, stay
 * queued without failing, and start only after resumeTransfers. The server is a loopback socket that never answers, so
 * a started transfer stays running until the test pauses or removes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadSystemPauseTest {
  private lateinit var context: Context
  private lateinit var root: File
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private lateinit var silent: java.net.ServerSocket
  private lateinit var owner: String

  private val manualOnly = object : AutoTransferGate {
    override fun isAuto(item: DownloadItem) = false
    override fun allowedNow() = true
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
    root = File(context.cacheDir, "system-pause").apply { deleteRecursively(); mkdirs() }
  }

  @After
  fun tearDown() {
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    silent.close()
  }

  private fun part(itemId: String) = DownloadItemPart(
          id = "part-$itemId", downloadItemId = itemId, filename = "$itemId.mp3", fileSize = 10,
          destinationPath = File(root, "staging/$itemId/$itemId.mp3.part").path,
          finalDestinationPath = File(root, "final/$itemId/$itemId.mp3").path,
          serverPath = "/api/items/li_book/file/1/download",
          localFolderName = "Internal App Storage", localFolderUrl = "", localFolderId = "internal-book",
          ebookFile = null, audioTrack = null, episode = null, completed = false, moved = false, isMoving = false,
          failed = false, uri = Uri.parse("$owner/x"), destinationUri = Uri.parse("file:///s/$itemId"),
          finalDestinationUri = Uri.parse("file:///f/$itemId"), completedDestinationUri = null,
          finalDestinationSubfolder = "Synthetic", downloadId = null, lastUpdateTime = null, progress = 0,
          bytesDownloaded = 0).also {
    val dir = File(it.destinationPath).parentFile!!.apply { mkdirs() }
    ShadowStatFs.registerStats(dir.absolutePath, 1_000_000, 900_000, 900_000)
  }

  private fun item(id: String, p: DownloadItemPart) = DownloadItem(
          id = id, libraryItemId = "li_book", episodeId = null, userMediaProgress = null,
          serverConnectionConfigId = "A", serverAddress = owner, serverUserId = "alice", mediaType = "book",
          itemFolderPath = File(root, "final/$id").path,
          localFolder = LocalFolder("internal-book", "Internal App Storage", "", "", "", "internal", "book"),
          itemTitle = "Synthetic", itemSubfolder = "Synthetic",
          media = MediaType(MediaTypeMetadata("Synthetic", false), null), downloadItemParts = mutableListOf(p))

  private fun manager() = DownloadItemManager(FolderScanner(context), context, emitter).also { it.autoGate = manualOnly }

  private fun running(p: DownloadItemPart) = p.downloadId == -1L && !p.failed

  @Test
  fun aRunningTransferPausesWithItsBytesAndStaysQueuedUntilResumed() {
    val m = manager()
    val p = part("book1")
    m.addDownloadItem(item("book1", p))
    m.resumeWork()
    assertTrue(running(p))
    File(p.destinationPath).writeBytes(ByteArray(7) { 3 })

    m.suspendTransfers()
    assertFalse(p in m.currentDownloadItemParts)
    assertNull(p.downloadId)
    assertFalse(p.failed)
    assertEquals(7L, File(p.destinationPath).length())
    assertEquals(7L, p.bytesDownloaded)
    assertEquals(listOf("book1"), m.downloadItemQueue.map { it.id })
    assertNull(m.downloadItemQueue.single().terminalFailureAt)
    // Still work, so the next foreground start brings the service back for it
    assertTrue(m.hasWork())
    assertEquals("continues when the app is open", "background", m.waitingReason(m.downloadItemQueue.single()))

    m.resumeWork()
    assertNull("a queue check alone must not restart a paused transfer", p.downloadId)

    m.resumeTransfers()
    assertTrue(running(p))
    assertNull(m.waitingReason(m.downloadItemQueue.single()))
    m.removeDownloadItem("book1", "test cleanup")
  }

  @Test
  fun aTransferThatFailedBeforeThePauseIsLeftForItsRetry() {
    val m = manager()
    val p = part("book3")
    m.addDownloadItem(item("book3", p))
    m.resumeWork()
    assertTrue(running(p))
    // As the transfer's failure callback leaves it until the watcher's next tick
    p.failed = true

    m.suspendTransfers()
    assertTrue("failOrRetry must still see it", p in m.currentDownloadItemParts)
    m.removeDownloadItem("book3", "test cleanup")
  }

  @Test
  fun aDownloadQueuedDuringThePauseWaitsUntouchedUntilResumed() {
    val m = manager()
    m.suspendTransfers()
    val p = part("book2")
    m.addDownloadItem(item("book2", p))
    m.resumeWork()
    assertNull(p.downloadId)
    assertFalse(p.failed)
    assertFalse(p.waitingForSpace)

    m.resumeTransfers()
    assertTrue(running(p))
    m.removeDownloadItem("book2", "test cleanup")
  }
}
