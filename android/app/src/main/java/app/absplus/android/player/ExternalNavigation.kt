package app.absplus.android.player

import android.view.KeyEvent
import app.absplus.android.data.PlaybackSession

/** External Next/Previous stay inside one playback session, never advancing its book/podcast queue. */
internal object ExternalNavigation {
  fun normalize(mode: String?): String = when (mode) {
    "chapters", "files" -> mode
    else -> "time"
  }

  fun target(mode: String?, forward: Boolean, session: PlaybackSession, position: Long,
             trackIndex: Int, playerTrackCount: Int, forwardMs: Long, backwardMs: Long): Long? {
    val duration = session.totalDurationMs
    if (duration <= 0 || playerTrackCount <= 0) return null
    val now = position.coerceIn(0, duration)
    when (normalize(mode)) {
      "chapters" -> {
        val chapters = session.chapters
        // Keep the original order. Sorting/filtering broken metadata could invent chapter boundaries.
        val valid = session.mediaType == "book" && chapters.isNotEmpty() && chapters.withIndex().all { (i, ch) ->
          ch.start.isFinite() && ch.end.isFinite() && ch.start >= 0 && ch.end > ch.start &&
            ch.end * 1000 <= duration + 1 &&
            (i == 0 || (ch.startMs > chapters[i - 1].startMs && ch.startMs >= chapters[i - 1].endMs))
        }
        if (valid) {
          if (forward) return chapters.firstOrNull { it.startMs > now }?.startMs
          val current = chapters.indexOfLast { it.startMs <= now }
          if (current < 0) return 0
          // Match the on-screen chapter Previous: restart after 4 seconds; otherwise previous chapter.
          return if (now - chapters[current].startMs > 4000) chapters[current].startMs
                 else chapters[(current - 1).coerceAtLeast(0)].startMs
        }
      }
      "files" -> {
        val tracks = session.audioTracks
        val valid = !session.isHLS && tracks.size == playerTrackCount && trackIndex in tracks.indices &&
          tracks.withIndex().all { (i, track) ->
            track.startOffset.isFinite() && track.duration.isFinite() && track.duration > 0 &&
              (if (i == 0) track.startOffsetMs == 0L else track.startOffsetMs == tracks[i - 1].endOffsetMs)
          }
        if (valid) {
          val targetIndex = if (forward) trackIndex + 1 else (trackIndex - 1).coerceAtLeast(0)
          return tracks.getOrNull(targetIndex)?.startOffsetMs
        }
      }
    }
    // No reliable chapters/original-file timeline: use the user's time skips, without seeking to ENDED.
    val last = (duration - 2000).coerceAtLeast(0)
    return if (forward) {
      if (now >= last) null else (now + forwardMs.coerceAtLeast(0).coerceAtMost(duration)).coerceAtMost(last)
    } else (now - backwardMs.coerceAtLeast(0).coerceAtMost(duration)).coerceAtLeast(0)
  }
}

/** Consume both halves of navigation keys, acting once. Repeat DOWNs and duplicate intents do not seek. */
internal class ExternalNavigationKeys {
  private data class Delivery(val code: Int, val action: Int, val down: Long, val time: Long, val device: Int)
  private val delivered = ArrayDeque<Delivery>()

  fun isNavigation(code: Int) = code in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)

  fun shouldAct(event: KeyEvent): Boolean {
    val action = if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS)
      KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN
    if (event.action != action || event.repeatCount != 0 || event.isCanceled) return false
    // The home widget's pending intents carry the same KeyEvent(action, code) with no times on every tap:
    // without an identity they cannot be duplicates of each other, so each one is a press.
    if (event.downTime == 0L && event.eventTime == 0L) return true
    val key = Delivery(event.keyCode, event.action, event.downTime, event.eventTime, event.deviceId)
    if (key in delivered) return false
    delivered.addLast(key)
    if (delivered.size > 32) delivered.removeFirst()
    return true
  }
}
