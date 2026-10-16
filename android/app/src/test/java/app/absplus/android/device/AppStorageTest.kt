package app.absplus.android.device

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStorageTest {
  private val internalDir = File("/data/user/0/app.absplus.android/files")
  private val primaryDir = File("/storage/emulated/0/Android/data/app.absplus.android/files")
  private val sdDir = File("/storage/79EC-9B33/Android/data/app.absplus.android/files")

  private class FakeProbe(private val vols: List<AppVolume>, private val internal: File) : VolumeProbe {
    var volumeCalls = 0
    override fun volumes(): List<AppVolume> {
      volumeCalls++
      return vols
    }
    override fun internalFilesDir() = internal
  }

  private fun primary() = AppVolume(null, primaryDir, removable = false, mounted = true, writable = true)
  private fun sd(uuid: String = "79EC-9B33", mounted: Boolean = true, writable: Boolean = true) =
          AppVolume(uuid, sdDir, removable = true, mounted = mounted, writable = writable)
  private fun probe(vararg vols: AppVolume) = FakeProbe(vols.toList(), internalDir)

  private val sdFolder = AppStorage.folderFor(StorageTarget.Sd("79EC-9B33"), "book")
  private val internalFolder = AppStorage.folderFor(StorageTarget.Internal, "book")

  @Test
  fun settingsSdCard() {
    val configured = StorageTarget.Sd("79EC-9B33")
    val other = sd(uuid = "1111-2222")
    assertEquals(sd(), AppStorage.settingsSdCard(listOf(primary(), other, sd()), configured))
    assertEquals("another inserted card is offered", other, AppStorage.settingsSdCard(listOf(primary(), other), configured))
    val missing = AppStorage.settingsSdCard(listOf(primary()), configured)
    assertEquals("79EC-9B33", missing?.uuid)
    assertFalse("a missing configured card is reported unmounted", missing!!.mounted)
    assertNull(AppStorage.settingsSdCard(listOf(primary()), StorageTarget.Internal))
    assertEquals(other, AppStorage.settingsSdCard(listOf(primary(), other), StorageTarget.Internal))
  }

  @Test
  fun ownershipCoversInternalAndSd() {
    assertTrue(AppStorage.isAppStorage("internal-podcast"))
    assertTrue(AppStorage.isAppStorage("appsd-79EC-9B33-book"))
    assertFalse(AppStorage.isAppStorage("cHJpbWFyeTpBdWRpb2Jvb2tzL0FCU1RFU1Q="))
  }

  @Test
  fun uuidRoundTrip() {
    assertEquals("79EC-9B33", AppStorage.uuidOf(AppStorage.appsdFolderId("79EC-9B33", "podcast")))
    assertNull(AppStorage.uuidOf("internal-book"))
    assertNull(AppStorage.uuidOf("appsd-book"))
  }

  @Test
  fun availability() {
    val unmounted = probe(primary(), sd(mounted = false, writable = false))
    assertFalse(AppStorage.isAvailable(sdFolder, unmounted))
    assertNull(AppStorage.downloadsRoot(sdFolder, unmounted))

    assertFalse(AppStorage.isAvailable(sdFolder, probe(primary(), sd(writable = false))))
    assertFalse(AppStorage.isAvailable(sdFolder, probe(primary(), sd(uuid = "1111-2222"))))
    assertNull(AppStorage.downloadsRoot(sdFolder, probe(primary(), sd(uuid = "1111-2222"))))

    val mounted = probe(primary(), sd())
    assertTrue(AppStorage.isAvailable(sdFolder, mounted))
    assertEquals(File(sdDir, "downloads"), AppStorage.downloadsRoot(sdFolder, mounted))
    assertEquals(File(sdDir, "download-staging"), AppStorage.stagingRoot(sdFolder, mounted))

    val untouched = probe(sd(mounted = false))
    assertTrue(AppStorage.isAvailable(internalFolder, untouched))
    assertEquals(File(internalDir, "downloads"), AppStorage.downloadsRoot(internalFolder, untouched))
    assertEquals(0, untouched.volumeCalls)
  }

  @Test
  fun storageKeyPerVolume() {
    val p = probe(primary(), sd())
    val onPrimary = AppStorage.storageKey(File(primaryDir, "downloads/a.mp3"), p)
    val onSd = AppStorage.storageKey(File(sdDir, "downloads/a.mp3"), p)
    assertEquals("volume:primary", onPrimary)
    assertEquals("volume:79EC-9B33", onSd)
    assertNotEquals(onPrimary, onSd)
    assertEquals("internal", AppStorage.storageKey(File(internalDir, "downloads/a.mp3"), p))
    assertEquals("other", AppStorage.storageKey(File(internalDir.parentFile, "files2/a.mp3"), p))
    assertFalse(AppStorage.isUnder(File(internalDir.parentFile, "files2"), internalDir))
  }

  @Test
  fun pathNormalization() {
    assertEquals("/a/b/c", AppStorage.normalizedPath(File("/a//b/./c/")))
    assertEquals("/a/c", AppStorage.normalizedPath(File("/a/b/../c")))
    assertEquals("above the root stays at the root", "/x", AppStorage.normalizedPath(File("/../../x")))
    assertEquals("/", AppStorage.normalizedPath(File("/")))
    assertEquals(AppStorage.normalizedPath(File("/a/b")), AppStorage.normalizedPath(File("/a/b/c/..")))
    // Same results as java.nio on the JVM, which the device code cannot use below API 26
    val samples = listOf("/a//b/./c/", "/a/b/../c", "/../../x", "/", "/data/files", "/data/files/../x", "/data/files2", "/data/./files//d/")
    for (p in samples) {
      assertEquals(p, File(p).absoluteFile.toPath().normalize().toString(), AppStorage.normalizedPath(File(p)))
      for (r in samples) {
        val nio = File(p).absoluteFile.toPath().normalize().startsWith(File(r).absoluteFile.toPath().normalize())
        assertEquals("$p under $r", nio, AppStorage.isUnder(File(p), File(r)))
      }
    }

    val files = File("/data/files")
    assertTrue("a root is under itself", AppStorage.isUnder(File("/data/files"), files))
    assertTrue(AppStorage.isUnder(File("/data//files/./downloads/"), File("/data/files/")))
    assertTrue(AppStorage.isUnder(File("/data/files/downloads/x/../y"), files))
    assertFalse("a sibling sharing the prefix is not under it", AppStorage.isUnder(File("/data/files2"), files))
    assertFalse("..-escape is not under it", AppStorage.isUnder(File("/data/files/../x"), files))
    assertFalse("the parent is not under it", AppStorage.isUnder(File("/data"), files))

    assertEquals("", AppStorage.relativePath(File("/data/files/"), files))
    assertEquals("d/a.mp3", AppStorage.relativePath(File("/data/files//d/./a.mp3"), files))
    assertNull(AppStorage.relativePath(File("/data/files2/a.mp3"), files))
  }

  @Test
  fun removableVolumeAndFolders() {
    assertEquals(sd(), AppStorage.removableVolume(probe(primary(), sd(mounted = false), sd())))
    assertNull(AppStorage.removableVolume(probe(primary(), sd(mounted = false))))
    assertNull("a read-only card is not a target", AppStorage.removableVolume(probe(primary(), sd(writable = false))))
    assertEquals(sd(), AppStorage.removableVolume(probe(primary(), sd(writable = false), sd())))
    assertEquals("appsd-79EC-9B33-book", sdFolder.id)
    assertEquals("appsd", sdFolder.storageType)
    assertEquals("SD card", sdFolder.name)
    assertEquals("internal-book", internalFolder.id)
    assertEquals("internal", internalFolder.storageType)
  }
}
