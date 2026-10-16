package app.absplus.android.managers

import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalItemLocksTest {
  private class FakeBook : LocalItemBook {
    val items = ConcurrentHashMap<String, LocalLibraryItem>()
    val writes = AtomicInteger()

    override fun read(id: String): LocalLibraryItem? = items[id]?.let { copyOf(it) }

    override fun write(item: LocalLibraryItem) {
      writes.incrementAndGet()
      items[item.id] = copyOf(item)
    }

    override fun delete(id: String) {
      items.remove(id)
    }
  }

  private fun newId() = "local_" + UUID.randomUUID()

  @Before
  fun setUp() {
    LocalItemLocks.clearPlaybackPin()
  }

  @After
  fun tearDown() {
    LocalItemLocks.clearPlaybackPin()
  }

  @Test
  fun bumpChangesVersion() {
    val a = newId()
    val b = newId()
    val before = LocalItemLocks.version(a)
    LocalItemLocks.bump(a)
    assertEquals(before + 1, LocalItemLocks.version(a))
    assertEquals(0L, LocalItemLocks.version(b))
  }

  @Test
  fun reentrant() {
    val a = newId()
    val done = CountDownLatch(1)
    val t = Thread { LocalItemLocks.withItem(a) { LocalItemLocks.withItem(a) { done.countDown() } } }
    t.start()
    t.join(5000)
    assertFalse("nested withItem on the same id must not deadlock", t.isAlive)
    assertEquals(0L, done.count)
  }

  @Test
  fun exclusivePerId() {
    val a = newId()
    val b = newId()
    val entered1 = CountDownLatch(1)
    val release = CountDownLatch(1)
    val holder = Thread { LocalItemLocks.withItem(a) { entered1.countDown(); release.await(5, TimeUnit.SECONDS) } }
    holder.start()
    assertTrue(entered1.await(5, TimeUnit.SECONDS))

    val entered2 = CountDownLatch(1)
    val sameId = Thread { LocalItemLocks.withItem(a) { entered2.countDown() } }
    sameId.start()
    assertFalse("a second writer of the same item must wait", entered2.await(300, TimeUnit.MILLISECONDS))

    val enteredOther = CountDownLatch(1)
    val otherId = Thread { LocalItemLocks.withItem(b) { enteredOther.countDown() } }
    otherId.start()
    assertTrue("another item must not be blocked", enteredOther.await(5, TimeUnit.SECONDS))

    release.countDown()
    assertTrue(entered2.await(5, TimeUnit.SECONDS))
    listOf(holder, sameId, otherId).forEach { it.join(5000); assertFalse(it.isAlive) }
  }

  @Test
  fun readForPlaybackPinsOnlyPlayable() {
    val a = newId()
    val b = newId()
    LocalItemLocks.pinForPlayback(b)

    assertNull(LocalItemLocks.readForPlayback(a, { "x" }, { false }))
    assertTrue(LocalItemLocks.isPinned(b))

    assertNull(LocalItemLocks.readForPlayback<String>(a, { null }, { true }))
    assertTrue(LocalItemLocks.isPinned(b))

    assertEquals("x", LocalItemLocks.readForPlayback(a, { "x" }, { true }))
    assertTrue(LocalItemLocks.isPinned(a))
    assertFalse(LocalItemLocks.isPinned(b))
  }

  @Test
  fun updateIsAtomicWithMove() {
    val id = newId()
    val book = FakeBook()
    val store = LocalItemStore(book)
    book.items[id] = item(id, "/old", listOf("1", "2"))

    val blocked = CountDownLatch(1)
    var reorder: Thread? = null
    val move = Thread {
      LocalItemLocks.withItem(id) {
        val snapshot = book.read(id)!!
        reorder =
                Thread {
                  blocked.countDown()
                  store.update(id) { current ->
                    current.localFiles = current.localFiles.reversed().toMutableList()
                    ItemUpdate.Save(current)
                  }
                }.also { it.start() }
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        snapshot.absolutePath = "/new"
        snapshot.localFiles = snapshot.localFiles.map { it.copy(absolutePath = "/new/${it.id}") }.toMutableList()
        store.save(snapshot)
      }
    }
    move.start()
    move.join(5000)
    assertFalse(move.isAlive)
    reorder!!.join(5000)
    assertFalse(reorder!!.isAlive)

    val saved = book.items[id]!!
    assertEquals("/new", saved.absolutePath)
    assertEquals(listOf("2", "1"), saved.localFiles.map { it.id })
    assertEquals(listOf("/new/2", "/new/1"), saved.localFiles.map { it.absolutePath })
  }

  @Test
  fun keepNeitherWritesNorBumps() {
    val id = newId()
    val book = FakeBook()
    val store = LocalItemStore(book)
    book.items[id] = item(id, "/old", listOf("1"))

    val v0 = LocalItemLocks.version(id)
    assertEquals(ItemUpdate.Keep, store.update(id) { ItemUpdate.Keep })
    assertEquals(0, book.writes.get())
    assertEquals(v0, LocalItemLocks.version(id))

    val saved = store.update(id) { ItemUpdate.Save(it.apply { absolutePath = "/saved" }) }
    assertTrue(saved is ItemUpdate.Save)
    assertEquals(1, book.writes.get())
    assertEquals("/saved", book.items[id]!!.absolutePath)
    assertEquals(v0 + 1, LocalItemLocks.version(id))

    assertEquals(ItemUpdate.Delete, store.update(id) { ItemUpdate.Delete })
    assertNull(book.items[id])
    assertEquals(v0 + 2, LocalItemLocks.version(id))

    var called = false
    assertNull(store.update(newId()) { called = true; ItemUpdate.Keep })
    assertFalse(called)
  }

  @Test(expected = IllegalArgumentException::class)
  fun saveOfAnotherItemIsRejected() {
    val id = newId()
    val book = FakeBook()
    book.items[id] = item(id, "/old", listOf("1"))
    LocalItemStore(book).update(id) { ItemUpdate.Save(item(newId(), "/x", listOf("1"))) }
  }

  companion object {
    private fun file(id: String, dir: String) = LocalFile(id, "$id.mp3", "", "", "$dir/$id", "audio/mpeg", 10)

    fun item(id: String, dir: String, fileIds: List<String>) =
            LocalLibraryItem(
                    id, "internal-book", "", dir, "", false, "book", MediaType(MediaTypeMetadata("Book", false), null),
                    fileIds.map { file(it, dir) }.toMutableList(),
                    null, null, true, "server", null, null, "li"
            )

    // Deep enough for the fields under test: no two readers share a mutable item or file list
    fun copyOf(i: LocalLibraryItem) =
            LocalLibraryItem(
                    i.id, i.folderId, i.basePath, i.absolutePath, i.contentUrl, i.isInvalid, i.mediaType, i.media,
                    i.localFiles.map { it.copy() }.toMutableList(),
                    i.coverContentUrl, i.coverAbsolutePath, i.isLocal, i.serverConnectionConfigId, i.serverAddress,
                    i.serverUserId, i.libraryItemId, i.ino
            )
  }
}
