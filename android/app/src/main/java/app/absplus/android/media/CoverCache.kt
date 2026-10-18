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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

const val COVER_FETCH_PARALLELISM = 4
const val COVER_CONNECT_TIMEOUT_MS = 5_000L
const val COVER_READ_TIMEOUT_MS = 10_000L
// The read timeout is per read: a server trickling bytes would otherwise hold a fetch worker without end
const val COVER_CALL_TIMEOUT_MS = 15_000L
// The original (raw) streams without a call timeout, but its headers must arrive this soon or the standard cover is shown
const val COVER_RAW_FIRST_BYTE_MS = 5_000L
// Per read while the original streams
const val COVER_RAW_READ_TIMEOUT_MS = 20_000L
// Requests beyond this are the oldest ones, from cards long gone: dropped so the visible ones come first
const val COVER_QUEUE_MAX = 200
// How long a canvas that draws once (Year in Review) waits for a missing cover
const val COVER_WAIT_MS = 10_000L
private const val AWAIT_POLL_MS = 100L
private const val REFUSAL_LOG_EVERY_MS = 5 * 60 * 1000L
private const val HEADERS_WAITING = 0
private const val HEADERS_ARRIVED = 1
private const val HEADERS_TIMED_OUT = 2
private val headerTimer by lazy {
  Executors.newSingleThreadScheduledExecutor { Thread(it, "cover-raw-headers").apply { isDaemon = true } }
}

// image/jpg and image/pjpeg are what some servers send for JPEG
private val MIME_TO_EXT = mapOf(
  "image/jpeg" to "jpg", "image/jpg" to "jpg", "image/pjpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif",
  "image/avif" to "avif", "image/bmp" to "bmp"
)

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
  private val rawHeaderTimeoutMs: Long = COVER_RAW_FIRST_BYTE_MS,
) : CoverFetcher {
  private val loggedAt = HashMap<String, Long>()
  // The call timeout covers reading the body too: a multi-megabyte original streamed to the WebView must not be cut; only
  // its headers are bounded (see [awaitHeaders]), since the WebView waits for them
  private val rawClient by lazy {
    client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(COVER_RAW_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
  }

  private fun refuse(reason: String): FetchedCover? {
    val now = clock()
    val first = synchronized(loggedAt) {
      val previous = loggedAt[reason]
      (previous == null || now - previous >= REFUSAL_LOG_EVERY_MS).also { if (it) loggedAt[reason] = now }
    }
    if (first) log("Cover not available: $reason")
    return null
  }

  /** Executes [call], cancelling it if its headers don't arrive within [rawHeaderTimeoutMs]; null when cancelled so. */
  private fun awaitHeaders(call: Call): Response? {
    // One CAS decides between "headers arrived" and "timed out", so a late cancel never cuts a body that is streaming
    val state = AtomicInteger(HEADERS_WAITING)
    val timer = headerTimer.schedule({ if (state.compareAndSet(HEADERS_WAITING, HEADERS_TIMED_OUT)) call.cancel() }, rawHeaderTimeoutMs, TimeUnit.MILLISECONDS)
    val response = try {
      call.execute()
    } catch (e: IOException) {
      timer.cancel(false)
      if (state.get() == HEADERS_TIMED_OUT) return null
      throw e
    }
    timer.cancel(false)
    if (state.compareAndSet(HEADERS_WAITING, HEADERS_ARRIVED)) return response
    response.close()
    return null
  }

  override fun fetch(config: ServerConnectionConfig, itemId: String, raw: Boolean): FetchedCover? {
    val url = (config.address.trimEnd('/') + "/api/items/$itemId/cover" + if (raw) "?raw=1" else "").toHttpUrlOrNull() ?: return null
    val request = Request.Builder().url(url).apply {
      config.customHeaders?.forEach { (name, value) -> header(name, value) }
      header("Authorization", "Bearer ${config.token}")
      header("Accept", "image/webp,image/*;q=0.8")
    }.build()
    val call = (if (raw) rawClient else client).newCall(request)
    val response = try {
      if (raw) awaitHeaders(call) else call.execute()
    } catch (e: IOException) {
      return refuse(e.javaClass.simpleName)
    } ?: return refuse("headers timed out")
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
 * One cover cache for the WebView and the native UI. The standard cover is answered at once from disk, never waiting on
 * the network (the WebView holds a few requests per host, so one blocked answer stalls every cover behind it); a miss
 * or an older version is fetched in the background, newest request first, and [onCoverReady] then tells the app to ask
 * again. Offline the cached version is served even when it is older than the one asked for.
 */
class CoverCache(
  private val store: CoverStore,
  private val fetcher: CoverFetcher,
  private val activeServer: () -> ServerConnectionConfig?,
  private val serverState: () -> ServerState,
  private val replicaUpdatedAt: suspend (serverConfigId: String, itemId: String) -> Long?,
  private val scope: CoroutineScope,
  private val workers: Int = COVER_FETCH_PARALLELISM,
  private val queueMax: Int = COVER_QUEUE_MAX,
  private val logError: (String) -> Unit = { AbsLogger.error("CoverCache", it) },
) {
  /** Called on a worker thread when a cover the WebView was refused (or got older) is stored. */
  @Volatile var onCoverReady: ((itemId: String) -> Unit)? = null

  private class Job(val itemId: String, val updatedAt: Long?)

  private val lock = Any()
  // The tail is the newest request: workers take from it
  private val queue = ArrayDeque<Job>()
  private val queued = HashMap<String, Job>()
  private val running = HashSet<String>()
  // One fetch per item at a time: a "latest" and a versioned job may resolve to the same file
  private val runningItems = HashSet<String>()
  // What the WebView was given per item while a fetch is pending (-1: nothing); announced only if something newer is stored
  private val awaitingAnnounce = HashMap<String, Long>()
  // itemId_version the server answered with an image type the route can't serve back: not asked again in this process
  private val unservable = HashSet<String>()
  private val wakeups = Channel<Unit>(Channel.UNLIMITED)
  private var started = false

  // UNKNOWN on a cold start may fetch: an <img> never asks again, so first cards would stay without covers
  fun mayFetch(): Boolean = activeServer() != null && serverState() != ServerState.OFFLINE

  /** The server is known to answer; UNKNOWN and UNAUTHORIZED are not enough to drop a cached cover for its URL. */
  fun isOnline(): Boolean = activeServer() != null && serverState() == ServerState.ONLINE

  /**
   * The cached cover to show now, marked as used; never waits. Online, a miss or an older version than [updatedAt]
   * (or any version when [updatedAt] is unknown, to check it) is fetched in the background; with [announce] the app is
   * told when it is stored.
   */
  fun standard(itemId: String, updatedAt: Long?, announce: Boolean = true): CoverEntry? {
    if (!DownloadIdentifiers.isSafe(itemId)) return null
    val newest = store.newest(itemId)
    // A card with a stale updatedAt must not fetch today's server cover under its old key
    if (newest != null && updatedAt != null && newest.updatedAt >= updatedAt) {
      store.touch(newest)
      return newest
    }
    if (mayFetch()) enqueue(itemId, updatedAt, served = if (announce) newest?.updatedAt ?: -1 else null)
    return newest?.also { store.touch(it) }
  }

  /**
   * The standard cover for a canvas that draws once: a miss online waits for its background fetch up to [timeoutMs].
   */
  suspend fun awaitStandard(itemId: String, updatedAt: Long?, timeoutMs: Long): CoverEntry? {
    standard(itemId, updatedAt)?.let { if (updatedAt == null || it.updatedAt >= updatedAt) return it }
    if (!mayFetch()) return newestCached(itemId)
    return withTimeoutOrNull(timeoutMs) { pollStored(itemId, updatedAt) } ?: newestCached(itemId)
  }

  // The stored cover once it is there, or whatever is cached once no fetch of the item is pending any more
  private suspend fun pollStored(itemId: String, updatedAt: Long?): CoverEntry? {
    while (true) {
      val newest = store.newest(itemId)
      if (newest != null && (updatedAt == null || newest.updatedAt >= updatedAt)) return newest
      if (synchronized(lock) { itemId !in runningItems && queue.none { it.itemId == itemId } }) return newest
      delay(AWAIT_POLL_MS)
    }
  }

  /** The original image, proxied and never stored; it seeds the standard cover, which is what offline gets. */
  suspend fun raw(itemId: String, updatedAt: Long?): CoverAnswer {
    if (!DownloadIdentifiers.isSafe(itemId)) return CoverAnswer.Miss
    // The page already shows the original: storing the standard copy needs no "ready" notice
    val standard = standard(itemId, updatedAt, announce = false)
    val config = activeServer()
    if (!mayFetch() || config == null) return cachedOrMiss(standard)
    val fetched = withContext(Dispatchers.IO) { fetcher.fetch(config, itemId, raw = true) } ?: return cachedOrMiss(standard)
    return CoverAnswer.Stream(fetched.mime, fetched.body)
  }

  /** The newest cached version, marked as used; no network (offline WebView answers). */
  fun newestCached(itemId: String): CoverEntry? = store.newest(itemId)?.also { store.touch(it) }

  /** For native consumers: the newest cached version, index only; marked as used so a playing book's cover stays. */
  fun latestEntry(itemId: String): CoverEntry? = store.peekNewest(itemId)?.also { store.touch(it) }

  fun latestFile(itemId: String): File? = latestEntry(itemId)?.file

  /** For tests: suspends until nothing is queued or being fetched. */
  internal suspend fun awaitIdleForTest() {
    while (synchronized(lock) { queue.isNotEmpty() || running.isNotEmpty() }) delay(5)
  }

  private fun cachedOrMiss(entry: CoverEntry?): CoverAnswer = entry?.let { CoverAnswer.Cached(it, coverMimeOf(it.file)) } ?: CoverAnswer.Miss

  private fun keyOf(itemId: String, updatedAt: Long?) = "${itemId}_${updatedAt ?: "latest"}"

  private fun enqueue(itemId: String, updatedAt: Long?, served: Long?) {
    val key = keyOf(itemId, updatedAt)
    val wake = synchronized(lock) {
      if (served != null) awaitingAnnounce[itemId] = minOf(awaitingAnnounce[itemId] ?: served, served)
      if (key in running || key in unservable) return
      startWorkers()
      val job = queued[key]
      if (job != null) {
        // Asked again: the card is on screen now, so it moves to the front
        queue.remove(job)
        queue.addLast(job)
        false
      } else {
        queue.addLast(Job(itemId, updatedAt))
        queued[key] = queue.last()
        // A full queue sheds the request asked for longest ago, not the visible ones
        if (queue.size > queueMax) queue.removeFirst().let { queued -= keyOf(it.itemId, it.updatedAt) }
        true
      }
    }
    if (wake) wakeups.trySend(Unit)
  }

  private fun startWorkers() {
    if (started) return
    started = true
    repeat(workers) {
      scope.launch {
        for (wakeup in wakeups) {
          val job = take() ?: continue
          try {
            fetch(job)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            // A failed job must not take its worker down with it, nor may logging it
            runCatching { logError("Cover fetch failed: ${e.javaClass.simpleName}") }
          } finally {
            val more = synchronized(lock) {
              running -= keyOf(job.itemId, job.updatedAt)
              runningItems -= job.itemId
              queue.isNotEmpty()
            }
            // A job skipped while its item was being fetched gets a worker now
            if (more) wakeups.trySend(Unit)
          }
        }
      }
    }
  }

  private fun take(): Job? = synchronized(lock) {
    val job = queue.lastOrNull { it.itemId !in runningItems } ?: return null
    queue.remove(job)
    val key = keyOf(job.itemId, job.updatedAt)
    queued -= key
    running += key
    runningItems += job.itemId
    job
  }

  private suspend fun fetch(job: Job) {
    // The server may have gone away while this request was queued
    if (!mayFetch()) return
    val config = activeServer() ?: return
    // Unknown version and no replica row: kept as the lowest version, replaced by any versioned one
    val version = job.updatedAt ?: replicaUpdatedAt(config.id, job.itemId) ?: 0L
    val versionKey = keyOf(job.itemId, version)
    if (synchronized(lock) { versionKey in unservable }) return
    val cached = store.newest(job.itemId)
    if (cached == null || cached.updatedAt < version) {
      val fetched = fetcher.fetch(config, job.itemId, raw = false) ?: return
      val ext = fetched.ext
      if (ext == null) {
        fetched.body.close()
        synchronized(lock) { unservable += versionKey }
        return
      }
      fetched.body.use { body -> store.put(job.itemId, version, ext) { body.copyTo(it) } } ?: return
    }
    announce(job.itemId)
  }

  private fun announce(itemId: String) {
    val stored = store.peekNewest(itemId) ?: return
    val ready = synchronized(lock) {
      val served = awaitingAnnounce[itemId] ?: return
      // Kept otherwise: a "latest" check that found nothing newer must not swallow a versioned fetch's notice
      (stored.updatedAt > served).also { if (it) awaitingAnnounce -= itemId }
    }
    if (ready) onCoverReady?.invoke(itemId)
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
