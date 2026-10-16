// The home page from the replica (offline-first release 5): $absData.getPersonalized reads native's shelves and falls
// back to the server while the library was never reconciled; the page merges them with the downloaded copies and its
// local shelves, and re-reads them on native's notices. Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { createDataService, catalogTopics } = await importSource('../utils/dataService.js')
const localCopyUrl = `data:text/javascript;base64,${Buffer.from(await read('../utils/localCopy.js')).toString('base64')}`
const { findLocalCopy } = await import(localCopyUrl)
// homeShelves imports localCopy through the webpack alias
const homeSource = (await read('../utils/homeShelves.js')).replace("'@/utils/localCopy'", `'${localCopyUrl}'`)
const { mergeHomeShelves, shouldRefreshHome } = await import(`data:text/javascript;base64,${Buffer.from(homeSource).toString('base64')}`)
const plain = (value) => JSON.parse(JSON.stringify(value))

const replica = (reconciledAt = 5) => ({ reconciledAt, complete: reconciledAt != null })
const shelf = (id, type, entities, extra = {}) => ({ id, label: id, labelStringKey: null, type, entities, total: entities.length, ...extra })

function fakePlugin(overrides = {}) {
  const calls = []
  return {
    calls,
    addListener: () => ({ remove() {} }),
    getServerState: async () => ({}),
    getUserData: async () => ({}),
    async getPersonalized(args) {
      calls.push(['getPersonalized', args])
      return { serverConfigId: 'a', libraryId: args.libraryId, shelves: [shelf('continue-listening', 'book', [{ id: 'li1' }])], replica: replica() }
    },
    async search(args) {
      calls.push(['search', args])
      return { serverConfigId: 'a', libraryId: args.libraryId, q: args.q, results: { book: [{ libraryItem: { id: 'li1' } }] }, replica: replica() }
    },
    ...overrides
  }
}

function fakeStore({ serverState = 'online', activeServerConfigId = 'a', signedIn = true } = {}) {
  return {
    state: { serverState, activeServerConfigId, user: { serverConnectionConfig: signedIn ? { id: activeServerConfigId } : null }, libraries: { currentLibraryId: 'lib1' } },
    commit() {},
    async dispatch() {}
  }
}

function fakeHttp(answer = () => []) {
  const paths = []
  return { paths, get: async (path) => (paths.push(path), answer(path)) }
}

const service = ({ plugin = fakePlugin(), store = fakeStore(), http = fakeHttp(), platform = null, $axios = null } = {}) => ({
  plugin,
  store,
  http,
  data: createDataService({ plugin, store, platform, $axios, nativeHttp: () => http })
})

test('home reads the replica and falls back while never reconciled', async () => {
  const asked = []
  const never = fakePlugin({ getPersonalized: async (args) => (asked.push(args), { serverConfigId: 'a', libraryId: args.libraryId, shelves: [], replica: replica(null) }) })
  const serverShelves = [shelf('recently-added', 'book', [{ id: 'srv1' }])]
  const online = service({ plugin: never, http: fakeHttp(() => serverShelves) })
  const answer = await online.data.getPersonalized({ libraryId: 'lib1' })
  assert.deepEqual(online.http.paths, ['/api/libraries/lib1/personalized?minified=1&include=rssfeed,numEpisodesIncomplete&limit=10'])
  assert.equal(answer.fromServer, true)
  assert.deepEqual(plain(answer.shelves), plain(serverShelves))
  assert.equal(answer.libraryId, 'lib1')
  assert.equal(answer.serverConfigId, 'a')
  assert.equal(answer.replica.reconciledAt, null, "native's replica state is kept")

  const offline = service({ plugin: never, store: fakeStore({ serverState: 'offline' }) })
  const native = await offline.data.getPersonalized({ libraryId: 'lib1', limit: 3 })
  assert.deepEqual(offline.http.paths, [])
  assert.equal(native.fromServer, undefined)
  assert.deepEqual(plain(asked.at(-1)), { libraryId: 'lib1', limit: 3 })

  // A reconciled library reads the replica online too: the same shelves as offline
  const reconciled = service({ http: fakeHttp(() => assert.fail('a reconciled replica does not go to the server')) })
  const fromReplica = await reconciled.data.getPersonalized({ libraryId: 'lib1' })
  assert.equal(fromReplica.fromServer, undefined)
  assert.equal(fromReplica.shelves[0].id, 'continue-listening')
})

test('search query is encoded on the fallback path', async () => {
  const never = fakePlugin({ search: async ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results: {}, replica: replica(null) }) })
  const groups = { book: [{ libraryItem: { id: 'srv1' } }], authors: [] }
  const online = service({ plugin: never, http: fakeHttp(() => groups) })
  const answer = await online.data.search({ libraryId: 'lib1', q: 'a&b #1' })
  assert.deepEqual(online.http.paths, ['/api/libraries/lib1/search?q=a%26b%20%231&limit=12'])
  assert.equal(answer.fromServer, true)
  assert.deepEqual(plain(answer.results), groups, 'the server answers the groups at the top level, the page reads them under results')
  assert.equal(answer.libraryId, 'lib1')
  assert.equal(answer.q, 'a&b #1')
  assert.equal(answer.serverConfigId, 'a')

  const odd = service({ plugin: never, http: fakeHttp(() => null) })
  assert.deepEqual(plain((await odd.data.search({ libraryId: 'lib1', q: 'x', limit: 5 })).results), {})
  assert.deepEqual(odd.http.paths, ['/api/libraries/lib1/search?q=x&limit=5'])
})

test('answer of the previous server is read again', async () => {
  const store = fakeStore({ activeServerConfigId: 'b' })
  let n = 0
  const plugin = fakePlugin({
    getPersonalized: async ({ libraryId }) => ({ serverConfigId: ++n === 1 ? 'a' : 'b', libraryId, shelves: [shelf(`s${n}`, 'book', [{ id: 'x' }])], replica: replica() }),
    search: async ({ libraryId, q }) => ({ serverConfigId: ++n === 3 ? 'a' : 'b', libraryId, q, results: { book: [{ libraryItem: { id: `li${n}` } }] }, replica: replica() })
  })
  const { data } = service({ plugin, store })
  assert.equal((await data.getPersonalized({ libraryId: 'lib1' })).shelves[0].id, 's2')
  assert.equal((await data.search({ libraryId: 'lib1', q: 'x' })).results.book[0].libraryItem.id, 'li4')
  assert.equal(n, 4)

  // A switch while the server answered the fallback: read again for the new server
  let release
  const switching = fakeStore()
  const nativeOf = fakePlugin({ getPersonalized: async ({ libraryId }) => ({ serverConfigId: switching.state.activeServerConfigId, libraryId, shelves: [], replica: replica(null) }) })
  const s = service({ plugin: nativeOf, store: switching, http: { get: () => new Promise((resolve) => (release = resolve)) } })
  const pending = s.data.getPersonalized({ libraryId: 'lib1' })
  for (let i = 0; i < 20; i++) await Promise.resolve()
  switching.state.activeServerConfigId = 'b'
  release([shelf('from-a', 'book', [{ id: 'x' }])])
  const answer = await pending
  assert.equal(answer.serverConfigId, 'b')
  assert.equal(answer.fromServer, undefined)
  assert.deepEqual(plain(answer.shelves), [])
})

test('local copies are joined only for the active server', () => {
  const copies = [
    { id: 'local_b', libraryItemId: 'li1', serverConnectionConfigId: 'b' },
    { id: 'local_b_ino', libraryItemId: 'other', ino: 'ino9', serverConnectionConfigId: 'b' },
    { id: 'local_a_ino', libraryItemId: 'moved', ino: 'ino7', serverConnectionConfigId: 'a' }
  ]
  assert.equal(findLocalCopy(copies, { id: 'li1' }, 'a'), null)
  assert.equal(findLocalCopy(copies, { id: 'li9', ino: 'ino9' }, 'a'), null)
  assert.equal(findLocalCopy(copies, { id: 'li7', ino: 'ino7' }, 'a').id, 'local_a_ino')
  assert.equal(findLocalCopy(copies, { id: 'li1' }, null).id, 'local_b')
  assert.equal(findLocalCopy(copies, null, 'a'), null)
  assert.equal(findLocalCopy(undefined, { id: 'li1' }, 'a'), null)

  const answer = { serverConfigId: 'a', libraryId: 'lib1', replica: replica(), shelves: [shelf('recently-added', 'book', [{ id: 'li1' }, { id: 'li7', ino: 'ino7' }]), shelf('recent-series', 'series', [{ id: 's1', books: [] }])] }
  const { shelves } = mergeHomeShelves({ answer, localShelves: [], localItems: copies, activeServerConfigId: 'a', mediaType: 'book' })
  assert.equal(shelves[0].entities[0].localLibraryItem, undefined)
  assert.equal(shelves[0].entities[1].localLibraryItem.id, 'local_a_ino')
  assert.equal(shelves[1].entities[0].localLibraryItem, undefined, 'series are not joined')
})

test('local continue shelves only without replica shelves', () => {
  const local = [
    shelf('local-books-continue', 'book', [{ id: 'lc' }], { localOnly: true }),
    shelf('local-episodes-continue', 'episode', [{ id: 'lp' }], { localOnly: true }),
    shelf('local-books', 'book', [{ id: 'lc' }]),
    shelf('local-podcasts', 'podcast', [{ id: 'lp' }])
  ]
  const ids = (result) => result.shelves.map((s) => s.id)
  const merge = (answer, mediaType = 'book') => mergeHomeShelves({ answer, localShelves: local, localItems: [], activeServerConfigId: 'a', mediaType })
  const replicaShelves = [shelf('continue-listening', 'book', [{ id: 'li1' }])]

  const reconciled = merge({ serverConfigId: 'a', shelves: replicaShelves, replica: replica() })
  assert.deepEqual(ids(reconciled), ['continue-listening', 'local-books'])
  assert.equal(reconciled.catalogNotLoaded, false)

  const notReconciled = merge({ serverConfigId: 'a', shelves: replicaShelves, replica: replica(null) })
  assert.deepEqual(ids(notReconciled), ['continue-listening', 'local-books-continue', 'local-books'])
  assert.equal(notReconciled.catalogNotLoaded, false)
  assert.deepEqual(ids(merge({ serverConfigId: 'a', shelves: [shelf('recently-added', 'podcast', [{ id: 'p' }])], replica: replica(null) }, 'podcast')), ['recently-added', 'local-episodes-continue', 'local-podcasts'])

  const emptyNotReconciled = merge({ serverConfigId: 'a', shelves: [], replica: replica(null) })
  assert.deepEqual(ids(emptyNotReconciled), local.map((s) => s.id))
  assert.equal(emptyNotReconciled.catalogNotLoaded, true)

  for (const answer of [{ serverConfigId: 'a', shelves: [], replica: replica() }, { serverConfigId: 'a', shelves: [], replica: replica(null), fromServer: true }]) {
    const result = merge(answer)
    assert.deepEqual(ids(result), ['local-books'])
    assert.equal(result.catalogNotLoaded, false)
  }

  // A server answer (never reconciled, online) is not the replica's: no local continue shelves beside it
  assert.deepEqual(ids(merge({ serverConfigId: 'a', shelves: replicaShelves, replica: replica(null), fromServer: true })), ['continue-listening', 'local-books'])

  // Without an active server nothing was asked: the local shelves, and no "catalog not loaded"
  const noServer = mergeHomeShelves({ answer: null, localShelves: local, localItems: [], activeServerConfigId: null, mediaType: 'book' })
  assert.deepEqual(ids(noServer), local.map((s) => s.id))
  assert.equal(noServer.catalogNotLoaded, false)
})

test('playback tick refreshes the home only when the order may change', () => {
  const opts = (extra = {}) => ({ libraryId: 'lib1', continueKeys: ['a', 'b'], playingEpisodeId: null, refreshedForKey: null, ...extra })
  const tick = (ids) => ({ scope: 'playback', ids })
  assert.equal(shouldRefreshHome(tick(['a']), opts()), false)
  assert.equal(shouldRefreshHome(tick(['b']), opts()), true)
  assert.equal(shouldRefreshHome(tick(['c']), opts()), true)
  assert.equal(shouldRefreshHome(tick(['p']), opts({ continueKeys: ['p:e1'], playingEpisodeId: 'e1' })), false)
  assert.equal(shouldRefreshHome(tick(['p']), opts({ continueKeys: ['p:e1'], playingEpisodeId: 'e2' })), true)
  assert.equal(shouldRefreshHome(tick(['x']), opts({ refreshedForKey: 'x' })), false)
  assert.equal(shouldRefreshHome(tick(['a']), opts({ continueKeys: [] })), true, 'an empty "continue" gets the playing book')

  // The page's sequence: a tick re-reads once for its key, any other re-read forgets it
  const page = { continueKeys: ['b', 'a'], refreshedForKey: null, reads: 0 }
  const notice = (change) => {
    if (!shouldRefreshHome(change, { libraryId: 'lib1', continueKeys: page.continueKeys, playingEpisodeId: null, refreshedForKey: page.refreshedForKey })) return false
    page.reads++
    page.refreshedForKey = change.scope === 'playback' ? change.ids[0] : null
    return true
  }
  assert.equal(notice(tick(['a'])), true)
  assert.equal(page.refreshedForKey, 'a')
  assert.equal(notice(tick(['a'])), false, 'the next tick of the same book does not re-read every 15 s')
  assert.equal(notice({ scope: 'progress', ids: ['b'] }), true)
  assert.equal(page.refreshedForKey, null)
  assert.equal(notice(tick(['a'])), true)

  assert.equal(shouldRefreshHome({ scope: 'progress', ids: ['zz'] }, opts()), true)
  assert.equal(shouldRefreshHome({ scope: 'user', ids: [] }, opts()), true)
  assert.equal(shouldRefreshHome({ scope: 'catalog', ids: ['items:lib2'] }, opts()), false)
  for (const topic of ['items', 'series', 'authors', 'episodes', 'state', 'library']) assert.equal(shouldRefreshHome({ scope: 'catalog', ids: [`${topic}:lib1`] }, opts()), true, topic)
  assert.equal(shouldRefreshHome({ scope: 'catalog', ids: ['collections:lib1', 'playlists:lib1'] }, opts()), false)
  assert.equal(shouldRefreshHome({ scope: 'bookmarks', ids: ['a'] }, opts()), false)
  assert.equal(shouldRefreshHome(null, opts()), false)
})

test('podcast item events also change its episodes', () => {
  assert.deepEqual(catalogTopics('item_updated', { id: 'p', libraryId: 'l1', mediaType: 'podcast' }, 'cur'), ['items:l1', 'episodes:l1'])
  assert.deepEqual(catalogTopics('items_added', [{ id: 'p', libraryId: 'l1', mediaType: 'podcast' }, { id: 'b', libraryId: 'l2', mediaType: 'book' }], 'cur'), ['items:l1', 'episodes:l1', 'items:l2'])
  assert.deepEqual(catalogTopics('item_removed', { id: 'b', libraryId: 'l1', mediaType: 'book' }, 'cur'), ['items:l1'])
})

test('web dev fallback proxies home and search to $axios', async () => {
  const gets = []
  const $axios = { $get: async (path, options) => (gets.push([path, options?.params ?? null]), path.endsWith('/personalized') ? [shelf('discover', 'book', [{ id: 'x' }])] : { book: [], tags: [{ name: 't', numItems: 1 }] }) }
  const { data, plugin } = service({ platform: 'web', $axios })
  const home = await data.getPersonalized({ libraryId: 'lib1' })
  const found = await data.search({ libraryId: 'lib1', q: 'a b' })
  assert.deepEqual(plain(gets), [
    ['/api/libraries/lib1/personalized', { minified: 1, include: 'rssfeed,numEpisodesIncomplete', limit: 10 }],
    ['/api/libraries/lib1/search', { q: 'a b', limit: 12 }]
  ])
  assert.equal(home.shelves[0].id, 'discover')
  assert.equal(home.libraryId, 'lib1')
  assert.equal(home.serverConfigId, 'a')
  assert.equal(home.replica.complete, true)
  assert.deepEqual(plain(found.results.tags), [{ name: 't', numItems: 1 }])
  assert.equal(found.q, 'a b')
  assert.equal(found.libraryId, 'lib1')
  assert.equal(found.replica.complete, true)
  assert.deepEqual(plugin.calls, [], 'native is not asked in the browser')
})

// ---- The home page (pages/bookshelf/index.vue) as a plain object over fakes

const { createDebouncer } = await importSource('../utils/catalogRefresh.js')
const { continueKeysOf } = await import(`data:text/javascript;base64,${Buffer.from(homeSource).toString('base64')}`)

const fakeTimers = () => {
  let queue = []
  return {
    setTimer: (fn, ms) => {
      const t = { fn, ms }
      queue.push(t)
      return t
    },
    clearTimer: (t) => (queue = queue.filter((q) => q !== t)),
    run: () => {
      const due = queue
      queue = []
      due.forEach((t) => t.fn())
      return due.map((t) => t.ms)
    }
  }
}

const flush = async (n = 30) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

async function homePage({ user = null, activeServerConfigId = 'a', serverState = 'offline', hasActiveServer = true, currentLibraryId = 'lib1', mediaType = 'book', plugin = fakePlugin(), localItems = [], signedIn = !!user } = {}) {
  const source = await read('../pages/bookshelf/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const component = new Function('mergeHomeShelves', 'shouldRefreshHome', 'continueKeysOf', 'createDebouncer', script)(mergeHomeShelves, shouldRefreshHome, continueKeysOf, createDebouncer)
  const store = {
    state: { user: { user, serverConnectionConfig: signedIn ? { id: activeServerConfigId } : null }, activeServerConfigId, serverState, libraries: { currentLibraryId }, globals: { localMediaProgress: [] }, currentPlaybackSession: null },
    getters: { getHasActiveServer: hasActiveServer, 'libraries/getCurrentLibraryMediaType': mediaType, 'globals/getLocalMediaProgressById': () => null },
    commit() {},
    async dispatch() {}
  }
  const http = fakeHttp(() => [shelf('from-server', 'book', [{ id: 'srv' }])])
  const nativeHttpCalls = []
  const absData = createDataService({ plugin, store, nativeHttp: () => http })
  const view = {
    ...component.data(),
    $store: store,
    $absData: absData,
    $db: { getLocalLibraryItems: async () => localItems },
    $nativeHttp: { get: async (path) => (nativeHttpCalls.push(path), []) },
    $strings: { LabelContinueBooks: 'Continue', LabelLocalBooks: 'Local books', LabelLocalPodcasts: 'Local podcasts', LabelContinueEpisodes: 'Continue episodes' },
    $eventBus: { $on() {}, $off() {} }
  }
  for (const [name, fn] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: fn.bind(view), configurable: true })
  for (const [name, fn] of Object.entries(component.methods)) view[name] = fn.bind(view)
  return { view, store, http, nativeHttpCalls, plugin, component }
}

test('cold start offline shows replica shelves', async () => {
  const page = await homePage({ localItems: [{ id: 'local_1', libraryItemId: 'li1', serverConnectionConfigId: 'a', mediaType: 'book', media: {} }] })
  await page.view.fetchCategories()
  assert.deepEqual(page.view.shelves.map((s) => s.id), ['continue-listening', 'local-books'])
  assert.equal(page.view.shelves[0].entities[0].localLibraryItem.id, 'local_1')
  assert.equal(page.view.catalogNotLoaded, false)
  assert.equal(page.view.isLoading, false)
  assert.deepEqual(page.nativeHttpCalls, [])
  assert.deepEqual(page.http.paths, [])

  // An empty replica offline: the local shelves and "catalog not loaded"
  const empty = await homePage({ plugin: fakePlugin({ getPersonalized: async ({ libraryId }) => ({ serverConfigId: 'a', libraryId, shelves: [], replica: replica(null) }) }) })
  await empty.view.fetchCategories()
  assert.equal(empty.view.catalogNotLoaded, true)

  // An answer for another library is dropped
  const other = await homePage({ plugin: fakePlugin({ getPersonalized: async () => ({ serverConfigId: 'a', libraryId: 'lib2', shelves: [shelf('x', 'book', [{ id: 'x' }])], replica: replica() }) }) })
  await other.view.fetchCategories()
  assert.deepEqual(other.view.shelves, [])
  assert.equal(other.view.isLoading, false)

  // No active server: nothing asked, the local shelves
  const none = await homePage({ hasActiveServer: false, activeServerConfigId: null })
  await none.view.fetchCategories()
  assert.deepEqual(none.plugin.calls, [])
  assert.equal(none.view.catalogNotLoaded, false)
})

test('a progress change re-reads the home once', async () => {
  const page = await homePage()
  await page.view.fetchCategories()
  assert.equal(page.plugin.calls.length, 1)
  const timers = fakeTimers()
  page.view.refreshDebouncer = createDebouncer({ delayMs: 1000, ...timers })
  for (let i = 0; i < 3; i++) page.view.dataChanged({ scope: 'progress', ids: ['li1'] })
  assert.equal(page.plugin.calls.length, 1)
  assert.deepEqual(timers.run(), [1000])
  await flush()
  assert.equal(page.plugin.calls.length, 2, 'one re-read, not throttled by the read just before')

  // The playing book is already first in "continue": a tick re-reads nothing
  page.view.dataChanged({ scope: 'playback', ids: ['li1'] })
  assert.deepEqual(timers.run(), [])
  // Another book: one re-read, and its next tick none
  page.view.dataChanged({ scope: 'playback', ids: ['li9'] })
  timers.run()
  await flush()
  assert.equal(page.plugin.calls.length, 3)
  assert.equal(page.view.refreshedFor.key, 'li9')
  page.view.dataChanged({ scope: 'playback', ids: ['li9'] })
  assert.deepEqual(timers.run(), [])
  // Another re-read that leaves the first "continue" item as it was keeps the key: still no re-read per tick
  page.view.dataChanged({ scope: 'catalog', ids: ['state:lib1'] })
  timers.run()
  await flush()
  page.view.dataChanged({ scope: 'playback', ids: ['li9'] })
  assert.deepEqual(timers.run(), [])
  page.view.dataChanged({ scope: 'catalog', ids: ['items:lib2'] })
  assert.deepEqual(timers.run(), [])
  // A new first item (or another episode playing) forgets it
  page.store.state.currentPlaybackSession = { episodeId: 'e2' }
  page.view.dataChanged({ scope: 'playback', ids: ['li9'] })
  assert.deepEqual(timers.run(), [1000])
})

test('a background re-read shows no loading state; another library never shows the previous shelves', async () => {
  let release = null
  const plugin = fakePlugin({
    async getPersonalized(args) {
      plugin.calls.push(['getPersonalized', args])
      const answer = { serverConfigId: 'a', libraryId: args.libraryId, shelves: [shelf(`continue-${args.libraryId}`, 'book', [{ id: 'li1' }])], replica: replica() }
      return release ? new Promise((resolve) => (release = () => resolve(answer))) : answer
    }
  })
  const page = await homePage({ plugin, localItems: [{ id: 'local_1', libraryItemId: 'li1', serverConnectionConfigId: 'a', mediaType: 'book', media: {} }] })
  await page.view.fetchCategories()
  assert.deepEqual(page.view.shelves.map((s) => s.id), ['continue-lib1', 'local-books'])
  const loading = []
  const watched = page.view
  let isLoading = watched.isLoading
  Object.defineProperty(watched, 'isLoading', { get: () => isLoading, set: (v) => (loading.push(v), (isLoading = v)), configurable: true })

  release = () => {}
  const reread = watched.fetchCategories({ force: true })
  await flush()
  assert.deepEqual(watched.shelves.map((s) => s.id), ['continue-lib1', 'local-books'], 'the shelves stay in place')
  release()
  await reread
  assert.ok(!loading.includes(true), 'no "Loading server data" for a re-read')
  loading.length = 0

  // Library switched: while native answers, the local shelves, not lib1's
  page.store.state.libraries.currentLibraryId = 'lib2'
  watched.catalogNotLoaded = true
  const switched = watched.fetchCategories()
  await flush()
  assert.deepEqual(watched.shelves.map((s) => s.id), ['local-books'])
  assert.equal(watched.catalogNotLoaded, false)
  assert.deepEqual(loading, [true])
  release()
  await switched
  assert.deepEqual(watched.shelves.map((s) => s.id), ['continue-lib2', 'local-books'])
  assert.equal(loading.at(-1), false)

  // An answer for the previous library arriving for the new one: not shown
  const stale = await homePage({ plugin: fakePlugin({ getPersonalized: async () => ({ serverConfigId: 'a', libraryId: 'lib1', shelves: [shelf('continue-lib1', 'book', [{ id: 'x' }])], replica: replica() }) }) })
  stale.view.shelves = [shelf('continue-lib1', 'book', [{ id: 'x' }])]
  stale.view.shownFor = 'a|lib1'
  stale.store.state.libraries.currentLibraryId = 'lib2'
  await stale.view.fetchCategories()
  assert.deepEqual(stale.view.shelves, [])
  assert.equal(stale.view.isLoading, false)
})

test('a finished download or a deleted copy re-reads the home', async () => {
  const page = await homePage()
  const handlers = {}
  page.view.$eventBus = { $on: (name, fn) => (handlers[name] = fn), $off: (name) => delete handlers[name] }
  page.view.initListeners()
  await page.view.fetchCategories()
  assert.equal(page.view.shelves[0].entities[0].localLibraryItem, undefined)
  page.view.$db.getLocalLibraryItems = async () => [{ id: 'local_1', libraryItemId: 'li1', serverConnectionConfigId: 'a', mediaType: 'book', media: {} }]
  await handlers['new-local-library-item']()
  assert.equal(page.view.shelves[0].entities[0].localLibraryItem.id, 'local_1')
  page.view.$db.getLocalLibraryItems = async () => []
  await handlers['local-library-items-changed']()
  assert.equal(page.view.shelves[0].entities[0].localLibraryItem, undefined)
  page.view.removeListeners()
  assert.deepEqual(Object.keys(handlers), [])
  // The menu that deletes a copy says so
  const menu = await read('../components/modals/ItemMoreMenuModal.vue')
  assert.equal((menu.match(/\$eventBus\.\$emit\('local-library-items-changed'\)/g) || []).length, 2)
})

test('home does not read /personalized directly', async () => {
  const source = await read('../pages/bookshelf/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  assert.doesNotMatch(script, /\$nativeHttp\.get\([^)]*personalized/)
  assert.match(script, /\$absData\.getPersonalized\(/)
  assert.match(script, /getters\['getHasActiveServer'\] && libraryId/)
  assert.doesNotMatch(script, /networkConnected\(/, 'the replica does not depend on the network')
  assert.doesNotMatch(script, /audiobookAdded|audiobookUpdated|removeBookFromShelf/)
  assert.match(script, /onDataChanged\(this\.dataChanged\)/)
  assert.match(source.match(/<template>([\s\S]*)<\/template>/)[1], /MessageCatalogNotLoaded/)
})
