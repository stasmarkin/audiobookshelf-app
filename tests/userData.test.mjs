// Progress and the user object from native userdata.db ($absData, utils/dataService.js) and every progress write going
// through it: "finished", reset and e-book position work offline and reach the server later; a socket or refocus value
// moves the player only when native took it. Fakes only, synthetic servers and items.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { EventEmitter } from 'node:events'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const moduleOf = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { createDataService } = await moduleOf('../utils/dataService.js')
const { progressTarget, isCopyOfActiveServer } = await moduleOf('../utils/progressTarget.js')

const flush = async (n = 20) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}
const plain = (value) => JSON.parse(JSON.stringify(value))

/** store/user.js itself, behind a store with the root state $absData reads. */
async function userStore() {
  let src = await read('../store/user.js')
  src = src.replace(/^import .*\n/gm, '').replace(/^export const (\w+) =/gm, 'exports.$1 =')
  const user = {}
  new Function('exports', 'AbsLogger', 'Browser', src)(user, { info: async () => {} }, {})
  const store = { state: { serverState: 'unknown', activeServerConfigId: null, user: user.state() }, commits: [], dispatched: [] }
  store.commit = (type, payload) => {
    store.commits.push(type)
    if (type === 'setServerState') store.state.serverState = payload
    else if (type === 'setActiveServerConfigId') store.state.activeServerConfigId = payload
    else if (type.startsWith('user/')) user.mutations[type.slice(5)](store.state.user, payload)
  }
  store.dispatch = async (type) => store.dispatched.push(type)
  const getter = (name) => user.getters[name](store.state.user)
  return { store, getter }
}

/** Native AbsData: user data per server, progress writes, events. `answer`: hold getUserData answers to settle by hand. */
function nativeUserData(data = {}, { hold = false } = {}) {
  const listeners = {}
  const plugin = {
    calls: [],
    pending: [],
    async getServerState() {
      return { state: 'offline', serverConfigId: 'server-a' }
    },
    async getSyncState() {
      return { running: false, pendingCount: 0, lastError: null, lastSyncAt: null }
    },
    getUserData(args = {}) {
      plugin.calls.push(['getUserData', args])
      // Native answers for the server active when it was asked
      const server = args.serverConfigId || plugin.active
      const answer = () => data[server] || { user: null, mediaProgress: [], bookmarks: [] }
      if (!hold) return Promise.resolve(answer())
      return new Promise((resolve) => plugin.pending.push({ server, resolve: () => resolve(answer()) }))
    },
    async getMediaProgress(args) {
      plugin.calls.push(['getMediaProgress', args])
      const all = (data[plugin.active] || {}).mediaProgress || []
      return { mediaProgress: all.filter((mp) => args.libraryItemIds.includes(mp.libraryItemId)) }
    },
    addListener(name, cb) {
      ;(listeners[name] ||= []).push(cb)
    },
    send(name, payload) {
      if (name === 'onServerState') plugin.active = payload.serverConfigId
      ;(listeners[name] || []).forEach((cb) => cb(payload))
    },
    active: 'server-a'
  }
  for (const name of ['markFinished', 'resetProgress', 'updateEbookProgress', 'applyServerProgress', 'applyServerEvent']) {
    plugin[name] = async (args) => {
      plugin.calls.push([name, args])
      return { applied: true }
    }
  }
  return plugin
}

const progressOf = (libraryItemId, currentTime, extra = {}) => ({ id: `mp-${libraryItemId}`, libraryItemId, episodeId: null, currentTime, lastUpdate: 100, ...extra })

test('switching saved servers offline swaps user data', async () => {
  const { store, getter } = await userStore()
  const plugin = nativeUserData(
    {
      'server-a': { user: { id: 'u-a', permissions: { download: true } }, mediaProgress: [progressOf('li-a', 10)], bookmarks: [{ libraryItemId: 'li-a', time: 1, title: 'A' }] },
      'server-b': { user: { id: 'u-b', permissions: { download: false } }, mediaProgress: [progressOf('li-b', 20)], bookmarks: [] }
    },
    { hold: true }
  )
  const $absData = createDataService({ plugin, store, platform: 'android' })
  plugin.send('onServerState', { state: 'offline', serverConfigId: 'server-a' })
  const first = $absData.reloadUserData()
  await flush()
  plugin.pending.shift().resolve()
  await first
  assert.equal(getter('getUserMediaProgress')('li-a').currentTime, 10)
  assert.equal(getter('getUserCanDownload'), true)

  // A re-read for server A is still on its way when B becomes active
  const late = $absData.reloadUserData()
  await flush()
  plugin.send('onServerState', { state: 'offline', serverConfigId: 'server-b' })
  assert.deepEqual(plain(store.state.user.mediaProgress), [], 'nothing of server A is shown after the switch')
  assert.equal(store.state.user.cachedUser, null)
  assert.equal(getter('getUserCanDownload'), false)
  await flush()
  const fromA = plugin.pending.find((p) => p.server === 'server-a')
  fromA.resolve()
  await flush()
  assert.equal(getter('getUserMediaProgress')('li-a'), undefined, 'the late answer for A is dropped')
  plugin.pending.forEach((p) => p.resolve())
  await late
  await flush()
  assert.equal(getter('getUserMediaProgress')('li-b').currentTime, 20)
  assert.deepEqual(plain(store.state.user.bookmarks), [])
  assert.equal(store.state.user.cachedUser.id, 'u-b')
})

test('progress getter works offline from userData', async () => {
  const { store, getter } = await userStore()
  store.state.user.user = null
  store.commit('user/setUserData', { mediaProgress: [progressOf('li1', 30), progressOf('li2', 5, { episodeId: 'ep1' })], bookmarks: [] })
  assert.equal(getter('getUserMediaProgress')('li1').currentTime, 30)
  assert.equal(getter('getUserMediaProgress')('li2', 'ep1').currentTime, 5)
  assert.equal(getter('getUserMediaProgress')('li2', 'ep9'), undefined)
})

test('cold start offline uses cached rights; a live user still takes precedence', async () => {
  const { store, getter } = await userStore()
  store.commit('user/setCachedUser', { id: 'u', type: 'admin', permissions: { download: true, update: true, delete: false, accessExplicitContent: true } })
  assert.equal(store.state.user.user, null, 'the cached copy is not a live session: authorize still runs when online')
  assert.equal(getter('getUserCanDownload'), true)
  assert.equal(getter('getUserCanUpdate'), true)
  assert.equal(getter('getUserCanDelete'), false)
  assert.equal(getter('getUserCanAccessExplicitContent'), true)
  assert.equal(getter('getIsAdminOrUp'), true)
  assert.equal(getter('getIsRoot'), false)
  store.commit('user/setUser', { id: 'u', type: 'user', permissions: { download: false } })
  assert.equal(getter('getUserCanDownload'), false)
  assert.equal(getter('getIsAdminOrUp'), false)
})

test('local write refreshes the open UI: only the changed items, and Paper only when it is not a tick', async () => {
  const { store, getter } = await userStore()
  const data = { 'server-a': { user: null, mediaProgress: [progressOf('li1', 0, { isFinished: true }), progressOf('li2', 7)], bookmarks: [] } }
  const plugin = nativeUserData(data)
  const $absData = createDataService({ plugin, store, platform: 'android' })
  plugin.send('onServerState', { state: 'offline', serverConfigId: 'server-a' })
  store.commit('user/setUserData', { mediaProgress: [progressOf('li1', 500), progressOf('li2', 7)], bookmarks: [] })

  await $absData.markFinished({ libraryItemId: 'li1', isFinished: true })
  assert.deepEqual(plugin.calls.at(-1), ['markFinished', { libraryItemId: 'li1', episodeId: null, isFinished: true }])
  plugin.send('onDataChanged', { scope: 'progress', ids: ['li1'] })
  await flush()
  assert.deepEqual(plugin.calls.at(-1), ['getMediaProgress', { libraryItemIds: ['li1'] }], 'only that item is read, not getUserData')
  assert.equal(getter('getUserMediaProgress')('li1').isFinished, true)
  assert.equal(getter('getUserMediaProgress')('li2').currentTime, 7)
  assert.deepEqual(store.dispatched, ['globals/loadLocalMediaProgress'], 'the downloaded copies are re-read too')

  // A playback tick: the same partial read, and no Paper re-read every 15 s
  data['server-a'].mediaProgress[1].currentTime = 22
  plugin.send('onDataChanged', { scope: 'playback', ids: ['li2'] })
  await flush()
  assert.equal(getter('getUserMediaProgress')('li2').currentTime, 22)
  assert.deepEqual(store.dispatched, ['globals/loadLocalMediaProgress'])
  assert.equal(plugin.calls.filter(([name]) => name === 'getUserData').length, 0)

  // No ids, or too many for one read: everything once
  plugin.send('onDataChanged', { scope: 'progress', ids: Array.from({ length: 101 }, (_, i) => `li${i}`) })
  await flush()
  assert.equal(plugin.calls.filter(([name]) => name === 'getUserData').length, 1)
})

test('progress writes carry the server of a downloaded copy only when given', async () => {
  const { store } = await userStore()
  const plugin = nativeUserData()
  const $absData = createDataService({ plugin, store, platform: 'android' })
  await $absData.markFinished({ libraryItemId: 'li1', episodeId: 'ep1', isFinished: false, serverConfigId: 'server-b' })
  await $absData.resetProgress({ libraryItemId: 'li1' })
  await $absData.updateEbookProgress({ libraryItemId: 'li1', ebookLocation: 'cfi', ebookProgress: 0.5, serverConfigId: null })
  assert.deepEqual(
    plugin.calls.filter(([name]) => ['markFinished', 'resetProgress', 'updateEbookProgress'].includes(name)),
    [
      ['markFinished', { libraryItemId: 'li1', episodeId: 'ep1', isFinished: false, serverConfigId: 'server-b' }],
      ['resetProgress', { libraryItemId: 'li1', episodeId: null }],
      ['updateEbookProgress', { libraryItemId: 'li1', ebookLocation: 'cfi', ebookProgress: 0.5 }]
    ]
  )
})

/** plugins/server.js with a fake $absData; returns the socket and what it re-emitted. */
async function socket(applyServerEvent) {
  let src = await read('../plugins/server.js')
  src = src.replace(/^import \{([^}]+)\} from '[^']+'\n/gm, (_, names) => `const {${names}} = __deps\n`)
  src = src.replace(/^import (\w+) from '[^']+'\n/gm, (_, name) => `const ${name} = __deps.${name}\n`)
  src = src.replace('export default ', 'return ')
  const plugin = new Function('__deps', src)({ io: () => null, EventEmitter })
  const commits = []
  const store = { commit: (type) => commits.push(type), getters: {}, state: { activeServerConfigId: 'server-a', libraries: {} } }
  const ctx = { app: {}, store }
  plugin(ctx, (name, value) => (ctx[`$${name}`] = value))
  const calls = []
  ctx.app.$absData = {
    applyServerEvent: async (args) => {
      calls.push(args)
      return applyServerEvent(args)
    }
  }
  const emitted = []
  ctx.$socket.on('user_media_progress_updated', (payload) => emitted.push(payload))
  return { s: ctx.$socket, calls, emitted, commits, store }
}

/** layouts/default.vue methods on a fake context: what userMediaProgressUpdated does with an event. */
async function layoutHandler({ open = true, playing = false } = {}) {
  const source = await read('../layouts/default.vue')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { AbsLogger: { info: async () => {}, error: async () => {} }, CapacitorHttp: {}, console: { ...console, log() {} } }
  vm.runInNewContext(script, sandbox)
  const seeks = []
  const db = []
  const ctx = {
    $store: { getters: { getIsMediaStreaming: () => open, getCurrentPlaybackSessionId: 'this-device' }, state: { playerIsPlaying: playing }, commit: () => db.push('commit') },
    $eventBus: { $emit: (name, time) => name === 'playback-time-update' && seeks.push(time) },
    $db: new Proxy({}, { get: (_, name) => (...args) => db.push(name) })
  }
  return { handle: (payload) => sandbox.component.methods.userMediaProgressUpdated.call(ctx, payload), seeks, db }
}

const socketEvent = { id: 'mp1', sessionId: 'other-device', deviceDescription: 'phone', data: progressOf('li1', 900, { lastUpdate: 999 }) }

test('socket progress event: merged by native, seek only when applied', async () => {
  for (const applied of [false, true]) {
    const { s, calls, emitted, commits } = await socket(async () => ({ applied }))
    await s.onUserItemProgressUpdated(socketEvent)
    assert.deepEqual(plain(calls), [{ event: 'user_item_progress_updated', payload: socketEvent, serverConfigId: 'server-a' }])
    assert.deepEqual(commits, [], 'the store is not written from JS')
    assert.equal(emitted.at(-1).applied, applied)

    const layout = await layoutHandler()
    await layout.handle(emitted.at(-1))
    assert.deepEqual(layout.seeks, applied ? [900] : [], applied ? 'taken: the paused player follows' : 'an unsent local change won: no seek')
    assert.deepEqual(layout.db, [], 'no Paper writes from JS')
  }
  const playing = await layoutHandler({ playing: true })
  await playing.handle({ ...socketEvent, applied: true })
  assert.deepEqual(playing.seeks, [], 'never while playing')
})

test('socket progress event keeps its server from arrival, and a failing bridge is not applied', async () => {
  const { s, calls, emitted, store } = await socket(async () => {
    throw new Error('bridge gone')
  })
  const handled = s.onUserItemProgressUpdated(socketEvent)
  store.state.activeServerConfigId = 'server-b'
  await handled
  assert.equal(calls[0].serverConfigId, 'server-a')
  assert.equal(emitted[0].applied, false)
})

test('socket progress event in the browser: the same rule against the store, strictly newer wins', async () => {
  const { store, getter } = await userStore()
  store.commit('user/setUserData', { mediaProgress: [progressOf('li1', 100, { lastUpdate: 500 })], bookmarks: [] })
  const $absData = createDataService({ plugin: nativeUserData(), store, platform: 'web', $axios: {} })
  const { s, emitted } = await socket((args) => $absData.applyServerEvent(args))

  await s.onUserItemProgressUpdated({ ...socketEvent, data: progressOf('li1', 200, { lastUpdate: 500 }) })
  assert.equal(emitted.at(-1).applied, false, 'an equal lastUpdate keeps the local value')
  assert.equal(getter('getUserMediaProgress')('li1').currentTime, 100)

  await s.onUserItemProgressUpdated({ ...socketEvent, data: progressOf('li1', 300, { lastUpdate: 600 }) })
  assert.equal(emitted.at(-1).applied, true)
  assert.equal(getter('getUserMediaProgress')('li1').currentTime, 300)
  assert.equal(store.state.user.mediaProgress.length, 1, 'only that row is replaced')
})

/** A component's script on a fake context: real computed getters and methods. */
async function component(path, sandboxExtra, ctxExtra) {
  const source = await read(path)
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { progressTarget, isCopyOfActiveServer, requiresServer: {}, Dialog: { confirm: async () => ({ value: true }) }, AbsFileSystem: {}, AbsDownloader: {}, console: { ...console, log() {} }, ...sandboxExtra }
  vm.runInNewContext(script, sandbox)
  const def = sandbox.component
  const calls = []
  const toasts = []
  const ctx = {
    $absData: new Proxy({}, { get: (_, name) => async (args) => calls.push([name, plain(args)]) }),
    $db: new Proxy({}, { get: (_, name) => async (args) => (calls.push(['$db.' + name, plain(args)]), {}) }),
    $nativeHttp: new Proxy({}, { get: (_, name) => (...args) => (calls.push(['$nativeHttp.' + name, args]), Promise.resolve()) }),
    $toast: { error: (m) => toasts.push(m), success() {} },
    $strings: {},
    $hapticsImpact: async () => {},
    $emit() {},
    ...ctxExtra
  }
  for (const [name, d] of Object.entries(def.props || {})) if (!(name in ctx)) ctx[name] = typeof d?.default === 'function' ? d.default() : undefined
  for (const [name, d] of Object.entries(def.computed || {})) {
    if (name in ctx) continue
    Object.defineProperty(ctx, name, { get: (d.get || d).bind(ctx), set: d.set?.bind(ctx), configurable: true })
  }
  for (const [name, fn] of Object.entries(def.methods || {})) ctx[name] = fn.bind(ctx)
  return { ctx, calls, toasts }
}

// A cold start without a connection: no session address, the downloaded copy's server is the active one
const coldStore = (extra = {}) => ({
  state: { activeServerConfigId: 'server-a', libraries: {}, user: { user: null } },
  getters: { 'user/getServerAddress': null, 'globals/getLocalMediaProgressById': () => null, 'user/getUserMediaProgress': () => null, ...extra },
  commit() {}
})

const localCopy = { id: 'local_li1', isLocal: true, libraryItemId: 'li1', serverConnectionConfigId: 'server-a', serverAddress: 'https://a.test.invalid', mediaType: 'book', media: { metadata: {} } }

test('mark finished on a server item goes through $absData even with a downloaded copy', async () => {
  const serverItem = { id: 'li1', mediaType: 'book', media: { metadata: {} }, localLibraryItem: { id: 'local_li1' } }
  const { ctx, calls, toasts } = await component('../components/modals/ItemMoreMenuModal.vue', {}, { libraryItem: serverItem, episode: null, $store: coldStore() })
  await ctx.toggleFinished()
  assert.deepEqual(calls, [['markFinished', { libraryItemId: 'li1', episodeId: null, serverConfigId: null, isFinished: true }]])
  assert.deepEqual(toasts, [])
})

test('downloaded item is server-linked offline', async () => {
  const { ctx, calls, toasts } = await component('../components/modals/ItemMoreMenuModal.vue', {}, { libraryItem: localCopy, episode: null, $store: coldStore() })
  assert.equal(ctx.serverLibraryItemId, 'li1', 'linked by its saved server, not by the empty session address')
  await ctx.toggleFinished()
  await ctx.clearProgressClick()
  assert.deepEqual(calls, [
    ['markFinished', { libraryItemId: 'li1', episodeId: null, serverConfigId: 'server-a', isFinished: true }],
    ['resetProgress', { libraryItemId: 'li1', episodeId: null, serverConfigId: 'server-a' }]
  ])
  assert.deepEqual(toasts, [])

  // A copy of a saved server that is not active: its server pages are not reachable, its writes still go to it
  const other = await component('../components/modals/ItemMoreMenuModal.vue', {}, { libraryItem: { ...localCopy, serverConnectionConfigId: 'server-b' }, episode: null, $store: coldStore() })
  assert.equal(other.ctx.serverLibraryItemId, null)
  await other.ctx.toggleFinished()
  assert.deepEqual(other.calls, [['markFinished', { libraryItemId: 'li1', episodeId: null, serverConfigId: 'server-b', isFinished: true }]])
})

test('local-only item keeps the Paper path', async () => {
  const unlinked = { id: 'local_x', isLocal: true, libraryItemId: null, serverConnectionConfigId: null, mediaType: 'book', media: { metadata: {} } }
  const { ctx, calls } = await component('../components/modals/ItemMoreMenuModal.vue', {}, { libraryItem: unlinked, episode: null, $store: coldStore() })
  await ctx.toggleFinished()
  assert.deepEqual(calls, [['$db.updateLocalMediaProgressFinished', { localLibraryItemId: 'local_x', isFinished: true }]])
})

test('episode rows: a downloaded podcast marks its server episode on its server (E9)', async () => {
  const serverEpisode = { id: 'local-ep', serverEpisodeId: 'ep1', title: 'E' }
  const row = await component('../components/tables/podcast/EpisodeRow.vue', { cellularPermissionHelpers: {} }, {
    libraryItemId: 'local_pod', episode: serverEpisode, isLocal: true, serverLibraryItemId: 'pod1', serverConfigId: 'server-a', localLibraryItemId: null, localEpisode: null, $store: coldStore()
  })
  await row.ctx.toggleFinished()
  assert.deepEqual(row.calls, [['markFinished', { libraryItemId: 'pod1', episodeId: 'ep1', serverConfigId: 'server-a', isFinished: true }]])

  const table = await read('../components/tables/podcast/EpisodesTable.vue')
  assert.match(table, /:server-library-item-id="isLocal \? libraryItem\.libraryItemId : null" :server-config-id="isLocal \? libraryItem\.serverConnectionConfigId : null"/)

  const latest = await component('../components/tables/podcast/LatestEpisodeRow.vue', { CellularPermissionHelpers: {} }, {
    libraryItemId: 'pod1', episode: { id: 'ep1' }, localEpisode: { id: 'local-ep' }, localLibraryItemId: 'local_pod', isLocal: false, $store: coldStore()
  })
  await latest.ctx.toggleFinished()
  assert.deepEqual(latest.calls, [['markFinished', { libraryItemId: 'pod1', episodeId: 'ep1', isFinished: true }]])
})

test('readers save the e-book position through $absData for a linked copy', async () => {
  const { ctx, calls } = await component('../components/readers/ComicReader.vue', { Path: {}, Archive: { init() {} }, CompressedFile: {} }, {
    libraryItem: localCopy, isLocal: true, keepProgress: true, numPages: 10, page: 3, $store: coldStore()
  })
  Object.defineProperty(ctx, 'savedPage', { get: () => 1 })
  await ctx.updateProgress()
  assert.deepEqual(calls, [['updateEbookProgress', { libraryItemId: 'li1', serverConfigId: 'server-a', ebookLocation: '3', ebookProgress: 0.2 }]])
})

test('no direct me/progress writes remain', async () => {
  const files = []
  const walk = async (dir) => {
    for (const entry of await readdir(new URL(dir, import.meta.url), { withFileTypes: true })) {
      if (entry.isDirectory()) await walk(`${dir}${entry.name}/`)
      else if (entry.name.endsWith('.vue') || entry.name.endsWith('.js')) files.push(`${dir}${entry.name}`)
    }
  }
  await walk('../components/')
  await walk('../pages/')
  await walk('../layouts/')
  const offenders = []
  for (const file of files) {
    if (/\$nativeHttp\s*\.\s*(patch|delete)\(\s*`\/api\/me\/progress/.test(await read(file))) offenders.push(file)
  }
  assert.deepEqual(offenders, [])
})

test('progressTarget: server items, linked copies, and copies without a server', () => {
  assert.deepEqual(progressTarget({ id: 'li1' }), { libraryItemId: 'li1', episodeId: null, serverConfigId: null })
  assert.deepEqual(progressTarget({ id: 'pod1' }, { id: 'ep1' }), { libraryItemId: 'pod1', episodeId: 'ep1', serverConfigId: null })
  assert.deepEqual(progressTarget(localCopy), { libraryItemId: 'li1', episodeId: null, serverConfigId: 'server-a' })
  assert.deepEqual(progressTarget(localCopy, { id: 'local-ep', serverEpisodeId: 'ep1' }), { libraryItemId: 'li1', episodeId: 'ep1', serverConfigId: 'server-a' })
  assert.equal(progressTarget(localCopy, { id: 'local-ep' }), null, 'an episode without its server id')
  assert.equal(progressTarget({ ...localCopy, serverConnectionConfigId: null }), null)
  assert.equal(progressTarget(null), null)
  assert.equal(isCopyOfActiveServer(localCopy, 'server-a'), true)
  assert.equal(isCopyOfActiveServer(localCopy, null), false)
})
