package app.absplus.android.device

import app.absplus.android.data.LocalFolder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FolderAccess: the one "can the app still write to this device folder" rule, shared by the
 * downloader and the Local Folders permission check.
 */
class FolderAccessTest {
  private val url = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks%2FABSTEST/document/primary%3AAudiobooks%2FABSTEST"
  private val path = "/storage/emulated/0/Audiobooks/ABSTEST"
  private val abstest = LocalFolder("cHJpbWFyeTpBdWRpb2Jvb2tzL0FCU1RFU1Q=", "ABSTEST", url, "Audiobooks/ABSTEST", path, "PRIMARY", "book")

  /** Records what each check was given; models SimpleStorage, which only understands file paths. */
  private class FakeProbe(
          val pathOk: Boolean = true,
          val grant: Boolean = false,
          val writable: Boolean = true
  ) : FolderAccess.Probe {
    val pathsAsked = mutableListOf<String>()
    override fun pathAccess(path: String): Boolean {
      pathsAsked.add(path)
      return pathOk && path.startsWith("/")
    }
    override fun treeGrant(contentUrl: String) = grant
    override fun folderWritable(contentUrl: String) = writable
  }

  /** Counts lookups; app storage checks must not touch volumes for internal storage. */
  private class FakeVolumes(private val vols: List<AppVolume> = emptyList()) : VolumeProbe {
    var calls = 0
    override fun volumes(): List<AppVolume> {
      calls++
      return vols
    }
    override fun internalFilesDir() = File("/data/user/0/app.absplus.android/files")
  }

  private val noVolumes = FakeVolumes()

  @Test
  fun aValidDeviceFolderPassesThroughItsFilePath() {
    val probe = FakeProbe()
    assertTrue(FolderAccess.canWrite(abstest, probe, noVolumes))
    // The path-based check never receives the content:// URL (the bug that made every check fail)
    assertEquals(listOf(path), probe.pathsAsked)
  }

  @Test
  fun aFolderWithoutAResolvablePathUsesItsPersistedTreeGrant() {
    val pathless = abstest.copy(absolutePath = "")
    assertTrue(FolderAccess.canWrite(pathless, FakeProbe(grant = true), noVolumes))
    assertFalse(FolderAccess.canWrite(pathless, FakeProbe(grant = false), noVolumes))
    assertTrue(FakeProbe().also { FolderAccess.canWrite(pathless, it, noVolumes) }.pathsAsked.isEmpty())
  }

  @Test
  fun lostOrDeletedFoldersStillFail() {
    // Grant revoked
    assertFalse(FolderAccess.canWrite(abstest, FakeProbe(pathOk = false, grant = false), noVolumes))
    // Grant still there but the folder was deleted or is read-only
    assertFalse(FolderAccess.canWrite(abstest, FakeProbe(pathOk = true, writable = false), noVolumes))
    assertFalse(FolderAccess.canWrite(abstest, FakeProbe(grant = true, pathOk = false, writable = false), noVolumes))
    // No folder address at all
    assertFalse(FolderAccess.canWrite(abstest.copy(contentUrl = ""), FakeProbe(), noVolumes))
  }

  @Test
  fun internalAppStorageNeedsNoDeviceAccess() {
    val internal = LocalFolder("internal-book", "Internal App Storage", "", "", "", "internal", "book")
    val probe = FakeProbe(pathOk = false, grant = false, writable = false)
    assertTrue(FolderAccess.canWrite(internal, probe, noVolumes))
    assertTrue(probe.pathsAsked.isEmpty())
  }

  @Test
  fun thePermissionCheckUsesTheSavedFolderForItsUrl() {
    val other = abstest.copy(id = "other", contentUrl = "content://other", absolutePath = "/storage/emulated/0/Other")
    // Existing folder ids and records are used as they are, so the check gets the real file path
    assertEquals(abstest, FolderAccess.folderForUrl(url, listOf(other, abstest)))
    // An unsaved URL becomes a path-less stand-in, judged by its tree grant
    val unsaved = FolderAccess.folderForUrl("content://new", listOf(abstest))
    assertEquals("", unsaved.absolutePath)
    assertFalse(FolderAccess.canWrite(unsaved, FakeProbe(grant = false), noVolumes))
  }

  @Test
  fun appsdFollowsVolume() {
    val sd = LocalFolder("appsd-79EC-9B33-book", "SD card", "", "", "", "appsd", "book")
    val dir = File("/storage/79EC-9B33/Android/data/app.absplus.android/files")
    fun vol(mounted: Boolean, writable: Boolean) = AppVolume("79EC-9B33", dir, true, mounted, writable)
    val saf = object : FolderAccess.Probe {
      override fun pathAccess(path: String) = throw AssertionError("SAF path check for app storage")
      override fun treeGrant(contentUrl: String) = throw AssertionError("SAF grant check for app storage")
      override fun folderWritable(contentUrl: String) = throw AssertionError("SAF folder check for app storage")
    }

    assertFalse(FolderAccess.canWrite(sd, saf, FakeVolumes()))
    assertFalse(FolderAccess.canWrite(sd, saf, FakeVolumes(listOf(vol(mounted = false, writable = false)))))
    assertFalse(FolderAccess.canWrite(sd, saf, FakeVolumes(listOf(vol(mounted = true, writable = false)))))
    assertTrue(FolderAccess.canWrite(sd, saf, FakeVolumes(listOf(vol(mounted = true, writable = true)))))

    val internal = LocalFolder("internal-book", "Internal App Storage", "", "", "", "internal", "book")
    val volumes = FakeVolumes(listOf(vol(mounted = false, writable = false)))
    assertTrue(FolderAccess.canWrite(internal, FakeProbe(pathOk = false), volumes))
    assertEquals(0, volumes.calls)
  }
}
