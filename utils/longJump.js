// Long jump lengths offered in settings, in seconds (the unit of the short jumps); 0 is Off and hides that key
export const LONG_JUMP_SECONDS_OPTIONS = [0, 15, 20, 30, 45, 60, 120, 180, 300, 600, 900, 1800]

// Only offered durations are valid; missing, malformed or unsupported stored values are Off.
export function normalizeLongJumpTime(value) {
  const seconds = Number(value)
  return LONG_JUMP_SECONDS_OPTIONS.includes(seconds) ? seconds : 0
}
