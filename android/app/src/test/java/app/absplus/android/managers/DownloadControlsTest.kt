package app.absplus.android.managers

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalFolder
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.AppVolume
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderScanner
import app.absplus.android.device.VolumeProbe
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import com.getcapacitor.JSObject
import io.paperdb.Paper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user's download controls: Retry restarts unfinished files from byte 0 and keeps finished ones;
 * Cancel and Clear remove the download, deleting only app-owned partial data (and unreachable
 * app storage files), never a file a saved local item uses or one that existed before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadControlsTest {
  private lateinit var context: Context
  private lateinit var root: File
  private val events = mutableListOf<String>()
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null

  private val emitter =
          object : DownloadItemManager.DownloadEventEmitter {
            override fun onDownloadItem(downloadItem: DownloadItem) { events.add("item:${downloadItem.id}") }
            override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) { events.add("part:${downloadItemPart.filename}") }
            override fun onDownloadItemComplete(jsobj: JSObject) { events.add("complete") }
            override fun onQueueChanged(hasWork: Boolean) { events.add("queue:$hasWork") }
          }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    Paper.book("downloadItems").destroy()
    Paper.book("localLibraryItems").destroy()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(
            ServerConnectionConfig("server", 0, "Synthetic owner", "http://server", null,
                    "user", "synthetic", "synthetic-owner-token", null))
    DeviceManager.serverConnectionConfig = null
    try {
      WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
    } catch (e: IllegalStateException) {
      // Already initialized by an earlier test in this process
    }
    root = File(context.cacheDir, "downloads-test").apply { deleteRecursively(); mkdirs() }
    events.clear()
  }

  @After
  fun restoreServerFixtures() {
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
  }

  private fun part(
          itemId: String,
          name: String,
          internal: Boolean = true,
          moved: Boolean = false,
          failed: Boolean = false,
          isMoving: Boolean = false,
          bytes: Long = 0,
          sd: Boolean = false
  ) =
          DownloadItemPart(
                  id = "part-$itemId-$name",
                  downloadItemId = itemId,
                  filename = name,
                  fileSize = 10,
                  destinationPath = File(root, "staging/$itemId/$name.part").path,
                  finalDestinationPath = File(root, "final/$itemId/$name").path,
                  serverPath = "/api/items/$itemId/file/$name",
                  localFolderName = if (sd) "SD card" else if (internal) "Internal App Storage" else "ABSTEST",
                  localFolderUrl = if (sd || internal) "" else "content://com.android.externalstorage.documents/tree/primary%3AABSTEST",
                  localFolderId = if (sd) SD_FOLDER_ID else if (internal) "internal-book" else "saf-abstest",
                  ebookFile = null,
                  audioTrack = null,
                  episode = null,
                  completed = moved,
                  moved = moved,
                  isMoving = isMoving,
                  failed = failed,
                  uri = Uri.parse("http://server/file"),
                  destinationUri = Uri.parse("file:///staging/$name"),
                  finalDestinationUri = Uri.parse("file:///final/$name"),
                  completedDestinationUri = null,
                  finalDestinationSubfolder = "Author/Book",
                  downloadId = null,
                  lastUpdateTime = null,
                  progress = 0,
                  bytesDownloaded = bytes
          )

  private fun item(id: String, vararg parts: DownloadItemPart, internal: Boolean = true, sd: Boolean = false) =
          DownloadItem(
                  id = id,
                  libraryItemId = id,
                  episodeId = null,
                  userMediaProgress = null,
                  serverConnectionConfigId = "server",
                  serverAddress = "http://server",
                  serverUserId = "user",
                  mediaType = "book",
                  itemFolderPath = File(root, "final/$id").path,
                  localFolder =
                          if (sd) LocalFolder(SD_FOLDER_ID, "SD card", "", "", "", "appsd", "book")
                          else if (internal) LocalFolder("internal-book", "Internal App Storage", "", "", "", "internal", "book")
                          else LocalFolder("saf-abstest", "ABSTEST", "content://tree/ABSTEST", "", "", "external", "book"),
                  itemTitle = "Title $id",
                  itemSubfolder = "Author/Book",
                  media = MediaType(MediaTypeMetadata("Title $id", false), null),
                  downloadItemParts = parts.toMutableList()
          )

  private fun touch(path: String, size: Int = 10) = File(path).apply { parentFile?.mkdirs(); writeBytes(ByteArray(size)) }

  private fun manager() = DownloadItemManager(FolderScanner(context), context, emitter)

  // Auto-owned transfers run only while plus's AutoTransferGate allows them; these tests are about the SD card
  private fun allowAutoTransfers(manager: DownloadItemManager) {
    val real = AutoTransferGate.forContext(context)
    manager.autoGate = object : AutoTransferGate {
      override fun isAuto(item: DownloadItem) = real.isAuto(item)
      override fun allowedNow() = true
    }
  }

  // The registered card in the slot: finished SD files are deleted only on it (never on a missing or other card)
  private val verifiedSdProbe = object : VolumeProbe {
    override fun volumes() = listOf(AppVolume("79EC-9B33", File(root, "sd"), removable = true, mounted = true, writable = true))
    override fun internalFilesDir(): File = context.filesDir
  }

  private fun saveLocalItemUsing(path: String) =
          DeviceManager.dbManager.saveLocalLibraryItem(
                  LocalLibraryItem(
                          "local_sd", SD_FOLDER_ID, "", "", "", false, "book", MediaType(MediaTypeMetadata("Book", false), null),
                          mutableListOf(LocalFile("file_sd", File(path).name, "", "", path, "audio/mpeg", 10)),
                          null, null, true, null, null, null, "sd"
                  )
          )

  // --- Pure decisions ---

  @Test
  fun aFinishedOrFinishingDownloadCannotBeRemoved() {
    val done = item("done", part("done", "01.mp3", moved = true), part("done", "cover.jpg", moved = true))
    assertEquals(DownloadControls.Refusal.FINISHING, DownloadControls.removalRefusal(done, isFinalizing = false))
    val failed = item("failed", part("failed", "01.mp3", failed = true))
    assertEquals(DownloadControls.Refusal.FINISHING, DownloadControls.removalRefusal(failed, isFinalizing = true))
    assertNull(DownloadControls.removalRefusal(failed, isFinalizing = false))
  }

  @Test
  fun aFileBeingSavedToItsFolderBlocksRemovalUntilItLands() {
    val saving = item("saving", part("saving", "01.mp3", internal = false, isMoving = true), internal = false)
    assertEquals(DownloadControls.Refusal.MOVING, DownloadControls.removalRefusal(saving, isFinalizing = false))
  }

  @Test
  fun removalDeletesPartialDataAndKeepsFinishedFilesInDeviceFolders() {
    val audio = part("a", "01.mp3", internal = false, moved = true)
    val reused = part("a", "02.mp3", internal = false, moved = true).apply { reusedExistingFile = true }
    val cover = part("a", "cover.jpg", internal = false, failed = true)
    val plan = DownloadControls.removalPlan(item("a", audio, reused, cover, internal = false)) { false }
    assertEquals(listOf(audio.destinationPath, reused.destinationPath, cover.destinationPath), plan.delete)
    assertEquals(listOf("01.mp3", "02.mp3"), plan.keptFinishedFiles)
  }

  @Test
  fun removalDeletesUnreachableInternalFilesButNeverOnesALocalItemUses() {
    val loose = part("b", "01.mp3", moved = true)
    val used = part("b", "02.mp3", moved = true)
    val pending = part("b", "03.mp3")
    val plan = DownloadControls.removalPlan(item("b", loose, used, pending)) { it === used }
    assertTrue(loose.finalDestinationPath in plan.delete)
    assertFalse(used.finalDestinationPath in plan.delete)
    assertEquals(listOf("02.mp3"), plan.keptFinishedFiles)
  }

  @Test
  fun retryNeedsEveryTransferStoppedAndAnUnfinishedItem() {
    val a = part("r", "01.mp3", failed = true)
    val b = part("r", "cover.jpg", failed = true)
    val stopped = item("r", a, b)
    assertTrue(DownloadControls.canRetry(stopped) { false })
    // A second Retry while the first one's transfer runs does nothing
    assertFalse(DownloadControls.canRetry(stopped) { it === a })
    assertFalse(DownloadControls.canRetry(item("m", part("m", "01.mp3", isMoving = true))) { false })
    assertFalse(DownloadControls.canRetry(item("f", part("f", "01.mp3", moved = true))) { false })
  }

  @Test
  fun lostFolderAccessIsReportedForFilesStillToBeWritten() {
    val audio = part("p", "01.mp3", internal = false, failed = true).apply { permissionLost = true }
    val cover = part("p", "cover-p.jpg", internal = false, failed = true).apply { permissionLost = true }
    val lost = item("p", audio, cover, internal = false)
    assertEquals(listOf("ABSTEST"), DownloadControls.foldersWithoutAccess(lost) { false })
    // Access restored by re-selecting the folder
    assertTrue(DownloadControls.foldersWithoutAccess(lost) { true }.isEmpty())
    // Files already written and internal storage need no folder access
    val written = item("w", part("w", "01.mp3", internal = false, moved = true), part("w", "02.mp3"), internal = false)
    assertTrue(DownloadControls.foldersWithoutAccess(written) { false }.isEmpty())
  }

  @Test
  fun appsdPartsAreAppOwned() {
    val loose = part("s", "01.mp3", moved = true, sd = true)
    val used = part("s", "02.mp3", moved = true, sd = true)
    val pending = part("s", "03.mp3", sd = true, bytes = 4)
    val plan = DownloadControls.removalPlan(item("s", loose, used, pending, sd = true)) { it === used }
    assertEquals(listOf(loose.destinationPath, used.destinationPath, pending.destinationPath), plan.delete.filter { it.endsWith(".part") })
    assertTrue(loose.finalDestinationPath in plan.delete)
    assertFalse(used.finalDestinationPath in plan.delete)
    assertEquals(listOf("02.mp3"), plan.keptFinishedFiles)

    // Queued first, then its files appear (plus's R29-N02 admission renames a part whose destination already holds
    // a file nobody owns, so files placed before queueing would not be this download's)
    val manager = DownloadItemManager(FolderScanner(context), context, emitter, verifiedSdProbe)
    manager.addDownloadItem(item("s", loose, used, pending, sd = true))
    val looseFile = touch(loose.finalDestinationPath)
    val usedFile = touch(used.finalDestinationPath)
    val staging = touch(pending.destinationPath, 4)
    saveLocalItemUsing(used.finalDestinationPath)
    assertEquals(DownloadItemManager.RemoveResult.REMOVED, manager.removeDownloadItem("s", "Clear"))
    assertFalse(looseFile.exists())
    assertFalse(staging.exists())
    assertTrue(usedFile.exists())

    // An SD card can't be re-selected like a device folder
    val unavailable = item("u", part("u", "01.mp3", sd = true, failed = true), sd = true)
    assertTrue(DownloadControls.foldersWithoutAccess(unavailable) { false }.isEmpty())
  }

  // --- DownloadItemManager applying them ---

  @Test
  fun cancelWhileQueuedRemovesTheItemAndItsPartialFiles() {
    val queued = part("q", "01.mp3", bytes = 4)
    touch(queued.destinationPath, 4)
    val manager = manager()
    manager.addDownloadItem(item("q", queued))
    assertEquals(DownloadItemManager.RemoveResult.REMOVED, manager.removeDownloadItem("q", "Cancel"))
    assertTrue(manager.downloadItemQueue.isEmpty())
    assertTrue(DeviceManager.dbManager.getDownloadItems().isEmpty())
    assertFalse(File(queued.destinationPath).exists())
    assertEquals("queue:false", events.last())
    // Cancelling again finds nothing: no duplicate work, no error
    assertEquals(DownloadItemManager.RemoveResult.NOT_FOUND, manager.removeDownloadItem("q", "Cancel"))
  }

  @Test
  fun clearFailedRemovesTheEntryAndKeepsTheFinishedAudioInTheDeviceFolder() {
    val audio = part("c", "01.mp3", internal = false, moved = true)
    val cover = part("c", "cover-c.jpg", internal = false, failed = true).apply { permissionLost = true }
    val finishedAudio = touch(audio.finalDestinationPath)
    touch(cover.destinationPath, 3)
    val manager = manager()
    manager.addDownloadItem(item("c", audio, cover, internal = false))
    assertEquals(DownloadItemManager.RemoveResult.REMOVED, manager.removeDownloadItem("c", "Clear"))
    assertTrue(finishedAudio.exists())
    assertFalse(File(cover.destinationPath).exists())
    assertTrue(manager.downloadItemQueue.isEmpty())
  }

  @Test
  fun clearCannotTouchACompletedDownload() {
    val audio = part("done", "01.mp3", moved = true)
    val cover = part("done", "cover-done.jpg", moved = true)
    val files = listOf(touch(audio.finalDestinationPath), touch(cover.finalDestinationPath))
    val manager = manager()
    manager.downloadItemQueue.add(item("done", audio, cover))
    assertEquals(DownloadItemManager.RemoveResult.FINISHING, manager.removeDownloadItem("done", "Clear"))
    assertTrue(files.all { it.exists() })
    assertEquals(1, manager.downloadItemQueue.size)
  }

  @Test
  fun retryRestartsUnfinishedFilesFromZeroAndKeepsFinishedOnes() {
    val finished = part("r", "01.mp3", moved = true)
    touch(finished.finalDestinationPath)
    val failedAudio = part("r", "02.mp3", failed = true, bytes = 7).apply { failureReason = "Transfer failed after 5 retries" }
    val failedCover = part("r", "cover-r.jpg", failed = true).apply { failureReason = "Transfer failed after 5 retries" }
    touch(failedAudio.destinationPath, 7)
    val failed = item("r", finished, failedAudio, failedCover).apply { terminalFailureAt = 1L }
    val manager = manager()
    manager.downloadItemQueue.add(failed)

    assertTrue(manager.retryDownloadItem("r"))
    // Audio and cover failures are reset alike: from byte 0, no stale reason, eligible to download again
    listOf(failedAudio, failedCover).forEach {
      assertFalse(it.failed)
      assertEquals(0L, it.bytesDownloaded)
      assertNull(it.failureReason)
      assertNull(it.downloadId)
    }
    assertFalse(File(failedAudio.destinationPath).exists())
    assertTrue(finished.moved)
    assertTrue(File(finished.finalDestinationPath).exists())
    assertNull(failed.terminalFailureAt)
    assertEquals(listOf(failedAudio, failedCover), failed.getNextDownloadItemParts(3))
  }

  @Test
  fun restoringAfterAProcessRestartRestartsUnfinishedFilesAndKeepsFailuresVisible() {
    val partial = part("i", "01.mp3", bytes = 6)
    touch(partial.destinationPath, 6)
    val finished = part("i", "02.mp3", moved = true)
    touch(finished.finalDestinationPath)
    DeviceManager.dbManager.saveDownloadItem(item("i", partial, finished))
    val lostCover = part("l", "cover-l.jpg", internal = false, failed = true).apply { permissionLost = true }
    DeviceManager.dbManager.saveDownloadItem(item("l", lostCover, internal = false).apply { terminalFailureAt = 1L })

    val manager = manager()
    manager.restoreQueue()
    val restored = manager.downloadItemQueue.associateBy { it.id }
    val restartedPart = restored.getValue("i").downloadItemParts.first { it.filename == "01.mp3" }
    assertEquals(0L, restartedPart.bytesDownloaded)
    assertFalse(File(partial.destinationPath).exists())
    assertTrue(restored.getValue("i").downloadItemParts.first { it.filename == "02.mp3" }.moved)
    // The folder-access failure stays failed (not silently retried) and is still in the queue to act on
    val lost = restored.getValue("l").downloadItemParts.single()
    assertTrue(lost.failed && lost.permissionLost)
    assertTrue(events.contains("item:l"))
  }

  @Test
  fun storageUnavailableLifecycle() {
    Paper.book("autoDownload").destroy()
    val probe =
            object : VolumeProbe {
              var mounted = false
              override fun volumes() = listOf(AppVolume("79EC-9B33", File(root, "sd"), removable = true, mounted = mounted, writable = mounted))
              override fun internalFilesDir(): File = context.filesDir
            }
    val finished = part("sdi", "01.mp3", moved = true, sd = true)
    val pending = part("sdi", "02.mp3", sd = true)
    val sdItem = item("sdi", finished, pending, sd = true).copy(episodeId = "ep1")
    AutoDownloadStore.markAutoQueued("server", "sdi", "ep1")

    val manager = DownloadItemManager(FolderScanner(context), context, emitter, probe)
    allowAutoTransfers(manager)
    manager.addDownloadItem(sdItem)
    manager.resumeWork()
    assertTrue("a missing card fails the download as SD card not available", pending.failed && pending.storageUnavailable)
    assertFalse("a missing card is not lost folder access", pending.permissionLost)
    assertEquals(LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE, pending.failureReason)
    assertTrue("the episode is still counted as auto downloaded", "ep1" in AutoDownloadStore.get("server", "sdi").autoDownloadedEpisodeIds)
    assertEquals(DownloadItemManager.QueuedState.STORAGE_UNAVAILABLE, manager.queuedState("sdi"))

    val restarted = DownloadItemManager(FolderScanner(context), context, emitter, probe)
    allowAutoTransfers(restarted)
    restarted.restoreQueue()
    val restored = restarted.downloadItemQueue.single { it.id == "sdi" }
    val restoredPending = restored.downloadItemParts.single { it.filename == "02.mp3" }
    val restoredFinished = restored.downloadItemParts.single { it.filename == "01.mp3" }
    assertTrue("the flag survives a restart", restoredPending.failed && restoredPending.storageUnavailable)
    assertEquals(LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE, restoredPending.failureReason)
    assertTrue("moved part stays moved while its card is missing", restoredFinished.moved)

    assertTrue(restarted.isStorageUnavailable("sdi"))
    assertFalse("Retry while the card is missing is refused", restarted.retryDownloadItem("sdi"))
    assertTrue(restoredPending.storageUnavailable)

    probe.mounted = true
    assertFalse(restarted.isStorageUnavailable("sdi"))
    assertTrue("Retry once the card is back", restarted.retryDownloadItem("sdi"))
    assertFalse(restoredPending.storageUnavailable)
    assertNull(restoredPending.failureReason)
    assertTrue(restoredFinished.moved)
  }

  @Test
  fun sdCardRemovedMidDownloadIsUnavailableAfterRestart() {
    Paper.book("autoDownload").destroy()
    val probe =
            object : VolumeProbe {
              var mounted = false
              override fun volumes() = listOf(AppVolume("79EC-9B33", File(root, "sd"), removable = true, mounted = mounted, writable = mounted))
              override fun internalFilesDir(): File = context.filesDir
            }
    val finished = part("mid", "01.mp3", moved = true, sd = true)
    val inFlight = part("mid", "02.mp3", sd = true, bytes = 5)
    DeviceManager.dbManager.saveDownloadItem(item("mid", finished, inFlight, sd = true).copy(episodeId = "ep1"))
    AutoDownloadStore.markAutoQueued("server", "mid", "ep1")
    // A non-empty directory can't be deleted, like staging on a removed card
    val staging = File(inFlight.destinationPath).apply { mkdirs() }
    File(staging, "blocker").writeBytes(ByteArray(1))

    val manager = DownloadItemManager(FolderScanner(context), context, emitter, probe)
    manager.restoreQueue()
    val restored = manager.downloadItemQueue.single { it.id == "mid" }
    val restoredInFlight = restored.downloadItemParts.single { it.filename == "02.mp3" }
    assertTrue("an unfinished part on a missing card is SD card not available", restoredInFlight.failed && restoredInFlight.storageUnavailable)
    assertEquals(LibraryItemDownloadStarter.STORAGE_UNAVAILABLE_MESSAGE, restoredInFlight.failureReason)
    assertFalse(restoredInFlight.permissionLost)
    assertTrue("the episode is still counted as auto downloaded", "ep1" in AutoDownloadStore.get("server", "mid").autoDownloadedEpisodeIds)
    assertTrue("staging on the missing card is left alone", staging.exists())
    assertTrue(restored.downloadItemParts.single { it.filename == "01.mp3" }.moved)
    assertEquals(DownloadItemManager.QueuedState.STORAGE_UNAVAILABLE, manager.queuedState("mid"))
    assertTrue(
            "the state is persisted",
            DeviceManager.dbManager.getDownloadItems().single { it.id == "mid" }.downloadItemParts.single { it.filename == "02.mp3" }.storageUnavailable
    )

    probe.mounted = true
    staging.deleteRecursively()
    assertTrue("Retry once the card is back", manager.retryDownloadItem("mid"))
    assertFalse(restoredInFlight.storageUnavailable)
    assertFalse(restoredInFlight.failed)
  }

  @Test
  fun finishedDownloadWaitsForItsSdCard() {
    val probe =
            object : VolumeProbe {
              override fun volumes() = listOf(AppVolume("79EC-9B33", File(root, "sd"), removable = true, mounted = false, writable = false))
              override fun internalFilesDir(): File = context.filesDir
            }
    DeviceManager.dbManager.saveDownloadItem(item("fin", part("fin", "01.mp3", moved = true, sd = true), sd = true))
    val manager = DownloadItemManager(FolderScanner(context), context, emitter, probe)
    manager.restoreQueue()
    manager.resumeWork()
    // Finalization runs on a background coroutine; give a wrongly started one time to drop the item
    val deadline = System.currentTimeMillis() + 2_000
    while (System.currentTimeMillis() < deadline && "complete" !in events) Thread.sleep(50)
    assertTrue("not finalized (and dropped) while the card is missing",manager.downloadItemQueue.any { it.id == "fin" })
    assertTrue(DeviceManager.dbManager.getDownloadItems().any { it.id == "fin" })
    assertTrue(manager.downloadItemQueue.single().downloadItemParts.single().moved)
  }

  private fun awaitFinalized(manager: DownloadItemManager) {
    // A finalization counts as work until its background scan is over
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline && manager.hasWork()) Thread.sleep(20)
    assertFalse("finalization did not end", manager.hasWork())
  }

  @Test
  fun returningSdCardFinalizesAWaitingDownload() {
    val probe =
            object : VolumeProbe {
              @Volatile var mounted = false
              override fun volumes() = listOf(AppVolume("79EC-9B33", File(root, "sd"), removable = true, mounted = mounted, writable = mounted))
              override fun internalFilesDir(): File = context.filesDir
            }
    val finished = part("fin", "01.mp3", moved = true, sd = true)
    DeviceManager.dbManager.saveDownloadItem(item("fin", finished, sd = true))
    val manager = DownloadItemManager(FolderScanner(context), context, emitter, probe)
    manager.restoreQueue()

    // The card comes back with the finished file on it (a scan from removable storage is all or nothing)
    touch(finished.finalDestinationPath)
    probe.mounted = true
    manager.resumeAwaitingStorage()
    awaitFinalized(manager)

    assertTrue("finalized without any other download running", "complete" in events)
    assertTrue(manager.downloadItemQueue.none { it.id == "fin" })
    assertFalse(DeviceManager.dbManager.getDownloadItems().any { it.id == "fin" })
  }

  @Test
  fun sdCardRemovedBeforeTheScanKeepsTheDownload() {
    val probe =
            object : VolumeProbe {
              val calls = java.util.concurrent.atomic.AtomicInteger()
              @Volatile var mountedAgain = false
              // Mounted for the restore's availability check only, then gone until the card comes back
              override fun volumes(): List<AppVolume> {
                val mounted = calls.getAndIncrement() == 0 || mountedAgain
                return listOf(AppVolume("79EC-9B33", File(root, "sd"), removable = true, mounted = mounted, writable = mounted))
              }
              override fun internalFilesDir(): File = context.filesDir
            }
    val finished = part("gone", "01.mp3", moved = true, sd = true)
    touch(finished.finalDestinationPath)
    DeviceManager.dbManager.saveDownloadItem(item("gone", finished, sd = true))
    val manager = DownloadItemManager(FolderScanner(context), context, emitter, probe)
    manager.restoreQueue()
    awaitFinalized(manager)

    assertFalse("not reported as complete", "complete" in events)
    assertTrue("kept in the queue", manager.downloadItemQueue.any { it.id == "gone" })
    assertTrue(DeviceManager.dbManager.getDownloadItems().any { it.id == "gone" })

    probe.mountedAgain = true
    manager.resumeAwaitingStorage()
    awaitFinalized(manager)
    assertTrue("finalized once the card is back", "complete" in events)
    assertTrue(manager.downloadItemQueue.none { it.id == "gone" })
  }

  @Test
  fun missingOwnerOnRestoreKeepsPartialBytesAndDoesNotAdoptActiveServer() {
    val partial = part("held", "01.mp3", bytes = 6)
    val bytes = touch(partial.destinationPath, 6).readBytes().toList()
    DeviceManager.dbManager.saveDownloadItem(item("held", partial).apply { terminalFailureAt = 1L })
    val other = DeviceManager.deviceData.serverConnectionConfigs.single().copy(
            id = "other", address = "http://other.invalid", token = "synthetic-other-token")
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(other)
    DeviceManager.serverConnectionConfig = other

    val manager = manager()
    manager.restoreQueue()
    val held = manager.downloadItemQueue.single()
    assertEquals("server", held.serverConnectionConfigId)
    assertEquals(6L, held.downloadItemParts.single().bytesDownloaded)
    assertTrue(held.downloadItemParts.single().failed)
    assertNull(held.terminalFailureAt)
    assertEquals(bytes, File(partial.destinationPath).readBytes().toList())
    assertEquals(6L, DeviceManager.dbManager.getDownloadItems().single().downloadItemParts.single().bytesDownloaded)
  }

  @Test
  fun changedOwnerCannotRetryOrDiscardPartialData() {
    val partial = part("held", "01.mp3", failed = true, bytes = 7)
    touch(partial.destinationPath, 7)
    val held = item("held", partial).apply { terminalFailureAt = 1L }
    DeviceManager.deviceData.serverConnectionConfigs.single().userId = "different-user"
    val manager = manager()
    manager.downloadItemQueue.add(held)

    assertFalse(manager.retryDownloadItem("held"))
    assertTrue(partial.failed)
    assertEquals(7L, partial.bytesDownloaded)
    assertEquals(7L, File(partial.destinationPath).length())
    assertNull(held.terminalFailureAt)
    assertEquals(1, DeviceManager.dbManager.getDownloadItems().size)
  }

  @Test
  fun missingFinalizedFileDoesNotDiscardRetainedCopyWhenOwnerIsGone() {
    val part = part("missing-final", "01.mp3", moved = true, bytes = 7)
    touch(part.destinationPath, 7)
    assertFalse(File(part.finalDestinationPath).exists())
    DeviceManager.dbManager.saveDownloadItem(item("missing-final", part))
    DeviceManager.deviceData.serverConnectionConfigs.clear()

    val manager = manager()
    manager.restoreQueue()
    val held = manager.downloadItemQueue.single().downloadItemParts.single()
    assertTrue(held.failed)
    assertEquals(7L, held.bytesDownloaded)
    assertEquals(7L, File(part.destinationPath).length())
  }

  @Test
  fun expiredCleanupBeforeRestorePreservesMissingOwnersPartialData() {
    val partial = part("expired-held", "01.mp3", failed = true, bytes = 7).copy(
            destinationPath = File(context.filesDir, "authorization-expiry-fixture/01.mp3.part").path)
    val owner = DeviceManager.deviceData.serverConnectionConfigs.single().copy()
    touch(partial.destinationPath, 7)
    DeviceManager.dbManager.saveDownloadItem(item("expired-held", partial).apply { terminalFailureAt = 1L })
    DeviceManager.deviceData.serverConnectionConfigs.clear()

    IncompleteDownloadCleanup.cleanupExpired(context)
    val kept = DeviceManager.dbManager.getDownloadItems().single()
    assertNull(kept.stagingCleanupAt)
    assertEquals(7L, kept.downloadItemParts.single().bytesDownloaded)
    assertEquals(7L, File(partial.destinationPath).length())

    // The ordinary retention policy still works for a verified owner; the guard is not a blanket bypass.
    DeviceManager.deviceData.serverConnectionConfigs.add(owner)
    IncompleteDownloadCleanup.cleanupExpired(context)
    assertFalse(File(partial.destinationPath).exists())
    assertEquals(0L, DeviceManager.dbManager.getDownloadItems().single().downloadItemParts.single().bytesDownloaded)
  }

  @Test
  fun missingOwnerOnAMissingSdCardIsHeldNotWaitingForTheCard() {
    val probe =
            object : VolumeProbe {
              var mounted = false
              override fun volumes() = listOf(AppVolume("79EC-9B33", File(root, "sd"), removable = true, mounted = mounted, writable = mounted))
              override fun internalFilesDir(): File = context.filesDir
            }
    val pending = part("held-sd", "01.mp3", sd = true, bytes = 5)
    DeviceManager.dbManager.saveDownloadItem(item("held-sd", pending, sd = true))
    DeviceManager.deviceData.serverConnectionConfigs.clear()

    val manager = DownloadItemManager(FolderScanner(context), context, emitter, probe)
    manager.restoreQueue()
    val held = manager.downloadItemQueue.single()
    val heldPart = held.downloadItemParts.single()
    assertTrue(heldPart.failed)
    assertFalse("the ownership hold wins over SD card not available", heldPart.storageUnavailable)
    assertNull("a hold is not a terminal failure", held.terminalFailureAt)

    probe.mounted = true
    assertFalse("the returned card does not bypass the ownership check", manager.retryDownloadItem("held-sd"))
    assertTrue(heldPart.failed)
  }

  private companion object {
    const val SD_FOLDER_ID = "appsd-79EC-9B33-book"
  }
}
