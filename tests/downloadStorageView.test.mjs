import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { DOWNLOAD_STORAGE_VIEW_KEYS, storageStatusView } from '../utils/downloadStorageView.js'

const strings = JSON.parse(await readFile(new URL('../strings/en-us.json', import.meta.url), 'utf8'))
const bytesPretty = (b) => +(b / 1e9).toFixed(1) + ' GB'
const sd = { uuid: '79EC-9B33', mounted: true, freeBytes: 103e9 }
const idle = { total: 0, done: 0, bytesTotal: 0, bytesDone: 0, waiting: {}, running: false, lastError: null }
const view = (extra = {}, move = {}) => storageStatusView({ downloadStorage: 'internal', internalFreeBytes: 5e9, sd: null, move: { ...idle, ...move }, ...extra }, strings, bytesPretty)

test('mounted SD card is described and selectable for either setting', () => {
  for (const downloadStorage of ['internal', 'sd:79EC-9B33']) {
    const v = view({ downloadStorage, sd })
    assert.equal(v.lines[0], 'SD card 79EC-9B33 · 103 GB free', downloadStorage)
    assert.equal(v.sdSelectable, true, downloadStorage)
  }
})

test('missing or unmounted SD card is not selectable', () => {
  assert.equal(view({ sd: null }).sdSelectable, false)
  const unmounted = view({ sd: { uuid: '79EC-9B33', mounted: false, freeBytes: 0 } })
  assert.equal(unmounted.sdSelectable, false)
  assert.ok(!unmounted.lines.some((l) => l.startsWith('SD card ')), 'no volume line for an unmounted card')
})

test('unavailable configured SD card is reported', () => {
  const v = view({ downloadStorage: 'sd:79EC-9B33', sd: { uuid: '79EC-9B33', mounted: false, freeBytes: 0 } })
  assert.equal(v.lines[0], 'SD card not available')
  assert.equal(v.sdSelectable, false)
  assert.ok(!v.lines.some((l) => l.startsWith('SD card 79EC')), 'no volume line')
  const internal = view({ downloadStorage: 'internal', sd: { uuid: '79EC-9B33', mounted: false, freeBytes: 0 } })
  assert.ok(!internal.lines.includes('SD card not available'), 'not with internal storage set')
})

test('a different inserted card still reports the configured one as unavailable', () => {
  const v = view({ downloadStorage: 'sd:79EC-9B33', sd: { uuid: '1234-ABCD', mounted: true, freeBytes: 1e9 } })
  assert.equal(v.lines[0], 'SD card not available')
  assert.ok(v.lines.some((l) => l.startsWith('SD card 1234-ABCD')), 'the other card is shown')
  assert.equal(v.sdSelectable, true)
  const same = view({ downloadStorage: 'sd:79EC-9B33', sd: { uuid: '79EC-9B33', mounted: true, freeBytes: 1e9 } })
  assert.ok(!same.lines.includes('SD card not available'), 'not when the configured card is inserted')
})

test('running move shows the current item and bytes', () => {
  assert.deepEqual(view({}, { running: true, done: 2, total: 7, bytesDone: 1.2e9, bytesTotal: 2.9e9 }).lines, ['Moving 3 of 7 items · 1.2 GB / 2.9 GB'])
  assert.deepEqual(view({}, { running: true, done: 7, total: 7, bytesDone: 2.9e9, bytesTotal: 2.9e9 }).lines, ['Moving 7 of 7 items · 2.9 GB / 2.9 GB'], 'never past the total')
  assert.deepEqual(view({}, { running: true, done: 0, total: 1, bytesDone: 0.3e9, bytesTotal: 0.5e9 }).lines, ['Moving 1 of 1 item · 0.3 GB / 0.5 GB'])
  // Not running: no progress line; what is left is said once (a pass continues while the app is open)
  assert.deepEqual(view({}, { running: false, done: 2, total: 7, bytesDone: 1.2e9, bytesTotal: 2.9e9 }).lines, ['7 items still to move: moving continues while the app is open'])
  assert.deepEqual(view({}, { running: false, total: 1 }).lines, ['1 item still to move: moving continues while the app is open'])
})

test('interrupted moves, kept copies and a completed move are reported', () => {
  assert.deepEqual(view({ recoveryPending: 1 }).lines, ['1 interrupted move is finished or undone while the app is open'])
  assert.deepEqual(view({ recoveryPending: 2 }).lines, ['2 interrupted moves are finished or undone while the app is open'])
  assert.deepEqual(view({ needsAttention: 1 }).lines, ['1 moved item kept both copies after a failed check'])
  assert.deepEqual(view({ lastPassMoved: 3 }).lines, ['Moved 3 items'])
  assert.deepEqual(view({ lastPassMoved: 1 }).lines, ['Moved 1 item'])
  // never "moved" while anything is still running, left, waiting or kept for a later check
  assert.deepEqual(view({ lastPassMoved: 3, cleanupPending: 3 }).lines, ['Old copies of 3 moved items are removed after the device restarts'])
  assert.deepEqual(view({ lastPassMoved: 3 }, { waiting: { PLAYING: 1 } }).lines, ['1 item waiting: currently playing'])
  assert.deepEqual(view({ lastPassMoved: 3 }, { total: 2 }).lines, ['2 items still to move: moving continues while the app is open'])
})

test('waiting reasons use singular and plural lines', () => {
  assert.deepEqual(view({}, { waiting: { PLAYING: 1 } }).lines, ['1 item waiting: currently playing'])
  assert.deepEqual(view({}, { waiting: { PLAYING: 3 } }).lines, ['3 items waiting: currently playing'])
  assert.deepEqual(view({}, { waiting: { UNAVAILABLE: 2 } }).lines, ['2 items on unavailable SD card'])
  assert.deepEqual(view({}, { waiting: { UNAVAILABLE: 1 } }).lines, ['1 item on unavailable SD card'])
  assert.deepEqual(view({}, { waiting: { DOWNLOADING: 1 } }).lines, ['1 item waiting: download in progress'])
  assert.deepEqual(view({}, { waiting: { DOWNLOADING: 4 } }).lines, ['4 items waiting: downloads in progress'])
  assert.deepEqual(view({}, { waiting: { NO_SPACE: 1 } }).lines, ['1 item waiting: not enough space'])
  assert.deepEqual(view({}, { waiting: { NO_SPACE: 5 } }).lines, ['5 items waiting: not enough space'])
  assert.deepEqual(view({}, { waiting: { RETRY: 1 } }).lines, ['1 item waiting: will try again later'])
  assert.deepEqual(view({}, { waiting: { RETRY: 6 } }).lines, ['6 items waiting: will try again later'])
})

test('lines keep a fixed order', () => {
  const v = view({ sd }, { running: true, done: 0, total: 2, bytesDone: 0, bytesTotal: 2e9, waiting: { RETRY: 1, NO_SPACE: 2, UNAVAILABLE: 1, DOWNLOADING: 2, PLAYING: 1 }, lastError: 'disk I/O' })
  assert.deepEqual(v.lines, [
    'SD card 79EC-9B33 · 103 GB free',
    'Moving 1 of 2 items · 0 GB / 2 GB',
    '1 item waiting: currently playing',
    '2 items waiting: downloads in progress',
    '1 item on unavailable SD card',
    '2 items waiting: not enough space',
    '1 item waiting: will try again later',
    "Couldn't move downloads: disk I/O"
  ])
})

test('last move error is shown', () => {
  assert.deepEqual(view({}, { lastError: 'disk I/O' }).lines, ["Couldn't move downloads: disk I/O"])
})

test('nothing to say without an SD card, move or status', () => {
  assert.deepEqual(view(), { lines: [], sdSelectable: false })
  assert.deepEqual(storageStatusView({ downloadStorage: 'internal', internalFreeBytes: 1, sd: null }, strings, bytesPretty), { lines: [], sdSelectable: false })
  assert.deepEqual(storageStatusView(null, strings, bytesPretty), { lines: [], sdSelectable: false })
  assert.deepEqual(storageStatusView(undefined, strings, bytesPretty), { lines: [], sdSelectable: false })
})

test('every string the view uses exists', () => {
  assert.equal(DOWNLOAD_STORAGE_VIEW_KEYS.length, 27)
  for (const key of DOWNLOAD_STORAGE_VIEW_KEYS) assert.equal(typeof strings[key], 'string', key)
})
