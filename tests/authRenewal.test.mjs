// Single-flight access-token renewal (docs/authentication.md): every request that gets a 401 for the same saved server
// waits for one shared renewal (utils/authRenewal.js, wired by plugins/authRenewal.js into plugins/nativeHttp.js and
// plugins/axios.js). The native refresh, the HTTP layer and the timers are fakes the tests settle by hand, so there are no
// sleeps. Synthetic servers, accounts and tokens only.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const util = await import(`data:text/javascript;base64,${Buffer.from(await read('../utils/authRenewal.js')).toString('base64')}`)
const { createAuthRenewal, AuthRenewalError, isRenewableUrl } = util

/** Loads a Nuxt plugin source with its imports replaced by the given fakes. */
async function loadPlugin(path, deps) {
  let src = await read(path)
  src = src.replace(/^import \{([^}]+)\} from '[^']+'\n/gm, (_, names) => `const {${names}} = __deps\n`)
  src = src.replace('export default ', 'return ')
  return new Function('__deps', src)(deps)
}

const flush = async (n = 10) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => ((resolve = res), (reject = rej)))
  return { promise, resolve, reject }
}

/** The native AbsDatabase.refreshAccessToken: each call waits until the test answers it. */
function fakeNativeRefresh() {
  const calls = []
  const refresh = (serverConnectionConfigId) => {
    const d = deferred()
    calls.push({ serverConnectionConfigId, ...d })
    return d.promise
  }
  return { calls, refresh }
}

/** Manual timers for the renewal timeout. */
function fakeTimers() {
  const timers = new Map()
  let next = 1
  return {
    setTimer: (fn) => (timers.set(next, fn), next++),
    clearTimer: (id) => timers.delete(id),
    fireAll: () => [...timers.entries()].forEach(([id, fn]) => (timers.delete(id), fn())),
    get count() {
      return timers.size
    }
  }
}

function coordinator({ session = { serverConnectionConfigId: 'server-a', token: 'access-0' } } = {}) {
  const native = fakeNativeRefresh()
  const timers = fakeTimers()
  const renewed = []
  const rejected = []
  const state = { session }
  const renewal = createAuthRenewal({
    refresh: native.refresh,
    getSession: () => state.session,
    onRenewed: (id, token, isActive) => {
      renewed.push({ id, token, isActive })
      if (isActive) state.session = { ...state.session, token }
    },
    onRejected: (id) => rejected.push(id),
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer
  })
  return { renewal, native, timers, renewed, rejected, state }
}

const kindOf = (promise) =>
  promise.then(
    () => 'resolved',
    (error) => (error instanceof AuthRenewalError ? error.kind : `other:${error.message}`)
  )

// Coordinator

test('one expired request renews once and gets the new token', async () => {
  const { renewal, native, renewed } = coordinator()
  const pending = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  await flush()
  assert.equal(native.calls.length, 1)
  native.calls[0].resolve({ status: 'success', accessToken: 'access-1' })
  assert.equal(await pending, 'access-1')
  assert.deepEqual(renewed, [{ id: 'server-a', token: 'access-1', isActive: true }])
  assert.equal(renewal.pendingCount, 0)
})

test('simultaneous expired requests share exactly one renewal', async () => {
  const { renewal, native, renewed } = coordinator()
  const waiting = Array.from({ length: 6 }, () => renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }))
  await flush()
  assert.equal(native.calls.length, 1)
  assert.equal(renewal.pendingCount, 1)
  native.calls[0].resolve({ status: 'success', accessToken: 'access-1' })
  assert.deepEqual(await Promise.all(waiting), Array(6).fill('access-1'))
  assert.equal(renewed.length, 1, 'applied once')
})

test('a request arriving while the renewal runs joins it', async () => {
  const { renewal, native } = coordinator()
  const first = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  await flush()
  const late = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  await flush()
  assert.equal(native.calls.length, 1)
  native.calls[0].resolve({ status: 'success', accessToken: 'access-1' })
  assert.deepEqual(await Promise.all([first, late]), ['access-1', 'access-1'])
})

test('a request sent with the old token after the renewal reuses the newer token without renewing', async () => {
  const { renewal, native } = coordinator()
  const first = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  await flush()
  native.calls[0].resolve({ status: 'success', accessToken: 'access-1' })
  await first
  assert.equal(await renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }), 'access-1')
  assert.equal(native.calls.length, 1)
})

test('a request refused with the newest token renews again (the token itself expired)', async () => {
  const { renewal, native } = coordinator()
  const first = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  await flush()
  native.calls[0].resolve({ status: 'success', accessToken: 'access-1' })
  await first
  const second = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-1' })
  await flush()
  assert.equal(native.calls.length, 2)
  native.calls[1].resolve({ status: 'success', accessToken: 'access-2' })
  assert.equal(await second, 'access-2')
})

test('a refused refresh token fails every waiting request and signs out once', async () => {
  const { renewal, native, rejected, renewed } = coordinator()
  const waiting = Array.from({ length: 4 }, () => kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })))
  await flush()
  native.calls[0].resolve({ status: 'rejected' })
  assert.deepEqual(await Promise.all(waiting), Array(4).fill('rejected'))
  assert.deepEqual(rejected, ['server-a'])
  assert.equal(renewed.length, 0)
})

test('a failure is not remembered: the next request recovers with a fresh renewal', async () => {
  const { renewal, native, rejected } = coordinator()
  const failed = kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }))
  await flush()
  native.calls[0].resolve({ status: 'transient' })
  assert.equal(await failed, 'transient')
  assert.equal(renewal.pendingCount, 0)
  const retry = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  await flush()
  assert.equal(native.calls.length, 2)
  native.calls[1].resolve({ status: 'success', accessToken: 'access-1' })
  assert.equal(await retry, 'access-1')
  assert.deepEqual(rejected, [], 'a transient failure never signs out')
})

test('network failure (offline) is transient, keeps the session, and recovers once online', async () => {
  const { renewal, native, rejected, state } = coordinator()
  const offline = kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }))
  await flush()
  native.calls[0].reject(new Error('Failed to connect'))
  assert.equal(await offline, 'transient')
  assert.equal(state.session.token, 'access-0')
  assert.deepEqual(rejected, [])
  const online = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  await flush()
  native.calls[1].resolve({ status: 'success', accessToken: 'access-1' })
  assert.equal(await online, 'access-1')
})

test('a renewal that never answers times out, settles its callers, and its late result is ignored', async () => {
  const { renewal, native, timers, renewed } = coordinator()
  const waiting = [kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })), kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }))]
  await flush()
  assert.equal(timers.count, 1)
  timers.fireAll()
  assert.deepEqual(await Promise.all(waiting), ['transient', 'transient'])
  assert.equal(renewal.pendingCount, 0)
  native.calls[0].resolve({ status: 'success', accessToken: 'access-late' })
  await flush()
  assert.equal(renewed.length, 0)
  assert.equal(timers.count, 0)
})

test('logout during a renewal fails its callers at once and the late result is never applied', async () => {
  const { renewal, native, renewed, rejected, state } = coordinator()
  const waiting = kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }))
  await flush()
  state.session = { serverConnectionConfigId: null, token: null }
  renewal.invalidate()
  assert.equal(await waiting, 'stale')
  native.calls[0].resolve({ status: 'success', accessToken: 'access-late' })
  await flush()
  assert.deepEqual(renewed, [])
  assert.equal(state.session.token, null)
  // A late rejection after logout doesn't sign anything out either.
  const again = kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }))
  await flush()
  renewal.invalidate()
  native.calls[1].resolve({ status: 'rejected' })
  assert.equal(await again, 'stale')
  await flush()
  assert.deepEqual(rejected, [])
})

test('a server or account switch during a renewal keeps the old result out of the new session', async () => {
  for (const next of [
    { serverConnectionConfigId: 'server-b', token: 'access-b' }, // another server
    { serverConnectionConfigId: 'server-a-account-2', token: 'access-a2' } // another account on the same server
  ]) {
    const { renewal, native, renewed, state } = coordinator()
    const old = kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }))
    await flush()
    state.session = next
    renewal.invalidate()
    assert.equal(await old, 'stale')

    // The new session renews on its own, and the delayed old answer arrives last.
    const fresh = renewal.renew({ serverConnectionConfigId: next.serverConnectionConfigId, token: next.token })
    await flush()
    assert.equal(native.calls.length, 2)
    native.calls[1].resolve({ status: 'success', accessToken: `${next.token}-renewed` })
    assert.equal(await fresh, `${next.token}-renewed`)
    native.calls[0].resolve({ status: 'success', accessToken: 'access-a-late' })
    await flush()
    assert.equal(state.session.token, `${next.token}-renewed`)
    assert.deepEqual(
      renewed.map((r) => r.token),
      [`${next.token}-renewed`]
    )
  }
})

test('different servers renew independently, and a saved server being connected is not applied to the session', async () => {
  const { renewal, native, renewed, state } = coordinator()
  const a = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  const b = renewal.renew({ serverConnectionConfigId: 'server-b', token: 'b-0' })
  await flush()
  assert.equal(native.calls.length, 2)
  native.calls[1].resolve({ status: 'success', accessToken: 'b-1' })
  native.calls[0].resolve({ status: 'success', accessToken: 'access-1' })
  assert.deepEqual(await Promise.all([a, b]), ['access-1', 'b-1'])
  assert.deepEqual(
    renewed.find((r) => r.id === 'server-b'),
    { id: 'server-b', token: 'b-1', isActive: false }
  )
  assert.equal(state.session.token, 'access-1')
  assert.equal(renewal.renewedToken('server-b'), 'b-1')
})

test('a caller that stops waiting does not cancel the renewal for the others, and nothing is left unhandled', async () => {
  const unhandled = []
  const onUnhandled = (reason) => unhandled.push(reason)
  process.on('unhandledRejection', onUnhandled)
  try {
    const { renewal, native } = coordinator()
    renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }) // abandoned: nobody awaits it
    const kept = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
    await flush()
    native.calls[0].resolve({ status: 'success', accessToken: 'access-1' })
    assert.equal(await kept, 'access-1')

    renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-1' }) // abandoned, and it fails
    await flush()
    native.calls[1].resolve({ status: 'transient' })
    await new Promise((resolve) => setImmediate(resolve))
    assert.deepEqual(unhandled, [])
  } finally {
    process.off('unhandledRejection', onUnhandled)
  }
})

test('the auth endpoints never trigger a renewal, so renewal cannot recurse', () => {
  assert.equal(isRenewableUrl('https://abs.test.invalid/auth/refresh'), false)
  assert.equal(isRenewableUrl('https://abs.test.invalid/login'), false)
  assert.equal(isRenewableUrl('https://abs.test.invalid/logout?x=1'), false)
  assert.equal(isRenewableUrl('https://abs.test.invalid/api/authorize'), true)
  assert.equal(isRenewableUrl('/api/libraries'), true)
})

test('errors carry no credentials', async () => {
  const { renewal, native } = coordinator()
  const p = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }).catch((e) => e)
  await flush()
  native.calls[0].resolve({ status: 'rejected', accessToken: 'access-secret-1' })
  const error = await p
  assert.doesNotMatch(`${error.message} ${error.stack}`, /secret/)
})

// The app wiring: plugins/authRenewal.js + plugins/nativeHttp.js + plugins/axios.js

function fakeStore(initial = { id: 'server-a', address: 'https://a.test.invalid', token: 'access-0' }) {
  const subscribers = []
  const store = {
    state: { serverState: 'online', user: { serverConnectionConfig: initial.id ? { id: initial.id, address: initial.address } : null, accessToken: initial.token } },
    getters: {},
    dispatched: [],
    commit(type, payload) {
      if (type === 'user/setAccessToken') store.state.user.accessToken = payload
      if (type === 'user/setServerConnectionConfig') store.state.user.serverConnectionConfig = payload
      if (type === 'user/logout') {
        store.state.user.accessToken = null
        store.state.user.serverConnectionConfig = null
      }
      if (type === 'setServerState') store.state.serverState = payload
      subscribers.forEach((fn) => fn({ type, payload }))
    },
    async dispatch(type) {
      store.dispatched.push(type)
      if (type === 'user/logout') store.commit('user/logout')
      if (type === 'user/revokeSession') store.commit('user/setAccessToken', null)
    },
    subscribe: (fn) => subscribers.push(fn)
  }
  Object.defineProperties(store.getters, {
    'user/getServerConnectionConfigId': { get: () => store.state.user.serverConnectionConfig?.id || null },
    'user/getToken': { get: () => store.state.user.accessToken || null },
    'user/getServerAddress': { get: () => store.state.user.serverConnectionConfig?.address || null }
  })
  return store
}

/** A server whose valid access tokens the test controls; requests with any other bearer get 401. */
function fakeHttp() {
  const valid = new Set()
  const requests = []
  const CapacitorHttp = {
    async request({ method, url, headers }) {
      const token = (headers?.Authorization || '').replace(/^Bearer /, '')
      requests.push({ method, url, token })
      if (url.endsWith('/broken')) return { status: 500, data: 'Internal error' }
      return valid.has(token) ? { status: 200, data: { ok: url, token } } : { status: 401, data: 'Unauthorized' }
    }
  }
  return { valid, requests, CapacitorHttp }
}

async function app({ store = fakeStore() } = {}) {
  const native = fakeNativeRefresh()
  const http = fakeHttp()
  const location = { pathname: '/bookshelf', href: '/bookshelf' }
  globalThis.window = { location }
  const ctx = { store, $socket: { connected: true, isAuthenticated: true, sendAuthenticate() {} } }
  const inject = (name, value) => (ctx[`$${name}`] = value)
  const renewalPlugin = await loadPlugin('../plugins/authRenewal.js', { AbsDatabase: { refreshAccessToken: ({ serverConnectionConfigId }) => native.refresh(serverConnectionConfigId) }, createAuthRenewal })
  renewalPlugin(ctx, inject)
  // Reachability reports go to native AbsData (covered by dataService.test.mjs); here they are ignored
  const AbsData = { reportServerResponse: async () => {}, reportServerUnreachable: async () => {} }
  const nativeHttpPlugin = await loadPlugin('../plugins/nativeHttp.js', { CapacitorHttp: http.CapacitorHttp, isRenewableUrl, AbsData })
  nativeHttpPlugin(ctx, inject)
  return { ctx, store, native, http, location }
}

/** Answers the pending native refresh like the server: rotates and makes the new access token valid. */
function answer(native, http, index, accessToken) {
  http.valid.add(accessToken)
  native.calls[index].resolve({ status: 'success', accessToken })
}

test('native-path requests: concurrent 401s renew once, each request is retried once, and the store gets the token', async () => {
  const { ctx, store, native, http } = await app()
  const results = Promise.all(['/api/libraries', '/api/me', '/api/items/1', '/api/me/progress/1'].map((url) => ctx.$nativeHttp.get(url)))
  await flush(30)
  assert.equal(native.calls.length, 1)
  answer(native, http, 0, 'access-1')
  const data = await results
  assert.deepEqual(
    data.map((d) => d.token),
    Array(4).fill('access-1')
  )
  assert.equal(store.getters['user/getToken'], 'access-1')
  assert.equal(http.requests.length, 8, 'each request sent twice: original + one retry')
})

test('a successful request never renews', async () => {
  const { ctx, native, http } = await app()
  http.valid.add('access-0')
  assert.equal((await ctx.$nativeHttp.get('/api/me')).token, 'access-0')
  assert.equal(native.calls.length, 0)
})

test('a retry that is refused again fails without another renewal (no loop)', async () => {
  const { ctx, native } = await app()
  const result = ctx.$nativeHttp.get('/api/me').catch((e) => e.message)
  await flush(30)
  native.calls[0].resolve({ status: 'success', accessToken: 'access-not-accepted' })
  assert.equal(await result, 'Unauthorized')
  assert.equal(native.calls.length, 1)
})

test('a server error after renewal fails only that request and does not sign out', async () => {
  const { ctx, store, native, http } = await app()
  const result = ctx.$nativeHttp.get('/api/broken').catch((e) => e.message)
  await flush(30)
  assert.equal(native.calls.length, 0, '500 is not an auth failure')
  assert.equal(await result, 'Internal error')
  const expired = ctx.$nativeHttp.get('/api/me')
  await flush(30)
  answer(native, http, 0, 'access-1')
  await expired
  assert.deepEqual(store.dispatched, [])
})

test('a refused refresh token revokes the session once and stays in the app as unauthorized', async () => {
  const { ctx, store, native, location } = await app()
  const results = Promise.all([ctx.$nativeHttp.get('/api/me').catch((e) => e.kind), ctx.$nativeHttp.get('/api/libraries').catch((e) => e.kind)])
  await flush(30)
  native.calls[0].resolve({ status: 'rejected' })
  assert.deepEqual(await results, ['rejected', 'rejected'])
  await flush(30)
  assert.deepEqual(store.dispatched, ['user/revokeSession'], 'not user/logout')
  assert.equal(store.state.serverState, 'unauthorized')
  assert.equal(location.href, '/bookshelf', 'no jump to /connect')
  assert.equal(store.getters['user/getServerConnectionConfigId'], 'server-a', 'the server stays selected')
})

test('revoked session keeps the library context', async () => {
  let src = await read('../store/user.js')
  src = src.replace(/^import .*\n/gm, '').replace(/^export const (\w+) =/gm, 'exports.$1 =')
  const exports = {}
  new Function('exports', 'AbsLogger', 'Browser', src)(exports, { info: async () => {} }, {})
  const state = exports.state()
  state.user = { id: 'user-1', permissions: { download: true } }
  state.accessToken = 'access-0'
  state.serverConnectionConfig = { id: 'server-a' }
  state.cachedUser = { id: 'user-1', permissions: { download: true } }
  state.mediaProgress = [{ libraryItemId: 'li1', currentTime: 30 }]
  state.bookmarks = [{ libraryItemId: 'li1', time: 1, title: 'A' }]
  const seen = { commits: [], invalidated: 0, socketLogout: 0, removedLibrary: 0 }
  const commit = (type, payload, opts) => {
    seen.commits.push(opts?.root ? `root:${type}` : type)
    exports.mutations[type]?.(state, payload)
  }
  const ctx = {
    $authRenewal: { invalidate: () => seen.invalidated++ },
    $socket: { logout: () => seen.socketLogout++ },
    $localStore: { removeLastLibraryId: () => seen.removedLibrary++ },
    $db: { logout: async () => assert.fail('the saved server is not signed out') }
  }
  await exports.actions.revokeSession.call(ctx, { state, commit })

  assert.equal(state.user, null)
  assert.equal(state.accessToken, null)
  assert.equal(seen.invalidated, 1)
  assert.equal(seen.socketLogout, 1)
  assert.equal(seen.removedLibrary, 0, 'the last library is kept')
  assert.ok(!seen.commits.some((c) => c.startsWith('root:') || c === 'logout'), 'no library reset, no logout')
  assert.deepEqual(state.serverConnectionConfig, { id: 'server-a' })
  assert.equal(state.mediaProgress.length, 1)
  assert.equal(state.bookmarks.length, 1)
  assert.equal(exports.getters.getUserCanDownload(state), true, 'rights come from the cached user')
})

test('a transient renewal failure (offline) fails the requests but keeps the user signed in', async () => {
  const { ctx, store, native } = await app()
  const result = ctx.$nativeHttp.get('/api/me').catch((e) => e.kind)
  await flush(30)
  native.calls[0].resolve({ status: 'transient' })
  assert.equal(await result, 'transient')
  assert.deepEqual(store.dispatched, [])
  assert.equal(store.getters['user/getServerConnectionConfigId'], 'server-a')
})

test('logout during renewal: waiting requests fail at once, nothing is retried, the late token is not applied', async () => {
  const { ctx, store, native, http } = await app()
  const result = ctx.$nativeHttp.get('/api/me').catch((e) => e.kind)
  await flush(30)
  store.commit('user/logout')
  assert.equal(await result, 'stale')
  answer(native, http, 0, 'access-late')
  await flush(30)
  assert.equal(store.getters['user/getToken'], null)
  assert.equal(http.requests.length, 1, 'not retried')
})

test('server switch during renewal: the old server token never reaches the new session', async () => {
  const { ctx, store, native, http } = await app()
  const old = ctx.$nativeHttp.get('/api/me').catch((e) => e.kind)
  await flush(30)
  store.commit('user/setServerConnectionConfig', { id: 'server-b', address: 'https://b.test.invalid' })
  store.commit('user/setAccessToken', 'b-0')
  assert.equal(await old, 'stale')
  answer(native, http, 0, 'access-a-late')
  await flush(30)
  assert.equal(store.getters['user/getToken'], 'b-0')
  assert.ok(http.requests.every((r) => r.token !== 'access-a-late'))
})

test('the old server rejecting its refresh token after a switch does not sign out the new session', async () => {
  const { ctx, store } = await app()
  store.commit('user/setServerConnectionConfig', { id: 'server-b', address: 'https://b.test.invalid' })
  await ctx.$authRenewal.signInAgain('server-a')
  assert.deepEqual(store.dispatched, [])
  assert.equal(store.getters['user/getServerConnectionConfigId'], 'server-b')
})

test('connecting a saved server: its authorize request renews without touching the signed-out store', async () => {
  const store = fakeStore({ id: null, token: null })
  const { ctx, native, http } = await app({ store })
  const savedConfig = { id: 'server-b', address: 'https://b.test.invalid' }
  const authorize = ctx.$nativeHttp.post('https://b.test.invalid/api/authorize', null, { headers: { Authorization: 'Bearer b-expired' }, serverConnectionConfig: savedConfig })
  await flush(30)
  answer(native, http, 0, 'b-1')
  assert.equal((await authorize).token, 'b-1')
  assert.equal(store.getters['user/getToken'], null)
  assert.equal(ctx.$authRenewal.renewedToken('server-b'), 'b-1', 'ServerConnectForm saves this one')
})

test('a 401 from login or without a saved server is an ordinary error, not a renewal', async () => {
  const store = fakeStore({ id: null, token: null })
  const { ctx, native } = await app({ store })
  const login = await ctx.$nativeHttp.post('https://b.test.invalid/login', { username: 'synthetic' }, { serverConnectionConfig: { address: 'https://b.test.invalid' } }).catch((e) => e.message)
  assert.equal(login, 'Unauthorized')
  assert.equal(native.calls.length, 0)
  assert.deepEqual(store.dispatched, [])
})

test('a recreated WebView starts with a fresh coordinator that still renews once', async () => {
  const first = await app()
  first.store.commit('user/logout')
  const { ctx, native, http } = await app()
  const results = Promise.all([ctx.$nativeHttp.get('/api/me'), ctx.$nativeHttp.get('/api/libraries')])
  await flush(30)
  assert.equal(native.calls.length, 1)
  answer(native, http, 0, 'access-1')
  assert.equal((await results).length, 2)
})

test('axios and native requests share the same renewal', async () => {
  const { ctx, store, native, http } = await app()
  const handlers = {}
  const $axios = (config) => http.CapacitorHttp.request({ method: 'GET', url: config.url, headers: config.headers }).then((res) => (res.status === 200 ? res.data : Promise.reject({ config, response: res })))
  $axios.onRequest = (fn) => (handlers.request = fn)
  $axios.onError = (fn) => (handlers.error = fn)
  const axiosPlugin = await loadPlugin('../plugins/axios.js', { isRenewableUrl })
  axiosPlugin({ $axios, store, $authRenewal: ctx.$authRenewal })

  const config = { url: '/api/items/1/ebook', headers: { common: {} } }
  handlers.request(config)
  assert.equal(config.url, 'https://a.test.invalid/api/items/1/ebook')
  const viaAxios = handlers.error({ config: { ...config, headers: { Authorization: config.headers.common.Authorization } }, response: { status: 401, data: 'Unauthorized' } })
  const viaNative = ctx.$nativeHttp.get('/api/me')
  await flush(30)
  assert.equal(native.calls.length, 1)
  answer(native, http, 0, 'access-1')
  assert.equal((await viaAxios).token, 'access-1')
  assert.equal((await viaNative).token, 'access-1')

  // The axios retry is marked, so a second 401 is returned instead of renewing again.
  const retried = { ...config, _retry: true }
  await assert.rejects(handlers.error({ config: retried, response: { status: 401 } }))
  assert.equal(native.calls.length, 1)
})

test('no token appears in the console output of a renewal', async () => {
  const lines = []
  const original = { log: console.log, error: console.error, warn: console.warn }
  for (const k of Object.keys(original)) console[k] = (...args) => lines.push(args.map(String).join(' '))
  try {
    const { ctx, native, http } = await app({ store: fakeStore({ id: 'server-a', address: 'https://a.test.invalid', token: 'access-secret-0' }) })
    const result = ctx.$nativeHttp.get('/api/me')
    await flush(30)
    answer(native, http, 0, 'access-secret-1')
    await result
  } finally {
    Object.assign(console, original)
  }
  assert.ok(lines.length > 0)
  assert.ok(lines.every((l) => !l.includes('secret')))
})

test('the session token renewed natively later wins over an older JS renewal', async () => {
  const { renewal, native, state } = coordinator()
  const first = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' })
  await flush()
  native.calls[0].resolve({ status: 'success', accessToken: 'access-1' })
  await first
  // A native request renewed the token afterwards (onTokenRefresh updated the store), and that one expired too
  state.session = { ...state.session, token: 'access-2' }
  const next = renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-2' })
  await flush()
  assert.equal(native.calls.length, 2, 'renews instead of retrying with the older access-1')
  native.calls[1].resolve({ status: 'success', accessToken: 'access-3' })
  assert.equal(await next, 'access-3')
})

// Final review: logout, startup reconnect and late native events

test('latestToken never hands out another server or account token', async () => {
  const { renewal, native } = coordinator()
  assert.equal(renewal.latestToken('server-a'), 'access-0', 'the signed-in session')
  assert.equal(renewal.latestToken('server-a-account-2'), null, 'not the session token of another account')
  const b = renewal.renew({ serverConnectionConfigId: 'server-b', token: 'b-expired' })
  await flush()
  native.calls[0].resolve({ status: 'success', accessToken: 'b-1' })
  await b
  assert.equal(renewal.latestToken('server-b'), 'b-1', 'renewed while connecting')
  renewal.invalidate()
  assert.equal(renewal.latestToken('server-b'), null, 'forgotten after logout or a switch')
})

test('app startup: authorize with an expired saved token renews, and the renewed token is the one to save', async () => {
  // layouts/default.vue attemptConnection: nobody is signed in yet, the saved server's token has expired
  const store = fakeStore({ id: null, token: null })
  const { ctx, native, http } = await app({ store })
  const saved = { id: 'server-a', address: 'https://a.test.invalid', token: 'access-expired' }
  const authorize = ctx.$nativeHttp.post(`${saved.address}/api/authorize`, null, { headers: { Authorization: `Bearer ${saved.token}` }, serverConnectionConfig: saved })
  await flush(30)
  answer(native, http, 0, 'access-1')
  assert.equal((await authorize).token, 'access-1')
  assert.equal(ctx.$authRenewal.latestToken(saved.id) || saved.token, 'access-1', 'not the expired token')
  assert.equal(native.calls.length, 1)
})

test('logout invalidates renewals before its own async steps finish', async () => {
  let src = await read('../store/user.js')
  src = src.replace(/^import .*\n/gm, '').replace(/^export const (\w+) =/gm, 'exports.$1 =')
  const exports = {}
  new Function('exports', 'AbsLogger', src)(exports, { info: async () => {} })
  const { renewal, native } = coordinator()
  const waiting = kindOf(renewal.renew({ serverConnectionConfigId: 'server-a', token: 'access-0' }))
  await flush()
  const dbLogout = deferred()
  const thisCtx = { $authRenewal: renewal, $db: { logout: () => dbLogout.promise }, $socket: { logout() {} }, $localStore: { removeLastLibraryId() {} } }
  const done = exports.actions.logout.call(thisCtx, { state: { serverConnectionConfig: { id: 'server-a', name: 'synthetic' } }, commit() {} })
  assert.equal(await waiting, 'stale', 'settled while native logout is still running')
  native.calls[0].resolve({ status: 'success', accessToken: 'access-late' })
  dbLogout.resolve()
  await done
  assert.equal(renewal.latestToken('server-a'), 'access-0', 'the late token was not taken')
})

test('late native token events after logout or in a recreated WebView do not sign anything in or out', async () => {
  const listeners = {}
  const db = await loadPlugin('../plugins/db.js', {
    AbsDatabase: { addListener: (name, fn) => (listeners[name] = fn) },
    deviceDataSummary: () => '',
    serverConnectionConfigSummary: () => ''
  })
  const store = fakeStore({ id: null, token: null }) // signed out (or a fresh WebView after logout)
  const signedOut = []
  const appCtx = { $authRenewal: { signInAgain: async (id) => signedOut.push(id) } }
  db.call(null, { app: appCtx, store }, () => {})
  listeners.onTokenRefresh({ accessToken: 'access-late', serverConnectionConfigId: 'server-a' })
  assert.equal(store.getters['user/getToken'], null)

  // Signed into server B: a late token or failure for server A is ignored by the listener and the sign-in guard
  const storeB = fakeStore({ id: 'server-b', address: 'https://b.test.invalid', token: 'b-0' })
  db.call(null, { app: appCtx, store: storeB }, () => {})
  listeners.onTokenRefresh({ accessToken: 'access-late', serverConnectionConfigId: 'server-a' })
  assert.equal(storeB.getters['user/getToken'], 'b-0')
  listeners.onTokenRefresh({ accessToken: 'b-1', serverConnectionConfigId: 'server-b' })
  assert.equal(storeB.getters['user/getToken'], 'b-1', 'its own renewal still applies')
  const { ctx } = await app({ store: storeB })
  await ctx.$authRenewal.signInAgain('server-a')
  assert.deepEqual(storeB.dispatched, [])
})
