// The bookmarks modal (components/modals/BookmarksModal.vue) writes through $absData: native saves the bookmark on the
// device and sends it when the server is reachable, so offline is not a failure. Methods run on a fake component context.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const modal = (await readFile(new URL('../components/modals/BookmarksModal.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
const script = modal
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '')
  .replace('export default', 'globalThis.component =')

const flush = async (n = 10) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

function context({ serverConfigId = null, confirm = true, fails = false } = {}) {
  const sandbox = { Dialog: { confirm: async () => ({ value: confirm }) }, console: { ...console, error: () => {} } }
  vm.runInNewContext(script, sandbox)
  const calls = []
  const toasts = []
  const write = (name) => async (args) => {
    calls.push([name, JSON.parse(JSON.stringify(args))])
    if (fails) throw new Error('local write failed')
  }
  const ctx = {
    currentTime: 754.6,
    libraryItemId: 'li1',
    serverConfigId,
    newBookmarkTitle: 'Chapter 2',
    showBookmarkTitleInput: true,
    selectedBookmark: null,
    $absData: { addBookmark: write('addBookmark'), renameBookmark: write('renameBookmark'), removeBookmark: write('removeBookmark') },
    $nativeHttp: new Proxy({}, { get: (_, name) => () => calls.push(['nativeHttp', name]) }),
    $store: { commit: (type) => calls.push(['commit', type]) },
    $toast: { success: (m) => toasts.push(['success', m]), error: (m) => toasts.push(['error', m]) },
    $strings: { ToastBookmarkCreateFailed: 'create failed', ToastBookmarkUpdateFailed: 'update failed', ToastBookmarkRemoveFailed: 'remove failed' },
    $formatDate: () => 'now',
    $hapticsImpact: async () => {}
  }
  Object.defineProperty(ctx, 'show', { set() {}, get: () => true })
  const methods = sandbox.component.methods
  for (const [name, fn] of Object.entries(methods)) ctx[name] = fn.bind(ctx)
  return { ctx, calls, toasts }
}

test('bookmarks modal writes through $absData and never shows a failure toast offline', async () => {
  const { ctx, calls, toasts } = context()
  await ctx.submitBookmark()
  await flush()
  ctx.editBookmark({ libraryItemId: 'li1', time: 754, title: 'Chapter 2' })
  ctx.newBookmarkTitle = 'Renamed'
  await ctx.submitBookmark()
  await flush()
  await ctx.deleteBookmark({ libraryItemId: 'li1', time: 754, title: 'Renamed' })
  await flush()

  assert.deepEqual(calls, [
    ['addBookmark', { libraryItemId: 'li1', title: 'Chapter 2', time: 754, serverConfigId: null }],
    ['renameBookmark', { libraryItemId: 'li1', time: 754, title: 'Renamed', serverConfigId: null }],
    ['removeBookmark', { libraryItemId: 'li1', time: 754, serverConfigId: null }]
  ], 'no $nativeHttp, and no manual store commits: the store follows onDataChanged')
  assert.equal(typeof calls[0][1].time, 'number')
  assert.deepEqual(toasts.filter(([kind]) => kind === 'error'), [])
  assert.equal(ctx.showBookmarkTitleInput, false)
})

test('bookmarks of a downloaded copy go to the server of that copy', async () => {
  const { ctx, calls } = context({ serverConfigId: 'server-b' })
  await ctx.submitBookmark()
  await ctx.deleteBookmark({ libraryItemId: 'li1', time: 754 })
  await flush()
  assert.deepEqual(
    calls.map(([name, args]) => [name, args.serverConfigId]),
    [
      ['addBookmark', 'server-b'],
      ['removeBookmark', 'server-b']
    ]
  )
})

test('a failed local write still reports itself', async () => {
  const { ctx, toasts } = context({ fails: true })
  await ctx.submitBookmark()
  await ctx.deleteBookmark({ libraryItemId: 'li1', time: 754 })
  await flush()
  assert.deepEqual(toasts, [
    ['error', 'create failed'],
    ['error', 'remove failed']
  ])
})

test('the player passes the copy server only for a local session', async () => {
  const container = (await readFile(new URL('../components/app/AudioPlayerContainer.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  assert.match(container, /<modals-bookmarks-modal [^>]*:server-config-id="bookmarkServerConfigId"/)
  const sandbox = { console }
  vm.runInNewContext(container.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =').replace(/mixins: \[[^\]]*\],/, ''), sandbox)
  const pick = sandbox.component.computed.bookmarkServerConfigId
  assert.equal(pick.call({ currentPlaybackSession: { libraryItemId: 'li1', localLibraryItem: { id: 'local_1', serverConnectionConfigId: 'server-b' } } }), 'server-b')
  assert.equal(pick.call({ currentPlaybackSession: { libraryItemId: 'li1' } }), null)
  assert.equal(pick.call({ currentPlaybackSession: null }), null)
})

test('signing in through the connect form hands the user to native and reloads this server bookmarks', async () => {
  const form = (await readFile(new URL('../components/connection/ServerConnectForm.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  const sandbox = { console: { ...console, log: () => {}, error: () => {} } }
  const formScript = form.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =')
  vm.runInNewContext(formScript, sandbox)
  const calls = []
  const user = { id: 'user-1', username: 'synthetic', accessToken: 'access-1', refreshToken: 'refresh-1', librariesAccessible: [], bookmarks: [{ libraryItemId: 'li1', time: 3, title: 'A' }] }
  const config = { id: 'server-b', address: 'https://b.test.invalid', token: 'access-1' }
  const ctx = {
    serverConfig: { id: 'server-b', address: 'https://b.test.invalid' },
    serverConnectionConfigs: [config],
    deviceSettings: {},
    $store: { commit: (type) => calls.push(['commit', type]) },
    $setServerLanguageCode() {},
    $isValidVersion: () => true,
    $db: { setServerConnectionConfig: async () => config },
    $localStore: { getLastLibraryId: async () => null },
    $absData: {
      applyServerEvent: async (args) => calls.push(['applyServerEvent', JSON.parse(JSON.stringify(args))]),
      reloadBookmarks: async () => calls.push(['reloadBookmarks'])
    },
    $socket: { connect: () => calls.push(['socket']) },
    $router: { replace() {} }
  }
  await sandbox.component.methods.setUserAndConnection.call(ctx, { user, userDefaultLibraryId: null, serverSettings: { version: '2.30.0', language: 'en-us' }, ereaderDevices: [] })
  await flush()
  assert.ok(calls.some(([kind, type]) => kind === 'commit' && type === 'user/setUser'))
  assert.deepEqual(
    calls.filter(([kind]) => kind === 'applyServerEvent' || kind === 'reloadBookmarks'),
    [['applyServerEvent', { event: 'user_updated', payload: user, serverConfigId: 'server-b' }], ['reloadBookmarks']]
  )
})

test('a downloaded copy of another server shows and follows that server bookmarks, not the store', async () => {
  const sandbox = { Dialog: {}, console: { ...console, error: () => {} } }
  vm.runInNewContext(script, sandbox)
  const { component } = sandbox
  const native = {
    'server-a': [{ libraryItemId: 'li1', time: 1, title: 'Active server' }],
    'server-b': [
      { libraryItemId: 'li1', time: 2, title: 'Copy server' },
      { libraryItemId: 'li9', time: 3, title: 'Other item' }
    ]
  }
  let listener = null
  const asked = []
  const view = (serverConfigId) => {
    const ctx = {
      ...component.data(),
      bookmarks: [{ libraryItemId: 'li1', time: 1, title: 'From store' }],
      libraryItemId: 'li1',
      currentTime: 0,
      serverConfigId,
      $store: { state: { activeServerConfigId: 'server-a' } },
      $absData: {
        getBookmarks: async ({ serverConfigId: id }) => (asked.push(id), native[id]),
        onDataChanged: (cb) => ((listener = cb), () => (listener = null))
      }
    }
    for (const [name, def] of Object.entries(component.computed)) Object.defineProperty(ctx, name, { get: (def.get || def).bind(ctx) })
    for (const [name, fn] of Object.entries(component.methods)) ctx[name] = fn.bind(ctx)
    component.mounted.call(ctx)
    return ctx
  }

  const copy = view('server-b')
  await copy.loadCopyBookmarks()
  assert.deepEqual(copy.shownBookmarks.map((b) => b.title), ['Copy server'])
  assert.equal(copy.canCreateBookmark, true)

  native['server-b'].push({ libraryItemId: 'li1', time: 4, title: 'Added offline' })
  listener({ scope: 'bookmarks', ids: ['li1'] })
  await flush()
  assert.deepEqual(copy.shownBookmarks.map((b) => b.title), ['Copy server', 'Added offline'], 'a new bookmark of the copy shows up')
  component.beforeDestroy.call(copy)
  assert.equal(listener, null)

  asked.length = 0
  for (const serverConfigId of [null, 'server-a']) {
    const active = view(serverConfigId)
    await active.loadCopyBookmarks()
    assert.deepEqual(active.shownBookmarks.map((b) => b.title), ['From store'], `${serverConfigId}: the store list`)
  }
  assert.deepEqual(asked, [], 'no extra read for the active server')
})

test('a downloaded copy played offline keeps its server id, so the player shows the bookmark button', async () => {
  const container = (await readFile(new URL('../components/app/AudioPlayerContainer.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  const sandbox = { console }
  vm.runInNewContext(container.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =').replace(/mixins: \[[^\]]*\],/, ''), sandbox)
  const { setServerIds } = sandbox.component.methods
  const session = (server, episodeId = null) => ({ libraryItemId: 'li-server', episodeId, localLibraryItem: { id: 'local_1', libraryItemId: 'li-server', serverConnectionConfigId: server } })
  const run = (currentPlaybackSession, args, previous = ['li-previous', 'ep-previous']) => {
    const ctx = { currentPlaybackSession, $store: { state: { activeServerConfigId: 'server-a' } }, serverLibraryItemId: previous[0], serverEpisodeId: previous[1] }
    setServerIds.apply(ctx, args)
    return [ctx.serverLibraryItemId, ctx.serverEpisodeId]
  }

  // Offline cold start: the page could not tell the server ids (no current server); the copy's own session has them
  assert.deepEqual(run(session('server-a'), ['local_1', null, null, null]), ['li-server', null])
  assert.deepEqual(run(session('server-a', 'ep-server'), ['local_1', 'local_ep', null, null]), ['li-server', 'ep-server'], 'the pair comes from the session')
  // A copy of another saved server: none, as before (no bookmark button, no cast to the wrong server)
  assert.deepEqual(run(session('server-b'), ['local_1', null, null, null]), [null, null])
  // A session of something else still in the store is not taken, and nothing is left over from before
  assert.deepEqual(run({ ...session('server-a'), localLibraryItem: { id: 'local_other', serverConnectionConfigId: 'server-a' } }, ['local_1', null, null, null]), [null, null])
  // Ids the page passed are used as a pair; server items as before
  assert.deepEqual(run(session('server-a', 'ep-server'), ['local_1', 'local_ep', 'li-page', 'ep-page']), ['li-page', 'ep-page'])
  assert.deepEqual(run(session('server-a', 'ep-server'), ['local_1', 'local_ep', 'li-page', null]), ['li-page', null], 'not mixed with the session episode')
  assert.deepEqual(run(null, ['li-stream', 'ep-stream', null, null]), ['li-stream', 'ep-stream'])
})

test('a session arriving for a copy of another server sets no server ids', async () => {
  const container = (await readFile(new URL('../components/app/AudioPlayerContainer.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  const sandbox = { console }
  vm.runInNewContext(container.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =').replace(/mixins: \[[^\]]*\],/, ''), sandbox)
  const watcher = sandbox.component.watch.currentPlaybackSession
  const arrive = (currentPlaybackSession) => {
    const ctx = { currentPlaybackSession, $store: { state: { activeServerConfigId: 'server-a' } }, onPlaybackEnded() {}, serverLibraryItemId: 'li-previous', serverEpisodeId: 'ep-previous' }
    ;(watcher.handler || watcher).call(ctx)
    return [ctx.serverLibraryItemId, ctx.serverEpisodeId]
  }
  const copy = (server) => ({ libraryItemId: 'li-server', episodeId: 'ep-server', localLibraryItem: { id: 'local_1', serverConnectionConfigId: server } })

  assert.deepEqual(arrive(copy('server-b')), [null, null])
  assert.deepEqual(arrive(copy(null)), [null, null])
  assert.deepEqual(arrive(copy('server-a')), ['li-server', 'ep-server'])
  assert.deepEqual(arrive({ libraryItemId: 'li-stream', episodeId: null }), ['li-stream', null], 'a stream as before')
  assert.deepEqual(arrive(null), [null, null])
})
