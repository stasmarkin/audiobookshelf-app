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

  private fun altName(item: DownloadItem, part: DownloadItemPart) =
          DownloadDestinationGuard.alternateFilename(part.filename, DownloadDestinationGuard.ownerOf(item, part))

  private fun docName(part: DownloadItemPart) =
          DocumentsContract.getDocumentId(Uri.parse(part.completedDestinationUri!!)).substringAfterLast('/')

  // the finalization guard: another episode's document is never replaced, even for a part that bypassed admission;
  // the new download is saved under its own alternative name instead (audit F2)
  @Test fun anotherEpisodesDocumentIsNeverReplaced() {
    val a = download("ep-a", "episode.mp3", bytes("A"))
    val (item, part) = forced("ep-b", "episode.mp3", bytes("B"))
    move(item, part)
    assertTrue(part.failureReason, part.moved)
    assertEquals(altName(item, part), docName(part))
    assertArrayEquals(bytes("A"), a.readBytes())
    assertArrayEquals(bytes("B"), File(showDir, altName(item, part)).readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
    assertTrue(hiddenTemporaries().isEmpty())
  }

  // a file the user keeps in the folder, unknown to any record, is not touched; the download gets another name
  @Test fun anUnrecordedDocumentInTheFolderIsKept() {
    showDir.mkdirs()
    val users = File(showDir, "episode.mp3").apply { writeBytes(bytes("USERS-OWN")) }
    val (item, part) = forced("ep-b", "episode.mp3", bytes("B"))
    move(item, part)
    assertTrue(part.failureReason, part.moved)
    assertArrayEquals(bytes("USERS-OWN"), users.readBytes())
    assertArrayEquals(bytes("B"), File(showDir, altName(item, part)).readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  // A re-download of the same episode never replaces the earlier copy: the new one gets its own name, nothing is deleted
  @Test fun aSameEpisodeRedownloadKeepsThePreviousCopy() {
    val first = download("ep-a", "episode.mp3", bytes("V1"))
    val (item, part) = forced("ep-a", "episode.mp3", bytes("V2-LONGER"))
    move(item, part)
    assertTrue(part.failureReason, part.moved)
    assertArrayEquals(bytes("V1"), first.readBytes())
    assertArrayEquals(bytes("V2-LONGER"), File(showDir, docName(part)).readBytes())
    assertEquals(setOf("episode.mp3", altName(item, part)), showDir.listFiles()!!.map { it.name }.toSet())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  // The v0.19.0 case: the records are gone but the files are still in the folder. The download completes under its own
  // name, is saved with that name, and the old file stays (equal names are not taken as the same media)
  @Test fun aLeftoverFileFromLostRecordsNoLongerBlocksTheDownload() {
    showDir.mkdirs()
    val leftover = File(showDir, "episode.mp3").apply { writeBytes(bytes("OLD-SAME-SIZE")) }
    download("ep-a", "episode.mp3", bytes("NEW-SAME-SIZE"))
    val local = DbManager().getLocalLibraryItems().single { it.folderId == "saf-r29" } // read back from Paper
    val file = local.localFiles.single()
    val expected = DownloadDestinationGuard.alternateFilename("episode.mp3",
            DownloadDestinationGuard.Owner(config.userId, config.id, "show-1", "episode:ep-a"))
    assertEquals(expected, file.filename)
    assertTrue(file.absolutePath.endsWith("/Synthetic/$expected"))
    assertEquals(expected, DocumentsContract.getDocumentId(Uri.parse(file.contentUrl)).substringAfterLast('/'))
    assertArrayEquals(bytes("NEW-SAME-SIZE"), context.contentResolver.openInputStream(Uri.parse(file.contentUrl))!!.use { it.readBytes() })
    assertArrayEquals(bytes("OLD-SAME-SIZE"), leftover.readBytes())
    assertEquals(1, (local.media as Podcast).episodes!!.size)
  }

  // Several collisions: the name, its alternative (a file) and the first numbered variant (a folder) are all taken
  @Test fun multipleCollisionsUseTheNextFreeNumberedName() {
    val (item, part) = forced("ep-a", "episode.mp3", bytes("NEW"))
    showDir.mkdirs()
    val alt = altName(item, part)
    File(showDir, "episode.mp3").writeBytes(bytes("ONE"))
    File(showDir, alt).writeBytes(bytes("TWO"))
    val dir = File(showDir, DownloadDestinationGuard.numberedFilename(alt, 2)).apply { mkdirs() }
    File(dir, "inside").writeBytes(bytes("THREE"))
    move(item, part)
    assertTrue(part.failureReason, part.moved)
    assertEquals(DownloadDestinationGuard.numberedFilename(alt, 3), docName(part))
    assertArrayEquals(bytes("ONE"), File(showDir, "episode.mp3").readBytes())
    assertArrayEquals(bytes("TWO"), File(showDir, alt).readBytes())
    assertArrayEquals(bytes("THREE"), File(dir, "inside").readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  // When every candidate name is taken the download fails and nothing changes (staging is kept for Retry)
  @Test fun allCandidateNamesTakenFailsWithoutTouchingAnything() {
    val (item, part) = forced("ep-a", "episode.mp3", bytes("NEW"))
    showDir.mkdirs()
    val alt = altName(item, part)
    val names = listOf("episode.mp3", alt) + (2..9).map { DownloadDestinationGuard.numberedFilename(alt, it) }
    names.forEach { File(showDir, it).writeBytes(bytes(it)) }
    move(item, part)
    assertTrue(part.failed)
    assertFalse(part.moved)
    names.forEach { assertArrayEquals(bytes(it), File(showDir, it).readBytes()) }
    assertEquals(names.toSet(), showDir.listFiles()!!.map { it.name }.toSet())
    assertArrayEquals(bytes("NEW"), File(part.destinationPath).readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  // A provider that gives the document a name of its own: that name and URI are what gets saved
  @Test fun aProviderChosenNameIsWhatTheItemRecords() {
    FakeDocumentTreeProvider.alternateFinalName = "episode (1).mp3"
    download("ep-a", "episode.mp3", bytes("A"))
    val file = DbManager().getLocalLibraryItems().single { it.folderId == "saf-r29" }.localFiles.single()
    assertEquals("episode (1).mp3", file.filename)
    assertTrue(file.absolutePath.endsWith("/Synthetic/episode (1).mp3"))
    assertArrayEquals(bytes("A"), context.contentResolver.openInputStream(Uri.parse(file.contentUrl))!!.use { it.readBytes() })
  }

  @Test fun aFailedFinalRenameKeepsStagingAndRetryCanSucceed() {
    FakeDocumentTreeProvider.failNextRenameTo = "episode.mp3"
    val (item, part) = forced("ep-a", "episode.mp3", bytes("NEW-BYTES"))
    move(item, part)
    assertFalse(part.moved)
    assertTrue(part.failed)
    assertNull(part.completedDestinationUri)
    assertFalse(File(showDir, "episode.mp3").exists())
    assertArrayEquals(bytes("NEW-BYTES"), File(part.destinationPath).readBytes())
    assertEquals(1, hiddenTemporaries().size)
    assertTrue(manager.retryDownloadItem(item.id))
    stage(part, bytes("NEW-BYTES")) // existing Retry policy is a fresh transfer, not temp recovery
    move(item, part)
    assertTrue(part.moved)
    assertArrayEquals(bytes("NEW-BYTES"), File(showDir, "episode.mp3").readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  @Test fun aBackupLeftByAnInterruptedReplacementIsLeftAlone() {
    val a = download("ep-a", "episode.mp3", bytes("ONLY-COPY"))
    val backup = File(showDir, ".episode.mp3.abs-backup")
    assertTrue(a.renameTo(backup))
    val renames = FakeDocumentTreeProvider.renames.toList()
    val (item, part) = forced("ep-b", "episode.mp3", bytes("B"))
    move(item, part)
    // the name is ep-a's on record: ep-b gets its own name; the backup is neither restored nor renamed nor deleted
    assertTrue(part.failureReason, part.moved)
    assertEquals(altName(item, part), docName(part))
    assertArrayEquals(bytes("ONLY-COPY"), backup.readBytes())
    assertFalse(a.exists())
    assertTrue(FakeDocumentTreeProvider.renames.drop(renames.size).none { it.contains("abs-backup") })
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
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

  @Test fun aDirectoryReplacingTheFinalFileIsNeverDeleted() {
    showDir.mkdirs()
    val target = File(showDir, "episode.mp3").apply { mkdirs() }
    val child = File(target, "important").apply { writeBytes(bytes("KEEP")) }
    val (item, part) = forced("ep-a", "episode.mp3", bytes("A2"))
    move(item, part)
    // the folder stays as it is; the download is saved next to it under its own name
    assertTrue(part.failureReason, part.moved)
    assertEquals(altName(item, part), docName(part))
    assertTrue(target.isDirectory)
    assertArrayEquals(bytes("KEEP"), child.readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
    assertTrue(FakeDocumentTreeProvider.renames.none { it.endsWith("-> episode.mp3") })
  }

  @Test fun deterministicTemporaryFileAndDirectoryCollisionsAreUntouched() {
    val (item, part) = forced("ep-a", "episode.mp3", bytes("NEW"))
    showDir.mkdirs()
    val old = File(showDir, "." + part.filename + "." + part.id.hashCode() + ".part").apply { mkdirs() }
    val child = File(old, "important").apply { writeBytes(bytes("KEEP")) }
    val other = File(showDir, ".episode.mp3.abs-backup").apply { writeBytes(bytes("OLD")) }
    move(item, part)
    assertTrue(part.moved)
    assertArrayEquals(bytes("NEW"), File(showDir, "episode.mp3").readBytes())
    assertArrayEquals(bytes("KEEP"), child.readBytes())
    assertArrayEquals(bytes("OLD"), other.readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  @Test fun incompleteStagingCannotComplete() {
    val (item, part) = forced("ep-a", "episode.mp3", bytes("NEW"))
    File(part.destinationPath).writeBytes(bytes("TRUNCATED-OR-CHANGED-SIZE"))
    move(item, part)
    assertTrue(part.failed)
    assertFalse(part.moved)
    assertNull(part.completedDestinationUri)
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  @Test fun aRegularFileAtTheOldTemporaryNameIsNotDeleted() {
    val (item, part) = forced("ep-a", "episode.mp3", bytes("NEW"))
    showDir.mkdirs()
    val old = File(showDir, "." + part.filename + "." + part.id.hashCode() + ".part").apply { writeBytes(bytes("KEEP")) }
    move(item, part)
    assertTrue(part.moved)
    assertArrayEquals(bytes("KEEP"), old.readBytes())
    assertArrayEquals(bytes("NEW"), File(showDir, "episode.mp3").readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  @Test fun anEqualLengthUnrecordedFileIsNeverAdoptedAsCompleted() {
    showDir.mkdirs()
    val unknown = File(showDir, "episode.mp3").apply { writeBytes(bytes("SAME-SIZE")) }
    val (item, part) = forced("ep-a", "episode.mp3", bytes("SAME-SIZE"))
    part.completed = false
    val method = DownloadItemManager::class.java.getDeclaredMethod("findSharedStorageFile", DownloadItemPart::class.java).apply { isAccessible = true }
    assertNull(method.invoke(manager, part))
    assertFalse(part.reusedExistingFile)
    assertFalse(part.moved)
    assertNull(part.completedDestinationUri)
    assertArrayEquals(bytes("SAME-SIZE"), unknown.readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
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
