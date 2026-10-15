// Reconnecting when the server comes back (layouts/default.vue): the real watchers, mounted and initLibraries of the
// layout run against a fake store, $absData and timers. The trigger is the server becoming reachable (serverState from
// native AbsData), not the network returning: on a live network with the server down there is nothing to retry.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const source = (await readFile(new URL('../layouts/default.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
const script = source
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '')
  .replace('export default', 'globalThis.component =')

const flush = async (n = 20) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

function layout({ serverState = 'offline', networkConnected = true, user = null, currentLibraryId = 'lib-1', getServerState, isFirstLoad = true, signsIn = false } = {}) {
  const timers = []
  const sandbox = {
    AbsLogger: { info: async () => {}, error: async () => {} },
    CapacitorHttp: {},
    console: { ...console, log: () => {}, warn: () => {} },
    setTimeout: (fn, ms) => timers.push({ fn, ms }) && timers.at(-1),
    clearTimeout: (t) => timers.includes(t) && timers.splice(timers.indexOf(t), 1),
    document: { addEventListener() {}, removeEventListener() {} }
  }
  vm.runInNewContext(script, sandbox)
  const component = sandbox.component

  const calls = []
  const emitted = []
  const store = {
    state: { serverState, networkConnected, socketConnected: false, attemptingConnection: false, isFirstLoad, user: { user, serverConnectionConfig: null }, libraries: { currentLibraryId } },
    getters: { getIsPlayerOpen: false, 'libraries/getCurrentLibraryName': 'Library', getOrientationLockSetting: null },
    commit(type, payload) {
      if (type === 'setAttemptingConnection') store.state.attemptingConnection = payload
    },
    async dispatch(type, payload) {
      calls.push(['dispatch', type, payload])
    }
  }
  const view = {
    ...component.data(),
    $store: store,
    $route: { name: 'bookshelf' },
    $eventBus: { $emit: (name) => emitted.push(name), $on() {}, $off() {} },
    $socket: { on() {}, off() {} },
    $localStore: { getServerSettings: async () => null },
    $db: { getDeviceData: async () => ({ deviceSettings: {} }) },
    $absData: {
      getServerState:
        getServerState ||
        (async () => {
          calls.push('getServerState')
          return { state: store.state.serverState }
        }),
      // Arrays, so the string-only filters below keep listing just the connection steps
      reloadUserData: async () => calls.push(['reloadUserData']),
      applyServerEvent: async (args) => calls.push(['applyServerEvent', args])
    },
    $setOrientationLock() {},
    $platform: 'android',
    $config: { version: 'test' }
  }
  for (const [name, def] of Object.entries(component.computed)) {
    Object.defineProperty(view, name, { get: (def.get || def).bind(view), set: def.set?.bind(view) })
  }
  for (const [name, fn] of Object.entries(component.methods)) view[name] = fn.bind(view)
  // Spies for the heavy work; initLibraries stays real so the shelves' refresh event is observable
  view.attemptConnection = async () => {
    calls.push('attemptConnection')
    if (signsIn) store.state.user.user = { id: 'user-1' }
  }
  view.syncLocalSessions = async (isFirstSync) => calls.push(['syncLocalSessions', isFirstSync])
  view.loadSavedSettings = async () => {}

  const watch = (name, newVal, oldVal) => {
    const w = component.watch[name]
    return (w.handler || w).call(view, newVal, oldVal)
  }
  const runTimers = () => timers.splice(0).forEach((t) => t.fn())
  return { view, store, calls, emitted, watch, runTimers, timers, component, mounted: () => component.mounted.call(view) }
}

test('server coming online retries authorization when there is no user and syncs sessions when there is', async () => {
  const anonymous = layout({ user: null })
  anonymous.view.hasMounted = true
  anonymous.store.state.serverState = 'online'
  anonymous.watch('serverState', 'online', 'offline')
  await flush()
  anonymous.runTimers()
  assert.deepEqual(anonymous.calls, ['attemptConnection'])

  const busy = layout({ user: null })
  busy.view.hasMounted = true
  busy.store.state.attemptingConnection = true
  busy.watch('serverState', 'online', 'offline')
  await flush()
  assert.deepEqual(busy.calls, [], 'a connection attempt already in progress is not doubled')

  const signedIn = layout({ user: { id: 'user-1' } })
  signedIn.view.hasMounted = true
  signedIn.watch('serverState', 'online', 'offline')
  await flush()
  signedIn.runTimers()
  await flush()
  assert.deepEqual(
    signedIn.calls.filter((c) => c[0] === 'syncLocalSessions'),
    [['syncLocalSessions', false]]
  )
  assert.deepEqual(signedIn.emitted, ['server-reachable'], 'Home re-reads its shelves')
  assert.deepEqual(
    signedIn.calls.filter((c) => c[0] === 'dispatch').map((c) => c[1]),
    [],
    'libraries come from the replica; the reconcile of this return updates them through a catalog notice'
  )
  assert.ok(!signedIn.emitted.includes('library-changed'), 'open pages and filters stay where they are')

  const noLibrary = layout({ user: { id: 'user-1' }, currentLibraryId: null })
  noLibrary.view.hasMounted = true
  noLibrary.watch('serverState', 'online', 'offline')
  await flush()
  assert.deepEqual(noLibrary.emitted, ['library-changed'], 'without a library yet, libraries are loaded as before')

  const steady = layout({ user: { id: 'user-1' } })
  steady.view.hasMounted = true
  steady.watch('serverState', 'online', 'online')
  steady.watch('serverState', 'offline', 'online')
  await flush()
  steady.runTimers()
  assert.deepEqual(steady.calls, [])
  assert.deepEqual(steady.emitted, [])
})

test('network returning while the server is offline does nothing by itself', async () => {
  for (const user of [null, { id: 'user-1' }]) {
    const l = layout({ user, serverState: 'offline' })
    l.view.hasMounted = true
    l.watch('networkConnected', true, false)
    await flush()
    l.runTimers()
    assert.deepEqual(l.calls, [])
    assert.deepEqual(l.emitted, [])
  }
})

test('nothing reconnects before the layout has mounted', async () => {
  const l = layout({ user: null })
  l.watch('serverState', 'online', 'offline')
  await flush()
  assert.deepEqual(l.calls, [])
})

test('startup reads the server state first and waits for the server when native already knows it is down', async () => {
  const down = layout({ serverState: 'offline' })
  await down.mounted()
  assert.deepEqual(
    down.calls.filter((c) => typeof c === 'string'),
    ['getServerState']
  )
  assert.equal(down.view.hasMounted, true)

  const unknown = layout({ serverState: 'unknown' })
  await unknown.mounted()
  assert.deepEqual(
    unknown.calls.filter((c) => typeof c === 'string'),
    ['getServerState', 'attemptConnection']
  )

  const failing = layout({
    serverState: 'online',
    getServerState: async () => {
      throw new Error('plugin missing')
    }
  })
  await failing.mounted()
  assert.ok(failing.calls.includes('attemptConnection'), 'a failed state read does not block the connection')
})

test('Home re-reads its shelves when the server is reachable again, without leaving the page', async () => {
  const page = (await readFile(new URL('../pages/bookshelf/index.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  const sandbox = { console: { ...console, log: () => {} } }
  vm.runInNewContext(page.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component ='), sandbox)
  const handlers = {}
  const fetched = []
  const view = { $eventBus: { $on: (name, fn) => (handlers[name] = fn), $off() {} }, currentLibraryId: 'lib-1', fetchCategories: () => fetched.push(true) }
  for (const [name, fn] of Object.entries(sandbox.component.methods)) if (!(name in view)) view[name] = fn.bind(view)
  view.initListeners()
  handlers['server-reachable']()
  assert.equal(fetched.length, 1)
})

test('after an offline cold start, the automatic sign-in also syncs local sessions', async () => {
  const l = layout({ serverState: 'offline', signsIn: true })
  await l.mounted()
  assert.ok(!l.calls.includes('attemptConnection'))
  l.store.state.serverState = 'online'
  l.watch('serverState', 'online', 'offline')
  await flush()
  assert.deepEqual(
    l.calls.filter((c) => typeof c === 'string' || c[0] === 'syncLocalSessions'),
    ['getServerState', ['syncLocalSessions', true], 'attemptConnection', ['syncLocalSessions', true]],
    'the startup sync was skipped without a user, so this one is the first'
  )

  const online = layout({ serverState: 'unknown', signsIn: true })
  await online.mounted()
  online.store.state.user.user = null // signed out later
  online.watch('serverState', 'online', 'offline')
  await flush()
  assert.deepEqual(online.calls.filter((c) => c[0] === 'syncLocalSessions').at(-1), ['syncLocalSessions', false])
})

test('a remounted layout (back from /connect) still reconnects', async () => {
  const l = layout({ isFirstLoad: false, user: null })
  await l.mounted()
  assert.equal(l.view.hasMounted, true)
  l.watch('serverState', 'online', 'offline')
  await flush()
  assert.deepEqual(l.calls, ['attemptConnection'])
})

test('the server answering before Android reports the network still connects, in either order', async () => {
  const serverFirst = layout({ user: null, networkConnected: false })
  serverFirst.view.hasMounted = true
  serverFirst.store.state.serverState = 'online'
  serverFirst.watch('serverState', 'online', 'offline') // attemptConnection bails out: no network yet (real code)
  serverFirst.calls.length = 0
  serverFirst.store.state.networkConnected = true
  serverFirst.watch('networkConnected', true, false)
  await flush()
  assert.deepEqual(serverFirst.calls, ['attemptConnection'])

  const networkFirst = layout({ user: null, networkConnected: false, serverState: 'offline' })
  networkFirst.view.hasMounted = true
  networkFirst.store.state.networkConnected = true
  networkFirst.watch('networkConnected', true, false)
  networkFirst.store.state.serverState = 'online'
  networkFirst.watch('serverState', 'online', 'offline')
  await flush()
  assert.deepEqual(networkFirst.calls, ['attemptConnection'])

  const signedIn = layout({ user: { id: 'user-1' }, serverState: 'online' })
  signedIn.view.hasMounted = true
  signedIn.watch('networkConnected', true, false)
  await flush()
  assert.deepEqual(signedIn.calls, [], 'a signed-in user waits for the server, not the network')
})

test('Authors re-reads its list on the catalog notice of the reconcile the return starts', async () => {
  const page = (await readFile(new URL('../pages/bookshelf/authors.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  const timers = []
  const sandbox = {
    console: { ...console, log: () => {} },
    shouldRefreshShelf: ({ ids }, { libraryId }) => ids.includes(`authors:${libraryId}`),
    createDebouncer: () => ({ schedule: (fn) => timers.push(fn), cancel: () => timers.splice(0) })
  }
  vm.runInNewContext(page.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component ='), sandbox)
  const handlers = {}
  const dataListeners = new Set()
  let inits = 0
  const view = {
    $eventBus: { $on: (name, fn) => (handlers[name] = fn), $off: (name) => delete handlers[name] },
    $absData: { onDataChanged: (cb) => (dataListeners.add(cb), () => dataListeners.delete(cb)) },
    currentLibraryId: 'lib-1'
  }
  for (const [name, fn] of Object.entries(sandbox.component.methods)) view[name] = fn.bind(view)
  view.init = () => inits++
  sandbox.component.mounted.call(view)
  assert.equal(inits, 1)
  assert.equal(handlers['server-reachable'], undefined, 'the server coming back alone reads nothing')
  dataListeners.forEach((cb) => cb({ scope: 'catalog', ids: ['authors:lib-1'] }))
  timers.shift()()
  assert.equal(inits, 2)
  sandbox.component.beforeDestroy.call(view)
  assert.equal(dataListeners.size, 0)
})

// The libraries follow the catalog notices of native ($absData dataChanged), not the server coming back
const { createDataService } = await import(`data:text/javascript;base64,${Buffer.from((await readFile(new URL('../utils/dataService.js', import.meta.url), 'utf8'))).toString('base64')}`)

function catalogService({ currentLibraryId = 'lib-1', onLoad = () => {} } = {}) {
  const listeners = {}
  const plugin = {
    addListener: (name, cb) => ((listeners[name] = cb), { remove() {} }),
    getServerState: async () => ({ state: 'online', serverConfigId: 'a' })
  }
  const dispatched = []
  const emitted = []
  const store = {
    state: { serverState: 'online', activeServerConfigId: 'a', user: {}, libraries: { currentLibraryId } },
    commit() {},
    async dispatch(type, payload) {
      dispatched.push([type, payload])
      if (type === 'libraries/load') onLoad(store)
    }
  }
  createDataService({ plugin, store, eventBus: { $emit: (name) => emitted.push(name) } })
  return { send: (payload) => listeners.onDataChanged(payload), dispatched, emitted, store }
}

test('a library removed while the server was away still switches pages to the new one', async () => {
  for (const [switched, expected] of [
    [true, ['library-changed']],
    [false, []]
  ]) {
    const c = catalogService({ onLoad: (store) => switched && (store.state.libraries.currentLibraryId = 'lib-2') })
    c.send({ scope: 'catalog', ids: ['libraries'] })
    await flush()
    assert.deepEqual(c.emitted, expected, `library switched by load: ${switched}`)
    assert.deepEqual(JSON.parse(JSON.stringify(c.dispatched[0])), ['libraries/load', { force: true }])
    assert.equal(c.dispatched.some(([type, payload]) => type === 'libraries/fetch' && payload === 'lib-2'), switched)
  }
})

test('a background library re-read does not override a library the user picked meanwhile', async () => {
  const storeSource = (await readFile(new URL('../store/libraries.js', import.meta.url), 'utf8')).replace(/^import .*$/gm, '').replace(/^export const (\w+) =/gm, 'globalThis.$1 =')
  const sandbox = { console: { ...console, error: () => {} }, require: () => ({ BookCoverAspectRatio: {} }) }
  vm.runInNewContext(storeSource, sandbox)
  const run = async (payload, switchTo) => {
    let answer
    const commits = []
    const state = { currentLibraryId: 'lib-a' }
    const store = { $absData: { getLibrary: () => new Promise((resolve) => (answer = resolve)), setCurrentLibrary: async () => {} } }
    const ctx = { state, rootGetters: { getHasActiveServer: true }, dispatch() {}, commit: (type, value) => commits.push(type) && type === 'setCurrentLibrary' && (state.currentLibraryId = value) }
    const done = sandbox.actions.fetch.call(store, ctx, payload)
    if (switchTo) state.currentLibraryId = switchTo
    answer({ library: { id: 'lib-a', mediaType: 'book' }, filterdata: {} })
    await done
    return { commits, current: state.currentLibraryId }
  }
  const late = await run({ libraryId: 'lib-a', onlyIfCurrent: true }, 'lib-b')
  assert.deepEqual(late, { commits: [], current: 'lib-b' })
  const fresh = await run({ libraryId: 'lib-a', onlyIfCurrent: true })
  assert.ok(fresh.commits.includes('setLibraryFilterData'))
  const picked = await run('lib-a', 'lib-b')
  assert.equal(picked.current, 'lib-a', 'an explicit pick still applies as before')

  // A change of the current library's filterdata re-reads it in the background
  const c = catalogService({ currentLibraryId: 'lib-1' })
  c.send({ scope: 'catalog', ids: ['library:lib-1'] })
  await flush()
  assert.deepEqual(JSON.parse(JSON.stringify(c.dispatched)), [['libraries/fetch', { libraryId: 'lib-1', onlyIfCurrent: true }]])
  const other = catalogService({ currentLibraryId: 'lib-1' })
  other.send({ scope: 'catalog', ids: ['library:lib-9', 'items:lib-1'] })
  await flush()
  assert.deepEqual(other.dispatched, [], "another library's topics do not refetch the current one")
})

// Bookmarks (offline-first 2a): kept natively, so a cold start without the server shows them; the server's copy arrives
// with the user object (authorize, socket user_updated) and is handed to native

const plain = (value) => JSON.parse(JSON.stringify(value)) // objects made inside the vm sandbox
const order = (calls) => calls.filter((c) => typeof c === 'string' || c[0] === 'reloadUserData').map((c) => (typeof c === 'string' ? c : c[0]))

test('a cold start loads saved progress and bookmarks before connecting, also with the server unreachable', async () => {
  const offline = layout({ serverState: 'offline' })
  await offline.mounted()
  assert.deepEqual(order(offline.calls), ['getServerState', 'reloadUserData'])

  const unknown = layout({ serverState: 'unknown' })
  await unknown.mounted()
  assert.deepEqual(order(unknown.calls), ['getServerState', 'reloadUserData', 'attemptConnection'])

  const failing = layout({ serverState: 'unknown' })
  failing.view.$absData.reloadUserData = async () => {
    throw new Error('plugin not implemented')
  }
  await failing.mounted()
  assert.ok(failing.calls.includes('attemptConnection'), 'a failed read does not block the connection')
})

test('user_updated is debounced and forwarded once', async () => {
  const l = layout({ user: { id: 'user-1' } })
  const commits = []
  l.store.commit = (type, payload) => commits.push([type, payload])
  l.store.state.activeServerConfigId = 'server-a'

  for (const n of [1, 2, 3]) l.view.userUpdated({ id: 'user-1', n })
  l.view.userUpdated({ id: 'someone-else' })
  assert.deepEqual(
    commits.map(([type, payload]) => [type, payload.n]),
    [
      ['user/setUser', 1],
      ['user/setUser', 2],
      ['user/setUser', 3]
    ],
    'the store still follows every event'
  )
  assert.equal(l.timers.length, 1)
  assert.equal(l.timers[0].ms, 500)
  l.runTimers()
  await flush()
  assert.deepEqual(
    plain(l.calls.filter((c) => c[0] === 'applyServerEvent')),
    [['applyServerEvent', { event: 'user_updated', payload: { id: 'user-1', n: 3 }, serverConfigId: 'server-a' }]]
  )

  // A server switch while one is waiting: the waiting one goes out at once with its own server
  l.view.userUpdated({ id: 'user-1', n: 4 })
  l.store.state.activeServerConfigId = 'server-b'
  l.view.userUpdated({ id: 'user-1', n: 5 })
  l.runTimers()
  await flush()
  assert.deepEqual(
    plain(l.calls.filter((c) => c[0] === 'applyServerEvent').slice(1).map(([, a]) => [a.payload.n, a.serverConfigId])),
    [
      [4, 'server-a'],
      [5, 'server-b']
    ]
  )
})

test('authorize result is handed to native once', async () => {
  const l = layout({ serverState: 'online' })
  const config = { id: 'server-a', name: 'Synthetic', address: 'https://a.test.invalid', token: 'access-1' }
  const user = { id: 'user-1', token: 'other', librariesAccessible: [], bookmarks: [{ libraryItemId: 'li1', time: 3, title: 'A' }] }
  const commits = []
  l.store.commit = (type, payload) => {
    commits.push(type)
    if (type === 'setAttemptingConnection') l.store.state.attemptingConnection = payload
  }
  Object.assign(l.view, {
    $db: {
      getDeviceData: async () => ({ deviceSettings: {}, lastServerConnectionConfigId: 'server-a', serverConnectionConfigs: [config] }),
      setServerConnectionConfig: async (c) => c
    },
    $nativeHttp: { post: async () => ({ user, userDefaultLibraryId: 'lib-1', serverSettings: { version: '2.30.0' }, ereaderDevices: [] }) },
    $isValidVersion: () => true,
    $authRenewal: { latestToken: () => null },
    $localStore: { getLastLibraryId: async () => null },
    $socket: { connect() {}, on() {}, off() {} }
  })
  l.view.initLibraries = async () => {}

  await l.component.methods.attemptConnection.call(l.view)

  assert.ok(commits.includes('user/setUser'), 'user/setUser stays')
  assert.deepEqual(
    plain(l.calls.filter((c) => c[0] === 'applyServerEvent')),
    [['applyServerEvent', { event: 'user_updated', payload: user, serverConfigId: 'server-a' }]]
  )
})

test('Home on a cold start with the saved server unreachable says so instead of offering Connect', async () => {
  const page = (await readFile(new URL('../pages/bookshelf/index.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  const sandbox = { console: { ...console, log: () => {} } }
  vm.runInNewContext(page.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component ='), sandbox)
  const unreachable = (hasServer, serverState) => sandbox.component.computed.serverUnreachable.call({ $store: { getters: { getHasActiveServer: hasServer }, state: { serverState } } })
  assert.equal(unreachable(true, 'offline'), true)
  assert.equal(unreachable(true, 'online'), false)
  assert.equal(unreachable(false, 'unknown'), false, 'no saved server: Connect as before')
  const template = page.match(/<template>([\s\S]*)<\/template>/)[1]
  assert.match(template, /v-if="!user && serverUnreachable"[\s\S]*MessageServerUnreachableShowingDevice/)
  assert.match(template, /<ui-btn v-if="!user && !serverUnreachable"[^>]*\$router\.push\('\/connect'\)/)
  const unreachableBlock = template.match(/v-if="!user && serverUnreachable">([\s\S]*?)<div class="w-full" v-else-if/)[1]
  assert.match(unreachableBlock, /@click="\$router\.push\('\/connect'\)">\{\{ \$strings\.ButtonSwitchServerUser \}\}/, 'a way to another server when this one is gone')
})

// The catalog (offline-first 4a): libraries come from the replica on the device, before and without a connection

test('cold start offline shows libraries from the replica before connecting', async () => {
  const l = layout({ serverState: 'offline' })
  l.store.getters.getHasActiveServer = true
  await l.mounted()
  assert.deepEqual(
    l.calls.filter((c) => typeof c === 'string' || c[0] === 'dispatch').map((c) => (typeof c === 'string' ? c : c[1])),
    ['setupNetworkListener', 'getServerState', 'libraries/load', 'libraries/fetch'],
    'libraries are read, no connection is attempted'
  )
  assert.ok(l.emitted.includes('library-changed'))

  const online = layout({ serverState: 'unknown' })
  online.store.getters.getHasActiveServer = true
  await online.mounted()
  const steps = online.calls.filter((c) => typeof c === 'string' || c[0] === 'dispatch').map((c) => (typeof c === 'string' ? c : c[1]))
  assert.deepEqual(steps, ['setupNetworkListener', 'getServerState', 'libraries/load', 'libraries/fetch', 'attemptConnection'], 'the replica first, then the connection')
})

test('libraries are read again after connecting', async () => {
  for (const pending of [false, true]) {
    const l = layout({ serverState: 'online' })
    const config = { id: 'server-a', name: 'Synthetic', address: 'https://a.test.invalid', token: 'access-1' }
    const user = { id: 'user-1', token: 'other', librariesAccessible: [] }
    let releaseFirst
    l.store.dispatch = async (type, payload) => {
      l.calls.push(['dispatch', type, payload])
      // The startup read is still waiting on the replica when the connection succeeds
      if (pending && type === 'libraries/load' && !payload?.force) await new Promise((resolve) => (releaseFirst = resolve))
    }
    Object.assign(l.view, {
      $db: {
        getDeviceData: async () => ({ deviceSettings: {}, lastServerConnectionConfigId: 'server-a', serverConnectionConfigs: [config] }),
        setServerConnectionConfig: async (c) => c
      },
      $nativeHttp: { post: async () => ({ user, userDefaultLibraryId: 'lib-1', serverSettings: { version: '2.30.0' }, ereaderDevices: [] }) },
      $isValidVersion: () => true,
      $authRenewal: { latestToken: () => null },
      $localStore: { getLastLibraryId: async () => null },
      $socket: { connect() {}, on() {}, off() {} }
    })
    const first = pending ? l.view.initLibraries() : null
    const connecting = l.component.methods.attemptConnection.call(l.view)
    await flush()
    if (pending) releaseFirst()
    await connecting
    await first
    const loads = l.calls.filter((c) => c[0] === 'dispatch' && c[1] === 'libraries/load').map((c) => c[2])
    assert.deepEqual(plain(loads.at(-1)), { force: true }, `pending startup read: ${pending}`)
    assert.equal(loads.length, pending ? 2 : 1)
  }
})

test('signing in after an offline start announces the library to the server-only tabs', async () => {
  for (const [signedInBefore, expected] of [
    [false, true],
    [true, false]
  ]) {
    const l = layout({ serverState: 'online', user: signedInBefore ? { id: 'user-1' } : null, currentLibraryId: 'lib-1' })
    const config = { id: 'server-a', name: 'Synthetic', address: 'https://a.test.invalid', token: 'access-1' }
    Object.assign(l.view, {
      $db: { getDeviceData: async () => ({ deviceSettings: {}, lastServerConnectionConfigId: 'server-a', serverConnectionConfigs: [config] }), setServerConnectionConfig: async (c) => c },
      $nativeHttp: { post: async () => ({ user: { id: 'user-1', token: 'x', librariesAccessible: [] }, userDefaultLibraryId: 'lib-1', serverSettings: { version: '2.30.0' }, ereaderDevices: [] }) },
      $isValidVersion: () => true,
      $authRenewal: { latestToken: () => null },
      $localStore: { getLastLibraryId: async () => 'lib-1' },
      $socket: { connect() {}, on() {}, off() {} }
    })
    const calls = []
    l.view.initLibraries = async (args) => calls.push(args)
    l.store.commit = (type, payload) => {
      if (type === 'setAttemptingConnection') l.store.state.attemptingConnection = payload
      if (type === 'user/setUser') l.store.state.user.user = payload
    }
    await l.component.methods.attemptConnection.call(l.view)
    assert.deepEqual(plain(calls), [{ force: true, notify: expected }], `signed in before: ${signedInBefore}`)
  }
})
