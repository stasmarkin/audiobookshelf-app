package app.absplus.android.server

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.KeyStore
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Real HTTP/TLS exchanges through TokenRefresher and OkHttp's redirect chain, never interceptor responses.
 * All servers bind loopback; all tokens and the TLS private key are public synthetic test fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TokenRefresherRedirectTest {
  companion object {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val tls: Pair<SSLContext, X509TrustManager> by lazy {
      val keyStore = KeyStore.getInstance("PKCS12")
      val bytes = TokenRefresherRedirectTest::class.java.getResourceAsStream("/r01-loopback.p12.b64")!!
              .use { Base64.getDecoder().decode(it.readBytes()) }
      keyStore.load(bytes.inputStream(), "synthetic-only".toCharArray())
      val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      keys.init(keyStore, "synthetic-only".toCharArray())
      val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      trusts.init(keyStore)
      val trust = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
      // JVM fixture only: avoid Robolectric's injected Conscrypt provider requiring JDK module opens.
      SSLContext.getInstance("TLS", "SunJSSE").apply { init(keys.keyManagers, arrayOf(trust), null) } to trust
    }
    private fun client() = OkHttpClient.Builder()
            .sslSocketFactory(tls.first.socketFactory, tls.second)
            .dns(object : okhttp3.Dns {
              override fun lookup(hostname: String): List<InetAddress> {
                require(hostname in setOf("127.0.0.1", "localhost", "a.test.invalid", "b.test.invalid", "abs_server", "xn--bcher-kva.test"))
                return listOf(loopback)
              }
            })
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS).build()
  }

  private data class Seen(val method: String, val path: String, val refresh: String?, val authorization: String?, val cookie: String?)

  private class Server(secure: Boolean = false) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "r01-loopback").apply { isDaemon = true } }
    private val listener = (if (secure) tls.first.serverSocketFactory.createServerSocket() else ServerSocket()).apply {
      bind(InetSocketAddress(loopback, 0), 4)
    }
    val url = "${if (secure) "https" else "http"}://127.0.0.1:${listener.localPort}"
    val seen = Collections.synchronizedList(mutableListOf<Seen>())
    @Volatile var code = 200
    @Volatile var location: String? = null
    @Volatile var beforeResponse: () -> Unit = {}
    init {
      executor.submit {
        while (!listener.isClosed) {
          val socket = try { listener.accept() } catch (closed: SocketException) {
            if (listener.isClosed) break else throw closed
          }
          executor.submit {
            try {
            socket.use {
              it.soTimeout = 5000
              val input = it.getInputStream()
              val header = StringBuilder()
              while (!header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                require(byte >= 0 && header.length < 16384)
                header.append(byte.toChar())
              }
              val lines = header.toString().split("\r\n")
              val first = lines.first().split(' ')
              val headers = lines.drop(1).filter { ':' in it }.associate {
                it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
              }
              require(headers["content-length"].orEmpty().let { it.isEmpty() || it == "0" })
              seen.add(Seen(first[0], first[1], headers["x-refresh-token"], headers["authorization"], headers["cookie"]))
              beforeResponse()
              val body = """{"user":{"accessToken":"synthetic-access-new","refreshToken":"synthetic-refresh-new"}}""".toByteArray()
              val response = "HTTP/1.1 $code Fixture\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                      "Connection: close\r\nSet-Cookie: syntheticSession=fixture; Path=/\r\n" +
                      (location?.let { dest -> "Location: $dest\r\n" } ?: "") + "\r\n"
              it.getOutputStream().apply { write(response.toByteArray()); write(body); flush() }
              // Wait for the peer to consume the complete response and close; avoid a TCP reset
              // racing the redirect request on Windows. The socket timeout bounds broken clients.
              try { input.read() } catch (_: java.io.IOException) { /* Peer closed after consuming the response. */ }
            }
            } catch (e: Exception) { e.printStackTrace() }
          }
        }
      }
    }
    override fun close() { listener.close(); executor.shutdownNow() }
  }

  private class Store : RefreshTokenStore {
    val tokens = Collections.synchronizedMap(mutableMapOf("A" to "synthetic-refresh-A", "B" to "synthetic-refresh-B"))
    override fun getRefreshToken(serverConnectionId: String) = tokens[serverConnectionId]
    override fun replaceRefreshToken(serverConnectionId: String, expected: String, newToken: String) = synchronized(tokens) {
      if (tokens[serverConnectionId] != expected) false else { tokens[serverConnectionId] = newToken; true }
    }
    override fun removeRefreshTokenIf(serverConnectionId: String, expected: String) = synchronized(tokens) {
      if (tokens[serverConnectionId] != expected) false else { tokens.remove(serverConnectionId); true }
    }
  }

  private class Harness(address: String) {
    val store = Store()
    val addresses = Collections.synchronizedMap(mutableMapOf("A" to address))
    val rotated = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val rejected = Collections.synchronizedList(mutableListOf<String>())
    val logs = Collections.synchronizedList(mutableListOf<String>())
    val refresher = TokenRefresher(store, { addresses[it] }, { id, token -> rotated.add(id to token) },
            { rejected.add(it) }, { logs.add(it) }, SingleFlight())
    fun refresh(http: OkHttpClient = client()): RefreshResult {
      val done = CountDownLatch(1)
      var result: RefreshResult? = null
      refresher.refresh("A", http) { result = it; done.countDown() }
      assertTrue("refresh callback completed", done.await(8, TimeUnit.SECONDS))
      return result!!
    }
    fun retained() {
      assertEquals("synthetic-refresh-A", store.tokens["A"])
      assertEquals("synthetic-refresh-B", store.tokens["B"])
      assertTrue(rotated.isEmpty())
      assertTrue(rejected.isEmpty())
      assertFalse(logs.any { "synthetic-" in it || "://" in it })
    }
  }

  @Test fun directHttpAndHttpsRenewOnlyTheSavedOwnerAndRotateAgain() {
    for (secure in listOf(false, true)) Server(secure).use { server ->
      val harness = Harness(server.url + "/reverse proxy")
      repeat(2) { assertEquals(RefreshResult.Success("synthetic-access-new"), harness.refresh()) }
      assertEquals(listOf("synthetic-refresh-A", "synthetic-refresh-new"), server.seen.map { it.refresh })
      assertTrue(server.seen.all { it.method == "POST" && it.path == "/reverse%20proxy/auth/refresh" && it.authorization == null && it.cookie == null })
      assertEquals("synthetic-refresh-B", harness.store.tokens["B"])
      assertEquals(listOf("A", "A"), harness.rotated.map { it.first })
    }
  }

  @Test fun allRedirectStatusCodesStopBeforeASecondOriginAndKeepCredentials() {
    Server().use { target -> Server().use { source ->
      for (code in listOf(300, 301, 302, 303, 307, 308)) {
        source.code = code
        source.location = target.url + "/capture"
        val harness = Harness(source.url)
        val result = harness.refresh()
        assertTrue("R01: no target request (status $code); observed refresh headers=${target.seen.map { it.refresh }}", target.seen.isEmpty())
        assertEquals(RefreshResult.Transient, result)
        harness.retained()
        assertTrue(harness.logs.any { "redirect blocked" in it && "retry" in it })
      }
      assertEquals(6, source.seen.size)
      assertTrue(source.seen.all { it.refresh == "synthetic-refresh-A" })
    } }
  }

  @Test fun sameOriginRelativeTraversalUserinfoAndChainsAreNotFollowed() {
    Server().use { target -> Server().use { source ->
      // /next would redirect again; rejecting the first hop also prevents chains and loops.
      val locations = listOf("/next", "next", "../outside", "/abs/%2e%2e/outside", "/abs/%252e%252e/outside",
              source.url + "/auth/refresh", "//127.0.0.1:${source.url.substringAfterLast(':')}/next",
              target.url.replace("://", "://user:password@") + "/capture",
              target.url.replace("://", "://@") + "/capture", "file:///synthetic", "http://[invalid")
      for (location in locations) {
        source.code = 302; source.location = location
        val before = source.seen.size
        val harness = Harness(source.url + "/abs")
        assertEquals(RefreshResult.Transient, harness.refresh())
        assertEquals("exactly the original request", before + 1, source.seen.size)
        assertTrue(target.seen.isEmpty())
        harness.retained()
      }
    } }
  }

  @Test fun schemeHostAndPortChangesIncludingTlsDowngradesAreBlocked() {
    Server(true).use { tlsServer -> Server().use { plain -> Server().use { other ->
      val cases = listOf(tlsServer to plain.url, plain to tlsServer.url, plain to other.url,
              plain to plain.url.replace("127.0.0.1", "localhost"))
      for ((source, dest) in cases) {
        tlsServer.seen.clear(); plain.seen.clear(); other.seen.clear()
        source.code = 307; source.location = "$dest/capture"
        val harness = Harness(source.url)
        assertEquals(RefreshResult.Transient, harness.refresh())
        assertEquals("no redirect request across scheme, host or port", 1,
                tlsServer.seen.size + plain.seen.size + other.seen.size)
        harness.retained()
        source.code = 200; source.location = null
      }
    } } }
  }

  @Test fun redirectFailureReleasesSingleFlightAndDirectRetrySucceedsWithoutChangingTheParentClient() {
    Server().use { source -> Server().use { target ->
      source.code = 302; source.location = target.url
      val harness = Harness(source.url)
      val http = client()
      assertEquals(RefreshResult.Transient, harness.refresh(http))
      harness.retained()
      assertTrue(http.followRedirects && http.followSslRedirects)
      // Ordinary traffic on the parent client still follows a redirect.
      http.newCall(Request.Builder().url(source.url + "/ordinary").build()).execute().use { assertEquals(200, it.code) }
      assertEquals(1, target.seen.size)
      assertNull(target.seen.single().refresh)
      source.code = 200; source.location = null
      assertEquals(RefreshResult.Success("synthetic-access-new"), harness.refresh(http))
      assertEquals("synthetic-refresh-new", harness.store.tokens["A"])
    } }
  }

  @Test fun removedOwnerAndLateRemovalNeverAdoptAnotherServersToken() {
    Server().use { server ->
      val missing = Harness(server.url)
      missing.addresses.remove("A")
      assertEquals(RefreshResult.Rejected, missing.refresh())
      assertTrue(server.seen.isEmpty())
      val pending = Harness(server.url)
      server.beforeResponse = { pending.addresses.remove("A"); pending.store.tokens.remove("A") }
      assertEquals(RefreshResult.Transient, pending.refresh())
      assertEquals("synthetic-refresh-A", server.seen.single().refresh)
      assertNull(pending.store.tokens["A"])
      assertEquals("synthetic-refresh-B", pending.store.tokens["B"])
      assertTrue(pending.rotated.isEmpty())
    }
  }

  @Test fun legacyAddressesUseTheExistingOkHttpDestinationWithoutNewUrlRules() {
    Server().use { server ->
      val port = server.url.substringAfterLast(':')
      for (address in listOf(server.url, "http://abs_server:$port", "http://bücher.test:$port",
              "http://a.test.invalid:$port/abs path", "http://a.test.invalid:$port/abs/",
              "http://a.test.invalid:$port/abs?tenant=home office")) {
        val expected = Request.Builder().url("$address/auth/refresh").build().url
        assertEquals(RefreshResult.Success("synthetic-access-new"), Harness(address).refresh())
        val wirePath = expected.encodedPath + (expected.encodedQuery?.let { "?$it" } ?: "")
        assertEquals(wirePath, server.seen.last().path)
      }
    }
  }
}
