package app.absplus.android.managers

import app.absplus.android.data.LocalFolder
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.device.AppStorage
import app.absplus.android.device.AppVolume
import app.absplus.android.device.StorageTarget
import app.absplus.android.device.VolumeProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadFolderResolverTest {
  @get:Rule val tmp = TemporaryFolder()

  private val uuid = "79EC-9B33"
  private val sd = StorageTarget.Sd(uuid)
  private val safId = "cHJpbWFyeTpBdWRpb2Jvb2tzL0FCU1RFU1Q="

  private class FakeProbe(private val vols: List<AppVolume>, private val internal: File) : VolumeProbe {
    override fun volumes() = vols
    override fun internalFilesDir() = internal
  }

  private fun probe(sdMounted: Boolean = true, sdPresent: Boolean = true): VolumeProbe {
    val vols = mutableListOf(AppVolume(null, tmp.newFolder(), removable = false, mounted = true, writable = true))
    if (sdPresent) vols.add(AppVolume(uuid, tmp.newFolder(), removable = true, mounted = sdMounted, writable = sdMounted))
    return FakeProbe(vols, tmp.newFolder())
  }

  private fun saf(mediaType: String) = LocalFolder(safId, "Audiobooks", "content://x", "", "/storage/emulated/0/Audiobooks", "external", mediaType)

  private fun item(folderId: String, mediaType: String) =
          LocalLibraryItem(
                  "local_li", folderId, "", "", "", false, mediaType, MediaType(MediaTypeMetadata("Title", false), null),
                  mutableListOf(), null, null, true, "server", null, null, "li"
          )

  private fun resolve(
          mediaType: String,
          explicit: String?,
          existing: LocalLibraryItem?,
          setting: StorageTarget,
          probe: VolumeProbe,
          folders: List<LocalFolder>,
          queued: String? = null
  ): FolderResolution {
    val byId = folders.associateBy { it.id }
    return DownloadFolderResolver.resolveFolder(mediaType, explicit, existing, queued, setting, probe) { byId[it] }
  }

  @Test
  fun existingItemFolderWins() {
    val internal = AppStorage.folderFor(StorageTarget.Internal, "podcast")
    val r = resolve("podcast", null, item(internal.id, "podcast"), sd, probe(), listOf(internal))
    assertEquals(FolderResolution.Existing(internal), r)
  }

  @Test
  fun existingSafFolderKept() {
    val folder = saf("podcast")
    val r = resolve("podcast", null, item(safId, "podcast"), sd, probe(), listOf(folder))
    assertEquals(FolderResolution.Existing(folder), r)
  }

  @Test
  fun newItemCreatesSdFolder() {
    val r = resolve("podcast", null, null, sd, probe(), emptyList())
    r as FolderResolution.Create
    assertEquals("appsd-79EC-9B33-podcast", r.folder.id)
    assertEquals(AppStorage.APPSD_STORAGE_TYPE, r.folder.storageType)
  }

  @Test
  fun internalSetting() {
    val internal = AppStorage.folderFor(StorageTarget.Internal, "book")
    assertEquals(FolderResolution.Existing(internal), resolve("book", null, null, StorageTarget.Internal, probe(), listOf(internal)))
    val created = resolve("book", null, null, StorageTarget.Internal, probe(), emptyList())
    assertEquals(FolderResolution.Create(internal), created)
    assertEquals("internal-book", (created as FolderResolution.Create).folder.id)
  }

  @Test
  fun unavailableSd() {
    assertEquals(FolderResolution.Unavailable, resolve("book", null, null, sd, probe(sdMounted = false), emptyList()))
    assertEquals(FolderResolution.Unavailable, resolve("book", null, null, sd, probe(sdPresent = false), emptyList()))

    val sdFolder = AppStorage.folderFor(sd, "podcast")
    assertEquals("appsd-79EC-9B33-podcast", sdFolder.id)
    val r = resolve("podcast", null, item(sdFolder.id, "podcast"), StorageTarget.Internal, probe(sdMounted = false), listOf(sdFolder))
    assertEquals(FolderResolution.Unavailable, r)
  }

  @Test
  fun explicitAppIdTreatedAsEmpty() {
    val created = resolve("book", "internal-book", null, sd, probe(), emptyList())
    assertEquals("appsd-79EC-9B33-book", (created as FolderResolution.Create).folder.id)
    val sdBook = AppStorage.folderFor(sd, "book")
    assertEquals(FolderResolution.Existing(sdBook), resolve("book", "internal-book", null, sd, probe(), listOf(sdBook)))

    val sdPodcast = AppStorage.folderFor(sd, "podcast")
    val kept = resolve("podcast", "internal-podcast", item(sdPodcast.id, "podcast"), StorageTarget.Internal, probe(), listOf(sdPodcast))
    assertEquals(FolderResolution.Existing(sdPodcast), kept)
  }

  @Test
  fun explicitSafId() {
    val folder = saf("book")
    val internal = AppStorage.folderFor(StorageTarget.Internal, "book")
    val existing = item(internal.id, "book")
    assertEquals(FolderResolution.Existing(folder), resolve("book", safId, existing, sd, probe(), listOf(folder, internal)))
    assertEquals(FolderResolution.NotFound, resolve("book", safId, existing, sd, probe(), listOf(internal)))
  }

  @Test
  fun queuedDownloadFolderWins() {
    val internal = AppStorage.folderFor(StorageTarget.Internal, "podcast")
    assertEquals(FolderResolution.Existing(internal), resolve("podcast", null, null, sd, probe(), listOf(internal), queued = internal.id))

    val sdPodcast = AppStorage.folderFor(sd, "podcast")
    assertEquals(FolderResolution.Unavailable, resolve("podcast", null, null, StorageTarget.Internal, probe(sdMounted = false), listOf(sdPodcast), queued = sdPodcast.id))

    val existingWins = resolve("podcast", null, item(internal.id, "podcast"), sd, probe(), listOf(internal, sdPodcast), queued = sdPodcast.id)
    assertEquals(FolderResolution.Existing(internal), existingWins)
  }
}
