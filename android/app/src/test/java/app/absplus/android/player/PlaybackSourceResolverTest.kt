package app.absplus.android.player

import app.absplus.android.data.AudioTrack
import app.absplus.android.data.Book
import app.absplus.android.data.BookMetadata
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
import app.absplus.android.managers.LocalItemLocks
import app.absplus.android.media.resolverDownloadMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  private fun bookWithTracks(id: String, serverId: String, tracks: Int, config: String = "a") = LocalLibraryItem(
    id, "internal-book", "", "", "", false, "book",
    Book(BookMetadata("Book", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null),
      null, emptyList(), null, null, (0 until tracks).map { AudioTrack(it, 0.0, 1.0, "t$it", "", null, null, true, null, null) }.toMutableList(), null, null, null, tracks),
    mutableListOf(), null, null, true, config, null, null, serverId, null
  )

  private fun episode(id: String, serverEpisodeId: String?) =
    PodcastEpisode(id, 0, null, null, "Episode", null, null, null, null, null, null, null, null, null, serverEpisodeId, null)

  private fun podcast(id: String, serverId: String, vararg episodes: PodcastEpisode) = LocalLibraryItem(
    id, "internal-podcast", "", "", "", false, "podcast",
    Podcast(PodcastMetadata("Podcast", null, null, mutableListOf(), false), null, mutableListOf(), episodes.toMutableList(), false, null),
    mutableListOf(), null, null, true, "a", null, null, serverId, null
  )

  private fun resolver(vararg items: LocalLibraryItem, broken: Set<String> = emptySet(), cardOut: Set<String> = emptySet()) =
    PlaybackSourceResolver({ items.toList() }, { id -> items.find { it.id == id } }, { it.id !in cardOut }) { item, _ -> item.id !in broken && item.id !in cardOut }

  @Test fun pinReadsTheCopyAgainAndPinsOnlyAPlayableOne() {
    LocalItemLocks.clearPlaybackPin()
    try {
      val stale = book("local_1", "li1")
      val fresh = book("local_1", "li1")
      val pinned = pinLocalSource(PlaybackSource.Local(stale, null), { fresh }) { _, _ -> LocalAvailability.OK }
      assertSame(fresh, (pinned as PlaybackSource.Local).item)
      assertTrue(LocalItemLocks.isPinned("local_1"))

      val other = book("local_2", "li2")
      assertEquals(PlaybackSource.StorageUnavailable(other),
        pinLocalSource(PlaybackSource.Local(other, null), { other }) { _, _ -> LocalAvailability.STORAGE_UNAVAILABLE })
      assertEquals(PlaybackSource.LocalBroken(other),
        pinLocalSource(PlaybackSource.Local(other, null), { other }) { _, _ -> LocalAvailability.NO_FILES })
      assertTrue(pinLocalSource(PlaybackSource.Local(other, null), { null }) { _, _ -> LocalAvailability.OK } is PlaybackSource.NotFound)
      val e1 = episode("local_e1", "e1")
      assertTrue(pinLocalSource(PlaybackSource.Local(podcast("local_p", "p", e1), e1), { podcast("local_p", "p") }) { _, _ -> LocalAvailability.OK }
        is PlaybackSource.NotFound)
      // A copy that cannot play leaves the previous pin
      assertTrue(LocalItemLocks.isPinned("local_1"))
      assertFalse(LocalItemLocks.isPinned("local_2"))
    } finally {
      LocalItemLocks.clearPlaybackPin()
    }
  }

  @Test fun copyOnMissingCardIsUnavailableNotBrokenOrStreamed() {
    val copy = book("local_1", "li1")
    assertEquals(PlaybackSource.StorageUnavailable(copy), resolver(copy, cardOut = setOf("local_1")).resolve("a", "li1", null))
    assertEquals(PlaybackSource.StorageUnavailable(copy), resolver(copy, cardOut = setOf("local_1")).resolve("a", "local_1", null))
    // A playable copy elsewhere still wins
    val other = book("local_2", "li1")
    assertSame(other, (resolver(copy, other, cardOut = setOf("local_1")).resolve("a", "li1", null) as PlaybackSource.Local).item)
  }

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

  @Test fun androidAutoStartOfAPartialCopyStreamsAsItsMarkSays() {
    val partial = bookWithTracks("local_1", "li1", tracks = 2)
    fun serverBook(numTracks: Int?, tracks: Int? = null) = LibraryItem("li1", "", "lib", "f", "", "", 0L, 0L, 0L, 0L, 0L, null, null, false, false, "book",
      Book(BookMetadata("Book", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, null, null, null, null, null),
        null, emptyList(), null, null, tracks?.let { n -> (0 until n).map { AudioTrack(it, 0.0, 1.0, "t$it", "", null, null, false, null, null) }.toMutableList() },
        null, null, null, numTracks), null, null, null, null, null)
    val mark = resolverDownloadMark { _, _ -> true }

    val incomplete = serverBook(numTracks = 3)
    assertNull("marked not downloaded", mark(listOf(partial), "a", incomplete, null))
    assertEquals(PlaybackSource.Stream("li1", null),
      planStart(StartRequest.forWrapper(incomplete, null, playWhenReady = true, playbackRate = 1f), "a", resolver(partial)))
    for (counted in listOf(serverBook(numTracks = null, tracks = 3), serverBook(numTracks = 0, tracks = 3))) {
      assertEquals("an unknown count falls back to the server's tracks", 3, StartRequest.forWrapper(counted, null, true, 1f).expectedTracks)
    }

    val complete = serverBook(numTracks = 2)
    assertEquals("local_1", mark(listOf(partial), "a", complete, null))
    assertSame(partial, (planStart(StartRequest.forWrapper(complete, null, playWhenReady = true, playbackRate = 1f), "a", resolver(partial)) as PlaybackSource.Local).item)
  }

  @Test fun partialCopyStreams() {
    val partial = bookWithTracks("local_1", "li1", tracks = 2)
    assertEquals(PlaybackSource.Stream("li1", null), resolver(partial).resolve("a", "li1", null, expectedTracks = 3))
    assertSame(partial, (resolver(partial).resolve("a", "li1", null, expectedTracks = 2) as PlaybackSource.Local).item)
    // Without the server's count the copy plays, as before
    assertSame(partial, (resolver(partial).resolve("a", "li1", null) as PlaybackSource.Local).item)
    // A device id is played as it is
    assertSame(partial, (resolver(partial).resolve("a", "local_1", null, expectedTracks = 3) as PlaybackSource.Local).item)
    // The queue carries the count
    val queued = StartRequest.forQueueItem(PlayerNotificationService.PlaylistQueueItem("li1", null, expectedTracks = 3), true)
    assertEquals(PlaybackSource.Stream("li1", null), planStart(queued, "a", resolver(partial)))
  }

  // A cast device streams from the active server only: another server's copy stays a file (the UI warns about casting)
  @Test fun castOfAnotherServersCopyStaysLocal() {
    val foreign = book("local_f", "liF", config = "b")
    assertSame(foreign, (planStart(StartRequest("local_f", null, playWhenReady = true, forceStream = true), "a", resolver(foreign)) as PlaybackSource.Local).item)
    val own = book("local_1", "li1", config = "a")
    assertEquals(PlaybackSource.Stream("li1", null), planStart(StartRequest("local_1", null, playWhenReady = true, forceStream = true), "a", resolver(own)))
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

  // A copy found by ino (requested B, the copy is A) also starts from B's progress when that is the newest
  @Test fun inoCopyStartsFromTheRequestedItemsProgress() {
    val requested = row(1800.0, 5000L).copy(libraryItemId = "liB")
    assertEquals(1800.0, chooseLocalStartTime(null, requested, null))
    assertEquals(1800.0, chooseLocalStartTime(row(30.0, 4000L), requested, ProgressPoint(40.0, 3000L, false)))
    assertEquals(40.0, chooseLocalStartTime(row(30.0, 4000L), requested, ProgressPoint(40.0, 6000L, false)))
    assertEquals(30.0, chooseLocalStartTime(row(30.0, 6000L), requested, null))
    assertNull(chooseLocalStartTime(null, null, null))
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
