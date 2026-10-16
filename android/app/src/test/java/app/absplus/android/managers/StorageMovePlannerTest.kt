package app.absplus.android.managers

import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.device.AppStorage
import app.absplus.android.device.AppVolume
import app.absplus.android.device.DownloadStorageSpace
import app.absplus.android.device.StorageTarget
import app.absplus.android.device.VolumeProbe
import app.absplus.android.models.DownloadItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageMovePlannerTest {
  private val internalDir = File("/data/user/0/app.absplus.android/files")
  private val sdDir = File("/storage/79EC-9B33/Android/data/app.absplus.android/files")
  private val sdUuid = "79EC-9B33"

  private class FakeProbe(val internal: File, val sdDir: File, var sdMounted: Boolean = true) : VolumeProbe {
    override fun volumes() = listOf(AppVolume("79EC-9B33", sdDir, removable = true, mounted = sdMounted, writable = sdMounted))
    override fun internalFilesDir() = internal
  }

  private fun book() =
          Book(
                  BookMetadata("Book", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null),
                  null, listOf(), null, null, mutableListOf(), null, null, null, null
          )

  private fun item(id: String, folderId: String, size: Long = 10) =
          LocalLibraryItem(
                  id, folderId, "", "", "", false, "book", book(),
                  mutableListOf(LocalFile("f-$id", "a.mp3", "", "", "", "audio/mpeg", size)),
                  null, null, true, "server", null, null, "li-$id"
          )

  private fun download(libraryItemId: String) =
          DownloadItem(
                  "d-$libraryItemId", libraryItemId, null, null, "server", "addr", "user", "book", "/x/$libraryItemId",
                  AppStorage.folderFor(StorageTarget.Internal, "book"), "Title", "", book(), mutableListOf()
          )

  private val internalBook = "internal-book"
  private val sdBook = AppStorage.appsdFolderId(sdUuid, "book")
  private val plenty: (File) -> Long = { Long.MAX_VALUE / 2 }

  @Test
  fun waitReasons() {
    val probe = FakeProbe(internalDir, sdDir)
    val items =
            listOf(
                    item("pinned", internalBook),
                    item("queued", internalBook),
                    item("saf", "cHJpbWFyeTpBdWRpb2Jvb2tz"),
                    item("there", sdBook),
                    item("plain", internalBook)
            )
    val plan =
            StorageMovePlanner.plan(items, listOf(download("li-queued").apply { terminalFailureAt = 1L }), StorageTarget.Sd(sdUuid), probe, { it == "pinned" }, plenty)
    assertEquals(listOf("plain"), plan.toMove.map { it.id })
    assertEquals(mapOf("pinned" to WaitReason.PLAYING, "queued" to WaitReason.DOWNLOADING), plan.waiting)

    val ejected = FakeProbe(internalDir, sdDir, sdMounted = false)
    val toInternal =
            StorageMovePlanner.plan(listOf(item("onsd", sdBook), item("inside", internalBook)), listOf(), StorageTarget.Internal, ejected, { false }, plenty)
    assertEquals("an item on the ejected card waits", mapOf("onsd" to WaitReason.UNAVAILABLE), toInternal.waiting)
    assertEquals("an item already internal is not planned", listOf<String>(), toInternal.toMove.map { it.id })

    val toSd = StorageMovePlanner.plan(listOf(item("inside", internalBook)), listOf(), StorageTarget.Sd(sdUuid), ejected, { false }, plenty)
    assertEquals("the target card is ejected", mapOf("inside" to WaitReason.UNAVAILABLE), toSd.waiting)
    assertEquals(listOf<LocalLibraryItem>(), toSd.toMove)
  }

  @Test
  fun itemOnEjectedTargetCardWaits() {
    val ejected = FakeProbe(internalDir, sdDir, sdMounted = false)
    val plan = StorageMovePlanner.plan(listOf(item("onsd", sdBook)), listOf(), StorageTarget.Sd(sdUuid), ejected, { false }, plenty)
    assertEquals("an item on the ejected target card waits", mapOf("onsd" to WaitReason.UNAVAILABLE), plan.waiting)
    assertEquals(listOf<LocalLibraryItem>(), plan.toMove)

    val mounted = StorageMovePlanner.plan(listOf(item("onsd", sdBook)), listOf(), StorageTarget.Sd(sdUuid), FakeProbe(internalDir, sdDir), { false }, plenty)
    assertEquals(mapOf<String, WaitReason>(), mounted.waiting)
    assertEquals(listOf<LocalLibraryItem>(), mounted.toMove)
  }

  @Test
  fun noSpaceFromFirstThatDoesNotFit() {
    val probe = FakeProbe(internalDir, sdDir)
    val margin = DownloadStorageSpace.MOVE_SPACE_MARGIN_BYTES
    val free = 1000L + margin
    val calls = mutableListOf<File>()
    val items =
            listOf(
                    item("one", internalBook, 600),
                    item("two", internalBook, 401),
                    item("three", internalBook, 1),
                    item("pinned", internalBook, 1)
            )
    val plan =
            StorageMovePlanner.plan(items, listOf(), StorageTarget.Sd(sdUuid), probe, { it == "pinned" }) {
              calls.add(it)
              free
            }
    assertEquals(listOf("one"), plan.toMove.map { it.id })
    assertEquals(
            mapOf("two" to WaitReason.NO_SPACE, "three" to WaitReason.NO_SPACE, "pinned" to WaitReason.PLAYING),
            plan.waiting
    )
    assertEquals("free space is read once per root", listOf(File(sdDir, "downloads")), calls)

    val exact = StorageMovePlanner.plan(listOf(item("one", internalBook, 600), item("two", internalBook, 400)), listOf(), StorageTarget.Sd(sdUuid), probe, { false }) { free }
    assertEquals("fitting to the byte with the margin kept is allowed", listOf("one", "two"), exact.toMove.map { it.id })
  }
}
