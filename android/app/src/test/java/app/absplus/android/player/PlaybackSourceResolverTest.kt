package app.absplus.android.player

import app.absplus.android.data.DeviceInfo
import app.absplus.android.data.LibraryItem
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.PlaybackSession
import app.absplus.android.data.Podcast
import app.absplus.android.data.PodcastEpisode
import app.absplus.android.data.PodcastMetadata
import app.absplus.android.db.userdata.MediaProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceResolverTest {
  private fun book(id: String, serverId: String?, config: String? = "a", ino: String? = null) = LocalLibraryItem(
    id, "internal-book", "", "", "", false, "book",
    MediaType(MediaTypeMetadata("Book", false), null),
    mutableListOf(), null, null, true, config, null, null, serverId, ino
  )

  private fun episode(id: String, serverEpisodeId: String?) =
    PodcastEpisode(id, 0, null, null, "Episode", null, null, null, null, null, null, null, null, null, serverEpisodeId, null)

  private fun podcast(id: String, serverId: String, vararg episodes: PodcastEpisode) = LocalLibraryItem(
    id, "internal-podcast", "", "", "", false, "podcast",
    Podcast(PodcastMetadata("Podcast", null, null, mutableListOf(), false), null, mutableListOf(), episodes.toMutableList(), false, null),
    mutableListOf(), null, null, true, "a", null, null, serverId, null
  )

  private fun resolver(vararg items: LocalLibraryItem, broken: Set<String> = emptySet()) =
    PlaybackSourceResolver({ items.toList() }) { item, _ -> item.id !in broken }

  @Test fun downloadedBookIsAlwaysLocal() {
    val copy = book("local_1", "li1")
    val source = resolver(copy).resolve("a", "li1", null)
    assertTrue(source is PlaybackSource.Local)
    assertSame(copy, (source as PlaybackSource.Local).item)
  }

  @Test fun downloadedEpisodeMatchesByServerEpisodeId() {
    val e1 = episode("local_e1", "e1")
    val copy = podcast("local_p", "p", e1)
    val source = resolver(copy).resolve("a", "p", "e1") as PlaybackSource.Local
    assertSame(e1, source.episode)
  }

  @Test fun missingEpisodeDownloadStreams() {
    val copy = podcast("local_p", "p", episode("local_e1", "e1"))
    assertEquals(PlaybackSource.Stream("p", "e2"), resolver(copy).resolve("a", "p", "e2"))
  }

  @Test fun noCopyStreams() {
    assertEquals(PlaybackSource.Stream("li1", null), resolver(book("local_2", "li2")).resolve("a", "li1", null))
  }

  @Test fun brokenCopyIsReportedNotStreamed() {
    val copy = book("local_1", "li1")
    assertEquals(PlaybackSource.LocalBroken(copy), resolver(copy, broken = setOf("local_1")).resolve("a", "li1", null))
  }

  @Test fun legacyLocalIdResolvesLocally() {
    val copy = book("local_1", "li1")
    assertSame(copy, (resolver(copy).resolve("a", "local_1", null) as PlaybackSource.Local).item)
    // A Local Media item has no server at all
    val sideloaded = book("local_s", null, config = null)
    assertSame(sideloaded, (resolver(sideloaded).resolve(null, "local_s", null) as PlaybackSource.Local).item)
    val e1 = episode("local_e1", "e1")
    assertSame(e1, (resolver(podcast("local_p", "p", e1)).resolve("a", "local_p", "local_e1") as PlaybackSource.Local).episode)
    assertTrue(resolver().resolve("a", "local_gone", null) is PlaybackSource.NotFound)
  }

  @Test fun copyFromAnotherLibraryMatchesByIno() {
    val copy = book("local_b", "liB", ino = "42")
    assertSame(copy, (resolver(copy).resolve("a", "liA", null, ino = "42") as PlaybackSource.Local).item)
    assertEquals(PlaybackSource.Stream("liA", null), resolver(copy).resolve("a", "liA", null, ino = null))
  }

  @Test fun copyOfAnotherAccountIsIgnored() {
    val copy = book("local_1", "li1", config = "b")
    assertEquals(PlaybackSource.Stream("li1", null), resolver(copy).resolve("a", "li1", null))
    assertEquals(PlaybackSource.Stream("li1", null), resolver(copy).resolve(null, "li1", null))
  }

  @Test fun restoreOfStreamedSessionUsesNewLocalCopy() {
    val copy = book("local_1", "li1")
    val streamed = session("li1", null, playMethod = PLAYMETHOD_DIRECTPLAY, config = "a")
    assertSame(copy, (resolver(copy).restoreSource(streamed) as PlaybackSource.Local).item)
    assertEquals(PlaybackSource.Stream("li1", null), resolver().restoreSource(streamed))
    // A downloaded session whose copy is gone does not fall back to streaming
    val local = session("li1", copy, playMethod = PLAYMETHOD_LOCAL, config = "a")
    assertTrue(resolver().restoreSource(local) is PlaybackSource.NotFound)
    assertSame(copy, (resolver(copy).restoreSource(local) as PlaybackSource.Local).item)
  }

  // Every start point (card, item page, queue, Android Auto, widget restore) builds a StartRequest and runs planStart
  @Test fun everyStartRequestOfADownloadedBookPlansTheCopy() {
    val copy = book("local_1", "li1", ino = "42")
    val r = resolver(copy)
    assertSame(copy, (planStart(StartRequest("li1", null, playWhenReady = true), "a", r) as PlaybackSource.Local).item)
    assertSame(copy, (planStart(StartRequest.forQueueItem(PlayerNotificationService.PlaylistQueueItem("liX", null, "42", "a"), true), "a", r) as PlaybackSource.Local).item)
    assertSame(copy, (planStart(StartRequest("local_1", null, playWhenReady = true), "a", r) as PlaybackSource.Local).item)
    // Casting cannot play a file: a server id streams, as for playServerLibraryItemAndCast
    assertEquals(PlaybackSource.Stream("li1", null), planStart(StartRequest("li1", null, playWhenReady = true, forceStream = true), "a", r))
    assertEquals(PlaybackSource.Stream("li1", null), planStart(StartRequest("local_1", null, playWhenReady = true, forceStream = true), "a", r))
  }

  // Android Auto hands over a browse-tree item: a server item with a copy plays the copy
  @Test fun androidAutoServerItemPlansTheCopy() {
    val copy = book("local_1", "li1")
    val server = LibraryItem("li1", "42", "lib", "f", "", "", 0L, 0L, 0L, 0L, 0L, null, null, false, false, "book",
      MediaType(MediaTypeMetadata("Book", false), null), null, null, null, null, null)
    val request = StartRequest.forWrapper(server, null, playWhenReady = true, playbackRate = 1f)
    assertEquals("42", request.ino)
    assertSame(copy, (planStart(request, "a", resolver(copy)) as PlaybackSource.Local).item)
    assertEquals("local_1", StartRequest.forWrapper(copy, null, playWhenReady = true, playbackRate = 1f).libraryItemId)
  }

  private fun row(currentTime: Double, lastUpdate: Long, isFinished: Boolean = false) = MediaProgressEntity(
    "a", "li1", "", null, 1000.0, currentTime / 1000.0, currentTime, isFinished, false, null, null, lastUpdate, 0L, null, "device", false)

  @Test fun startTimeTakesNewerOfRowAndPaper() {
    assertEquals(300.0, chooseStartTime(row(300.0, 2000L), ProgressPoint(100.0, 1000L, false)))
    assertEquals(100.0, chooseStartTime(row(300.0, 1000L), ProgressPoint(100.0, 2000L, false)))
    // A tie goes to the other copy
    assertEquals(100.0, chooseStartTime(row(300.0, 1000L), ProgressPoint(100.0, 1000L, false)))
  }

  @Test fun startTimeTakesNewerOfRowAndServer() {
    assertEquals(450.0, chooseStartTime(row(450.0, 5000L), ProgressPoint(30.0, 4000L, false)))
    assertEquals(30.0, chooseStartTime(row(450.0, 5000L), ProgressPoint(30.0, 6000L, false)))
  }

  @Test fun finishedWinnerStartsAtZero() {
    assertEquals(0.0, chooseStartTime(row(990.0, 5000L, isFinished = true), ProgressPoint(30.0, 4000L, false)))
    assertEquals(0.0, chooseStartTime(row(30.0, 4000L), ProgressPoint(990.0, 5000L, true)))
  }

  @Test fun noRowKeepsOther() {
    assertEquals(77.0, chooseStartTime(null, ProgressPoint(77.0, 1L, false)))
    assertEquals(12.0, chooseStartTime(row(12.0, 1L), null))
    assertNull(chooseStartTime(null, null))
  }

  private fun session(serverId: String, copy: LocalLibraryItem?, playMethod: Int, config: String?) = PlaybackSession(
    "s", null, serverId, null, "book", MediaTypeMetadata("Book", false), DeviceInfo("d", "m", "x", 35, "0"),
    emptyList(), "Book", "Author", null, 360.0, playMethod, 0L, 0L, 0L, mutableListOf(), 12.5, null, copy, null, config, null, null
  )
}
