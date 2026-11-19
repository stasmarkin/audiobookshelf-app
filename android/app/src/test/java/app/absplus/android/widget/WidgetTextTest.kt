package app.absplus.android.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetTextTest {
  @Test
  fun formatsLikeTheAppTimestamps() {
    assertEquals("0:00", WidgetText.format(0))
    assertEquals("0:59", WidgetText.format(59_999))
    assertEquals("12:05", WidgetText.format(725_000))
    assertEquals("5:11:14", WidgetText.format((5 * 3600 + 11 * 60 + 14) * 1000L))
    assertEquals("0:00", WidgetText.format(-5_000))
  }

  @Test
  fun snapshotShowsElapsedRemainingAndProgress() {
    val snapshot = WidgetText.snapshot(positionMs = 18_674_000, durationMs = 59_167_000)!!
    assertEquals("5:11:14", snapshot.elapsed)
    assertEquals("-11:14:53", snapshot.remaining)
    assertEquals(315, snapshot.progress)
  }

  @Test
  fun snapshotIsClampedAndHiddenWhenUnknown() {
    assertEquals(WidgetText.PROGRESS_MAX, WidgetText.snapshot(120_000, 60_000)!!.progress)
    assertEquals("-0:00", WidgetText.snapshot(120_000, 60_000)!!.remaining)
    assertEquals(0, WidgetText.snapshot(0, 60_000)!!.progress)
    assertNull(WidgetText.snapshot(null, 60_000))
    assertNull(WidgetText.snapshot(1_000, null))
    assertNull(WidgetText.snapshot(1_000, 0))
    assertNull(WidgetText.snapshot(-1, 60_000))
  }

  @Test fun timesAreListeningTimeAtTheSpeedAndTheBarIsTheShareOfTheBook() {
    val atOne = WidgetText.snapshot(1_800_000, 3_600_000, 1f)!!
    assertEquals(WidgetText.snapshot(1_800_000, 3_600_000), atOne)
    val atTwo = WidgetText.snapshot(1_800_000, 3_600_000, 2f)!!
    assertEquals("15:00", atTwo.elapsed)
    assertEquals("-15:00", atTwo.remaining)
    assertEquals(atOne.progress, atTwo.progress)
    assertEquals("-40:00", WidgetText.snapshot(0, 3_600_000, 1.5f)!!.remaining)
    listOf(0f, -1f, Float.NaN).forEach { assertEquals("$it", atOne, WidgetText.snapshot(1_800_000, 3_600_000, it)) }
  }
}

