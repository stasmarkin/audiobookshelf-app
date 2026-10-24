package app.absplus.android.managers

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.*
import app.absplus.android.device.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.paperdb.Paper
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupHistoryPreservationTest {
  private lateinit var ctx: Context
  private lateinit var root: File
  private lateinit var db: DbManager
  private lateinit var item: LocalLibraryItem
  private val protected = listOf("localLibraryItems", "localMediaProgress", "localFolders", "mediaItemHistory", "device", "playbackSession")
  private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private fun disk() = protected.associateWith { b -> File(Paper.book(b).path).walkTopDown().filter { it.isFile }
          .associate { it.relativeTo(File(Paper.book(b).path)).path to sha(it.readBytes()) } }
  private fun progress() = LocalMediaProgress("local_show-legacy-episode", "local_show", "legacy-episode", 60.0, 0.8, 48.0, true,
          null, null, 100L, 1L, 100L, "server", "https://fixture.invalid", "user", "show", "ep")

  @Before fun setup() {
    ctx = ApplicationProvider.getApplicationContext()
    Paper.init(ctx)
    (protected + "localMediaAvailabilityV1").forEach { Paper.book(it).destroy() }
    root = File(ctx.cacheDir, "patch-a-${UUID.randomUUID()}").apply { mkdirs() }
    File(root, "root/show").mkdirs()
    File(root, "root/show/episode.mp3").writeText("synthetic media")
    StartupPresenceProvider.base = root; StartupPresenceProvider.fault = "none"; StartupPresenceProvider.queries = 0
    Robolectric.buildContentProvider(StartupPresenceProvider::class.java).create(ProviderInfo().apply {
      authority = StartupPresenceProvider.AUTHORITY; exported = true; grantUriPermissions = true
    })
    val tree = DocumentsContract.buildTreeDocumentUri(StartupPresenceProvider.AUTHORITY, "primary:root")
    ctx.contentResolver.takePersistableUriPermission(tree, flags)
    val file = LocalFile("file-episode", "episode.mp3", StartupPresenceProvider.uri("primary:root/show/episode.mp3").toString(),
            "root/show", File(root, "root/show/episode.mp3").path, "audio/mpeg", 15)
    val track = AudioTrack(1, 0.0, 60.0, "Episode", file.contentUrl, "audio/mpeg", null, true, file.id, 1)
    val episode = PodcastEpisode("legacy-episode", 1, null, null, "Episode", null, null, null, 1L, null, track, null, 60.0, 15L, "ep", "legacy-episode")
    val podcast = Podcast(PodcastMetadata("Show", null, null, mutableListOf(), false), null, mutableListOf(), mutableListOf(episode), false, 1)
    item = LocalLibraryItem("local_show", "saf-fixture", "root/show", File(root, "root/show").path,
            StartupPresenceProvider.uri("primary:root/show").toString(), false, "podcast", podcast, mutableListOf(file), null, null,
            true, "server", "https://fixture.invalid", "user", "show")
    db = DbManager(); db.saveLocalLibraryItem(item); db.saveLocalMediaProgress(progress())
    // Cached payload sentinel; Android has no separate bookmark book. JS owns actual server bookmark caching.
    Paper.book("device").write("cached-user-bookmarks", listOf(mapOf("libraryItemId" to "show", "time" to 12)))
  }

  private fun start() { db = DbManager(); db.cleanLocalLibraryItems(ctx); db.cleanLocalMediaProgress() }

  private fun preserved(before: Map<String, Map<String, String>>) {
    assertEquals(before, disk())
    assertEquals("legacy-episode", (DbManager().getLocalLibraryItem(item.id)!!.media as Podcast).episodes!!.single().id)
    assertEquals(48.0, DbManager().getLocalMediaProgress(progress().id)!!.currentTime, 0.0)
  }

  @Test fun providerFailuresPreserveRealPaperRecordsAcrossFreshOwnersAndRecovery() {
    val before = disk()
    for (fault in listOf("exception", "null", "loading", "error", "gone", "unreadable")) {
      StartupPresenceProvider.fault = fault
      repeat(3) { start(); preserved(before) }
      StartupPresenceProvider.fault = "none"
      start(); preserved(before)
      println("PATCH_A provider=$fault originalPaperHashes=$before unchanged=true restored=true")
    }
  }

  @Test fun revokedGrantPreservesIdentityAndRestorationReobservesTheOriginalDocument() {
    val before = disk()
    val tree = DocumentsContract.buildTreeDocumentUri(StartupPresenceProvider.AUTHORITY, "primary:root")
    ctx.contentResolver.releasePersistableUriPermission(tree, flags)
    start(); preserved(before)
    ctx.contentResolver.takePersistableUriPermission(tree, flags)
    start(); preserved(before)
  }

  @Test fun confirmedMissingAndMissingParentNeverRemoveEpisodesOrHistory() {
    val before = disk()
    assertTrue(File(root, "root/show/episode.mp3").delete())
    repeat(3) { start(); preserved(before) }
    assertTrue(File(root, "root/show").renameTo(File(root, "root/held-show")))
    start(); preserved(before)
    assertTrue(File(root, "root").renameTo(File(root, "held-root")))
    start(); preserved(before)
  }

  @Test fun opaqueDocumentPresenceDoesNotInventMissingParentage() {
    item.localFiles.single().contentUrl = StartupPresenceProvider.uri("opaque-token").toString()
    db.saveLocalLibraryItem(item)
    val before = disk()
    start(); preserved(before)
    File(root, "root/show/episode.mp3").delete()
    start(); preserved(before)
  }

  @Test fun rawInternalAndSdAvailabilityKeepOriginalRecordsAndCardIdentityChecks() {
    val sd = File(root, "sd-files").apply { mkdirs() }
    val probe = object : VolumeProbe {
      var mounted = false; var identity = VolumeIdentity.VERIFIED
      override fun internalFilesDir() = ctx.filesDir
      override fun volumes() = listOf(AppVolume("card", sd, true, mounted, mounted, identity))
    }
    item.folderId = AppStorage.appsdFolderId("card", "podcast")
    val file = File(sd, "downloads/show/episode.mp3").apply { parentFile!!.mkdirs(); writeText("synthetic") }
    item.localFiles.single().apply { contentUrl = Uri.fromFile(file).toString(); absolutePath = file.path }
    db.saveLocalLibraryItem(item); val before = disk()
    start(); preserved(before)
    assertEquals(app.absplus.android.player.LocalAvailability.STORAGE_UNAVAILABLE,
            app.absplus.android.player.LocalPlaybackAvailability.check(item, probe) { file.isFile })
    probe.mounted = true
    start(); preserved(before)
    assertEquals(app.absplus.android.player.LocalAvailability.OK,
            app.absplus.android.player.LocalPlaybackAvailability.check(item, probe) { file.isFile })
    probe.identity = VolumeIdentity.MISMATCH
    start(); preserved(before)
    probe.identity = VolumeIdentity.UNREGISTERED
    start()
    item.folderId = "internal-podcast"
    val internal = File(ctx.filesDir, "downloads/show/episode.mp3").apply { parentFile!!.mkdirs(); writeText("synthetic") }
    item.localFiles.single().apply { contentUrl = Uri.fromFile(internal).toString(); absolutePath = internal.path }
    db.saveLocalLibraryItem(item); val internalBefore = disk()
    start(); assertEquals(internalBefore, disk())
    internal.delete(); start(); assertEquals(internalBefore, disk())
  }

  @Test fun repeatedStartupDoesNotRemoveOrphanProgressAndExplicitRemovalStillWorks() {
    repeat(3) { start() }
    db.removeLocalLibraryItem(item.id); start(); assertNotNull(db.getLocalMediaProgress(progress().id))
    db.removeLocalMediaProgressFor(item.id); assertNull(db.getLocalMediaProgress(progress().id))
  }

  @Test fun startupRequiresNoPaperWriteAndPreservesRecordsWhenWritesAreUnavailable() {
    val before = disk()
    val original = File(Paper.book("localLibraryItems").path)
    val saved = File(original.parentFile, original.name + "-held")
    assertTrue(original.renameTo(saved))
    original.writeText("synthetic write obstruction")
    try { repeat(3) { start() } } finally {
      assertTrue(original.delete())
      assertTrue(saved.renameTo(original))
    }
    preserved(before)
  }

  @Test fun concurrentListeningRewindAndResetAreNotOverwrittenByStartup() {
    val listener = Thread {
      repeat(50) { db.saveLocalMediaProgress(progress().apply { currentTime = it.toDouble(); lastUpdate = 100L + it }) }
    }.apply { start() }
    repeat(50) { start() }
    listener.join(5000)
    assertFalse("listening writer must never wait for a new startup item lock", listener.isAlive)
    assertEquals(49.0, DbManager().getLocalMediaProgress(progress().id)!!.currentTime, 0.0)
    db.saveLocalMediaProgress(progress().apply { currentTime = 3.0; isFinished = false; lastUpdate = 200L })
    start()
    assertEquals(3.0, DbManager().getLocalMediaProgress(progress().id)!!.currentTime, 0.0)
    db.saveLocalMediaProgress(progress().apply { currentTime = 0.0; progress = 0.0; isFinished = false; lastUpdate = 201L })
    start()
    assertEquals(0.0, DbManager().getLocalMediaProgress(progress().id)!!.currentTime, 0.0)
    assertFalse(DbManager().getLocalMediaProgress(progress().id)!!.isFinished)
  }

  @Test fun audiobookRecordsAndNonstandardLegacyKeysRemainByteExact() {
    item.mediaType = "book"
    val meta = BookMetadata("Book", null, mutableListOf(), mutableListOf(), mutableListOf(), null, null, null, null, null, null, null, false, "Author", null, null, null, null)
    item.media = Book(meta, null, emptyList(), emptyList(), emptyList(), mutableListOf(), null, 15L, 60.0, 1)
    db.saveLocalLibraryItem(item)
    val legacy = progress().apply { id = "legacy-key"; localEpisodeId = null; episodeId = null }
    db.saveLocalMediaProgress(legacy)
    StartupPresenceProvider.fault = "gone"
    val before = disk(); repeat(3) { start(); assertEquals(before, disk()) }
    assertNotNull(DbManager().getLocalMediaProgress("legacy-key"))
    println("PATCH_A_BOOK_AFTER=" + jacksonObjectMapper().writeValueAsString(DbManager().getLocalLibraryItem(item.id)))
  }
}
