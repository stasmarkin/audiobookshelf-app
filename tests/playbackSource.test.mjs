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

function container(native, { casting = false, confirm = true } = {}) {
  const calls = { cellular: 0, dialogs: 0, prepared: [], resolved: [] }
  const AbsAudioPlayer = {
    resolvePlaybackSource: async (payload) => {
      calls.resolved.push(payload)
      return native.resolvePlaybackSource(payload)
    },
    prepareLibraryItem: async (payload) => {
      calls.prepared.push(payload)
      return {}
    },
    setPlaylistQueue: async () => {},
    clearPlaylistQueue: async () => {},
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
      commit: () => {}
    },
    $toast: { error: (message) => (ctx.error = message) },
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
