package app.absplus.android.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.BuildConfig
import app.absplus.android.device.AppVolume
import app.absplus.android.device.VolumeIdentity
import app.absplus.android.device.VolumeProbe
import org.junit.Assume
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Local cover art must never stop playback. Most failures here are simulated by injecting a decoder that throws
 * what the platform throws (FileNotFoundException, SecurityException, ImageDecoder.DecodeException...). Robolectric
 * can't decode real images (its ImageDecoder shadow isn't a decoder), so real files are covered on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverArtTest {
  /** Same simple name as android.graphics.ImageDecoder.DecodeException, which can't be constructed in tests. */
  class DecodeException(message: String) : IOException(message)

  private val ctx: Context = ApplicationProvider.getApplicationContext()
  private val cover = Uri.parse("content://app.absplus.android.debug.fileprovider/downloads/li_1/cover-li_1.jpg")
  private val platformDecoder = CoverArt.decoder
  private var now = 1_000_000L
  private var decodes = 0
  private val bitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

  @Before fun setUp() {
    // FileProvider caches its roots per authority in a static map; Robolectric gives every test a new cache dir
    androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
    CoverArt.reset()
    CoverArt.clock = { now }
    ShadowLog.reset()
  }

  @After fun tearDown() {
    CoverArt.decoder = platformDecoder
    CoverArt.reset()
  }

  private fun decodeWith(result: () -> Bitmap?) {
    CoverArt.decoder = { _, _ -> decodes++; result() }
  }

  private fun warnings() = ShadowLog.getLogsForTag("CoverArt")

  @Test fun aValidCoverIsReturnedUnchangedAndNotLogged() {
    decodeWith { bitmap }
    assertSame(bitmap, CoverArt.decodeLocal(ctx, cover, "session"))
    assertFalse(CoverArt.isUnavailable(cover))
    assertTrue(warnings().isEmpty())
  }

  @Test fun everyExpectedFailureFallsBackWithItsCategory() {
    val cases = listOf(
      FileNotFoundException("open failed: ENOENT") to CoverArt.Failure.MISSING, // missing, or deleted after saving
      DecodeException("Input was incomplete.") to CoverArt.Failure.CORRUPT, // zero-byte, truncated, corrupt
      DecodeException("unimplemented") to CoverArt.Failure.CORRUPT, // unsupported format, not an image
      SecurityException("Permission Denial") to CoverArt.Failure.PERMISSION, // denied, or revoked SAF grant
      IOException("I/O error") to CoverArt.Failure.IO, // transient read failure
      IllegalArgumentException("Unknown URI") to CoverArt.Failure.INVALID
    )
    cases.forEachIndexed { i, (error, category) ->
      val uri = Uri.parse("$cover?case=$i")
      decodeWith { throw error }
      assertNull(CoverArt.decodeLocal(ctx, uri, "session"))
      assertTrue(CoverArt.isUnavailable(uri))
      assertEquals(category, CoverArt.classify(error))
    }
  }

  @Test fun aDecoderThatReturnsNoBitmapCountsAsCorrupt() {
    // MediaStore.Images.Media.getBitmap (API < 28) returns null for undecodable data
    decodeWith { null }
    assertNull(CoverArt.decodeLocal(ctx, cover, "session"))
    assertTrue(CoverArt.isUnavailable(cover))
  }

  @Test fun aCoverDeletedAfterItWasDecodedFallsBackOnTheNextDecode() {
    decodeWith { bitmap }
    assertSame(bitmap, CoverArt.decodeLocal(ctx, cover, "session"))
    decodeWith { throw FileNotFoundException("deleted") }
    assertNull(CoverArt.decodeLocal(ctx, cover, "session"))
  }

  @Test fun aFailedCoverIsNotDecodedAgainOrRelogged_untilTheWindowPasses() {
    decodeWith { throw FileNotFoundException("missing") }
    repeat(5) { assertNull(CoverArt.decodeLocal(ctx, cover, "notification")) } // repeated playback / redraws
    assertEquals(1, decodes)
    assertEquals(1, warnings().size)

    now += CoverArt.FAILURE_TTL_MS
    decodeWith { bitmap } // a transient failure that has cleared, or a cover put back
    assertSame(bitmap, CoverArt.decodeLocal(ctx, cover, "notification"))
    assertEquals(2, decodes)
  }

  @Test fun theWarningNamesTheCategoryButNoPathOrUri() {
    decodeWith { throw FileNotFoundException("/data/user/0/app.absplus.android.debug/files/downloads/li_1/cover-li_1.jpg: open failed") }
    CoverArt.decodeLocal(ctx, cover, "session")
    val message = warnings().single().msg
    assertTrue(message.contains("MISSING"))
    listOf("content://", "fileprovider", "downloads", "li_1", "/data/").forEach { assertFalse("leaked $it in $message", message.contains(it)) }
  }

  @Test fun programmingErrorsAreNotHidden() {
    decodeWith { throw IllegalStateException("bug") }
    try {
      CoverArt.decodeLocal(ctx, cover, "session")
      fail("expected the programming error to propagate")
    } catch (e: IllegalStateException) {
      assertFalse(CoverArt.isUnavailable(cover))
    }
  }

  @Test fun cancellationPropagates() {
    decodeWith { throw CancellationException("player closed") }
    try {
      CoverArt.decodeLocal(ctx, cover, "session")
      fail("expected cancellation to propagate")
    } catch (e: CancellationException) {
      assertFalse(CoverArt.isUnavailable(cover))
    }
  }

  @Test fun aFileCoverTheFileProviderCantServeGetsTheDefaultArtworkInsteadOfThrowing() {
    // Outside the provider's paths (files/downloads/): FileProvider.getUriForFile throws IllegalArgumentException
    val outside = File(ctx.filesDir, "elsewhere/cover.jpg")
    assertEquals(CoverArt.defaultUri(), CoverArt.localCoverUri(ctx, Uri.fromFile(outside).toString()))
  }

  // The SD card's app folder, as a temporary directory; FileProvider maps no /storage path any more (M02)
  private val sdFiles: File by lazy { File(ctx.cacheDir.parentFile, "sdcard-files").apply { mkdirs() } }
  private var sdIdentity = VolumeIdentity.VERIFIED
  private val sdProbe = object : VolumeProbe {
    override fun volumes() = listOf(AppVolume("79EC-9B33", sdFiles, removable = true, mounted = true, writable = true, identity = sdIdentity))
    override fun internalFilesDir(): File = ctx.filesDir
  }

  private fun sdCover(name: String = "cover.jpg"): File =
          File(sdFiles, "downloads/li_1/$name").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { it.toByte() }) }

  @Test fun anSdCardCoverIsServedFromAValidatedCopyInTheCache() {
    val cover = sdCover()
    val uri = CoverArt.localCoverUri(ctx, Uri.fromFile(cover).toString(), sdProbe)
    assertEquals("content", uri.scheme)
    assertEquals("${BuildConfig.APPLICATION_ID}.fileprovider", uri.authority)
    assertEquals("served from the narrow cache path, not the card", "sd_covers", uri.pathSegments.first())
    val cached = File(ctx.cacheDir, "sd-covers").listFiles()!!.single { it.extension == "jpg" }
    assertTrue(cached.readBytes().contentEquals(cover.readBytes()))
    // A second request reuses the copy
    assertEquals(uri, CoverArt.localCoverUri(ctx, Uri.fromFile(cover).toString(), sdProbe))
  }

  @Test fun theFileProviderMapsNoStoragePath() {
    for (path in listOf("/storage/79EC-9B33/Android/data/${ctx.packageName}/files/downloads/li_1/cover.jpg", "/storage/emulated/0/Music/x.jpg")) {
      try {
        androidx.core.content.FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", File(path))
        fail("$path must not be representable by the app's FileProvider")
      } catch (e: IllegalArgumentException) {
        // expected: no root covers /storage
      }
    }
  }

  @Test fun coversOutsideTheCardsAppFolderOrOnAnotherCardGetTheDefaultArtwork() {
    assertEquals(CoverArt.defaultUri(), CoverArt.localCoverUri(ctx, "file:///storage/79EC-9B33/Music/x.jpg", sdProbe))
    assertEquals(CoverArt.defaultUri(), CoverArt.localCoverUri(ctx, "file://${sdFiles.path}/../../../../Music/x.jpg", sdProbe))
    val cover = sdCover("other.jpg")
    sdIdentity = VolumeIdentity.MISMATCH
    assertEquals("a different card under the same UUID is not served", CoverArt.defaultUri(), CoverArt.localCoverUri(ctx, Uri.fromFile(cover).toString(), sdProbe))
  }

  @Test fun aLinkedSdCardCoverIsNotServed() {
    val secret = File(ctx.filesDir, "private.jpg").apply { writeBytes(ByteArray(10)) }
    val link = File(sdFiles, "downloads/li_1/link.jpg").apply { parentFile!!.mkdirs() }
    try {
      Files.createSymbolicLink(link.toPath(), secret.toPath())
    } catch (e: Exception) {
      Assume.assumeTrue("symbolic links are supported here", false)
    }
    assertEquals(CoverArt.defaultUri(), CoverArt.localCoverUri(ctx, Uri.fromFile(link).toString(), sdProbe))
  }
}
