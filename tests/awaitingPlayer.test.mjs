// A queue start (series, collection, playlist) on a book that can only stream while the server is away: native waits on
// it and sends a display of that book from the replica. The player shows it instead of a toast, or instead of the book
// that was open. Real store, real AudioPlayerContainer methods and real AudioPlayer computed/methods, fake Capacitor.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = async (path) => (await readFile(new URL(path, import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
const strip = (source) => source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =')
const helperSource = await read('../utils/playbackQueue.js')
const helpers = await import(`data:text/javascript;base64,${Buffer.from(helperSource).toString('base64')}`)

const storeSource = (await read('../store/index.js')).replace(/^import .*$/gm, '').replace(/^export const (\w+) =/gm, 'globalThis.$1 =')
const storeSandbox = {}
vm.runInNewContext(storeSource, storeSandbox)
const containerScript = strip(await read('../components/app/AudioPlayerContainer.vue'))
const playerSource = await read('../components/app/AudioPlayer.vue')

const displayB = { libraryItemId: 'bookB', episodeId: null, serverConfigId: 'server', title: 'Book B', author: 'Author B', coverUri: null, duration: 600, currentTime: 42 }
const sessionA = { id: 'session-a', libraryItemId: 'bookA', episodeId: null, displayTitle: 'Book A', displayAuthor: 'Author A' }
const sessionB = { id: 'session-b', libraryItemId: 'bookB', episodeId: null, displayTitle: 'Book B', displayAuthor: 'Author B' }

function newStore() {
  const state = storeSandbox.state()
  return {
    state,
    getters: {
      getIsMediaStreaming: () => false,
      'user/getServerConnectionConfigId': 'server',
      get getIsPlayerOpen() {
        return storeSandbox.getters.getIsPlayerOpen(state)
      }
    },
    commit(name, payload) {
      storeSandbox.mutations[name].call({ $localStore: { setPlaybackQueue: async () => {} } }, state, payload)
    }
  }
}

function container(store, prepareAnswer) {
  const AbsAudioPlayer = {
    resolvePlaybackSource: async () => ({ source: 'stream' }),
    prepareLibraryItem: async () => prepareAnswer,
    setPlaylistQueue: async () => {},
    clearPlaylistQueue: async () => {}
  }
  const sandbox = { ...helpers, AbsAudioPlayer, Dialog: {}, AbsLogger: { info: async () => {} }, CellularPermissionHelpers: {}, console: { ...console, log: () => {} } }
  vm.runInNewContext(containerScript, sandbox)
  const ctx = { $platform: 'android', $store: store, $strings: { ToastWaitingForServer: 'Waiting for the server' }, $refs: {}, checkCellularPermission: async () => true }
  ctx.$toast = { error: (m) => (ctx.error = m), info: (m) => (ctx.info = m) }
  Object.assign(ctx, sandbox.component.data())
  for (const [name, fn] of Object.entries(sandbox.component.methods)) ctx[name] = fn.bind(ctx)
  Object.defineProperty(ctx, 'currentPlaybackSession', { get: () => store.state.currentPlaybackSession })
  return ctx
}

// The AudioPlayer's computed values as the template reads them, for the store's state now
function playerView(store, playbackSession = store.state.currentPlaybackSession, native = {}) {
  const sandbox = { AbsAudioPlayer: native, console: { ...console, log: () => {} }, Capacitor: { convertFileSrc: (v) => `converted:${v}` }, Dialog: {}, getAverageColorFromCoverUrl: () => null, normalizePlaybackRate: (v) => v, formatPlaybackRate: String, coverPresentation: {}, playerLayout: {}, WrappingMarquee: function () {}, jumpLabelMixin: {}, COARSE_STEP_S: 60, coarseTime: (v) => v, coarseClock: String, sleepRemainingLabel: String, createPainter: () => () => {} }
  vm.runInNewContext(strip(playerSource), sandbox)
  const { computed, methods } = sandbox.component
  const view = { $store: store, $strings: { LabelWaitingForServer: 'Waiting for server' }, $platform: 'android', playbackSession, showFullscreen: false, currentChapterTitle: '', isLoading: false, isCheckingServerProgress: false }
  for (const name of ['awaitingDisplay', 'awaitingServer', 'isPlayerOpen', 'mediaMetadata', 'libraryItem', 'localLibraryItem', 'localLibraryItemCoverSrc', 'coverLibraryItem', 'coverDownloadSrc', 'title', 'authorName', 'authorLine']) {
    Object.defineProperty(view, name, { get: () => computed[name].call(view), configurable: true })
  }
  for (const [name, fn] of Object.entries(methods)) view[name] = fn.bind(view)
  return view
}

test('a queue start that waits shows the awaited book in the player, not a toast', async () => {
  const store = newStore()
  const ui = container(store, { awaitingServer: true, display: displayB })
  await ui.startLibraryItem({ libraryItemId: 'bookB' }, { sourceType: 'series', items: [{ libraryItemId: 'bookB' }], currentIndex: 0 })
  assert.equal(ui.info, undefined, 'the player says it waits: no toast')
  assert.deepEqual({ ...store.state.playerAwaitingDisplay }, displayB)
  assert.equal(store.state.playerAwaitingServer, true)
  const view = playerView(store)
  assert.equal(view.isPlayerOpen, true, 'the player is open without a session')
  assert.equal(view.title, 'Book B')
  assert.equal(view.authorLine, 'Waiting for server')
  assert.equal(view.authorName, 'Author B')
  assert.equal(store.getters.getIsPlayerOpen, true, 'the page leaves room for the player')
  assert.equal(view.coverLibraryItem.media.metadata.title, 'Book B', 'the placeholder cover names the awaited book')

  // No row in the replica: nothing to show, so Play is not silent
  const missing = newStore()
  const cold = container(missing, { awaitingServer: true, display: null })
  await cold.startLibraryItem({ libraryItemId: 'bookB' }, { sourceType: 'series', items: [{ libraryItemId: 'bookB' }], currentIndex: 0 })
  assert.equal(cold.info, 'Waiting for the server')
  assert.equal(playerView(missing).isPlayerOpen, false)
})

test('a wait native began by itself shows the awaited book the same way', () => {
  const store = newStore()
  const ui = container(store, {})
  ui.onAwaitingServer({ awaiting: true, display: displayB })
  const view = playerView(store)
  assert.equal(view.isPlayerOpen, true)
  assert.equal(view.title, 'Book B')
})

test('with book A open, a wait on book B shows B', () => {
  const store = newStore()
  store.commit('setPlaybackSession', sessionA)
  const ui = container(store, {})
  ui.onAwaitingServer({ awaiting: true, display: displayB })
  const view = playerView(store)
  assert.equal(view.title, 'Book B')
  assert.equal(view.authorName, 'Author B')

  // Native kept A's session id: the same session again does not drop the display
  store.commit('setPlaybackSession', sessionA)
  assert.equal(playerView(store).title, 'Book B')
})

test('the display ends with the awaited session, with the end of the wait, and with Close', () => {
  const store = newStore()
  const ui = container(store, {})
  ui.onAwaitingServer({ awaiting: true, display: displayB })
  store.commit('setPlaybackSession', sessionB)
  assert.equal(store.state.playerAwaitingDisplay, null)
  assert.equal(playerView(store).title, 'Book B')

  const waiting = newStore()
  const other = container(waiting, {})
  other.onAwaitingServer({ awaiting: true, display: displayB })
  // The server came back: native starts B and keeps showing it meanwhile
  other.onAwaitingServer({ awaiting: false, display: displayB })
  assert.equal(playerView(waiting).isPlayerOpen, true)
  assert.equal(playerView(waiting).authorLine, 'Author B')
  other.onAwaitingServer({ awaiting: false })
  assert.equal(waiting.state.playerAwaitingDisplay, null)
  assert.equal(playerView(waiting).isPlayerOpen, false)

  other.onAwaitingServer({ awaiting: true, display: displayB })
  waiting.commit('setPlaybackSession', null)
  assert.equal(waiting.state.playerAwaitingDisplay, null, 'Close ends it')
})

test('a recreated UI restores the awaiting display', async () => {
  const store = newStore()
  const state = { awaitingServer: true, awaitingDisplay: displayB, queue: { items: [{ libraryItemId: 'bookB' }], currentIndex: 0 } }
  const emitted = []
  const view = playerView(store, null, { getCurrentPlaybackState: async () => state })
  Object.assign(view, { $diag: { debug: () => {} }, $nextTick: async () => {}, $eventBus: { $emit: (name, payload) => emitted.push([name, payload]) }, $refs: {} })
  view.onPlaybackSession = () => assert.fail('there is no session to attach')
  view.showAwaitingDisplay = () => {}
  await view.reattachNativePlayback()
  assert.equal(store.state.playerAwaitingServer, true)
  assert.equal(view.isPlayerOpen, true)
  assert.equal(view.title, 'Book B')
  assert.deepEqual(emitted.map(([name]) => name), ['native-playback-reattached'], 'Up Next is restored as for a session')
})

test('the player template opens for a session or an awaited book', () => {
  const template = playerSource.slice(0, playerSource.indexOf('</template>'))
  assert.match(template, /<div v-if="isPlayerOpen" id="streamContainer"/)
  assert.match(template, /<covers-book-cover v-if="coverLibraryItem \|\| coverDownloadSrc"[^>]*:library-item="coverLibraryItem"[^>]*:download-cover="coverDownloadSrc"/)
})
