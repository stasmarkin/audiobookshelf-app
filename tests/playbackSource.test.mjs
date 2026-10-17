// Where a Play plays from is decided natively (PlaybackSourceResolver: a downloaded copy always, the stream otherwise).
// The UI asks it first (AbsAudioPlayer.resolvePlaybackSource) so the cellular prompt only appears for a real stream and
// the cast warning only for a file. Real AudioPlayerContainer methods with a fake Capacitor boundary.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = async (path) => (await readFile(new URL(path, import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
const helperSource = await read('../utils/playbackQueue.js')
const helpers = await import(`data:text/javascript;base64,${Buffer.from(helperSource).toString('base64')}`)
const containerScript = (await read('../components/app/AudioPlayerContainer.vue'))
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '')
  .replace('export default', 'globalThis.component =')

function container(native, { casting = false, confirm = true, prepareAnswer = {} } = {}) {
  const calls = { cellular: 0, dialogs: 0, prepared: [], resolved: [], cleared: 0, commits: [] }
  const AbsAudioPlayer = {
    resolvePlaybackSource: async (payload) => {
      calls.resolved.push(payload)
      return native.resolvePlaybackSource(payload)
    },
    prepareLibraryItem: async (payload) => {
      calls.prepared.push(payload)
      return prepareAnswer
    },
    setPlaylistQueue: async () => {},
    clearPlaylistQueue: async () => {
      calls.cleared++
    },
    requestSession: () => {}
  }
  const Dialog = {
    confirm: async () => {
      calls.dialogs++
      return { value: confirm }
    }
  }
  const sandbox = { ...helpers, AbsAudioPlayer, Dialog, AbsLogger: { info: async () => {} }, CellularPermissionHelpers: {}, console: { ...console, log: () => {} } }
  vm.runInNewContext(containerScript, sandbox)
  const ctx = {
    $platform: 'android',
    $store: {
      state: { isCasting: casting, playbackQueue: null, activeServerConfigId: 'server' },
      getters: { getIsMediaStreaming: () => false, 'user/getServerConnectionConfigId': 'server' },
      commit: (type) => calls.commits.push(type)
    },
    $toast: { error: (message) => (ctx.error = message), info: (message) => (ctx.info = message) },
    $strings: { ToastWaitingForServer: 'Waiting for the server' },
    $refs: {},
    checkCellularPermission: async () => {
      calls.cellular++
      return true
    }
  }
  Object.assign(ctx, sandbox.component.data())
  for (const [name, fn] of Object.entries(sandbox.component.methods)) ctx[name] = fn.bind(ctx)
  return { ctx, calls }
}

test('startLibraryItem sends the id as before and checks cellular only when native streams', async () => {
  // A server id of a downloaded book: native plays the copy, so no cellular prompt
  const downloaded = container({ resolvePlaybackSource: async () => ({ source: 'local' }) })
  await downloaded.ctx.startLibraryItem({ libraryItemId: 'li1', ino: '42', expectedTracks: 3 })
  assert.equal(downloaded.calls.cellular, 0)
  assert.deepEqual(JSON.parse(JSON.stringify(downloaded.calls.resolved)), [{ libraryItemId: 'li1', episodeId: null, ino: '42', expectedTracks: 3 }])
  assert.equal(downloaded.calls.prepared[0].expectedTracks, 3)
  assert.equal(downloaded.calls.prepared[0].libraryItemId, 'li1')
  assert.equal(downloaded.calls.prepared[0].ino, '42')

  const streamed = container({ resolvePlaybackSource: async () => ({ source: 'stream' }) })
  await streamed.ctx.startLibraryItem({ libraryItemId: 'li2' })
  assert.equal(streamed.calls.cellular, 1)
  assert.equal(streamed.calls.prepared.length, 1)

  // A copy whose files are gone: native answers with its error, there is nothing to stream
  const broken = container({ resolvePlaybackSource: async () => ({ source: 'broken' }) })
  await broken.ctx.startLibraryItem({ libraryItemId: 'local_x' })
  assert.equal(broken.calls.cellular, 0)
})

test('no cellular permission, no start', async () => {
  const { ctx, calls } = container({ resolvePlaybackSource: async () => ({ source: 'stream' }) })
  ctx.checkCellularPermission = async () => false
  await ctx.startLibraryItem({ libraryItemId: 'li2' })
  assert.equal(calls.prepared.length, 0)
})

test('without an answer from native the id decides, as before', async () => {
  const failing = { resolvePlaybackSource: async () => Promise.reject(new Error('bridge')) }
  const server = container(failing)
  await server.ctx.startLibraryItem({ libraryItemId: 'li2' })
  assert.equal(server.calls.cellular, 1)
  const local = container(failing)
  await local.ctx.startLibraryItem({ libraryItemId: 'local_1' })
  assert.equal(local.calls.cellular, 0)
})

test('the cast warning is shown for a file, not for a stream', async () => {
  const file = container({ resolvePlaybackSource: async () => ({ source: 'local' }) }, { casting: true, confirm: false })
  await file.ctx.startLibraryItem({ libraryItemId: 'local_1' })
  assert.equal(file.calls.dialogs, 1)
  assert.equal(file.calls.prepared.length, 0)
  const stream = container({ resolvePlaybackSource: async () => ({ source: 'stream' }) }, { casting: true })
  await stream.ctx.startLibraryItem({ libraryItemId: 'li1' })
  assert.equal(stream.calls.dialogs, 0)
  assert.equal(stream.calls.prepared.length, 1)
})

test('casting a downloaded book asks native to stream it', async () => {
  const { ctx, calls } = container({ resolvePlaybackSource: async () => ({ source: 'stream' }) })
  await ctx.playServerLibraryItemAndCast('li1', null)
  assert.equal(calls.prepared[0].libraryItemId, 'li1')
  assert.equal(calls.prepared[0].forceStream, true)
})

// Native picks the start of a downloaded book (the newer of the device's media_progress and its copy's progress), so a
// seek while paused survives. A Play on the playlist page must not override it with the Vuex progress.
test('playlist Play of a downloaded book leaves the start position to native', async () => {
  const strip = (source) => source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =')
  const vuexProgress = () => ({ currentTime: 235.295, isFinished: false })
  const emitted = []
  const bus = { $emit: (name, payload) => emitted.push([name, payload]) }
  const item = { libraryItemId: 'li1', episodeId: null, libraryItem: { id: 'li1', media: { numTracks: 1 } }, localLibraryItem: { id: 'local_1' } }

  const pageSandbox = { ...helpers, resolveItemProgress: vuexProgress, downloadMissingItems: () => {}, requiresServer: {} }
  vm.runInNewContext(strip(await read('../pages/playlist/_id.vue')), pageSandbox)
  const page = { playlist: { id: 'pl1' }, playableItems: [item], $store: { commit: () => {} }, $eventBus: bus }
  pageSandbox.component.methods.startPlaylistPlayback.call(page, item)

  const rowSandbox = { resolveItemProgress: vuexProgress }
  vm.runInNewContext(strip(await read('../components/tables/playlist/ItemTableRow.vue')), rowSandbox)
  const row = { playerIsStartingPlayback: false, streamIsPlaying: false, episodeId: null, libraryItem: { id: 'li1' }, localLibraryItem: { id: 'local_1' }, localEpisode: null, playlistId: 'pl1', playlistPlayableItems: [item], item, $hapticsImpact: async () => {}, $store: { commit: () => {} }, $eventBus: bus }
  await rowSandbox.component.methods.playClick.call(row)

  assert.equal(emitted.length, 2)
  for (const [name, payload] of emitted) {
    assert.equal(name, 'play-item')
    assert.equal(payload.startTime, undefined)
  }
  assert.equal(emitted[0][1].libraryItemId, 'local_1')
  assert.equal(emitted[1][1].serverLibraryItemId, 'li1')
})

test('a queue start that native holds for the server keeps the queue', async () => {
  // Native answers awaitingServer when the queue's item can only stream and the server is away (queueItemToAwait)
  const queue = { sourceType: 'playlist', sourceId: 'p1', items: [{ libraryItemId: 'li2', episodeId: null }], currentIndex: 0 }
  const waiting = container({ resolvePlaybackSource: async () => ({ source: 'stream' }) }, { prepareAnswer: { awaitingServer: true } })
  await waiting.ctx.startLibraryItem({ libraryItemId: 'li2', expectedTracks: 3 }, queue)
  assert.equal(waiting.calls.cleared, 0, 'the native queue stays')
  assert.ok(!waiting.calls.commits.includes('clearPlaybackQueue'), 'Up Next stays')
  assert.ok(waiting.calls.commits.includes('setPlaybackQueue'))
  assert.equal(waiting.ctx.error, undefined, 'no error toast')

  const failed = container({ resolvePlaybackSource: async () => ({ source: 'stream' }) }, { prepareAnswer: { error: 'Server play request failed' } })
  await failed.ctx.startLibraryItem({ libraryItemId: 'li2' }, queue)
  assert.equal(failed.calls.cleared, 1, 'a failed start still clears it')
  assert.equal(failed.ctx.error, 'Server play request failed')
})

test('while a queue waits for the server, the open book keeps its ids and a cold start says it waits', async () => {
  // Book A is open (paused by native); the queue now waits on book B
  const warm = container({ resolvePlaybackSource: async () => ({ source: 'stream' }) }, { prepareAnswer: { awaitingServer: true } })
  warm.ctx.currentPlaybackSession = { libraryItemId: 'bookA', episodeId: null }
  warm.ctx.setSessionServerIds()
  await warm.ctx.startLibraryItem({ libraryItemId: 'bookB' }, { sourceType: 'series', items: [{ libraryItemId: 'bookB' }], currentIndex: 0 })
  assert.equal(warm.ctx.serverLibraryItemId, 'bookA', "bookmarks go to the open book, not the awaited one")
  assert.equal(warm.ctx.info, undefined, 'the open player shows the wait')

  const cold = container({ resolvePlaybackSource: async () => ({ source: 'stream' }) }, { prepareAnswer: { awaitingServer: true } })
  cold.ctx.currentPlaybackSession = null
  await cold.ctx.startLibraryItem({ libraryItemId: 'bookB' }, { sourceType: 'series', items: [{ libraryItemId: 'bookB' }], currentIndex: 0 })
  assert.equal(cold.ctx.info, 'Waiting for the server', 'no player to show it: Play is not silent')
  assert.equal(cold.ctx.serverLibraryItemId, null)
  const strings = JSON.parse(await read('../strings/en-us.json'))
  assert.equal(strings.ToastWaitingForServer, 'Waiting for the server')
})
