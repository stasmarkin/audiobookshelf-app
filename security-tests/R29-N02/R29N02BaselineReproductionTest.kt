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

/**
 * R29-N02 baseline reproductions beyond Codex's sequential case (Claude, 2026-10-15). Fixture follows Codex's
 * DownloadCollisionReproductionTest. Every assertion describes the DEFECT on unmodified plus 6649f76e; these are
 * evidence, not safety regressions, and are expected to fail once the correction is in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class R29N02BaselineReproductionTest {
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
    AutoDownloadStore.clearCacheForTests()
  }

  private fun sanitized(path: String): String = LibraryItemDownloadStarter::class.java
      .getDeclaredMethod("getFilenameFromRelPath", String::class.java).apply { isAccessible = true }
      .invoke(LibraryItemDownloadStarter, path) as String

  private fun fixture(ep: String, rel: String, bytes: ByteArray, config: ServerConnectionConfig = configA): DownloadItem {
    val name = sanitized(rel)
    val stage = File(context.filesDir, "download-staging/show-1/$name.part")
    val final = File(context.filesDir, "downloads/show-1/$name")
    val metadata = FileMetadata(rel.substringAfterLast('/'), "mp3", "/synthetic/$rel", rel, bytes.size.toLong())
    val track = AudioTrack(1, 0.0, 60.0, ep, "", "audio/mpeg", metadata, false, null, 1)
    val episode = PodcastEpisode(ep, 1, null, null, ep, null, null, null, 1L, AudioFile(1, "ino-$ep", metadata), track, null, 60.0, bytes.size.toLong(), null, null)
    val part = DownloadItemPart.make("show-1-$ep", name, bytes.size.toLong(), stage, final, "Synthetic", "/api/items/show-1/file/ino-$ep/download", folder, null, track, episode)
    part.completed = true
    val media = Podcast(PodcastMetadata("Synthetic", "Fixture", null, mutableListOf(), false), null, mutableListOf(), mutableListOf(), false, 0)
    return DownloadItem("show-1-$ep", "show-1", ep, null, config.id, config.address, config.userId, "podcast", final.parent, folder, "Synthetic", "Synthetic", media, mutableListOf(part))
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

  @Test fun sameFilenameFromAnotherServerOverwritesTheFirstServersOnlyCopy() {
    val a = fixture("ep-1", "episode.mp3", "SERVER-A-BYTES".toByteArray(), configA)
    finalize(a, "SERVER-A-BYTES".toByteArray())
    val path = a.downloadItemParts.single().finalDestinationPath
    DeviceManager.serverConnectionConfig = configB
    val b = fixture("ep-2", "episode.mp3", "SERVER-B-DIFFERENT".toByteArray(), configB)
    assertEquals(path, b.downloadItemParts.single().finalDestinationPath)
    finalize(b, "SERVER-B-DIFFERENT".toByteArray())
    assertArrayEquals("DEFECT: server A's file now holds server B's bytes", "SERVER-B-DIFFERENT".toByteArray(), File(path).readBytes())
    val saved = DeviceManager.dbManager.getLocalLibraryItem("local_show-1")!!
    assertEquals("DEFECT: both servers' episodes share one local item", setOf("ep-1", "ep-2"), (saved.media as Podcast).episodes!!.map { it.serverEpisodeId }.toSet())
  }

  @Test fun afterACrashBetweenTheRenamesTheNextFinalizationDeletesTheOnlyCopy() {
    val a = fixture("ep-a", "Season1/episode.mp3", "ORIGINAL-A".toByteArray())
    finalize(a, "ORIGINAL-A".toByteArray())
    val finalFile = File(a.downloadItemParts.single().finalDestinationPath)
    val backup = File(finalFile.parentFile, ".${finalFile.name}.abs-backup")
    // process death right after "final -> backup": A's only copy is now the hidden backup
    assertTrue(finalFile.renameTo(backup))
    val b = fixture("ep-b", "Season1_episode.mp3", "REPLACEMENT-B".toByteArray())
    finalize(b, "REPLACEMENT-B".toByteArray())
    assertFalse("DEFECT: the backup holding A's only copy was deleted", backup.exists())
    assertArrayEquals("REPLACEMENT-B".toByteArray(), finalFile.readBytes())
  }

  @Test fun retentionOfAnAliasedAutoEpisodeDeletesTheManualEpisodesBytes() {
    finalize(fixture("ep-a", "Season1/episode.mp3", "AUTO-A".toByteArray()), "AUTO-A".toByteArray())
    finalize(fixture("ep-b", "Season1_episode.mp3", "MANUAL-B".toByteArray()), "MANUAL-B".toByteArray())
    val local = DeviceManager.dbManager.getLocalLibraryItem("local_show-1")!!
    val file = File(local.localFiles.single().absolutePath)
    AutoDownloadStore.markAutoQueued("synthetic-A", "show-1", "ep-a")
    AutoDownloadStore.claimManual("synthetic-A", "show-1", "ep-b")
    val removed = AutoDownloadRetention.apply(context, "synthetic-A", "show-1", AutoDownloadStore.get("synthetic-A", "show-1"),
            emptySet(), setOf("ep-a", "ep-b"), emptyMap<String, MediaProgress>(), emptySet())
    assertEquals(listOf("ep-a"), removed)
    assertFalse("DEFECT: removing auto episode A deleted the file manual episode B plays", file.exists())
  }
}
