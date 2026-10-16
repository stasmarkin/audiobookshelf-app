package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import io.paperdb.Paper
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Manual deletion of one downloaded track never takes another episode's audio, record or progress (R29-N02). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalTrackDeletionTest {
  private lateinit var context: Context
  private lateinit var dir: File

  @Before fun setup() {
    context = ApplicationProvider.getApplicationContext()
    Paper.init(context)
    for (book in listOf("localLibraryItems", "localMediaProgress")) Paper.book(book).destroy()
    dir = File(context.filesDir, "downloads/show-del").apply { deleteRecursively(); mkdirs() }
  }

  private fun file(name: String, content: String) = File(dir, name).apply { writeText(content) }

  private fun localFile(f: File) = LocalFile("lf_${f.name}", f.name, f.toURI().toString(), dir.path, f.absolutePath, "audio/mpeg", f.length())

  private fun episode(serverId: String?, localId: String, f: File, index: Int) = PodcastEpisode(localId, index, null, null, serverId, null, null, null, null, null,
          AudioTrack(index, 0.0, 60.0, f.name, f.toURI().toString(), "audio/mpeg", null, true, "lf_${f.name}", index), null, 60.0, f.length(), serverId, localId)

  private fun podcastItem(files: List<File>, episodes: List<PodcastEpisode>) = LocalLibraryItem("local_show-del", "internal-podcast", dir.path, dir.path, "",
          false, "podcast", Podcast(PodcastMetadata("Synthetic", null, null, mutableListOf(), false), null, mutableListOf(), episodes.toMutableList(), false, episodes.size),
          files.map { localFile(it) }.toMutableList(), null, null, true, "synthetic-A", "http://127.0.0.1:1", "user-a", "show-del")

  private fun progress(localEpisodeId: String, serverEpisodeId: String) = LocalMediaProgress("local_show-del-$localEpisodeId", "local_show-del", localEpisodeId,
          60.0, 0.5, 30.0, false, null, null, 1L, 1L, null, "synthetic-A", "http://127.0.0.1:1", "user-a", "show-del", serverEpisodeId)

  private fun episodes(item: LocalLibraryItem) = (item.media as Podcast).episodes!!

  @Test fun aFileSharedByTwoEpisodesIsNotDeletedWithoutKnowingWhichEpisodeGoes() {
    val shared = file("episode.mp3", "SHARED")
    val item = podcastItem(listOf(shared), listOf(episode("ep-a", "local_ep_ep-a", shared, 1), episode("ep-b", "local_ep_ep-b", shared, 2)))
    var deleted = false
    assertEquals(LocalTrackDeletion.Outcome.REFUSED_SHARED, LocalTrackDeletion.delete(item, "lf_episode.mp3", null) { deleted = true; shared.delete() })
    assertFalse(deleted)
    assertTrue(shared.exists())
    assertEquals(listOf("ep-a", "ep-b"), episodes(item).map { it.serverEpisodeId })
    assertEquals(1, item.localFiles.size)
  }

  @Test fun removingOneOfTwoEpisodesOnASharedFileKeepsTheFileTheOtherRecordAndItsProgress() {
    val shared = file("episode.mp3", "SHARED")
    val item = podcastItem(listOf(shared), listOf(episode("ep-a", "local_ep_ep-a", shared, 1), episode("ep-b", "local_ep_ep-b", shared, 2)))
    DeviceManager.dbManager.saveLocalMediaProgress(progress("local_ep_ep-b", "ep-b"))
    var deleted = false
    assertEquals(LocalTrackDeletion.Outcome.RECORD_ONLY, LocalTrackDeletion.delete(item, "lf_episode.mp3", "local_ep_ep-a") { deleted = true; shared.delete() })
    assertFalse(deleted)
    assertEquals("SHARED", shared.readText())
    assertEquals(listOf("ep-b"), episodes(item).map { it.serverEpisodeId })
    assertEquals(1, episodes(item).single().index)
    assertEquals("lf_episode.mp3", episodes(item).single().audioTrack!!.localFileId)
    assertEquals(1, item.localFiles.size)
    assertNotNull(DeviceManager.dbManager.getLocalMediaProgress("local_show-del-local_ep_ep-b"))
  }

  @Test fun anEpisodesOwnFileIsDeletedAndTheOtherEpisodeIsUntouched() {
    val a = file("a.mp3", "A")
    val b = file("b.mp3", "B")
    val item = podcastItem(listOf(a, b), listOf(episode("ep-a", "local_ep_ep-a", a, 1), episode("ep-b", "local_ep_ep-b", b, 2)))
    assertEquals(LocalTrackDeletion.Outcome.DELETED, LocalTrackDeletion.delete(item, "lf_a.mp3", "local_ep_ep-a") { a.delete() })
    assertFalse(a.exists())
    assertEquals("B", b.readText())
    assertEquals(listOf("ep-b"), episodes(item).map { it.serverEpisodeId })
    assertEquals(listOf("lf_b.mp3"), item.localFiles.map { it.id })
  }

  @Test fun duplicateEntriesOfOneEpisodeAreOneOwnerAndDeleteNormally() {
    val f = file("episode.mp3", "ONE")
    val item = podcastItem(listOf(f), listOf(episode("ep-a", "local_ep_ep-a", f, 1), episode("ep-a", "local_ep_ep-a-dup", f, 2)))
    assertEquals(LocalTrackDeletion.Outcome.DELETED, LocalTrackDeletion.delete(item, "lf_episode.mp3", null) { f.delete() })
    assertFalse(f.exists())
    assertTrue(episodes(item).isEmpty())
    assertTrue(item.localFiles.isEmpty())
  }

  @Test fun episodesWithoutServerIdsOnOneFileCountAsDifferentOwners() {
    val f = file("episode.mp3", "LOCAL")
    val item = podcastItem(listOf(f), listOf(episode(null, "local_ep_1", f, 1), episode(null, "local_ep_2", f, 2)))
    assertEquals(LocalTrackDeletion.Outcome.REFUSED_SHARED, LocalTrackDeletion.delete(item, "lf_episode.mp3", null) { f.delete() })
    assertTrue(f.exists())
    assertEquals(LocalTrackDeletion.Outcome.RECORD_ONLY, LocalTrackDeletion.delete(item, "lf_episode.mp3", "local_ep_2") { f.delete() })
    assertTrue(f.exists())
    assertEquals(listOf("local_ep_1"), episodes(item).map { it.id })
  }

  @Test fun aFileThatCannotBeDeletedChangesNothing() {
    val a = file("a.mp3", "A")
    val item = podcastItem(listOf(a), listOf(episode("ep-a", "local_ep_ep-a", a, 1)))
    assertEquals(LocalTrackDeletion.Outcome.FILE_NOT_DELETED, LocalTrackDeletion.delete(item, "lf_a.mp3", "local_ep_ep-a") { false })
    assertTrue(a.exists())
    assertEquals(1, episodes(item).size)
    assertEquals(1, item.localFiles.size)
  }

  @Test fun bookTrackDeletionIsUnchanged() {
    val t = file("track1.mp3", "T1")
    val metadata = BookMetadata("Synthetic", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null)
    val book = Book(metadata, null, mutableListOf(), null, null,
            mutableListOf(AudioTrack(1, 0.0, 60.0, "track1.mp3", t.toURI().toString(), "audio/mpeg", null, true, "lf_track1.mp3", 1)), null, null, null, 1)
    val item = LocalLibraryItem("local_book-del", "internal-book", dir.path, dir.path, "", false, "book", book, mutableListOf(localFile(t)), null, null, true, "synthetic-A", "http://127.0.0.1:1", "user-a", "book-del")
    assertEquals(LocalTrackDeletion.Outcome.DELETED, LocalTrackDeletion.delete(item, "lf_track1.mp3", null) { t.delete() })
    assertFalse(t.exists())
    assertTrue(item.localFiles.isEmpty())
  }
}
