package app.absplus.android.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import app.absplus.android.BuildConfig
import app.absplus.android.R
import app.absplus.android.device.AndroidVolumeProbe
import app.absplus.android.device.AppStorage
import app.absplus.android.device.VolumeProbe
import app.absplus.android.device.AndroidStorageIo
import app.absplus.android.device.AppVolume
import app.absplus.android.device.NodeKind
import app.absplus.android.device.StorageIo
import app.absplus.android.device.VolumeIdentity
import java.security.MessageDigest
import app.absplus.android.diagnostics.DLog
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Failure boundary for local (downloaded / device-folder) cover art. A cover that is missing, unreadable,
 * revoked, corrupt or not an image must never stop playback: decoding returns null instead of throwing, and
 * callers fall back to the app's default artwork. Only the expected artwork failures are absorbed;
 * programming errors and coroutine cancellation still propagate.
 *
 * A cover that failed is not decoded again for [FAILURE_TTL_MS] (the notification, MediaSession queue and
 * Android Auto ask for the same cover many times), and is logged once per window, without its path or URI.
 */
internal object CoverArt {
  enum class Failure { MISSING, PERMISSION, CORRUPT, IO, INVALID }

  const val FAILURE_TTL_MS = 5 * 60 * 1000L
  private const val MAX_REMEMBERED = 32
  private const val TAG = "CoverArt"
  private const val SD_COVER_DIR = "sd-covers"
  private const val MAX_SD_COVER_BYTES = 20L * 1024 * 1024

  // Replaceable in tests; production decodes through the platform decoder
  internal var decoder: (Context, Uri) -> Bitmap? = ::platformDecode
  internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

  private val failedAt = LinkedHashMap<String, Long>()

  fun defaultUri(): Uri = Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)

  /** Decodes a local cover; null when it can't be used (a failure is categorised and logged once). */
  fun decodeLocal(ctx: Context, uri: Uri, where: String): Bitmap? {
    val key = uri.toString()
    if (isUnavailable(uri)) return null
    val bitmap = try {
      decoder(ctx, uri)
    } catch (e: Exception) {
      record(key, classify(e) ?: throw e, where)
      return null
    }
    if (bitmap == null) record(key, Failure.CORRUPT, where)
    return bitmap
  }

  /** True while a recent decode of this cover failed. */
  fun isUnavailable(uri: Uri): Boolean = synchronized(failedAt) {
    val at = failedAt[uri.toString()] ?: return false
    if (clock() - at < FAILURE_TTL_MS) return true
    failedAt.remove(uri.toString())
    false
  }

  /**
   * Content URI for a local cover. A `file:` cover is served through the app's FileProvider; one the provider
   * can't serve (outside its paths, or not a valid file URI) gets the default artwork instead of throwing.
   * The provider maps internal downloads and a cache folder only. A cover on the app's SD card folder is first copied
   * into that cache folder, and only when it is a regular file (not a link) on the registered card (M02).
   */
  fun localCoverUri(ctx: Context, coverContentUrl: String, probe: VolumeProbe = AndroidVolumeProbe(ctx), io: StorageIo = AndroidStorageIo): Uri {
    if (!coverContentUrl.startsWith("file:")) return Uri.parse(coverContentUrl)
    return try {
      val file = Uri.parse(coverContentUrl).toFile()
      val served = sdCardCover(ctx, file, probe, io) ?: file
      FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", served)
    } catch (e: IllegalArgumentException) {
      record(coverContentUrl, Failure.INVALID, "cover uri")
      defaultUri()
    }
  }

  /** The cached copy of a cover in an SD card download folder; null when [file] is not on a removable volume. */
  internal fun sdCardCover(ctx: Context, file: File, probe: VolumeProbe, io: StorageIo): File? {
    val volume = probe.volumes().firstOrNull { it.removable && AppStorage.isUnder(file, it.filesDir) } ?: return null
    if (!isServableCoverPath(file, volume, io)) throw IllegalArgumentException("not a servable cover")
    val cacheDir = File(ctx.cacheDir, SD_COVER_DIR)
    val name = MessageDigest.getInstance("SHA-256").digest(file.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
    val cached = File(cacheDir, "$name.${file.extension.takeIf { it.length in 1..5 } ?: "img"}")
    if (cached.isFile && cached.length() == file.length() && cached.lastModified() >= file.lastModified()) return cached
    if (!cacheDir.isDirectory && !cacheDir.mkdirs()) throw IllegalArgumentException("no cover cache")
    val tmp = File(cacheDir, "$name.tmp")
    file.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    if (!tmp.renameTo(cached)) {
      tmp.delete()
      throw IllegalArgumentException("cover cache rename failed")
    }
    return cached
  }

  /** On the registered card's app folder, a regular file (no link) of a cover's size. */
  internal fun isServableCoverPath(file: File, volume: AppVolume, io: StorageIo): Boolean =
          volume.mounted && volume.identity == VolumeIdentity.VERIFIED && AppStorage.isUnder(file, volume.filesDir) &&
                  io.kind(file) == NodeKind.FILE && io.length(file) in 1..MAX_SD_COVER_BYTES

  fun classify(e: Throwable): Failure? = when {
    e is FileNotFoundException -> Failure.MISSING
    e is SecurityException -> Failure.PERMISSION
    // ImageDecoder.DecodeException (API 28+) is an IOException; matched by name so API 24-27 never loads it
    e is IOException && e.javaClass.simpleName == "DecodeException" -> Failure.CORRUPT
    e is IOException -> Failure.IO
    e is IllegalArgumentException -> Failure.INVALID
    else -> null
  }

  internal fun reset() = synchronized(failedAt) { failedAt.clear() }

  private fun record(key: String, failure: Failure, where: String) {
    val firstInWindow = synchronized(failedAt) {
      val now = clock()
      val previous = failedAt[key]
      failedAt.remove(key)
      failedAt[key] = if (previous != null && now - previous < FAILURE_TTL_MS) previous else now
      while (failedAt.size > MAX_REMEMBERED) failedAt.remove(failedAt.keys.first())
      previous == null || now - previous >= FAILURE_TTL_MS
    }
    if (firstInWindow) DLog.w(TAG, "Local cover art unavailable ($failure, $where) - using default artwork, playback continues")
  }

  private fun platformDecode(ctx: Context, uri: Uri): Bitmap? =
    if (Build.VERSION.SDK_INT < 28) {
      @Suppress("DEPRECATION")
      MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
    } else {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri))
    }
}
