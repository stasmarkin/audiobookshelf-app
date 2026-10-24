export const AUTO_DELETE_DELAY_DAYS = [0, 1, 3, 7]
export const DOWNLOADS_LIMIT_GB = [0, 1, 2, 5, 10, 20]

// Native settings saved before auto delete existed carry no delay (null), which reads as the 1 day default
export function normalizeDelayDays(value) {
  return AUTO_DELETE_DELAY_DAYS.includes(value) ? value : 1
}

export function normalizeLimitGb(value) {
  return DOWNLOADS_LIMIT_GB.includes(value) ? value : 0
}

// Free space of the storage new downloads go to (Settings → Download storage); null when it can't be read, e.g. the card is out
export function downloadsFreeBytes(status) {
  if (!status) return null
  const setting = String(status.downloadStorage || '')
  if (!setting.startsWith('sd:')) return typeof status.internalFreeBytes === 'number' ? status.internalFreeBytes : null
  const sd = status.sd
  return sd && sd.mounted === true && sd.uuid === setting.slice(3) && typeof sd.freeBytes === 'number' ? sd.freeBytes : null
}

// Turning auto delete on starts a cleanup check at once, which can remove files, so only Off -> On asks first.
// Turning it off, or repeating the current state, never asks.
export function autoDeleteNeedsConfirmation(current, requested) {
  return !current && !!requested
}
