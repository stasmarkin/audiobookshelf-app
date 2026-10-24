package app.absplus.android.managers

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalFolder
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastAutoDownloadEntry
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.NodeKind
import app.absplus.android.device.StorageIo
import app.absplus.android.device.AndroidStorageIo
import app.absplus.android.device.AppStorage
import app.absplus.android.device.AppVolume
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderScanner
import app.absplus.android.device.StorageTarget
import app.absplus.android.device.VolumeIdentity
import app.absplus.android.device.VolumeProbe
import app.absplus.android.device.VolumeRegistry
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.paperdb.Paper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Removable storage must never make the app forget downloads, episodes, progress, ownership or queued work (PR #29
 * H02), deletes nothing on a card that is out or is another card (L01), and keeps PR #23 retention working on the SD
 * card within the same guards. Synthetic files, records and accounts only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemovableStorageSafetyTest {
  private lateinit var context: Context
  private lateinit var root: File
  private lateinit var sdFiles: File
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private val mapper = jacksonObjectMapper()
  private val uuid = "79EC-9B33"
  private val server = "srvA"
  private val pod = "li_pod"
  private val tokens = mutableMapOf<String, String>()
  private val journal = mutableMapOf<String, StorageMoveJournalEntry>()

  private inner class Probe(var mounted: Boolean = true, var identity: VolumeIdentity = VolumeIdentity.VERIFIED) : VolumeProbe {
    override fun volumes() = listOf(AppVolume(uuid, sdFiles, removable = true, mounted = mounted, writable = mounted, identity = identity))
    override fun internalFilesDir(): File = context.filesDir
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    listOf("downloadItems", "localLibraryItems", "localMediaProgress", "autoDownload").forEach { Paper.book(it).destroy() }
    AutoDownloadStore.clearCacheForTests()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    val a = ServerConnectionConfig(server, 0, "Synthetic A", "http://127.0.0.1:1", null, "alice", "synthetic", "synthetic-A", null)
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(a)
    DeviceManager.serverConnectionConfig = a
    try {
      WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build())
    } catch (e: IllegalStateException) {
      // initialized by an earlier test in this process
    }
    root = File(context.cacheDir, "removable-test").apply { deleteRecursively(); mkdirs() }
    sdFiles = File(root, "sdcard/Android/data/app/files").apply { mkdirs() }
    VolumeRegistry.tokens = object : VolumeRegistry.Tokens {
      override fun get(uuid: String) = tokens[uuid]
      override fun put(uuid: String, token: String) {
        tokens[uuid] = token
      }
    }
    AutoDownloadRetention.journal = object : MoveJournal {
      override fun all() = journal.values.toList()
      override fun get(itemId: String) = journal[itemId]
      override fun put(entry: StorageMoveJournalEntry) {
        journal[entry.itemId] = entry
      }
      override fun remove(itemId: String) {
        journal.remove(itemId)
      }
    }
  }

  @After
  fun tearDown() {
    VolumeRegistry.tokens = VolumeRegistry.PaperTokens
    AutoDownloadRetention.journal = PaperMoveJournal
    AutoDownloadRetention.volumes = { app.absplus.android.device.AndroidVolumeProbe(it) }
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    AutoDownloadStore.clearCacheForTests()
  }

  private val sdFolderId get() = AppStorage.appsdFolderId(uuid, "podcast")
  private val sdDownloads get() = File(sdFiles, "downloads")

  private fun media(dir: File, name: String) = File(dir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(16) { 1 }) }

  private fun podcastLocal(folderId: String, dir: File, vararg eps: Pair<String, File>): LocalLibraryItem {
    val episodes = eps.mapIndexed { i, (serverEp, file) ->
      val track = AudioTrack(i + 1, 0.0, 20.0, file.name, Uri.fromFile(file).toString(), "audio/mpeg", null, true, "lf_$serverEp", null)
      PodcastEpisode("local_ep_$serverEp", i + 1, null, null, serverEp, null, null, null, i.toLong(), null, track, null, 20.0, 16L, serverEp, "local_ep_$serverEp")
    }.toMutableList()
    val files = eps.map { (serverEp, file) -> LocalFile("lf_$serverEp", file.name, Uri.fromFile(file).toString(), file.parent, file.path, "audio/mpeg", 16L) }.toMutableList()
    val podcast = Podcast(PodcastMetadata("Synthetic", null, null, mutableListOf(), false), null, mutableListOf(), episodes, false, episodes.size)
    return LocalLibraryItem("local_$pod", folderId, dir.path, dir.path, "", false, "podcast", podcast, files, null, null, true, server, "http://127.0.0.1:1", "alice", pod)
  }

  private fun progressFor(serverEp: String) = LocalMediaProgress("local_$pod-local_ep_$serverEp", "local_$pod", "local_ep_$serverEp", 20.0, 0.25, 5.0,
          false, null, null, 1L, 1L, null, server, null, "alice", pod, serverEp)

  // ---- Volume identity

  @Test
  fun aCardIsBoundToTheAppAndAnotherCardUnderItsUuidIsNeverTheSame() {
    assertEquals(VolumeIdentity.UNREGISTERED, VolumeRegistry.identity(uuid, sdFiles))
    val volume = AppVolume(uuid, sdFiles, removable = true, mounted = true, writable = true)
    assertTrue(VolumeRegistry.ensureRegistered(volume))
    assertTrue("a marker is written on the card", File(sdFiles, VolumeRegistry.MARKER_NAME).isFile)
    assertEquals(VolumeIdentity.VERIFIED, VolumeRegistry.identity(uuid, sdFiles))
    assertTrue("registering again keeps the binding", VolumeRegistry.ensureRegistered(volume))

    File(sdFiles, VolumeRegistry.MARKER_NAME).writeText("another card's marker")
    assertEquals(VolumeIdentity.MISMATCH, VolumeRegistry.identity(uuid, sdFiles))
    assertFalse("a mismatching card is never re-bound", VolumeRegistry.ensureRegistered(volume))
    File(sdFiles, VolumeRegistry.MARKER_NAME).delete()
    assertEquals("a wiped card is not the registered one", VolumeIdentity.MISMATCH, VolumeRegistry.identity(uuid, sdFiles))
    assertEquals(VolumeIdentity.MISMATCH, VolumeRegistry.identity(null, sdFiles))

    assertTrue(AppStorage.isVerified(sdFolderId, Probe()))
    assertFalse(AppStorage.isAvailable(sdFolderId, Probe(identity = VolumeIdentity.MISMATCH)))
    assertFalse(AppStorage.isVerified(sdFolderId, Probe(identity = VolumeIdentity.MISMATCH)))
    assertTrue("a new card can take its first download", AppStorage.isAvailable(sdFolderId, Probe(identity = VolumeIdentity.UNREGISTERED)))
    assertFalse("but nothing is reconciled or deleted on it", AppStorage.isVerified(sdFolderId, Probe(identity = VolumeIdentity.UNREGISTERED)))
    assertFalse(AppStorage.isAvailable(sdFolderId, Probe(mounted = false)))
    assertTrue("internal storage is always verified", AppStorage.isVerified("internal-podcast", Probe(mounted = false)))
  }

  // ---- Startup cleanup (H02)

  @Test
  fun startupCleanupNeverPrunesAnSdItemAndItsProgressSurvives() {
    // Files that look missing: the card is out, leaving, or another card
    val dir = File(sdDownloads, pod)
    val sdItem = podcastLocal(sdFolderId, dir, "e1" to File(dir, "e1.mp3"), "e2" to File(dir, "e2.mp3"))
    DeviceManager.dbManager.saveLocalLibraryItem(sdItem)
    DeviceManager.dbManager.saveLocalMediaProgress(progressFor("e1"))
    val before = mapper.writeValueAsString(DeviceManager.dbManager.getLocalLibraryItem(sdItem.id))
    // Missing internal files also retain episode identity and history under the startup preservation policy.
    val internalDir = File(context.filesDir, "downloads/li_int")
    val present = media(internalDir, "present.mp3")
    val internal = podcastLocal("internal-podcast", internalDir, "p" to present, "gone" to File(internalDir, "gone.mp3"))
            .apply { id = "local_li_int"; libraryItemId = "li_int" }
    DeviceManager.dbManager.saveLocalLibraryItem(internal)

    repeat(2) {
      DeviceManager.dbManager.cleanLocalLibraryItems(context)
      DeviceManager.dbManager.cleanLocalMediaProgress()
    }

    assertEquals("the SD item, its episodes and file records are unchanged", before, mapper.writeValueAsString(DeviceManager.dbManager.getLocalLibraryItem(sdItem.id)))
    assertNotNull("its progress is kept", DeviceManager.dbManager.getLocalMediaProgress("local_$pod-local_ep_e1"))
    assertEquals(listOf("p", "gone"), (DeviceManager.dbManager.getLocalLibraryItem("local_li_int")!!.media as Podcast).episodes!!.map { it.serverEpisodeId })
  }

  // ---- Finalizing downloads on removable storage (H02)

  private fun sdPart(
          itemId: String,
          name: String,
          folderId: String = AppStorage.appsdFolderId(uuid, "book"),
          staging: File = File(sdFiles, "download-staging/$itemId/$name.part"),
          finished: Boolean = true
  ) =
          DownloadItemPart(
                  id = "part-$itemId-$name", downloadItemId = itemId, filename = name, fileSize = 16,
                  destinationPath = staging.path,
                  finalDestinationPath = File(sdDownloads, "$itemId/$name").path,
                  serverPath = "/api/items/$itemId/file/$name", localFolderName = "SD card", localFolderUrl = "",
                  localFolderId = folderId, ebookFile = null,
                  audioTrack = AudioTrack(name.take(2).toIntOrNull() ?: 1, 0.0, 20.0, name, "", "audio/mpeg", null, false, null, null), episode = null,
                  completed = finished, moved = finished, isMoving = false, failed = !finished, uri = Uri.parse("http://server/file"),
                  destinationUri = Uri.parse("file:///staging/$name"), finalDestinationUri = Uri.parse("file:///final/$name"),
                  completedDestinationUri = null, finalDestinationSubfolder = "Author/Book", downloadId = null, lastUpdateTime = null,
                  progress = if (finished) 100 else 30, bytesDownloaded = if (finished) 16 else 5)

  private fun sdItem(id: String, vararg parts: DownloadItemPart) =
          DownloadItem(id = id, libraryItemId = id, episodeId = null, userMediaProgress = null, serverConnectionConfigId = server,
                  serverAddress = "http://127.0.0.1:1", serverUserId = "alice", mediaType = "book", itemFolderPath = File(sdDownloads, id).path,
                  localFolder = LocalFolder(AppStorage.appsdFolderId(uuid, "book"), "SD card", "", "", "", "appsd", "book"),
                  itemTitle = "Title $id", itemSubfolder = "Author/Book", media = MediaType(MediaTypeMetadata("Title $id", false), null),
                  downloadItemParts = parts.toMutableList())

  private fun scan(item: DownloadItem, onIncomplete: () -> Unit = {}): FolderScanner.DownloadItemScanResult? {
    val latch = CountDownLatch(1)
    var result: FolderScanner.DownloadItemScanResult? = null
    FolderScanner(context).scanDownloadItem(item, onIncomplete) {
      result = it
      latch.countDown()
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS))
    return result
  }

  @Test
  fun aScanFromTheSdCardSavesAllOfAnItemOrNothing() {
    val one = sdPart("li_book", "01.mp3")
    val two = sdPart("li_book", "02.mp3")
    media(File(one.finalDestinationPath).parentFile!!, "01.mp3")
    // 02.mp3 can't be read (the card is leaving): no partial item is saved, and the scan says so
    var incomplete = false
    assertNull(scan(sdItem("li_book", one, two)) { incomplete = true }?.localLibraryItem)
    assertTrue(incomplete)
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_li_book"))

    media(File(two.finalDestinationPath).parentFile!!, "02.mp3")
    val saved = scan(sdItem("li_book", one, two))?.localLibraryItem
    assertNotNull(saved)
    assertEquals(2, DeviceManager.dbManager.getLocalLibraryItem("local_li_book")!!.localFiles.size)
  }

  @Test
  fun completedRedownloadReactivatesTheRetainedItemButAnUnfinishedAttemptDoesNot() {
    val part = sdPart("li_reactivated", "01.mp3")
    media(File(part.finalDestinationPath).parentFile!!, "01.mp3")
    val download = sdItem("li_reactivated", part)
    val saved = scan(download)!!.localLibraryItem
    saved.isInvalid = true
    DeviceManager.dbManager.saveLocalLibraryItem(saved)
    val history = LocalMediaProgress(saved.id, saved.id, null, 20.0, 0.1, 2.0, false, null, null,
            9000, 1000, null, server, "http://127.0.0.1:1", "alice", download.libraryItemId, null)
    DeviceManager.dbManager.saveLocalMediaProgress(history)
    val stale = app.absplus.android.data.MediaProgress("stale", download.libraryItemId, null, 20.0, 1.0, 20.0, true, null, null, 500, 0, 500)
    val retry = download.copy(userMediaProgress = stale)
    part.completed = false
    assertTrue(scan(retry)!!.localLibraryItem.isInvalid)
    part.completed = true
    val restored = scan(retry)!!.localLibraryItem
    assertEquals(saved.id, restored.id)
    assertEquals(saved.serverConnectionConfigId, restored.serverConnectionConfigId)
    assertFalse(restored.isInvalid)
    assertEquals(mapper.writeValueAsString(history), mapper.writeValueAsString(DeviceManager.dbManager.getLocalMediaProgress(saved.id)))
  }

  @Test
  fun aFinishedSdDownloadThatSavedNothingStaysQueued() {
    assertTrue("card away", DownloadFinalizationPolicy.waitInsteadOfDropping(false, true, false))
    assertTrue("card present but the scan refused a partial item", DownloadFinalizationPolicy.waitInsteadOfDropping(false, false, true))
    assertFalse("nothing playable is not a partial item", DownloadFinalizationPolicy.waitInsteadOfDropping(false, false, false))
    assertFalse("a saved item completes", DownloadFinalizationPolicy.waitInsteadOfDropping(true, false, false))
  }

  // ---- Expired staging (H02)

  private fun failedItem(id: String, folder: LocalFolder, staging: File): DownloadItem {
    val part = sdPart(id, "01.mp3", folderId = folder.id, staging = staging, finished = false)
    return sdItem(id, part).copy(localFolder = folder).apply { terminalFailureAt = System.currentTimeMillis() - 48L * 3600 * 1000 }
  }

  @Test
  fun expiredStagingOnACardThatIsNotThereKeepsItsPendingState() {
    // A card the device can't see (Robolectric has no removable volume): not inspected, nothing recorded. The file
    // sits under an app-owned root, so only the availability check keeps it from being deleted
    val sdStaging = media(File(context.filesDir, "sd-sim/download-staging/li_sd"), "01.mp3.part")
    DeviceManager.dbManager.saveDownloadItem(failedItem("li_sd", LocalFolder(sdFolderId, "SD card", "", "", "", "appsd", "podcast"), sdStaging))
    // Control: an internal one is cleaned as before
    val inStaging = media(File(context.filesDir, "download-staging/li_in"), "01.mp3.part")
    DeviceManager.dbManager.saveDownloadItem(failedItem("li_in", LocalFolder("internal-podcast", "Internal App Storage", "", "", "", "internal", "podcast"), inStaging))
    // An internal one whose staging can't be deleted (a non-empty directory in its place) stays pending too
    val blocked = File(context.filesDir, "download-staging/li_blk/01.mp3.part").apply { mkdirs() }
    File(blocked, "x").writeBytes(ByteArray(1))
    DeviceManager.dbManager.saveDownloadItem(failedItem("li_blk", LocalFolder("internal-podcast", "Internal App Storage", "", "", "", "internal", "podcast"), blocked))

    IncompleteDownloadCleanup.cleanupExpired(context)

    val sd = DeviceManager.dbManager.getDownloadItems().single { it.id == "li_sd" }
    assertNull("not marked cleaned", sd.stagingCleanupAt)
    assertEquals(5L, sd.downloadItemParts.single().bytesDownloaded)
    assertTrue(sdStaging.isFile)
    val blk = DeviceManager.dbManager.getDownloadItems().single { it.id == "li_blk" }
    assertNull("a failed delete is not recorded as cleanup", blk.stagingCleanupAt)
    assertEquals(5L, blk.downloadItemParts.single().bytesDownloaded)
    val internal = DeviceManager.dbManager.getDownloadItems().single { it.id == "li_in" }
    assertNotNull("the control was cleaned", internal.stagingCleanupAt)
    assertFalse(inStaging.exists())
  }

  // ---- Explicit deletion (L01)

  /** The real file operations, except that the card goes away at a chosen moment of the delete (R32-H03). */
  private inner class CardPulledIo(private val probe: Probe, private val pullOnFirstDelete: Boolean) : StorageIo by AndroidStorageIo {
    var pulled = false
    override fun kind(file: File): NodeKind {
      // Unmounted at the first look (pullOnFirstDelete=false): the filesystem then reports the folder as missing
      if (!pullOnFirstDelete && !pulled) { pulled = true; probe.mounted = false }
      return if (pulled) NodeKind.MISSING else AndroidStorageIo.kind(file)
    }
    override fun list(dir: File): List<String>? = if (pulled) null else AndroidStorageIo.list(dir)
    override fun delete(file: File): Boolean {
      if (pulled) return false
      val ok = AndroidStorageIo.delete(file)
      if (pullOnFirstDelete) { pulled = true; probe.mounted = false }
      return ok
    }
  }

  @Test
  fun aCardPulledRightAfterVerificationIsNotADeleteAndKeepsTheItem() {
    val dir = File(sdDownloads, pod)
    val file = media(dir, "e1.mp3")
    val item = podcastLocal(sdFolderId, dir, "e1" to file)
    val probe = Probe()
    // Verified, then the folder reads as missing because the card is out: must not be reported as deleted
    assertEquals(AppStorageItemDeletion.Outcome.STORAGE_UNAVAILABLE, AppStorageItemDeletion.delete(item, probe, CardPulledIo(probe, pullOnFirstDelete = false)))
    assertTrue("the media stays on the card", file.isFile)
    // Card back: the delete finishes
    probe.mounted = true
    assertEquals(AppStorageItemDeletion.Outcome.DELETED, AppStorageItemDeletion.delete(item, probe, AndroidStorageIo))
    assertFalse(dir.exists())
  }

  @Test
  fun aCardPulledDuringTheDeleteIsNotADeleteAndCanBeFinishedLater() {
    val dir = File(sdDownloads, pod)
    media(dir, "e1.mp3")
    media(dir, "e2.mp3")
    val item = podcastLocal(sdFolderId, dir, "e1" to File(dir, "e1.mp3"), "e2" to File(dir, "e2.mp3"))
    val probe = Probe()
    val outcome = AppStorageItemDeletion.delete(item, probe, CardPulledIo(probe, pullOnFirstDelete = true))
    assertTrue("partial or interrupted delete is not DELETED: $outcome", outcome != AppStorageItemDeletion.Outcome.DELETED)
    assertTrue("what was not reached stays", File(dir, "e1.mp3").exists() || File(dir, "e2.mp3").exists())
    probe.mounted = true
    assertEquals(AppStorageItemDeletion.Outcome.DELETED, AppStorageItemDeletion.delete(item, probe, AndroidStorageIo))
    assertFalse(dir.exists())
  }

  @Test
  fun deletingAnSdItemNeedsItsCardAndStaysInsideItsDownloadFolder() {
    val dir = File(sdDownloads, pod)
    val file = media(dir, "e1.mp3")
    val item = podcastLocal(sdFolderId, dir, "e1" to file)

    assertEquals(AppStorageItemDeletion.Outcome.STORAGE_UNAVAILABLE, AppStorageItemDeletion.delete(item, Probe(mounted = false), AndroidStorageIo))
    assertEquals(AppStorageItemDeletion.Outcome.STORAGE_UNAVAILABLE, AppStorageItemDeletion.delete(item, Probe(identity = VolumeIdentity.MISMATCH), AndroidStorageIo))
    assertTrue("nothing is deleted on a card that is out or is another card", file.isFile)

    val outside = podcastLocal(sdFolderId, dir, "e1" to file).apply { absolutePath = sdFiles.path }
    assertEquals(AppStorageItemDeletion.Outcome.OUTSIDE_APP_STORAGE, AppStorageItemDeletion.delete(outside, Probe(), AndroidStorageIo))
    assertTrue(file.isFile)

    val secret = media(File(root, "elsewhere"), "private.bin")
    try {
      Files.createSymbolicLink(File(dir, "link").toPath(), secret.parentFile!!.toPath())
    } catch (e: Exception) {
      assumeTrue("symbolic links are supported here", false)
    }
    assertEquals(AppStorageItemDeletion.Outcome.DELETED, AppStorageItemDeletion.delete(item, Probe(), AndroidStorageIo))
    assertFalse(dir.exists())
    assertTrue("a link inside the folder is removed, never followed", secret.isFile)
  }

  // ---- Retention on the SD card (PR #23)

  private fun retain(): List<String> {
    val entry = AutoDownloadStore.get(server, pod)
    return AutoDownloadRetention.apply(context, server, pod, entry, emptySet(), setOf("keep", "control", "manual"), emptyMap(), emptySet())
  }

  private fun seedSdPodcast(): Triple<File, File, File> {
    val dir = File(sdDownloads, pod)
    val keep = media(dir, "keep.mp3"); val control = media(dir, "control.mp3"); val manual = media(dir, "manual.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal(sdFolderId, dir, "keep" to keep, "control" to control, "manual" to manual))
    DeviceManager.dbManager.saveLocalMediaProgress(progressFor("keep"))
    listOf("keep", "control").forEach { AutoDownloadStore.markAutoQueued(server, pod, it) }
    AutoDownloadStore.claimManual(server, pod, "manual")
    return Triple(keep, control, manual)
  }

  @Test
  fun managedRetentionWorksOnTheSdCardWithTheSameGuards() {
    val (keep, control, manual) = seedSdPodcast()
    AutoDownloadRetention.volumes = { Probe() }
    assertEquals(listOf("control"), retain())
    assertFalse(control.exists())
    assertTrue("in progress", keep.isFile)
    assertTrue("manual", manual.isFile)
    assertEquals(listOf("keep", "manual"), (DeviceManager.dbManager.getLocalLibraryItem("local_$pod")!!.media as Podcast).episodes!!.map { it.serverEpisodeId })
  }

  @Test
  fun retentionDeletesNothingOnAMissingOrDifferentCardOrDuringAMove() {
    val (_, control, _) = seedSdPodcast()
    val before = mapper.writeValueAsString(DeviceManager.dbManager.getLocalLibraryItem("local_$pod"))
    for (probe in listOf(Probe(mounted = false), Probe(identity = VolumeIdentity.MISMATCH), Probe(identity = VolumeIdentity.UNREGISTERED))) {
      AutoDownloadRetention.volumes = { probe }
      assertEquals(emptyList<String>(), retain())
    }
    AutoDownloadRetention.volumes = { Probe() }
    journal["local_$pod"] = StorageMoveJournalEntry(itemId = "local_$pod", phase = MovePhase.COMMITTED)
    assertEquals(emptyList<String>(), retain())
    assertTrue(control.isFile)
    assertEquals(before, mapper.writeValueAsString(DeviceManager.dbManager.getLocalLibraryItem("local_$pod")))
    // Keep All
    journal.clear()
    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)
    assertEquals(emptyList<String>(), retain())
    assertTrue(control.isFile)
  }

  @Test
  fun anotherServersSdCopyIsNeverTouchedByRetention() {
    seedSdPodcast()
    val other = DeviceManager.dbManager.getLocalLibraryItem("local_$pod")!!.apply { serverConnectionConfigId = "srvB" }
    DeviceManager.dbManager.saveLocalLibraryItem(other)
    AutoDownloadRetention.volumes = { Probe() }
    assertEquals(emptyList<String>(), retain())
    assertTrue(File(sdDownloads, "$pod/control.mp3").isFile)
  }

  @Test
  fun theDownloadTargetFolderIsTheChosenStorage() {
    assertEquals(AppStorage.appsdFolderId(uuid, "podcast"), AppStorage.folderFor(StorageTarget.Sd(uuid), "podcast").id)
    assertEquals(File(sdFiles, "downloads"), AppStorage.downloadsRoot(sdFolderId, Probe()))
    assertNull("a missing card is never silently replaced by internal storage", AppStorage.downloadsRoot(sdFolderId, Probe(mounted = false)))
  }
}
