package app.absplus.android.device

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.device.FolderFileDeletion.Kind
import app.absplus.android.device.FolderFileDeletion.Result
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting a download in a user's device folder: registered regular files only, never a folder, unknown never success
 * (R32-C01/C02/C03 for the inherited manual delete). Adversarial providers are simulated; real files cover file:// URIs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderFileDeletionTest {
  /** A scripted provider: kinds per URI (a list is answered in order, the last one repeated), recorded deletes. */
  private class FakeDocs(
    var reachable: List<Boolean> = listOf(true),
    val kinds: MutableMap<String, MutableList<Kind>> = mutableMapOf(),
    val deleteAnswers: Map<String, Boolean> = emptyMap(),
    val removeOnDelete: Boolean = true
  ) : FolderFileDeletion.Docs {
    val deleted = mutableListOf<String>()
    private var reachableCalls = 0
    override fun folderReachable(): Boolean = reachable[minOf(reachableCalls++, reachable.size - 1)]
    override fun kind(uri: String): Kind {
      val list = kinds.getValue(uri)
      return if (list.size > 1) list.removeAt(0) else list[0]
    }
    override fun deleteFile(uri: String): Boolean {
      deleted += uri
      val ok = deleteAnswers[uri] ?: true
      if (ok && removeOnDelete) kinds[uri] = mutableListOf(Kind.MISSING)
      return ok
    }
  }

  private fun files(vararg pairs: Pair<String, Kind>) = pairs.associate { it.first to mutableListOf(it.second) }.toMutableMap()

  @Test fun registeredRegularFilesAreDeleted() {
    val docs = FakeDocs(kinds = files("a" to Kind.FILE, "b" to Kind.FILE))
    assertEquals(Result.DELETED, FolderFileDeletion.deleteFiles(listOf("a", "b"), docs))
    assertEquals(listOf("a", "b"), docs.deleted)
  }

  @Test fun aFolderInPlaceOfARegisteredFileStopsEverythingBeforeAnyDelete() {
    val docs = FakeDocs(kinds = files("a" to Kind.FILE, "b" to Kind.DIRECTORY))
    assertEquals(Result.FAILED, FolderFileDeletion.deleteFiles(listOf("a", "b"), docs))
    assertTrue("no delete at all", docs.deleted.isEmpty())
  }

  @Test fun anEntryOfUnknownTypeFailsClosed() {
    val docs = FakeDocs(kinds = files("a" to Kind.UNKNOWN))
    assertEquals(Result.FAILED, FolderFileDeletion.deleteFiles(listOf("a"), docs))
    assertTrue(docs.deleted.isEmpty())
  }

  @Test fun anUnreachableFolderDeletesNothingAndIsNotSuccess() {
    // An SD card that is out: every file looks missing, which must not read as "already deleted"
    val docs = FakeDocs(reachable = listOf(false), kinds = files("a" to Kind.MISSING))
    assertEquals(Result.UNAVAILABLE, FolderFileDeletion.deleteFiles(listOf("a"), docs))
    assertTrue(docs.deleted.isEmpty())
  }

  @Test fun aFileReplacedByAFolderBetweenTheChecksIsNotDeleted() {
    // FILE at the first look, DIRECTORY right before its delete, still DIRECTORY when the outcome is checked
    val docs = FakeDocs(kinds = mutableMapOf("a" to mutableListOf(Kind.FILE, Kind.DIRECTORY, Kind.DIRECTORY)))
    assertEquals(Result.FAILED, FolderFileDeletion.deleteFiles(listOf("a"), docs))
    assertTrue(docs.deleted.isEmpty())
  }

  @Test fun aRefusedDeleteIsFailure() {
    val docs = FakeDocs(kinds = files("a" to Kind.FILE), deleteAnswers = mapOf("a" to false))
    assertEquals(Result.FAILED, FolderFileDeletion.deleteFiles(listOf("a"), docs))
  }

  @Test fun aProviderThatSaysDeletedButKeepsTheFileIsFailure() {
    val docs = FakeDocs(kinds = files("a" to Kind.FILE), removeOnDelete = false)
    assertEquals(Result.FAILED, FolderFileDeletion.deleteFiles(listOf("a"), docs))
  }

  @Test fun theFolderGoingAwayDuringTheDeleteIsNotSuccess() {
    // Reachable before, gone after (card pulled mid-delete): the record must stay
    val docs = FakeDocs(reachable = listOf(true, false), kinds = files("a" to Kind.FILE, "b" to Kind.FILE))
    assertEquals(Result.UNAVAILABLE, FolderFileDeletion.deleteFiles(listOf("a", "b"), docs))
  }

  @Test fun alreadyMissingFilesInAReachableFolderCountAsDeleted() {
    val docs = FakeDocs(kinds = files("a" to Kind.MISSING, "b" to Kind.FILE))
    assertEquals(Result.DELETED, FolderFileDeletion.deleteFiles(listOf("a", "b"), docs))
    assertEquals(listOf("b"), docs.deleted)
  }

  // --- Real files (file:// URIs) through the Android implementation ---

  private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

  /** The Android kind/delete with the folder reachable (FolderAccess needs a real grant, covered by FolderAccessTest). */
  private fun realDocs(): FolderFileDeletion.Docs {
    val android = FolderFileDeletion.AndroidDocs(ctx, null)
    return object : FolderFileDeletion.Docs {
      override fun folderReachable() = true
      override fun kind(uri: String) = android.kind(uri)
      override fun deleteFile(uri: String) = android.deleteFile(uri)
    }
  }

  private fun uri(f: File) = Uri.fromFile(f).toString()

  @Test fun characterizationTheOldDocumentFileDeleteEmptiesAFolder() {
    // Why the old code was dangerous: DocumentFile.delete() on a file:// folder removes what is inside
    val dir = Files.createTempDirectory("abs-old").toFile()
    val sentinel = File(dir, "notes.txt").apply { writeText("user data") }
    assertTrue(DocumentFile.fromFile(dir).delete())
    assertFalse(sentinel.exists())
  }

  @Test fun aRegisteredTrackReplacedByAFolderKeepsTheUsersFiles() {
    val book = Files.createTempDirectory("abs-book").toFile()
    val track = File(book, "book.mp3").apply { mkdirs() } // the registered file's path is now a folder
    val sentinel = File(track, "notes.txt").apply { writeText("user data") }
    assertEquals(Result.FAILED, FolderFileDeletion.deleteFiles(listOf(uri(track)), realDocs()))
    assertEquals("user data", sentinel.readText())
    assertTrue(track.isDirectory)
  }

  @Test fun onlyRegisteredFilesGoAndFoldersAndOtherFilesStay() {
    val folder = Files.createTempDirectory("abs-folder").toFile()
    val author = File(folder, "Author").apply { mkdirs() }
    val book = File(author, "Book").apply { mkdirs() }
    val a = File(book, "01.mp3").apply { writeText("a") }
    val b = File(book, "cover.jpg").apply { writeText("b") }
    val notes = File(book, "notes.txt").apply { writeText("user data") }
    val partial = File(book, ".01.mp3.123456789.part").apply { writeText("not proven ours") }
    assertEquals(Result.DELETED, FolderFileDeletion.deleteFiles(listOf(uri(a), uri(b)), realDocs()))
    assertFalse(a.exists())
    assertFalse(b.exists())
    assertEquals("user data", notes.readText())
    assertEquals("not proven ours", partial.readText())
    assertTrue("the book folder is never deleted", book.isDirectory)
    assertTrue("nor the author folder", author.isDirectory)
  }

  @Test fun aFileFolderThatIsGoneIsUnavailableAndAnExistingOneIsReachable() {
    val dir = Files.createTempDirectory("abs-reach").toFile()
    val folder = app.absplus.android.data.LocalFolder("f", "F", uri(dir), "", dir.absolutePath, "external", "book")
    assertTrue(FolderFileDeletion.AndroidDocs(ctx, folder).folderReachable())
    dir.deleteRecursively()
    assertFalse(FolderFileDeletion.AndroidDocs(ctx, folder).folderReachable())
    assertFalse("no saved folder record: not reachable", FolderFileDeletion.AndroidDocs(ctx, null).folderReachable())
  }

  @Test fun anEmptyBookFolderIsLeftInPlace() {
    val book = Files.createTempDirectory("abs-empty").toFile()
    val a = File(book, "01.mp3").apply { writeText("a") }
    assertEquals(Result.DELETED, FolderFileDeletion.deleteFiles(listOf(uri(a)), realDocs()))
    assertTrue(book.isDirectory)
  }
}
