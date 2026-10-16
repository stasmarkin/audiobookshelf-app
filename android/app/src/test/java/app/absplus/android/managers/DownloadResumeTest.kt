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
import org.junit.Assert.assertArrayEquals
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
 * Download resume across a restart of the app (Claude, download reliability A). Staged bytes are kept only for a
 * transfer whose source is identified (ETag or Last-Modified), resumed with Range + If-Range, and appended only to the
 * same offset, total size and version of the file; anything else starts from byte zero. Responses come from a scripted
 * loopback server owned by the test, shaped like the Audiobookshelf server's (express `send`); media is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadResumeTest {
  private lateinit var context: Context
  private lateinit var root: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    root = File(context.cacheDir, "resume").apply { deleteRecursively(); mkdirs() }
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  // ---- scripted server: one response per accepted connection, in order ----

  private fun server(vararg responses: (String) -> ByteArray): Pair<Int, () -> List<String>> {
    val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
    val requests = mutableListOf<String>()
    val done = CountDownLatch(1)
    thread(isDaemon = true) {
      socket.use { s ->
        for (response in responses) {
          try {
            s.accept().use { c ->
              val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
              val head = StringBuilder()
              while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; head.append(line).append('\n') }
              synchronized(requests) { requests += head.toString() }
              c.getOutputStream().apply { write(response(head.toString())); flush() }
              c.shutdownOutput()
              try { while (c.getInputStream().read() >= 0) Unit } catch (e: java.io.IOException) { }
            }
          } catch (e: java.net.SocketTimeoutException) {
            break
          }
        }
      }
      done.countDown()
    }
    return socket.localPort to { done.await(15, TimeUnit.SECONDS); synchronized(requests) { requests.toList() } }
  }

  private fun http(status: String, headers: List<String>, body: ByteArray = ByteArray(0)) =
          ("HTTP/1.1 $status\r\n" + (headers + "Content-Length: ${body.size}" + "Connection: close").joinToString("") { "$it\r\n" } + "\r\n").toByteArray(Charsets.ISO_8859_1) + body

  private data class Outcome(val failed: Boolean?, val validators: List<String?>)

  private fun transfer(file: File, expected: Long, port: Int, validator: String?): Outcome {
    val latch = CountDownLatch(1); var result: Boolean? = null
    val validators = mutableListOf<String?>()
    InternalDownloadManager(file, expected, object : DownloadItemManager.InternalProgressCallback {
      override fun onSizeResolved(totalBytes: Long) {}
      override fun onProgress(totalBytesWritten: Long, progress: Long) {}
      override fun onComplete(failed: Boolean) { result = failed; latch.countDown() }
      override fun onAuthError() { latch.countDown() }
      override fun onValidator(validator: String?) { synchronized(validators) { validators += validator } }
    }, validator) { true }.download("http://127.0.0.1:$port/api/items/li_x/file/1/download", "synthetic-token")
    latch.await(15, TimeUnit.SECONDS)
    return Outcome(result, synchronized(validators) { validators.toList() })
  }

  private fun media(n: Int, seed: Int = 0) = ByteArray(n) { ((it + seed) % 251).toByte() }

  private val etag = "W/\"3e8-19a1\""

  @Test
  fun aFullAnswerReportsWhatIdentifiesTheFileEtagFirstThenLastModified() {
    val (p1, _) = server({ http("200 OK", listOf("ETag: $etag", "Last-Modified: Tue, 13 Oct 2026 22:51:59 GMT"), media(1000)) })
    assertEquals(listOf<String?>(etag), transfer(File(root, "v1.part"), 1000, p1, null).validators)
    val (p2, _) = server({ http("200 OK", listOf("Last-Modified: Tue, 13 Oct 2026 22:51:59 GMT"), media(1000)) })
    assertEquals(listOf<String?>("Tue, 13 Oct 2026 22:51:59 GMT"), transfer(File(root, "v2.part"), 1000, p2, null).validators)
    val (p3, _) = server({ http("200 OK", emptyList(), media(1000)) })
    assertEquals(listOf<String?>(null), transfer(File(root, "v3.part"), 1000, p3, null).validators)
  }

  @Test
  fun stagedBytesResumeWithIfRangeAndAppendOnlyToTheSameVersion() {
    val full = media(1000)
    val f = File(root, "a.part").apply { writeBytes(full.copyOf(400)) }
    val (port, requests) = server({ http("206 Partial Content", listOf("ETag: $etag", "Content-Range: bytes 400-999/1000"), full.copyOfRange(400, 1000)) })
    val outcome = transfer(f, 1000, port, etag)
    assertEquals(false, outcome.failed)
    val request = requests().single()
    assertTrue(request.contains("Range: bytes=400-"))
    assertTrue(request.contains("If-Range: $etag"))
    assertArrayEquals(full, f.readBytes())
    assertTrue("an appended resume keeps the validator it was started with", outcome.validators.isEmpty())
  }

  @Test
  fun aChangedFileAnswers200AndIsRewrittenFromByteZeroNeverMixed() {
    val old = media(1000, seed = 7)
    val changed = media(1200, seed = 99)
    val f = File(root, "b.part").apply { writeBytes(old.copyOf(400)) }
    val (port, requests) = server({ http("200 OK", listOf("ETag: W/\"4b0-2b2b\""), changed) })
    val outcome = transfer(f, 1000, port, etag)
    assertEquals(false, outcome.failed)
    assertTrue(requests().single().contains("If-Range: $etag"))
    assertArrayEquals(changed, f.readBytes())
    assertEquals(listOf<String?>("W/\"4b0-2b2b\""), outcome.validators)
  }

  @Test
  fun aPartialAnswerForAnotherOffsetSizeOrVersionRestartsFromZero() {
    val full = media(1000)
    // another offset
    val f1 = File(root, "c1.part").apply { writeBytes(full.copyOf(400)) }
    val (p1, r1) = server(
            { http("206 Partial Content", listOf("ETag: $etag", "Content-Range: bytes 300-999/1000"), full.copyOfRange(300, 1000)) },
            { http("200 OK", listOf("ETag: $etag"), full) })
    assertEquals(false, transfer(f1, 1000, p1, etag).failed)
    assertArrayEquals(full, f1.readBytes())
    assertFalse("the restart asks for the whole file", r1()[1].contains("Range:"))
    // another total size (the file was replaced by one of a different length)
    val f2 = File(root, "c2.part").apply { writeBytes(full.copyOf(400)) }
    val bigger = media(1500, seed = 3)
    val (p2, _) = server(
            { http("206 Partial Content", listOf("ETag: $etag", "Content-Range: bytes 400-1499/1500"), bigger.copyOfRange(400, 1500)) },
            { http("200 OK", listOf("ETag: W/\"5dc-1\""), bigger) })
    assertEquals(false, transfer(f2, 1000, p2, etag).failed)
    assertArrayEquals(bigger, f2.readBytes())
    // a server that ignores If-Range but identifies another version
    val f3 = File(root, "c3.part").apply { writeBytes(full.copyOf(400)) }
    val other = media(1000, seed = 50)
    val (p3, _) = server(
            { http("206 Partial Content", listOf("ETag: W/\"other\"", "Content-Range: bytes 400-999/1000"), other.copyOfRange(400, 1000)) },
            { http("200 OK", listOf("ETag: W/\"other\""), other) })
    assertEquals(false, transfer(f3, 1000, p3, etag).failed)
    assertArrayEquals(other, f3.readBytes())
  }

  @Test
  fun theServersUnsatisfiableRangeAnswerConfirmsACompleteFileOrRestarts() {
    // Audiobookshelf answers a range at the end of the file with 500 and "Content-Range: bytes */size"
    val full = media(1000)
    val f1 = File(root, "d1.part").apply { writeBytes(full) }
    val (p1, _) = server({ http("500 Internal Server Error", listOf("Content-Range: bytes */1000"), "Internal Error".toByteArray()) })
    assertEquals(false, transfer(f1, 1000, p1, etag).failed)
    assertArrayEquals(full, f1.readBytes())
    // more bytes staged than the file has: never kept
    val f2 = File(root, "d2.part").apply { writeBytes(media(1200)) }
    val (p2, _) = server(
            { http("500 Internal Server Error", listOf("Content-Range: bytes */1000"), "Internal Error".toByteArray()) },
            { http("200 OK", listOf("ETag: $etag"), full) })
    assertEquals(false, transfer(f2, 1000, p2, etag).failed)
    assertArrayEquals(full, f2.readBytes())
  }

  @Test
  fun aCutShortResumeIsIncompleteAndKeepsWhatArrivedForTheNextAttempt() {
    val full = media(1000)
    val f = File(root, "e.part").apply { writeBytes(full.copyOf(400)) }
    // 206 for the right range whose body ends early (the declared total is not reached)
    val (port, _) = server({
      ("HTTP/1.1 206 Partial Content\r\nETag: $etag\r\nContent-Range: bytes 400-999/1000\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
              "${Integer.toHexString(200)}\r\n").toByteArray(Charsets.ISO_8859_1) + full.copyOfRange(400, 600) + "\r\n0\r\n\r\n".toByteArray()
    })
    assertEquals(true, transfer(f, 1000, port, etag).failed)
    assertArrayEquals(full.copyOf(600), f.readBytes())
  }

  // ---- restart of the app: what the queue keeps ----

  @Test
  fun stagedResumeDropsTheLastMegabyteAndNothingFromAShortFile() {
    val f = File(root, "s.part").apply { writeBytes(ByteArray(3 * 1024 * 1024) { 1 }) }
    assertEquals(2L * 1024 * 1024, StagedResume.keepForResume(f))
    assertEquals(2L * 1024 * 1024, f.length())
    val small = File(root, "small.part").apply { writeBytes(ByteArray(500_000)) }
    assertEquals(0L, StagedResume.keepForResume(small))
    assertEquals(0L, StagedResume.keepForResume(File(root, "missing.part")))
  }

  private val owner = "http://127.0.0.1:1"
  private val emitter = object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) {}
    override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
    override fun onDownloadItemComplete(jsobj: JSObject) {}
    override fun onQueueChanged(hasWork: Boolean) {}
  }

  private fun queuePart(id: String, validator: String?) = DownloadItemPart(
          id = "part-$id", downloadItemId = id, filename = "$id.mp3", fileSize = 4L * 1024 * 1024,
          destinationPath = File(root, "staging/$id/$id.mp3.part").path,
          finalDestinationPath = File(root, "final/$id/$id.mp3").path,
          serverPath = "/api/items/li_x/file/1/download",
          localFolderName = "Internal App Storage", localFolderUrl = "", localFolderId = "internal-podcast",
          ebookFile = null, audioTrack = null, episode = null, completed = false, moved = false, isMoving = false,
          failed = false, uri = Uri.parse("$owner/x"), destinationUri = Uri.parse("file:///s/$id"),
          finalDestinationUri = Uri.parse("file:///f/$id"), completedDestinationUri = null,
          finalDestinationSubfolder = "Synthetic", downloadId = -1L, lastUpdateTime = null, progress = 70,
          bytesDownloaded = 3L * 1024 * 1024, resumeValidator = validator)

  private fun queueItem(id: String, p: DownloadItemPart, serverId: String = "A") = DownloadItem(
          id = id, libraryItemId = "li_x", episodeId = id, userMediaProgress = null,
          serverConnectionConfigId = serverId, serverAddress = owner, serverUserId = "alice", mediaType = "podcast",
          itemFolderPath = File(root, "final/$id").path,
          localFolder = LocalFolder("internal-podcast", "Internal App Storage", "", "", "", "internal", "podcast"),
          itemTitle = "Synthetic", itemSubfolder = "Synthetic",
          media = MediaType(MediaTypeMetadata("Synthetic", false), null), downloadItemParts = mutableListOf(p))

  private fun withQueueEnv(block: () -> Unit) {
    Paper.init(context)
    listOf("downloadItems", "localLibraryItems", "autoDownload").forEach { Paper.book(it).destroy() }
    val previousServers = DeviceManager.deviceData.serverConnectionConfigs
    val previousActive = DeviceManager.serverConnectionConfig
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(ServerConnectionConfig("A", 0, "Synthetic A", owner, null, "alice", "synthetic", "synthetic-A", null))
    try {
      WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
    } catch (e: IllegalStateException) {
      // Already initialized by an earlier test in this process
    }
    try {
      block()
    } finally {
      DeviceManager.deviceData.serverConnectionConfigs = previousServers
      DeviceManager.serverConnectionConfig = previousActive
    }
  }

  private fun staged(p: DownloadItemPart, bytes: Int) = File(p.destinationPath).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes) { 5 }) }

  @Test
  fun afterARestartIdentifiedStagedBytesAreKeptForAResumeAndUnidentifiedOnesRestart() = withQueueEnv {
    val kept = queuePart("r1", etag)
    staged(kept, 3 * 1024 * 1024)
    DeviceManager.dbManager.saveDownloadItem(queueItem("r1", kept))
    val unknown = queuePart("r2", null)
    staged(unknown, 3 * 1024 * 1024)
    DeviceManager.dbManager.saveDownloadItem(queueItem("r2", unknown))

    val m = DownloadItemManager(FolderScanner(context), context, emitter).apply { restoreQueue() }
    val r1 = m.downloadItemQueue.single { it.id == "r1" }.downloadItemParts.single()
    assertEquals(etag, r1.resumeValidator)
    assertEquals(2L * 1024 * 1024, File(r1.destinationPath).length())
    assertEquals(2L * 1024 * 1024, r1.bytesDownloaded)
    assertFalse(r1.failed)
    assertFalse(r1.completed)
    assertNull(r1.downloadId)

    val r2 = m.downloadItemQueue.single { it.id == "r2" }.downloadItemParts.single()
    assertNull(r2.resumeValidator)
    assertFalse("bytes from an unidentified source are not resumed", File(r2.destinationPath).exists())
    assertEquals(0L, r2.bytesDownloaded)
  }

  @Test
  fun aRestartWithAnotherSavedServerHoldsTheDownloadAndKeepsItsBytes() = withQueueEnv {
    // R01/H01: the item's server no longer matches the saved one; nothing transfers with another server's credentials
    val p = queuePart("h1", etag)
    staged(p, 3 * 1024 * 1024)
    DeviceManager.dbManager.saveDownloadItem(queueItem("h1", p, serverId = "gone"))
    val m = DownloadItemManager(FolderScanner(context), context, emitter).apply { restoreQueue() }
    val held = m.downloadItemQueue.single().downloadItemParts.single()
    assertTrue(held.authorizationHold)
    assertTrue(held.failed)
    assertEquals(3L * 1024 * 1024, File(held.destinationPath).length())
    assertFalse(m.hasWork())
  }
}
