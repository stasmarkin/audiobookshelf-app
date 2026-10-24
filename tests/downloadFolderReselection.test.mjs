import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import * as states from '../utils/downloadState.js'

const folder = { id: 'original', contentUrl: 'content://provider/tree/original', name: 'Synthetic' }
const item = () => ({ id: 'download', mediaType: 'book', localFolder: { ...folder }, downloadItemParts: [{ failed: true, completed: false, moved: false, permissionLost: false, failureReason: 'Collision', localFolderId: folder.id, localFolderName: folder.name }] })
function component(selected) {
  const calls = []
  const script = fs.readFileSync(new URL('../pages/downloading.vue', import.meta.url), 'utf8').split('<script>')[1].split('</script>')[0]
    .split('\n').filter((s) => !s.startsWith('import ')).join('\n').replace('export default', 'globalThis.page =')
  const context = vm.createContext({ ...states, isAppStorageFolderId: () => false,
    AbsFileSystem: { selectFolder: async (args) => { calls.push(['picker', args]); return selected } },
    AbsDownloader: { retryDownload: async () => { calls.push(['retry']); return { errorCode: 'folderAccess' } } } })
  vm.runInContext(script, context)
  const page = { ...context.page.methods, busyId: null, $strings: {}, $getString: (key) => key,
    $toast: { error: (message) => calls.push(['toast', message]) }, refresh: async () => calls.push(['refresh']) }
  return { page, calls }
}

test('a collision-failed queue observes later grant loss and renewal without changing its failure or files', () => {
  const download = item()
  download.folderAccessLost = true
  assert.equal(states.downloadState(download), states.DownloadState.FOLDER_ACCESS)
  assert.deepEqual(states.lostFolder(download), folder)
  download.folderAccessLost = false
  download.downloadItemParts[0].permissionLost = true // older persisted reason
  assert.equal(states.downloadState(download), states.DownloadState.FAILED)
  assert.deepEqual(states.downloadActions(states.downloadState(download)), ['retry', 'clear'])
  assert.equal(download.downloadItemParts[0].failureReason, 'Collision')
})

test('ordinary network failure without storage-access loss offers Retry, not folder reselection', () => {
  const download = item()
  download.folderAccessLost = false
  download.downloadItemParts[0].failureReason = 'Network unavailable'
  assert.deepEqual(states.downloadActions(states.downloadState(download)), ['retry', 'clear'])
})

test('reselecting original tree refreshes the failed queue without automatically retrying', async () => {
  const { page, calls } = component({ ...folder })
  const download = item(); download.folderAccessLost = true
  const before = JSON.stringify(download)
  await page.chooseFolder(download)
  assert.deepEqual(calls.map((x) => x[0]), ['picker', 'refresh'])
  assert.equal(JSON.stringify(download), before)
})

test('picker cancellation returns without retrying or losing queue state', async () => {
  const { page, calls } = component({ error: 'Cancel', errorCode: 'canceled' })
  const download = item(); const before = JSON.stringify(download)
  await page.chooseFolder(download)
  assert.deepEqual(calls.map((x) => x[0]), ['picker'])
  assert.equal(JSON.stringify(download), before)
})

test('a different folder or provider cannot redirect the pending download', async () => {
  for (const selected of [{ ...folder, id: 'different' }, { ...folder, contentUrl: 'content://another-provider/tree/original' }]) {
    const { page, calls } = component(selected)
    const download = item(); const before = JSON.stringify(download)
    await page.chooseFolder(download)
    assert.deepEqual(calls.map((x) => x[0]), ['picker', 'toast'])
    assert.equal(JSON.stringify(download), before)
  }
})

test('Retry reporting lost access refreshes the screen to offer the existing picker', async () => {
  const { page, calls } = component(null)
  await page.retry(item())
  assert.deepEqual(calls.map((x) => x[0]), ['retry', 'refresh', 'toast'])
})
