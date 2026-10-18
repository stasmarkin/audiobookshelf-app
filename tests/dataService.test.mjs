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
  await assert.rejects(busy.$nativeHttp.get('/api/libraries'))
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
