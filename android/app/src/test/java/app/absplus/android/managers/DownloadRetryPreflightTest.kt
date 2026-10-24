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
import java.net.InetAddress
import java.net.ServerSocket
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRetryPreflightTest {
  private lateinit var context: Context
  private lateinit var root: File
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private lateinit var owner: String
  private lateinit var ownerSocket: ServerSocket
  private lateinit var otherSocket: ServerSocket
  private val otherAddress get() = "http://127.0.0.1:${otherSocket.localPort}"
  private val hold = "Download authorization changed: reconnect the original server before retrying"
  private val managers = mutableListOf<DownloadItemManager>()

  private val emitter = object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) {}
    override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
    override fun onDownloadItemComplete(jsobj: JSObject) {}
    override fun onQueueChanged(hasWork: Boolean) {}
  }

  private fun config(id: String = "A", address: String = owner, user: String = "alice", token: String = "synthetic-A") =
          ServerConnectionConfig(id, 0, "Synthetic $id", address, null, user, "synthetic", token, null)

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    Paper.book("downloadItems").destroy()
    Paper.book("localLibraryItems").destroy()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    ownerSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    otherSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    owner = "http://127.0.0.1:${ownerSocket.localPort}"
    try {
      WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
    } catch (e: IllegalStateException) {
      // Already initialized by an earlier test in this process
    }
    root = File(context.cacheDir, "auth-wiring").apply { deleteRecursively(); mkdirs() }
  }

  @After
  fun tearDown() {
    // Also stop a deliberately broken mutant's transfer when an assertion fails mid-test.
    managers.forEach { manager ->
      manager.downloadItemQueue.map { it.id }.forEach { manager.removeDownloadItem(it, "test cleanup") }
    }
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    if (::ownerSocket.isInitialized) ownerSocket.close()
    if (::otherSocket.isInitialized) otherSocket.close()
  }

  private fun part(itemId: String, name: String, bytes: Long = 0) = DownloadItemPart(
          id = "part-$itemId-$name", downloadItemId = itemId, filename = name, fileSize = 10,
          destinationPath = File(root, "staging/$itemId/$name.part").path,
          finalDestinationPath = File(root, "final/$itemId/$name").path,
          serverPath = "/api/items/$itemId/file/$name/download",
          localFolderName = "Internal App Storage", localFolderUrl = "", localFolderId = "internal-book",
          ebookFile = null, audioTrack = null, episode = null, completed = false, moved = false, isMoving = false,
          failed = false, uri = Uri.parse("$owner/x"), destinationUri = Uri.parse("file:///s/$name"),
          finalDestinationUri = Uri.parse("file:///f/$name"), completedDestinationUri = null,
          finalDestinationSubfolder = "Author/Book", downloadId = null, lastUpdateTime = null, progress = 0,
          bytesDownloaded = bytes)

  private fun item(id: String, vararg parts: DownloadItemPart) = DownloadItem(
          id = id, libraryItemId = id, episodeId = null, userMediaProgress = null,
          serverConnectionConfigId = "A", serverAddress = owner, serverUserId = "alice", mediaType = "book",
          itemFolderPath = File(root, "final/$id").path,
          localFolder = LocalFolder("internal-book", "Internal App Storage", "", "", "", "internal", "book"),
          itemTitle = "Title $id", itemSubfolder = "Author/Book",
          media = MediaType(MediaTypeMetadata("Title $id", false), null), downloadItemParts = parts.toMutableList())

  private fun manager() = DownloadItemManager(FolderScanner(context), context, emitter).also { managers.add(it) }

  /** Robolectric reports 0 free bytes unless told otherwise; give the staging folder room so tryReserve passes. */
  private fun room(p: DownloadItemPart) {
    val dir = File(p.destinationPath).parentFile!!.apply { mkdirs() }
    ShadowStatFs.registerStats(dir.absolutePath, 1_000_000, 900_000, 900_000)
  }

  private fun staged(p: DownloadItemPart, size: Int) =
          File(p.destinationPath).apply { parentFile?.mkdirs(); writeBytes(ByteArray(size) { 7 }) }

  @Test
  fun retryPreflightKnowsWhenNoCredentialsCanBeChosenAndChangesNothing() {
    val p = part("pf", "01.mp3", bytes = 6).also { it.failed = true }
    staged(p, 6)
    DeviceManager.dbManager.saveDownloadItem(item("pf", p))
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(config(user = "mallory"))
    val m = manager()
    m.restoreQueue()
    val before = m.downloadItemQueue.single().downloadItemParts.single().failureReason

    assertFalse(m.hasDownloadAuthorization("pf"))
    assertEquals("no hold is recorded by asking", before, m.downloadItemQueue.single().downloadItemParts.single().failureReason)
    assertFalse("unknown item", m.hasDownloadAuthorization("nope"))

    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(config())
    assertTrue(m.hasDownloadAuthorization("pf"))
  }

  @Test
  fun onlyTheAutoDownloadPreflightRefusesForAuthorization() {
    val host = File("src/main/java/app/absplus/android/services/DownloadServiceHost.kt").readText()
    val preflight = host.substring(host.indexOf("fun retryRefusal("), host.indexOf("fun retryExisting("))
    assertTrue(preflight.contains("hasDownloadAuthorization"))
    val retry = host.substring(host.indexOf("fun retryExisting("), host.indexOf("/** Cancel or Clear one download"))
    assertFalse("a manual retry must still reach the hold", retry.contains("hasDownloadAuthorization"))
  }
}
