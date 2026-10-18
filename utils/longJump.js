// Long jump lengths offered in settings, in seconds (the unit of the short jumps); 0 is Off and hides that key
export const LONG_JUMP_SECONDS_OPTIONS = [0, 15, 20, 30, 45, 60, 120, 180, 300, 600, 900, 1800]

// A stored long jump length in whole seconds; 0 (Off) for anything missing, invalid or not positive
export function normalizeLongJumpTime(value) {
  const seconds = Number(value)
  return Number.isInteger(seconds) && seconds > 0 ? seconds : 0
}
