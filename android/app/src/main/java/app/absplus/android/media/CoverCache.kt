package app.absplus.android.media

import android.content.Context
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.diagnostics.DLog
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.managers.DownloadIdentifiers
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.sync.ActiveServer
import app.absplus.android.sync.ServerReachability
import app.absplus.android.sync.ServerState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

const val COVER_FETCH_PARALLELISM = 4
const val COVER_CONNECT_TIMEOUT_MS = 10_000L
const val COVER_READ_TIMEOUT_MS = 20_000L
// The read timeout is per read: a server trickling bytes would otherwise hold a fetch slot without end
const val COVER_CALL_TIMEOUT_MS = 30_000L
private const val REFUSAL_LOG_EVERY_MS = 5 * 60 * 1000L

private val MIME_TO_EXT = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif")

/** The image type of a cached cover file, from the extension it was stored under. */
fun coverMimeOf(file: File): String = MIME_TO_EXT.entries.firstOrNull { it.value == file.extension }?.key ?: "image/jpeg"

/** A cover answer from the server; the caller owns and closes [body]. [ext] is null for an image type the cache doesn't keep. */
data class FetchedCover(val mime: String, val ext: String?, val body: InputStream, val length: Long?)

/** Blocking; null for no answer, a code other than 200, a non-image type or a 401. */
fun interface CoverFetcher {
  fun fetch(config: ServerConnectionConfig, itemId: String, raw: Boolean): FetchedCover?
}

fun coverHttpClient(): OkHttpClient = OkHttpClient.Builder()
  .connectTimeout(COVER_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .readTimeout(COVER_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .callTimeout(COVER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .build()

/** [log] gets why a cover was refused (code, type or exception class; never the URL or token), once per reason per 5 min. */
class OkHttpCoverFetcher(
  private val client: OkHttpClient,
  private val log: (String) -> Unit = { DLog.w("CoverCache", it) },
  private val clock: () -> Long = System::currentTimeMillis,
) : CoverFetcher {
  private val loggedAt = HashMap<String, Long>()
  // The call timeout covers reading the body too: a multi-megabyte original streamed to the WebView must not be cut
  private val rawClient by lazy { client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build() }

  private fun refuse(reason: String): FetchedCover? {
    val now = clock()
    val first = synchronized(loggedAt) {
      val previous = loggedAt[reason]
      (previous == null || now - previous >= REFUSAL_LOG_EVERY_MS).also { if (it) loggedAt[reason] = now }
    }
    if (first) log("Cover not available: $reason")
    return null
  }

  override fun fetch(config: ServerConnectionConfig, itemId: String, raw: Boolean): FetchedCover? {
    val url = (config.address.trimEnd('/') + "/api/items/$itemId/cover" + if (raw) "?raw=1" else "").toHttpUrlOrNull() ?: return null
    val request = Request.Builder().url(url).apply {
      config.customHeaders?.forEach { (name, value) -> header(name, value) }
      header("Authorization", "Bearer ${config.token}")
      header("Accept", "image/webp,image/*;q=0.8")
    }.build()
    val response = try {
      (if (raw) rawClient else client).newCall(request).execute()
    } catch (e: IOException) {
      return refuse(e.javaClass.simpleName)
    }
    val body = response.body
    // A captive portal answers 200 with its own HTML page; only an image is a cover
    val mime = body?.contentType()?.let { "${it.type}/${it.subtype}".lowercase() }
    if (response.code != 200 || body == null || mime == null || !mime.startsWith("image/")) {
      response.close()
      return refuse(if (response.code != 200) "HTTP ${response.code}" else "type ${mime ?: "none"}")
    }
    return FetchedCover(mime, MIME_TO_EXT[mime], body.byteStream(), body.contentLength().takeIf { it >= 0 })
  }
}

sealed interface CoverAnswer {
  data class Cached(val entry: CoverEntry, val mime: String) : CoverAnswer
  data class Stream(val mime: String, val body: InputStream) : CoverAnswer
  object Miss : CoverAnswer
}

/**
 * One cover cache for the WebView and the native UI: a hit is served from disk, a miss is downloaded once from the
 * active server and stored, and offline the cached version is served even when it is older than the one asked for.
 */
class CoverCache(
  private val store: CoverStore,
  private val fetcher: CoverFetcher,
  private val activeServer: () -> ServerConnectionConfig?,
  private val serverState: () -> ServerState,
  private val replicaUpdatedAt: suspend (serverConfigId: String, itemId: String) -> Long?,
  private val scope: CoroutineScope,
) {
  // A fetch gives a stored entry, or the bytes of an image type the store doesn't keep (each waiter gets its own stream)
  private sealed interface Fetched {
    data class Stored(val entry: CoverEntry) : Fetched
    class Unstored(val mime: String, val bytes: ByteArray) : Fetched
  }

  private val inFlight = HashMap<String, Deferred<Fetched?>>()
  private val permits = Semaphore(COVER_FETCH_PARALLELISM)

  // UNKNOWN on a cold start may fetch: an <img> never asks again, so first cards would stay without covers
  fun mayFetch(): Boolean = activeServer() != null && serverState() != ServerState.OFFLINE

  /** The server is known to answer; UNKNOWN and UNAUTHORIZED are not enough to drop a cached cover for its URL. */
  fun isOnline(): Boolean = activeServer() != null && serverState() == ServerState.ONLINE

  /** The exact cached version, marked as used; no network. */
  fun cached(itemId: String, updatedAt: Long): CoverEntry? = store.find(itemId, updatedAt)?.also { store.touch(it) }

  suspend fun standard(itemId: String, updatedAt: Long?): CoverAnswer {
    if (!DownloadIdentifiers.isSafe(itemId)) return CoverAnswer.Miss
    val version = updatedAt ?: activeServer()?.let { replicaUpdatedAt(it.id, itemId) }
    if (version == null) return if (mayFetch()) streamOrNewest(itemId, raw = false) else newestOrMiss(itemId)
    store.find(itemId, version)?.let { return hit(it) }
    // A card with a stale updatedAt must not store today's server cover under its old key
    store.newest(itemId)?.let { if (it.updatedAt > version) return hit(it) }
    if (!mayFetch()) return newestOrMiss(itemId)
    return when (val fetched = fetchOnce(itemId, version)) {
      is Fetched.Stored -> cachedAnswer(fetched.entry)
      is Fetched.Unstored -> CoverAnswer.Stream(fetched.mime, ByteArrayInputStream(fetched.bytes))
      null -> newestOrMiss(itemId)
    }
  }

  /** The original image, proxied and never stored; it seeds the standard cover, which is what offline gets. */
  suspend fun raw(itemId: String, updatedAt: Long?): CoverAnswer {
    if (!DownloadIdentifiers.isSafe(itemId)) return CoverAnswer.Miss
    if (!mayFetch()) return standard(itemId, updatedAt)
    if (updatedAt == null || store.find(itemId, updatedAt) == null) scope.launch { standard(itemId, updatedAt) }
    val config = activeServer() ?: return standard(itemId, updatedAt)
    val fetched = withContext(Dispatchers.IO) { fetcher.fetch(config, itemId, raw = true) } ?: return standard(itemId, updatedAt)
    return CoverAnswer.Stream(fetched.mime, fetched.body)
  }

  /** The newest cached version, marked as used; no network (offline WebView answers). */
  fun newestCached(itemId: String): CoverEntry? = store.newest(itemId)?.also { store.touch(it) }

  /** For native consumers: the newest cached version, index only; marked as used so a playing book's cover stays. */
  fun latestEntry(itemId: String): CoverEntry? = store.peekNewest(itemId)?.also { store.touch(it) }

  fun latestFile(itemId: String): File? = latestEntry(itemId)?.file

  private fun hit(entry: CoverEntry): CoverAnswer {
    store.touch(entry)
    return cachedAnswer(entry)
  }

  private fun cachedAnswer(entry: CoverEntry) = CoverAnswer.Cached(entry, coverMimeOf(entry.file))

  private fun newestOrMiss(itemId: String): CoverAnswer = store.newest(itemId)?.let { hit(it) } ?: CoverAnswer.Miss

  private suspend fun streamOrNewest(itemId: String, raw: Boolean): CoverAnswer {
    val config = activeServer() ?: return newestOrMiss(itemId)
    val fetched = withContext(Dispatchers.IO) { fetcher.fetch(config, itemId, raw) } ?: return newestOrMiss(itemId)
    return CoverAnswer.Stream(fetched.mime, fetched.body)
  }

  private suspend fun fetchOnce(itemId: String, version: Long): Fetched? {
    val key = "${itemId}_$version"
    val job = synchronized(inFlight) {
      inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) { permits.withPermit { download(itemId, version) } }.also { job ->
        inFlight[key] = job
        job.invokeOnCompletion { synchronized(inFlight) { if (inFlight[key] === job) inFlight.remove(key) } }
      }
    }
    return job.await()
  }

  private fun download(itemId: String, version: Long): Fetched? {
    // The server may have gone away while this request waited for a free slot
    if (!mayFetch()) return null
    val config = activeServer() ?: return null
    val fetched = fetcher.fetch(config, itemId, raw = false) ?: return null
    return fetched.body.use { body ->
      val ext = fetched.ext
      if (ext != null) {
        store.put(itemId, version, ext) { body.copyTo(it) }?.let { Fetched.Stored(it) }
      } else {
        readUpTo(body, MAX_COVER_FILE_BYTES)?.let { Fetched.Unstored(fetched.mime, it) }
      }
    }
  }

  private fun readUpTo(body: InputStream, limit: Long): ByteArray? = try {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() <= limit) {
      val n = body.read(buffer)
      if (n < 0) break
      out.write(buffer, 0, n)
    }
    out.toByteArray().takeIf { it.isNotEmpty() && it.size <= limit }
  } catch (e: IOException) {
    null
  }

  companion object {
    @Volatile var instance: CoverCache? = null
      private set

    /** Self-sufficient: the widget can start the process without an Activity or the sync runtime. */
    @Synchronized
    fun init(context: Context) {
      if (instance != null) return
      val app = context.applicationContext
      val client = coverHttpClient()
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e -> AbsLogger.error("CoverCache", "Cover job failed: $e") })
      val store = CoverStore(File(app.filesDir, COVER_CACHE_DIR))
      instance = CoverCache(
        store,
        OkHttpCoverFetcher(client),
        activeServer = ActiveServer::current,
        serverState = { ServerReachability.state.value },
        replicaUpdatedAt = { sc, id -> ReplicaDatabase.get(app).items().get(sc, id)?.updatedAt },
        scope = scope,
      )
      scope.launch { store.load() }
    }

    internal fun setForTest(cache: CoverCache?) {
      instance = cache
    }
  }
}
