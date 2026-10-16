// "Update once a minute" (player menu, for e-paper screens, where every changed pixel is a visible refresh). The player
// still polls the native position every second, because bookmarks and the refocus checks read it; only what it draws is
// coarse: times as H:MM, bars in steps, the sleep timer in minutes, and nothing is written while those values stand still.

export const COARSE_STEP_S = 60

// The position the player draws: `seconds` rounded down to whole `step`s counted from `origin` (the start of the book, or of
// the chapter on the chapter track), never before the origin. Every readout and bar derived from it changes at the same
// moment. The step is a minute of whatever the readout shows: listening time (60 s times the rate) or media time (60 s).
export function coarseTime(seconds, step = COARSE_STEP_S, origin = 0) {
  const from = Number.isFinite(origin) ? origin : 0
  if (!Number.isFinite(seconds) || seconds <= from) return from
  const size = step > 0 ? step : COARSE_STEP_S
  return from + Math.floor((seconds - from) / size) * size
}

// H:MM. Elapsed time rounds down; remaining time rounds up, so it reaches 0:00 only at the end
export function coarseClock(seconds, roundUp = false) {
  const value = Number.isFinite(seconds) ? Math.max(0, seconds) : 0
  // Tolerate float noise from rate-scaled steps (89.99999 minutes is 90)
  const minutes = roundUp ? Math.ceil(value / 60 - 1e-6) : Math.floor(value / 60 + 1e-6)
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`
}

export function sleepRemainingLabel(seconds, coarse) {
  if (!seconds) return '0s'
  const secondsRemaining = Math.round(seconds)
  if (coarse || secondsRemaining > 91) return Math.ceil(secondsRemaining / 60) + 'm'
  return secondsRemaining + 's'
}

// Writes a value to an element only when it differs from the last one written through this painter (per element, so a
// re-created element is always painted). With `always`, it writes regardless and only records the value.
export function createPainter() {
  const painted = new WeakMap()
  return function paint(el, key, value, write, always = false) {
    if (!el) return false
    let values = painted.get(el)
    if (!values) painted.set(el, (values = new Map()))
    if (!always && values.has(key) && values.get(key) === value) return false
    values.set(key, value)
    write(el, value)
    return true
  }
}
