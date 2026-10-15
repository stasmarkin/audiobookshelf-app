// Refocus while paused: a downloaded book's displayed position (and so a new bookmark's time) comes from the native
// player's live position when it holds the same session, never from stale saved progress; the previous fallback stays
// for every other case, and nothing seeks or writes progress just to refresh the display.
// Also: the layout consumers above the collapsed player use its projected height.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const helperSource = await read('../utils/livePosition.js')
const { refocusDisplayTime } = await import(`data:text/javascript;base64,${Buffer.from(helperSource).toString('base64')}`)

const SAVED = 6107.556
const LIVE = 7107.976
const session = { id: 'session-1', localLibraryItem: { id: 'local_oz' }, localEpisodeId: null }

test('refocusDisplayTime: the live position for the matching loaded session, the saved progress otherwise', () => {
  assert.deepEqual(refocusDisplayTime(session, { playbackSession: { id: 'session-1' }, currentTime: LIVE, isPlaying: false }, SAVED), { time: LIVE, source: 'live' })
  assert.deepEqual(refocusDisplayTime(session, { playbackSession: { id: 'session-1' }, currentTime: 0 }, SAVED), { time: 0, source: 'live' })
  const fallback = { time: SAVED, source: 'saved' }
  assert.deepEqual(refocusDisplayTime(session, { playbackSession: { id: 'other' }, currentTime: LIVE }, SAVED), fallback, 'another session')
  assert.deepEqual(refocusDisplayTime(session, { queue: {} }, SAVED), fallback, 'no native session')
  assert.deepEqual(refocusDisplayTime(session, null, SAVED), fallback, 'no answer')
  assert.deepEqual(refocusDisplayTime(session, { playbackSession: { id: 'session-1' }, currentTime: NaN }, SAVED), fallback, 'NaN')
  assert.deepEqual(refocusDisplayTime(session, { playbackSession: { id: 'session-1' }, currentTime: -1 }, SAVED), fallback, 'negative')
  assert.deepEqual(refocusDisplayTime(session, { playbackSession: { id: 'session-1' }, currentTime: '7107' }, SAVED), fallback, 'not a number')
  assert.deepEqual(refocusDisplayTime({}, { playbackSession: { id: undefined }, currentTime: LIVE }, SAVED), fallback, 'no ids')
  assert.deepEqual(refocusDisplayTime(null, { playbackSession: { id: 'session-1' }, currentTime: LIVE }, SAVED), fallback, 'no UI session')
})

// AudioPlayerContainer's methods run against a minimal component context. The player stub mirrors AudioPlayer: timeupdate()
// emits updateTime, which the container stores as currentTime (the time the bookmarks modal receives).
async function loadContainer() {
  const source = await read('../components/app/AudioPlayerContainer.vue')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { AbsAudioPlayer: {}, AbsLogger: { info: async () => {}, error: async () => {} }, Dialog: {}, CellularPermissionHelpers: {}, nativeQueueItems() {}, resolvePlaybackQueue() {}, resolveQueueSourceAppendItems() {}, sessionToQueueItem() {}, refocusDisplayTime, console: { log() {}, error() {}, warn() {} } }
  vm.runInNewContext(script, sandbox)
  return { component: sandbox.component, sandbox }
}

// $absData.applyServerProgress stands for native's merge: by default it takes only a newer value than the saved one;
// `applied` forces its answer (false: an unsent local change wins). calls.sync: values it took
function makeContext(component, { native, server, applied } = {}) {
  const calls = { seek: [], sync: [], asked: [], db: [], native: 0 }
  const ctx = {
    currentPlaybackSession: session,
    currentTime: SAVED,
    $store: { getters: { 'globals/getLocalMediaProgressById': (id) => (id === 'local_oz' ? { id: 'progress-1', currentTime: SAVED, lastUpdate: 2000 } : null) } },
    onPlaybackEnded: async () => {},
    getServerMediaProgressForCurrentSession: async () => server || { currentTime: SAVED, lastUpdate: 1000 },
    $absData: {
      applyServerProgress: async ({ progress }) => {
        calls.asked.push(progress)
        const taken = applied ?? progress.lastUpdate > 2000
        if (taken) calls.sync.push(progress)
        return { applied: taken }
      }
    },
    $db: new Proxy({}, { get: (_, name) => (...args) => calls.db.push([name, args]) })
  }
  ctx.$refs = {
    audioPlayer: {
      isPlaying: false,
      isLocalPlayMethod: true,
      currentTime: SAVED,
      seek: (t) => calls.seek.push(t),
      timeupdate() {
        ctx.currentTime = this.currentTime
      }
    }
  }
  for (const name of ['getNativePlaybackState', 'getLocalMediaProgressForCurrentSession', 'checkSyncServerProgressWithLocalProgress']) ctx[name] = component.methods[name].bind(ctx)
  return { ctx, calls, native }
}

test('deviceFocused while paused: the matching live position is shown; no seek and no progress write', async () => {
  const { component, sandbox } = await loadContainer()
  const { ctx, calls } = makeContext(component)
  sandbox.AbsAudioPlayer.getCurrentPlaybackState = async () => {
    calls.native++
    return { playbackSession: { id: 'session-1' }, isPlaying: false, currentTime: LIVE }
  }
  // The UI first shows the stale saved time, as on the S22
  ctx.$refs.audioPlayer.currentTime = SAVED
  await component.methods.deviceFocused.call(ctx, true)
  assert.equal(calls.native, 1)
  assert.equal(ctx.$refs.audioPlayer.currentTime, LIVE)
  assert.equal(ctx.currentTime, LIVE, 'the container (and so the bookmarks modal) gets the live position')
  assert.deepEqual(calls.seek, [], 'no seek')
  assert.deepEqual(calls.sync, [], 'saved progress is not rewritten')
  // Refocusing again keeps it (no drift back to saved progress)
  await component.methods.deviceFocused.call(ctx, true)
  assert.equal(ctx.currentTime, LIVE)
})

test('deviceFocused fallback: another native session, no session, a failing or missing call -> saved progress as before', async () => {
  const { component, sandbox } = await loadContainer()
  const cases = {
    'another session': async () => ({ playbackSession: { id: 'session-2' }, currentTime: LIVE }),
    'no session': async () => ({ queue: { items: [] } }),
    'call fails': async () => {
      throw new Error('not implemented')
    },
    'method missing (web)': undefined
  }
  for (const [name, impl] of Object.entries(cases)) {
    sandbox.AbsAudioPlayer.getCurrentPlaybackState = impl
    const { ctx, calls } = makeContext(component)
    ctx.$refs.audioPlayer.currentTime = 1
    await component.methods.deviceFocused.call(ctx, true)
    assert.equal(ctx.currentTime, SAVED, name)
    assert.deepEqual(calls.seek, [], name)
  }
})

test('deviceFocused keeps its other behaviour: no work while playing or unfocused; a newer server position still syncs', async () => {
  const { component, sandbox } = await loadContainer()
  let asked = 0
  sandbox.AbsAudioPlayer.getCurrentPlaybackState = async () => {
    asked++
    return { playbackSession: { id: 'session-1' }, currentTime: LIVE }
  }
  const playing = makeContext(component)
  playing.ctx.$refs.audioPlayer.isPlaying = true
  await component.methods.deviceFocused.call(playing.ctx, true)
  const unfocused = makeContext(component)
  await component.methods.deviceFocused.call(unfocused.ctx, false)
  assert.equal(asked, 0)
  assert.equal(playing.ctx.currentTime, SAVED)
  // Progress from another device (server newer than local) is still applied exactly as before
  const remote = makeContext(component, { server: { currentTime: 9000, lastUpdate: 3000 } })
  await component.methods.deviceFocused.call(remote.ctx, true)
  assert.deepEqual(remote.calls.seek, [9000])
  assert.equal(remote.calls.sync.length, 1, 'taken through $absData.applyServerProgress')
  assert.deepEqual(remote.calls.db, [], 'nothing written to Paper from JS')
})

test('refocus applies server progress through Room and seeks only if accepted', async () => {
  const { component, sandbox } = await loadContainer()
  sandbox.AbsAudioPlayer.getCurrentPlaybackState = async () => null
  const newer = { libraryItemId: 'item-1', currentTime: 9000, lastUpdate: 3000 }
  // The device has an unsent change of this item: native keeps it although the server value is newer
  const kept = makeContext(component, { server: newer, applied: false })
  await component.methods.deviceFocused.call(kept.ctx, true)
  assert.deepEqual(kept.calls.asked, [newer], 'the server value is handed to native')
  assert.deepEqual(kept.calls.seek, [], 'no seek to a value native did not take')
  assert.deepEqual(kept.calls.db, [])

  const taken = makeContext(component, { server: newer, applied: true })
  await component.methods.deviceFocused.call(taken.ctx, true)
  assert.deepEqual(taken.calls.seek, [9000])
})

test('Bookmark timestamp: the modal saves the container time, which after refocus is the live paused position', async () => {
  const container = await read('../components/app/AudioPlayerContainer.vue')
  assert.match(container, /@updateTime="\(t\) => \(currentTime = t\)"/)
  assert.match(container, /<modals-bookmarks-modal v-model="showBookmarksModal" :bookmarks="bookmarks" :current-time="currentTime"/)
  const modal = await read('../components/modals/BookmarksModal.vue')
  const script = modal
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { Dialog: {}, console }
  vm.runInNewContext(script, sandbox)
  const added = []
  const ctx = {
    currentTime: LIVE,
    libraryItemId: 'item-1',
    serverConfigId: null,
    newBookmarkTitle: 'check',
    showBookmarkTitleInput: true,
    $absData: { addBookmark: (args) => (added.push(args), Promise.resolve()) },
    $toast: { success() {}, error() {} },
    $strings: {},
    $formatDate: () => ''
  }
  Object.defineProperty(ctx, 'show', { set() {}, get: () => true })
  sandbox.component.methods.submitCreateBookmark.call(ctx)
  assert.equal(added.length, 1)
  assert.equal(added[0].libraryItemId, 'item-1')
  assert.equal(added[0].title, 'check')
  assert.equal(added[0].time, Math.floor(LIVE))
  assert.equal(added[0].time, 7107)
})

test('Layout consumers above the collapsed player follow its projected height (bars: the variable; lists: the page height)', async () => {
  const H = 'var(--mini-player-height, 120px)'
  const item = await read('../pages/localMedia/item/_id.vue')
  const folder = await read('../pages/localMedia/folders/_id.vue')
  const pdf = await read('../components/readers/PdfReader.vue')
  assert.ok(item.includes(`:style="{ bottom: isPlayerOpen ? '${H}' : '0px' }"`), 'reorder bar')
  assert.ok(pdf.includes(`:style="{ bottom: isPlayerOpen ? '${H}' : '0px' }"`), 'PDF toolbar')
  // The downloaded-media lists are measured, not estimated: each fills its page down to the page bottom (the page fills
  // #content, which ends at the collapsed player's projected height), so they hold at any font scale (closure pass)
  assert.match(item, /<div v-if="localLibraryItem" class="w-full h-full flex flex-col">/)
  assert.match(folder, /<div class="w-full h-full py-6 px-4 flex flex-col">/)
  for (const src of [item, folder]) assert.ok(src.includes('.media-item-container {\n  flex: 1 1 0;\n  min-height: 0;\n  margin-bottom: -24px;\n}'))
  assert.ok(item.includes('.media-item-container.media-order-changed {\n  margin-bottom: 56px;\n}'))
  for (const src of [item, folder]) assert.ok(!src.includes('calc(100vh'))
  for (const [name, src] of [
    ['item', item],
    ['folder', folder],
    ['pdf', pdf]
  ])
    assert.doesNotMatch(src, /'120px'|- (300|310|380)px\)/, name)
  // The variable comes from the player's projection (theme/playerLayout.js miniLayout)
  const player = await read('../components/app/AudioPlayer.vue')
  assert.match(player, /document\.documentElement\.style\.setProperty\('--mini-player-height', height \+ 'px'\)/)
})
