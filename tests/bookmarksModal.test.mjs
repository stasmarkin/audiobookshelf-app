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
