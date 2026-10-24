// What a download in the native queue is doing, and which controls it offers. Derived from the item's
// parts as the native downloader reports them (DownloadItemManager / DownloadControls):
// - a part with a downloadId is being transferred; completed && !moved is a finished transfer still
//   being saved to its folder; isMoving is that save in progress;
// - failed is terminal (automatic retries are used up, or the folder can't be written: permissionLost);
//   storageUnavailable: the SD card is missing; Retry once it is back; authorizationHold: the saved server is no
//   longer the one the download was started for (nothing transfers or is deleted until it is reconnected);
// - waitingForSpace is queued until storage frees up.
// The item's waitingFor (getDownloadQueue) says why a queued item does not transfer: "sdCard" (finished files wait for
// the SD card), for an auto download "network", "wifi", "charging", or "background" (continues in the next background
// window or when the app is open; also any download while Android paused the download service).
// Retry restarts unfinished files from byte 0 (finished files are kept), so it is never called Resume. (A restart of
// the app resumes identified staged bytes on its own; that is not an action.)

export const DownloadState = Object.freeze({
  FINISHING: 'finishing',
  DOWNLOADING: 'downloading',
  WAITING_FOR_STORAGE: 'waitingForStorage',
  QUEUED: 'queued',
  FAILED: 'failed',
  FOLDER_ACCESS: 'folderAccess',
  STORAGE_UNAVAILABLE: 'storageUnavailable',
  HELD: 'held',
  WAITING_FOR_CARD: 'waitingForCard',
  WAITING_FOR_NETWORK: 'waitingForNetwork',
  WAITING_FOR_WIFI: 'waitingForWifi',
  WAITING_FOR_CHARGING: 'waitingForCharging',
  WAITING_FOR_BACKGROUND: 'waitingForBackground'
})

const WAITING_STATES = Object.freeze({
  network: DownloadState.WAITING_FOR_NETWORK,
  wifi: DownloadState.WAITING_FOR_WIFI,
  charging: DownloadState.WAITING_FOR_CHARGING,
  background: DownloadState.WAITING_FOR_BACKGROUND
})

export const DownloadAction = Object.freeze({
  CANCEL: 'cancel',
  RETRY: 'retry',
  CHOOSE_FOLDER: 'chooseFolder',
  CLEAR: 'clear'
})

const parts = (item) => (item && Array.isArray(item.downloadItemParts) ? item.downloadItemParts : [])

export function downloadState(item) {
  const all = parts(item)
  if (item?.waitingFor === 'sdCard') return DownloadState.WAITING_FOR_CARD
  if (all.length && all.every((p) => p.completed && p.moved && !p.failed)) return DownloadState.FINISHING
  // Anything still transferring or being saved keeps the item active, even if another file already failed
  const active = all.some((p) => !p.failed && (p.isMoving || (!p.completed && p.downloadId != null) || (p.completed && !p.moved)))
  if (active) return DownloadState.DOWNLOADING
  if (all.some((p) => p.failed && p.authorizationHold)) return DownloadState.HELD
  // The current-access observation overrides a terminal failure's older permission flag.
  if (item?.folderAccessLost === true || (item?.folderAccessLost == null && all.some((p) => p.failed && p.permissionLost))) return DownloadState.FOLDER_ACCESS
  if (all.some((p) => p.failed && p.storageUnavailable)) return DownloadState.STORAGE_UNAVAILABLE
  if (all.some((p) => p.failed)) return DownloadState.FAILED
  if (all.some((p) => !p.completed && p.waitingForSpace)) return DownloadState.WAITING_FOR_STORAGE
  return WAITING_STATES[item?.waitingFor] || DownloadState.QUEUED
}

/** The label of a queued item's waitingFor reason, for places that show a single download (episode rows). */
export const WAITING_LABEL_KEYS = Object.freeze({
  sdCard: 'LabelDownloadWaitingForCard',
  network: 'LabelDownloadWaitingForNetwork',
  wifi: 'LabelDownloadWaitingForWifi',
  charging: 'LabelDownloadWaitingForCharging',
  background: 'LabelDownloadWaitingForApp'
})

/** Whether the state is a wait for a condition (shown with a clock, never as an error). */
export function isWaitingState(state) {
  return Object.values(WAITING_STATES).includes(state) || state === DownloadState.QUEUED || state === DownloadState.WAITING_FOR_STORAGE || state === DownloadState.WAITING_FOR_CARD
}

export function downloadActions(state) {
  switch (state) {
    case DownloadState.DOWNLOADING:
    case DownloadState.QUEUED:
    case DownloadState.WAITING_FOR_STORAGE:
    case DownloadState.WAITING_FOR_NETWORK:
    case DownloadState.WAITING_FOR_WIFI:
    case DownloadState.WAITING_FOR_CHARGING:
    case DownloadState.WAITING_FOR_BACKGROUND:
      return [DownloadAction.CANCEL]
    case DownloadState.FAILED:
    case DownloadState.STORAGE_UNAVAILABLE:
    // Retry checks the server again: while it is still another one the download stays held, nothing is deleted
    case DownloadState.HELD:
      return [DownloadAction.RETRY, DownloadAction.CLEAR]
    case DownloadState.FOLDER_ACCESS:
      return [DownloadAction.CHOOSE_FOLDER, DownloadAction.CLEAR]
    default:
      return []
  }
}

/** 0..1 by bytes across the item's files (finished files count in full). */
export function downloadProgress(item) {
  let total = 0
  let done = 0
  parts(item).forEach((p) => {
    total += p.fileSize || 0
    done += p.moved ? p.fileSize || 0 : Math.min(p.bytesDownloaded || 0, p.fileSize || 0)
  })
  return total > 0 ? Math.min(1, done / total) : 0
}

/** The device folder a folder-access failure needs re-selected, from the failed part. */
export function lostFolder(item) {
  if (item?.folderAccessLost === true) return item.localFolder || null
  const part = parts(item).find((p) => p.failed && p.permissionLost)
  return part ? { id: part.localFolderId, name: part.localFolderName } : null
}
