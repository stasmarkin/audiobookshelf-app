// Up Next restoration after process death, inactivity, an app update or a WebView recreation (v0.18.1 hotfix).
// The native player keeps and restores the queue (PlaybackRestoreStore, covered by PlaybackRestoreStoreTest.kt). These
// tests cover the native -> Vue handoff: the real AudioPlayerContainer methods, the real store mutations/getters and the
// real queueDisplayFromNative, with a fake Capacitor boundary and Preferences that persist across "UI instances".
// Every async step is a controlled promise; nothing depends on timing.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = async (path) => (await readFile(new URL(path, import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
const helperSource = await read('../utils/playbackQueue.js')
const helpers = await import(`data:text/javascript;base64,${Buffer.from(helperSource).toString('base64')}`)
const { nativeQueueItems, queueDisplayFromNative } = helpers

// Store module as Nuxt builds it: state factory, getters and mutations (imports are not needed by these)
const storeSource = (await read('../store/index.js')).replace(/^import .*$/gm, '').replace(/^export const (\w+) =/gm, 'globalThis.$1 =')
const storeSandbox = {}
vm.runInNewContext(storeSource, storeSandbox)

const containerSource = await read('../components/app/AudioPlayerContainer.vue')
const containerScript = containerSource
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '')
  .replace('export default', 'globalThis.component =')
const playerSource = await read('../components/app/AudioPlayer.vue')

const plain = (value) => JSON.parse(JSON.stringify(value))
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => ((resolve = res), (reject = rej)))
  return { promise, resolve, reject }
}

// A 13-book series queue like the user's: some downloaded (local copy), some streamed; current index 3
const entry = (id, downloaded) => ({ libraryItemId: id, episodeId: null, localLibraryItem: downloaded ? { id: `local_${id}` } : null, title: `Book ${id}` })
const series13 = Array.from({ length: 13 }, (_, i) => entry(`b${i}`, i <= 4))
const savedSeriesQueue = () => ({ sourceType: 'series', sourceId: 'hwfwm', items: series13, currentIndex: 3 })
const nativeQueue = (currentIndex = 3, items = series13) => ({ items: nativeQueueItems(items), currentIndex })
// The session native prepares for the current downloaded book (as onPlaybackSession delivers it)
const sessionFor = (item, id = 'session-1') => ({ id, libraryItemId: item.libraryItemId, episodeId: null, localLibraryItem: item.localLibraryItem, localEpisodeId: null })

// Preferences shared by every UI instance: it outlives a WebView recreation, process death and an APK update
function persistentPreferences() {
  const data = {}
  return {
    data,
    localStore: {
      setPlaybackQueue: async (queue) => {
        if (queue) data.playbackQueue = JSON.stringify(queue)
        else delete data.playbackQueue
      },
      getPlaybackQueue: async () => (data.playbackQueue ? JSON.parse(data.playbackQueue) : null)
    }
  }
}

// One UI instance (a fresh WebView): new store and container, same persisted Preferences
function uiInstance(prefs, native = {}) {
  const sandbox = { ...helpers, AbsAudioPlayer: native, AbsLogger: { info: async () => {} }, CellularPermissionHelpers: {}, console: { ...console, log: () => {} } }
  vm.runInNewContext(containerScript, sandbox)
  const state = storeSandbox.state()
  const store = {
    state,
    getters: {
      'user/getServerConnectionConfigId': 'server',
      'globals/getLocalMediaProgressById': () => null,
      'user/getUserMediaProgress': () => null,
      getIsMediaStreaming: () => false,
      get upcoming() {
        return storeSandbox.getters.getPlaybackQueueUpcomingItems(state)
      },
      get current() {
        return storeSandbox.getters.getPlaybackQueueCurrentItem(state)
      }
    },
    commit(name, payload) {
      storeSandbox.mutations[name].call({ $localStore: prefs.localStore }, state, payload)
    }
  }
  const ctx = { $platform: 'android', $store: store, $localStore: prefs.localStore, $diag: { debug: () => {} }, $toast: { error: (m) => (ctx.error = m), info: () => {} }, $refs: {}, $encode: (v) => v }
  // Library metadata and the network are not available: restoring the display must not need them
  ctx.$nativeHttp = { get: async () => assert.fail('the Up Next restore must not fetch library data') }
  ctx.$db = { getLocalLibraryItems: async () => assert.fail('the Up Next restore must not read the library') }
  Object.assign(ctx, sandbox.component.data())
  for (const [name, fn] of Object.entries(sandbox.component.methods)) ctx[name] = fn.bind(ctx)
  return ctx
}

// The user's session on Oct 11: Play from the series page set the 13-item queue at index 3 (display copy persisted)
function playedSeriesYesterday() {
  const prefs = persistentPreferences()
  const first = uiInstance(prefs)
  first.$store.commit('setPlaybackQueue', savedSeriesQueue())
  return prefs
}

// A native session arrives while the UI is open (AbsAudioPlayer onPlaybackSession -> AudioPlayer emits the event)
async function nativeSessionArrives(ui, session) {
  ui.$store.commit('setPlaybackSession', session)
  await ui.nativePlaybackSessionPrepared(session)
}

// --- queueDisplayFromNative ---

test('the display copy is used only when it is exactly the native queue, with a valid index on the session item', () => {
  const saved = savedSeriesQueue()
  assert.equal(queueDisplayFromNative(saved, nativeQueue(3), sessionFor(series13[3])).queue.currentIndex, 3)
  assert.equal(queueDisplayFromNative(saved, nativeQueue(5)).queue.currentIndex, 5, 'native index wins (it advanced in the background)')
  assert.equal(queueDisplayFromNative(saved, nativeQueue(3, series13.slice(0, 12))).reason, 'mismatch')
  assert.equal(queueDisplayFromNative(saved, nativeQueue(3, [series13[1], series13[0], ...series13.slice(2)])).reason, 'mismatch', 'order matters')
  // Native names a book by its server id whether or not it is downloaded (it plays the copy itself)
  const streamedInstead = series13.map((item, i) => (i === 2 ? { ...item, localLibraryItem: null } : item))
  assert.equal(queueDisplayFromNative(saved, nativeQueue(3, streamedInstead)).queue.currentIndex, 3)
  assert.equal(queueDisplayFromNative(saved, nativeQueue(13)).reason, 'invalid-index')
  assert.equal(queueDisplayFromNative(saved, nativeQueue(-1)).reason, 'invalid-index')
  assert.equal(queueDisplayFromNative(saved, nativeQueue(3), sessionFor(series13[4])).reason, 'session-mismatch')
  assert.equal(queueDisplayFromNative(null, nativeQueue(3)).reason, 'no-saved-display')
  assert.equal(queueDisplayFromNative(saved, { items: [], currentIndex: -1 }).reason, 'no-native-queue')
  assert.equal(queueDisplayFromNative(saved, null).reason, 'no-native-queue')
})

test('podcast episodes match by episode identity (local episode for downloads, server episode for streams)', () => {
  const items = [
    { libraryItemId: 'p', episodeId: 'e1', localLibraryItem: { id: 'local_p' }, localEpisode: { id: 'local_e1' } },
    { libraryItemId: 'p', episodeId: 'e2', localLibraryItem: null }
  ]
  const saved = { sourceType: 'podcast', sourceId: 'p', items, currentIndex: 0 }
  const native = { items: nativeQueueItems(items), currentIndex: 0 }
  assert.equal(queueDisplayFromNative(saved, native, { id: 's', libraryItemId: 'p', episodeId: 'e1', localLibraryItem: { id: 'local_p' }, localEpisodeId: 'local_e1' }).queue.currentIndex, 0)
  assert.equal(queueDisplayFromNative(saved, { ...native, currentIndex: 1 }, { id: 's', libraryItemId: 'p', episodeId: 'e2', localLibraryItem: null }).queue.currentIndex, 1)
  assert.equal(queueDisplayFromNative(saved, native, { id: 's', libraryItemId: 'p', episodeId: 'e2', localLibraryItem: null }).reason, 'session-mismatch')
})

test('queue restore display accepts server-id queue for a local session', () => {
  const saved = savedSeriesQueue()
  // series13[3] is downloaded: native holds server ids and plays the copy, so the session is local
  const localSession = { id: 's', libraryItemId: 'b3', episodeId: null, localLibraryItem: { id: 'local_b3', libraryItemId: 'b3' }, localEpisodeId: null }
  assert.equal(nativeQueue(3).items[3].libraryItemId, 'b3')
  assert.equal(queueDisplayFromNative(saved, nativeQueue(3), localSession).queue.currentIndex, 3)
  // A queue native saved before this version names the copies by their device ids; it is restored too
  const legacy = { items: series13.map((item) => ({ libraryItemId: item.localLibraryItem?.id || item.libraryItemId, episodeId: null })), currentIndex: 3 }
  assert.equal(legacy.items[3].libraryItemId, 'local_b3')
  assert.equal(queueDisplayFromNative(saved, legacy, localSession).queue.currentIndex, 3)
  // A copy downloaded through another library: native found it by ino for the same server
  const inoItem = { libraryItemId: 'serverA', episodeId: null, libraryItem: { ino: '42' }, localLibraryItem: null }
  const inoSaved = { sourceType: 'series', sourceId: 's', items: [inoItem], currentIndex: 0 }
  const inoNative = { items: nativeQueueItems([inoItem], 'server'), currentIndex: 0 }
  const inoSession = { id: 's2', libraryItemId: 'serverB', episodeId: null, localLibraryItem: { id: 'local_b', libraryItemId: 'serverB', ino: '42', serverConnectionConfigId: 'server' } }
  assert.equal(queueDisplayFromNative(inoSaved, inoNative, inoSession).queue.currentIndex, 0)
  const otherAccount = { ...inoSession, localLibraryItem: { ...inoSession.localLibraryItem, serverConnectionConfigId: 'other' } }
  assert.equal(queueDisplayFromNative(inoSaved, inoNative, otherAccount).reason, 'session-mismatch')
})

// --- the reported defect: UI open first, native restores later ---

test('process death + UI started first, then a widget/headset/notification Play restores natively: Up Next shows the queue', async () => {
  const prefs = playedSeriesYesterday()
  // Overnight the process died; today the app is opened (cold UI): no native session to reattach
  let nativeReads = 0
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => (nativeReads++, nativeQueue(3)) })
  await ui.nativePlaybackReattached(null)
  assert.equal(ui.$store.state.playbackQueue, null, 'nothing to show before playback is restored')
  // Widget/notification/headset Play: PlaybackRestore rebuilds the session and the 13-item queue, then notifies the UI
  await nativeSessionArrives(ui, sessionFor(series13[3]))
  assert.equal(nativeReads, 1)
  const q = ui.$store.state.playbackQueue
  assert.deepEqual(
    plain(q.items.map((i) => i.libraryItemId)),
    series13.map((i) => i.libraryItemId),
    'original order, no duplicates'
  )
  assert.equal(q.currentIndex, 3)
  assert.equal(q.sourceType, 'series')
  // What Up Next renders
  assert.equal(ui.$store.getters.current.libraryItemId, 'b3')
  assert.deepEqual(plain(ui.$store.getters.upcoming.map((i) => i.libraryItemId)), ['b4', 'b5', 'b6', 'b7', 'b8', 'b9', 'b10', 'b11', 'b12'])
})

test('application update: a new APK starts a new UI over the same Preferences; the first Play restores Up Next', async () => {
  const prefs = playedSeriesYesterday()
  // v0.17.0 -> v0.18.0 update: the old process is replaced, Preferences and the native restore store survive
  const updated = uiInstance(prefs, { getPlaylistQueue: async () => nativeQueue(3) })
  await updated.nativePlaybackReattached({ queue: { items: [], currentIndex: -1 } }) // no session after the update
  await nativeSessionArrives(updated, sessionFor(series13[3]))
  assert.equal(updated.$store.state.playbackQueue.items.length, 13)
  assert.equal(updated.$store.state.playbackQueue.currentIndex, 3)
})

test('native restored before the UI started (widget Play, then the app is opened): reattach restores Up Next', async () => {
  const prefs = playedSeriesYesterday()
  const ui = uiInstance(prefs)
  const session = sessionFor(series13[3])
  ui.$store.commit('setPlaybackSession', session)
  await ui.nativePlaybackReattached({ playbackSession: session, queue: nativeQueue(3) })
  assert.equal(ui.$store.state.playbackQueue.items.length, 13)
  assert.equal(ui.$store.state.playbackQueue.currentIndex, 3)
})

test('WebView recreation while native kept playing: the new UI shows the queue at the index native advanced to', async () => {
  const prefs = playedSeriesYesterday()
  const session = sessionFor(series13[6], 'session-7')
  const ui = uiInstance(prefs)
  ui.$store.commit('setPlaybackSession', session)
  await ui.nativePlaybackReattached({ playbackSession: session, queue: nativeQueue(6) })
  assert.equal(ui.$store.state.playbackQueue.currentIndex, 6)
  assert.equal(ui.$store.getters.current.libraryItemId, 'b6')
})

test('reattach and the session event both firing for one restore show the queue once (no duplicates, one commit)', async () => {
  const prefs = playedSeriesYesterday()
  const session = sessionFor(series13[3])
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => nativeQueue(3) })
  ui.$store.commit('setPlaybackSession', session)
  await ui.nativePlaybackReattached({ playbackSession: session, queue: nativeQueue(3) })
  const generation = ui.$store.state.playbackQueueGeneration
  await ui.nativePlaybackSessionPrepared(session)
  assert.equal(ui.$store.state.playbackQueue.items.length, 13)
  assert.equal(ui.$store.state.playbackQueueGeneration, generation, 'the second path did nothing')
})

test('Up Next opened after a cold restore reads the restored queue (the modal is bound to the store queue)', async () => {
  const prefs = playedSeriesYesterday()
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => nativeQueue(3) })
  await nativeSessionArrives(ui, sessionFor(series13[3]))
  assert.match(containerSource, /<modals-queue-modal v-model="showQueueModal" :queue="playbackQueue"/)
  assert.match(containerSource, /playbackQueue\(\) \{\s*return this\.\$store\.state\.playbackQueue/)
  assert.equal(ui.$store.state.playbackQueue.items[3].libraryItemId, 'b3')
})

test('offline restore of a downloaded book: no library or network access needed to show Up Next', async () => {
  const prefs = playedSeriesYesterday()
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => nativeQueue(3) })
  // $nativeHttp and $db fail the test if touched
  await nativeSessionArrives(ui, sessionFor(series13[3]))
  assert.equal(ui.$store.state.playbackQueue.items[3].localLibraryItem.id, 'local_b3', 'the downloaded entry stays downloaded')
  assert.equal(ui.$store.state.playbackQueue.items[8].localLibraryItem, null, 'streamed entries stay streamed')
})

// --- what must NOT change ---

test('a stale native answer never replaces a newer queue the user started while it was in flight', async () => {
  const prefs = playedSeriesYesterday()
  const read = deferred()
  const ui = uiInstance(prefs, { getPlaylistQueue: () => read.promise })
  const pending = nativeSessionArrives(ui, sessionFor(series13[3]))
  const newer = { sourceType: 'collection', sourceId: 'c', items: [entry('x', false)], currentIndex: 0 }
  ui.$store.commit('setPlaybackQueue', newer)
  read.resolve(nativeQueue(3))
  await pending
  assert.equal(ui.$store.state.playbackQueue, newer)
})

test('a stale native answer never brings back a queue the user cleared (Play without a source, Close) meanwhile', async () => {
  const prefs = playedSeriesYesterday()
  const read = deferred()
  const ui = uiInstance(prefs, { getPlaylistQueue: () => read.promise })
  const pending = nativeSessionArrives(ui, sessionFor(series13[3]))
  ui.$store.commit('clearPlaybackQueue')
  read.resolve(nativeQueue(3))
  await pending
  assert.equal(ui.$store.state.playbackQueue, null)
})

test('a stale saved-copy read loses to a newer queue too (the second await in the restore)', async () => {
  const prefs = playedSeriesYesterday()
  const savedRead = deferred()
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => nativeQueue(3) })
  ui.$localStore = { ...prefs.localStore, getPlaybackQueue: () => savedRead.promise }
  const pending = nativeSessionArrives(ui, sessionFor(series13[3]))
  await Promise.resolve()
  await Promise.resolve()
  const newer = { sourceType: 'collection', sourceId: 'c', items: [entry('x', false)], currentIndex: 0 }
  ui.$store.commit('setPlaybackQueue', newer)
  savedRead.resolve(savedSeriesQueue())
  await pending
  assert.equal(ui.$store.state.playbackQueue, newer)
})

test('explicit Close: native has no queue afterwards, so a later session shows none; the closed queue never returns', async () => {
  const prefs = playedSeriesYesterday()
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => ({ items: [], currentIndex: -1 }) })
  await nativeSessionArrives(ui, sessionFor(series13[3]))
  assert.equal(ui.$store.state.playbackQueue, null)
})

test('a Play started from this UI is never touched: its queue (or no queue) is set before the session arrives', async () => {
  const prefs = playedSeriesYesterday()
  let nativeReads = 0
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => (nativeReads++, nativeQueue(3)) })
  const own = { sourceType: 'series', sourceId: 'other', items: [entry('y', true), entry('z', false)], currentIndex: 0 }
  ui.$store.commit('setPlaybackQueue', own)
  await nativeSessionArrives(ui, sessionFor(own.items[0]))
  assert.equal(ui.$store.state.playbackQueue, own)
  assert.equal(nativeReads, 0, 'no native read while the UI owns a queue')
  // A play request still being prepared (preparingPlayback): the restore stays out of its way
  const ui2 = uiInstance(prefs, { getPlaylistQueue: async () => (nativeReads++, nativeQueue(3)) })
  ui2.preparingPlayback = true
  await nativeSessionArrives(ui2, sessionFor(series13[3]))
  assert.equal(ui2.$store.state.playbackQueue, null)
  assert.equal(nativeReads, 0)
})

test('auto-advance keeps working: the next session leaves the queue to onPlaybackEnded, which moves the index', async () => {
  const prefs = playedSeriesYesterday()
  let native = nativeQueue(3)
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => native })
  await nativeSessionArrives(ui, sessionFor(series13[3]))
  const queue = ui.$store.state.playbackQueue
  // Book 3 ends: native advances and prepares book 4
  native = nativeQueue(4)
  await ui.onPlaybackEnded()
  await nativeSessionArrives(ui, sessionFor(series13[4], 'session-2'))
  assert.equal(ui.$store.state.playbackQueue.currentIndex, 4)
  assert.equal(ui.$store.state.playbackQueue.items, queue.items, 'same items, no rebuild')
  assert.equal(ui.$store.getters.current.libraryItemId, 'b4')
})

test('a native queue whose current item is not the session item, or that does not match the display copy, is not shown', async () => {
  const prefs = playedSeriesYesterday()
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => nativeQueue(3) })
  await nativeSessionArrives(ui, sessionFor(series13[5]))
  assert.equal(ui.$store.state.playbackQueue, null)
  const ui2 = uiInstance(prefs, { getPlaylistQueue: async () => nativeQueue(3, series13.slice(0, 10)) })
  await nativeSessionArrives(ui2, sessionFor(series13[3]))
  assert.equal(ui2.$store.state.playbackQueue, null, 'never a series-derived replacement')
})

test('a session event for an older session that was replaced meanwhile does nothing', async () => {
  const prefs = playedSeriesYesterday()
  const read = deferred()
  const ui = uiInstance(prefs, { getPlaylistQueue: () => read.promise })
  const old = sessionFor(series13[3], 'old')
  ui.$store.commit('setPlaybackSession', old)
  const pending = ui.nativePlaybackSessionPrepared(old)
  ui.$store.commit('setPlaybackSession', sessionFor(series13[3], 'new'))
  read.resolve(nativeQueue(3))
  await pending
  assert.equal(ui.$store.state.playbackQueue, null)
})

test('rapid foreground/background: repeated events for the same restored session are idempotent', async () => {
  const prefs = playedSeriesYesterday()
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => nativeQueue(3) })
  const session = sessionFor(series13[3])
  await nativeSessionArrives(ui, session)
  const queue = ui.$store.state.playbackQueue
  for (let i = 0; i < 5; i++) await ui.nativePlaybackSessionPrepared(session)
  assert.equal(ui.$store.state.playbackQueue, queue)
})

test('a failed native queue read is harmless (no queue shown, no error to the user)', async () => {
  const prefs = playedSeriesYesterday()
  const ui = uiInstance(prefs, { getPlaylistQueue: async () => Promise.reject(new Error('bridge')) })
  ui.$store.commit('setPlaybackSession', sessionFor(series13[3]))
  await assert.doesNotReject(ui.nativePlaybackSessionPrepared(sessionFor(series13[3])))
  assert.equal(ui.$store.state.playbackQueue, null)
  assert.equal(ui.error, undefined)
})

// --- wiring ---

test('the player forwards every natively prepared session to the container; reattach keeps its own path', () => {
  assert.match(playerSource, /AbsAudioPlayer\.addListener\('onPlaybackSession', this\.onNativePlaybackSession\)/)
  const wrapper = playerSource.match(/onNativePlaybackSession\(playbackSession\) \{[\s\S]*?\n {4}\}/)[0]
  assert.match(wrapper, /this\.onPlaybackSession\(playbackSession\)/)
  assert.match(wrapper, /\$eventBus\.\$emit\('native-playback-session', playbackSession\)/)
  const reattach = playerSource.match(/async reattachNativePlayback\(\) \{[\s\S]*?\n {4}\}/)[0]
  assert.match(reattach, /this\.onPlaybackSession\(state\.playbackSession\)/)
  assert.doesNotMatch(reattach, /native-playback-session/)
  assert.match(containerSource, /\$eventBus\.\$on\('native-playback-session', this\.nativePlaybackSessionPrepared\)/)
  assert.match(containerSource, /\$eventBus\.\$off\('native-playback-session', this\.nativePlaybackSessionPrepared\)/)
})

test('every queue set and clear bumps the store generation', () => {
  const state = storeSandbox.state()
  const ctx = { $localStore: { setPlaybackQueue: async () => {} } }
  storeSandbox.mutations.setPlaybackQueue.call(ctx, state, savedSeriesQueue())
  storeSandbox.mutations.clearPlaybackQueue.call(ctx, state)
  assert.equal(state.playbackQueueGeneration, 2)
})
