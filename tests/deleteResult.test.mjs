import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { deleteFailureMessage } from '../utils/deleteResult.js'

const strings = JSON.parse(await readFile(new URL('../strings/en-us.json', import.meta.url), 'utf8'))

// Audit F5: every native reason (FolderFileDeletion.Detail.reason, plus 'shared') has its own message
const REASONS = {
  folderAccess: 'MessageDeleteFolderAccessLost',
  folderMissing: 'MessageDeleteFolderMissing',
  storageUnavailable: 'LabelDownloadStorageUnavailable',
  unsupported: 'MessageDeleteUnsupported',
  notAFile: 'MessageDeleteNotAFile',
  unverified: 'MessageDeleteUnverified',
  deleteFailed: 'MessageDeleteFailed',
  partial: 'MessageDeletePartial',
  partialFolderAccess: 'MessageDeletePartialAccessLost',
  partialFolderMissing: 'MessageDeletePartialFolderMissing',
  shared: 'MessageDeleteShared'
}

test('every native delete reason has its own existing English message', () => {
  for (const [reason, key] of Object.entries(REASONS)) {
    assert.ok(strings[key], `${key} exists`)
    assert.equal(deleteFailureMessage({ success: false, reason }, strings), strings[key], reason)
  }
  assert.equal(new Set(Object.values(REASONS).map((k) => strings[k])).size, Object.keys(REASONS).length, 'messages are distinct')
})

test('a lost folder grant is never described as a missing SD card', () => {
  const message = deleteFailureMessage({ success: false, reason: 'folderAccess' }, strings)
  assert.doesNotMatch(message, /SD card/i)
  assert.match(message, /access/i)
})

test('an unknown or missing reason is a generic failure, never success wording', () => {
  for (const res of [{ success: false }, { success: false, reason: 'somethingNew' }, null, undefined]) {
    assert.equal(deleteFailureMessage(res, strings), strings.MessageDeleteFailed)
  }
  assert.equal(deleteFailureMessage({ reason: 'partial' }, {}), 'Failed to delete')
})

test('every delete call site reports the native reason through the shared mapping', async () => {
  const sites = {
    'pages/localMedia/item/_id.vue': 3, // item delete + two track deletes
    'components/modals/ItemMoreMenuModal.vue': 2, // item delete + episode delete
    'pages/item/_id/_episode/index.vue': 1 // episode delete
  }
  for (const [file, count] of Object.entries(sites)) {
    const src = await readFile(new URL(`../${file}`, import.meta.url), 'utf8')
    assert.match(src, /import \{ deleteFailureMessage \} from '@\/utils\/deleteResult'/, file)
    assert.equal((src.match(/deleteFailureMessage\(res, this\.\$strings\)/g) || []).length, count, file)
    assert.doesNotMatch(src, /reason === 'storageUnavailable'/, `${file}: reasons are mapped in one place`)
    assert.doesNotMatch(src, /toast\.error\('Failed to delete'\)/, `${file}: no reason is dropped`)
  }
})
