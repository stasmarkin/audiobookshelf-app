package app.absplus.android.managers

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafDownloadPublisherTest {
  private lateinit var context: Context
  private lateinit var base: File
  private lateinit var root: DocumentFile
  private lateinit var staging: File
  private val bytes = "SYNTHETIC-COMPLETE-MEDIA".toByteArray()

  @Before fun setup() {
    context = ApplicationProvider.getApplicationContext()
    base = File(context.cacheDir, "bounded-saf-${UUID.randomUUID()}").apply { mkdirs() }
    File(base, "selected/show").mkdirs()
    FakeDocumentTreeProvider.reset(base)
    Robolectric.buildContentProvider(FakeDocumentTreeProvider::class.java).create(ProviderInfo().apply {
      authority = FakeDocumentTreeProvider.AUTHORITY; exported = true; grantUriPermissions = true
    })
    root = DocumentFile.fromTreeUri(context, DocumentsContract.buildTreeDocumentUri(FakeDocumentTreeProvider.AUTHORITY, "primary:selected"))!!
    staging = File(base, "private-staging").apply { writeBytes(bytes) }
  }

  private fun publish(path: String = "show", name: String = "episode.mp3"): Uri =
          SafDownloadPublisher(context).publish(root, path, name, "audio/mpeg", staging)

  private fun fails(path: String = "show", name: String = "episode.mp3") {
    try { publish(path, name); fail("must refuse unsafe publication") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
    assertArrayEquals(bytes, staging.readBytes())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  @Test fun returnedAlternateNameIsAuthoritativeAndBytesAreVerified() {
    FakeDocumentTreeProvider.alternateFinalName = "provider-generated.mp3"
    val uri = publish()
    assertEquals("primary:selected/show/provider-generated.mp3", DocumentsContract.getDocumentId(uri))
    assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    assertFalse(File(base, "selected/show/episode.mp3").exists())
    assertTrue(FakeDocumentTreeProvider.deletes.isEmpty())
  }

  @Test fun returnedExistingOrOutsideDocumentIsNeverWrittenOrDeleted() {
    val keep = File(base, "selected/show/keep.mp3").apply { writeText("KEEP") }
    FakeDocumentTreeProvider.createResultId = "primary:selected/show/keep.mp3"
    fails()
    assertEquals("KEEP", keep.readText())
    val outside = File(base, "outside.mp3").apply { writeText("OUTSIDE") }
    FakeDocumentTreeProvider.createResultId = "primary:outside.mp3"
    fails()
    assertEquals("OUTSIDE", outside.readText())
    assertTrue(FakeDocumentTreeProvider.renames.isEmpty())
  }

  @Test fun failedListingsCannotAuthorizePublication() {
    for (fault in listOf("exception", "null", "loading", "error")) {
      FakeDocumentTreeProvider.childQueryFailure = fault
      try { fails() } catch (_: SecurityException) { }
      assertTrue(File(base, "selected/show").listFiles()!!.isEmpty())
    }
    FakeDocumentTreeProvider.childQueryFailure = null
    assertNotNull(publish())
  }

  @Test fun relativePathAndFilenameCannotEscapeSelectedTree() {
    for (path in listOf("../outside", "show/../outside", "show\\outside")) fails(path)
    for (name in listOf("../outside.mp3", "..", "show/other.mp3", "show\\other.mp3")) fails(name = name)
    assertFalse(File(base, "outside").exists())
    assertTrue(FakeDocumentTreeProvider.renames.isEmpty())
  }

  @Test fun readbackCorruptionCannotBeReportedAsSuccessfulPublication() {
    FakeDocumentTreeProvider.corruptOnRead = true
    fails()
    assertEquals(1, File(base, "selected/show").listFiles()!!.size)
  }

  @Test fun aFileOccupyingTheRequiredSubfolderIsLeftUnchanged() {
    val file = File(base, "selected/file").apply { writeText("KEEP") }
    fails("file/child")
    assertEquals("KEEP", file.readText())
    assertTrue(FakeDocumentTreeProvider.renames.isEmpty())
  }
}
