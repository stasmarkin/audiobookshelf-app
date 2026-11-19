import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
// Load the Nuxt ES module without changing this project's CommonJS package type.
const source = await readFile(new URL('../utils/mediaHistory.js', import.meta.url), 'utf8')
const { buildHistoryRows, formatListenDuration, pickPlayTarget, acceptsHistoryUpdate } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

const MIN = 60000
let seq = 0
const ev = (name, timestamp, extra = {}) => ({ name, timestamp, id: `id-${++seq}`, description: '', currentTime: 1, ...extra })
const rows = (groups) => groups.flatMap((g) => g.rows)
const base = new Date(2026, 10, 19, 22, 0, 0).getTime()

test('compact view keeps playback transitions only', () => {
  const events = [ev('Play', base), ev('Save', base + 1), ev('Seek', base + 2), ev('Sync', base + 3), ev('Pause', base + 4), ev('Stop', base + 5), ev('Finished', base + 6)]
  assert.deepEqual(rows(buildHistoryRows(events)).map((r) => r.evt.name), ['Finished', 'Stop', 'Pause', 'Play'])
})

test('full view keeps every event and collapses runs of Save and Sync', () => {
  const events = [ev('Play', base), ev('Save', base + 1), ev('Save', base + 2), ev('Save', base + 3), ev('Sync', base + 4), ev('Sync', base + 5), ev('Pause', base + 6)]
  const shown = rows(buildHistoryRows(events, { showAll: true }))
  assert.deepEqual(shown.map((r) => r.evt.name), ['Pause', 'Sync', 'Save', 'Play'])
  assert.equal(shown[1].num, 1)
  assert.equal(shown[2].num, 2)
})

test('duration pairs Play with the next closing event in write order, skipping service events', () => {
  const events = [ev('Play', base), ev('Save', base + MIN), ev('Seek', base + 2 * MIN), ev('Pause', base + 30 * MIN)]
  const play = rows(buildHistoryRows(events)).find((r) => r.evt.name === 'Play')
  assert.equal(play.durationMs, 30 * MIN)
})

test('pairs follow write order even when display order differs', () => {
  const events = [ev('Play', 100), ev('Pause', 200), ev('Play', 150), ev('Pause', 300)]
  const shown = rows(buildHistoryRows(events)).filter((r) => r.evt.name === 'Play')
  assert.deepEqual(shown.map((r) => [r.evt.timestamp, r.durationMs]), [[150, 150], [100, 100]])
})

test('clock moved back between Play and Pause gives no duration', () => {
  const shown = rows(buildHistoryRows([ev('Play', base + 10 * MIN), ev('Pause', base + 5 * MIN)]))
  assert.equal(shown.find((r) => r.evt.name === 'Play').durationMs, null)
})

test('equal timestamps give no duration and show the later write first', () => {
  const play = ev('Play', base)
  const pause = ev('Pause', base)
  const shown = rows(buildHistoryRows([play, pause]))
  assert.deepEqual(shown.map((r) => r.evt.name), ['Pause', 'Play'])
  assert.equal(shown[1].durationMs, null)
})

test('Play followed by Play has no duration; the last open Play is now playing', () => {
  const shown = rows(buildHistoryRows([ev('Play', base), ev('Play', base + MIN)], { nowPlaying: true }))
  assert.deepEqual(shown.map((r) => [r.durationMs, r.isNow]), [[null, true], [null, false]])
  assert.equal(rows(buildHistoryRows([ev('Play', base)], { nowPlaying: false }))[0].isNow, false)
})

test('events written before ids existed get no duration', () => {
  const shown = rows(buildHistoryRows([ev('Play', base, { id: null }), ev('Pause', base + MIN)]))
  assert.equal(shown.find((r) => r.evt.name === 'Play').durationMs, null)
})

test('a sleep timer pause is marked', () => {
  const shown = rows(buildHistoryRows([ev('Pause', base, { description: 'sleep_timer' }), ev('Pause', base + 1)]))
  assert.deepEqual(shown.map((r) => r.isSleepTimer), [false, true])
})

test('input events are not mutated', () => {
  const events = [ev('Pause', base + 5), ev('Play', base)]
  const copy = JSON.parse(JSON.stringify(events))
  buildHistoryRows(events, { showAll: true })
  assert.deepEqual(events, copy)
})

test('rows are grouped by local day', () => {
  const nextDay = base + 3 * 60 * MIN
  const groups = buildHistoryRows([ev('Play', base), ev('Pause', nextDay)])
  assert.equal(groups.length, 2)
  assert.ok(groups[0].dayStart > groups[1].dayStart)
})

test('formatListenDuration', () => {
  assert.equal(formatListenDuration(30000), '<1 min')
  assert.equal(formatListenDuration(60000), '1 min')
  assert.equal(formatListenDuration(3900000), '1 h 05 min')
})

const server = (extra = {}) => ({ id: 'li_1', libraryItemId: 'li_1', episodeId: null, isLocal: false, serverAddress: 'https://abs', ...extra })
const copy = (extra = {}) => ({ id: 'local_1', libraryItemId: 'li_1', serverAddress: 'http://lan', isInvalid: false, ...extra })

test('pickPlayTarget: a local-only book plays its local id', () => {
  const history = { id: 'local_9', libraryItemId: 'local_9', episodeId: null, isLocal: true, serverAddress: null }
  assert.deepEqual(pickPlayTarget({ history, localCopy: null, isCasting: false, currentServerAddress: null }), { libraryItemId: 'local_9', episodeId: null, serverLibraryItemId: null })
})

test('pickPlayTarget: a valid downloaded copy plays locally with its server id', () => {
  assert.deepEqual(pickPlayTarget({ history: server(), localCopy: copy(), isCasting: false, currentServerAddress: 'https://abs' }), { libraryItemId: 'local_1', episodeId: null, serverLibraryItemId: 'li_1' })
})

test('pickPlayTarget: an invalid copy falls back to the matching server only', () => {
  const invalid = copy({ isInvalid: true })
  assert.equal(pickPlayTarget({ history: server(), localCopy: invalid, isCasting: false, currentServerAddress: 'https://abs' }).libraryItemId, 'li_1')
  assert.equal(pickPlayTarget({ history: server(), localCopy: invalid, isCasting: false, currentServerAddress: 'https://other' }), null)
  assert.equal(pickPlayTarget({ history: server(), localCopy: invalid, isCasting: false, currentServerAddress: null }), null)
})

test('pickPlayTarget: casting plays the server item when the server matches, else the local copy', () => {
  assert.equal(pickPlayTarget({ history: server(), localCopy: copy(), isCasting: true, currentServerAddress: 'https://abs' }).libraryItemId, 'li_1')
  assert.equal(pickPlayTarget({ history: server(), localCopy: copy(), isCasting: true, currentServerAddress: 'https://other' }).libraryItemId, 'local_1')
})

test('pickPlayTarget: the journal address wins over the copy address', () => {
  // Downloaded over LAN, later played from the server over HTTPS
  assert.equal(pickPlayTarget({ history: server({ serverAddress: 'https://abs' }), localCopy: copy({ serverAddress: 'http://lan' }), isCasting: true, currentServerAddress: 'https://abs' }).libraryItemId, 'li_1')
})

test('pickPlayTarget: without a journal address the copy address is used', () => {
  const target = pickPlayTarget({ history: server({ serverAddress: null }), localCopy: copy({ serverAddress: 'https://abs', isInvalid: true }), isCasting: false, currentServerAddress: 'https://abs' })
  assert.equal(target.libraryItemId, 'li_1')
})

test('pickPlayTarget: the same server over another address matches by user id', () => {
  const history = server({ serverAddress: 'http://192.168.1.10:13378', serverUserId: 'user-1' })
  assert.equal(pickPlayTarget({ history, localCopy: null, isCasting: false, currentServerAddress: 'https://abs.example', currentUserId: 'user-1' }).libraryItemId, 'li_1')
  assert.equal(pickPlayTarget({ history, localCopy: null, isCasting: false, currentServerAddress: 'https://abs.example', currentUserId: 'user-2' }), null)
})

test('pickPlayTarget: addresses compare without trailing slash or host case', () => {
  assert.equal(pickPlayTarget({ history: server({ serverAddress: 'https://ABS/' }), localCopy: null, isCasting: false, currentServerAddress: 'https://abs' }).libraryItemId, 'li_1')
})

test('pickPlayTarget: two servers on one host under different paths do not match', () => {
  assert.equal(pickPlayTarget({ history: server({ serverAddress: 'https://host/abs-a' }), localCopy: null, isCasting: false, currentServerAddress: 'https://host/abs-b' }), null)
})

test('pickPlayTarget: nothing local and no matching server gives null', () => {
  assert.equal(pickPlayTarget({ history: server(), localCopy: null, isCasting: false, currentServerAddress: 'https://other' }), null)
})

test('acceptsHistoryUpdate matches the open book only', () => {
  assert.equal(acceptsHistoryUpdate('li_1', { id: 'li_1' }), true)
  assert.equal(acceptsHistoryUpdate('li_1', { id: 'li_2' }), false)
  assert.equal(acceptsHistoryUpdate('li_1', null), false)
})
