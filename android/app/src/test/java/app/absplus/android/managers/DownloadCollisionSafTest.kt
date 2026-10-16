package app.absplus.android.managers

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderScanner
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import com.getcapacitor.JSObject
import io.paperdb.Paper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/**
 * R29-N02 in a device folder (Storage Access Framework): the real queue admission, device-folder finalization
 * (moveDownloadedFile), FolderScanner registration and same-name reuse, against FakeDocumentTreeProvider, a document
 * tree over a temporary directory that can fail renames and deletes. Synthetic files, ids and accounts only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadCollisionSafTest {
  private lateinit var context: Context
  private lateinit var manager: DownloadItemManager
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private var done = CountDownLatch(1)
  private lateinit var tree: Uri
  private lateinit var folder: LocalFolder
  private lateinit var showDir: File
  private val config = ServerConnectionConfig("synthetic-A", 0, "Fixture A", "http://127.0.0.1:1", null, "user-a", "fixture", "token-a", null)

  @Before fun setup() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    for (book in listOf("downloadItems", "localLibraryItems", "localMediaProgress", "autoDownload", "localFolders")) Paper.book(book).destroy()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(config)
    DeviceManager.serverConnectionConfig = config
    try { WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build()) } catch (_: IllegalStateException) {}
    val base = File(context.cacheDir, "saf-tree").apply { deleteRecursively(); mkdirs() }
    File(base, "R29").mkdirs()
    showDir = File(base, "R29/Synthetic")
    FakeDocumentTreeProvider.reset(base)
    Robolectric.buildContentProvider(FakeDocumentTreeProvider::class.java).create(ProviderInfo().apply {
      authority = FakeDocumentTreeProvider.AUTHORITY; exported = true; grantUriPermissions = true
    })
    tree = DocumentsContract.buildTreeDocumentUri(FakeDocumentTreeProvider.AUTHORITY, "primary:R29")
    context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    folder = LocalFolder("saf-r29", "R29", tree.toString(), "R29", File(base, "R29").absolutePath, "external", "podcast")
    DeviceManager.dbManager.saveLocalFolder(folder)
    val staging = File(context.filesDir, "saf-staging/show-1").apply { deleteRecursively(); mkdirs() }
    ShadowStatFs.registerStats(staging, 1_000_000, 900_000, 900_000)
    manager = DownloadItemManager(FolderScanner(context), context, object : DownloadItemManager.DownloadEventEmitter {
      override fun onDownloadItem(downloadItem: DownloadItem) {}
      override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
      override fun onDownloadItemComplete(jsobj: JSObject) { done.countDown() }
      override fun onQueueChanged(hasWork: Boolean) {}
    })
  }

  @After fun cleanup() {
    manager.destroy()
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
  }

  private fun sanitized(path: String): String = LibraryItemDownloadStarter::class.java
      .getDeclaredMethod("getFilenameFromRelPath", String::class.java).apply { isAccessible = true }
      .invoke(LibraryItemDownloadStarter, path) as String

  private fun bytes(label: String) = "SYNTHETIC-SAF-$label".toByteArray()
  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  /** A device-folder episode download as the starter builds it. */
  private fun safItem(ep: String, name: String, size: Int): DownloadItem {
    val metadata = FileMetadata(name, "mp3", "/synthetic/$name", name, size.toLong())
    val track = AudioTrack(1, 0.0, 60.0, ep, "", "audio/mpeg", metadata, false, null, 1)
    val episode = PodcastEpisode(ep, 1, null, null, ep, null, null, null, 1L, AudioFile(1, "ino-$ep", metadata), track, null, 60.0, size.toLong(), null, null)
    val part = partFor("show-1-$ep", name, size, ep, track, episode)
    val media = Podcast(PodcastMetadata("Synthetic", "Fixture", null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0)
    return DownloadItem("show-1-$ep", "show-1", ep, null, config.id, config.address, config.userId, "podcast", "${folder.absolutePath}/Synthetic", folder, "Synthetic", "Synthetic", media, mutableListOf(part))
  }

  private fun partFor(itemId: String, name: String, size: Int, ep: String, track: AudioTrack?, episode: PodcastEpisode?) =
          DownloadItemPart.make(itemId, name, size.toLong(), File(context.filesDir, "saf-staging/show-1/$name.$ep.part"),
                  File("${folder.absolutePath}/Synthetic/$name"), "Synthetic", "/api/items/show-1/file/ino-$ep/download", folder, null, track, episode)

  private fun stage(part: DownloadItemPart, content: ByteArray) {
    File(part.destinationPath).apply { parentFile!!.mkdirs(); writeBytes(content) }
    part.completed = true
  }

  private fun move(item: DownloadItem, part: DownloadItemPart) {
    DownloadItemManager::class.java.getDeclaredMethod("moveDownloadedFile", DownloadItem::class.java, DownloadItemPart::class.java)
        .apply { isAccessible = true }.invoke(manager, item, part)
    val deadline = System.currentTimeMillis() + 15000
    while (!part.moved && !part.failed && System.currentTimeMillis() < deadline) Thread.sleep(10)
  }

  /** Admission, staged transfer, device-folder finalization and registration; returns the document's file in the tree. */
  private fun download(ep: String, rel: String, content: ByteArray): File {
    done = CountDownLatch(1)
    val item = safItem(ep, sanitized(rel), content.size)
    manager.addDownloadItem(item)
    val queued = manager.findDownloadItem(item.id)!!
    val part = queued.downloadItemParts.single()
    stage(part, content)
    move(queued, part)
    assertTrue("$ep must finalize: ${part.failureReason}", part.moved)
    assertTrue("registration of $ep", done.await(15, TimeUnit.SECONDS))
    val deadline = System.currentTimeMillis() + 10000
    while (manager.findDownloadItem(item.id) != null && System.currentTimeMillis() < deadline) Thread.sleep(10)
    return File(showDir, part.filename)
  }

  /** A part queued before the fix (no admission): forced onto [name]. */
  private fun forced(ep: String, name: String, content: ByteArray): Pair<DownloadItem, DownloadItemPart> {
    val item = safItem(ep, "admitted-$ep.mp3", content.size)
    manager.addDownloadItem(item)
    val queued = manager.findDownloadItem(item.id)!!
    val original = queued.downloadItemParts.single()
    val part = partFor(item.id, name, content.size, ep, original.audioTrack, original.episode)
    queued.downloadItemParts[0] = part
    stage(part, content)
    return queued to part
  }

  private fun hiddenTemporaries() = showDir.listFiles()!!.filter { it.name.endsWith(".part") }

  private fun safLocal(): LocalLibraryItem = DeviceManager.dbManager.getLocalLibraryItems().single { it.folderId == "saf-r29" }

  // two episodes resolving to one name in a device folder keep separate documents, bytes and records
  @Test fun twoEpisodesWithOneNameKeepSeparateDocuments() {
    val a = download("ep-a", "Season1/episode.mp3", bytes("A"))
    val b = download("ep-b", "Season1_episode.mp3", bytes("B-DIFFERENT"))
    assertEquals("Season1_episode.mp3", a.name)
    assertNotEquals(a.name, b.name)
    assertArrayEquals(bytes("A"), a.readBytes())
    assertArrayEquals(bytes("B-DIFFERENT"), b.readBytes())
    val local = safLocal()
    val episodes = (local.media as Podcast).episodes!!
    assertEquals(2, episodes.map { it.audioTrack!!.localFileId }.toSet().size)
    episodes.forEach { e ->
      val file = local.localFiles.single { it.id == e.audioTrack!!.localFileId }
      val bytesOnRecord = context.contentResolver.openInputStream(Uri.parse(file.contentUrl))!!.use { it.readBytes() }
      assertEquals(sha(bytes(if (e.serverEpisodeId == "ep-a") "A" else "B-DIFFERENT")), sha(bytesOnRecord))
    }
  }

  // the finalization guard: another episode's document is never deleted, even for a part that bypassed admission
  @Test fun anotherEpisodesDocumentIsNeverReplaced() {
    val a = download("ep-a", "episode.mp3", bytes("A"))
    val (item, part) = forced("ep-b", "episode.mp3", bytes("B"))
    move(item, part)
    assertTrue(part.failed)
    assertTrue(part.failureReason!!.contains("another file"))
    assertArrayEquals(bytes("A"), a.readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.none { it.endsWith("/episode.mp3") })
    assertTrue(hiddenTemporaries().isEmpty())
    assertTrue(File(part.destinationPath).exists())
  }

  // a file the user keeps in the folder, unknown to any record, is not deleted either
  @Test fun anUnrecordedDocumentInTheFolderIsKept() {
    showDir.mkdirs()
    val users = File(showDir, "episode.mp3").apply { writeBytes(bytes("USERS-OWN")) }
    val (item, part) = forced("ep-b", "episode.mp3", bytes("B"))
    move(item, part)
    assertTrue(part.failed)
    assertArrayEquals(bytes("USERS-OWN"), users.readBytes())
  }

  // the same episode again: replaces only its own document, under its own name
  @Test fun aSameEpisodeRetryReplacesItsOwnDocument() {
    val first = download("ep-a", "episode.mp3", bytes("V1"))
    val second = download("ep-a", "episode.mp3", bytes("V2-LONGER"))
    assertEquals(first.name, second.name)
    assertArrayEquals(bytes("V2-LONGER"), second.readBytes())
    assertEquals(listOf("episode.mp3"), showDir.listFiles()!!.map { it.name })
  }

  // the new file cannot be renamed into place: the previous copy (already moved to its backup) is restored
  @Test fun aFailedRenameRestoresThePreviousDocument() {
    val a = download("ep-a", "episode.mp3", bytes("KEEP"))
    FakeDocumentTreeProvider.failNextRenameTo = "episode.mp3"
    val item = safItem("ep-a", "episode.mp3", 9)
    manager.addDownloadItem(item)
    val queued = manager.findDownloadItem(item.id)!!
    val part = queued.downloadItemParts.single()
    stage(part, bytes("NEW-BYTES"))
    move(queued, part)
    assertFalse(part.moved)
    assertTrue(FakeDocumentTreeProvider.renames.any { it.endsWith("-> .episode.mp3.abs-backup") })
    assertArrayEquals(bytes("KEEP"), a.readBytes())
    assertFalse(File(showDir, ".episode.mp3.abs-backup").exists())
    assertTrue(File(part.destinationPath).exists())
  }

  // a backup left by an interrupted replacement is restored before anything else happens
  @Test fun aBackupLeftByAnInterruptedReplacementIsRestored() {
    val a = download("ep-a", "episode.mp3", bytes("ONLY-COPY"))
    assertTrue(a.renameTo(File(showDir, ".episode.mp3.abs-backup"))) // process death after "existing -> backup"
    val (item, part) = forced("ep-b", "episode.mp3", bytes("B"))
    move(item, part)
    assertTrue(part.failed) // the restored file belongs to ep-a
    assertArrayEquals(bytes("ONLY-COPY"), a.readBytes())
    assertFalse(File(showDir, ".episode.mp3.abs-backup").exists())
  }

  // every rename fails: the existing document is never touched
  @Test fun whenNoRenameWorksTheExistingDocumentIsUntouched() {
    val a = download("ep-a", "episode.mp3", bytes("KEEP"))
    FakeDocumentTreeProvider.failRename = true
    val item = safItem("ep-a", "episode.mp3", 9)
    manager.addDownloadItem(item)
    val queued = manager.findDownloadItem(item.id)!!
    val part = queued.downloadItemParts.single()
    stage(part, bytes("NEW-BYTES"))
    move(queued, part)
    assertFalse(part.moved)
    assertArrayEquals(bytes("KEEP"), a.readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.none { it.endsWith("/episode.mp3") })
  }

  // a failed delete of an existing document never loses it
  @Test fun aFailedDeleteKeepsEverything() {
    val a = download("ep-a", "episode.mp3", bytes("A"))
    FakeDocumentTreeProvider.failDelete = true
    val second = download("ep-a", "episode.mp3", bytes("A2"))
    assertArrayEquals(bytes("A2"), second.readBytes())
    // the previous copy could not be removed: it stays aside, nothing is lost
    assertArrayEquals(bytes("A"), File(showDir, ".episode.mp3.abs-backup").readBytes())
    assertEquals(a.name, second.name)
  }

  // a same-named document of another episode is never reused as this episode's download
  @Test fun anotherEpisodesDocumentIsNotReusedAsThisDownload() {
    download("ep-a", "episode.mp3", bytes("SAME-SIZE"))
    val (item, part) = forced("ep-b", "episode.mp3", bytes("SAME-SIZE"))
    part.completed = false
    File(part.destinationPath).delete()
    DownloadItemManager::class.java.getDeclaredMethod("checkUpdateDownloadQueue").apply { isAccessible = true }.invoke(manager)
    assertFalse(part.reusedExistingFile)
    assertNull(part.completedDestinationUri)
    manager.cancelAll()
  }
}
