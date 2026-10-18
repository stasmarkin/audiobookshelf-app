package app.absplus.android.media

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private val day = COVER_TOUCH_EVERY_MS
  private val hour = 60 * 60 * 1000L
  private var now = 1_700_000_000_000L

  private fun dir() = File(tmp.root, COVER_CACHE_DIR)

  private fun store(maxBytes: Long = MAX_COVER_CACHE_BYTES, maxFileBytes: Long = MAX_COVER_FILE_BYTES) =
    CoverStore(dir(), maxBytes, maxFileBytes, clock = { now }).also { it.load() }

  private fun CoverStore.putBytes(itemId: String, updatedAt: Long, size: Int, ext: String = "jpg"): CoverEntry? =
    put(itemId, updatedAt, ext) { it.write(ByteArray(size) { 7 }) }

  private fun files(): Set<String> = dir().list()?.toSet() ?: emptySet()

  @Test
  fun evictsLeastRecentlyUsedOverTheLimit() {
    val store = store(maxBytes = 300)
    store.putBytes("A", 1, 100); now += 1000
    store.putBytes("B", 1, 100); now += 1000
    store.putBytes("C", 1, 100); now += 1000

    store.touch(store.find("A", 1)!!)
    assertNotNull(store.putBytes("D", 1, 100))

    assertNull("the least recently used cover is evicted", store.find("B", 1))
    assertNotNull(store.find("A", 1))
    assertNotNull(store.find("C", 1))
    assertNotNull("the cover just written survives", store.find("D", 1))
    assertEquals(300L, store.totalBytes())
    assertEquals(setOf("A_1.jpg", "C_1.jpg", "D_1.jpg"), files())
  }

  @Test
  fun newVersionReplacesOld() {
    val store = store()
    store.putBytes("A", 1, 10)
    store.putBytes("A", 2, 20, ext = "webp")

    assertNull(store.find("A", 1))
    assertEquals(2L, store.newest("A")!!.updatedAt)
    assertEquals(setOf("A_2.webp"), files())
    assertEquals(20L, store.totalBytes())
  }

  @Test
  fun olderVersionDoesNotReplaceNewer() {
    val store = store()
    store.putBytes("A", 2, 10)

    assertNull(store.putBytes("A", 1, 10))
    assertEquals(2L, store.newest("A")!!.updatedAt)
    assertEquals(setOf("A_2.jpg"), files())
  }

  @Test
  fun firstCallBuildsTheIndex() {
    dir().mkdirs()
    File(dir(), "A_1.jpg").writeBytes(ByteArray(10))
    val store = CoverStore(dir(), clock = { now })

    assertEquals(1L, store.peekNewest("A")!!.updatedAt)
    assertEquals(1L, store.find("A", 1)!!.updatedAt)
  }

  @Test
  fun oversizeIsNotStored() {
    val store = store(maxFileBytes = 10)

    assertNull(store.putBytes("A", 1, 11))
    assertEquals(emptySet<String>(), files())
    assertEquals(0L, store.totalBytes())
  }

  @Test
  fun emptyBodyIsNotStored() {
    val store = store()

    assertNull(store.putBytes("A", 1, 0))
    assertEquals(emptySet<String>(), files())
  }

  @Test
  fun failedWriteLeavesNothing() {
    val store = store()
    store.putBytes("A", 1, 10)

    val result = store.put("A", 2, "jpg") {
      it.write(ByteArray(5))
      throw IOException("connection reset")
    }

    assertNull(result)
    assertEquals(setOf("A_1.jpg"), files())
    assertEquals(1L, store.newest("A")!!.updatedAt)
    assertEquals(10L, store.totalBytes())
  }

  @Test
  fun loadSkipsTmpAndForeignNames() {
    dir().mkdirs()
    fun seed(name: String, mtime: Long) = File(dir(), name).apply { writeBytes(ByteArray(100)); setLastModified(mtime) }
    seed("A_1.jpg", now - 2 * hour)
    seed("B_2.webp", now - hour)
    seed("a_b_5.jpg", now - hour / 2)
    seed("C_3.tmp", now)
    seed("notes.txt", now)
    seed("D_x.jpg", now)
    seed("E_4.bmp", now)

    val store = store(maxBytes = 300)

    assertEquals(1L, store.find("A", 1)!!.updatedAt)
    assertEquals(2L, store.find("B", 2)!!.updatedAt)
    assertEquals("an id with '_' is split at the last one", 5L, store.find("a_b", 5)!!.updatedAt)
    assertNull(store.newest("a"))
    assertEquals(setOf("A_1.jpg", "B_2.webp", "a_b_5.jpg"), files())
    assertEquals(300L, store.totalBytes())

    store.putBytes("F", 1, 100)
    assertNull("the oldest lastModified is evicted first", store.find("A", 1))
    assertNotNull(store.find("B", 2))
  }

  @Test
  fun loadKeepsOnlyTheNewestVersion() {
    dir().mkdirs()
    File(dir(), "A_1.jpg").writeBytes(ByteArray(10))
    File(dir(), "A_3.png").writeBytes(ByteArray(10))

    val store = store()

    assertEquals(3L, store.newest("A")!!.updatedAt)
    assertEquals(setOf("A_3.png"), files())
  }

  @Test
  fun unsafeIdRejected() {
    val store = store()

    assertNull(store.putBytes("../x", 1, 10))
    assertNull(store.find("a/b", 1))
    assertNull(store.newest(""))
    assertNull(store.putBytes("A", 1, 10, ext = "html"))

    assertEquals("nothing is created outside the cache directory", listOf(COVER_CACHE_DIR), tmp.root.list()!!.toList())
    assertEquals(emptySet<String>(), files())
  }

  @Test
  fun missingFileIsAMiss() {
    val store = store()
    store.putBytes("A", 1, 10)
    val b = store.putBytes("B", 1, 30)!!

    assertTrue(b.file.delete())

    assertEquals("peekNewest trusts the index", b, store.peekNewest("B"))
    assertNull(store.find("B", 1))
    assertNull(store.newest("B"))
    assertNull(store.peekNewest("B"))
    assertEquals(10L, store.totalBytes())
  }

  @Test
  fun touchWritesLastModifiedAtMostDaily() {
    val store = store()
    val entry = store.putBytes("A", 1, 10)!!
    val written = entry.file.lastModified()

    now += hour
    store.touch(entry)
    assertEquals("a read within a day keeps lastModified", written, entry.file.lastModified())

    now += day
    store.touch(entry)
    val firstTouch = entry.file.lastModified()
    assertTrue(firstTouch > written)

    now += hour
    store.touch(entry)
    assertEquals("two reads within an hour write once", firstTouch, entry.file.lastModified())

    now += day
    store.touch(entry)
    assertTrue(entry.file.lastModified() > firstTouch)
  }
  @Test
  fun twoWritesOfOneVersionDoNotMix() {
    val store = store()
    val firstStarted = java.util.concurrent.CountDownLatch(1)
    val secondDone = java.util.concurrent.CountDownLatch(1)
    val first = Thread {
      store.put("A", 1, "jpg") {
        it.write(1)
        it.flush()
        firstStarted.countDown()
        secondDone.await(5, java.util.concurrent.TimeUnit.SECONDS)
        it.write(byteArrayOf(1, 1))
      }
    }
    first.start()
    assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
    store.put("A", 1, "jpg") { it.write(byteArrayOf(2, 2, 2, 2)) }
    secondDone.countDown()
    first.join(5_000)

    val content = store.find("A", 1)!!.file.readBytes().toList()
    assertTrue("one whole write, not a mix: $content", content == listOf<Byte>(1, 1, 1) || content == listOf<Byte>(2, 2, 2, 2))
    assertEquals("no temporary file left", setOf("A_1.jpg"), files())
  }

  @Test
  fun aWriteNoLongerWantedIsNotStored() {
    val store = store()
    assertNull(store.put("A", 1, "jpg", accept = { false }) { it.write(byteArrayOf(1)) })
    assertNull(store.find("A", 1))
    assertEquals(emptySet<String>(), files())
  }
}
