package app.absplus.android.sync

import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.server.ApiHandler
import app.absplus.android.server.RefreshResult
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/**
 * [SyncHttp] against a fake server (an OkHttp interceptor scripted per call) and fake refresh/token sources.
 * Synthetic ids, addresses and tokens only.
 */
class SyncHttpTest {
  private val config = ServerConnectionConfig("srv", 0, "Synthetic", "https://srv.test.invalid/abs", "2.30.0", "user", "synthetic", "t1",
          mapOf("X-Custom" to "yes"))

  private class Sink : ReachabilitySink {
    val responses = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
    val unreachable = Collections.synchronizedList(mutableListOf<String>())
    override fun reportResponse(address: String, code: Int) { responses.add(address to code) }
    override fun reportUnreachable(address: String) { unreachable.add(address) }
  }

  /** Answers call n (from 1) with `script(request, n)`: a status and body, or an exception to throw. */
  private class FakeServer(val script: (Request, Int) -> Pair<Int, String>) {
    val requests = Collections.synchronizedList(mutableListOf<Request>())
    val bodies = Collections.synchronizedList(mutableListOf<String?>())
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
      val request = chain.request()
      requests.add(request)
      bodies.add(request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() })
      val (code, body) = script(request, requests.size)
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fake")
              .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()

    fun authorizations() = requests.map { it.header("Authorization") }
  }

  private class Refresher(val result: RefreshResult) {
    val calls = Collections.synchronizedList(mutableListOf<String>())
    val refresh: (String, (RefreshResult) -> Unit) -> Unit = { id, onResult -> calls.add(id); onResult(result) }
  }

  private fun http(server: FakeServer, sink: Sink, refresher: Refresher = Refresher(RefreshResult.Transient),
                   token: () -> String? = { "t1" }) =
          SyncHttp(server.client, refresher.refresh, { id -> assertEquals("srv", id); token() }, sink)

  @Test
  fun okReturnsBodyAndReportsResponse() = runBlocking {
    val server = FakeServer { _, _ -> 200 to """{"a":1}""" }
    val sink = Sink()

    val outcome = http(server, sink).send(config, "POST", "/api/me/item/li1/bookmark", """{"time":754,"title":"x"}""")

    assertEquals(HttpOutcome.Ok(200, """{"a":1}"""), outcome)
    assertEquals(listOf(config.address to 200), sink.responses)
    val request = server.requests.single()
    assertEquals("https://srv.test.invalid/abs/api/me/item/li1/bookmark", request.url.toString())
    assertEquals("POST", request.method)
    assertEquals("Bearer t1", request.header("Authorization"))
    assertEquals("yes", request.header("X-Custom"))
    assertEquals("""{"time":754,"title":"x"}""", server.bodies.single())
  }

  @Test
  fun errorStatusIsHttpErrorAndStillReported() = runBlocking {
    val server = FakeServer { _, _ -> 404 to "Not found" }
    val sink = Sink()

    assertEquals(HttpOutcome.HttpError(404), http(server, sink).send(config, "DELETE", "/api/me/item/li1/bookmark/754"))
    assertEquals(listOf(config.address to 404), sink.responses)
    assertEquals(null, server.bodies.single())
  }

  @Test
  fun ioExceptionIsUnreachable() = runBlocking {
    val server = FakeServer { _, _ -> throw IOException("connection refused") }
    val sink = Sink()

    val outcome = http(server, sink).send(config, "GET", "/api/me")

    assertEquals(HttpOutcome.Unreachable("connection refused"), outcome)
    assertEquals(listOf(config.address), sink.unreachable)
    assertTrue(sink.responses.isEmpty())
  }

  @Test
  fun newerTokenRetriesWithoutRefresh() = runBlocking {
    var current = "t1"
    val server = FakeServer { _, n -> if (n == 1) { current = "t2"; 401 to "" } else 200 to "{}" }
    val refresher = Refresher(RefreshResult.Success("t3"))

    val outcome = http(server, Sink(), refresher) { current }.send(config, "GET", "/api/me")

    assertEquals(HttpOutcome.Ok(200, "{}"), outcome)
    assertEquals(listOf("Bearer t1", "Bearer t2"), server.authorizations())
    assertTrue(refresher.calls.isEmpty())
  }

  @Test
  fun unauthorizedRefreshesOnceAndRetriesWithNewToken() = runBlocking {
    val refresher = Refresher(RefreshResult.Success("t2"))
    val renewed = FakeServer { _, n -> if (n == 1) 401 to "" else 200 to "{}" }

    assertEquals(HttpOutcome.Ok(200, "{}"), http(renewed, Sink(), refresher).send(config, "GET", "/api/me"))
    assertEquals(listOf("Bearer t1", "Bearer t2"), renewed.authorizations())
    assertEquals(listOf("srv"), refresher.calls)

    val stillRejected = FakeServer { _, _ -> 401 to "" }
    val again = Refresher(RefreshResult.Success("t2"))
    assertEquals(HttpOutcome.Unauthorized, http(stillRejected, Sink(), again).send(config, "GET", "/api/me"))
    assertEquals(2, stillRejected.requests.size)
    assertEquals(1, again.calls.size)
  }

  @Test
  fun rejectedRefreshIsUnauthorized() = runBlocking {
    val server = FakeServer { _, _ -> 401 to "" }
    val refresher = Refresher(RefreshResult.Rejected)

    assertEquals(HttpOutcome.Unauthorized, http(server, Sink(), refresher).send(config, "GET", "/api/me"))
    assertEquals(1, server.requests.size)
  }

  @Test
  fun transientRefreshIsUnreachable() = runBlocking {
    val server = FakeServer { _, _ -> 401 to "" }
    val refresher = Refresher(RefreshResult.Transient)

    val outcome = http(server, Sink(), refresher).send(config, "GET", "/api/me")

    assertTrue(outcome is HttpOutcome.Unreachable)
    assertEquals(1, server.requests.size)
  }

  @Test
  fun staleGenerationDropsAnswer() = runBlocking {
    // A logout while the request is on the wire: the answer, even a failure, must not move the new session's state
    val failing = FakeServer { _, _ -> ApiHandler.invalidateAuthRequests(); throw IOException("connection reset") }
    val sink = Sink()
    assertEquals(HttpOutcome.STALE, http(failing, sink).send(config, "GET", "/api/me"))

    val answered = FakeServer { _, _ -> ApiHandler.invalidateAuthRequests(); 200 to "{}" }
    assertEquals(HttpOutcome.STALE, http(answered, sink).send(config, "POST", "/api/me/item/li1/bookmark", "{}"))

    val refresher = Refresher(RefreshResult.Success("t2"))
    val rejected = FakeServer { _, _ -> ApiHandler.invalidateAuthRequests(); 401 to "" }
    assertEquals(HttpOutcome.STALE, http(rejected, sink, refresher).send(config, "GET", "/api/me"))
    assertTrue("no refresh for a stale 401", refresher.calls.isEmpty())

    assertTrue(sink.responses.isEmpty())
    assertTrue(sink.unreachable.isEmpty())
  }

  @Test
  fun logoutDuringRefreshDropsTheRetry() = runBlocking {
    val server = FakeServer { _, _ -> 401 to "" }
    val refresh: (String, (RefreshResult) -> Unit) -> Unit = { _, onResult ->
      ApiHandler.invalidateAuthRequests()
      onResult(RefreshResult.Success("t2"))
    }

    val outcome = SyncHttp(server.client, refresh, { "t1" }, Sink()).send(config, "GET", "/api/me")

    assertEquals(HttpOutcome.STALE, outcome)
    assertEquals(1, server.requests.size)
  }
}
