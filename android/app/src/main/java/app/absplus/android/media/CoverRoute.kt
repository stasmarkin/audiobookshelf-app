package app.absplus.android.media

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import app.absplus.android.managers.DownloadIdentifiers
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebViewClient
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking

const val COVER_ROUTE_PREFIX = "/_absplus/cover/"

data class CoverRequest(val itemId: String, val updatedAt: Long?, val raw: Boolean)

/** A cover route on the app's own origin; the same path on any other host is a normal request. */
fun isCoverRoute(uri: Uri, appOrigin: Uri): Boolean =
  uri.scheme == appOrigin.scheme && uri.host == appOrigin.host && uri.port == appOrigin.port &&
    uri.encodedPath?.startsWith(COVER_ROUTE_PREFIX) == true

/** Null for a path that is not exactly one safe id after the prefix. */
fun parseCoverRoute(uri: Uri): CoverRequest? {
  val path = uri.encodedPath ?: return null
  if (!path.startsWith(COVER_ROUTE_PREFIX)) return null
  val itemId = Uri.decode(path.substring(COVER_ROUTE_PREFIX.length))
  if (!DownloadIdentifiers.isSafe(itemId)) return null
  val updatedAt = uri.getQueryParameter("ts")?.toLongOrNull()?.takeIf { it >= 0 }
  return CoverRequest(itemId, updatedAt, raw = uri.getQueryParameter("raw") == "1")
}

/** The WebView answer for [uri], or null when it is not a cover route. */
fun interceptCoverRoute(uri: Uri, appOrigin: Uri, cache: CoverCache?): WebResourceResponse? {
  if (!isCoverRoute(uri, appOrigin)) return null
  val request = parseCoverRoute(uri) ?: return notFound()
  return cache?.let { coverResponse(it, request) } ?: notFound()
}

fun coverResponse(cache: CoverCache, req: CoverRequest): WebResourceResponse {
  // Never waits on the network: a miss is a 404 now (the card shows its title) and the cover is fetched in the background
  if (!req.raw) return cache.standard(req.itemId, req.updatedAt)?.let { fileResponse(it) } ?: notFound()
  // Offline answers come straight from the index: an older cached version beats no cover
  if (!cache.mayFetch()) return cache.newestCached(req.itemId)?.let { fileResponse(it) } ?: notFound()
  // The original streams from the server; Chromium reads the body on its own thread, at most until the answer starts
  val body = LazyCoverStream {
    when (val answer = runBlocking { cache.raw(req.itemId, req.updatedAt) }) {
      is CoverAnswer.Cached -> FileInputStream(answer.entry.file)
      is CoverAnswer.Stream -> answer.body
      CoverAnswer.Miss -> null
    }
  }
  return WebResourceResponse("image/jpeg", null, 200, "OK", noStore(), body)
}

private fun fileResponse(entry: CoverEntry): WebResourceResponse? = try {
  WebResourceResponse(coverMimeOf(entry.file), null, 200, "OK", noStore(), FileInputStream(entry.file))
} catch (e: IOException) {
  null
}

private fun notFound() = WebResourceResponse("text/plain", null, 404, "Not Found", noStore(), ByteArrayInputStream(ByteArray(0)))

// Freshness is the cover cache's decision, not the WebView HTTP cache's
private fun noStore() = mapOf("Cache-Control" to "no-store")

/** Resolves its source on the first read; a failed resolve is an empty body, which the <img> sees as an error. */
private class LazyCoverStream(private val resolve: () -> InputStream?) : InputStream() {
  private var source: InputStream? = null
  private var resolved = false

  private fun source(): InputStream? {
    if (!resolved) {
      resolved = true
      source = try {
        resolve()
      } catch (e: Exception) {
        null
      }
    }
    return source
  }

  override fun read(): Int = source()?.read() ?: -1

  override fun read(b: ByteArray, off: Int, len: Int): Int = source()?.read(b, off, len) ?: -1

  override fun close() {
    source?.close()
  }
}

class CoverWebViewClient(private val bridge: Bridge) : BridgeWebViewClient(bridge) {
  private val appOrigin by lazy { Uri.parse(bridge.localUrl) }

  override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
    interceptCoverRoute(request.url, appOrigin, CoverCache.instance) ?: super.shouldInterceptRequest(view, request)
}
