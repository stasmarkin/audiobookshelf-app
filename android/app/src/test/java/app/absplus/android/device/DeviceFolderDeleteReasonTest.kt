package app.absplus.android.device

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalFolder
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.managers.DbManager
import app.absplus.android.managers.FakeDocumentTreeProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit F5: a manual delete of a download in a device folder picked through Android's folder picker, through the real
 * LocalItemDeleter, FolderFileDeletion.AndroidDocs and Paper, against a document tree over a temporary directory that
 * answers isChildDocument (Android 10+) and findDocumentPath (Android 8+) as Android's storage provider does. Checks
 * that the delete works on Android 8–9 as well, that Android 7 refuses it with its own reason, and that every refusal
 * says why and keeps the files and records. Synthetic files only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceFolderDeleteReasonTest {
  private lateinit var ctx: Context
  private lateinit var base: File
  private lateinit var tree: Uri
  private lateinit var bookDir: File
  private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

  @Before fun setup() {
    ctx = ApplicationProvider.getApplicationContext()
    DbManager.initialize(ctx)
    base = File(ctx.cacheDir, "reason-${UUID.randomUUID()}").apply { mkdirs() }
    bookDir = File(base, "Books/Author/Title").apply { mkdirs() }
    FakeDocumentTreeProvider.reset(base)
    Robolectric.buildContentProvider(FakeDocumentTreeProvider::class.java).create(ProviderInfo().apply {
      authority = FakeDocumentTreeProvider.AUTHORITY; exported = true; grantUriPermissions = true
    })
    tree = DocumentsContract.buildTreeDocumentUri(FakeDocumentTreeProvider.AUTHORITY, "primary:Books")
    ctx.contentResolver.takePersistableUriPermission(tree, flags)
    // Path-less: access is judged by the persisted tree grant alone (as for a folder without a resolvable path)
    DeviceManager.dbManager.saveLocalFolder(LocalFolder("saf-books", "Books", tree.toString(), "Books", "", "external", "book"))
  }

  private fun doc(relative: String) = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Books/$relative").toString()

  /** A downloaded book in Books/Author/Title with [files] tracks. */
  private fun savedBook(id: String, vararg files: String): LocalLibraryItem {
    files.forEach { File(bookDir, it).writeText("SYNTHETIC-$it") }
    val localFiles = files.mapIndexed { i, f -> LocalFile("f$i", f, doc("Author/Title/$f"), "Books", File(bookDir, f).path, "audio/mpeg", 10) }
    val tracks = localFiles.mapIndexed { i, f -> AudioTrack(i + 1, i * 60.0, 60.0, f.filename!!, f.contentUrl, "audio/mpeg", null, true, f.id, i + 1) }
    val book = Book(BookMetadata("Title", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null),
            null, emptyList(), null, null, tracks.toMutableList(), null, null, null, null)
    val item = LocalLibraryItem(id, "saf-books", "", bookDir.path, doc("Author/Title"), false, "book", book, localFiles.toMutableList(),
            null, null, true, "cfg", "https://s", "u", "srv-$id")
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    return item
  }

  private fun deleteItem(id: String): Pair<LocalItemDeleter.Outcome, FolderFileDeletion.Detail?> {
    var detail: FolderFileDeletion.Detail? = null
    val outcome = LocalItemDeleter.deleteItem(ctx, id, auto = false, onDetail = { detail = it })
    return outcome to detail
  }

  private fun assertKept(id: String, vararg files: String) {
    assertNotNull("record kept", DeviceManager.dbManager.getLocalLibraryItem(id))
    files.forEach { assertTrue("$it kept", File(bookDir, it).exists()) }
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  @Test fun deletesTheRegisteredFilesOnAndroid10AndLater() {
    savedBook("local_ok", "01.mp3", "02.mp3")
    assertEquals(LocalItemDeleter.Outcome.DELETED to null, deleteItem("local_ok"))
    assertFalse(File(bookDir, "01.mp3").exists() || File(bookDir, "02.mp3").exists())
    assertTrue("folders are never deleted", bookDir.isDirectory)
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_ok"))
  }

  @Config(sdk = [28])
  @Test fun deletesTheRegisteredFilesOnAndroid9ThroughTheDocumentPath() {
    savedBook("local_p", "01.mp3", "02.mp3")
    assertEquals(LocalItemDeleter.Outcome.DELETED to null, deleteItem("local_p"))
    assertFalse(File(bookDir, "01.mp3").exists() || File(bookDir, "02.mp3").exists())
    assertTrue(bookDir.isDirectory)
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_p"))
  }

  @Config(sdk = [26])
  @Test fun deletesTheRegisteredFilesOnAndroid8() {
    savedBook("local_o", "01.mp3")
    assertEquals(LocalItemDeleter.Outcome.DELETED to null, deleteItem("local_o"))
    assertFalse(File(bookDir, "01.mp3").exists())
  }

  @Config(sdk = [24])
  @Test fun android7SaysItCannotDeleteHereAndKeepsEverything() {
    savedBook("local_n", "01.mp3")
    assertEquals(LocalItemDeleter.Outcome.FAILED to FolderFileDeletion.Detail.UNSUPPORTED, deleteItem("local_n"))
    assertKept("local_n", "01.mp3")
  }

  @Test fun aRevokedFolderGrantIsReportedAsLostAccessNotAsAnSdCard() {
    savedBook("local_r", "01.mp3")
    ctx.contentResolver.releasePersistableUriPermission(tree, flags)
    assertEquals(LocalItemDeleter.Outcome.STORAGE_UNAVAILABLE to FolderFileDeletion.Detail.FOLDER_ACCESS, deleteItem("local_r"))
    assertKept("local_r", "01.mp3")
    assertEquals("folderAccess", FolderFileDeletion.Detail.FOLDER_ACCESS.reason)
  }

  @Test fun aMissingFolderWithItsGrantIsReportedAsMissing() {
    savedBook("local_m", "01.mp3")
    assertTrue(File(base, "Books").deleteRecursively()) // the picked folder (or its storage) is gone
    assertEquals(LocalItemDeleter.Outcome.STORAGE_UNAVAILABLE to FolderFileDeletion.Detail.FOLDER_MISSING, deleteItem("local_m"))
    assertNotNull(DeviceManager.dbManager.getLocalLibraryItem("local_m"))
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  @Test fun aFolderWhereAFileShouldBeIsNeverDeleted() {
    savedBook("local_d", "01.mp3", "02.mp3")
    File(bookDir, "02.mp3").apply { delete(); mkdirs() }
    File(bookDir, "02.mp3/inside.txt").writeText("KEEP")
    assertEquals(LocalItemDeleter.Outcome.FAILED to FolderFileDeletion.Detail.NOT_A_FILE, deleteItem("local_d"))
    assertKept("local_d", "01.mp3", "02.mp3/inside.txt")
  }

  @Test fun aDeleteTheProviderRefusesIsReported() {
    savedBook("local_f", "01.mp3")
    FakeDocumentTreeProvider.failDeleteOf += "primary:Books/Author/Title/01.mp3"
    assertEquals(LocalItemDeleter.Outcome.FAILED to FolderFileDeletion.Detail.DELETE_FAILED, deleteItem("local_f"))
    assertNotNull(DeviceManager.dbManager.getLocalLibraryItem("local_f"))
    assertTrue(File(bookDir, "01.mp3").exists())
  }

  @Test fun aPartialDeleteIsReportedAndKeepsTheRecordForAnotherTry() {
    savedBook("local_x", "01.mp3", "02.mp3")
    FakeDocumentTreeProvider.failDeleteOf += "primary:Books/Author/Title/02.mp3"
    assertEquals(LocalItemDeleter.Outcome.FAILED to FolderFileDeletion.Detail.PARTIAL, deleteItem("local_x"))
    assertFalse(File(bookDir, "01.mp3").exists())
    assertTrue(File(bookDir, "02.mp3").exists())
    assertNotNull("record kept", DeviceManager.dbManager.getLocalLibraryItem("local_x"))
    // another try finishes it: the gone file counts as deleted, the other one goes now
    FakeDocumentTreeProvider.failDeleteOf.clear()
    assertEquals(LocalItemDeleter.Outcome.DELETED to null, deleteItem("local_x"))
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_x"))
  }

  @Test fun aSingleTrackThatCannotBeDeletedSaysWhyAndKeepsTheTrack() {
    savedBook("local_t", "01.mp3", "02.mp3")
    FakeDocumentTreeProvider.failDeleteOf += "primary:Books/Author/Title/02.mp3"
    var detail: FolderFileDeletion.Detail? = null
    val result = LocalItemDeleter.deleteTrack(ctx, "local_t", "f1", null, onDetail = { detail = it })
    assertSame(LocalItemDeleter.TrackResult.Failed, result)
    assertEquals(FolderFileDeletion.Detail.DELETE_FAILED, detail)
    assertEquals(2, DeviceManager.dbManager.getLocalLibraryItem("local_t")!!.localFiles.size)
    assertTrue(File(bookDir, "02.mp3").exists())
  }

  @Test fun aSingleTrackIsDeletedAndATrackWhoseFileIsGoneLosesOnlyItsRecord() {
    savedBook("local_s", "01.mp3", "02.mp3")
    val first = LocalItemDeleter.deleteTrack(ctx, "local_s", "f0", null)
    assertTrue(first is LocalItemDeleter.TrackResult.Deleted)
    assertFalse(File(bookDir, "01.mp3").exists())
    assertTrue(File(bookDir, "02.mp3").delete()) // removed outside the app
    val second = LocalItemDeleter.deleteTrack(ctx, "local_s", "f1", null)
    assertTrue(second is LocalItemDeleter.TrackResult.Deleted)
    assertTrue(DeviceManager.dbManager.getLocalLibraryItem("local_s")!!.localFiles.isEmpty())
    assertTrue(bookDir.isDirectory)
  }
}
