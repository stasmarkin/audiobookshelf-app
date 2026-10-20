package app.absplus.android.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.managers.DbManager
import app.absplus.android.managers.LocalItemLocks
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalItemDeleterTest {
  private lateinit var ctx: Context
  private lateinit var root: File
  private lateinit var probe: VolumeProbe

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    DbManager.initialize(ctx)
    root = Files.createTempDirectory("appfiles").toFile()
    probe = probeWith(emptyList())
  }

  private fun probeWith(volumes: List<AppVolume>) =
          object : VolumeProbe {
            override fun volumes() = volumes
            override fun internalFilesDir() = root
          }

  private fun savedItem(id: String, folderId: String, dir: File): LocalLibraryItem {
    val book = Book(
      BookMetadata("Title", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null),
      null, emptyList(), null, null, mutableListOf(), null, null, null, null
    )
    val item = LocalLibraryItem(
      id, folderId, "", dir.absolutePath, "file://${dir.absolutePath}", false, "book", book,
      mutableListOf(LocalFile("f1", "a.mp3", "", "", File(dir, "a.mp3").absolutePath, "audio/mpeg", 3)),
      null, null, true, "cfg", "https://s", "u", "srv-$id"
    )
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    return item
  }

  private fun itemDir(name: String) = File(root, "downloads/$name").apply { mkdirs() }

  private fun delete(id: String, auto: Boolean, p: VolumeProbe = probe) = LocalItemDeleter.deleteItem(ctx, id, auto, p)

  @Test
  fun `deletes internal item folder and record`() {
    val dir = itemDir("one")
    File(dir, "a.mp3").writeText("abc")
    savedItem("local_1", "internal-downloads", dir)

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_1", auto = true))
    assertFalse("folder is gone", dir.exists())
    assertNull("record is gone", DeviceManager.dbManager.getLocalLibraryItem("local_1"))
  }

  @Test
  fun `already missing internal folder still removes the record`() {
    val dir = File(root, "downloads/gone")
    savedItem("local_2", "internal-downloads", dir)

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_2", auto = true))
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_2"))
  }

  @Test
  fun `unknown item counts as deleted`() {
    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("no_such_item", auto = true))
  }

  @Test
  fun `a path outside app storage is not deleted and the record stays`() {
    val outside = Files.createTempDirectory("outside").toFile()
    File(outside, "a.mp3").writeText("abc")
    savedItem("local_out", "internal-downloads", outside)

    assertEquals(LocalItemDeleter.Outcome.FAILED, delete("local_out", auto = true))
    assertTrue(File(outside, "a.mp3").exists())
    assertNotNull(DeviceManager.dbManager.getLocalLibraryItem("local_out"))
  }

  @Test
  fun `an item on a missing SD card keeps its record and its files`() {
    val sdDir = Files.createTempDirectory("sd").toFile()
    val dir = File(sdDir, "downloads/book").apply { mkdirs() }
    File(dir, "a.mp3").writeText("abc")
    savedItem("local_sd", AppStorage.appsdFolderId("79EC-9B33", "book"), dir)

    // The probe knows no volume, so the card counts as removed
    assertEquals(LocalItemDeleter.Outcome.STORAGE_UNAVAILABLE, delete("local_sd", auto = true))
    assertTrue(File(dir, "a.mp3").exists())
    assertNotNull(DeviceManager.dbManager.getLocalLibraryItem("local_sd"))
  }

  @Test
  fun `an item on a mounted SD card is deleted with its files`() {
    val sdFiles = Files.createTempDirectory("sd").toFile()
    val dir = File(sdFiles, "downloads/book").apply { mkdirs() }
    File(dir, "a.mp3").writeText("abc")
    savedItem("local_sd2", AppStorage.appsdFolderId("79EC-9B33", "book"), dir)
    val sdProbe = probeWith(listOf(AppVolume("79EC-9B33", sdFiles, removable = true, mounted = true, writable = true)))

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_sd2", auto = true, p = sdProbe))
    assertFalse(dir.exists())
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_sd2"))
  }

  @Test
  fun `deleting an item waits for its lock`() {
    val dir = itemDir("locked")
    savedItem("local_lock", "internal-downloads", dir)
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val holder = Thread { LocalItemLocks.withItem("local_lock") { held.countDown(); release.await(5, TimeUnit.SECONDS) } }
    holder.start()
    assertTrue(held.await(5, TimeUnit.SECONDS))

    val finished = CountDownLatch(1)
    val deleter = Thread { delete("local_lock", auto = true); finished.countDown() }
    deleter.start()

    assertFalse("delete must wait while the item is locked", finished.await(300, TimeUnit.MILLISECONDS))
    assertTrue(dir.exists())
    release.countDown()
    assertTrue(finished.await(5, TimeUnit.SECONDS))
    assertFalse(dir.exists())
    holder.join()
  }

  @Test
  fun `deleting an item bumps its version`() {
    val dir = itemDir("versioned")
    savedItem("local_ver", "internal-downloads", dir)
    val before = LocalItemLocks.version("local_ver")

    delete("local_ver", auto = true)

    assertTrue(LocalItemLocks.version("local_ver") > before)
  }

  @Test
  fun `delete track of unknown item fails`() {
    assertTrue(LocalItemDeleter.deleteTrack(ctx, "no_such_item", "f1", null, probe) === LocalItemDeleter.TrackResult.Failed)
  }

  @Test
  fun `delete track of unknown file keeps the item untouched`() {
    val dir = itemDir("two")
    savedItem("local_3", "internal-downloads", dir)

    assertTrue(LocalItemDeleter.deleteTrack(ctx, "local_3", "nope", null, probe) === LocalItemDeleter.TrackResult.Failed)
    assertEquals(1, DeviceManager.dbManager.getLocalLibraryItem("local_3")!!.localFiles.size)
  }

  @Test
  fun `deleting a track removes its file and drops it from the item`() {
    val dir = itemDir("three")
    File(dir, "a.mp3").writeText("abc")
    savedItem("local_4", "internal-downloads", dir)

    val result = LocalItemDeleter.deleteTrack(ctx, "local_4", "f1", null, probe)

    assertTrue(result is LocalItemDeleter.TrackResult.Deleted)
    assertFalse(File(dir, "a.mp3").exists())
    assertTrue(DeviceManager.dbManager.getLocalLibraryItem("local_4")!!.localFiles.isEmpty())
  }

  @Test
  fun `deleting a track whose file is already gone fails and keeps the record`() {
    val dir = itemDir("gone-track")
    savedItem("local_gt", "internal-downloads", dir)

    assertTrue(LocalItemDeleter.deleteTrack(ctx, "local_gt", "f1", null, probe) === LocalItemDeleter.TrackResult.Failed)
    assertEquals(1, DeviceManager.dbManager.getLocalLibraryItem("local_gt")!!.localFiles.size)
  }

  private fun folderItem(id: String, dir: File, fileUrl: File, folderRoot: File = dir): LocalLibraryItem {
    // The picked device folder the item lives in (file:// here: reachable while it exists and is writable)
    DeviceManager.dbManager.saveLocalFolder(app.absplus.android.data.LocalFolder("saf-folder", "SAF", "file://${folderRoot.absolutePath}", "", folderRoot.absolutePath, "external", "book"))
    val item = savedItem(id, "saf-folder", dir)
    item.localFiles[0].contentUrl = "file://${fileUrl.absolutePath}"
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    return item
  }

  @Test
  fun `auto delete in a device folder removes only registered files and keeps the folder with the user's files`() {
    val dir = Files.createTempDirectory("saf").toFile()
    File(dir, "a.mp3").writeText("abc")
    File(dir, "notes.txt").writeText("mine")
    folderItem("local_saf", dir, File(dir, "a.mp3"))

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_saf", auto = true))

    assertFalse(File(dir, "a.mp3").exists())
    assertTrue("the user's file is not touched", File(dir, "notes.txt").exists())
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_saf"))
  }

  @Test
  fun `manual delete in a device folder also keeps the user's files and the folder`() {
    val dir = Files.createTempDirectory("saf").toFile()
    File(dir, "a.mp3").writeText("abc")
    File(dir, "notes.txt").writeText("mine")
    folderItem("local_man", dir, File(dir, "a.mp3"))

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_man", auto = false))

    assertFalse(File(dir, "a.mp3").exists())
    assertTrue("the user's file is not touched", File(dir, "notes.txt").exists())
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_man"))
  }

  @Test
  fun `manual delete drops a record whose saved file link does not open and the file is gone`() {
    val dir = Files.createTempDirectory("saf").toFile()
    File(dir, "notes.txt").writeText("mine")
    folderItem("local_man2", dir, File(dir, "gone.mp3"))

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_man2", auto = false))

    assertTrue(File(dir, "notes.txt").exists())
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_man2"))
  }

  @Test
  fun `auto delete skips a device-folder item whose saved file link does not resolve`() {
    val dir = Files.createTempDirectory("saf").toFile()
    File(dir, "notes.txt").writeText("mine")
    // DocumentFileCompat gives no document for a file:// link to a file that is not there
    folderItem("local_skip", dir, File(dir, "gone.mp3"))

    assertEquals(LocalItemDeleter.Outcome.SKIPPED, delete("local_skip", auto = true))
    assertTrue("nothing of the user's is touched", File(dir, "notes.txt").exists())
    assertNotNull("the record stays", DeviceManager.dbManager.getLocalLibraryItem("local_skip"))
  }

  @Test
  fun `deleting an item forgets the auto delete records kept for it`() {
    val dir = itemDir("meta")
    savedItem("local_meta", "internal-downloads", dir)
    DeviceManager.dbManager.saveAutoDeleteDownloadedAt("local_meta", 5)
    DeviceManager.dbManager.saveAutoDeleteConfirmed("local_meta", app.absplus.android.managers.AutoDeletePolicy.Confirmation(app.absplus.android.managers.AutoDeletePolicy.Identity(null, null, null), 1, true))

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_meta", auto = false))

    assertNull(DeviceManager.dbManager.getAutoDeleteDownloadedAt("local_meta"))
    assertNull(DeviceManager.dbManager.getAutoDeleteConfirmed("local_meta"))
  }

  @Test
  fun `a registered path replaced by a folder is kept, auto skips and manual fails`() {
    val dir = Files.createTempDirectory("saf").toFile()
    val replaced = File(dir, "a.mp3").apply { mkdirs() }
    File(replaced, "inside.txt").writeText("mine")
    folderItem("local_dirfile", dir, replaced)

    assertEquals(LocalItemDeleter.Outcome.SKIPPED, delete("local_dirfile", auto = true))
    assertTrue(File(replaced, "inside.txt").exists())
    assertEquals(LocalItemDeleter.Outcome.FAILED, delete("local_dirfile", auto = false))
    assertTrue(File(replaced, "inside.txt").exists())
    assertNotNull(DeviceManager.dbManager.getLocalLibraryItem("local_dirfile"))
  }

  @Test
  fun `the delete is skipped when the caller no longer allows it under the lock`() {
    val dir = itemDir("guarded")
    File(dir, "a.mp3").writeText("abc")
    savedItem("local_guard", "internal-downloads", dir)

    assertEquals(LocalItemDeleter.Outcome.SKIPPED, LocalItemDeleter.deleteItem(ctx, "local_guard", true, probe) { false })
    assertTrue(File(dir, "a.mp3").exists())
    assertNotNull(DeviceManager.dbManager.getLocalLibraryItem("local_guard"))
  }

  // --- R32-C01/C02/C03 (review corrections) ---

  @Test
  fun `a device-folder delete never removes folders, unregistered files or partial-looking files`() {
    val root = Files.createTempDirectory("picked").toFile()
    val author = File(root, "Author").apply { mkdirs() }
    val book = File(author, "Book").apply { mkdirs() }
    File(book, "a.mp3").writeText("abc")
    File(book, "notes.txt").writeText("mine")
    File(book, ".a.mp3.123456789.part").writeText("not proven ours")
    folderItem("local_c13", book, File(book, "a.mp3"), folderRoot = root)

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_c13", auto = false))

    assertFalse(File(book, "a.mp3").exists())
    assertEquals("mine", File(book, "notes.txt").readText())
    assertEquals("not proven ours", File(book, ".a.mp3.123456789.part").readText())
    assertTrue("book folder kept", book.isDirectory)
    assertTrue("author folder kept", author.isDirectory)
    assertNull(DeviceManager.dbManager.getLocalLibraryItem("local_c13"))
  }

  @Test
  fun `an emptied book folder stays, also for auto delete`() {
    val root = Files.createTempDirectory("picked").toFile()
    val book = File(root, "Book").apply { mkdirs() }
    File(book, "a.mp3").writeText("abc")
    folderItem("local_c1e", book, File(book, "a.mp3"), folderRoot = root)

    assertEquals(LocalItemDeleter.Outcome.DELETED, delete("local_c1e", auto = true))
    assertTrue(book.isDirectory)
  }

  @Test
  fun `a device folder that cannot be reached deletes nothing and keeps the record`() {
    val root = Files.createTempDirectory("picked").toFile()
    val book = File(root, "Book").apply { mkdirs() }
    File(book, "a.mp3").writeText("abc")
    folderItem("local_unr", book, File(book, "a.mp3"), folderRoot = File(root, "gone"))

    assertEquals(LocalItemDeleter.Outcome.STORAGE_UNAVAILABLE, delete("local_unr", auto = false))
    assertEquals("abc", File(book, "a.mp3").readText())
    assertNotNull(DeviceManager.dbManager.getLocalLibraryItem("local_unr"))
  }

  @Test
  fun `deleting a device-folder track whose path became a folder keeps the files inside and the record`() {
    val root = Files.createTempDirectory("picked").toFile()
    val replaced = File(root, "a.mp3").apply { mkdirs() }
    File(replaced, "inside.txt").writeText("mine")
    folderItem("local_trk", root, replaced)

    assertTrue(LocalItemDeleter.deleteTrack(ctx, "local_trk", "f1", null, probe) === LocalItemDeleter.TrackResult.Failed)
    assertEquals("mine", File(replaced, "inside.txt").readText())
    assertEquals(1, DeviceManager.dbManager.getLocalLibraryItem("local_trk")!!.localFiles.size)
  }

  @Test
  fun `deleting a device-folder track removes only that regular file`() {
    val root = Files.createTempDirectory("picked").toFile()
    File(root, "a.mp3").writeText("abc")
    File(root, "notes.txt").writeText("mine")
    folderItem("local_trk2", root, File(root, "a.mp3"))

    assertTrue(LocalItemDeleter.deleteTrack(ctx, "local_trk2", "f1", null, probe) is LocalItemDeleter.TrackResult.Deleted)
    assertFalse(File(root, "a.mp3").exists())
    assertEquals("mine", File(root, "notes.txt").readText())
  }
}
