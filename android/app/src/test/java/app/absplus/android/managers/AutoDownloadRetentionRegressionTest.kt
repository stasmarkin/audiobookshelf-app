package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.PlaybackSession
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastAutoDownloadEntry
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.DeviceManager
import io.paperdb.Paper
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the retention guards that the 2026-10-15 mutation run found without coverage (m07, m22, m24,
 * m31; Claude). Every case seeds real ownership in the store, so only the guard under test stands between the episode
 * and deletion, and every case also removes an unprotected control episode, so the deletion path provably ran.
 * Synthetic files and accounts only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoDownloadRetentionRegressionTest {
  private lateinit var context: Context
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private var previousLast: PlaybackSession? = null
  private val server = "srvA"
  private val otherServer = "srvB"
  private val pod = "li_pod"

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    listOf("downloadItems", "localLibraryItems", "localMediaProgress", "autoDownload").forEach { Paper.book(it).destroy() }
    AutoDownloadStore.clearCacheForTests()
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    previousLast = DeviceManager.deviceData.lastPlaybackSession
    val a = ServerConnectionConfig(server, 0, "Synthetic A", "http://127.0.0.1:1", null, "alice", "synthetic", "synthetic-A", null)
    val b = ServerConnectionConfig(otherServer, 1, "Synthetic B", "http://127.0.0.1:2", null, "bob", "synthetic", "synthetic-B", null)
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(a, b)
    DeviceManager.serverConnectionConfig = a
    DeviceManager.deviceData.lastPlaybackSession = null
    listOf("downloads", "downloads-shadow", "elsewhere", "escape.mp3").forEach { File(context.filesDir, it).deleteRecursively() }
    File(context.cacheDir, "outside.mp3").delete()
  }

  @After
  fun tearDown() {
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    DeviceManager.deviceData.lastPlaybackSession = previousLast
    AutoDownloadStore.clearCacheForTests()
  }

  private fun media(name: String, dir: String = "downloads/$pod") =
          File(context.filesDir, "$dir/$name").apply { parentFile?.mkdirs(); writeBytes(ByteArray(16) { 1 }) }

  private fun podcastLocal(localId: String, serverConfigId: String, vararg eps: Pair<String, File>): LocalLibraryItem {
    val episodes = eps.mapIndexed { i, (serverEp, file) ->
      val track = AudioTrack(i + 1, 0.0, 20.0, file.name, file.toURI().toString(), "audio/mpeg", null, true, "lf_$serverEp", null)
      PodcastEpisode("local_ep_$serverEp", i + 1, null, null, serverEp, null, null, null, i.toLong(), null, track, null, 20.0, file.length(), serverEp, "local_ep_$serverEp")
    }.toMutableList()
    // absolutePath is stored as given (it may contain ".."), like a record written from an untrusted path
    val files = eps.map { (serverEp, file) -> LocalFile("lf_$serverEp", file.name, file.toURI().toString(), file.parent, file.path, "audio/mpeg", file.length()) }.toMutableList()
    val podcast = Podcast(PodcastMetadata("Synthetic", null, null, mutableListOf(), false), null, mutableListOf(), episodes, false, episodes.size)
    return LocalLibraryItem(localId, "internal-podcast", "", "", "", false, "podcast", podcast, files, null, null, true, serverConfigId, "http://127.0.0.1:1", "alice", pod)
  }

  private fun own(serverConfigId: String, vararg ids: String) = ids.forEach { AutoDownloadStore.markAutoQueued(serverConfigId, pod, it) }

  private fun retain(plan: PodcastAutoDownloadEntry, serverEpisodeIds: Set<String>, serverProgress: Map<String, MediaProgress> = emptyMap()) =
          AutoDownloadRetention.apply(context, server, pod, plan, emptySet(), serverEpisodeIds, serverProgress, emptySet())

  private fun savedServerEpisodes(localId: String) =
          (DeviceManager.dbManager.getLocalLibraryItem(localId)!!.media as Podcast).episodes!!.map { it.serverEpisodeId }

  private fun localProgress(serverEp: String) = LocalMediaProgress("local_$pod-local_ep_$serverEp", "local_$pod", "local_ep_$serverEp", 20.0, 0.25, 5.0,
          false, null, null, 1L, 1L, null, server, null, "alice", pod, serverEp)

  private fun sessionOf(serverEp: String) = PlaybackSession("s", "alice", pod, serverEp, "podcast", PodcastMetadata("Synthetic", null, null, mutableListOf(), false),
          DeviceInfo("d", "m", "b", 35, "v"), emptyList(), null, null, null, 20.0, 3, 1L, 1L, 0L, mutableListOf(), 0.0, null, null, null, server, null, null)

  private fun remote(serverEp: String, currentTime: Double, finished: Boolean) =
          MediaProgress("p_$serverEp", pod, serverEp, 20.0, currentTime / 20.0, currentTime, finished, null, null, 1L, 1L, null)

  // 1. Playback protection (m07): playback that starts after the plan, while the removal waits for the store lock

  @Test
  fun anEpisodeThatStartsPlayingWhileItsRemovalWaitsIsKept() {
    val started = media("started.mp3"); val playing = media("playing.mp3"); val control = media("control.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "started" to started, "playing" to playing, "control" to control))
    own(server, "started", "playing", "control")
    val plan = AutoDownloadStore.get(server, pod)
    var removed: List<String>? = null
    val remover: Thread
    // Holding the store lock parks the removal after its plan, exactly where the per-file recheck runs
    synchronized(AutoDownloadStore) {
      remover = Thread { removed = retain(plan, setOf("started", "playing", "control")) }.apply { start() }
      val deadline = System.currentTimeMillis() + 5000
      while (remover.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.sleep(5)
      assertEquals(Thread.State.BLOCKED, remover.state)
      DeviceManager.dbManager.saveLocalMediaProgress(localProgress("started")) // listening began on this device
      DeviceManager.deviceData.lastPlaybackSession = sessionOf("playing") // loaded in the player
    }
    remover.join(5000)
    assertFalse(remover.isAlive)

    assertEquals(listOf("control"), removed)
    assertTrue(started.exists() && playing.exists())
    assertFalse(control.exists())
    assertEquals(listOf("started", "playing"), savedServerEpisodes("local_$pod"))
  }

  // 2. Cross-device progress (m22): server progress from another device protects only the episode being listened to

  @Test
  fun progressFromAnotherDeviceKeepsOnlyTheEpisodeInProgress() {
    val listening = media("listening.mp3"); val finished = media("finished.mp3"); val control = media("control.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "listening" to listening, "finished" to finished, "control" to control))
    own(server, "listening", "finished", "control")
    val serverProgress = mapOf("listening" to remote("listening", 5.0, false), "finished" to remote("finished", 20.0, true))

    val removed = retain(AutoDownloadStore.get(server, pod), setOf("listening", "finished", "control"), serverProgress)

    assertEquals(setOf("finished", "control"), removed.toSet())
    assertTrue(listening.exists())
    assertFalse(finished.exists() || control.exists())
    assertEquals(listOf("listening"), savedServerEpisodes("local_$pod"))
  }

  // 3. Server isolation (m24): another server's records neither leak into this server's entries nor authorize deletion

  @Test
  fun anotherServersRecordsNeverAuthorizeDeletionHere() {
    val mine = media("mine.mp3"); val control = media("control.mp3")
    val theirs = media("theirs.mp3", dir = "downloads/${pod}_b")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "mine" to mine, "control" to control))
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_b_$pod", otherServer, "theirs" to theirs, "mine" to media("mine_b.mp3", dir = "downloads/${pod}_b")))
    own(server, "control")
    own(otherServer, "mine", "theirs") // B owns the episode A has only manually, plus B's own copy
    AutoDownloadStore.setSettings(otherServer, "li_b_only", PodcastAutoDownloadEntry.ENABLED_ON, 1)

    // the check's view of server A holds A's records only
    val entries = AutoDownloadStore.getEntriesForServer(server)
    assertEquals(setOf(pod), entries.keys)
    assertEquals(listOf("control"), entries.getValue(pod).autoDownloadedEpisodeIds)

    // even a plan built from B's record cannot remove A's file, and A's run never touches B's copies
    val crossPlan = AutoDownloadStore.get(otherServer, pod)
    assertEquals(emptyList<String>(), retain(crossPlan, setOf("mine", "theirs", "control")))
    assertEquals(listOf("control"), retain(entries.getValue(pod), setOf("mine", "theirs", "control")))
    assertTrue(mine.exists() && theirs.exists())
    assertFalse(control.exists())
    assertEquals(listOf("theirs", "mine"), savedServerEpisodes("local_b_$pod"))
  }

  // 4. Filesystem containment (m31): only files inside the app's downloads folder are ever deleted

  @Test
  fun filesOutsideAppDownloadStorageAreNeverDeleted() {
    val cache = File(context.cacheDir, "outside.mp3").apply { writeBytes(ByteArray(16) { 1 }) }
    val elsewhere = media("elsewhere.mp3", dir = "elsewhere")
    val shadow = media("shadow.mp3", dir = "downloads-shadow") // shares the "downloads" prefix
    media("escape.mp3", dir = ".")
    val escape = File(context.filesDir, "downloads/$pod/../../escape.mp3") // resolves outside through ".."
    val control = media("control.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server,
            "cache" to cache, "elsewhere" to elsewhere, "shadow" to shadow, "escape" to escape, "control" to control))
    own(server, "cache", "elsewhere", "shadow", "escape", "control")

    val removed = retain(AutoDownloadStore.get(server, pod), setOf("cache", "elsewhere", "shadow", "escape", "control"))

    assertEquals(listOf("control"), removed)
    assertTrue(cache.exists() && elsewhere.exists() && shadow.exists() && escape.canonicalFile.exists())
    assertFalse(control.exists())
    assertEquals(listOf("cache", "elsewhere", "shadow", "escape"), savedServerEpisodes("local_$pod"))
  }
}
