package app.absplus.android.sync

import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** The fake server's request log is read by tests while requests arrive on other threads. */
class FakeApiTest {
  @Test
  fun pathsCanBeReadWhileRequestsArrive() {
    val api = FakeApi()
    val start = CountDownLatch(1)
    val writer = thread {
      start.await()
      repeat(20_000) { api.requests.add(FakeRequest("GET", "/api/me/progress", null)) }
    }
    start.countDown()
    // What eventually { gets() == n } does while the engine's requests come in
    while (writer.isAlive) api.paths().count { it == "GET /api/me/progress" }
    writer.join()
  }
}
