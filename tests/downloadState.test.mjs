import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { downloadState, downloadActions, downloadProgress, lostFolder, isWaitingState, WAITING_LABEL_KEYS, DownloadState, DownloadAction } from '../utils/downloadState.js'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

// A part as the native downloader serializes it (DownloadItemPart): downloadId is set while transferring
const part = (over = {}) => ({ id: 'p', filename: '01.mp3', fileSize: 100, bytesDownloaded: 0, progress: 0, completed: false, moved: false, isMoving: false, failed: false, permissionLost: false, storageUnavailable: false, waitingForSpace: false, downloadId: null, localFolderId: 'internal-book', localFolderName: 'Internal App Storage', ...over })
const item = (...parts) => ({ id: 'li', itemTitle: 'Book', downloadItemParts: parts })

test('queued -> downloading -> finishing, with Cancel until the files are in place', () => {
  const queued = item(part(), part({ id: 'c', filename: 'cover-li.jpg' }))
  assert.equal(downloadState(queued), DownloadState.QUEUED)
  assert.deepEqual(downloadActions(DownloadState.QUEUED), [DownloadAction.CANCEL])

  const downloading = item(part({ downloadId: -1, bytesDownloaded: 40, progress: 40 }), part({ id: 'c' }))
  assert.equal(downloadState(downloading), DownloadState.DOWNLOADING)
  assert.deepEqual(downloadActions(DownloadState.DOWNLOADING), [DownloadAction.CANCEL])
  // A finished transfer still being saved to its folder is still active
  assert.equal(downloadState(item(part({ completed: true }))), DownloadState.DOWNLOADING)
  assert.equal(downloadState(item(part({ completed: true, isMoving: true }))), DownloadState.DOWNLOADING)

  const finishing = item(part({ completed: true, moved: true }), part({ id: 'c', completed: true, moved: true }))
  assert.equal(downloadState(finishing), DownloadState.FINISHING)
  // A completed download offers nothing that could remove it
  assert.deepEqual(downloadActions(DownloadState.FINISHING), [])
})

test('a failed download offers Retry and Clear (never Resume)', () => {
  const failed = item(part({ completed: true, moved: true }), part({ id: 'b', filename: '02.mp3', failed: true, failureReason: 'Transfer failed after 5 retries' }))
  assert.equal(downloadState(failed), DownloadState.FAILED)
  assert.deepEqual(downloadActions(DownloadState.FAILED), [DownloadAction.RETRY, DownloadAction.CLEAR])
  assert.ok(!Object.values(DownloadAction).includes('resume'))
})

test('lost folder access asks for the folder instead of a blind Retry', () => {
  const lost = item(part({ failed: true, permissionLost: true, localFolderId: 'saf-abstest', localFolderName: 'ABSTEST' }), part({ id: 'c', filename: 'cover-li.jpg', failed: true, permissionLost: true, localFolderId: 'saf-abstest', localFolderName: 'ABSTEST' }))
  assert.equal(downloadState(lost), DownloadState.FOLDER_ACCESS)
  assert.deepEqual(downloadActions(DownloadState.FOLDER_ACCESS), [DownloadAction.CHOOSE_FOLDER, DownloadAction.CLEAR])
  assert.deepEqual(lostFolder(lost), { id: 'saf-abstest', name: 'ABSTEST' })
})

test('storageUnavailableState', () => {
  const missing = item(part({ failed: true, storageUnavailable: true, localFolderId: 'appsd-79EC-9B33-book', localFolderName: 'SD card' }))
  assert.equal(downloadState(missing), DownloadState.STORAGE_UNAVAILABLE)
  // An SD card can't be re-selected like a device folder: Retry once it is back
  assert.deepEqual(downloadActions(DownloadState.STORAGE_UNAVAILABLE), [DownloadAction.RETRY, DownloadAction.CLEAR])
})

test('audio and cover failures are handled alike, and active work wins over a failed sibling', () => {
  const coverFailed = item(part({ completed: true, moved: true }), part({ id: 'c', filename: 'cover-li.jpg', failed: true }))
  const audioFailed = item(part({ failed: true }), part({ id: 'c', filename: 'cover-li.jpg', completed: true, moved: true }))
  assert.equal(downloadState(coverFailed), DownloadState.FAILED)
  assert.equal(downloadState(audioFailed), DownloadState.FAILED)
  // Clear is not offered while another file is still transferring: Cancel stops it first
  assert.equal(downloadState(item(part({ failed: true }), part({ id: 'b', downloadId: -1 }))), DownloadState.DOWNLOADING)
})

test('waiting for storage is shown as such', () => {
  assert.equal(downloadState(item(part({ waitingForSpace: true }))), DownloadState.WAITING_FOR_STORAGE)
  assert.deepEqual(downloadActions(DownloadState.WAITING_FOR_STORAGE), [DownloadAction.CANCEL])
})

test('a queued download says what it waits for, and only Cancel is offered', () => {
  const withReason = (waitingFor, ...parts) => ({ ...item(...parts), waitingFor })
  assert.equal(downloadState(withReason('wifi', part())), DownloadState.WAITING_FOR_WIFI)
  assert.equal(downloadState(withReason('charging', part())), DownloadState.WAITING_FOR_CHARGING)
  assert.equal(downloadState(withReason('network', part())), DownloadState.WAITING_FOR_NETWORK)
  assert.equal(downloadState(withReason('background', part())), DownloadState.WAITING_FOR_BACKGROUND)
  assert.equal(downloadState(withReason(null, part())), DownloadState.QUEUED)
  for (const state of [DownloadState.WAITING_FOR_WIFI, DownloadState.WAITING_FOR_CHARGING, DownloadState.WAITING_FOR_NETWORK, DownloadState.WAITING_FOR_BACKGROUND]) {
    assert.deepEqual(downloadActions(state), [DownloadAction.CANCEL], state)
    assert.ok(isWaitingState(state), state)
  }
  // a reason never hides a transfer in progress, a failure or a lack of space
  assert.equal(downloadState(withReason('wifi', part({ downloadId: -1 }))), DownloadState.DOWNLOADING)
  assert.equal(downloadState(withReason('wifi', part({ failed: true }))), DownloadState.FAILED)
  assert.equal(downloadState(withReason('wifi', part({ waitingForSpace: true }))), DownloadState.WAITING_FOR_STORAGE)
})

test('finished files waiting for the SD card are not shown as finishing, and offer nothing that removes them', () => {
  const waiting = { ...item(part({ completed: true, moved: true })), waitingFor: 'sdCard' }
  assert.equal(downloadState(waiting), DownloadState.WAITING_FOR_CARD)
  assert.deepEqual(downloadActions(DownloadState.WAITING_FOR_CARD), [])
  assert.ok(isWaitingState(DownloadState.WAITING_FOR_CARD))
})

test('a download held for another server offers Retry and Clear, and is not a plain failure', () => {
  const held = item(part({ failed: true, authorizationHold: true, failureReason: 'Download authorization changed: reconnect the original server before retrying' }))
  assert.equal(downloadState(held), DownloadState.HELD)
  assert.deepEqual(downloadActions(DownloadState.HELD), [DownloadAction.RETRY, DownloadAction.CLEAR])
  assert.ok(!isWaitingState(DownloadState.HELD))
})

test('every waiting reason has a label', async () => {
  const strings = JSON.parse(await read('../strings/en-us.json'))
  for (const [reason, key] of Object.entries(WAITING_LABEL_KEYS)) assert.equal(typeof strings[key], 'string', reason)
  for (const key of ['LabelDownloadAuto', 'LabelDownloadHeld', 'MessageDownloadHeld']) assert.equal(typeof strings[key], 'string', key)
  const page = await read('../pages/downloading.vue')
  assert.ok(page.includes("if (result?.errorCode === 'held') return this.$toast.error(this.$strings.MessageDownloadHeld)"))
  assert.ok(page.includes('this.refreshTimer = setInterval(() => this.refresh(), REFRESH_MS)'))
  assert.ok(page.includes('clearInterval(this.refreshTimer)'))
  const row = await read('../components/tables/podcast/EpisodeRow.vue')
  assert.ok(row.includes('<span v-if="autoDownloaded" class="text-xs text-fg-muted">{{ $strings.LabelDownloadAuto }}</span>'))
  assert.ok(row.includes('<span v-if="downloadWaitingText" class="text-xs text-fg-muted">{{ downloadWaitingText }}</span>'))
})

test('progress counts finished files in full and partial bytes as they are', () => {
  assert.equal(downloadProgress(item(part({ completed: true, moved: true }), part({ id: 'b', bytesDownloaded: 50 }))), 0.75)
  assert.equal(downloadProgress(item()), 0)
})

test('Downloads screen and item page wire the controls to the native downloader', async () => {
  const page = await read('../pages/downloading.vue')
  assert.ok(page.includes('AbsDownloader.removeDownload({ downloadItemId: item.id, action })'))
  assert.ok(page.includes('AbsDownloader.retryDownload({ downloadItemId: item.id })'))
  assert.ok(page.includes('AbsFileSystem.selectFolder({ mediaType: item.mediaType })'))
  // The re-selected folder must be the one the download writes to
  assert.ok(page.includes("if (lost && folder.id !== lost.id) return this.$toast.error(this.$getString('MessageDownloadWrongFolder', [lost.name]))"))
  // State shown in words next to its icon, not by color alone
  assert.match(page, /<span class="text-fg">\{\{ stateText\(item\) \}\}<\/span>/)

  const indicator = await read('../components/widgets/DownloadProgressIndicator.vue')
  assert.ok(indicator.includes('await AbsDownloader.getDownloadQueue()'))
  assert.ok(indicator.includes("this.$store.commit(\n        'globals/setItemDownloads'"))

  const itemPage = await read('../pages/item/_id/index.vue')
  assert.ok(itemPage.includes("if (this.downloadItem) return this.$router.push('/downloading')"))
  assert.ok(itemPage.includes('<nuxt-link to="/downloading" class="underline text-sm">{{ $strings.ButtonOpenDownloads }}</nuxt-link>'))

  const strings = JSON.parse(await read('../strings/en-us.json'))
  for (const key of [
    'ButtonChooseFolder',
    'ButtonClear',
    'ButtonRetry',
    'ButtonOpenDownloads',
    'LabelDownloadDownloading',
    'LabelDownloadFailed',
    'LabelDownloadFinishing',
    'LabelDownloadFolderAccessLost',
    'LabelDownloadQueued',
    'LabelDownloadStorageUnavailable',
    'LabelDownloadWaitingForStorage',
    'MessageDownloadBusy',
    'MessageDownloadChooseFolder',
    'MessageDownloadFinishing',
    'MessageDownloadRetryRestarts',
    'MessageDownloadWrongFolder',
    'MessageNoDownloads'
  ]) {
    assert.ok(strings[key], key)
  }
  assert.doesNotMatch(JSON.stringify(strings), /"Resume[^"]*download/i)
})
