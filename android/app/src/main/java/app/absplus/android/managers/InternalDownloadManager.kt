package app.absplus.android.managers

import app.absplus.android.plugins.AbsLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Streams a download into an app-owned staging file.
 *
 * Staged bytes are resumed with a Range request. When [resumeValidator] (the ETag or Last-Modified of the response the
 * bytes came from) is known, the request carries it as If-Range, and the bytes are appended only when the server
 * answers 206 for exactly the staged offset, for the same total size and the same validator; a full 200 (the file
 * changed, or the server ignores ranges) rewrites the staging file from byte zero. Any other partial answer restarts
 * from zero once.
 */
class InternalDownloadManager(
        private val destinationFile: File,
        private val expectedSize: Long,
        private val progressCallback: DownloadItemManager.InternalProgressCallback,
        private val resumeValidator: String? = null,
        private val hasAvailableSpace: () -> Boolean
) {
  private val tag = "InternalDownloadManager"

  interface DownloadHandle {
    fun cancel()
  }

  private class ActiveDownloadHandle : DownloadHandle {
    private val cancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>()

    fun setCall(call: Call) {
      activeCall.set(call)
      if (cancelled.get()) call.cancel()
    }

    override fun cancel() {
      cancelled.set(true)
      activeCall.get()?.cancel()
    }
  }
  /**
   * Starts or resumes a download.
   *
   * @param url download URL
   * @param token access token sent in the Authorization header
   * @return logical handle used to cancel the active request, including a restarted request
   */
  fun download(url: String, token: String): DownloadHandle {
    destinationFile.parentFile?.mkdirs()
    val handle = ActiveDownloadHandle()
    startRequest(url, token, handle, allowRestart = true)
    return handle
  }

  private fun startRequest(
          url: String,
          token: String,
          handle: ActiveDownloadHandle,
          allowRestart: Boolean
  ) {
    var existingBytes = destinationFile.takeIf { it.exists() }?.length() ?: 0L
    val validator = resumeValidator?.takeIf { existingBytes > 0L && it.isNotBlank() }
    AbsLogger.info(
            tag,
            "Starting ${if (existingBytes > 0L) "resumed" else "new"} download for ${destinationFile.name} at byte $existingBytes${if (validator != null) " (validated)" else ""}")
    val request =
            Request.Builder()
                    .url(url)
                    .addHeader("Accept-Encoding", "identity")
                    .addHeader("Authorization", "Bearer $token")
                    .apply {
                      if (existingBytes > 0L) {
                        header("Range", "bytes=$existingBytes-")
                        if (validator != null) header("If-Range", validator)
                      }
                    }
                    .build()
    val call = client.newCall(request)
    handle.setCall(call)
    call.enqueue(
            object : Callback {
              override fun onFailure(call: Call, e: IOException) {
                AbsLogger.error(tag, "Download request failed for ${destinationFile.name}: ${e.message}")
                progressCallback.onComplete(true)
              }

              override fun onResponse(call: Call, response: Response) {
                response.use {
                  try {
                    if (response.code == 401) {
                      AbsLogger.error(tag, "Download unauthorized (401) for ${destinationFile.name}")
                      progressCallback.onAuthError()
                      return
                    }
                    // 416, or the Audiobookshelf server's 500 with "Content-Range: bytes */size" for a range at or past
                    // the end of the file
                    val unsatisfiedTotal = unsatisfiedRangeTotal(response)
                    if (response.code == 416 || (existingBytes > 0L && unsatisfiedTotal != null && response.code !in 200..299)) {
                      val serverSize = unsatisfiedTotal
                      if (serverSize != null) progressCallback.onSizeResolved(serverSize)
                      if (serverSize != null && existingBytes == serverSize) {
                        progressCallback.onProgress(existingBytes, 100L)
                        AbsLogger.info(tag, "Download completed for ${destinationFile.name} ($existingBytes bytes)")
                        progressCallback.onComplete(false)
                      } else if (allowRestart && destinationFile.delete()) {
                        AbsLogger.info(tag, "Restarting stale range from byte zero for ${destinationFile.name}")
                        startRequest(url, token, handle, allowRestart = false)
                      } else {
                        AbsLogger.error(tag, "Could not recover invalid range for ${destinationFile.name} at byte $existingBytes")
                        progressCallback.onComplete(true)
                      }
                      return
                    }
                    val append = existingBytes > 0L && response.code == 206 && resumable(response, existingBytes, validator)
                    if (existingBytes > 0L && response.code == 206 && !append) {
                      // A partial answer for another offset, size or version of the file: never appended to these bytes
                      if (allowRestart && destinationFile.delete()) {
                        AbsLogger.info(tag, "Resume of ${destinationFile.name} does not match the staged bytes; restarting from byte zero")
                        startRequest(url, token, handle, allowRestart = false)
                      } else {
                        AbsLogger.error(tag, "Invalid resume response for ${destinationFile.name} at byte $existingBytes")
                        progressCallback.onComplete(true)
                      }
                      return
                    }
                    if (existingBytes > 0L && !append && response.code != 200) {
                      AbsLogger.error(
                              tag,
                              "Invalid resume response ${response.code} for ${destinationFile.name} at byte $existingBytes"
                      )
                      progressCallback.onComplete(true)
                      return
                    }
                    if (!response.isSuccessful || response.body == null) {
                      AbsLogger.error(tag, "Download HTTP failure ${response.code} for ${destinationFile.name}")
                      progressCallback.onComplete(true)
                      return
                    }
                    // A full answer starts a new staged copy: remember what identifies it, for a later resume
                    if (!append) progressCallback.onValidator(validatorOf(response))

                    val startingBytes = if (append) existingBytes else 0L
                    val responseLength = response.body!!.contentLength()
                    val serverSize =
                            if (append) contentRangeTotal(response)
                            else responseLength.takeIf { it >= 0L }
                    if (serverSize != null) progressCallback.onSizeResolved(serverSize)
                    val totalLength =
                            serverSize ?: if (expectedSize > 0L) expectedSize else 0L

                    FileOutputStream(destinationFile, append).use { output ->
                      response.body!!.byteStream().use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var totalBytes = startingBytes
                        while (true) {
                          val read = input.read(buffer)
                          if (read < 0) break
                          if (!hasAvailableSpace())
                                  throw IOException("Download paused to preserve free storage")
                          output.write(buffer, 0, read)
                          totalBytes += read
                          val progress =
                                  if (totalLength > 0L) (totalBytes * 100L) / totalLength else 0L
                          progressCallback.onProgress(totalBytes, progress.coerceAtMost(100L))
                        }
                      }
                    }

                    val downloadedSize = destinationFile.length()
                    // An empty or cut-short body is not the file: never report it complete (the retry resumes
                    // from the staged bytes, or restarts when there are none)
                    val incomplete = DownloadIntegrity.incompleteReason(downloadedSize, serverSize, expectedSize)
                    if (incomplete != null) {
                      AbsLogger.error(tag, "Incomplete download for ${destinationFile.name}: $incomplete")
                      progressCallback.onComplete(true)
                    } else {
                      AbsLogger.info(
                              tag,
                              "Download completed for ${destinationFile.name} ($downloadedSize bytes)"
                      )
                      progressCallback.onComplete(false)
                    }
                  } catch (e: IOException) {
                    AbsLogger.error(tag, "Could not write staging file ${destinationFile.name}: ${e.message}")
                    progressCallback.onComplete(true)
                  }
                }
              }
            }
    )
  }

  /**
   * A 206 continues the staged bytes only if it starts exactly at [offset], its total is the size the staged copy was
   * started for (when known), and the server still identifies the file by the [validator] the bytes came from.
   */
  private fun resumable(response: Response, offset: Long, validator: String?): Boolean {
    val match = CONTENT_RANGE.matchEntire(response.header("Content-Range") ?: return false) ?: return false
    if (match.groupValues[1].toLongOrNull() != offset) return false
    if (match.groupValues[2].toLongOrNull()?.let { it >= offset } != true) return false
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (total != null && expectedSize > 0L && total != expectedSize) return false
    if (validator != null) {
      val current = if (isEntityTag(validator)) response.header("ETag") else response.header("Last-Modified")
      if (current != null && current != validator) return false
    }
    return true
  }

  private fun contentRangeTotal(response: Response): Long? =
          CONTENT_RANGE.matchEntire(response.header("Content-Range") ?: "")
                  ?.groupValues
                  ?.get(3)
                  ?.takeUnless { it == "*" }
                  ?.toLongOrNull()

  private fun unsatisfiedRangeTotal(response: Response): Long? =
          response.header("Content-Range")?.takeIf { it.startsWith("bytes */") }?.removePrefix("bytes */")?.toLongOrNull()

  companion object {
    private const val CHUNK_SIZE = 512 * 1024 // 512 KB
    private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()

    private fun isEntityTag(value: String) = value.startsWith("\"") || value.startsWith("W/\"")

    /** What identifies the file a full response delivered: its ETag, else its Last-Modified date, else nothing. */
    fun validatorOf(response: Response): String? =
            response.header("ETag")?.takeIf { isEntityTag(it) }
                    ?: response.header("Last-Modified")?.takeIf { it.isNotBlank() }
  }
}
