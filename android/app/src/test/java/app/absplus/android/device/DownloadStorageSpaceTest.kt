package app.absplus.android.device

import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStorageSpaceTest {
  private fun item(id: String, folderId: String, vararg sizes: Long) =
          LocalLibraryItem(
                  id, folderId, "", "", "", false, "book", MediaType(MediaTypeMetadata("Book", false), null),
                  sizes.mapIndexed { i, size -> LocalFile("$id-$i", "$i.mp3", "", "", "/$id/$i", "audio/mpeg", size) }.toMutableList(),
                  null, null, true, "server", null, null, id
          )

  @Test
  fun bytesToMove() {
    val items =
            listOf(
                    item("internal", "internal-book", 100, 1),
                    item("sdA", AppStorage.appsdFolderId("AAAA-1111", "book"), 20, 2),
                    item("sdB", AppStorage.appsdFolderId("BBBB-2222", "podcast"), 3000),
                    item("saf", "c2FmLWZvbGRlcg", 400000)
            )

    assertEquals(3022L, DownloadStorageSpace.bytesToMove(items, StorageTarget.Internal))
    assertEquals(3101L, DownloadStorageSpace.bytesToMove(items, StorageTarget.Sd("AAAA-1111")))
    assertEquals(0L, DownloadStorageSpace.bytesToMove(emptyList(), StorageTarget.Internal))
  }
}
