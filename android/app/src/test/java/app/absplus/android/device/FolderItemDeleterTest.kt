package app.absplus.android.device

import androidx.documentfile.provider.DocumentFile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderItemDeleterTest {
  private lateinit var root: File

  @Before
  fun setUp() {
    root = Files.createTempDirectory("folder-delete").toFile()
  }

  @After
  fun tearDown() {
    File(root, "Locked").setWritable(true)
    root.deleteRecursively()
  }

  private fun file(path: String, text: String = "x"): File = File(root, path).also { it.parentFile!!.mkdirs(); it.writeText(text) }

  private fun doc(f: File): DocumentFile = DocumentFile.fromFile(f)

  private val lister = FolderItemDeleter.DeviceLister(ApplicationProvider.getApplicationContext<Context>())
  private val never = FolderItemDeleter.Lister { null }

  private fun target(f: File?) = FolderItemDeleter.Target(f?.let { doc(it) }) { false }

  private fun delete(book: File, registered: List<File>, parent: File?, l: FolderItemDeleter.Lister = lister): Boolean =
          FolderItemDeleter.deleteRegistered(doc(book), registered.map { target(it) }, registered.map { it.name }, { parent?.let { doc(it) } }, l)

  @Test
  fun userFileInBookFolderSurvives() {
    val audio = file("Author/Book/book.mp3")
    val notes = file("Author/Book/notes.txt")
    assertTrue(delete(File(root, "Author/Book"), listOf(audio), File(root, "Author")))
    assertFalse(audio.exists())
    assertTrue(notes.exists())
    assertTrue(File(root, "Author").exists())
  }

  @Test
  fun registeredPathReplacedByFolderKeepsTheFolderAndEverythingElse() {
    val real = file("Author/Book/a.mp3")
    val replaced = file("Author/Book/b.mp3/inside.txt").parentFile!!
    val part = file("Author/Book/.a.mp3.1.part")
    assertFalse(delete(File(root, "Author/Book"), listOf(real, replaced), File(root, "Author")))
    assertTrue("nothing deleted, not even the other file", real.exists())
    assertTrue(File(replaced, "inside.txt").exists())
    assertTrue("nor the partial", part.exists())
  }

  @Test
  fun neighbourUnregisteredFolderSurvives() {
    val audio = file("Author/Book/book.mp3")
    val other = file("Author/Other/song.mp3")
    assertTrue(delete(File(root, "Author/Book"), listOf(audio), File(root, "Author")))
    assertFalse(File(root, "Author/Book").exists())
    assertTrue(other.exists())
    assertTrue(File(root, "Author").exists())
  }

  @Test
  fun emptiedBookFolderAndEmptyParentGo() {
    val audio = file("Author/Book/book.mp3")
    assertTrue(delete(File(root, "Author/Book"), listOf(audio), File(root, "Author")))
    assertFalse(File(root, "Author").exists())
  }

  @Test
  fun parentIsNotTouchedWhenNotRequested() {
    val audio = file("Author/Book/book.mp3")
    assertTrue(delete(File(root, "Author/Book"), listOf(audio), null))
    assertFalse(File(root, "Author/Book").exists())
    assertTrue(File(root, "Author").exists())
  }

  @Test
  fun alreadyMissingAndUnresolvedFilesCountAsGone() {
    val book = File(root, "Author/Book").also { it.mkdirs() }
    val gone = File(book, "gone.mp3")
    assertTrue(FolderItemDeleter.deleteRegistered(doc(book), listOf(target(gone), FolderItemDeleter.Target(null) { true }), emptyList(), { null }, lister))
    assertFalse(book.exists())
  }

  @Test
  fun missingBookFolderIsNotAnError() {
    assertTrue(FolderItemDeleter.deleteRegistered(null, emptyList(), emptyList(), { null }, lister))
  }

  @Test
  fun failedDeleteKeepsRecordAndStillTriesTheRest() {
    val a = file("Book/a.mp3")
    val b = file("Book/b.mp3")
    val locked = file("Locked/c.mp3")
    locked.parentFile!!.setWritable(false)
    assumeFalse("a read-only folder does not stop root", runCatching { File(locked.parentFile!!, "probe").createNewFile() }.getOrDefault(false))
    val ok = FolderItemDeleter.deleteRegistered(doc(File(root, "Book")), listOf(a, locked, b).map { target(it) }, emptyList(), { null }, lister)
    assertFalse(ok)
    assertFalse(a.exists())
    assertFalse(b.exists())
    assertTrue(locked.exists())
    assertTrue(File(root, "Book").exists())
  }

  @Test
  fun unresolvedLinkThatFailsToDeleteFailsTheResult() {
    val book = File(root, "Book").also { it.mkdirs() }
    assertFalse(FolderItemDeleter.deleteRegistered(doc(book), listOf(FolderItemDeleter.Target(null) { false }), emptyList(), { null }, lister))
    assertTrue(book.exists())
  }

  @Test
  fun unreadableFolderIsNotTreatedAsEmpty() {
    val audio = file("Author/Book/book.mp3")
    assertTrue(delete(File(root, "Author/Book"), listOf(audio), File(root, "Author"), never))
    assertFalse(audio.exists())
    assertTrue(File(root, "Author/Book").exists())
    assertTrue(File(root, "Author").exists())
  }

  @Test
  fun interruptedDownloadFilesGoWithTheBook() {
    val audio = file("Author/Book/book.mp3")
    val part = file("Author/Book/.book.mp3.-12345.part")
    val foreign = file("Author/Book/.other.mp3.12.part")
    assertTrue(delete(File(root, "Author/Book"), listOf(audio), File(root, "Author")))
    assertFalse(part.exists())
    assertTrue(foreign.exists())
    assertTrue(File(root, "Author/Book").exists())
  }

  @Test
  fun directoryNamedLikeAPartialSurvives() {
    val audio = file("Book/book.mp3")
    val inner = file("Book/.book.mp3.5.part/keep.txt")
    assertTrue(delete(File(root, "Book"), listOf(audio), null))
    assertTrue(inner.exists())
  }

  @Test
  fun partialThatCannotBeDeletedFailsTheResult() {
    val audio = file("Book/book.mp3")
    val part = file("Locked/.book.mp3.5.part")
    part.parentFile!!.setWritable(false)
    assumeFalse("a read-only folder does not stop root", runCatching { File(part.parentFile!!, "probe").createNewFile() }.getOrDefault(false))
    val failing = FolderItemDeleter.Lister { dir -> lister.children(dir)?.plus(doc(part)) }
    assertFalse(delete(File(root, "Book"), listOf(audio), null, failing))
    assertTrue(File(root, "Book").exists())
  }

  @Test
  fun interruptedDownloadOnlyBookFolderGoes() {
    val audio = file("Book/book.mp3")
    file("Book/.book.mp3.7.part")
    assertTrue(delete(File(root, "Book"), listOf(audio), null))
    assertFalse(File(root, "Book").exists())
  }

  @Test
  fun fileLinkThatIsGoneOrDeletableCountsAsDeleted() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val missing = File(root, "Book/gone.mp3")
    assertTrue(FolderItemDeleter.deleteByUri(ctx, android.net.Uri.fromFile(missing).toString()))
    val present = file("Book/here.mp3")
    assertTrue(FolderItemDeleter.deleteByUri(ctx, android.net.Uri.fromFile(present).toString()))
    assertFalse(present.exists())
    val uri = android.net.Uri.fromFile(missing)
    val resolved = com.anggrayudi.storage.file.DocumentFileCompat.fromUri(ctx, uri)
    val ok = FolderItemDeleter.deleteRegistered(doc(File(root, "Book")), listOf(FolderItemDeleter.Target(resolved) { FolderItemDeleter.deleteByUri(ctx, uri.toString()) }), emptyList(), { null }, lister)
    assertTrue(ok)
  }

  @Test
  fun listerReportsUnlistableAsNull() {
    assertNull(lister.children(doc(File(root, "missing"))))
    assertEquals(0, lister.children(doc(root))!!.size)
  }

  @Test
  fun partialThatStaysKeepsTheRegisteredFiles() {
    val audio = file("Book/book.mp3")
    val part = file("Locked/.book.mp3.5.part")
    part.parentFile!!.setWritable(false)
    assumeFalse("a read-only folder does not stop root", runCatching { File(part.parentFile!!, "probe").createNewFile() }.getOrDefault(false))
    val withPart = FolderItemDeleter.Lister { dir -> lister.children(dir)?.plus(doc(part)) }
    assertFalse(delete(File(root, "Book"), listOf(audio), null, withPart))
    assertTrue("the registered file stays so a repeat still sees the item", audio.exists())
  }

  @Test
  fun interruptedNameRule() {
    val names = listOf("a.mp3")
    assertTrue(FolderItemDeleter.isInterruptedDownload(".a.mp3.5.part", names))
    assertTrue(FolderItemDeleter.isInterruptedDownload(".a.mp3.-5.part", names))
    assertTrue(FolderItemDeleter.isInterruptedDownload(".a.mp3.5.part.mp3", names))
    assertFalse(FolderItemDeleter.isInterruptedDownload(".b.mp3.5.part.mp3", names))
    assertFalse(FolderItemDeleter.isInterruptedDownload(".a.mp3.5.part.m-p3", names))
    assertFalse(FolderItemDeleter.isInterruptedDownload(".a.mp3.x.part", names))
    assertFalse(FolderItemDeleter.isInterruptedDownload("a.mp3.5.part", names))
    assertFalse(FolderItemDeleter.isInterruptedDownload(".b.mp3.5.part", names))
    assertFalse(FolderItemDeleter.isInterruptedDownload(null, names))
    for (sep in listOf("\n", "\u2028", "\u2029")) {
      assertTrue(FolderItemDeleter.isInterruptedDownload(".a${sep}b.mp3.5.part", listOf("a${sep}b.mp3")))
      assertTrue(FolderItemDeleter.isInterruptedDownload(".a${sep}b.mp3.5.part.mp3", listOf("a${sep}b.mp3")))
      assertFalse(FolderItemDeleter.isInterruptedDownload(".a${sep}b.mp3.5.part", names))
    }
  }

  @Test
  fun parentSubfolderRule() {
    val folder = "/storage/Audiobooks"
    assertNull(FolderItemDeleter.parentSubfolderToDelete("$folder/Book", folder, emptyList()))
    assertEquals("$folder/Author", FolderItemDeleter.parentSubfolderToDelete("$folder/Author/Book", folder, listOf("$folder/Other/X")))
    assertNull(FolderItemDeleter.parentSubfolderToDelete("$folder/Author/Book", folder, listOf("$folder/Author/Book2")))
    assertEquals("$folder/Author", FolderItemDeleter.parentSubfolderToDelete("$folder/Author/Book", folder, listOf("$folder/Author 2/X")))
    assertNull(FolderItemDeleter.parentSubfolderToDelete("Book", folder, emptyList()))
    assertNull(FolderItemDeleter.parentSubfolderToDelete("/Book", folder, emptyList()))
    assertNull(FolderItemDeleter.parentSubfolderToDelete("$folder/Book", "$folder/", emptyList()))
    assertNull(FolderItemDeleter.parentSubfolderToDelete("$folder//Book", "$folder/", emptyList()))
  }

  @Test
  fun emptyDirRule() {
    assertTrue(FolderItemDeleter.isRemovableEmptyDir(true, 0))
    assertFalse(FolderItemDeleter.isRemovableEmptyDir(true, 1))
    assertFalse(FolderItemDeleter.isRemovableEmptyDir(true, null))
    assertFalse(FolderItemDeleter.isRemovableEmptyDir(false, 0))
  }
}
