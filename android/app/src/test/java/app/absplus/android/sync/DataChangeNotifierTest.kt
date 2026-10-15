package app.absplus.android.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataChangeNotifierTest {
  @Test
  fun coalescesPerScopeWithin500ms() = runTest {
    val emitted = mutableListOf<Pair<String, List<String>>>()
    val notifier = DataChangeNotifier(500, backgroundScope) { scope, ids -> emitted.add(scope to ids) }

    repeat(10) { i ->
      notifier.notify("bookmarks", listOf("li${i % 3}"))
      if (i == 5) notifier.notify("progress", listOf("li9|"))
      advanceTimeBy(10)
    }
    assertEquals(emptyList<Pair<String, List<String>>>(), emitted)

    advanceTimeBy(500)
    runCurrent()
    assertEquals(listOf("bookmarks" to listOf("li0", "li1", "li2"), "progress" to listOf("li9|")), emitted)

    notifier.notify("bookmarks", listOf("li5"))
    advanceTimeBy(501)
    assertEquals("bookmarks" to listOf("li5"), emitted.last())
    assertEquals(3, emitted.size)
  }

  @Test
  fun versionGrowsBeforeTheEvent() = runTest {
    val notifier = DataChangeNotifier(500, backgroundScope) { _, _ -> }
    assertEquals(0L, notifier.version("progress"))
    notifier.notify("progress", listOf("li1"))
    notifier.notify("progress", listOf("li2"))
    assertEquals("counted at once, not after the window", 2L, notifier.version("progress"))
    assertEquals(0L, notifier.version("catalog"))
  }
}
