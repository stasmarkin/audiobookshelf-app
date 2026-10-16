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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
 * Zero-byte / incomplete download integrity (plus): a finished transfer counts as the file only when it is not empty
 * and matches the server-declared size, or the expected metadata size when the server sends none. Responses come from
 * a scripted loopback server owned by the test; media is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadIntegrityTest {
  private lateinit var context: Context
  private lateinit var root: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    root = File(context.cacheDir, "integrity").apply { deleteRecursively(); mkdirs() }
  }

  // ---- the rule ----

  @Test
  fun completenessRule() {
    assertEquals("empty response (0 bytes)", DownloadIntegrity.incompleteReason(0, null, 1000))
    assertEquals("empty response (0 bytes)", DownloadIntegrity.incompleteReason(0, 0, 0)) // Content-Length: 0
    assertEquals("empty response (0 bytes)", DownloadIntegrity.incompleteReason(0, null, 0))
    assertNull(DownloadIntegrity.incompleteReason(1000, 1000, 1000))
    assertNull(DownloadIntegrity.incompleteReason(1200, 1200, 1000)) // server size wins over stale metadata
    assertTrue(DownloadIntegrity.incompleteReason(400, 1000, 1000)!!.startsWith("received 400 of 1000"))
    assertTrue(DownloadIntegrity.incompleteReason(400, null, 1000)!!.contains("server sent no size"))
    assertNull(DownloadIntegrity.incompleteReason(1000, null, 1000))
    assertNull(DownloadIntegrity.incompleteReason(10, null, 0)) // nothing known to compare with
  }

  // ---- real transfers through InternalDownloadManager ----

  /** One scripted HTTP exchange per call; returns the request head it received. */
  private fun server(response: (String) -> ByteArray): Pair<Int, () -> String> {
    val socket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
    var request = ""
    val done = CountDownLatch(1)
    thread(isDaemon = true) {
      socket.use { s ->
        s.accept().use { c ->
          val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
          val head = StringBuilder()
          while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; head.append(line).append('\n') }
          request = head.toString()
          c.getOutputStream().apply { write(response(request)); flush() }
          // Close gracefully: FIN after the data, then wait for the client to close; an abrupt close can reset the
          // connection before the client has read the response
          c.shutdownOutput()
          try { while (c.getInputStream().read() >= 0) Unit } catch (e: java.io.IOException) { }
        }
      }
      done.countDown()
    }
    return socket.localPort to { done.await(10, TimeUnit.SECONDS); request }
  }

  private fun http(status: String, headers: List<String>, body: ByteArray = ByteArray(0)) =
          ("HTTP/1.1 $status\r\n" + (headers + "Connection: close").joinToString("") { "$it\r\n" } + "\r\n").toByteArray(Charsets.ISO_8859_1) + body

  private fun chunked(body: ByteArray) =
          (if (body.isEmpty()) ByteArray(0) else "${Integer.toHexString(body.size)}\r\n".toByteArray() + body + "\r\n".toByteArray()) + "0\r\n\r\n".toByteArray()

  private data class Outcome(val failed: Boolean?, val auth: Boolean)

  private fun transfer(file: File, expected: Long, port: Int): Outcome {
    val latch = CountDownLatch(1); var result: Boolean? = null; var auth = false
    InternalDownloadManager(file, expected, object : DownloadItemManager.InternalProgressCallback {
      override fun onSizeResolved(totalBytes: Long) {}
      override fun onProgress(totalBytesWritten: Long, progress: Long) {}
      override fun onComplete(failed: Boolean) { result = failed; latch.countDown() }
      override fun onAuthError() { auth = true; latch.countDown() }
    }) { true }.download("http://127.0.0.1:$port/api/items/li_x/file/1/download", "synthetic-token")
    latch.await(15, TimeUnit.SECONDS)
    return Outcome(result, auth)
  }

  private fun media(n: Int) = ByteArray(n) { (it % 251).toByte() }

  @Test
  fun emptyChunked200IsNotAComplete0ByteFile() {
    val (port, req) = server { http("200 OK", listOf("Content-Type: audio/mpeg", "Transfer-Encoding: chunked"), chunked(ByteArray(0))) }
    val f = File(root, "a.mp3.part")
    assertEquals(true, transfer(f, 960598, port).failed)
    req()
    assertEquals(0L, f.length())
  }

  @Test
  fun contentLengthZeroIsNotACompleteFile() {
    val (port, _) = server { http("200 OK", listOf("Content-Length: 0")) }
    assertEquals(true, transfer(File(root, "b.mp3.part"), 0, port).failed) // even with no expected size
  }

  @Test
  fun anInterruptedTransferResumesWithARangeRequestToAByteExactFile() {
    val full = media(1000)
    // the first 400 bytes of an interrupted transfer are kept in staging (a short Content-Length body is rejected by
    // the HTTP client itself as "unexpected end of stream"; the retry resumes from these bytes)
    val f = File(root, "c.mp3.part").apply { writeBytes(full.copyOf(400)) }
    val (p2, req) = server { http("206 Partial Content", listOf("Content-Length: 600", "Content-Range: bytes 400-999/1000"), full.copyOfRange(400, 1000)) }
    assertEquals(false, transfer(f, 1000, p2).failed)
    assertTrue(req().contains("Range: bytes=400-"))
    assertTrue(full.contentEquals(f.readBytes()))
  }

  @Test
  fun sizelessBodyShorterThanTheExpectedSizeFailsAndAMatchingOneCompletes() {
    val f = File(root, "d.mp3.part")
    val (p1, _) = server { http("200 OK", listOf("Transfer-Encoding: chunked"), chunked(media(300))) }
    assertEquals(true, transfer(f, 1000, p1).failed)
    f.delete()
    val (p2, _) = server { http("200 OK", listOf("Transfer-Encoding: chunked"), chunked(media(1000))) }
    assertEquals(false, transfer(f, 1000, p2).failed)
    assertEquals(1000L, f.length())
  }

  @Test
  fun aNormalDownloadStillCompletesAndServerSizeOverridesStaleMetadata() {
    val f = File(root, "e.mp3.part")
    val (port, _) = server { http("200 OK", listOf("Content-Length: 1200"), media(1200)) }
    assertEquals(false, transfer(f, 1000, port).failed)
    assertEquals(1200L, f.length())
  }

  @Test
  fun alreadyCompleteStagedFileIsConfirmedBy416() {
    val f = File(root, "g.mp3.part").apply { writeBytes(media(500)) }
    val (port, _) = server { http("416 Range Not Satisfiable", listOf("Content-Range: bytes */500", "Content-Length: 0")) }
    assertEquals(false, transfer(f, 500, port).failed)
    assertEquals(500L, f.length())
  }

  // ---- queue: an empty staged file is never finalized, and this survives a restart ----

  private val owner = "http://127.0.0.1:1"
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null

  private fun queuePart(id: String) = DownloadItemPart(
          id = "part-$id", downloadItemId = id, filename = "$id.mp3", fileSize = 1000,
          destinationPath = File(root, "staging/$id/$id.mp3.part").path,
          finalDestinationPath = File(root, "final/$id/$id.mp3").path,
          serverPath = "/api/items/li_x/file/1/download",
          localFolderName = "Internal App Storage", localFolderUrl = "", localFolderId = "internal-podcast",
          ebookFile = null, audioTrack = null, episode = null, completed = true, moved = false, isMoving = false,
          failed = false, uri = Uri.parse("$owner/x"), destinationUri = Uri.parse("file:///s/$id"),
          finalDestinationUri = Uri.parse("file:///f/$id"), completedDestinationUri = null,
          finalDestinationSubfolder = "Synthetic", downloadId = null, lastUpdateTime = null, progress = 100,
          bytesDownloaded = 0)

  private fun queueItem(id: String, p: DownloadItemPart) = DownloadItem(
          id = id, libraryItemId = "li_x", episodeId = id, userMediaProgress = null,
          serverConnectionConfigId = "A", serverAddress = owner, serverUserId = "alice", mediaType = "podcast",
          itemFolderPath = File(root, "final/$id").path,
          localFolder = LocalFolder("internal-podcast", "Internal App Storage", "", "", "", "internal", "podcast"),
          itemTitle = "Synthetic", itemSubfolder = "Synthetic",
          media = MediaType(MediaTypeMetadata("Synthetic", false), null), downloadItemParts = mutableListOf(p))

  @Test
  fun anEmptyStagedFileMarkedCompleteIsNotFinalizedAndNotRegisteredAsDownloaded() {
    Paper.init(context)
    listOf("downloadItems", "localLibraryItems").forEach { Paper.book(it).destroy() }
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(ServerConnectionConfig("A", 0, "Synthetic A", owner, null, "alice", "synthetic", "synthetic-A", null))
    try {
      WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
    } catch (e: IllegalStateException) {
      // Already initialized by an earlier test in this process
    }
    try {
      val emitter = object : DownloadItemManager.DownloadEventEmitter {
        override fun onDownloadItem(downloadItem: DownloadItem) {}
        override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
        override fun onDownloadItemComplete(jsobj: JSObject) {}
        override fun onQueueChanged(hasWork: Boolean) {}
      }
      val p = queuePart("z1")
      File(p.destinationPath).apply { parentFile?.mkdirs(); writeBytes(ByteArray(0)) }
      val m = DownloadItemManager(FolderScanner(context), context, emitter)
      m.addDownloadItem(queueItem("z1", p))
      m.resumeWork()
      // the watcher picks the completed staged part up within a tick or two
      for (i in 0 until 40) { if (p.retryCount > 0) break; Thread.sleep(100) }
      assertEquals(1, p.retryCount)
      assertFalse(p.completed)
      assertFalse(p.moved)
      assertFalse(File(p.finalDestinationPath).exists())
      assertTrue(DeviceManager.dbManager.getLocalLibraryItems().none { it.libraryItemId == "li_x" })
      // persisted state after a restart: still not downloaded, nothing registered
      val restored = DownloadItemManager(FolderScanner(context), context, emitter).apply { restoreQueue() }.downloadItemQueue.single()
      assertFalse(restored.downloadItemParts.single().moved)
      assertFalse(restored.isDownloadFinished)
      m.removeDownloadItem("z1", "test cleanup")
    } finally {
      DeviceManager.deviceData.serverConnectionConfigs = previousServers
      DeviceManager.serverConnectionConfig = previousActive
    }
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }
}
