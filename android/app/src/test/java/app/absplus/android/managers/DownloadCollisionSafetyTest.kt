package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderScanner
import app.absplus.android.models.DownloadItem
import app.absplus.android.models.DownloadItemPart
import com.getcapacitor.JSObject
import io.paperdb.Paper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R29-N02 regression tests: two downloads that resolve to one local filename never replace each other's bytes or records.
 * The fixture is Codex's DownloadCollisionReproductionTest (real queue admission, internal finalization and FolderScanner
 * registration, no network), extended by Claude. Synthetic files, ids and accounts only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadCollisionSafetyTest {
  private lateinit var context: Context
  private lateinit var manager: DownloadItemManager
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private var done = CountDownLatch(1)
  private val folder = LocalFolder("internal-podcast", "Synthetic", "", "", "", "internal", "podcast")
  private val configA = ServerConnectionConfig("synthetic-A", 0, "Fixture A", "http://127.0.0.1:1", null, "user-a", "fixture", "token-a", null)
  private val configB = ServerConnectionConfig("synthetic-B", 1, "Fixture B", "http://127.0.0.1:2", null, "user-b", "fixture", "token-b", null)

  @Before fun setup() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    for (book in listOf("downloadItems", "localLibraryItems", "localMediaProgress", "autoDownload")) Paper.book(book).destroy()
    AutoDownloadStore.clearCacheForTests()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(configA, configB)
    DeviceManager.serverConnectionConfig = configA
    try { WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build()) } catch (_: IllegalStateException) {}
    for (dir in listOf("downloads", "download-staging")) File(context.filesDir, dir).deleteRecursively()
    manager = newManager()
  }

  @After fun cleanup() {
    manager.destroy()
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    AutoDownloadStore.clearCacheForTests()
  }

  private fun newManager() = DownloadItemManager(FolderScanner(context), context, object : DownloadItemManager.DownloadEventEmitter {
    override fun onDownloadItem(downloadItem: DownloadItem) {}
    override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
    override fun onDownloadItemComplete(jsobj: JSObject) { done.countDown() }
    override fun onQueueChanged(hasWork: Boolean) {}
  })

  private fun sanitized(path: String): String = LibraryItemDownloadStarter::class.java
      .getDeclaredMethod("getFilenameFromRelPath", String::class.java).apply { isAccessible = true }
      .invoke(LibraryItemDownloadStarter, path) as String

  private fun bytes(label: String) = "SYNTHETIC-$label-${label.length}".toByteArray()
  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  /** An episode download as the starter builds it (Codex's fixture), before queue admission. */
  private fun episodeItem(ep: String, rel: String, size: Int, config: ServerConnectionConfig = configA, show: String = "show-1"): DownloadItem {
    val name = sanitized(rel)
    val stage = File(context.filesDir, "download-staging/$show/$name.part")
    val final = File(context.filesDir, "downloads/$show/$name")
    val metadata = FileMetadata(rel.substringAfterLast('/'), "mp3", "/synthetic/$rel", rel, size.toLong())
    val track = AudioTrack(1, 0.0, 60.0, ep, "", "audio/mpeg", metadata, false, null, 1)
    val episode = PodcastEpisode(ep, 1, null, null, ep, null, null, null, 1L, AudioFile(1, "ino-$ep", metadata), track, null, 60.0, size.toLong(), null, null)
    val part = DownloadItemPart.make("$show-$ep", name, size.toLong(), stage, final, "Synthetic", "/api/items/$show/file/ino-$ep/download", folder, null, track, episode)
    val media = Podcast(PodcastMetadata("Synthetic", "Fixture", null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0)
    return DownloadItem("$show-$ep", show, ep, null, config.id, config.address, config.userId, "podcast", final.parent, folder, "Synthetic", "Synthetic", media, mutableListOf(part))
  }

  private fun admit(item: DownloadItem): DownloadItemPart {
    manager.addDownloadItem(item)
    return manager.findDownloadItem(item.id)!!.downloadItemParts.single()
  }

  private fun stage(part: DownloadItemPart, content: ByteArray) {
    File(part.destinationPath).apply { parentFile!!.mkdirs(); writeBytes(content) }
    part.completed = true
  }

  private fun finalizeNow(item: DownloadItem, part: DownloadItemPart) {
    DownloadItemManager::class.java.getDeclaredMethod("finalizeInternalFile", DownloadItem::class.java, DownloadItemPart::class.java)
        .apply { isAccessible = true }.invoke(manager, item, part)
  }

  /** Admission, transfer (staged bytes), finalization and registration of one episode; returns its final file. */
  private fun download(ep: String, rel: String, content: ByteArray, config: ServerConnectionConfig = configA, show: String = "show-1"): File {
    done = CountDownLatch(1)
    val item = episodeItem(ep, rel, content.size, config, show)
    val part = admit(item)
    stage(part, content)
    finalizeNow(manager.findDownloadItem(item.id)!!, part)
    assertTrue("registration of $ep must complete without network", done.await(15, TimeUnit.SECONDS))
    awaitLeftQueue(item.id)
    return File(part.finalDestinationPath)
  }

  /** Registration reports completion just before it removes the item from the queue. */
  private fun awaitLeftQueue(id: String) {
    val deadline = System.currentTimeMillis() + 10000
    while (manager.findDownloadItem(id) != null && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertNull("$id must leave the queue after registration", manager.findDownloadItem(id))
  }

  private fun local(show: String = "show-1") = DeviceManager.dbManager.getLocalLibraryItem("local_$show")!!

  /** episode id -> bytes its persisted record plays */
  private fun recordedBytes(show: String = "show-1"): Map<String, ByteArray> {
    val item = local(show)
    return (item.media as Podcast).episodes!!.associate { e ->
      e.serverEpisodeId!! to File(item.localFiles.single { it.id == e.audioTrack!!.localFileId }.absolutePath).readBytes()
    }
  }

  // 1. Two filenames normalizing to the same destination
  @Test fun twoPathsThatFlattenToOneNameKeepSeparateFilesAndRecords() {
    assertEquals(sanitized("Season1/episode.mp3"), sanitized("Season1_episode.mp3"))
    val a = download("ep-a", "Season1/episode.mp3", bytes("A"))
    val b = download("ep-b", "Season1_episode.mp3", bytes("B-DIFFERENT"))
    assertEquals("Season1_episode.mp3", a.name)
    assertNotEquals(a.canonicalPath, b.canonicalPath)
    assertEquals(DownloadDestinationGuard.alternateFilename("Season1_episode.mp3",
            DownloadDestinationGuard.Owner("user-a", "synthetic-A", "show-1", "episode:ep-b")), b.name)
    assertArrayEquals(bytes("A"), a.readBytes())
    assertArrayEquals(bytes("B-DIFFERENT"), b.readBytes())
    val recorded = recordedBytes()
    assertArrayEquals(bytes("A"), recorded["ep-a"])
    assertArrayEquals(bytes("B-DIFFERENT"), recorded["ep-b"])
  }

  // 2. Different episodes in the same podcast with the identical server filename
  @Test fun threeEpisodesWithOneServerFilenameAllSurvive() {
    val files = listOf("ep-1", "ep-2", "ep-3").map { download(it, "episode.mp3", bytes(it)) }
    assertEquals(3, files.map { it.canonicalPath }.toSet().size)
    files.zip(listOf("ep-1", "ep-2", "ep-3")).forEach { (f, ep) -> assertArrayEquals(bytes(ep), f.readBytes()) }
    assertEquals(3, local().localFiles.filter { it.filename?.endsWith(".mp3") == true }.size)
    recordedBytes().forEach { (ep, b) -> assertArrayEquals(bytes(ep), b) }
  }

  // 3. The same filename in two podcasts keeps the server name in both folders
  @Test fun sameFilenameInTwoPodcastsNeedsNoAlternativeName() {
    val one = download("ep-1", "episode.mp3", bytes("SHOW1"), show = "show-1")
    val two = download("ep-1", "episode.mp3", bytes("SHOW2"), show = "show-2")
    assertEquals("episode.mp3", one.name)
    assertEquals("episode.mp3", two.name)
    assertArrayEquals(bytes("SHOW1"), one.readBytes())
    assertArrayEquals(bytes("SHOW2"), two.readBytes())
  }

  // 4. The same item and filename from another server: refused at the start, held if it reaches finalization anyway
  @Test fun anotherServersDownloadOfTheSameItemIsRefusedAndNeverReplacesTheFile() {
    val a = download("ep-1", "episode.mp3", bytes("SERVER-A"))
    DeviceManager.serverConnectionConfig = configB
    var refusal: String? = "not called"
    val episode = episodeItem("ep-2", "episode.mp3", 8, configB).downloadItemParts.single().episode!!
    val libraryItem = LibraryItem("show-1", "1", "lib", "folder", "/p", "p", 0, 0, 0, 0, 0, null, null, false, false, "podcast",
            Podcast(PodcastMetadata("Synthetic", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(episode), false, 1),
            mutableListOf(), null, null, null, null)
    // The start is resolved under the item's lock on the download host's coroutine; wait for its answer
    val answered = java.util.concurrent.CountDownLatch(1)
    LibraryItemDownloadStarter.startLibraryItemDownload(context, libraryItem, null, episode) { refusal = it; answered.countDown() }
    assertTrue(answered.await(10, java.util.concurrent.TimeUnit.SECONDS))
    assertNotNull(refusal)
    assertTrue(refusal!!.contains("another server"))
    assertTrue(DeviceManager.dbManager.getDownloadItems().none { it.serverConnectionConfigId == "synthetic-B" })
    // the finalization guard holds even an item that bypassed the starter
    val item = episodeItem("ep-2", "episode.mp3", 8, configB)
    val part = item.downloadItemParts.single()
    manager.addDownloadItem(item)
    val queued = manager.findDownloadItem(item.id)!!
    val bypass = queued.downloadItemParts.single()
    val forced = DownloadItemPart.make(item.id, part.filename, 8, File(part.destinationPath), File(part.finalDestinationPath),
            "Synthetic", part.serverPath, folder, null, part.audioTrack, part.episode)
    queued.downloadItemParts[0] = forced
    stage(forced, bytes("SERVER-B"))
    finalizeNow(queued, forced)
    assertNotEquals(bypass.finalDestinationPath, part.finalDestinationPath) // admission had moved it away too
    assertTrue(forced.failed)
    assertTrue(forced.failureReason!!.contains("another download"))
    assertArrayEquals(bytes("SERVER-A"), a.readBytes())
    assertTrue(File(forced.destinationPath).exists()) // B's bytes are held, not discarded
  }

  // 5. Legacy downloads: a recorded episode keeps its name on re-download; legacy shared records never get overwritten
  @Test fun legacyRecordsKeepTheirNameAndAnAliasedRecordIsNeverOverwritten() {
    val a = download("ep-a", "episode.mp3", bytes("LEGACY-A"))
    val again = download("ep-a", "episode.mp3", bytes("LEGACY-A-NEW"))
    assertEquals(a.canonicalPath, again.canonicalPath)
    assertArrayEquals(bytes("LEGACY-A-NEW"), a.readBytes())
    assertEquals(1, local().localFiles.filter { it.filename == "episode.mp3" }.size)
    // a record written by the old code: ep-b also points at ep-a's file
    val item = local()
    val podcast = item.media as Podcast
    val shared = podcast.episodes!!.first().audioTrack!!
    podcast.episodes!!.add(PodcastEpisode("local_ep_ep-b", 2, null, null, "ep-b", null, null, null, null, null, shared, null, 60.0, 1L, "ep-b", "local_ep_ep-b"))
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    val b = download("ep-b", "episode.mp3", bytes("NEW-B"))
    assertNotEquals(a.canonicalPath, b.canonicalPath)
    assertArrayEquals(bytes("LEGACY-A-NEW"), a.readBytes())
    assertArrayEquals(bytes("NEW-B"), b.readBytes())
  }

  // 5b. An episode stored under its alternative name keeps that name after the other episode is gone
  @Test fun anAlternativeNameIsKeptOnReDownload() {
    download("ep-a", "Season1/episode.mp3", bytes("A"))
    val b = download("ep-b", "Season1_episode.mp3", bytes("B"))
    val item = local()
    val aFile = item.localFiles.single { it.filename == "Season1_episode.mp3" }
    File(aFile.absolutePath).delete()
    item.media.removeAudioTrack(aFile.id)
    item.removeLocalFile(aFile.id)
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    val again = download("ep-b", "Season1_episode.mp3", bytes("B-AGAIN"))
    assertEquals(b.canonicalPath, again.canonicalPath)
    assertFalse(File(b.parentFile, "Season1_episode.mp3").exists())
  }

  // 6. Missing or ambiguous ownership is never destructive
  @Test fun anUnaccountedFileIsAvoidedAtAdmissionAndSetAsideAtFinalization() {
    val dir = File(context.filesDir, "downloads/show-1").apply { mkdirs() }
    val stray = File(dir, "episode.mp3").apply { writeBytes(bytes("STRAY")) }
    val b = download("ep-b", "episode.mp3", bytes("B"))
    assertNotEquals(stray.canonicalPath, b.canonicalPath)
    assertArrayEquals(bytes("STRAY"), stray.readBytes())
    // a part queued before the fix (no admission) still never deletes the unaccounted file
    val item = episodeItem("ep-c", "other.mp3", 1)
    File(dir, "other.mp3").writeBytes(bytes("STRAY-2"))
    manager.addDownloadItem(item)
    val queued = manager.findDownloadItem(item.id)!!
    val original = item.downloadItemParts.single()
    val legacyPart = DownloadItemPart.make(item.id, "other.mp3", 1, File(dir.parentFile!!.parentFile, "download-staging/show-1/other.mp3.part"),
            File(dir, "other.mp3"), "Synthetic", original.serverPath, folder, null, original.audioTrack, original.episode)
    queued.downloadItemParts[0] = legacyPart
    stage(legacyPart, bytes("C"))
    done = CountDownLatch(1)
    finalizeNow(queued, legacyPart)
    assertTrue(done.await(15, TimeUnit.SECONDS))
    assertArrayEquals(bytes("C"), File(dir, "other.mp3").readBytes())
    val kept = dir.listFiles()!!.single { it.name.startsWith(".other.mp3.abs-preserved-") }
    assertArrayEquals(bytes("STRAY-2"), kept.readBytes())
  }

  @Test fun aRecordWhoseServerCannotBeIdentifiedIsNotReplaced() {
    val a = download("ep-a", "episode.mp3", bytes("A"))
    val item = local()
    item.serverUserId = null
    item.serverConnectionConfigId = null
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    val again = episodeItem("ep-a", "episode.mp3", 4)
    manager.addDownloadItem(again)
    val part = manager.findDownloadItem(again.id)!!.downloadItemParts.single()
    assertNotEquals(a.canonicalPath, File(part.finalDestinationPath).canonicalPath)
    assertArrayEquals(bytes("A"), a.readBytes())
  }

  // 7. Concurrent finalization of two different episodes onto one path (e.g. queued before the fix): exactly one wins
  @Test fun concurrentFinalizationsCannotBothReplaceTheDestination() {
    val dir = File(context.filesDir, "downloads/show-1")
    val items = listOf("ep-x", "ep-y").map { ep ->
      val item = episodeItem(ep, "same.mp3", 6)
      manager.addDownloadItem(item)
      val queued = manager.findDownloadItem(item.id)!!
      val original = item.downloadItemParts.single()
      val forced = DownloadItemPart.make(item.id, "same.mp3", 6, File(context.filesDir, "download-staging/show-1/same.mp3.$ep.part"),
              File(dir, "same.mp3"), "Synthetic", original.serverPath, folder, null, original.audioTrack, original.episode)
      queued.downloadItemParts[0] = forced
      stage(forced, bytes(ep.uppercase()))
      queued to forced
    }
    done = CountDownLatch(1)
    val barrier = CyclicBarrier(2)
    val threads = items.map { (item, part) -> Thread { barrier.await(); finalizeNow(item, part) }.apply { start() } }
    threads.forEach { it.join(15000) }
    val winners = items.filter { it.second.moved }
    val losers = items.filter { it.second.failed }
    assertEquals(1, winners.size)
    assertEquals(1, losers.size)
    assertTrue(losers.single().second.failureReason!!.contains("another download"))
    assertArrayEquals(File(winners.single().second.destinationPath).let { bytes(winners.single().first.episodeId!!.uppercase()) }, File(dir, "same.mp3").readBytes())
    assertTrue(File(losers.single().second.destinationPath).exists())
  }

  // 8. Interrupted finalization: the backup is the only copy and is restored, never deleted
  @Test fun aBackupLeftByAnInterruptedFinalizationIsRestored() {
    val a = download("ep-a", "episode.mp3", bytes("ORIGINAL-A"))
    val backup = File(a.parentFile, ".episode.mp3.abs-backup")
    assertTrue(a.renameTo(backup)) // process death between the two renames
    // a different episode that reaches finalization of this path is held, and A's bytes are back in place
    val item = episodeItem("ep-b", "episode.mp3", 5)
    manager.addDownloadItem(item)
    val queued = manager.findDownloadItem(item.id)!!
    val original = item.downloadItemParts.single()
    val forced = DownloadItemPart.make(item.id, "episode.mp3", 5, File(original.destinationPath), a, "Synthetic", original.serverPath, folder, null, original.audioTrack, original.episode)
    queued.downloadItemParts[0] = forced
    stage(forced, bytes("B"))
    finalizeNow(queued, forced)
    assertTrue(forced.failed)
    assertArrayEquals(bytes("ORIGINAL-A"), a.readBytes())
    assertFalse(backup.exists())
    // the user clears the held download; the same episode's retry after another crash restores, then replaces its own copy
    manager.removeDownloadItem(item.id, "clear")
    assertTrue(a.renameTo(backup))
    val retry = download("ep-a", "episode.mp3", bytes("RETRY-A"))
    assertEquals(a.canonicalPath, retry.canonicalPath)
    assertArrayEquals(bytes("RETRY-A"), a.readBytes())
    assertFalse(backup.exists())
  }

  // 9. Failed finalization and a stale backup: the existing copy survives and nothing is deleted unseen
  @Test fun aFailedFinalizationRestoresTheExistingCopyAndAStaleBackupIsSetAside() {
    val a = download("ep-a", "episode.mp3", bytes("KEEP-A"))
    val stale = File(a.parentFile, ".episode.mp3.abs-backup").apply { writeBytes(bytes("STALE")) }
    // same-episode retry whose staged file vanished: the move fails after the backup step
    val item = episodeItem("ep-a", "episode.mp3", 6)
    val part = admit(item)
    part.completed = true
    finalizeNow(manager.findDownloadItem(item.id)!!, part)
    assertFalse(part.moved)
    assertArrayEquals(bytes("KEEP-A"), a.readBytes())
    assertFalse(File(a.parentFile, ".episode.mp3.abs-backup").exists())
    val setAside = a.parentFile!!.listFiles()!!.single { it.name.startsWith(".episode.mp3.abs-backup.abs-preserved-") }
    assertArrayEquals(bytes("STALE"), setAside.readBytes())
    assertFalse(stale.exists())
  }

  // 10. A legitimate same-episode retry replaces only its own copy, under its own name
  @Test fun aSameEpisodeRetryReplacesItsOwnCopy() {
    val first = download("ep-a", "episode.mp3", bytes("V1"))
    val second = download("ep-a", "episode.mp3", bytes("V2-LONGER"))
    assertEquals(first.canonicalPath, second.canonicalPath)
    assertArrayEquals(bytes("V2-LONGER"), first.readBytes())
    assertTrue(first.parentFile!!.listFiles()!!.none { it.name.contains("abs-backup") || it.name.contains("abs-preserved") })
  }

  // 11 + 12. File contents and persisted records stay consistent, including the queue entries
  @Test fun everyRecordPointsAtItsOwnBytesAndQueuedDestinationsAreDistinct() {
    val inputs = mapOf("ep-1" to "a/b.mp3", "ep-2" to "a_b.mp3", "ep-3" to "_a_b.mp3", "ep-4" to "a?_b.mp3")
    inputs.forEach { (ep, rel) -> assertEquals("a_b.mp3", sanitized(rel)) }
    val queued = inputs.map { (ep, rel) -> episodeItem(ep, rel, 9).also { manager.addDownloadItem(it) } }
    val destinations = queued.map { manager.findDownloadItem(it.id)!!.downloadItemParts.single().finalDestinationPath }
    assertEquals(4, destinations.toSet().size)
    assertEquals(4, DeviceManager.dbManager.getDownloadItems().flatMap { it.downloadItemParts }.map { it.finalDestinationPath }.toSet().size)
    queued.forEach { item ->
      done = CountDownLatch(1)
      val queuedItem = manager.findDownloadItem(item.id)!!
      val part = queuedItem.downloadItemParts.single()
      stage(part, bytes(item.episodeId!!))
      finalizeNow(queuedItem, part)
      assertTrue(done.await(15, TimeUnit.SECONDS))
      awaitLeftQueue(item.id)
    }
    val record = local()
    val episodes = (record.media as Podcast).episodes!!
    assertEquals(4, episodes.map { it.audioTrack!!.localFileId }.toSet().size)
    recordedBytes().forEach { (ep, b) -> assertEquals(sha(bytes(ep)), sha(b)) }
  }

  // 13. App restart: the admitted owner-specific destination is persisted, restored and finalized correctly
  @Test fun theOwnerSpecificDestinationSurvivesARestart() {
    val a = download("ep-a", "Season1/episode.mp3", bytes("A"))
    val item = episodeItem("ep-b", "Season1_episode.mp3", 5)
    val admitted = admit(item)
    assertNotEquals(a.canonicalPath, File(admitted.finalDestinationPath).canonicalPath)
    manager.destroy()
    manager = newManager()
    manager.restoreQueue()
    val restoredItem = manager.findDownloadItem(item.id)!!
    val restored = restoredItem.downloadItemParts.single()
    assertEquals(admitted.finalDestinationPath, restored.finalDestinationPath)
    done = CountDownLatch(1)
    stage(restored, bytes("B"))
    finalizeNow(restoredItem, restored)
    assertTrue(done.await(15, TimeUnit.SECONDS))
    assertArrayEquals(bytes("A"), a.readBytes())
    assertArrayEquals(bytes("B"), recordedBytes()["ep-b"])
  }

  // 14. Retention: an automatic removal never takes a file another episode plays; distinct files are removed normally
  @Test fun retentionKeepsASharedFileAndRemovesOnlyTheAutoEpisodesOwnFile() {
    val a = download("ep-a", "Season1/episode.mp3", bytes("AUTO-A"))
    val b = download("ep-b", "Season1_episode.mp3", bytes("MANUAL-B"))
    AutoDownloadStore.markAutoQueued("synthetic-A", "show-1", "ep-a")
    AutoDownloadStore.claimManual("synthetic-A", "show-1", "ep-b")
    fun retain() = AutoDownloadRetention.apply(context, "synthetic-A", "show-1", AutoDownloadStore.get("synthetic-A", "show-1"),
            emptySet(), setOf("ep-a", "ep-b"), emptyMap<String, MediaProgress>(), emptySet())
    // a legacy shared record: B's track points at A's file
    val item = local()
    val podcast = item.media as Podcast
    val aTrack = podcast.episodes!!.single { it.serverEpisodeId == "ep-a" }.audioTrack!!
    val bEpisode = podcast.episodes!!.single { it.serverEpisodeId == "ep-b" }
    val bTrack = bEpisode.audioTrack
    bEpisode.audioTrack = aTrack
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    assertEquals(emptyList<String>(), retain())
    assertArrayEquals(bytes("AUTO-A"), a.readBytes())
    // distinct records again: only A's own file goes, B (manual) is untouched
    bEpisode.audioTrack = bTrack
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    assertEquals(listOf("ep-a"), retain())
    assertFalse(a.exists())
    assertArrayEquals(bytes("MANUAL-B"), b.readBytes())
  }
}
