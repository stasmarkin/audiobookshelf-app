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
  assert.deepEqual(online.http.paths, ['/api/libraries/lib1/personalized?include=rssfeed,numEpisodesIncomplete&limit=10'])
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
    ['/api/libraries/lib1/personalized', { include: 'rssfeed,numEpisodesIncomplete', limit: 10 }],
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
