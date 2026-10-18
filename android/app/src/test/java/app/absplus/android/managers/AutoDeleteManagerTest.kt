package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
import app.absplus.android.data.DeviceData
import app.absplus.android.data.DeviceSettings
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.DeviceManager
import app.absplus.android.models.User
import app.absplus.android.player.PlayerNotificationService.PlaylistQueueItem
import io.paperdb.Paper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Candidate collection and the cleanup pass over real saved items (internal storage, so files are plain). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoDeleteManagerTest {
  private lateinit var ctx: Context
  private val db get() = DeviceManager.dbManager
  private val server = ServerConnectionConfig("cfg", 0, "srv", "https://s", null, "u", "user", "token", null)
  private val longAgo = 1_000L
  private val gb = 1024L * 1024 * 1024

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    DbManager.initialize(ctx)
    // Paper is initialized once per JVM, so state from earlier tests is still there
    listOf("localLibraryItems", "localMediaProgress", "autoDeleteConfirmed", "autoDeleteDownloadedAt", "playbackSession", "downloadItems").forEach { Paper.book(it).destroy() }
    ctx.getSharedPreferences("absplus_playback_restore", Context.MODE_PRIVATE).edit().clear().commit()
    settings()
    DeviceManager.serverConnectionConfig = server
    journal.clear()
    AutoDeleteManager.journal = object : MoveJournal {
      override fun all() = journal.values.toList()
      override fun get(itemId: String) = journal[itemId]
      override fun put(entry: StorageMoveJournalEntry) {
        journal[entry.itemId] = entry
      }
      override fun remove(itemId: String) {
        journal.remove(itemId)
      }
    }
  }

  @After
  fun tearDown() {
    AutoDeleteManager.journal = PaperMoveJournal
    AutoDeleteManager.afterRecheck = {}
  }

  private val journal = mutableMapOf<String, StorageMoveJournalEntry>()

  private fun settings(books: Boolean = true, limitGb: Int = 0) {
    val s = DeviceSettings.default().apply {
      autoDeleteFinishedBooks = books
      autoDeleteDelayDays = 1
      downloadsLimitGb = limitGb
    }
    DeviceManager.deviceData = DeviceData(mutableListOf(server), "cfg", s, null)
  }

  // The deleter only touches paths inside the app's own storage
  private fun tempItemDir(prefix: String): File {
    val downloads = File(ctx.filesDir, "downloads").apply { mkdirs() }
    return Files.createTempDirectory(downloads.toPath(), prefix).toFile()
  }

  private fun book(id: String, size: Long = 3, folderId: String = "internal-downloads", linked: Boolean = true, owner: String = "u", modifiedAt: Long = 500): File {
    val dir = tempItemDir("book")
    // Old files, as downloads from before their time was recorded would be
    File(dir, "a.mp3").apply { writeText("abc"); setLastModified(modifiedAt) }
    val meta = BookMetadata("Title $id", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null)
    val track = AudioTrack(1, 0.0, 10.0, "t", "", "audio/mpeg", null, true, "f1", null)
    val media = Book(meta, null, emptyList(), null, null, mutableListOf(track), null, null, null, null)
    db.saveLocalLibraryItem(
      LocalLibraryItem(
        id, folderId, "", dir.absolutePath, "", false, "book", media,
        mutableListOf(LocalFile("f1", "a.mp3", "", "", File(dir, "a.mp3").absolutePath, "audio/mpeg", size)),
        null, null, true, if (linked) "cfg" else null, "https://s", owner, if (linked) "srv-$id" else null
      )
    )
    return dir
  }

  private fun localProgress(id: String, finished: Boolean, lastUpdate: Long, finishedAt: Long? = lastUpdate, owner: String = "u") =
    db.saveLocalMediaProgress(LocalMediaProgress(id, id, null, 10.0, 1.0, 0.0, finished, null, null, lastUpdate, 0, finishedAt, "cfg", "https://s", owner, "srv-$id", null))

  private fun user(vararg progress: MediaProgress) = User("u", "user", progress.toList())

  private fun serverProgress(id: String, finished: Boolean, lastUpdate: Long) =
    MediaProgress("p-$id", "srv-$id", null, 10.0, 1.0, 0.0, finished, null, null, lastUpdate, 0, lastUpdate)

  @Test
  fun `deletes a finished book once the server has its progress`() {
    val dir = book("b1")
    localProgress("b1", true, longAgo)

    val deleted = AutoDeleteManager.runCleanup(ctx, user(serverProgress("b1", true, longAgo)))

    assertEquals(1, deleted)
    assertFalse(dir.exists())
    assertNull(db.getLocalLibraryItem("b1"))
  }

  @Test
  fun `keeps a book whose finished progress the server does not have yet`() {
    val dir = book("b2")
    localProgress("b2", true, longAgo + 5)

    val deleted = AutoDeleteManager.runCleanup(ctx, user(serverProgress("b2", false, longAgo)))

    assertEquals(0, deleted)
    assertTrue(dir.exists())
  }

  @Test
  fun `progress that changes between the re-check and the delete keeps the book`() {
    val dir = book("race1")
    localProgress("race1", true, longAgo)
    AutoDeleteManager.afterRecheck = { localProgress("race1", false, longAgo + 5) }

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("race1", true, longAgo))))
    assertTrue(dir.exists())
    assertNotNull(db.getLocalLibraryItem("race1"))
  }

  @Test
  fun `a background progress write waits for a delete that holds the item lock`() {
    val dir = book("cw1")
    localProgress("cw1", true, longAgo)
    val held = CountDownLatch(1)
    val writerStarted = CountDownLatch(1)
    var itemGoneWhenWriteLanded: Boolean? = null
    val writer = Thread {
      held.await(5, TimeUnit.SECONDS)
      writerStarted.countDown()
      db.saveLocalMediaProgressLocked(db.getLocalMediaProgress("cw1")!!.also { it.isFinished = false; it.lastUpdate = longAgo + 50 })
      itemGoneWhenWriteLanded = db.getLocalLibraryItem("cw1") == null
    }
    writer.start()

    // The check passes, then the writer tries to write while the delete still holds the lock
    val outcome = app.absplus.android.device.LocalItemDeleter.deleteItem(ctx, "cw1", true) {
      held.countDown()
      writerStarted.await(5, TimeUnit.SECONDS)
      Thread.sleep(300)
      true
    }
    writer.join(5000)

    assertEquals(app.absplus.android.device.LocalItemDeleter.Outcome.DELETED, outcome)
    assertFalse(dir.exists())
    assertEquals("the write landed only after the delete finished", true, itemGoneWhenWriteLanded)
  }

  @Test
  fun `a newer unfinished record wins over a finished one`() {
    val dir = book("b3")
    localProgress("b3", false, longAgo + 5)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("b3", true, longAgo))))
    assertTrue(dir.exists())
  }

  @Test
  fun `offline pass deletes only what an earlier online pass confirmed`() {
    val dir = book("b4")
    localProgress("b4", true, longAgo)

    assertEquals("nothing is confirmed yet", 0, AutoDeleteManager.runCleanup(ctx, null))
    assertTrue(dir.exists())

    // The online pass sees the progress on the server, but the delay has not passed yet
    settings()
    localProgress("b4", true, System.currentTimeMillis())
    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("b4", true, System.currentTimeMillis()))))
    assertNotNull(db.getAutoDeleteConfirmed("b4"))

    localProgress("b4", true, longAgo)
    db.saveAutoDeleteConfirmed("b4", AutoDeletePolicy.Confirmation(AutoDeletePolicy.Identity("cfg", "u", "srv-b4"), longAgo, true))
    assertEquals(1, AutoDeleteManager.runCleanup(ctx, null))
    assertFalse(dir.exists())
    assertNull("the confirmation goes with the book", db.getAutoDeleteConfirmed("b4"))
  }

  @Test
  fun `another server is treated as offline`() {
    val dir = book("b5")
    localProgress("b5", true, longAgo)
    DeviceManager.serverConnectionConfig = server.copy(id = "other")

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("b5", true, longAgo))))
    assertTrue(dir.exists())
  }

  @Test
  fun `never touches items added from a device folder`() {
    val dir = book("b6", linked = false)
    localProgress("b6", true, longAgo)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user()))
    assertTrue(dir.exists())
  }

  @Test
  fun `keeps what the saved queue would resume`() {
    val dir = book("b7")
    localProgress("b7", true, longAgo)
    PlaybackRestoreStore.saveQueue(ctx, listOf(PlaylistQueueItem("srv-b7", null)), 0)
    PlaybackRestoreStore.setResumable(ctx, true)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("b7", true, longAgo))))
    assertTrue(dir.exists())
  }

  @Test
  fun `keeps a book that is downloading`() {
    val dir = book("b8")
    localProgress("b8", true, longAgo)
    db.saveDownloadItem(downloadItem("srv-b8"))

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("b8", true, longAgo))))
    assertTrue(dir.exists())
  }

  @Test
  fun `does nothing with every switch off`() {
    settings(books = false)
    val dir = book("b9")
    localProgress("b9", true, longAgo)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("b9", true, longAgo))))
    assertTrue(dir.exists())
  }

  @Test
  fun `frees the oldest finished book for space and leaves the rest`() {
    settings(limitGb = 1)
    val old = book("old", size = 100)
    val newer = book("newer", size = 100)
    val unfinished = book("open", size = 100)
    localProgress("old", true, longAgo)
    localProgress("newer", true, longAgo + 10)
    localProgress("open", false, longAgo)
    val u = user(serverProgress("old", true, longAgo), serverProgress("newer", true, longAgo + 10), serverProgress("open", false, longAgo))

    val result = AutoDeleteManager.freeSpaceFor(ctx, gb - 200, u)

    assertTrue(result.enough)
    assertEquals(100, result.freedBytes)
    assertFalse(old.exists())
    assertTrue(newer.exists())
    assertTrue(unfinished.exists())
  }

  @Test
  fun `deleting for room stops once the books left cannot cover the deficit any more`() {
    settings(limitGb = 1)
    val first = book("pd1", size = 100)
    val second = book("pd2", size = 100)
    localProgress("pd1", true, longAgo)
    localProgress("pd2", true, longAgo + 10)
    // After the first delete is decided, a large manual download lands: the second book alone cannot make the room
    AutoDeleteManager.afterRecheck = {
      AutoDeleteManager.afterRecheck = {}
      book("manual", size = 1000)
    }
    val u = user(serverProgress("pd1", true, longAgo), serverProgress("pd2", true, longAgo + 10))

    val result = AutoDeleteManager.freeSpaceFor(ctx, gb - 200 + 150, u)

    assertFalse(result.enough)
    assertFalse(first.exists())
    assertTrue("not deleted for nothing", second.exists())
    assertNotNull(db.getLocalLibraryItem("pd2"))
  }

  @Test
  fun `deletes nothing when finished downloads cannot free enough`() {
    settings(limitGb = 1)
    val dir = book("only", size = 100)
    localProgress("only", true, longAgo)
    val u = user(serverProgress("only", true, longAgo))

    val result = AutoDeleteManager.freeSpaceFor(ctx, gb - 100 + 500, u)

    assertFalse(result.enough)
    assertEquals(0, result.freedBytes)
    assertTrue(dir.exists())
    assertNotNull(db.getLocalLibraryItem("only"))
  }

  @Test
  fun `server progress of another account is not used`() {
    val dir = book("acct")
    localProgress("acct", true, longAgo)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, User("someone-else", "x", listOf(serverProgress("acct", true, longAgo)))))
    assertTrue(dir.exists())
  }

  // A podcast with two finished episodes of an internal podcast; retention of the podcast's auto download is the only thing that removes them
  private fun finishedPodcast(): List<File> {
    val dir = tempItemDir("pod")
    val ep1 = File(dir, "e1.mp3").apply { writeText("one"); setLastModified(500) }
    val ep2 = File(dir, "e2.mp3").apply { writeText("two"); setLastModified(500) }
    fun track(f: File, id: String) = AudioTrack(1, 0.0, 10.0, f.name, android.net.Uri.fromFile(f).toString(), "audio/mpeg", null, true, id, null)
    fun episode(id: String, serverId: String, t: AudioTrack) = PodcastEpisode(id, 1, null, null, id, null, null, null, null, null, t, null, 10.0, 3, serverId, id)
    val podcast = Podcast(PodcastMetadata("Pod", null, null, mutableListOf(), false), null, mutableListOf(),
      mutableListOf(episode("le1", "se1", track(ep1, "f1")), episode("le2", "se2", track(ep2, "f2"))), false, 2)
    db.saveLocalLibraryItem(
      LocalLibraryItem(
        "local_pod", "internal-podcast", "", dir.absolutePath, "", false, "podcast", podcast,
        mutableListOf(
          LocalFile("f1", "e1.mp3", android.net.Uri.fromFile(ep1).toString(), "", ep1.absolutePath, "audio/mpeg", 3),
          LocalFile("f2", "e2.mp3", android.net.Uri.fromFile(ep2).toString(), "", ep2.absolutePath, "audio/mpeg", 3)
        ),
        null, null, true, "cfg", "https://s", "u", "srv-pod"
      )
    )
    db.saveLocalMediaProgress(LocalMediaProgress("local_pod-le1", "local_pod", "le1", 10.0, 1.0, 0.0, true, null, null, longAgo, 0, longAgo, "cfg", "https://s", "u", "srv-pod", "se1"))
    return listOf(ep1, ep2)
  }

  private fun podcastProgress() = MediaProgress("p", "srv-pod", "se1", 10.0, 1.0, 0.0, true, null, null, longAgo, 0, longAgo)

  @Test
  fun `a finished episode is not deleted after the delay`() {
    val files = finishedPodcast()

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(podcastProgress())))

    assertTrue(files.all { it.exists() })
    assertNotNull(db.getLocalLibraryItem("local_pod"))
  }

  @Test
  fun `a finished episode is not deleted to make room, but its size counts towards the limit`() {
    settings(limitGb = 1)
    val files = finishedPodcast()
    val big = book("room", size = 100)
    localProgress("room", true, longAgo)
    val u = user(podcastProgress(), serverProgress("room", true, longAgo))

    assertEquals(106, AutoDeleteManager.usedBytes(ctx))
    // Only the book can give room: asking for more than it holds frees nothing at all
    val result = AutoDeleteManager.freeSpaceFor(ctx, gb - 106 + 104, u)

    assertFalse(result.enough)
    assertEquals(0, result.freedBytes)
    assertTrue(files.all { it.exists() })
    assertTrue(big.exists())
    assertEquals(1, AutoDeleteManager.freeSpaceFor(ctx, gb - 106 + 10, u).let { if (it.enough) 1 else 0 })
    assertFalse(big.exists())
    assertTrue(files.all { it.exists() })
  }

  @Test
  fun `the periodic limit cleanup leaves episodes when only they are over the limit`() {
    settings(limitGb = 1)
    val files = finishedPodcast()
    db.saveLocalLibraryItem(db.getLocalLibraryItem("local_pod")!!.also { it.localFiles.forEach { f -> f.size = 2L * 1024 * 1024 * 1024 } })

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(podcastProgress())))
    assertTrue(files.all { it.exists() })
  }

  @Test
  fun `the limit deletes no book while the books toggle is off`() {
    settings(books = false, limitGb = 1)
    val big = book("nb", size = 2L * 1024 * 1024 * 1024)
    localProgress("nb", true, longAgo)
    val u = user(serverProgress("nb", true, longAgo))

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, u))
    val result = AutoDeleteManager.freeSpaceFor(ctx, 100, u)

    assertFalse(result.enough)
    assertEquals(0, result.freedBytes)
    assertTrue(big.exists())
    assertNotNull(db.getLocalLibraryItem("nb"))
  }

  @Test
  fun `used bytes count server downloads only`() {
    book("u1", size = 40)
    book("u2", size = 60)
    book("u3", size = 999, linked = false)

    assertEquals(100, AutoDeleteManager.usedBytes(ctx))
  }

  @Test
  fun `the limit deletes finished books past the limit even before their delay`() {
    settings(limitGb = 1)
    val big = book("big", size = 2L * 1024 * 1024 * 1024)
    localProgress("big", true, System.currentTimeMillis())
    val now = System.currentTimeMillis()

    assertEquals(1, AutoDeleteManager.runCleanup(ctx, user(serverProgress("big", true, now))))
    assertFalse(big.exists())
  }

  @Test
  fun `leaves an item that the move journal has under way`() {
    val dir = book("mv1", folderId = "internal-book")
    localProgress("mv1", true, longAgo)
    journal["mv1"] = StorageMoveJournalEntry(opId = "op", itemId = "mv1", phase = MovePhase.COMMITTED)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("mv1", true, longAgo))))
    assertTrue("being moved to the SD card", dir.exists())
    assertNotNull(db.getLocalLibraryItem("mv1"))

    journal.remove("mv1")
    assertEquals("deletable again once the move is done", 1, AutoDeleteManager.runCleanup(ctx, user(serverProgress("mv1", true, longAgo))))
    assertFalse(dir.exists())
  }

  @Test
  fun `a journal entry of another item does not protect this one`() {
    val dir = book("mv2", folderId = "internal-book")
    localProgress("mv2", true, longAgo)
    journal["other"] = StorageMoveJournalEntry(opId = "op", itemId = "other", phase = MovePhase.COPYING)

    assertEquals(1, AutoDeleteManager.runCleanup(ctx, user(serverProgress("mv2", true, longAgo))))
    assertFalse(dir.exists())
  }

  @Test
  fun `leaves an item on an unavailable SD card and does not count it`() {
    val dir = book("sd1", size = 70, folderId = "appsd-79EC-9B33-book")
    book("int1", size = 30)
    localProgress("sd1", true, longAgo)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("sd1", true, longAgo))))
    assertTrue(dir.exists())
    assertNotNull(db.getLocalLibraryItem("sd1"))
    assertEquals("only the internal item takes room on an available volume", 30, AutoDeleteManager.usedBytes(ctx))
    assertEquals(0, AutoDeleteManager.freeSpaceFor(ctx, 10, user(serverProgress("sd1", true, longAgo))).freedBytes)
    assertTrue(dir.exists())
  }

  @Test
  fun `a delete waits for the item lock and re-checks progress under it`() {
    val dir = book("lk1")
    localProgress("lk1", true, longAgo)
    val u = user(serverProgress("lk1", true, longAgo))
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val holder = Thread { LocalItemLocks.withItem("lk1") { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
    holder.start()
    assertTrue(held.await(5, TimeUnit.SECONDS))

    var deleted = -1
    val finished = CountDownLatch(1)
    val cleaner = Thread { deleted = AutoDeleteManager.runCleanup(ctx, u); finished.countDown() }
    cleaner.start()

    assertFalse("the delete must wait for the item lock", finished.await(600, TimeUnit.MILLISECONDS))
    // The user resumed the book while the cleanup waited
    localProgress("lk1", false, longAgo + 5)
    release.countDown()
    assertTrue(finished.await(10, TimeUnit.SECONDS))
    holder.join()

    assertEquals(0, deleted)
    assertTrue(dir.exists())
  }

  @Test
  fun `a download that was finished listening before it was downloaded again is kept until a new finish`() {
    val dir = book("re1")
    localProgress("re1", true, longAgo)
    db.saveAutoDeleteDownloadedAt("re1", longAgo + 100)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("re1", true, longAgo))))
    assertTrue(dir.exists())

    // Finished again after the download
    localProgress("re1", true, longAgo + 200)
    assertEquals(1, AutoDeleteManager.runCleanup(ctx, user(serverProgress("re1", true, longAgo + 200))))
    assertNull("the download time goes with the book", db.getAutoDeleteDownloadedAt("re1"))
  }

  @Test
  fun `recording a finished download keeps the time of a book, not of a podcast`() {
    val dir = tempItemDir("pod")
    val podcast = Podcast(PodcastMetadata("Pod", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0)
    val pod = LocalLibraryItem("local_p", "internal-podcast", "", dir.absolutePath, "", false, "podcast", podcast, mutableListOf(), null, null, true, "cfg", "https://s", "u", "srv-p")
    book("rec1")

    AutoDeleteManager.recordDownloaded(pod, now = 77)
    AutoDeleteManager.recordDownloaded(db.getLocalLibraryItem("rec1")!!, now = 88)

    assertNull(db.getAutoDeleteDownloadedAt("local_p"))
    assertEquals(88L, db.getAutoDeleteDownloadedAt("rec1"))
  }

  @Test
  fun `a relinked item is not deleted offline on an old confirmation`() {
    val dir = book("rl1")
    localProgress("rl1", true, longAgo)
    db.saveAutoDeleteConfirmed("rl1", AutoDeletePolicy.Confirmation(AutoDeletePolicy.Identity("cfg", "u", "srv-OLD"), longAgo, true))

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, null))
    assertTrue(dir.exists())
  }

  @Test
  fun `a server that was newer and not finished online revokes the offline deletion`() {
    val dir = book("rv1")
    localProgress("rv1", true, longAgo)

    // Online: the server's record is newer and not finished, so nothing is deleted and the confirmation says "not finished"
    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("rv1", false, longAgo + 10))))
    assertEquals(false, db.getAutoDeleteConfirmed("rv1")!!.isFinished)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, null))
    assertTrue(dir.exists())
  }

  @Test
  fun `an item in a device folder the app cannot write is left alone and not counted`() {
    val dir = book("saf1", size = 50, folderId = "saf-folder")
    localProgress("saf1", true, longAgo)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("saf1", true, longAgo))))
    assertTrue(dir.exists())
    assertNotNull(db.getLocalLibraryItem("saf1"))
    assertEquals(0, AutoDeleteManager.usedBytes(ctx))
  }

  @Test
  fun `a retry adds only the files that are not downloaded yet`() {
    fun part(failed: Boolean, moved: Boolean, size: Long) = app.absplus.android.models.DownloadItemPart(
      "p$size", "dl", "a.mp3", size, "", "", "", "", "", "internal-downloads", null, null, null,
      completed = moved, moved = moved, isMoving = false, failed = failed, uri = android.net.Uri.EMPTY,
      destinationUri = android.net.Uri.EMPTY, finalDestinationUri = android.net.Uri.EMPTY, completedDestinationUri = null,
      finalDestinationSubfolder = "", downloadId = null, lastUpdateTime = null, progress = 0, bytesDownloaded = 0
    )
    val item = downloadItem("srv-x")
    item.downloadItemParts.clear()
    item.downloadItemParts.addAll(listOf(part(false, true, 10), part(true, false, 100), part(false, false, 1000)))

    assertEquals("only the failed, unfinished part is new", 100, AutoDeleteManager.retryExtraBytes(item))
  }

  @Test
  fun `server progress of another account on the same server is not used for a download it does not own`() {
    val dir = book("ow1", owner = "other-user")
    localProgress("ow1", true, longAgo, owner = "other-user")

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("ow1", true, longAgo))))
    assertTrue(dir.exists())
    assertNull("no confirmation from someone else's progress", db.getAutoDeleteConfirmed("ow1"))
  }

  @Test
  fun `an online answer without the server record withdraws an earlier confirmation`() {
    val dir = book("wd2")
    localProgress("wd2", true, longAgo)
    db.saveAutoDeleteConfirmed("wd2", AutoDeletePolicy.Confirmation(AutoDeletePolicy.Identity("cfg", "u", "srv-wd2"), longAgo, true))

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user()))
    assertNull("the server no longer has the record", db.getAutoDeleteConfirmed("wd2"))
    assertNotNull(dir)
  }

  @Test
  fun `a download from before its time was recorded is dated by its files`() {
    val dir = book("old1", modifiedAt = System.currentTimeMillis())
    localProgress("old1", true, longAgo)

    assertEquals("finished before the files were written", 0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("old1", true, longAgo))))
    assertTrue(dir.exists())
    assertNotNull(db.getAutoDeleteDownloadedAt("old1"))
  }

  @Test
  fun `a finished download that is still in the queue protects its item`() {
    val dir = book("fin1")
    localProgress("fin1", true, longAgo)
    val queued = downloadItem("srv-fin1")
    queued.downloadItemParts.forEach { it.completed = true; it.moved = true }
    db.saveDownloadItem(queued)

    assertEquals(0, AutoDeleteManager.runCleanup(ctx, user(serverProgress("fin1", true, longAgo))))
    assertTrue(dir.exists())
  }

  private fun finishedQueued(id: String, size: Long) = downloadItem(id, size).also { q ->
    q.downloadItemParts.forEach { it.completed = true; it.moved = true }
    db.saveDownloadItem(q)
  }

  @Test
  fun `a finished download not yet saved as a local item still counts as queued`() {
    finishedQueued("srv-pending", 70)

    assertEquals(70, AutoDeleteManager.queuedBytes(ctx))
  }

  @Test
  fun `a finished download whose files the saved item lists is not counted twice`() {
    book("twice", size = 40)
    finishedQueued("srv-twice", 40)

    assertEquals(0, AutoDeleteManager.queuedBytes(ctx))
    assertEquals(40, AutoDeleteManager.usedBytes(ctx))
  }

  @Test
  fun `the next download does not fit next to a finished one that is not saved yet`() {
    settings(limitGb = 1)
    finishedQueued("srv-prev", gb - 5)

    val result = AutoDeleteManager.freeSpaceFor(ctx, 10, user())

    assertFalse(result.enough)
  }

  @Test
  fun `room is made with the next candidate when the first cannot be deleted`() {
    settings(limitGb = 1)
    val mb600 = 600L * 1024 * 1024
    val stuck = book("sp1", size = mb600)
    val next = book("sp2", size = mb600)
    // The first candidate's folder is outside app storage, so the deleter refuses it
    val outside = Files.createTempDirectory("outside").toFile()
    db.saveLocalLibraryItem(db.getLocalLibraryItem("sp1")!!.also { it.absolutePath = outside.absolutePath })
    localProgress("sp1", true, longAgo)
    localProgress("sp2", true, longAgo + 10)
    val u = user(serverProgress("sp1", true, longAgo), serverProgress("sp2", true, longAgo + 10))

    val result = AutoDeleteManager.freeSpaceFor(ctx, 200L * 1024 * 1024, u)

    assertTrue("the episode fits by fact", result.enough)
    assertTrue(stuck.exists())
    assertFalse(next.exists())
    assertEquals(mb600, result.freedBytes)
  }

  private fun downloadItem(libraryItemId: String, size: Long = 10) = app.absplus.android.models.DownloadItem(
    "dl-$libraryItemId", libraryItemId, null, null, "cfg", "https://s", "u", "book", "", app.absplus.android.data.LocalFolder("internal-downloads", "n", "", "", "", "internal", "book"),
    "t", "", Book(BookMetadata("t", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null), null, emptyList(), null, null, mutableListOf(), null, null, null, null),
    mutableListOf(
      app.absplus.android.models.DownloadItemPart(
        "p", "dl-$libraryItemId", "a.mp3", size, "", "", "", "", "", "internal-downloads", null, null, null,
        completed = false, moved = false, isMoving = false, failed = false, uri = android.net.Uri.EMPTY,
        destinationUri = android.net.Uri.EMPTY, finalDestinationUri = android.net.Uri.EMPTY, completedDestinationUri = null,
        finalDestinationSubfolder = "", downloadId = null, lastUpdateTime = null, progress = 0, bytesDownloaded = 0
      )
    )
  )
}
