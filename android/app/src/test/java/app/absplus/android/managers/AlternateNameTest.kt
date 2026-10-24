package app.absplus.android.managers

import org.junit.Assert.*
import org.junit.Test

/** Audit F2: the alternative names a download gets when its planned name is taken in a device folder. */
class AlternateNameTest {
  private fun owner(resource: String, user: String = "user-a", item: String = "book-1") =
          DownloadDestinationGuard.Owner(user, "cfg-a", item, resource)

  @Test fun eachFileOfAMultiFileBookGetsItsOwnStableAlternative() {
    val one = DownloadDestinationGuard.alternateFilename("Part.mp3", owner("file:/api/items/book-1/file/1/download"))
    val two = DownloadDestinationGuard.alternateFilename("Part.mp3", owner("file:/api/items/book-1/file/2/download"))
    assertNotEquals(one, two)
    assertEquals(one, DownloadDestinationGuard.alternateFilename("Part.mp3", owner("file:/api/items/book-1/file/1/download")))
    assertTrue(one.startsWith("Part.") && one.endsWith(".mp3"))
  }

  @Test fun anotherAccountOrItemNeverGetsTheSameAlternative() {
    val mine = DownloadDestinationGuard.alternateFilename("e.mp3", owner("episode:ep-1"))
    assertNotEquals(mine, DownloadDestinationGuard.alternateFilename("e.mp3", owner("episode:ep-1", user = "user-b")))
    assertNotEquals(mine, DownloadDestinationGuard.alternateFilename("e.mp3", owner("episode:ep-1", item = "book-2")))
  }

  @Test fun numberedVariantsKeepTheExtension() {
    assertEquals("e.0123456789-2.mp3", DownloadDestinationGuard.numberedFilename("e.0123456789.mp3", 2))
    assertEquals("noext-3", DownloadDestinationGuard.numberedFilename("noext", 3))
    val variants = (2..9).map { DownloadDestinationGuard.numberedFilename("e.tag.mp3", it) }
    assertEquals(variants.size, variants.toSet().size)
  }
}
