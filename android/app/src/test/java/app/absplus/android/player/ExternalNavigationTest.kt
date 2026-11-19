package app.absplus.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalNavigationTest {
  @Test fun aListeningStepCoversMoreOfTheBookAtHigherSpeeds() {
    assertEquals(30_000L, ExternalNavigation.scaleStep(30_000, 1f))
    assertEquals(60_000L, ExternalNavigation.scaleStep(30_000, 2f))
    assertEquals(45_000L, ExternalNavigation.scaleStep(30_000, 1.5f))
    assertEquals(15_000L, ExternalNavigation.scaleStep(30_000, 0.5f))
  }

  @Test fun anUnusableSpeedLeavesTheStepAsSet() {
    listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach { assertEquals("$it", 30_000L, ExternalNavigation.scaleStep(30_000, it)) }
  }

  @Test fun theResumeRewindIsScaledBeforeItStopsAtTheChapterStart() {
    // 3 s of listening at 2x from 65 s, chapter from 60 s: 6 s would cross into the previous chapter
    assertEquals(5_000L, PlayerListener.clampToChapterStart(ExternalNavigation.scaleStep(3_000, 2f), 65_000, 60_000))
    // Well inside the chapter the whole scaled rewind applies
    assertEquals(6_000L, PlayerListener.clampToChapterStart(ExternalNavigation.scaleStep(3_000, 2f), 80_000, 60_000))
    assertEquals(3_000L, PlayerListener.clampToChapterStart(ExternalNavigation.scaleStep(3_000, 1f), 80_000, 60_000))
  }
}
