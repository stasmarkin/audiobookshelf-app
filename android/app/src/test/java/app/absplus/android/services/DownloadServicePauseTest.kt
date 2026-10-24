package app.absplus.android.services

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
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
import app.absplus.android.managers.AutoTransferGate
import app.absplus.android.managers.DownloadItemManager
import app.absplus.android.managers.InternalDownloadManager
import app.absplus.android.managers.StagedResume
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import com.getcapacitor.JSObject
import io.paperdb.Paper
import io.paperdb.Book
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/**
 * Real host/service/queue, synthetic Paper records and app-cache media only. Holding the queue monitor keeps a
 * timeout's IO pause pending; the service callback must still stop promptly. Payload checks use controlled loopback
 * only. No sleeps, provider deletion, book destruction or fixture-file deletion. This does not emulate quota enforcement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [DownloadServicePauseTest.PromotionShadow::class])
class DownloadServicePauseTest {
  @Implements(Service::class)
  class PromotionShadow : ShadowService() {
    companion object { var refuse = false }

    @Implementation(minSdk = 29)
    public override fun startForeground(id: Int, notification: Notification, type: Int) {
      if (refuse) throw ForegroundServiceStartNotAllowedException("Synthetic exhausted dataSync allowance")
      super.startForeground(id, notification, type)
    }
  }

  private lateinit var context: Context
  private lateinit var queue: DownloadItemManager
  private lateinit var root: File
  private val controllers = mutableListOf<ServiceController<DownloadService>>()
  private val savedHost = mutableMapOf<String, Any?>()
  private val savedBooks = mutableMapOf<String, Book>()
  private var savedPaperContext: Any? = null
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private val cancels = AtomicInteger()
  private val owner = "http://127.0.0.1:1"
  private val noEvents = object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) {}
    override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
    override fun onDownloadItemComplete(jsobj: JSObject) {}
    override fun onQueueChanged(hasWork: Boolean) {}
  }

  @Before
  fun setUp() {
    PromotionShadow.refuse = false
    val app = ApplicationProvider.getApplicationContext<Context>()
    root = File(app.cacheDir, "host-pause-${UUID.randomUUID()}").apply { check(mkdirs()) }
    context = object : ContextWrapper(app) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = File(root, "database").apply { mkdirs() }
    }
    savedPaperContext = Paper::class.java.getDeclaredField("mContext").let { it.isAccessible = true; it.get(null) }
    savedBooks.putAll(paperBooks())
    paperBooks().clear() // In-memory handles only; never destroy another test's persisted books.
    Paper.init(context)
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    val saved = ServerConnectionConfig("pause-A", 0, "Synthetic", owner, null, "alice", "synthetic", "synthetic-A", null)
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(saved)
    DeviceManager.serverConnectionConfig = saved
    try {
      WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
    } catch (_: IllegalStateException) { /* Another test initialized this isolated application. */ }
    queue = newQueue()
    for (name in listOf("manager", "service", "pausedBySystem", "pauseGeneration", "restoreJob", "bridgeReady", "appContext", "networkWatched")) {
      savedHost[name] = hostField(name)
    }
    setHost("manager", queue)
    setHost("service", null)
    setHost("restoreJob", null)
    setHost("pausedBySystem", false)
    setHost("pauseGeneration", 0)
    setHost("bridgeReady", false)
  }

  @After
  fun tearDown() {
    PromotionShadow.refuse = false
    controllers.forEach { it.destroy() }
    awaitHostJobs()
    queue.destroy()
    savedHost.forEach { (name, value) -> setHost(name, value) }
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    paperBooks().clear()
    paperBooks().putAll(savedBooks)
    Paper::class.java.getDeclaredField("mContext").also { it.isAccessible = true }.set(null, savedPaperContext)
  }

  private fun newQueue() = DownloadItemManager(FolderScanner(context), context, noEvents).also {
    it.autoGate = object : AutoTransferGate {
      override fun isAuto(item: DownloadItem) = false
      override fun allowedNow() = true
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun paperBooks() = Paper::class.java.getDeclaredField("mBookMap").let {
    it.isAccessible = true; it.get(null) as ConcurrentHashMap<String, Book>
  }

  private fun hostField(name: String): Any? = DownloadServiceHost::class.java.getDeclaredField(name).let {
    it.isAccessible = true; it.get(null)
  }

  private fun setHost(name: String, value: Any?) {
    DownloadServiceHost::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(null, value)
  }

  private fun hostJobs(): List<Job> = ((hostField("scope") as CoroutineScope).coroutineContext[Job]!!).children.toList()
  private fun awaitHostJobs(jobs: List<Job> = hostJobs()) = runBlocking { withTimeout(10_000) { jobs.joinAll() } }
  private fun suspended() = DownloadItemManager::class.java.getDeclaredField("transfersSuspended").let {
    it.isAccessible = true; it.getBoolean(queue)
  }

  private fun service(refused: Boolean = false): DownloadService {
    PromotionShadow.refuse = refused
    val controller = Robolectric.buildService(DownloadService::class.java)
    controllers.add(controller)
    controller.create()
    return controller.get()
  }

  private fun runningFixture(): DownloadItemPart {
    val id = "pause-${UUID.randomUUID()}"
    val file = File(root, "$id.mp3.part")
    // Large enough to exercise the existing validated restart rollback without deleting the staging fixture.
    file.writeBytes(ByteArray((StagedResume.ROLLBACK_BYTES + 37).toInt()) { (it % 251).toByte() })
    val part = DownloadItemPart(
            id = "part-$id", downloadItemId = id, filename = "$id.mp3", fileSize = 4_000_000,
            destinationPath = file.path, finalDestinationPath = File(root, "final/$id.mp3").path,
            serverPath = "/api/items/li_pause/file/1/download", localFolderName = "Internal App Storage",
            localFolderUrl = "", localFolderId = "internal-book", ebookFile = null, audioTrack = null, episode = null,
            completed = false, moved = false, isMoving = false, failed = false, uri = Uri.parse("$owner/x"),
            destinationUri = Uri.fromFile(file), finalDestinationUri = Uri.fromFile(File(root, "final/$id.mp3")),
            completedDestinationUri = null, finalDestinationSubfolder = "Synthetic", downloadId = -1,
            lastUpdateTime = System.currentTimeMillis(), progress = 1, bytesDownloaded = file.length())
    part.resumeValidator = "\"synthetic-validator\""
    val item = DownloadItem(id, "li_pause", null, null, "pause-A", owner, "alice", "book", root.path,
            LocalFolder("internal-book", "Internal App Storage", "", "", "", "internal", "book"),
            "Synthetic", "Synthetic", MediaType(MediaTypeMetadata("Synthetic", false), null), mutableListOf(part))
    // Avoid admission/finalization: this fixture represents a transfer that is already running.
    queue.downloadItemQueue.add(item)
    queue.currentDownloadItemParts.add(part)
    @Suppress("UNCHECKED_CAST")
    val calls = DownloadItemManager::class.java.getDeclaredField("activeCalls").let {
      it.isAccessible = true; it.get(queue) as ConcurrentHashMap<String, InternalDownloadManager.DownloadHandle>
    }
    calls[part.id] = object : InternalDownloadManager.DownloadHandle { override fun cancel() { cancels.incrementAndGet() } }
    DeviceManager.dbManager.saveDownloadItem(item)
    return part
  }

  private fun assertPaused(part: DownloadItemPart) {
    assertTrue(suspended())
    assertNull(hostField("service"))
    assertEquals(true, hostField("pausedBySystem"))
    assertNull(part.downloadId)
    assertFalse(part.failed)
    assertFalse(part.completed)
    assertTrue(queue.currentDownloadItemParts.isEmpty())
    assertEquals(1, cancels.get())
    assertEquals(File(part.destinationPath).length(), part.bytesDownloaded)
    val stored = Paper.book("downloadItems").read<DownloadItem>(part.downloadItemId)!!
    assertEquals("pause-A", stored.serverConnectionConfigId)
    assertEquals("alice", stored.serverUserId)
    assertNull(stored.terminalFailureAt)
    assertNull(stored.downloadItemParts.single().downloadId)
    assertEquals(part.bytesDownloaded, stored.downloadItemParts.single().bytesDownloaded)
    assertEquals(part.resumeValidator, stored.downloadItemParts.single().resumeValidator)
    println("PAUSE: service=null suspended=true cancelled=${cancels.get()} savedOwner=${stored.serverConnectionConfigId}/${stored.serverUserId} savedHandle=null savedBytes=${part.bytesDownloaded}")
  }

  private fun refusedWhilePauseIsPending(attempts: Int) {
    val active = service()
    awaitHostJobs()
    val part = runningFixture()
    val jobs: List<Job>
    synchronized(queue) {
      active.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      assertTrue("stopSelf must not wait for the blocked queue", shadowOf(active).isStoppedBySelf)
      val generation = hostField("pauseGeneration")
      repeat(attempts) {
        val refused = service(refused = true)
        assertTrue(shadowOf(refused).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, refused.onStartCommand(null, 0, 2))
      }
      jobs = hostJobs()
      println("ORDER: timeoutGeneration=$generation refusedStarts=$attempts resultingGeneration=${hostField("pauseGeneration")} pendingJobs=${jobs.size}")
      assertEquals("failed promotion must not supersede a pending owned pause", generation, hostField("pauseGeneration"))
    }
    awaitHostJobs(jobs)
    assertPaused(part)
  }

  @Test fun refusedReplacementCannotInvalidatePendingTimeoutPause() = refusedWhilePauseIsPending(1)
  @Test fun multipleRefusedReplacementsCannotInvalidatePendingTimeoutPause() = refusedWhilePauseIsPending(3)

  @Test
  fun duplicateTimeoutCallbacksDoNotInvalidateTheOriginalPause() {
    val active = service(); awaitHostJobs()
    val part = runningFixture()
    val jobs: List<Job>
    synchronized(queue) {
      active.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      active.onTimeout(1)
      active.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      jobs = hostJobs()
    }
    awaitHostJobs(jobs)
    assertPaused(part)
  }

  @Test
  fun serviceDestructionDoesNotInvalidatePendingTimeoutPause() {
    val active = service(); awaitHostJobs()
    val part = runningFixture()
    val jobs: List<Job>
    synchronized(queue) {
      active.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      active.onDestroy()
      jobs = hostJobs()
    }
    awaitHostJobs(jobs)
    assertPaused(part)
  }

  @Test
  fun repeatedRefusalAfterPauseIsIdempotent() {
    val active = service(); awaitHostJobs()
    val part = runningFixture()
    active.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); awaitHostJobs()
    val generation = hostField("pauseGeneration")
    repeat(3) { service(refused = true) }
    awaitHostJobs()
    assertEquals(generation, hostField("pauseGeneration"))
    assertPaused(part)
  }

  @Test
  fun normalForegroundPromotionMaySupersedeAnOldPause() {
    val old = service(); awaitHostJobs()
    val replacement: DownloadService
    val jobs: List<Job>
    synchronized(queue) {
      old.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      replacement = service()
      old.onDestroy()
      jobs = hostJobs()
    }
    awaitHostJobs(jobs)
    assertSame(replacement, hostField("service"))
    assertFalse(suspended())
    assertEquals(false, hostField("pausedBySystem"))
    assertFalse(shadowOf(replacement).isStoppedBySelf)
    assertEquals(Service.START_STICKY, replacement.onStartCommand(Intent(), 0, 3))
    awaitHostJobs()
  }

  @Test
  fun aNewerOwnedTimeoutWinsOverAnEarlierResume() {
    val old = service(); awaitHostJobs()
    val jobs: List<Job>
    synchronized(queue) {
      old.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      val replacement = service()
      replacement.onTimeout(2, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      DownloadServiceHost.pauseBySystem(old)
      jobs = hostJobs()
    }
    awaitHostJobs(jobs)
    assertTrue(suspended())
    assertEquals(true, hostField("pausedBySystem"))
    assertNull(hostField("service"))
  }

  @Test
  fun cancelAndTimeoutKeepShutdownSafeWithNoFilesToDelete() {
    val active = service(); awaitHostJobs()
    val jobs: List<Job>
    synchronized(queue) {
      active.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      assertEquals(Service.START_NOT_STICKY, active.onStartCommand(
              Intent().setAction("app.absplus.android.download.CANCEL"), 0, 4))
      jobs = hostJobs()
    }
    awaitHostJobs(jobs)
    assertTrue(shadowOf(active).isStoppedBySelf)
    assertTrue(suspended())
    assertTrue(queue.downloadItemQueue.isEmpty())
  }

  @Test
  fun completionStillStopsTheServiceNormally() {
    val active = service(); awaitHostJobs()
    active.onQueueChanged(false)
    assertTrue(shadowOf(active).isStoppedBySelf)
    assertTrue(shadowOf(active).isForegroundStopped)
    assertEquals(false, hostField("pausedBySystem"))
  }

  @Test fun normalPayloadStillCompletesByteExactly() = payloadCompletes(prefixSize = 0)
  @Test fun anInterruptedPayloadStillResumesByteExactly() = payloadCompletes(prefixSize = 3)

  @Test
  fun explicitRetryAfterTimeoutWaitsForAnAcceptedServiceWithoutLosingTheQueue() {
    val active = service(); awaitHostJobs()
    val part = runningFixture()
    active.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); awaitHostJobs()
    assertPaused(part)
    // A finalization/transfer failure held for explicit Retry, while Android still withholds the service.
    part.failed = true
    DeviceManager.dbManager.saveDownloadItem(queue.downloadItemQueue.single())
    val answer = AtomicReference<DownloadServiceHost.ExistingDownloadResult>()
    DownloadServiceHost.retryExisting(context, part.downloadItemId) { answer.set(it) }
    awaitHostJobs()
    assertEquals(DownloadServiceHost.ExistingDownloadResult.RETRIED, answer.get())
    assertTrue("requesting a service does not itself lift Android's pause", suspended())
    assertNull(part.downloadId)
    assertFalse(part.failed)
    assertFalse(part.completed)
    val persisted = Paper.book("downloadItems").read<DownloadItem>(part.downloadItemId)!!
    assertEquals("alice", persisted.serverUserId)
    assertFalse(persisted.downloadItemParts.single().failed)
    // Keep the transfer network-gated: this case checks Retry/service ordering; payload tests below check HTTP.
    queue.autoGate = object : AutoTransferGate {
      override fun isAuto(item: DownloadItem) = true
      override fun allowedNow() = false
    }
    val replacement = service(); awaitHostJobs()
    assertSame(replacement, hostField("service"))
    assertFalse(suspended())
    assertEquals(false, hostField("pausedBySystem"))
    assertEquals(part.downloadItemId, queue.downloadItemQueue.single().id)
    assertNull(part.downloadId)
  }

  private fun payloadCompletes(prefixSize: Int) {
    val bytes = byteArrayOf(3, 1, 4, 1, 5, 9, 2)
    val file = File(root, "payload-$prefixSize.part")
    if (prefixSize > 0) file.writeBytes(bytes.copyOf(prefixSize))
    val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 10_000 }
    val serverDone = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val request = AtomicReference("")
    val failure = AtomicReference<Throwable?>()
    val result = AtomicReference<Boolean?>()
    thread(isDaemon = true) {
      try {
        socket.accept().use { client ->
          val reader = client.getInputStream().bufferedReader()
          request.set(buildString {
            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; append(line).append('\n') }
          })
          val status = if (prefixSize == 0) "200 OK" else "206 Partial Content"
          val range = if (prefixSize == 0) "" else "Content-Range: bytes $prefixSize-6/7\r\n"
          val header = "HTTP/1.1 $status\r\nContent-Length: ${bytes.size - prefixSize}\r\n" +
                  "ETag: \"fixture\"\r\n${range}Connection: close\r\n\r\n"
          client.getOutputStream().apply { write(header.toByteArray()); write(bytes, prefixSize, bytes.size - prefixSize); flush() }
        }
      } catch (e: Throwable) { failure.set(e) }
      finally { serverDone.countDown() }
    }
    try {
      val callback = object : DownloadItemManager.InternalProgressCallback {
        override fun onSizeResolved(totalBytes: Long) {}
        override fun onProgress(totalBytesWritten: Long, progress: Long) {}
        override fun onComplete(failed: Boolean) { result.set(failed); finished.countDown() }
        override fun onAuthError() { failure.set(AssertionError("Unexpected authentication failure")); finished.countDown() }
      }
      val handle = InternalDownloadManager(file, 7L, callback, if (prefixSize == 0) null else "\"fixture\"") { true }
              .download("http://127.0.0.1:${socket.localPort}/synthetic", "synthetic-pause")
      try {
        assertTrue("controlled transfer must finish", finished.await(10, TimeUnit.SECONDS))
        assertTrue(serverDone.await(10, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals(false, result.get())
        assertArrayEquals(bytes, file.readBytes())
        if (prefixSize > 0) {
          assertTrue(request.get().contains("Range: bytes=3-"))
          assertTrue(request.get().contains("If-Range: \"fixture\""))
        }
      } finally { handle.cancel() }
    } finally { socket.close() }
  }

  @Test
  fun refusedServiceDoesNotPauseARealWorkerWindowButWorkerExitDoes() = runBlocking {
    val part = runningFixture()
    val worker = launch(start = CoroutineStart.UNDISPATCHED) { DownloadServiceHost.runQueueWhileWorking(context, 60_000) }
    service(refused = true)
    assertFalse(suspended())
    assertEquals(-1L, part.downloadId)
    worker.cancelAndJoin()
    assertPaused(part)
  }

  @Test
  fun pausedQueueCanBeReconstructedFromSavedRecordsWithoutLosingOwnershipOrBytes() {
    val active = service(); awaitHostJobs()
    val part = runningFixture()
    val originalPrefix = File(part.destinationPath).inputStream().use { input -> ByteArray(37).also { input.read(it) } }
    active.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); awaitHostJobs()
    assertPaused(part)
    active.onDestroy()
    queue.destroy()
    // Forget process-local handles/flags and run the production host's cold restore entry point.
    paperBooks().clear()
    setHost("manager", null)
    setHost("restoreJob", null)
    setHost("service", null)
    setHost("pausedBySystem", false)
    setHost("networkWatched", false)
    val recovered = DownloadServiceHost.ensure(context)
    try {
      awaitHostJobs()
      val stored = recovered.downloadItemQueue.single { it.id == part.downloadItemId }
      val restoredPart = stored.downloadItemParts.single()
      assertNotSame(part, restoredPart)
      assertEquals("pause-A", stored.serverConnectionConfigId)
      assertEquals("alice", stored.serverUserId)
      assertNull(restoredPart.downloadId)
      assertFalse(restoredPart.failed)
      assertEquals(part.resumeValidator, restoredPart.resumeValidator)
      assertEquals(37L, restoredPart.bytesDownloaded)
      assertArrayEquals(originalPrefix, File(part.destinationPath).readBytes())
      assertTrue(recovered.currentDownloadItemParts.isEmpty())
      assertTrue(recovered.hasWork())
      println("RESTORE: newHostQueue=true owner=${stored.serverConnectionConfigId}/${stored.serverUserId} activeTransfers=0 retainedBytes=${restoredPart.bytesDownloaded} prefixVerified=true")
    } finally { recovered.destroy() }
  }
}
