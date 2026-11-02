// Server reachability in the WebView: $absData (utils/dataService.js) mirrors the native AbsData state into the store, and
// the two JS transports that talk to the server (plugins/nativeHttp.js, the socket in plugins/server.js) report what
// they saw to native. Native decides whether a report is about the active server. Fakes only, synthetic servers.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { EventEmitter } from 'node:events'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const { createDataService } = await import(`data:text/javascript;base64,${Buffer.from(await read('../utils/dataService.js')).toString('base64')}`)

/** Loads a Nuxt plugin source with its named and default imports replaced by the given fakes. */
async function loadPlugin(path, deps) {
  let src = await read(path)
  src = src.replace(/^import \{([^}]+)\} from '[^']+'\n/gm, (_, names) => `const {${names}} = __deps\n`)
  src = src.replace(/^import (\w+) from '[^']+'\n/gm, (_, name) => `const ${name} = __deps.${name}\n`)
  src = src.replace('export default ', 'return ')
  return new Function('__deps', src)(deps)
}

const flush = async (n = 10) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

function fakeStore() {
  const store = { state: { serverState: 'unknown', activeServerConfigId: null, user: { serverConnectionConfig: null } }, commits: [], getters: {} }
  store.commit = (type, payload) => {
    store.commits.push([type, payload])
    if (type === 'setServerState') store.state.serverState = payload
    if (type === 'setActiveServerConfigId') store.state.activeServerConfigId = payload
  }
  return store
}

/** The native AbsData plugin: records reports and lets the test send onServerState. */
function fakeAbsData(initial = { state: 'unknown', serverConfigId: null }) {
  const listeners = {}
  const plugin = {
    reports: [],
    current: initial,
    async getServerState() {
      return plugin.current
    },
    async retryServer() {
      plugin.reports.push(['retry'])
    },
    async reportServerResponse(payload) {
      plugin.reports.push(['response', payload])
    },
    async reportServerUnreachable(payload) {
      plugin.reports.push(['unreachable', payload])
    },
    async reportSocketLost() {
      plugin.reports.push(['socketLost'])
    },
    addListener(name, cb) {
      ;(listeners[name] ||= []).push(cb)
      return { remove: () => {} }
    },
    send(name, payload) {
      ;(listeners[name] || []).forEach((cb) => cb(payload))
    }
  }
  return plugin
}

test('dataService forwards server state events to the store', async () => {
  const store = fakeStore()
  const plugin = fakeAbsData({ state: 'online', serverConfigId: 'server-a' })
  const $data = createDataService({ plugin, store })
  await flush()
  assert.equal(store.state.serverState, 'online', 'asks native once on creation, so the indicator is not hidden at start')
  assert.equal(store.state.activeServerConfigId, 'server-a')

  const seen = []
  $data.onServerState((payload) => seen.push(payload.state))
  plugin.send('onServerState', { state: 'offline', serverConfigId: 'server-a' })
  assert.deepEqual(store.commits.at(-2), ['setServerState', 'offline'])
  assert.deepEqual(seen, ['offline'])

  plugin.send('onServerState', { state: 'unknown', serverConfigId: null })
  assert.equal(store.state.activeServerConfigId, null)

  await $data.retryServer()
  assert.deepEqual(plugin.reports, [['retry']])
})

test('an initial state answer older than an event does not overwrite it', async () => {
  const store = fakeStore()
  const plugin = fakeAbsData({ state: 'unknown', serverConfigId: 'server-a' })
  let answer
  plugin.getServerState = () => new Promise((resolve) => (answer = resolve))
  createDataService({ plugin, store })
  plugin.send('onServerState', { state: 'offline', serverConfigId: 'server-a' })
  answer({ state: 'unknown', serverConfigId: 'server-a' })
  await flush()
  assert.equal(store.state.serverState, 'offline')
})

// nativeHttp

async function nativeHttp({ respond, AbsData = fakeAbsData(), renewed = 'access-1' } = {}) {
  const sent = []
  const CapacitorHttp = {
    async request(req) {
      sent.push(req)
      return respond(req, sent.length)
    }
  }
  const store = { state: { user: { serverConnectionConfig: { id: 'server-a', address: 'https://a.test.invalid/abs' } } }, getters: { 'user/getToken': 'access-0' } }
  const ctx = { store, $authRenewal: { renew: async () => renewed } }
  const plugin = await loadPlugin('../plugins/nativeHttp.js', { CapacitorHttp, isRenewableUrl: () => true, AbsData })
  plugin(ctx, (name, value) => (ctx[`$${name}`] = value))
  return { $nativeHttp: ctx.$nativeHttp, AbsData, sent }
}

const javaError = (code) => Object.assign(new Error(`synthetic ${code}`), { code })

test('nativeHttp reports an unreachable server on a network exception and a response on any status', async () => {
  const refused = javaError('ConnectException')
  const down = await nativeHttp({
    respond: () => {
      throw refused
    }
  })
  await assert.rejects(down.$nativeHttp.get('/api/me'), (e) => e === refused, 'the original exception is rethrown unchanged')
  await flush()
  assert.deepEqual(down.AbsData.reports, [['unreachable', { address: 'https://a.test.invalid/abs/api/me' }]])

  const busy = await nativeHttp({ respond: () => ({ status: 503, data: 'Service Unavailable' }) })
  await assert.rejects(busy.$nativeHttp.get('/api/libraries'), (error) => error.status === 503, 'the error carries the status')
  await flush()
  assert.deepEqual(busy.AbsData.reports, [['response', { address: 'https://a.test.invalid/abs/api/libraries', status: 503 }]])

  const expired = await nativeHttp({ respond: (req) => (req.headers.Authorization === 'Bearer access-1' ? { status: 200, data: { ok: true } } : { status: 401, data: 'Unauthorized' }) })
  assert.deepEqual(await expired.$nativeHttp.get('/api/me'), { ok: true })
  await flush()
  assert.deepEqual(expired.AbsData.reports, [
    ['response', { address: 'https://a.test.invalid/abs/api/me', status: 401 }],
    ['response', { address: 'https://a.test.invalid/abs/api/me', status: 200 }]
  ])

  const authorize = await nativeHttp({ respond: () => ({ status: 200, data: { user: {} } }) })
  await authorize.$nativeHttp.post('https://b.test.invalid/api/authorize', null, { connectTimeout: 6000, serverConnectionConfig: { id: 'server-b', address: 'https://b.test.invalid' } })
  await flush()
  assert.deepEqual(authorize.AbsData.reports, [['response', { address: 'https://b.test.invalid/api/authorize', status: 200 }]], 'native decides whether this is the active server')
})

test('nativeHttp does not report request errors that say nothing about the server, or non-http urls', async () => {
  for (const code of ['JSONException', 'MalformedURLException', 'ProtocolException', undefined]) {
    const broken = await nativeHttp({
      respond: () => {
        throw javaError(code)
      }
    })
    await assert.rejects(broken.$nativeHttp.get('/api/me'))
    await flush()
    assert.deepEqual(broken.AbsData.reports, [], `${code} is not unreachable`)
  }
  for (const code of ['SocketTimeoutException', 'UnknownHostException', 'NoRouteToHostException', 'SSLHandshakeException', 'SocketException', 'IOException', 'EOFException']) {
    const down = await nativeHttp({
      respond: () => {
        throw javaError(code)
      }
    })
    await assert.rejects(down.$nativeHttp.get('/api/me'))
    await flush()
    assert.equal(down.AbsData.reports.length, 1, `${code} is unreachable`)
  }

  const local = await nativeHttp({ respond: () => ({ status: 200, data: 'ok' }) })
  await local.$nativeHttp.get('capacitor://localhost/_capacitor_file_/x')
  await flush()
  assert.deepEqual(local.AbsData.reports, [])
})

test('a failing report never breaks the request', async () => {
  const AbsData = fakeAbsData()
  AbsData.reportServerResponse = async () => {
    throw new Error('plugin not implemented')
  }
  const { $nativeHttp } = await nativeHttp({ AbsData, respond: () => ({ status: 200, data: { ok: true } }) })
  assert.deepEqual(await $nativeHttp.get('/api/me'), { ok: true })
  await flush()
})

// Socket

async function socket() {
  const plugin = await loadPlugin('../plugins/server.js', { io: () => null, EventEmitter })
  const reports = []
  const app = {}
  const store = { commit() {}, getters: { 'user/getToken': 'access-0' }, state: { libraries: {} } }
  const ctx = { app, store }
  plugin(ctx, (name, value) => (ctx[`$${name}`] = value))
  // Nuxt injects $absData after server.js runs; the socket must read it at event time
  app.$absData = { reportServerResponse: (payload) => reports.push(['response', payload]), reportSocketLost: () => reports.push(['socketLost']) }
  const s = ctx.$socket
  s.serverAddress = 'https://a.test.invalid/abs'
  s.socket = { id: 'socket-1', emit() {} }
  return { s, reports }
}

test('socket connect counts as a server answer and disconnect asks for a probe', async () => {
  const { s, reports } = await socket()
  s.onConnect()
  assert.deepEqual(reports, [['response', { address: 'https://a.test.invalid/abs', status: 200 }]])
  s.onDisconnect('transport close')
  assert.deepEqual(reports.at(-1), ['socketLost'])
  assert.equal(reports.length, 2)
})

test('a deliberate socket logout does not ask for a probe', async () => {
  const { s, reports } = await socket()
  s.onDisconnect('io client disconnect')
  assert.deepEqual(reports, [])
})

// Bookmarks and sync state (offline-first 2a): native userdata.db is the source, the store follows onDataChanged

function bookmarkStore() {
  const store = fakeStore()
  const commit = store.commit
  store.commit = (type, payload) => {
    commit(type, payload)
    if (type === 'user/setBookmarks') store.state.bookmarks = payload
  }
  return store
}

function nativeBookmarks(list = []) {
  const plugin = fakeAbsData({ state: 'offline', serverConfigId: 'server-a' })
  plugin.calls = []
  plugin.bookmarks = list
  plugin.getBookmarks = async () => {
    plugin.calls.push(['getBookmarks'])
    return { bookmarks: plugin.bookmarks }
  }
  for (const name of ['addBookmark', 'renameBookmark', 'removeBookmark', 'syncNow', 'applyServerEvent']) {
    plugin[name] = async (args) => {
      plugin.calls.push([name, args])
      return name === 'applyServerEvent' ? { applied: true } : undefined
    }
  }
  plugin.getSyncState = async () => ({ running: false, pendingCount: 2, lastError: null, lastSyncAt: null })
  return plugin
}

test('dataService reloads bookmarks into the store on onDataChanged bookmarks', async () => {
  const store = bookmarkStore()
  const plugin = nativeBookmarks([{ libraryItemId: 'li1', time: 754, title: 'Mark', createdAt: 1 }])
  const $absData = createDataService({ plugin, store, platform: 'android' })
  const seen = []
  $absData.onDataChanged((payload) => seen.push(payload.scope))
  await flush()

  plugin.send('onDataChanged', { scope: 'progress', ids: ['li1|'] })
  await flush()
  assert.deepEqual(plugin.calls, [], 'progress does not reread bookmarks')

  plugin.send('onDataChanged', { scope: 'bookmarks', ids: ['li1'] })
  await flush()
  assert.deepEqual(plugin.calls, [['getBookmarks']])
  assert.deepEqual(store.state.bookmarks, plugin.bookmarks)
  assert.deepEqual(seen, ['progress', 'bookmarks'])
})

test('bookmark writes go to native, with the server of a downloaded copy only when given', async () => {
  const store = bookmarkStore()
  const plugin = nativeBookmarks()
  const $absData = createDataService({ plugin, store, platform: 'android' })

  await $absData.addBookmark({ libraryItemId: 'li1', time: 754, title: 'Mark' })
  await $absData.renameBookmark({ libraryItemId: 'li1', time: 754, title: 'Renamed', serverConfigId: 'server-b' })
  await $absData.removeBookmark({ libraryItemId: 'li1', time: 754, serverConfigId: null })

  assert.deepEqual(plugin.calls, [
    ['addBookmark', { libraryItemId: 'li1', time: 754, title: 'Mark' }],
    ['renameBookmark', { libraryItemId: 'li1', time: 754, title: 'Renamed', serverConfigId: 'server-b' }],
    ['removeBookmark', { libraryItemId: 'li1', time: 754 }]
  ])
})

test('sync state from native reaches the store and subscribers', async () => {
  const store = bookmarkStore()
  const plugin = nativeBookmarks()
  const $absData = createDataService({ plugin, store, platform: 'android' })
  await flush()
  assert.deepEqual(store.commits.find(([type]) => type === 'setSyncState'), ['setSyncState', { running: false, pendingCount: 2, lastError: null, lastSyncAt: null }], 'read once on creation')

  const seen = []
  $absData.onSyncState((s) => seen.push(s.running))
  plugin.send('onSyncState', { running: true, pendingCount: 2, lastError: null, lastSyncAt: null })
  assert.deepEqual(store.commits.at(-1), ['setSyncState', { running: true, pendingCount: 2, lastError: null, lastSyncAt: null }])
  assert.deepEqual(seen, [true])

  await $absData.syncNow()
  await $absData.syncNow({ reason: 'socket-init' })
  assert.deepEqual(plugin.calls, [
    ['syncNow', { reason: 'manual' }],
    ['syncNow', { reason: 'socket-init' }]
  ])
  assert.deepEqual(await $absData.applyServerEvent({ event: 'user_updated', payload: { bookmarks: [] }, serverConfigId: 'server-a' }), { applied: true })
  assert.deepEqual(plugin.calls.at(-1), ['applyServerEvent', { event: 'user_updated', payload: { bookmarks: [] }, serverConfigId: 'server-a' }])
})

test('web dev fallback proxies bookmarks to $axios and refreshes the store', async () => {
  const store = bookmarkStore()
  const plugin = nativeBookmarks()
  const server = [{ libraryItemId: 'li1', time: 10, title: 'Old', createdAt: 1 }]
  const axiosCalls = []
  const $axios = {
    async $get(url) {
      axiosCalls.push(['get', url])
      return { id: 'user', bookmarks: [...server] }
    },
    async $post(url, body) {
      axiosCalls.push(['post', url, body])
      server.push({ libraryItemId: 'li1', ...body, createdAt: 2 })
    },
    async $patch(url, body) {
      axiosCalls.push(['patch', url, body])
    },
    async $delete(url) {
      axiosCalls.push(['delete', url])
    }
  }
  const $absData = createDataService({ plugin, store, platform: 'web', $axios })
  const seen = []
  $absData.onDataChanged((payload) => seen.push(payload.scope))

  await $absData.addBookmark({ libraryItemId: 'li1', time: 754, title: 'New' })
  await flush()
  assert.deepEqual(axiosCalls.slice(0, 2), [
    ['post', '/api/me/item/li1/bookmark', { time: 754, title: 'New' }],
    ['get', '/api/me']
  ])
  assert.deepEqual(
    store.state.bookmarks.map((b) => b.time),
    [10, 754]
  )
  assert.deepEqual(plugin.calls, [], 'the native plugin is not used in the browser')

  await $absData.renameBookmark({ libraryItemId: 'li1', time: 10, title: 'Renamed' })
  await $absData.removeBookmark({ libraryItemId: 'li1', time: 10 })
  await flush()
  assert.deepEqual(axiosCalls.filter(([m]) => m !== 'get'), [
    ['post', '/api/me/item/li1/bookmark', { time: 754, title: 'New' }],
    ['patch', '/api/me/item/li1/bookmark', { time: 10, title: 'Renamed' }],
    ['delete', '/api/me/item/li1/bookmark/10']
  ])

  const pushed = [{ libraryItemId: 'li2', time: 5, title: 'From socket', createdAt: 3 }]
  assert.deepEqual(await $absData.applyServerEvent({ event: 'user_updated', payload: { bookmarks: pushed }, serverConfigId: 'server-a' }), { applied: true })
  assert.deepEqual(store.state.bookmarks, pushed)
  assert.equal(seen.at(-1), 'bookmarks')
  await $absData.syncNow()
  assert.deepEqual(plugin.calls, [])
})

test('bookmark getter works without a signed-in user', async () => {
  let src = await read('../store/user.js')
  src = src.replace(/^import .*\n/gm, '').replace(/^export const (\w+) =/gm, 'exports.$1 =')
  const exports = {}
  new Function('exports', 'AbsLogger', src)(exports, { info: async () => {} })
  const state = exports.state()
  assert.deepEqual(state.bookmarks, [])
  state.user = null
  exports.mutations.setBookmarks(state, [
    { libraryItemId: 'li1', time: 1, title: 'A' },
    { libraryItemId: 'li2', time: 2, title: 'B' }
  ])
  assert.deepEqual(
    exports.getters.getUserBookmarksForItem(state)('li1').map((b) => b.title),
    ['A']
  )
  exports.mutations.setBookmarks(state, null)
  assert.deepEqual(exports.getters.getUserBookmarksForItem(state)('li1'), [])
})

test('socket init triggers user reconcile', async () => {
  const { s } = await socket()
  const synced = []
  s.app.$absData.syncNow = async (args) => synced.push(args)
  s.onInit({})
  assert.deepEqual(synced, [{ reason: 'socket-init' }])
})

test('bookmarks read for one server are not committed after a switch to another', async () => {
  const store = bookmarkStore()
  store.state.activeServerConfigId = 'server-a'
  const plugin = nativeBookmarks()
  const answers = []
  plugin.getBookmarks = (args) => new Promise((resolve) => answers.push({ args, resolve }))
  const $absData = createDataService({ plugin, store, platform: 'android' })

  const reload = $absData.reloadBookmarks()
  await flush()
  store.state.activeServerConfigId = 'server-b'
  answers[0].resolve({ bookmarks: [{ libraryItemId: 'li1', time: 1, title: 'Of server A' }] })
  await flush()
  assert.equal(store.state.bookmarks, undefined, 'the answer about server A is not committed')
  assert.equal(answers.length, 2, 'the list is asked again for the server now active')
  answers[1].resolve({ bookmarks: [{ libraryItemId: 'li2', time: 2, title: 'Of server B' }] })
  await reload
  assert.deepEqual(store.state.bookmarks.map((b) => b.title), ['Of server B'])

  answers.length = 0
  const copy = $absData.getBookmarks({ serverConfigId: 'server-c' })
  await flush()
  assert.deepEqual(answers[0].args, { serverConfigId: 'server-c' })
  answers[0].resolve({ bookmarks: [] })
  assert.deepEqual(await copy, [])
})
