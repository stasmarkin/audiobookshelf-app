package app.absplus.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.db.userdata.MediaProgressEntity
import app.absplus.android.db.userdata.UserDataDatabase
import app.absplus.android.server.RefreshResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A request the fake server received. */
data class FakeRequest(val method: String, val path: String, val body: String?) {
  override fun toString() = "$method $path"
}

/**
 * A scripted Audiobookshelf server behind an OkHttp interceptor. [handler] answers with a status and body, or throws
 * [IOException] for a server that does not answer. Synthetic addresses only.
 */
class FakeApi(var handler: (FakeRequest) -> Pair<Int, String> = { 200 to "{}" }) {
  // Read by the test thread (paths(), count, any) while OkHttp threads add to it: iteration must not throw
  val requests: MutableList<FakeRequest> = CopyOnWriteArrayList()
  private val active = AtomicInteger()
  /** The most requests the server ever handled at the same time. */
  val maxConcurrent = AtomicInteger()

  val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
    val request = chain.request()
    val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
    val fake = FakeRequest(request.method, request.url.encodedPath.removePrefix(BASE_PATH), body)
    requests.add(fake)
    val now = active.incrementAndGet()
    maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
    try {
      val (code, text) = handler(fake)
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fake")
              .body(text.toResponseBody("application/json".toMediaType())).build()
    } finally {
      active.decrementAndGet()
    }
  }).build()

  fun paths() = requests.map { it.toString() }

  companion object {
    const val BASE_PATH = "/abs"
  }
}

class NoopSink : ReachabilitySink {
  override fun reportResponse(address: String, code: Int) {}
  override fun reportUnreachable(address: String) {}
}

fun syncHttp(api: FakeApi, refresh: RefreshResult = RefreshResult.Transient) =
        SyncHttp(api.client, { _, onResult -> onResult(refresh) }, { "token" }, NoopSink())

fun fakeServer(id: String = "srv", version: String = "2.37.0") =
        ServerConnectionConfig(id, 0, "Synthetic $id", "https://$id.test.invalid${FakeApi.BASE_PATH}", version, "user", "synthetic", "token", null)

fun inMemoryUserData(): UserDataDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), UserDataDatabase::class.java).build()

fun progressRow(currentTime: Double, lastUpdate: Long, pending: Boolean = true, episodeId: String = "") = MediaProgressEntity(
        serverConfigId = "srv", libraryItemId = "li1", episodeId = episodeId, serverProgressId = "mp1", duration = 1000.0,
        progress = currentTime / 1000.0, currentTime = currentTime, isFinished = false, hideFromContinueListening = false,
        ebookLocation = null, ebookProgress = null, lastUpdate = lastUpdate, startedAt = 1L, finishedAt = null,
        source = "device", pending = pending)

/**
 * Polls [condition] for up to 5 s of real time: Room and OkHttp run on their own threads. Only running out of time is
 * a timeout; an exception from the condition itself fails with that exception.
 */
suspend fun eventually(message: String = "condition", condition: suspend () -> Boolean) {
  try {
    withTimeout(5_000) { while (!condition()) delay(10) }
  } catch (e: TimeoutCancellationException) {
    throw AssertionError("Timed out waiting for $message", e)
  }
}
