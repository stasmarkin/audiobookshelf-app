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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Baseline reproduction, intentionally asserts the defect. Not a passing safety regression. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadCollisionReproductionTest {
  private lateinit var context: Context
  private lateinit var manager: DownloadItemManager
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private var done = CountDownLatch(1)
  private val folder = LocalFolder("internal-podcast", "Synthetic", "", "", "", "internal", "podcast")
  private val bytesA = "SYNTHETIC-EPISODE-A".toByteArray()
  private val bytesB = "SYNTHETIC-EPISODE-B-DIFFERENT".toByteArray()

  @Before fun setup() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    for (book in listOf("downloadItems", "localLibraryItems", "localMediaProgress", "podcastAutoDownload")) Paper.book(book).destroy()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    val config = ServerConnectionConfig("synthetic-A", 0, "Fixture", "http://127.0.0.1:1", null, "synthetic-user", "fixture", "synthetic-token", null)
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(config)
    DeviceManager.serverConnectionConfig = config
    try { WorkManager.initialize(context, Configuration.Builder().setExecutor { it.run() }.build()) } catch (_: IllegalStateException) {}
    File(context.filesDir, "downloads/show-1").deleteRecursively()
    File(context.filesDir, "download-staging/show-1").deleteRecursively()
    manager = DownloadItemManager(FolderScanner(context), context, object : DownloadItemManager.DownloadEventEmitter {
      override fun onDownloadItem(downloadItem: DownloadItem) {}
      override fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart) {}
      override fun onDownloadItemComplete(jsobj: JSObject) { done.countDown() }
      override fun onQueueChanged(hasWork: Boolean) {}
    })
  }
  @After fun cleanup() {
    manager.destroy()
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
  }

  private fun sanitized(path: String): String = LibraryItemDownloadStarter::class.java
      .getDeclaredMethod("getFilenameFromRelPath", String::class.java).apply { isAccessible = true }
      .invoke(LibraryItemDownloadStarter, path) as String

  private fun fixture(ep: String, rel: String, bytes: ByteArray): DownloadItem {
    val name = sanitized(rel)
    val stage = File(context.filesDir, "download-staging/show-1/$name.part")
    val final = File(context.filesDir, "downloads/show-1/$name")
    val metadata = FileMetadata(rel.substringAfterLast('/'), "mp3", "/synthetic/$rel", rel, bytes.size.toLong())
    val track = AudioTrack(1, 0.0, 60.0, ep, "", "audio/mpeg", metadata, false, null, 1)
    val episode = PodcastEpisode(ep, 1, null, null, ep, null, null, null, 1L, AudioFile(1, "ino-$ep", metadata), track, null, 60.0, bytes.size.toLong(), null, null)
    val part = DownloadItemPart.make("show-1-$ep", name, bytes.size.toLong(), stage, final, "Synthetic", "/api/items/show-1/file/ino-$ep/download", folder, null, track, episode)
    part.completed = true
    val media = Podcast(PodcastMetadata("Synthetic", "Fixture", null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0)
    return DownloadItem("show-1-$ep", "show-1", ep, null, "synthetic-A", "http://127.0.0.1:1", "synthetic-user", "podcast", final.parent, folder, "Synthetic", "Synthetic", media, mutableListOf(part))
  }
  private fun finalize(item: DownloadItem, bytes: ByteArray) {
    done = CountDownLatch(1)
    val part = item.downloadItemParts.single()
    File(part.destinationPath).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    manager.addDownloadItem(item)
    DownloadItemManager::class.java.getDeclaredMethod("finalizeInternalFile", DownloadItem::class.java, DownloadItemPart::class.java)
        .apply { isAccessible = true }.invoke(manager, item, part)
    assertTrue("Registration must complete without network", done.await(15, TimeUnit.SECONDS))
  }

  @Test fun sequentialCollidingEpisodesOverwriteBytesAndAliasPersistedRecords() {
    val a = fixture("ep-a", "Season1/episode.mp3", bytesA)
    finalize(a, bytesA)
    val old = DeviceManager.dbManager.getLocalLibraryItem("local_show-1")!!
    assertArrayEquals(bytesA, File(old.localFiles.single().absolutePath).readBytes())
    val b = fixture("ep-b", "Season1_episode.mp3", bytesB)
    assertEquals(a.downloadItemParts.single().finalDestinationPath, b.downloadItemParts.single().finalDestinationPath)
    assertNotEquals(a.id, b.id)
    finalize(b, bytesB)
    val saved = DeviceManager.dbManager.getLocalLibraryItem("local_show-1")!!
    val episodes = (saved.media as Podcast).episodes!!
    assertEquals(setOf("ep-a", "ep-b"), episodes.map { it.serverEpisodeId }.toSet())
    assertEquals(1, saved.localFiles.size)
    assertEquals(1, episodes.map { it.audioTrack!!.localFileId }.distinct().size)
    assertArrayEquals("DEFECT: A's persisted path now contains B", bytesB, File(old.localFiles.single().absolutePath).readBytes())
    assertFalse(File(old.localFiles.single().absolutePath).resolveSibling(".Season1_episode.mp3.abs-backup").exists())
  }

  @Test fun filenameTransformIsLossyButDoesNotUnicodeNormalizeOrCaseFold() {
    assertEquals(sanitized("Season1/episode.mp3"), sanitized("Season1_episode.mp3"))
    assertEquals(sanitized("a?b.mp3"), sanitized("ab.mp3"))
    assertEquals(sanitized("_a.mp3"), sanitized("a.mp3"))
    assertNotEquals(sanitized("Episode.mp3"), sanitized("episode.mp3"))
    assertNotEquals(sanitized("caf\u00e9.mp3"), sanitized("cafe\u0301.mp3"))
    assertEquals(304, sanitized("x".repeat(300) + ".mp3").length)
  }
}
