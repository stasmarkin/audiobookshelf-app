package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.AudioTrack
import app.absplus.android.data.LocalFile
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaProgress
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastAutoDownloadEntry
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.DeviceManager
import app.absplus.android.managers.AutoDownloadManager.Trigger
import app.absplus.android.managers.AutoDownloadPolicy.LocalCopy
import app.absplus.android.managers.AutoDownloadPolicy.WindowAction
import app.absplus.android.managers.DownloadItemManager.QueuedState
import io.paperdb.Paper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * Per-podcast retention choice of PR #23 (Claude): "Automatically manage downloads" (AUTO, the default) and "Keep all
 * downloads" (KEEP_ALL). Selection follows the newest window in both modes; only AUTO ever removes, and only eligible
 * auto-owned episodes. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoDownloadRetentionModeTest {
  private lateinit var context: Context
  private lateinit var previousServers: MutableList<ServerConnectionConfig>
  private var previousActive: ServerConnectionConfig? = null
  private var previousLast: app.absplus.android.data.PlaybackSession? = null
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
    val config = ServerConnectionConfig(server, 0, "Synthetic", "http://127.0.0.1:1", null, "alice", "synthetic", "synthetic-A", null)
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf(config)
    DeviceManager.serverConnectionConfig = config
    DeviceManager.deviceData.lastPlaybackSession = null
    File(context.filesDir, "downloads").deleteRecursively()
  }

  @After
  fun tearDown() {
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    DeviceManager.deviceData.lastPlaybackSession = previousLast
    AutoDownloadStore.clearCacheForTests()
  }

  private fun entry(owned: List<String> = emptyList(), manual: List<String> = emptyList(), retention: String = PodcastAutoDownloadEntry.RETENTION_AUTO) =
          PodcastAutoDownloadEntry(PodcastAutoDownloadEntry.ENABLED_ON, 2, owned.toMutableList(), manual.toMutableList(), mutableMapOf(), retention)

  private fun keepAll(owned: List<String> = emptyList(), manual: List<String> = emptyList()) =
          entry(owned, manual, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)

  private fun local(id: String) = AutoDownloadPolicy.LocalEpisode(id, inProgress = false, isLastPlayed = false, queued = false)

  private fun media(name: String, bytes: Int = 16) =
          File(context.filesDir, "downloads/$pod/$name").apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes) { 1 }) }

  private fun podcastLocal(localId: String, serverConfigId: String, vararg eps: Pair<String, File>): LocalLibraryItem {
    val episodes = eps.mapIndexed { i, (serverEp, file) ->
      val track = AudioTrack(i + 1, 0.0, 20.0, file.name, file.toURI().toString(), "audio/mpeg", null, true, "lf_$serverEp", null)
      PodcastEpisode("local_ep_$serverEp", i + 1, null, null, serverEp, null, null, null, i.toLong(), null, track, null, 20.0, file.length(), serverEp, "local_ep_$serverEp")
    }.toMutableList()
    val files = eps.map { (serverEp, file) -> LocalFile("lf_$serverEp", file.name, file.toURI().toString(), file.parent, file.absolutePath, "audio/mpeg", file.length()) }.toMutableList()
    val podcast = Podcast(PodcastMetadata("Synthetic", null, null, mutableListOf(), false), null, mutableListOf(), episodes, false, episodes.size)
    return LocalLibraryItem(localId, "internal-podcast", "", "", "", false, "podcast", podcast, files, null, null, true, serverConfigId, "http://127.0.0.1:1", "alice", pod)
  }

  private fun retain(e: PodcastAutoDownloadEntry, serverEpisodeIds: Set<String>, window: Set<String> = emptySet()) =
          AutoDownloadRetention.apply(context, server, pod, e, window, serverEpisodeIds, emptyMap<String, MediaProgress>(), emptySet())

  private fun savedServerEpisodes(localId: String = "local_$pod") =
          (DeviceManager.dbManager.getLocalLibraryItem(localId)!!.media as Podcast).episodes!!.map { it.serverEpisodeId }

  // ---- 1. default mode, unknown and older records ----

  @Test
  fun newAndOlderRecordsManageDownloadsAndAnUnknownModeKeepsAll() {
    assertEquals(PodcastAutoDownloadEntry.RETENTION_AUTO, AutoDownloadStore.get(server, pod).retention)
    // a record saved before the choice existed (no retention field): Paper skips constructors, so the field reads null
    val older = entry(owned = listOf("e1"))
    PodcastAutoDownloadEntry::class.java.getDeclaredField("retention").apply { isAccessible = true }.set(older, null)
    DeviceManager.dbManager.savePodcastAutoDownloadEntry("$server:$pod", older)
    assertEquals(PodcastAutoDownloadEntry.RETENTION_AUTO, AutoDownloadStore.get(server, pod).retention)
    assertEquals(listOf("e1"), AutoDownloadStore.get(server, pod).autoDownloadedEpisodeIds)
    // a value this version does not know never enables deletion
    DeviceManager.dbManager.savePodcastAutoDownloadEntry("$server:$pod", entry(owned = listOf("e1"), retention = "SOMETHING_NEW"))
    assertEquals(PodcastAutoDownloadEntry.RETENTION_KEEP_ALL, AutoDownloadStore.get(server, pod).retention)
  }

  // ---- 2./7. keep all vs automatic cleanup ----

  @Test
  fun keepAllNeverRemovesWhileAutoRemovesOnlyEligibleAutoOwnedEpisodes() {
    val local = listOf(local("old"), local("mine"), local("pre"))
    val ids = setOf("old", "mine", "pre")
    assertEquals(emptyList<String>(), AutoDownloadPolicy.retentionDeletions(keepAll(owned = listOf("old"), manual = listOf("mine")), emptySet(), ids, local))
    assertEquals(listOf("old"), AutoDownloadPolicy.retentionDeletions(entry(owned = listOf("old"), manual = listOf("mine")), emptySet(), ids, local))
  }

  @Test
  fun keepAllExecutorDeletesNoFileEvenWhenHandedAnOwnedEpisodeOutsideTheWindow() {
    val old = media("old.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "old" to old))
    AutoDownloadStore.markAutoQueued(server, pod, "old")
    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)

    assertEquals(emptyList<String>(), retain(AutoDownloadStore.get(server, pod), setOf("old")))
    assertTrue(old.exists())
    assertEquals(listOf("old"), savedServerEpisodes())
  }

  // ---- 3. switching modes ----

  @Test
  fun switchingModesKeepsOwnershipAndOnlyTheNextManagedPassRemoves() {
    val old = media("old.mp3"); val mine = media("mine.mp3"); val pre = media("pre.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "old" to old, "mine" to mine, "pre" to pre))
    AutoDownloadStore.markAutoQueued(server, pod, "old")
    AutoDownloadStore.claimManual(server, pod, "mine")

    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)
    var e = AutoDownloadStore.get(server, pod)
    assertEquals(listOf("old"), e.autoDownloadedEpisodeIds)
    assertEquals(listOf("mine"), e.manualEpisodeIds)
    assertEquals(emptyList<String>(), retain(e, setOf("old", "mine", "pre")))

    // switching back changes the record only; the settings change itself removes nothing
    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, PodcastAutoDownloadEntry.RETENTION_AUTO)
    assertTrue(old.exists() && mine.exists() && pre.exists())
    e = AutoDownloadStore.get(server, pod)
    assertEquals(listOf("old"), e.autoDownloadedEpisodeIds)
    // a check started under Keep all, the check right after saving settings: no retention
    assertFalse(AutoDownloadPolicy.retentionRuns(Trigger.APP, keepAll(), e))
    assertFalse(AutoDownloadPolicy.retentionRuns(Trigger.WORKER, e, keepAll()))
    assertFalse(AutoDownloadPolicy.retentionRuns(Trigger.SETTINGS, e, e))
    // the next regular pass re-evaluates everything and removes only the eligible auto-owned episode
    assertTrue(AutoDownloadPolicy.retentionRuns(Trigger.APP, e, e))
    assertTrue(AutoDownloadPolicy.retentionRuns(Trigger.WORKER, e, e))
    assertEquals(listOf("old"), retain(e, setOf("old", "mine", "pre")))
    assertFalse(old.exists())
    assertTrue(mine.exists() && pre.exists())
  }

  @Test
  fun anOlderCallerWithoutARetentionValueKeepsTheSavedChoice() {
    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 2, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)
    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 4)
    val e = AutoDownloadStore.get(server, pod)
    assertEquals(PodcastAutoDownloadEntry.RETENTION_KEEP_ALL, e.retention)
    assertEquals(4, e.keepCount)
  }

  // ---- 4. newest-N selection is the same in both modes ----

  @Test
  fun selectionFollowsTheNewestWindowInBothModes() {
    data class Ep(val id: String, val at: Long?, val playable: Boolean = true)
    val eps = listOf(Ep("a", 1), Ep("b", 5), Ep("c", 3), Ep("d", null), Ep("e", 4, playable = false))
    val window = AutoDownloadPolicy.newestWindow(eps, 2, { it.at }) { it.playable }.map { it.id }
    assertEquals(listOf("b", "c"), window)
    for (e in listOf(entry(), keepAll())) {
      assertEquals(WindowAction.ENQUEUE, AutoDownloadPolicy.windowAction(e, "b", QueuedState.NOT_QUEUED, LocalCopy.NONE))
      assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(e, "b", QueuedState.NOT_QUEUED, LocalCopy.VALID))
      assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(e, "b", QueuedState.PENDING, LocalCopy.NONE))
    }
  }

  // ---- 5./6. manual and earlier downloads, both modes ----

  @Test
  fun manualAndEarlierDownloadsSurviveBothModes() {
    val mine = media("mine.mp3"); val pre = media("pre.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "mine" to mine, "pre" to pre))
    AutoDownloadStore.claimManual(server, pod, "mine")
    for (mode in listOf(PodcastAutoDownloadEntry.RETENTION_AUTO, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)) {
      AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, mode)
      // a later auto check cannot take over a manual download
      assertFalse(AutoDownloadStore.markAutoQueued(server, pod, "mine"))
      assertEquals(emptyList<String>(), retain(AutoDownloadStore.get(server, pod), setOf("mine", "pre")))
    }
    assertTrue(mine.exists() && pre.exists())
    assertEquals(listOf("mine", "pre"), savedServerEpisodes())
  }

  // ---- 8. persistence across restart ----

  @Test
  fun theChoicePersistsAcrossARestartTogetherWithOwnership() {
    AutoDownloadStore.markAutoQueued(server, pod, "e1")
    AutoDownloadStore.claimManual(server, pod, "e2")
    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 3, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)
    // process restart: in-memory cache gone, Paper re-read
    AutoDownloadStore.clearCacheForTests()
    Paper.init(context)
    val e = AutoDownloadStore.get(server, pod)
    assertEquals(PodcastAutoDownloadEntry.RETENTION_KEEP_ALL, e.retention)
    assertEquals(3, e.keepCount)
    assertEquals(listOf("e1"), e.autoDownloadedEpisodeIds)
    assertEquals(listOf("e2"), e.manualEpisodeIds)
    assertTrue(AutoDownloadStore.isAutoOwned(server, pod, "e1"))
    assertEquals(PodcastAutoDownloadEntry.RETENTION_KEEP_ALL, AutoDownloadStore.getEntriesForServer(server)[pod]!!.retention)
  }

  // ---- 10. servers stay apart ----

  @Test
  fun eachServerKeepsItsOwnChoiceAndRetentionNeverCrossesServers() {
    val mineA = media("a.mp3")
    val fileB = File(context.filesDir, "downloads/other/b.mp3").apply { parentFile?.mkdirs(); writeBytes(ByteArray(8) { 1 }) }
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "a" to mineA))
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_other", otherServer, "a" to fileB))
    AutoDownloadStore.markAutoQueued(server, pod, "a")
    AutoDownloadStore.markAutoQueued(otherServer, pod, "a")
    AutoDownloadStore.setSettings(otherServer, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)

    assertEquals(PodcastAutoDownloadEntry.RETENTION_AUTO, AutoDownloadStore.get(server, pod).retention)
    assertEquals(PodcastAutoDownloadEntry.RETENTION_KEEP_ALL, AutoDownloadStore.get(otherServer, pod).retention)
    // server A manages its own copy; B's copy (Keep all) is untouched
    assertEquals(listOf("a"), retain(AutoDownloadStore.get(server, pod), setOf("a")))
    assertFalse(mineA.exists())
    assertTrue(fileB.exists())
    assertEquals(listOf("a"), savedServerEpisodes("local_other"))
    // B under Keep all, even when asked directly
    assertEquals(emptyList<String>(), AutoDownloadRetention.apply(context, otherServer, pod, AutoDownloadStore.get(otherServer, pod),
            emptySet(), setOf("a"), emptyMap(), emptySet()))
    assertTrue(fileB.exists())
  }

  // ---- 11. concurrent settings, manual requests and playback ----

  @Test
  fun aStalePlanDeletesNothingAfterKeepAllAManualRequestOrPlaybackLandedInBetween() {
    val a = media("a.mp3"); val b = media("b.mp3"); val c = media("c.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "a" to a, "b" to b, "c" to c))
    listOf("a", "b", "c").forEach { AutoDownloadStore.markAutoQueued(server, pod, it) }
    val stalePlan = AutoDownloadStore.get(server, pod) // AUTO, owns a, b, c

    // Keep all chosen after the plan was made
    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)
    assertEquals(emptyList<String>(), retain(stalePlan, setOf("a", "b", "c")))
    assertTrue(a.exists() && b.exists() && c.exists())

    // back to managed; then a manual request for b and playback of c land after the plan
    AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, PodcastAutoDownloadEntry.RETENTION_AUTO)
    val plan = AutoDownloadStore.get(server, pod)
    AutoDownloadStore.claimManual(server, pod, "b")
    DeviceManager.dbManager.saveLocalMediaProgress(LocalMediaProgress("local_$pod-local_ep_c", "local_$pod", "local_ep_c", 20.0, 0.25, 5.0, false,
            null, null, 1L, 1L, null, server, null, "alice", pod, "c"))
    assertEquals(listOf("a"), retain(plan, setOf("a", "b", "c")))
    assertFalse(a.exists())
    assertTrue(b.exists() && c.exists())
    assertTrue(DeviceManager.dbManager.getLocalMediaProgress("local_$pod-local_ep_c") != null)
  }

  @Test
  fun aKeepAllChoiceWaitsForAnInFlightRemovalAndBlocksEveryLaterOne() {
    AutoDownloadStore.markAutoQueued(server, pod, "a")
    AutoDownloadStore.markAutoQueued(server, pod, "b")
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)
    val saved = CountDownLatch(1)
    val remover = Thread {
      AutoDownloadStore.deleteIfStillRemovable(server, pod, "a") {
        inside.countDown()
        release.await(5, TimeUnit.SECONDS)
        true
      }
    }.apply { start() }
    assertTrue(inside.await(5, TimeUnit.SECONDS))
    val chooser = Thread {
      AutoDownloadStore.setSettings(server, pod, PodcastAutoDownloadEntry.ENABLED_ON, 1, PodcastAutoDownloadEntry.RETENTION_KEEP_ALL)
      saved.countDown()
    }.apply { start() }
    // the choice cannot interleave with a removal that already passed its checks
    assertFalse(saved.await(300, TimeUnit.MILLISECONDS))
    release.countDown()
    assertTrue(saved.await(5, TimeUnit.SECONDS))
    remover.join(5000); chooser.join(5000)
    var ran = false
    assertFalse(AutoDownloadStore.deleteIfStillRemovable(server, pod, "b") { ran = true; true })
    assertFalse(ran)
  }

  // ---- 12. empty local copies ----

  @Test
  fun anEmptyLocalFileIsNotAValidDownloadAndIsNeverRemovedAutomatically() {
    assertEquals(LocalCopy.NONE, AutoDownloadPolicy.localCopyOf(null))
    assertEquals(LocalCopy.EMPTY, AutoDownloadPolicy.localCopyOf(0L))
    assertEquals(LocalCopy.VALID, AutoDownloadPolicy.localCopyOf(1L))
    // not re-downloaded automatically either: that would add a second local record for the episode
    assertEquals(WindowAction.SKIP, AutoDownloadPolicy.windowAction(entry(), "e", QueuedState.NOT_QUEUED, LocalCopy.EMPTY))

    val empty = media("empty.mp3", bytes = 0); val full = media("full.mp3")
    DeviceManager.dbManager.saveLocalLibraryItem(podcastLocal("local_$pod", server, "empty" to empty, "full" to full))
    AutoDownloadStore.markAutoQueued(server, pod, "empty")
    AutoDownloadStore.markAutoQueued(server, pod, "full")
    assertEquals(listOf("full"), retain(AutoDownloadStore.get(server, pod), setOf("empty", "full")))
    assertTrue(empty.exists())
    assertEquals(0L, empty.length())
    assertEquals(listOf("empty"), savedServerEpisodes())
  }
}
