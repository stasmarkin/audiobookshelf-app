// The catalog in the WebView (offline-first release 4a): $absData reads libraries and items from native replica.db
// (utils/dataService.js), falls back to the server while a library was never reconciled, keeps the libraries store
// current from native's `catalog` notices, and the socket hands catalog events to native. Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const { createDataService, catalogTopics, CATALOG_EVENTS } = await import(`data:text/javascript;base64,${Buffer.from(await read('../utils/dataService.js')).toString('base64')}`)
const plain = (value) => JSON.parse(JSON.stringify(value))

const flush = async (n = 20) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

const replica = (reconciledAt = 5) => ({ reconciledAt, complete: reconciledAt != null })

function fakePlugin(overrides = {}) {
  const listeners = {}
  const calls = []
  const plugin = {
    calls,
    addListener: (name, cb) => ((listeners[name] = cb), { remove() {} }),
    send: (name, payload) => listeners[name]?.(payload),
    // No state: the store keeps what the test set up
    getServerState: async () => ({}),
    getUserData: async () => ({}),
    async getLibraries() {
      calls.push(['getLibraries'])
      return { serverConfigId: 'a', libraries: [{ id: 'l1' }], replica: replica() }
    },
    async getLibrary(args) {
      calls.push(['getLibrary', args])
      return { serverConfigId: 'a', library: { id: args.id, mediaType: 'book' }, filterdata: { authors: [] }, issues: 1, numUserPlaylists: 2, replica: replica() }
    },
    async getLibraryItems(args) {
      calls.push(['getLibraryItems', args])
      return { serverConfigId: 'a', results: [{ id: 'li1' }], total: 1, replica: replica() }
    },
    async setCurrentLibrary(args) {
      calls.push(['setCurrentLibrary', args])
    },
    ...overrides
  }
  return plugin
}

function fakeStore({ serverState = 'online', activeServerConfigId = 'a', signedIn = true, currentLibraryId = 'l1' } = {}) {
  const store = {
    state: { serverState, activeServerConfigId, user: { serverConnectionConfig: signedIn ? { id: activeServerConfigId } : null }, libraries: { currentLibraryId } },
    commits: [],
    dispatched: [],
    commit(type, payload) {
      store.commits.push(type)
      if (type === 'setServerState') store.state.serverState = payload
      if (type === 'setActiveServerConfigId') store.state.activeServerConfigId = payload
    },
    async dispatch(type, payload) {
      store.dispatched.push([type, payload])
      store.onDispatch?.(type, payload)
    }
  }
  return store
}

function fakeHttp(answer = (path) => ({ path })) {
  const paths = []
  return { paths, get: async (path) => (paths.push(path), answer(path)) }
}

const service = ({ plugin = fakePlugin(), store = fakeStore(), http = fakeHttp(), platform = null, $axios = null, emitted = [] } = {}) => ({
  plugin,
  store,
  http,
  emitted,
  data: createDataService({ plugin, store, platform, $axios, eventBus: { $emit: (name) => emitted.push(name) }, nativeHttp: () => http })
})

test('catalog reads go to native and drop answers of the previous server', async () => {
  let n = 0
  const plugin = fakePlugin({
    async getLibraryItems() {
      n++
      return { serverConfigId: n === 1 ? 'a' : 'b', results: [{ id: `li${n}` }], total: 1, replica: replica() }
    }
  })
  const { data } = service({ plugin, store: fakeStore({ activeServerConfigId: 'b' }) })
  const answer = await data.getLibraryItems({ libraryId: 'l1', limit: 20, page: 0 })
  assert.equal(n, 2)
  assert.equal(answer.results[0].id, 'li2')
})

/** store/libraries.js actions with a fake $absData and $localStore. */
async function librariesStore({ hasActiveServer = true, lastLibraryId = null, libraries = [{ id: 'l1' }, { id: 'l2' }], currentLibraryId = '' } = {}) {
  const source = (await read('../store/libraries.js')).replace(/^import .*$/gm, '').replace(/^export const (\w+) =/gm, 'globalThis.$1 =')
  const sandbox = { console: { ...console, error: () => {} }, require: () => ({ BookCoverAspectRatio: {} }) }
  vm.runInNewContext(source, sandbox)
  const calls = []
  const state = { currentLibraryId, lastLoad: 0, libraries: [] }
  const commits = []
  const self = {
    $absData: {
      getLibraries: async () => (calls.push('getLibraries'), { libraries }),
      getLibrary: async ({ id }) => (calls.push(['getLibrary', id]), { library: { id, mediaType: 'book' }, filterdata: { genres: ['g'] }, issues: 3, numUserPlaylists: 4 }),
      setCurrentLibrary: async (args) => calls.push(['setCurrentLibrary', args.libraryId])
    },
    $localStore: { getLastLibraryId: async () => lastLibraryId }
  }
  const ctx = {
    state,
    rootGetters: { getHasActiveServer: hasActiveServer },
    rootState: { user: { user: null } },
    dispatch() {},
    commit: (type, value) => {
      commits.push([type, value])
      if (type === 'setCurrentLibrary') state.currentLibraryId = value
      if (type === 'set') state.libraries = value
      if (type === 'setLastLoad') state.lastLoad = value
    }
  }
  return { actions: sandbox.actions, self, ctx, state, calls, commits }
}

test('libraries load from the replica without a signed-in user', async () => {
  const last = await librariesStore({ lastLibraryId: 'l2' })
  assert.equal(await last.actions.load.call(last.self, last.ctx), true)
  assert.deepEqual(last.calls, ['getLibraries'])
  assert.equal(last.state.currentLibraryId, 'l2', 'the last picked library when it is in the list')

  const gone = await librariesStore({ lastLibraryId: 'l9' })
  await gone.actions.load.call(gone.self, gone.ctx)
  assert.equal(gone.state.currentLibraryId, 'l1', 'otherwise the first')

  const kept = await librariesStore({ lastLibraryId: 'l2', currentLibraryId: 'l1' })
  await kept.actions.load.call(kept.self, kept.ctx)
  assert.equal(kept.state.currentLibraryId, 'l1', 'a current library still in the list stays')

  const none = await librariesStore({ hasActiveServer: false })
  assert.equal(await none.actions.load.call(none.self, none.ctx), false)
  assert.deepEqual(none.calls, [])

  // The 5-minute cache, and force skipping it
  assert.equal(await last.actions.load.call(last.self, last.ctx), false)
  assert.equal(await last.actions.load.call(last.self, last.ctx, { force: true }), true)
})

test('libraries fetch takes the cached filterdata shape', async () => {
  const s = await librariesStore({ currentLibraryId: 'l1' })
  await s.actions.fetch.call(s.self, s.ctx, 'l1')
  const commits = Object.fromEntries(s.commits)
  assert.deepEqual(plain(commits.addUpdate), { id: 'l1', mediaType: 'book' })
  assert.equal(commits.setLibraryIssues, 3)
  assert.deepEqual(plain(commits.setLibraryFilterData), { genres: ['g'] })
  assert.equal(commits.setNumUserPlaylists, 4)

  const none = await librariesStore({ hasActiveServer: false })
  assert.equal(await none.actions.fetch.call(none.self, none.ctx, 'l1'), false)
})

test('the native current library follows every switch', async () => {
  const s = await librariesStore()
  // First library picked by load, then fetched (as initLibraries does), then a pick in the libraries modal
  await s.actions.load.call(s.self, s.ctx)
  await s.actions.fetch.call(s.self, s.ctx, s.state.currentLibraryId)
  await s.actions.fetch.call(s.self, s.ctx, 'l2')
  await flush()
  assert.deepEqual(
    s.calls.filter((c) => c[0] === 'setCurrentLibrary').map((c) => c[1]),
    ['l1', 'l2']
  )
})

test('catalog change of the libraries topic reloads libraries; other libraries\' topics do not refetch the current one', async () => {
  const { plugin, store } = service()
  plugin.send('onDataChanged', { scope: 'catalog', ids: ['libraries'] })
  await flush()
  assert.deepEqual(plain(store.dispatched), [['libraries/load', { force: true }]])

  store.dispatched.length = 0
  plugin.send('onDataChanged', { scope: 'catalog', ids: ['library:l2', 'items:l1', 'state:l1'] })
  await flush()
  assert.deepEqual(store.dispatched, [])

  plugin.send('onDataChanged', { scope: 'catalog', ids: ['library:l1'] })
  await flush()
  assert.deepEqual(plain(store.dispatched), [['libraries/fetch', { libraryId: 'l1', onlyIfCurrent: true }]])
})

test('server switch reloads libraries and announces a library change', async () => {
  const store = fakeStore({ currentLibraryId: 'l1' })
  store.onDispatch = (type) => type === 'libraries/load' && (store.state.libraries.currentLibraryId = 'l7')
  const { plugin, emitted } = service({ store })
  await flush()
  store.dispatched.length = 0
  plugin.send('onServerState', { state: 'online', serverConfigId: 'b' })
  await flush()
  assert.deepEqual(plain(store.dispatched.filter(([t]) => t.startsWith('libraries/'))), [
    ['libraries/load', { force: true }],
    ['libraries/fetch', 'l7']
  ])
  assert.deepEqual(emitted, ['library-changed'])

  // The libraries topic switches only when the current library went away
  const steady = service()
  steady.plugin.send('onDataChanged', { scope: 'catalog', ids: ['libraries'] })
  await flush()
  assert.deepEqual(steady.emitted, [])
  assert.ok(!steady.store.dispatched.some(([t]) => t === 'libraries/fetch'))
})

test('fallback waits for the server address', async () => {
  const plugin = fakePlugin({ getLibraryItems: async () => ({ serverConfigId: 'a', results: [], total: 0, replica: replica(null) }) })
  const { data, http } = service({ plugin, store: fakeStore({ signedIn: false }) })
  const answer = await data.getLibraryItems({ libraryId: 'l1', limit: 20, page: 0 })
  assert.deepEqual(http.paths, [])
  assert.equal(answer.fromServer, undefined)
})

test('never-reconciled library reads the server while it is online', async () => {
  const never = fakePlugin({
    getLibraryItems: async () => ({ serverConfigId: 'a', results: [], total: 0, replica: replica(null) }),
    getLibraries: async () => ({ serverConfigId: 'a', libraries: [], replica: replica(null) }),
    getLibrary: async () => ({ serverConfigId: 'a', library: null, filterdata: null, issues: 0, numUserPlaylists: 0, replica: replica(null) })
  })
  const http = fakeHttp((path) => {
    if (path.includes('/items?')) return { results: [{ id: 'srv1' }], total: 1 }
    if (path.includes('include=filterdata')) return { library: { id: 'l1' }, filterdata: { series: [] }, issues: 2, numUserPlaylists: 0 }
    return { libraries: [{ id: 'l1' }] }
  })
  const online = service({ plugin: never, http })
  const items = await online.data.getLibraryItems({ libraryId: 'lib1', sort: 'media.metadata.title', desc: true, filter: 'genres.RmFudGFzeQ%3D%3D', limit: 20, page: 1 })
  assert.deepEqual(http.paths[0], '/api/libraries/lib1/items?filter=genres.RmFudGFzeQ%3D%3D&sort=media.metadata.title&desc=1&limit=20&page=1&minified=1&include=rssfeed,numEpisodesIncomplete')
  assert.equal(items.fromServer, true)
  assert.deepEqual(plain(items.results), [{ id: 'srv1' }])
  assert.equal(items.replica.reconciledAt, null, "native's replica state is kept")
  assert.deepEqual(plain((await online.data.getLibraries()).libraries), [{ id: 'l1' }])
  const library = await online.data.getLibrary({ id: 'l1' })
  assert.equal(library.issues, 2)
  assert.deepEqual(plain(library.library), { id: 'l1' })

  const offline = service({ plugin: never, http: fakeHttp(), store: fakeStore({ serverState: 'offline' }) })
  const native = await offline.data.getLibraryItems({ libraryId: 'lib1', limit: 20, page: 0 })
  assert.equal(native.total, 0)
  assert.deepEqual(offline.http.paths, [])
  await assert.rejects(() => offline.data.getLibrary({ id: 'l1' }), /not found/)

  // A server error shows the replica rather than nothing
  const failing = service({ plugin: never, http: { get: async () => Promise.reject(new Error('502')) } })
  assert.equal((await failing.data.getLibraryItems({ libraryId: 'lib1', limit: 20, page: 0 })).total, 0)
})

test('unsupported filter online proxies to the server', async () => {
  const plugin = fakePlugin({ getLibraryItems: async () => ({ serverConfigId: 'a', results: [], total: 0, unsupportedFilter: true, replica: replica(9) }) })
  const http = fakeHttp(() => ({ results: [{ id: 'x' }], total: 1 }))
  const online = service({ plugin, http })
  const answer = await online.data.getLibraryItems({ libraryId: 'l1', filter: 'ebooks.c3VwcGxlbWVudGFyeQ%3D%3D', limit: 20, page: 0 })
  assert.match(http.paths[0], /filter=ebooks\.c3VwcGxlbWVudGFyeQ%3D%3D/)
  assert.equal(answer.fromServer, true)
  assert.equal(answer.replica.reconciledAt, 9)

  const offline = service({ plugin, store: fakeStore({ serverState: 'offline' }) })
  const native = await offline.data.getLibraryItems({ libraryId: 'l1', filter: 'ebooks.c3VwcGxlbWVudGFyeQ%3D%3D', limit: 20, page: 0 })
  assert.equal(native.unsupportedFilter, true)
  assert.equal(native.fromServer, undefined)
})

test('catalogTopics maps every event family', async () => {
  const table = [
    ['items_updated', [{ id: 'a', libraryId: 'l1' }, { id: 'b', libraryId: 'l2' }], ['items:l1', 'items:l2']],
    ['item_added', { id: 'a', libraryId: 'l1', media: { metadata: { series: [{ id: 's1' }] } } }, ['items:l1', 'series:l1']],
    ['item_removed', { id: 'a', libraryId: 'l1' }, ['items:l1']],
    ['item_updated', { id: 'a' }, []],
    ['library_updated', { id: 'l1' }, ['libraries', 'library:l1']],
    ['series_removed', { id: 's', libraryId: 'l1' }, ['series:l1']],
    ['playlist_added', { id: 'p', libraryId: 'l1' }, ['playlists:l1', 'library:l1']],
    ['collection_updated', { id: 'c', libraryId: 'l1' }, ['collections:l1']],
    ['author_updated', { id: 'au', libraryId: 'l1' }, ['authors:l1', 'library:l1']],
    ['rss_feed_open', { id: 'f', entityType: 'libraryItem', entityId: 'a', feedUrl: '/feed/x', meta: {} }, ['items:cur']],
    ['rss_feed_closed', { id: 'f', entityType: 'libraryItem', entityId: 'a' }, ['items:cur']],
    ['rss_feed_open', { id: 'f', entityType: 'series', entityId: 's' }, []],
    ['backup_applied', {}, ['libraries']]
  ]
  for (const [event, payload, topics] of table) assert.deepEqual(catalogTopics(event, payload, 'cur'), topics, event)
  assert.ok(!catalogTopics('items_added', [{ id: 'x' }], 'cur').some((t) => t.includes('undefined')))

  // In the browser the event goes the same way as native's notice, store included
  const store = fakeStore({ currentLibraryId: 'cur' })
  const seen = []
  const web = service({ platform: 'web', store, $axios: { $get: async () => ({}) } })
  web.data.onDataChanged((change) => seen.push(change))
  assert.deepEqual(plain(await web.data.applyServerEvent({ event: 'library_updated', payload: { id: 'cur' } })), { applied: false })
  await flush()
  assert.deepEqual(plain(seen), [{ scope: 'catalog', ids: ['libraries', 'library:cur'] }])
  assert.deepEqual(plain(store.dispatched[0]), ['libraries/load', { force: true }])
})

test('web dev fallback proxies catalog reads to $axios', async () => {
  const gets = []
  const $axios = { $get: async (path, options) => (gets.push([path, options?.params ?? null]), path.endsWith('/items') ? { results: [], total: 0 } : path === '/api/libraries' ? { libraries: [{ id: 'l1' }] } : { library: { id: 'l1' }, filterdata: {}, issues: 0 }) }
  const { data, plugin } = service({ platform: 'web', $axios })
  const libraries = await data.getLibraries()
  assert.equal(libraries.replica.complete, true)
  assert.ok(libraries.replica.reconciledAt > 0)
  await data.getLibrary({ id: 'l1' })
  await data.getLibraryItems({ libraryId: 'l1', sort: 'addedAt', desc: true, filter: 'issues', collapseseries: true, limit: 50, page: 2 })
  assert.deepEqual(plain(gets), [
    ['/api/libraries', null],
    ['/api/libraries/l1?include=filterdata', null],
    ['/api/libraries/l1/items', { limit: 50, page: 2, minified: 1, include: 'rssfeed,numEpisodesIncomplete', filter: 'issues', sort: 'addedAt', desc: 1, collapseseries: 1 }]
  ])
  assert.deepEqual(plugin.calls, [], 'native is not asked in the browser')

  const missing = service({ platform: 'web', $axios: { $get: async () => Promise.reject(Object.assign(new Error('404'), { response: { status: 404 } })) } })
  await assert.rejects(() => missing.data.getLibrary({ id: 'gone' }), /not found/)
})

/** Loads a Nuxt plugin source with its named and default imports replaced by the given fakes. */
async function loadPlugin(path, deps) {
  let src = await read(path)
  src = src.replace(/^import \{([^}]+)\} from '[^']+'\n/gm, (_, names) => `const {${names}} = __deps\n`)
  src = src.replace(/^import (\w+) from '[^']+'\n/gm, (_, name) => `const ${name} = __deps.${name}\n`)
  src = src.replace('export default ', 'return ')
  return new Function('__deps', src)(deps)
}

test('socket catalog events are forwarded with the server captured on arrival', async () => {
  const { EventEmitter } = await import('node:events')
  const handlers = {}
  const socket = { on: (name, fn) => (handlers[name] = fn), io: { on() {} } }
  const store = { state: { activeServerConfigId: 'a' }, commit() {}, getters: {} }
  const forwarded = []
  let release
  const app = { $absData: { applyServerEvent: (args) => (forwarded.push(args), new Promise((resolve) => (release = resolve))) } }
  let $socket
  const plugin = await loadPlugin('../plugins/server.js', { io: () => socket, EventEmitter, CATALOG_EVENTS })
  plugin({ app, store }, (name, value) => ($socket = value))
  $socket.connect('https://a.test.invalid', 'token')
  for (const event of CATALOG_EVENTS) assert.equal(typeof handlers[event], 'function', event)

  handlers.item_updated({ id: 'li1', libraryId: 'l1' })
  store.state.activeServerConfigId = 'b'
  release({ applied: true })
  await flush()
  handlers.backup_applied()
  await flush()
  assert.deepEqual(plain(forwarded), [
    { event: 'item_updated', payload: { id: 'li1', libraryId: 'l1' }, serverConfigId: 'a' },
    { event: 'backup_applied', payload: {}, serverConfigId: 'b' }
  ])
})

test('a server answer that arrives after a switch is dropped', async () => {
  let release
  const plugin = fakePlugin({
    getLibraryItems: async () => ({ serverConfigId: store.state.activeServerConfigId, results: [{ id: `native-${store.state.activeServerConfigId}` }], total: 1, replica: replica(null) })
  })
  const store = fakeStore()
  const http = { get: () => new Promise((resolve) => (release = resolve)) }
  const { data } = service({ plugin, store, http })
  const read = data.getLibraryItems({ libraryId: 'l1', limit: 20, page: 0 })
  await flush()
  // Switched to b, which this session is not signed in to
  store.state.activeServerConfigId = 'b'
  release({ results: [{ id: 'from-a' }], total: 1 })
  const answer = await read
  assert.deepEqual(plain(answer.results), [{ id: 'native-b' }])
  assert.equal(answer.fromServer, undefined)

  // Signed in to another server than the active one: no server read at all
  const other = service({ plugin: fakePlugin({ getLibraryItems: async () => ({ serverConfigId: 'a', results: [], total: 0, replica: replica(null) }) }), store: fakeStore() })
  other.store.state.user.serverConnectionConfig = { id: 'z' }
  await other.data.getLibraryItems({ libraryId: 'l1', limit: 20, page: 0 })
  assert.deepEqual(other.http.paths, [])
})

test('a narrator with a comma online goes to the server', async () => {
  // Native answers unsupportedFilter for it (LibraryQuery): online the server, offline "requires the server"
  const plugin = fakePlugin({ getLibraryItems: async () => ({ serverConfigId: 'a', results: [], total: 0, unsupportedFilter: true, replica: replica(9) }) })
  const http = fakeHttp(() => ({ results: [{ id: 'x' }], total: 1 }))
  const filter = `narrators.${encodeURIComponent(Buffer.from('Doe, Jane').toString('base64'))}`
  const { data } = service({ plugin, http })
  const answer = await data.getLibraryItems({ libraryId: 'l1', filter, limit: 20, page: 0 })
  assert.equal(answer.fromServer, true)
  assert.ok(http.paths[0].includes(`filter=${filter}`))
})

test('an empty never-reconciled replica does not count as a loaded list', async () => {
  const s = await librariesStore({ libraries: [] })
  s.self.$absData.getLibraries = async () => ({ libraries: [], replica: { reconciledAt: null } })
  await s.actions.load.call(s.self, s.ctx)
  assert.equal(s.state.lastLoad, 0)
  assert.equal(await s.actions.load.call(s.self, s.ctx), true, 'asked again without force')
})

test('a server error after a switch reads the new server, not the old replica', async () => {
  let fail
  const store = fakeStore()
  const plugin = fakePlugin({
    getLibraryItems: async () => ({ serverConfigId: store.state.activeServerConfigId, results: [{ id: `native-${store.state.activeServerConfigId}` }], total: 1, replica: replica(null) })
  })
  const http = { get: () => new Promise((_, reject) => (fail = reject)) }
  const { data } = service({ plugin, store, http })
  const read = data.getLibraryItems({ libraryId: 'l1', limit: 20, page: 0 })
  await flush()
  store.state.activeServerConfigId = 'b'
  fail(new Error('502'))
  assert.deepEqual(plain((await read).results), [{ id: 'native-b' }])
})
