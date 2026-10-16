package app.absplus.android.managers

import android.net.Uri
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.EBookFile
import app.absplus.android.data.FileMetadata
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.PlaybackSession
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.device.AppStorage
import app.absplus.android.device.AppVolume
import app.absplus.android.device.NodeKind
import app.absplus.android.device.StorageHashing
import app.absplus.android.device.StorageIo
import app.absplus.android.device.StorageTarget
import app.absplus.android.device.VolumeIdentity
import app.absplus.android.device.VolumeProbe
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving downloads between internal storage and the SD card (PR #29, H03): the journal, its recovery after a crash at
 * every step, the restart check before a source is removed, and the coordination with playback, downloads and edits.
 * Faults are injected deterministically through [FaultIo] and the mover's step hook; there are no timing sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageMoverTest {
  @get:Rule val temp = TemporaryFolder()

  private val mapper = jacksonObjectMapper()
  private val sdUuid = "79EC-9B33"

  /** Simulates the process dying at a step: an Error, so no `catch (e: Exception)` in the mover can absorb it. */
  private class SimulatedCrash(step: String) : Error("process died at $step")

  private class FakeProbe(val internal: File, val sdDir: File) : VolumeProbe {
    var sdMounted = true
    var sdIdentity = VolumeIdentity.VERIFIED
    override fun volumes() = listOf(AppVolume("79EC-9B33", sdDir, removable = true, mounted = sdMounted, writable = sdMounted, identity = sdIdentity))
    override fun internalFilesDir() = internal
  }

  /** The real filesystem, links never followed, with switches that fail or corrupt chosen operations. */
  private inner class FaultIo : StorageIo {
    var failCopyOf: String? = null
    var corruptCopyOf: String? = null
    var failRename = false
    var free = Long.MAX_VALUE / 4
    var afterCopy: ((File) -> Unit)? = null
    /** Files on the SD card can't be reached while it is unmounted, like a removed card. */
    private fun reachable(f: File) = probe.sdMounted || !AppStorage.isUnder(f, probe.sdDir)

    override fun kind(file: File): NodeKind {
      if (!reachable(file)) return NodeKind.MISSING
      return try {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        when {
          a.isSymbolicLink -> NodeKind.LINK
          a.isRegularFile -> NodeKind.FILE
          a.isDirectory -> NodeKind.DIRECTORY
          else -> NodeKind.OTHER
        }
      } catch (e: NoSuchFileException) {
        NodeKind.MISSING
      }
    }
    override fun list(dir: File): List<String>? = if (reachable(dir)) dir.list()?.toList() else null
    override fun mkdirs(dir: File) = reachable(dir) && (dir.isDirectory || dir.mkdirs())
    override fun copyAndSync(from: File, to: File, cancelled: () -> Boolean): String {
      if (!reachable(from) || !reachable(to)) throw IOException("storage not available")
      failCopyOf?.let { if (from.name == it) throw IOException("disk full") }
      val sha = StorageHashing.copyAndSync(from, to, cancelled)
      corruptCopyOf?.let { if (from.name == it) flipByte(to) }
      afterCopy?.invoke(from)
      return sha
    }
    override fun sha256(file: File, cancelled: () -> Boolean): String {
      if (!reachable(file)) throw IOException("storage not available")
      return StorageHashing.sha256(file, cancelled)
    }
    override fun length(file: File) = if (reachable(file)) file.length() else 0L
    override fun rename(from: File, to: File) = !failRename && reachable(from) && from.renameTo(to)
    override fun delete(file: File) = reachable(file) && Files.deleteIfExists(file.toPath()).let { true }
    override fun syncDir(dir: File) = true
    override fun freeBytes(dir: File) = free
  }

  /** Stores serialized copies, like Paper. */
  private inner class MapBook : LocalItemBook {
    val data = ConcurrentHashMap<String, String>()
    override fun read(id: String): LocalLibraryItem? = data[id]?.let { mapper.readValue(it, LocalLibraryItem::class.java) }
    override fun write(item: LocalLibraryItem) {
      data[item.id] = mapper.writeValueAsString(item)
    }
    override fun delete(id: String) {
      data.remove(id)
    }
  }

  private inner class FakeDb : MoverDb {
    val book = MapBook()
    val store = LocalItemStore(book)
    val queued: MutableSet<String> = ConcurrentHashMap.newKeySet()
    var session: PlaybackSession? = null
    var sessionSaves = 0
    var failNextSave = false
    var afterFirstGet: ((String) -> Unit)? = null
    var beforeSave: ((LocalLibraryItem) -> Unit)? = null

    override fun getItem(id: String): LocalLibraryItem? {
      val item = book.read(id)
      afterFirstGet?.let {
        afterFirstGet = null
        it(id)
      }
      return item
    }
    override fun saveItem(item: LocalLibraryItem) {
      if (failNextSave) {
        failNextSave = false
        throw IOException("database write failed")
      }
      beforeSave?.let {
        beforeSave = null
        it(item)
      }
      store.save(item)
    }
    override fun hasQueuedDownload(libraryItemId: String) = libraryItemId in queued
    override fun lastSession() = session
    override fun saveLastSession(s: PlaybackSession) {
      sessionSaves++
      session = s
    }
    fun json(id: String) = book.data[id]
  }

  /** Serialized like Paper, so a restarted mover reads what was durably written. */
  private inner class MapJournal : MoveJournal {
    val data = ConcurrentHashMap<String, String>()
    override fun all() = data.values.map { mapper.readValue<StorageMoveJournalEntry>(it) }
    override fun get(itemId: String) = data[itemId]?.let { mapper.readValue<StorageMoveJournalEntry>(it) }
    override fun put(entry: StorageMoveJournalEntry) {
      data[entry.itemId] = mapper.writeValueAsString(entry)
    }
    override fun remove(itemId: String) {
      data.remove(itemId)
    }
  }

  private lateinit var probe: FakeProbe
  private lateinit var db: FakeDb
  private lateinit var journal: MapJournal
  private lateinit var io: FaultIo
  private lateinit var inRoot: File
  private lateinit var sdRoot: File
  private var boot = "boot-1"
  private var cancel = false

  private fun mover(crashAt: String? = null) =
          StorageMover(probe, db, journal, io, { boot }, { cancel }) { step -> if (step == crashAt) throw SimulatedCrash(step) }

  @Before
  fun setUp() {
    probe = FakeProbe(temp.newFolder("internal"), temp.newFolder("sd"))
    inRoot = File(probe.internal, "downloads").apply { mkdirs() }
    sdRoot = File(probe.sdDir, "downloads").apply { mkdirs() }
    db = FakeDb()
    journal = MapJournal()
    io = FaultIo()
  }

  @After
  fun tearDown() {
    LocalItemLocks.clearPlaybackPin()
  }

  private fun restart() {
    boot = "boot-" + UUID.randomUUID()
  }

  private fun uniq() = "li_" + UUID.randomUUID().toString().take(8)

  private fun sdFolder(mediaType: String) = AppStorage.folderFor(StorageTarget.Sd(sdUuid), mediaType)
  private fun internalFolder(mediaType: String) = AppStorage.folderFor(StorageTarget.Internal, mediaType)

  private fun write(dir: File, name: String, size: Int, seed: Int = 0): File =
          File(dir, name).apply { writeBytes(ByteArray(size) { ((it + seed) % 251).toByte() }) }

  private fun flipByte(f: File) {
    val b = f.readBytes()
    b[b.size / 2] = (b[b.size / 2] + 1).toByte()
    f.writeBytes(b)
  }

  private fun localFile(f: File) =
          LocalFile("lf-${f.name}", f.name, Uri.fromFile(f).toString(), "Internal/${f.parentFile!!.name}", f.absolutePath, "audio/mpeg", f.length())

  private fun track(index: Int, lf: LocalFile) =
          AudioTrack(index, 0.0, 10.0, lf.filename!!, lf.contentUrl, "audio/mpeg",
                  FileMetadata(lf.filename!!, "mp3", lf.absolutePath, lf.basePath, lf.size), true, lf.id, index)

  private fun bookMeta() =
          BookMetadata("Book", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null)

  private fun item(libId: String, dir: File, folderId: String, mediaType: String, media: app.absplus.android.data.MediaType, files: List<LocalFile>, cover: LocalFile?) =
          LocalLibraryItem("local_$libId", folderId, dir.absolutePath, dir.absolutePath, "", false, mediaType, media,
                  files.toMutableList(), cover?.contentUrl, cover?.absolutePath, true, "server", "addr", "user", libId)

  private fun bookItem(libId: String = uniq(), root: File = inRoot, folderId: String = "internal-book"): LocalLibraryItem {
    val dir = File(root, libId).apply { mkdirs() }
    val files = listOf(write(dir, "01 Chapter.mp3", 1000), write(dir, "02 Глава é.mp3", 1500, 7), write(dir, "book.epub", 300, 3), write(dir, "cover.jpg", 50, 9)).map { localFile(it) }
    val ebook = EBookFile("ino", FileMetadata("book.epub", "epub", "/server/Book/book.epub", "book.epub", 300), "epub", true, files[2].id, files[2].contentUrl)
    val media = Book(bookMeta(), null, listOf(), null, null, mutableListOf(track(1, files[0]), track(2, files[1])), ebook, null, null, null)
    return item(libId, dir, folderId, "book", media, files, files[3])
  }

  private fun episode(lf: LocalFile, n: Int) =
          PodcastEpisode("local_ep_$n-${lf.id}", n, null, null, "Episode $n", null, null, null, null, null, track(n, lf), null, 10.0, lf.size, "ep$n", null)

  private fun podcastItem(libId: String = uniq()): LocalLibraryItem {
    val dir = File(inRoot, libId).apply { mkdirs() }
    val files = listOf(write(dir, "ep 1.mp3", 800), write(dir, "ép 2.mp3", 900, 5), write(dir, "cover.jpg", 40, 2)).map { localFile(it) }
    val media = Podcast(PodcastMetadata("Podcast", null, null, mutableListOf(), false), null, mutableListOf(),
            mutableListOf(episode(files[0], 1), episode(files[1], 2)), false, 2)
    return item(libId, dir, "internal-podcast", "podcast", media, files, files[2])
  }

  private fun addEpisode(item: LocalLibraryItem, n: Int) {
    val lf = localFile(write(File(item.absolutePath), "new $n.mp3", 700, n))
    item.localFiles.add(lf)
    (item.media as Podcast).episodes!!.add(episode(lf, n))
  }

  private fun seed(item: LocalLibraryItem): LocalLibraryItem {
    db.store.save(item)
    return item
  }

  private fun episodes(item: LocalLibraryItem) = (item.media as Podcast).episodes!!
  private fun tracks(item: LocalLibraryItem) = item.media.getAudioTracks()

  /** Relative path -> SHA-256 of every file below [dir]. */
  private fun hashes(dir: File): Map<String, String> =
          dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path.replace('\\', '/') to StorageHashing.sha256(it) { false } }

  private fun noTmp() = listOf(inRoot, sdRoot).forEach { r -> assertTrue("no move copy is left in $r", r.listFiles()!!.none { it.name.startsWith(".moving-") }) }

  /** The record's every file exists and matches [expected]; returns the record. */
  private fun assertRecordComplete(id: String, expected: Map<String, String>): LocalLibraryItem {
    val rec = db.getItem(id)!!
    assertEquals("the folder the record uses holds every file intact", expected, hashes(File(rec.absolutePath)))
    rec.localFiles.forEach { assertTrue("${it.absolutePath} exists", File(it.absolutePath).isFile) }
    return rec
  }

  // ---- Paths

  @Test
  fun relocateRewritesAllPaths() {
    val libId = uniq()
    val book = bookItem(libId)
    val saf = LocalFile("saf", "x.mp3", "content://com.android.externalstorage.documents/document/primary%3AX%2Fx.mp3", "", "/storage/emulated/0/X/x.mp3", "audio/mpeg", 5)
    book.localFiles.add(saf)
    val before = mapper.writeValueAsString(book)
    val moved = StorageMover.relocate(book, inRoot, sdRoot, sdFolder("book"))

    assertEquals("the input is not changed", before, mapper.writeValueAsString(book))
    assertEquals(sdFolder("book").id, moved.folderId)
    assertEquals(File(sdRoot, libId).absolutePath, moved.absolutePath)
    assertEquals("a path outside the old root stays", saf, moved.localFiles.last())
    assertEquals(Uri.fromFile(File(sdRoot, "$libId/02 Глава é.mp3")).toString(), tracks(moved)[1].contentUrl)
    assertEquals(File(sdRoot, "$libId/02 Глава é.mp3").absolutePath, tracks(moved)[1].metadata!!.path)
    assertEquals(Uri.fromFile(File(sdRoot, "$libId/book.epub")).toString(), (moved.media as Book).ebookFile!!.contentUrl)
    assertEquals(File(sdRoot, "$libId/cover.jpg").absolutePath, moved.coverAbsolutePath)
    assertEquals(book.localFiles.map { it.id }, moved.localFiles.map { it.id })
  }

  // ---- Normal moves keep the source until the copy is checked after a restart

  @Test
  fun internalToSdKeepsTheSourceUntilTheCopyIsCheckedAfterARestart() {
    val item = seed(podcastItem())
    val libId = item.libraryItemId!!
    val original = hashes(File(item.absolutePath))

    assertEquals(MoveResult.Moved, mover().moveOne(item.id, sdFolder("podcast")))
    val rec = assertRecordComplete(item.id, original)
    assertEquals(File(sdRoot, libId).absolutePath, rec.absolutePath)
    assertEquals(sdFolder("podcast").id, rec.folderId)
    assertEquals("episodes and their ids are kept", episodes(item).map { it.id }, episodes(rec).map { it.id })
    assertEquals("the source is kept", original, hashes(File(inRoot, libId)))
    assertEquals(MovePhase.COMMITTED, journal.get(item.id)!!.phase)
    assertTrue("the manifest has a SHA-256 per file", journal.get(item.id)!!.files.all { it.sha256.length == 64 })
    noTmp()

    // Same boot: the copy may still be in memory only, so the source stays however often recovery runs
    repeat(3) { mover().recover() }
    assertEquals(original, hashes(File(inRoot, libId)))

    restart()
    val summary = mover().recover()
    assertEquals(1, summary.retired)
    assertFalse("the old copy is removed after the restart check", File(inRoot, libId).exists())
    assertNull(journal.get(item.id))
    assertRecordComplete(item.id, original)
    // Idempotent afterwards
    mover().recover()
    assertRecordComplete(item.id, original)
  }

  @Test
  fun sdToInternalMovesBackTheSameWay() {
    val item = seed(bookItem(root = sdRoot, folderId = sdFolder("book").id))
    val libId = item.libraryItemId!!
    val original = hashes(File(item.absolutePath))

    assertEquals(MoveResult.Moved, mover().moveOne(item.id, internalFolder("book")))
    assertEquals(File(inRoot, libId).absolutePath, assertRecordComplete(item.id, original).absolutePath)
    assertEquals(original, hashes(File(sdRoot, libId)))
    restart()
    mover().recover()
    assertFalse(File(sdRoot, libId).exists())
    assertRecordComplete(item.id, original)
  }

  @Test
  fun aSessionSavedForTheOldPathsIsPointedAtTheCopy() {
    val item = bookItem()
    val moved = StorageMover.relocate(item, inRoot, sdRoot, sdFolder("book"))
    seed(moved)
    db.session = PlaybackSession("s1", "user", item.libraryItemId, null, "book", item.media.metadata, DeviceInfo("d", "m", "m", 35, "1"),
            listOf(), "Book", "Author", null, 20.0, 3, 0L, 0L, 0L, tracks(item).map { it.copy(metadata = it.metadata!!.copy()) }.toMutableList(),
            5.0, null, item, null, "server", "addr", "exo-player")
    StorageMover.healLastSession(db)
    assertEquals(moved.absolutePath, db.session!!.localLibraryItem!!.absolutePath)
    assertEquals(tracks(moved).map { it.contentUrl }, db.session!!.audioTracks.map { it.contentUrl })
    assertEquals(5.0, db.session!!.currentTime, 0.0)
    StorageMover.healLastSession(db)
    assertEquals("a healed session is not written again", 1, db.sessionSaves)
  }

  // ---- Copy faults leave the source and the record untouched

  private fun assertUntouched(item: LocalLibraryItem, before: String?, original: Map<String, String>) {
    assertEquals("the record is unchanged", before, db.json(item.id))
    assertEquals("the source is intact", original, hashes(File(item.absolutePath)))
    assertFalse(File(sdRoot, item.libraryItemId!!).exists())
    assertNull("no journal entry is left", journal.get(item.id))
    noTmp()
  }

  @Test
  fun notEnoughSpaceStartsNothing() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    io.free = 1000L
    assertEquals(MoveResult.Discarded("no space"), mover().moveOne(item.id, sdFolder("book")))
    assertUntouched(item, before, original)
  }

  @Test
  fun aFailedCopyIsUndone() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    io.failCopyOf = "02 Глава é.mp3"
    val result = mover().moveOne(item.id, sdFolder("book"))
    assertTrue("$result", result is MoveResult.Failed && result.message.contains("disk full"))
    assertUntouched(item, before, original)
  }

  @Test
  fun aCopyWithTheSameLengthButOtherBytesIsRejected() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    io.corruptCopyOf = "01 Chapter.mp3"
    val result = mover().moveOne(item.id, sdFolder("book"))
    assertTrue("$result", result is MoveResult.Failed && result.message.contains("SHA-256"))
    assertUntouched(item, before, original)
  }

  @Test
  fun aFailedRenameOrRecordWriteIsUndone() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    io.failRename = true
    assertTrue(mover().moveOne(item.id, sdFolder("book")) is MoveResult.Failed)
    assertUntouched(item, before, original)

    io.failRename = false
    db.failNextSave = true
    assertTrue(mover().moveOne(item.id, sdFolder("book")) is MoveResult.Failed)
    assertUntouched(item, before, original)
  }

  @Test
  fun anExistingDestinationIsNeverReplaced() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    val foreign = write(File(sdRoot, item.libraryItemId!!).apply { mkdirs() }, "someone else.bin", 10)
    var copies = 0
    io.afterCopy = { copies++ }
    val result = mover().moveOne(item.id, sdFolder("book"))
    // Refused before anything is copied, not left to whether a rename onto it happens to fail on this filesystem
    assertTrue("$result", result is MoveResult.Failed && result.message.contains("already exists"))
    assertEquals(0, copies)
    assertTrue("the existing folder is untouched", foreign.isFile)
    assertEquals(before, db.json(item.id))
    assertEquals(original, hashes(File(item.absolutePath)))
    assertNull(journal.get(item.id))
  }

  @Test
  fun aLinkInTheSourceRefusesTheMoveAndNothingOutsideIsTouched() {
    val item = seed(bookItem())
    val outside = temp.newFolder("outside")
    val sentinel = write(outside, "private.bin", 10)
    val link = File(item.absolutePath, "link")
    try {
      Files.createSymbolicLink(link.toPath(), outside.toPath())
    } catch (e: Exception) {
      assumeTrue("symbolic links are supported here", false)
    }
    val result = mover().moveOne(item.id, sdFolder("book"))
    assertTrue("$result", result is MoveResult.Failed)
    assertTrue("the link target is untouched", sentinel.isFile)
    assertFalse(File(sdRoot, item.libraryItemId!!).exists())
    assertNull(journal.get(item.id))
  }

  @Test
  fun aFolderNamedDotDotIsNotMoved() {
    val item = seed(bookItem().apply { absolutePath = File(inRoot, "..").path })
    val sentinel = write(probe.sdDir, "keep.bin", 10)
    assertTrue(mover().moveOne(item.id, sdFolder("book")) is MoveResult.Failed)
    assertTrue(sentinel.isFile)
    assertTrue(inRoot.isDirectory)
  }

  // ---- Coordination with edits, playback and downloads

  @Test
  fun anItemChangedDuringTheCopyIsNotMoved() {
    val item = seed(podcastItem())
    io.afterCopy = { from ->
      if (from.name == "ep 1.mp3") db.store.update(item.id) {
        addEpisode(it, 3)
        ItemUpdate.Save(it)
      }
    }
    assertEquals(MoveResult.Discarded("changed during copy"), mover().moveOne(item.id, sdFolder("podcast")))
    val saved = db.getItem(item.id)!!
    assertEquals("the episode saved during the copy is kept", 3, episodes(saved).size)
    assertEquals(item.absolutePath, saved.absolutePath)
    assertTrue(File(item.absolutePath, "new 3.mp3").isFile)
    assertFalse(File(sdRoot, item.libraryItemId!!).exists())
    assertNull(journal.get(item.id))
    noTmp()
  }

  @Test
  fun playbackThatStartsDuringTheCopyKeepsTheItemWhereItIs() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    io.afterCopy = { LocalItemLocks.pinForPlayback(item.id) }
    assertEquals(MoveResult.Discarded("playing"), mover().moveOne(item.id, sdFolder("book")))
    assertUntouched(item, before, original)
    io.afterCopy = null
    assertEquals("a playing item is not copied at all", MoveResult.Discarded("playing"), mover().moveOne(item.id, sdFolder("book")))
  }

  @Test
  fun aDownloadQueuedDuringTheCopyKeepsTheItemWhereItIs() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    io.afterCopy = { db.queued.add(item.libraryItemId!!) }
    assertEquals(MoveResult.Discarded("downloading"), mover().moveOne(item.id, sdFolder("book")))
    assertUntouched(item, before, original)
  }

  @Test
  fun aWriteRacingTheSnapshotIsNotLost() {
    val raced = seed(podcastItem())
    val writer = AtomicReference<Thread>()
    db.afterFirstGet = { id ->
      val t = Thread { db.store.update(id) { addEpisode(it, 3); ItemUpdate.Save(it) } }
      writer.set(t)
      t.start()
      t.join(300)
    }
    val result = mover().moveOne(raced.id, sdFolder("podcast"))
    writer.get().join(5000)
    val after = db.getItem(raced.id)!!
    assertEquals("the racing episode is not lost ($result)", 3, episodes(after).size)
    assertTrue("its file is in the folder the record uses", File(after.absolutePath, "new 3.mp3").isFile)
  }

  @Test
  fun aReaderWaitsForTheCommitAndSeesTheNewFolder() {
    val item = seed(bookItem())
    val seen = AtomicReference<String>()
    var readerDoneWhileSaving = true
    lateinit var reader: Thread
    db.beforeSave = {
      reader = Thread { seen.set(LocalItemLocks.withItem(item.id) { db.getItem(item.id)!!.folderId }) }
      reader.start()
      reader.join(100)
      readerDoneWhileSaving = !reader.isAlive
    }
    assertEquals(MoveResult.Moved, mover().moveOne(item.id, sdFolder("book")))
    reader.join(5000)
    assertFalse("a reader waits for the commit", readerDoneWhileSaving)
    assertEquals(sdFolder("book").id, seen.get())
  }

  @Test
  fun aSecondMoveOfAnItemWaitsForTheFirstToFinish() {
    val item = seed(bookItem())
    assertEquals(MoveResult.Moved, mover().moveOne(item.id, sdFolder("book")))
    assertEquals(MoveResult.Discarded("previous move not finished"), mover().moveOne(item.id, internalFolder("book")))
    val plan = StorageMovePlanner.plan(listOf(db.getItem(item.id)!!), listOf(), StorageTarget.Internal, probe, { false },
            freeBytes = { Long.MAX_VALUE / 2 }, journaled = { journal.get(it) != null })
    assertEquals(WaitReason.FINISHING, plan.waiting[item.id])
    restart()
    mover().recover()
    assertEquals(MoveResult.Moved, mover().moveOne(item.id, internalFolder("book")))
  }

  @Test
  fun aTimedOutPassLeavesWorkThatTheNextPassUndoes() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    io.afterCopy = { cancel = true }
    assertEquals(MoveResult.Discarded("cancelled"), mover().moveOne(item.id, sdFolder("book")))
    assertEquals(MovePhase.COPYING, journal.get(item.id)!!.phase)
    cancel = false
    io.afterCopy = null
    mover().recover()
    assertUntouched(item, before, original)
  }

  // ---- Process death at every step

  @Test
  fun aCrashAtAnyStepNeverLosesTheOnlyGoodCopy() {
    val steps = listOf("journaled", "copied:01 Chapter.mp3", "copied:cover.jpg", "verified", "installed", "saved", "committed")
    for (step in steps) {
      val item = seed(bookItem())
      val libId = item.libraryItemId!!
      val original = hashes(File(item.absolutePath))
      try {
        mover(crashAt = step).moveOne(item.id, sdFolder("book"))
        throw AssertionError("expected a crash at $step")
      } catch (e: SimulatedCrash) {
        // the process died
      }
      // The restarted app: recovery runs first, any number of times
      repeat(2) { mover().recover() }
      val rec = assertRecordComplete(item.id, original)
      val committed = step in listOf("saved", "committed")
      assertEquals("after a crash at $step the record uses the ${if (committed) "copy" else "source"}",
              File(if (committed) sdRoot else inRoot, libId).absolutePath, rec.absolutePath)
      if (committed) assertEquals("the source stays until the restart check ($step)", original, hashes(File(inRoot, libId)))
      else assertFalse("an unused copy is removed ($step)", File(sdRoot, libId).exists())
      noTmp()

      restart()
      repeat(2) { mover().recover() }
      assertRecordComplete(item.id, original)
      assertNull("finished after a restart ($step)", journal.get(item.id))
      assertFalse("exactly one copy is left ($step)", File(if (committed) inRoot else sdRoot, libId).exists())
    }
  }

  @Test
  fun aCrashWhileRemovingTheOldCopyIsFinishedLater() {
    val item = seed(bookItem())
    val libId = item.libraryItemId!!
    val original = hashes(File(item.absolutePath))
    assertEquals(MoveResult.Moved, mover().moveOne(item.id, sdFolder("book")))
    restart()
    try {
      mover(crashAt = "retired:01 Chapter.mp3").recover()
      throw AssertionError("expected a crash")
    } catch (e: SimulatedCrash) {
      // the process died during the retirement
    }
    assertEquals(MovePhase.RETIRING, journal.get(item.id)!!.phase)
    mover().recover()
    assertFalse(File(inRoot, libId).exists())
    assertNull(journal.get(item.id))
    assertRecordComplete(item.id, original)
  }

  // ---- The restart check

  @Test
  fun aCopyThatFailsItsCheckAfterARestartSendsTheItemBackToItsOriginalFiles() {
    val item = seed(bookItem())
    val libId = item.libraryItemId!!
    val original = hashes(File(item.absolutePath))
    assertEquals(MoveResult.Moved, mover().moveOne(item.id, sdFolder("book")))
    // Bytes lost on the card (power cut during writeback): same length, other content
    flipByte(File(sdRoot, "$libId/02 Глава é.mp3"))
    restart()
    assertEquals(1, mover().recover().reverted)
    val rec = assertRecordComplete(item.id, original)
    assertEquals(File(inRoot, libId).absolutePath, rec.absolutePath)
    assertEquals("internal-book", rec.folderId)
    assertFalse("the failed copy is removed", File(sdRoot, libId).exists())
    assertNull(journal.get(item.id))
  }

  @Test
  fun aFailedCheckWithNewerFilesInTheCopyKeepsBothCopies() {
    val item = seed(podcastItem())
    val libId = item.libraryItemId!!
    val original = hashes(File(item.absolutePath))
    assertEquals(MoveResult.Moved, mover().moveOne(item.id, sdFolder("podcast")))
    db.store.update(item.id) { addEpisode(it, 3); ItemUpdate.Save(it) }
    flipByte(File(sdRoot, "$libId/ep 1.mp3"))
    restart()
    assertEquals(1, mover().recover().attention)
    assertEquals(MovePhase.NEEDS_ATTENTION, journal.get(item.id)!!.phase)
    assertEquals("the original stays", original, hashes(File(inRoot, libId)))
    assertTrue("the copy and its newer episode stay", File(sdRoot, "$libId/new 3.mp3").isFile)
    assertEquals(File(sdRoot, libId).absolutePath, db.getItem(item.id)!!.absolutePath)
    // Nothing more happens to it automatically
    restart()
    mover().recover()
    assertEquals(original, hashes(File(inRoot, libId)))
  }

  @Test
  fun noBootIdentityMeansTheOldCopyIsNeverRemoved() {
    val item = seed(bookItem())
    val libId = item.libraryItemId!!
    val unknown = StorageMover(probe, db, journal, io, { null })
    assertEquals(MoveResult.Moved, unknown.moveOne(item.id, sdFolder("book")))
    restart()
    repeat(2) { unknown.recover() }
    mover().recover()
    assertTrue("kept: the commit's boot is unknown", File(inRoot, libId).isDirectory)
    assertFalse(StorageMover.recoveryPending(journal) { boot })
  }

  // ---- Removable storage during and after a move

  @Test
  fun aCardRemovedDuringTheCopyLeavesTheItemOnItsSource() {
    val item = seed(bookItem())
    val before = db.json(item.id)
    val original = hashes(File(item.absolutePath))
    io.afterCopy = { probe.sdMounted = false }
    val result = mover().moveOne(item.id, sdFolder("book"))
    assertTrue("$result", result !is MoveResult.Moved)
    assertEquals(before, db.json(item.id))
    assertEquals(original, hashes(File(item.absolutePath)))
    // The card comes back: recovery removes the partial copy
    probe.sdMounted = true
    io.afterCopy = null
    mover().recover()
    assertFalse(File(sdRoot, item.libraryItemId!!).exists())
    assertNull(journal.get(item.id))
    noTmp()
  }

  @Test
  fun aCardMissingAfterTheMoveDelaysTheCleanupUntilItIsBack() {
    val item = seed(bookItem())
    val libId = item.libraryItemId!!
    val original = hashes(File(item.absolutePath))
    assertEquals(MoveResult.Moved, mover().moveOne(item.id, sdFolder("book")))
    probe.sdMounted = false
    restart()
    repeat(2) { mover().recover() }
    assertEquals("the source stays while the copy can't be checked", original, hashes(File(inRoot, libId)))
    assertEquals(MovePhase.COMMITTED, journal.get(item.id)!!.phase)

    // Another card under the same UUID (no matching marker) is not the copy's card
    probe.sdMounted = true
    probe.sdIdentity = VolumeIdentity.MISMATCH
    mover().recover()
    assertEquals(original, hashes(File(inRoot, libId)))
    assertEquals(MoveResult.Discarded("unavailable"), mover().moveOne(seed(bookItem()).id, sdFolder("book")))

    probe.sdIdentity = VolumeIdentity.VERIFIED
    mover().recover()
    assertFalse(File(inRoot, libId).exists())
    assertRecordComplete(item.id, original)
  }

  @Test
  fun aDeletedItemsOldCopyIsRemovedAfterTheRestart() {
    val item = seed(bookItem())
    val libId = item.libraryItemId!!
    assertEquals(MoveResult.Moved, mover().moveOne(item.id, sdFolder("book")))
    // The user deletes the moved item (its current folder) before the restart
    File(sdRoot, libId).deleteRecursively()
    db.store.remove(item.id)
    restart()
    mover().recover()
    assertFalse(File(inRoot, libId).exists())
    assertNull(journal.get(item.id))
  }

  // ---- Leftovers

  @Test
  fun onlyUnusedTemporaryCopiesAreSweptAndUnreferencedFoldersStay() {
    val stray = File(sdRoot, ".moving-old").apply { mkdirs() }
    write(stray, "a.mp3", 10)
    // A folder no record refers to (a record lost elsewhere, a failed download) is not deleted for that reason
    val nobody = File(inRoot, "li_nobody").apply { mkdirs() }
    write(nobody, "a.mp3", 10)
    val rootFile = write(inRoot, "stray.bin", 10)
    mover().recover()
    assertFalse("an unused temporary copy is removed", stray.exists())
    assertTrue("an unreferenced folder is kept", File(nobody, "a.mp3").isFile)
    assertTrue(rootFile.isFile)
  }
}
