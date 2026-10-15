// Streamed-progress conflict on refocus (v0.16.0 release blocker): this phone's own server write (an echo) is told apart
// from another device's change by what the phone wrote and had acknowledged (ServerProgressAck.kt), not by how close the
// server's stamp is to this phone's last sync. A paused native move here is kept; another device's newer position is
// applied however soon after this phone's last save it came, and the next save continues from it.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const live = await read('../utils/livePosition.js')
const { refocusServerAction, refocusDisplayTime, serverProgressOrigin, readRefocusHandled, writeRefocusHandled, REFOCUS_HANDLED_KEY } = await import(`data:text/javascript;base64,${Buffer.from(live).toString('base64')}`)

// A small model of the pieces involved: the server's progress record (stamped by the server's clock), the native player
// and its progress syncer (MediaProgressSyncer + ServerProgressAck), and the UI's refocus handler with its memory
function world({ start = 1269.279, serverSkewMs = 0 } = {}) {
  let phoneClock = 1_791_690_000_000
  const serverNow = () => phoneClock + serverSkewMs
  const server = { currentTime: start, lastUpdate: serverNow() - 3_600_000 }
  const session = { id: 's1' }
  const native = { currentTime: start, isPlaying: false, ack: { sessionId: 's1', ackedTime: start, ackedAt: null, pendingTime: null } }
  let handled = null
  const seeks = []
  const w = {
    server,
    native,
    seeks,
    wait: (ms) => (phoneClock += ms),
    state: () => ({ playbackSession: { id: 's1', updatedAt: 1_791_000_000_000 }, currentTime: native.currentTime, isPlaying: native.isPlaying, serverProgressAck: native.ack }),
    // MediaProgressSyncer.sync, server branch: pending when sent; acknowledged when the server answered
    sync({ reaches = true, answered = reaches } = {}) {
      native.ack = { ...native.ack, pendingTime: native.currentTime }
      if (reaches) Object.assign(server, { currentTime: native.currentTime, lastUpdate: serverNow() })
      if (answered) native.ack = { ...native.ack, ackedTime: native.currentTime, ackedAt: phoneClock, pendingTime: native.ack.pendingTime === native.currentTime ? null : native.ack.pendingTime }
    },
    play(seconds) {
      native.currentTime += seconds
      w.wait(seconds * 1000)
      w.sync() // the pause sync
    },
    pausedSeek: (t) => (native.currentTime = t), // notification, widget or headset: no sync while paused
    remote(t) {
      // Another device (in the S22 test: a server-side write through the API)
      Object.assign(server, { currentTime: t, lastUpdate: serverNow() })
    },
    // AudioPlayerContainer.deviceFocused for a paused streamed book
    refocus() {
      const d = refocusServerAction(session, w.state(), { ...server }, native.currentTime, handled)
      if (d.handled) handled = d.handled
      if (d.action === 'seek') {
        seeks.push(d.time)
        native.currentTime = d.time
      }
      return d
    }
  }
  return w
}

test('own-write echo: the server holding this phone’s last save changes nothing, at any distance in time', () => {
  for (const after of [0, 300, 3_000, 9_999, 12_000, 600_000]) {
    const w = world()
    w.play(9)
    w.wait(after)
    const d = w.refocus()
    assert.deepEqual([d.action, d.origin, d.time, w.seeks.length], ['display', 'own', 1278.279, 0], `${after} ms after the save`)
  }
  // A server clock far off from the phone's does not matter either
  const skewed = world({ serverSkewMs: -45_000 })
  skewed.play(9)
  assert.equal(skewed.refocus().origin, 'own')
})

test('paused native seek: kept on refocus whatever the age of the last save, then saved by the next sync', () => {
  for (const after of [1_000, 5_000, 12_000, 120_000]) {
    const w = world()
    w.play(9)
    w.wait(after)
    w.pausedSeek(1569.279)
    const d = w.refocus()
    assert.deepEqual([d.action, d.origin, d.time, w.seeks.length], ['display', 'own', 1569.279, 0], `${after} ms after the save`)
    w.play(4)
    assert.equal(w.server.currentTime, 1573.279, 'the next save writes the moved position')
  }
  // Before this phone ever saved: the session's start position is what the server holds
  const fresh = world()
  fresh.pausedSeek(1569.279)
  assert.deepEqual([fresh.refocus().action, fresh.native.currentTime], ['display', 1569.279])
})

test('remote update inside the former 10 s window: applied on refocus, and the next save continues from it', () => {
  for (const after of [0, 300, 3_000, 9_999]) {
    const w = world()
    w.play(9)
    w.wait(after)
    w.remote(2184)
    const d = w.refocus()
    assert.deepEqual([d.action, d.origin, d.time], ['seek', 'remote', 2184], `${after} ms after this phone's save`)
    assert.deepEqual([w.native.currentTime, w.native.isPlaying], [2184, false], 'still paused, at the remote position')
    w.play(17)
    assert.equal(w.server.currentTime, 2201, 'final server progress continues from the remote position (not 1278 + 17)')
  }
})

test('remote update outside the former window: applied as before; within 1 s of the native position no seek', () => {
  const w = world()
  w.play(9)
  w.wait(12_000)
  w.remote(1878)
  assert.deepEqual([w.refocus().action, w.native.currentTime], ['seek', 1878])
  w.play(5)
  assert.equal(w.server.currentTime, 1883)
  const near = world()
  near.play(9)
  near.remote(1278.9)
  assert.deepEqual(near.refocus(), { action: 'display', time: 1278.279, origin: 'own', handled: { sessionId: 's1', lastUpdate: near.server.lastUpdate } })
  const close = world()
  close.play(9)
  close.pausedSeek(2183.5)
  close.remote(2184)
  assert.deepEqual([close.refocus().action, close.seeks.length], ['display', 0], 'remote, but already there')
})

test('a remote update while this phone had moved while paused: the remote position wins (it is newer than any save here)', () => {
  const w = world()
  w.play(9)
  w.pausedSeek(1569.279)
  w.wait(2_000)
  w.remote(2184)
  assert.deepEqual([w.refocus().action, w.native.currentTime], ['seek', 2184])
})

test('after a remote update was applied, a later paused move here is not undone by it again', () => {
  const w = world()
  w.play(9)
  w.remote(2184)
  w.refocus()
  w.pausedSeek(2500)
  for (let i = 0; i < 3; i++) assert.deepEqual([w.refocus().action, w.refocus().origin, w.native.currentTime], ['display', 'handled', 2500])
  w.play(4)
  assert.equal(w.server.currentTime, 2504)
  w.wait(2_000)
  w.remote(3000) // and the next remote change is seen again
  assert.deepEqual([w.refocus().action, w.native.currentTime], ['seek', 3000])
})

test('sync in flight or unanswered: the position this phone sent counts as its own; a failed one keeps the last acknowledged', () => {
  const inFlight = world()
  inFlight.play(9)
  inFlight.native.currentTime = 1290.5
  inFlight.sync({ reaches: true, answered: false })
  inFlight.pausedSeek(1600)
  assert.deepEqual([inFlight.refocus().origin, inFlight.native.currentTime], ['own', 1600])
  const offline = world()
  offline.play(9)
  offline.native.currentTime = 1290.5
  offline.sync({ reaches: false })
  offline.pausedSeek(1600)
  assert.deepEqual([offline.refocus().origin, offline.native.currentTime], ['own', 1600])
})

test('without the native acknowledgement (another native side) the server is authoritative, as upstream', () => {
  const session = { id: 's1' }
  const state = { playbackSession: { id: 's1', updatedAt: 1 }, currentTime: 1569.279, isPlaying: false }
  assert.equal(serverProgressOrigin(state, { currentTime: 1269.279, lastUpdate: 2 }), 'remote')
  assert.equal(refocusServerAction(session, state, { currentTime: 1269.279, lastUpdate: 2 }, 1569.28).action, 'seek')
  // An acknowledgement for another session is not this one's
  const other = { ...state, serverProgressAck: { sessionId: 's0', ackedTime: 1269.279, ackedAt: 1, pendingTime: null } }
  assert.equal(serverProgressOrigin(other, { currentTime: 1269.279, lastUpdate: 2 }), 'remote')
  // A handled record of another session does not apply either
  assert.equal(serverProgressOrigin(state, { currentTime: 2184, lastUpdate: 5 }, { sessionId: 's0', lastUpdate: 9 }), 'remote')
})

test('no matching native session, no server progress, and the downloaded-book rule: unchanged', () => {
  const session = { id: 's1' }
  assert.deepEqual(refocusServerAction(session, { playbackSession: { id: 'other' }, currentTime: 5 }, { currentTime: 100, lastUpdate: 1 }, 50), { action: 'seek', time: 100 })
  assert.deepEqual(refocusServerAction(session, null, { currentTime: 100.5, lastUpdate: 1 }, 100), { action: 'none' })
  assert.deepEqual(refocusServerAction(session, { playbackSession: { id: 's1' }, currentTime: 1 }, null, 1), { action: 'none' })
  assert.deepEqual(refocusDisplayTime(session, { playbackSession: { id: 's1' }, currentTime: 7107.976 }, 6107.556), { time: 7107.976, source: 'live' })
  assert.doesNotMatch(live, /REMOTE_UPDATE_MARGIN|lastSyncedAt/, 'no timestamp-proximity rule left')
})

test('deviceFocused passes and keeps the handled record, also across a recreated UI; seeks only for a remote update', async () => {
  const source = await read('../components/app/AudioPlayerContainer.vue')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { AbsAudioPlayer: {}, AbsLogger: { info: async () => {} }, Dialog: {}, CellularPermissionHelpers: {}, nativeQueueItems() {}, resolvePlaybackQueue() {}, resolveQueueSourceAppendItems() {}, sessionToQueueItem() {}, refocusDisplayTime, refocusServerAction, readRefocusHandled, writeRefocusHandled, console: { log() {}, error() {} } }
  const stored = new Map()
  sandbox.localStorage = { getItem: (k) => (stored.has(k) ? stored.get(k) : null), setItem: (k, v) => stored.set(k, String(v)) }
  vm.runInNewContext(script, sandbox)
  const { methods } = sandbox.component
  assert.equal(sandbox.component.data.call({}).refocusServerHandled, null)
  const ack = { sessionId: 's1', ackedTime: 1278.232, ackedAt: 10, pendingTime: null }
  const ctx = { currentPlaybackSession: { id: 's1' }, currentTime: 1569.28, refocusServerHandled: null, onPlaybackEnded: async () => {} }
  const seeks = []
  ctx.$refs = {
    audioPlayer: {
      isPlaying: false,
      isLocalPlayMethod: false,
      currentTime: 1569.28,
      seek: (t) => seeks.push(t),
      timeupdate() {
        ctx.currentTime = this.currentTime
      }
    }
  }
  ctx.getNativePlaybackState = methods.getNativePlaybackState.bind(ctx)
  let nativeTime = 1569.279
  sandbox.AbsAudioPlayer.getCurrentPlaybackState = async () => ({ playbackSession: { id: 's1' }, currentTime: nativeTime, isPlaying: false, serverProgressAck: ack })
  const focus = async (server) => {
    ctx.getServerMediaProgressForCurrentSession = async () => server
    await methods.deviceFocused.call(ctx, true)
  }
  await focus({ currentTime: 1278.232, lastUpdate: 1000 }) // own echo, paused move kept
  assert.deepEqual([seeks, ctx.currentTime, ctx.refocusServerHandled], [[], 1569.279, { sessionId: 's1', lastUpdate: 1000 }])
  await focus({ currentTime: 2184, lastUpdate: 1003 }) // another device, 3 s later
  assert.deepEqual([seeks, ctx.refocusServerHandled], [[2184], { sessionId: 's1', lastUpdate: 1003 }])
  nativeTime = 2500 // moved here afterwards
  await focus({ currentTime: 2184, lastUpdate: 1003 })
  assert.deepEqual([seeks, ctx.currentTime], [[2184], 2500])
  // The UI is recreated while the native player keeps its session: the record comes back from storage
  assert.deepEqual(JSON.parse(stored.get(REFOCUS_HANDLED_KEY)), { sessionId: 's1', lastUpdate: 1003 })
  ctx.refocusServerHandled = null
  nativeTime = 2600
  await focus({ currentTime: 2184, lastUpdate: 1003 })
  assert.deepEqual([seeks, ctx.currentTime], [[2184], 2600])
})

test('Native wiring: the syncer records sent and acknowledged server syncs; the session start is seeded; the state reports it', async () => {
  const syncer = await read('../android/app/src/main/java/app/absplus/android/media/MediaProgressSyncer.kt')
  assert.match(syncer, /val sessionId = session\.id\n[\s\S]*?noteServerSync\(sessionId, currentTime, false\)\n\s+send\(sessionId, MediaProgressSyncData\(sent, session\.duration, currentTime\)\) \{ syncSuccess, errorMsg ->\n\s+if \(syncSuccess\) \{\n\s+synchronized\(ledger\) \{\n\s+ledger\.ackServerSync\(sent\)\n\s+saveUnsettled\(session, ledger\)\n\s+\}\n\s+noteServerSync\(sessionId, currentTime, true\)/)
  assert.match(syncer, /if \(playbackSession\.isLocal \|\| serverProgressAck\?\.sessionId == playbackSession\.id\) return\n\s+serverProgressAck = ServerProgressAck\.sessionStart\(playbackSession\.id, playbackSession\.currentTime\)/)
  const service = await read('../android/app/src/main/java/app/absplus/android/player/PlayerNotificationService.kt')
  assert.ok(service.indexOf('mediaProgressSyncer.noteServerSessionStart(playbackSession)') < service.indexOf('Prepare Player Session is finished, so restart it'), 'seeded before the finished-item reset')
  const plugin = await read('../android/app/src/main/java/app/absplus/android/plugins/AbsAudioPlayer.kt')
  assert.match(plugin, /pns\.mediaProgressSyncer\.serverProgressAck \}\.getOrNull\(\)\?\.takeIf \{ it\.sessionId == session\.id \}\?\.let \{\n\s+ret\.put\("serverProgressAck", JSObject\(jacksonMapper\.writeValueAsString\(it\)\)\)/)
})

test('handled record storage: validated on read, and unavailable storage changes nothing', () => {
  const box = (value) => ({ getItem: () => value, setItem() {} })
  assert.deepEqual(readRefocusHandled(box(JSON.stringify({ sessionId: 's1', lastUpdate: 5, extra: 1 }))), { sessionId: 's1', lastUpdate: 5 })
  for (const bad of [null, '', 'not json', '{"sessionId":1,"lastUpdate":5}', '{"sessionId":"s1"}', '{"sessionId":"s1","lastUpdate":"5"}']) assert.equal(readRefocusHandled(box(bad)), null, String(bad))
  assert.equal(readRefocusHandled(undefined), null)
  assert.equal(readRefocusHandled({ getItem() { throw new Error('SecurityError') } }), null)
  assert.doesNotThrow(() => writeRefocusHandled(undefined, { sessionId: 's1', lastUpdate: 5 }))
  assert.doesNotThrow(() => writeRefocusHandled({ setItem() { throw new Error('QuotaExceededError') } }, { sessionId: 's1', lastUpdate: 5 }))
})
