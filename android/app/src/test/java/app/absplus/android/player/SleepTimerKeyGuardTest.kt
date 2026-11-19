package app.absplus.android.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepTimerKeyGuardTest {
  private val guard = SleepTimerKeyGuard()
  private var window = true
  private var extendResult = true
  private var extends = 0

  private fun send(event: KeyEvent, now: Long) =
    guard.intercept(event, now, { window }, { extends++; extendResult })

  private fun down(time: Long, code: Int = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeat: Int = 0, eventTime: Long = time) =
    KeyEvent(time, eventTime, KeyEvent.ACTION_DOWN, code, repeat)

  private fun up(time: Long, code: Int = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, eventTime: Long = time + 80) =
    KeyEvent(time, eventTime, KeyEvent.ACTION_UP, code, 0)

  @Test fun singlePressExtendsOnceAndSwallowsUp() {
    val press = down(100)
    assertTrue(send(press, 100))
    assertEquals(1, extends)
    assertTrue("same DOWN delivered twice", send(press, 110))
    assertTrue(send(up(100), 180))
    assertEquals(1, extends)
  }

  @Test fun doubleAndTriplePressSwallowedWithinSecond() {
    assertTrue(send(down(100), 100))
    window = false
    assertTrue(send(down(400), 400))
    assertTrue(send(up(400), 480))
    assertTrue(send(down(700), 700))
    assertEquals(1, extends)
    assertFalse("a press after the swallow time acts normally", send(down(1600), 1600))
  }

  @Test fun holdRepeatsSwallowed() {
    assertTrue(send(down(100), 100))
    window = false
    (1..3).forEach { assertTrue(send(down(100, repeat = it, eventTime = 1300L + it * 50), 1300L + it * 50)) }
    assertTrue(send(up(100, eventTime = 1500), 1500))
    assertEquals(1, extends)
  }

  @Test fun widgetKeyNeverIntercepted() {
    assertFalse(send(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), 100))
    assertEquals(0, extends)
  }

  @Test fun upWhoseDownCameBeforeTheWindowExtends() {
    assertTrue(send(up(100, code = KeyEvent.KEYCODE_MEDIA_NEXT), 180))
    assertEquals(1, extends)
  }

  @Test fun upOfAKeyThatActedAtDownDoesNotExtend() {
    assertTrue(send(up(100, code = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD), 180))
    assertEquals(0, extends)
  }

  @Test fun canceledAndHoldRepeatSwallowedWithoutExtend() {
    assertTrue(send(KeyEvent.changeFlags(up(300), KeyEvent.FLAG_CANCELED), 380))
    assertTrue(send(down(500, repeat = 2), 1500))
    assertEquals(0, extends)
  }

  @Test fun overlappingPressesKeepTheirOwnSwallowedState() {
    assertTrue(send(down(100, code = KeyEvent.KEYCODE_HEADSETHOOK), 100))
    window = false
    assertTrue(send(down(500, code = KeyEvent.KEYCODE_MEDIA_NEXT), 500))
    assertTrue("first press released after the swallow time", send(up(100, code = KeyEvent.KEYCODE_HEADSETHOOK, eventTime = 2000), 2000))
    assertEquals(1, extends)
  }

  @Test fun failedExtendPassesThrough() {
    extendResult = false
    assertFalse(send(down(100), 100))
    assertEquals(1, extends)
    window = false
    assertFalse("a failed extend starts no swallow time", send(down(300), 300))
  }

  @Test fun outsideWindowPassesThrough() {
    window = false
    assertFalse(send(down(500, code = KeyEvent.KEYCODE_MEDIA_NEXT), 500))
    assertEquals(0, extends)
  }

  @Test fun nonMediaKeyPassesThrough() {
    assertFalse(send(down(100, code = KeyEvent.KEYCODE_VOLUME_UP), 100))
    assertEquals(0, extends)
  }
}
