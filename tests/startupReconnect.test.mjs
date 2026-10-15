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

function layout({ serverState = 'offline', networkConnected = true, user = null, currentLibraryId = 'lib-1', getServerState } = {}) {
  const timers = []
  const sandbox = {
    AbsLogger: { info: async () => {}, error: async () => {} },
    CapacitorHttp: {},
    console: { ...console, log: () => {}, warn: () => {} },
    setTimeout: (fn, ms) => timers.push({ fn, ms }),
    document: { addEventListener() {}, removeEventListener() {} }
  }
  vm.runInNewContext(script, sandbox)
  const component = sandbox.component

  const calls = []
  const emitted = []
  const store = {
    state: { serverState, networkConnected, socketConnected: false, attemptingConnection: false, isFirstLoad: true, user: { user, serverConnectionConfig: null }, libraries: { currentLibraryId } },
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
        })
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
  view.attemptConnection = async () => calls.push('attemptConnection')
  view.syncLocalSessions = async (isFirstSync) => calls.push(['syncLocalSessions', isFirstSync])
  view.loadSavedSettings = async () => {}

  const watch = (name, newVal, oldVal) => {
    const w = component.watch[name]
    return (w.handler || w).call(view, newVal, oldVal)
  }
  const runTimers = () => timers.splice(0).forEach((t) => t.fn())
  return { view, store, calls, emitted, watch, runTimers, mounted: () => component.mounted.call(view) }
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
  assert.ok(signedIn.calls.some((c) => c[0] === 'dispatch' && c[1] === 'libraries/fetch'), 'the library is re-read')
  assert.deepEqual(signedIn.emitted, ['library-changed'], 'shelves refetch on library-changed')

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
