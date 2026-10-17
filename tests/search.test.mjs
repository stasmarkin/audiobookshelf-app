// Search from the replica (offline-first release 5): pages/search.vue asks $absData.search, which native answers from
// replica.db (the server while the library was never reconciled); offline, results without a downloaded copy are marked
// and open their page from the replica. Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { createDataService } = await importSource('../utils/dataService.js')
const { createDebouncer, isReplicaIncomplete } = await importSource('../utils/catalogRefresh.js')
const { findLocalCopy } = await importSource('../utils/localCopy.js')
const requiresServer = (await importSource('../mixins/requiresServer.js')).default

const replica = (reconciledAt = 5) => ({ reconciledAt, complete: reconciledAt != null })
const scriptOf = (source) => source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
const templateOf = (source) => source.match(/<template>([\s\S]*)<\/template>/)[1]

const flush = async (n = 30) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

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

const book = (id) => ({ libraryItem: { id, mediaType: 'book', media: { metadata: { title: id } } } })
const episode = (podcastId, episodeId) => ({ libraryItem: { id: podcastId, mediaType: 'podcast', media: { metadata: { title: podcastId } }, recentEpisode: { id: episodeId, title: episodeId } } })

/** pages/search.vue as a plain object; `search` answers native's search (args → answer or a promise of it). */
async function searchPage({ user = null, serverState = 'offline', activeServerConfigId = 'a', signedIn = !!user, search, localItems = [], useDataService = false, plugin = null } = {}) {
  const component = new Function('findLocalCopy', 'createDebouncer', 'isReplicaIncomplete', scriptOf(await read('../pages/search.vue')))(findLocalCopy, createDebouncer, isReplicaIncomplete)
  const store = {
    state: { user: { user, serverConnectionConfig: signedIn ? { id: activeServerConfigId } : null }, serverState, activeServerConfigId, libraries: { currentLibraryId: 'lib1' }, globals: { lastSearch: null } },
    getters: { 'libraries/getBookCoverAspectRatio': 1.6 },
    commits: [],
    commit(type, payload) {
      store.commits.push([type, payload])
    },
    async dispatch() {}
  }
  const asked = []
  const nativeHttp = []
  const absData = useDataService
    ? createDataService({ plugin, store, nativeHttp: () => ({ get: async (path) => (nativeHttp.push(path), {}) }) })
    : { search: async (args) => (asked.push(args), search(args)), onDataChanged: () => () => {} }
  const view = {
    ...component.data(),
    $store: store,
    $absData: absData,
    $db: { getLocalLibraryItems: async () => localItems },
    $nativeHttp: { get: async (path) => (nativeHttp.push(path), {}) },
    $eventBus: { handlers: {}, $on(name, fn) { this.handlers[name] = fn }, $off(name) { delete this.handlers[name] } },
    $toast: { shown: [], info(m) { this.shown.push(m) } },
    $strings: { LabelNotAvailableOffline: 'Not available offline' }
  }
  for (const [name, fn] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: fn.bind(view), configurable: true })
  for (const [name, fn] of Object.entries(component.methods)) view[name] = fn.bind(view)
  return { view, store, asked, nativeHttp }
}

test('search reads the replica without a live user', async () => {
  const calls = []
  const plugin = {
    addListener: () => ({ remove() {} }),
    getServerState: async () => ({}),
    getUserData: async () => ({}),
    async search(args) {
      calls.push(args)
      return { serverConfigId: 'a', libraryId: args.libraryId, q: args.q, results: { book: [book('li1')], authors: [{ id: 'au1', name: 'Ann' }] }, replica: replica() }
    }
  }
  const page = await searchPage({ useDataService: true, plugin })
  await page.view.runSearch('  ann ')
  assert.deepEqual(calls, [{ libraryId: 'lib1', q: 'ann', limit: 12 }])
  assert.deepEqual(page.nativeHttp, [])
  assert.deepEqual(page.view.bookResults.map((r) => r.libraryItem.id), ['li1'])
  assert.equal(page.view.authorResults.length, 1)
  assert.equal(page.view.isFetching, false)

  // The search button: with a saved server, not only a signed-in user
  const appbar = await read('../components/app/Appbar.vue')
  assert.match(templateOf(appbar), /<nuxt-link v-if="user \|\| hasActiveServer"[^>]*to="\/search"/)
  const computed = new Function(scriptOf(appbar).replace(/^import .*$/gm, ''))().computed
  assert.equal(computed.hasActiveServer.call({ $store: { getters: { getHasActiveServer: true }, state: {} } }), true)

  // The page asks nothing of the server itself
  assert.doesNotMatch(scriptOf(await read('../pages/search.vue')), /\$nativeHttp/)
})

test('only the latest answer touches results and the fetching flag', async () => {
  for (const order of [
    ['first', 'second'],
    ['second', 'first']
  ]) {
    const pending = {}
    const page = await searchPage({ search: ({ libraryId, q }) => new Promise((resolve) => (pending[q] = () => resolve({ serverConfigId: 'a', libraryId, q, results: { book: [book(q)] }, replica: replica() }))) })
    const first = page.view.runSearch('first')
    const second = page.view.runSearch('second')
    await flush()
    pending[order[0]]()
    await flush()
    if (order[0] === 'first') {
      assert.equal(page.view.isFetching, true, 'the earlier answer leaves the flag to the latest')
      assert.deepEqual(page.view.bookResults, [])
    }
    pending[order[1]]()
    await Promise.all([first, second])
    assert.deepEqual(page.view.bookResults.map((r) => r.libraryItem.id), ['second'], order.join(' then '))
    assert.equal(page.view.isFetching, false)
  }

  // Clearing the field while a search runs: the flag drops, the late answer brings nothing back
  let release
  const page = await searchPage({ search: ({ libraryId, q }) => new Promise((resolve) => (release = () => resolve({ serverConfigId: 'a', libraryId, q, results: { book: [book('late')] }, replica: replica() }))) })
  const running = page.view.runSearch('abc')
  await flush()
  assert.equal(page.view.isFetching, true)
  await page.view.runSearch('   ')
  assert.equal(page.view.isFetching, false)
  release()
  await running
  assert.deepEqual(page.view.bookResults, [])
  assert.equal(page.asked.length, 1, 'a blank query asks nothing')

  // An answer for another library or server is not shown
  const foreign = await searchPage({ search: ({ q }) => ({ serverConfigId: 'a', libraryId: 'lib2', q, results: { book: [book('x')] }, replica: replica() }) })
  await foreign.view.runSearch('x')
  assert.deepEqual(foreign.view.bookResults, [])
  assert.equal(foreign.view.isFetching, false)
})

test('empty replica offline says catalog not loaded', async () => {
  const empty = (reconciledAt, extra = {}) => async ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results: {}, replica: replica(reconciledAt), ...extra })
  const notLoaded = await searchPage({ search: empty(null) })
  await notLoaded.view.runSearch('abc')
  assert.equal(notLoaded.view.catalogNotLoaded, true)
  assert.match(templateOf(await read('../pages/search.vue')), /catalogNotLoaded \? \$strings\.MessageCatalogNotLoaded : \$strings\.MessageNoItemsFound/)

  for (const search of [empty(5), empty(null, { fromServer: true })]) {
    const page = await searchPage({ search })
    await page.view.runSearch('abc')
    assert.equal(page.view.catalogNotLoaded, false, 'nothing found in a read catalog')
  }
  const found = await searchPage({ search: async ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results: { book: [book('li1')] }, replica: replica(null) }) })
  await found.view.runSearch('abc')
  assert.equal(found.view.catalogNotLoaded, false)
})

test('the search follows another library or server', async () => {
  const page = await searchPage({ search: async ({ libraryId, q }) => ({ serverConfigId: page.store.state.activeServerConfigId, libraryId, q, results: { book: [book(`${libraryId}-${q}`)] }, replica: replica() }) })
  await page.view.runSearch('abc')
  assert.deepEqual(page.view.bookResults.map((r) => r.libraryItem.id), ['lib1-abc'])
  page.store.state.libraries.currentLibraryId = 'lib2'
  const rerun = page.view.libraryOrServerChanged()
  assert.deepEqual(page.view.bookResults, [], "lib1's results go at once")
  await flush()
  assert.deepEqual(page.view.bookResults.map((r) => r.libraryItem.id), ['lib2-abc'])
  page.store.state.activeServerConfigId = 'b'
  page.view.libraryOrServerChanged()
  await flush()
  assert.deepEqual(page.asked.map((a) => a.libraryId), ['lib1', 'lib2', 'lib2'])
  assert.equal(page.view.bookResults.length, 1)
  const component = new Function('findLocalCopy', 'createDebouncer', 'isReplicaIncomplete', scriptOf(await read('../pages/search.vue')))(findLocalCopy, createDebouncer, isReplicaIncomplete)
  assert.deepEqual(Object.keys(component.watch).sort(), ['activeServerConfigId', 'currentLibraryId'])
  void rerun
})

test('downloaded copies are read once and follow downloads and deletions', async () => {
  let reads = 0
  let copies = []
  const page = await searchPage({ search: ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results: { book: [book('li1')] }, replica: replica() }) })
  page.view.$db.getLocalLibraryItems = async () => (reads++, copies)
  page.view.$eventBus.$on('new-local-library-item', page.view.localItemsChanged)
  await page.view.runSearch('a')
  await page.view.runSearch('ab')
  assert.equal(reads, 1, 'not on every query')
  assert.equal(page.view.bookResults[0].localLibraryItem, null)
  copies = [{ id: 'local_1', libraryItemId: 'li1', serverConnectionConfigId: 'a', media: {} }]
  await page.view.$eventBus.handlers['new-local-library-item']()
  assert.equal(page.view.bookResults[0].localLibraryItem.id, 'local_1')
  copies = []
  await page.view.localItemsChanged()
  assert.equal(page.view.bookResults[0].localLibraryItem, null)
  assert.equal(page.asked.length, 2, 'the search is not asked again for a copy')
  const script = scriptOf(await read('../pages/search.vue'))
  assert.match(script, /\$eventBus\.\$on\('local-library-items-changed', this\.localItemsChanged\)/)
  assert.match(script, /\$eventBus\.\$off\('new-local-library-item', this\.localItemsChanged\)/)
})

test('open search re-runs after the catalog changes', async () => {
  const page = await searchPage({ search: async ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results: {}, replica: replica() }) })
  await page.view.runSearch('abc')
  const timers = fakeTimers()
  page.view.refreshDebouncer = createDebouncer({ delayMs: 1000, ...timers })
  page.view.dataChanged({ scope: 'catalog', ids: ['state:lib1'] })
  page.view.dataChanged({ scope: 'catalog', ids: ['episodes:lib1'] })
  assert.deepEqual(timers.run(), [1000])
  assert.equal(page.view.isFetching, false, 'a quiet re-run: no "Fetching" over the shown results')
  await flush()
  assert.deepEqual(page.asked.map((a) => a.q), ['abc', 'abc'])

  page.view.dataChanged({ scope: 'catalog', ids: ['state:lib2'] })
  page.view.dataChanged({ scope: 'progress', ids: ['li1'] })
  assert.deepEqual(timers.run(), [])
  await page.view.runSearch('')
  page.view.dataChanged({ scope: 'catalog', ids: ['state:lib1'] })
  assert.deepEqual(timers.run(), [])
  assert.equal(page.asked.length, 2)
})

/** A search card's computed values and click over a fake store. */
async function searchCard(path, props, serverState) {
  const component = new Function('requiresServer', scriptOf(await read(path)))(requiresServer)
  const toasts = []
  const view = { ...props, $store: { state: { serverState }, getters: { 'libraries/getBookCoverAspectRatio': 1.6, getIsServerOnline: serverState === 'online' } }, $toast: { info: (m) => toasts.push(m) }, $strings: { LabelNotAvailableOffline: 'Not available offline' } }
  for (const part of [...(component.mixins || []), component]) {
    for (const [name, fn] of Object.entries(part.computed || {})) Object.defineProperty(view, name, { get: fn.bind(view), configurable: true })
    for (const [name, fn] of Object.entries(part.methods || {})) view[name] = fn.bind(view)
  }
  return { view, toasts }
}

/** LazyBookCard's computed values and methods for an episode card. */
async function episodeCard({ serverState, localLibraryItem, signedIn = false }) {
  const source = await read('../components/cards/LazyBookCard.vue')
  const component = new Function('Capacitor', 'requiresServer', scriptOf(source))({ isNativePlatform: () => true, getPlatform: () => 'android' }, requiresServer)
  const emitted = []
  const pushed = []
  const toasts = []
  const view = {
    ...component.data(),
    libraryItem: { id: 'li1', mediaType: 'podcast', media: { metadata: { title: 'P' } }, recentEpisode: { id: 'ep1', title: 'E' } },
    localLibraryItem,
    isSelectionMode: false,
    $store: { state: { serverState, user: { serverConnectionConfig: signedIn ? { id: 'a' } : null }, isCasting: false, playerIsPlaying: false, globals: {} }, getters: new Proxy({}, { get: () => () => null }), commit() {} },
    $eventBus: { $emit: (name, payload) => emitted.push([name, payload]) },
    $router: { push: (path) => pushed.push(path) },
    $toast: { info: (m) => toasts.push(m) },
    $strings: { LabelNotAvailableOffline: 'Not available offline' },
    $hapticsImpact: async () => {}
  }
  for (const part of [...(component.mixins || []), component]) {
    for (const [name, def] of Object.entries(part.computed || {})) Object.defineProperty(view, name, { get: (def.get || def).bind(view), configurable: true })
    for (const [name, fn] of Object.entries(part.methods || {})) view[name] = fn.bind(view)
  }
  return { view, emitted, pushed, toasts }
}

test('offline search cards are marked', async () => {
  const item = { libraryItem: { id: 'li1', mediaType: 'book', media: { metadata: { title: 'A' } } } }
  const copy = { id: 'local_1', media: { episodes: [{ id: 'local_ep_1', serverEpisodeId: 'ep1' }] } }
  for (const path of ['../components/cards/ItemSearchCard.vue', '../components/cards/EpisodeSearchCard.vue']) {
    const offline = await searchCard(path, { ...item, episode: { id: 'ep1' }, localLibraryItem: null, localEpisode: null }, 'offline')
    assert.equal(offline.view.notAvailableOffline, true, path)
    const downloaded = await searchCard(path, { ...item, episode: { id: 'ep1' }, localLibraryItem: copy, localEpisode: copy.media.episodes[0] }, 'offline')
    assert.equal(downloaded.view.notAvailableOffline, false, path)
    const online = await searchCard(path, { ...item, episode: { id: 'ep1' }, localLibraryItem: null, localEpisode: null }, 'online')
    assert.equal(online.view.notAvailableOffline, false)
    assert.equal((await searchCard(path, { ...item, episode: { id: 'ep1' }, localLibraryItem: null, localEpisode: null }, 'unknown')).view.notAvailableOffline, false, 'not while the first check is pending')
    assert.match(templateOf(await read(path)), /v-if="notAvailableOffline"[^>]*>\{\{ \$strings\.LabelNotAvailableOffline \}\}/)
  }
  // The episode card needs this episode downloaded, not the podcast's other ones
  const episodeOnly = await searchCard('../components/cards/EpisodeSearchCard.vue', { ...item, episode: { id: 'ep2' }, localLibraryItem: copy, localEpisode: null }, 'offline')
  assert.equal(episodeOnly.view.notAvailableOffline, true)
  assert.match(templateOf(await read('../pages/search.vue')), /<cards-episode-search-card [^>]*:local-library-item="item\.localLibraryItem"/)

  // On the home shelf: a copy of the podcast without this episode is not enough to play it, but the page opens
  const other = await episodeCard({ serverState: 'offline', localLibraryItem: { id: 'local_1', media: { episodes: [{ id: 'local_ep_9', serverEpisodeId: 'ep9' }] } } })
  assert.equal(other.view.notAvailableOffline, true)
  await other.view.playEpisode()
  assert.deepEqual(other.emitted, [], 'no play-item')
  assert.deepEqual(other.toasts, ['Not available offline'])
  await other.view.clickCard({})
  assert.deepEqual(other.pushed, ['/item/li1/ep1'], "the server episode page, read from the replica")

  const downloaded = await episodeCard({ serverState: 'offline', localLibraryItem: { id: 'local_1', media: { episodes: [{ id: 'local_ep_1', serverEpisodeId: 'ep1' }] } } })
  assert.equal(downloaded.view.notAvailableOffline, false)
  await downloaded.view.clickCard({})
  assert.deepEqual(downloaded.pushed, ['/item/local_1/local_ep_1'])
  await downloaded.view.playEpisode()
  assert.equal(downloaded.emitted[0][1].episodeId, 'local_ep_1')

  // Online and signed in: the server's episode page
  const online = await episodeCard({ serverState: 'online', signedIn: true, localLibraryItem: { id: 'local_1', media: { episodes: [{ id: 'local_ep_1', serverEpisodeId: 'ep1' }] } } })
  await online.view.clickCard({})
  assert.deepEqual(online.pushed, ['/item/li1/ep1'])
  // A copy without episodes (a book's) does not break the card
  assert.equal((await episodeCard({ serverState: 'offline', localLibraryItem: { id: 'local_2', media: {} } })).view.notAvailableOffline, true)
})

test('search results open undownloaded items offline', async () => {
  const localItems = [{ id: 'local_2', libraryItemId: 'pod1', serverConnectionConfigId: 'a', media: { episodes: [{ id: 'local_ep_1', serverEpisodeId: 'ep1' }] } }]
  const answer = ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results: { book: [book('li1')], episodes: [episode('pod1', 'ep1'), episode('pod1', 'ep9')] }, replica: replica() })
  const page = await searchPage({ search: answer, localItems })
  await page.view.runSearch('x')
  const [bookResult] = page.view.bookResults
  const [downloadedEpisode, notDownloaded] = page.view.episodeResults
  assert.equal(page.view.itemLink(bookResult), '/item/li1', 'a link to the page, read from the replica')
  assert.equal(page.view.episodeLink(notDownloaded), '/item/pod1/ep9', "the server's episode page")
  assert.equal(page.view.episodeLink(downloadedEpisode), '/item/local_2/local_ep_1', 'offline the downloaded episode opens its copy')
  assert.deepEqual(page.view.$toast.shown, [])

  // Every result is a link; no stand-in that shows a toast instead
  const template = templateOf(await read('../pages/search.vue'))
  assert.equal((template.match(/<nuxt-link :to="(itemLink|episodeLink)\(item\)">/g) || []).length, 3)
  assert.doesNotMatch(template, /isUnavailable|showUnavailable/)
  // The marker stays on the card
  const card = await searchCard('../components/cards/ItemSearchCard.vue', { libraryItem: bookResult.libraryItem, localLibraryItem: null }, 'offline')
  assert.equal(card.view.notAvailableOffline, true)

  // Online, signed out or not: a downloaded episode opens the server page
  for (const user of [null, { id: 'u' }]) {
    const online = await searchPage({ serverState: 'online', user, search: answer, localItems })
    await online.view.runSearch('x')
    assert.equal(online.view.episodeLink(online.view.episodeResults[0]), '/item/pod1/ep1')
  }
})

test('a downloaded search result opens its copy offline', async () => {
  const localItems = [
    { id: 'local_1', libraryItemId: 'li1', serverConnectionConfigId: 'a', media: {} },
    { id: 'local_2', libraryItemId: 'pod1', serverConnectionConfigId: 'a', media: { episodes: [{ id: 'local_ep_1', serverEpisodeId: 'ep1' }] } },
    { id: 'local_b', libraryItemId: 'li2', serverConnectionConfigId: 'b', media: {} }
  ]
  const answer = ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results: { book: [book('li1'), book('li2')], episodes: [episode('pod1', 'ep1'), episode('pod1', 'ep2')] }, replica: replica() })
  for (const serverState of ['offline', 'unknown']) {
    const page = await searchPage({ serverState, search: answer, localItems })
    await page.view.runSearch('x')
    const [withCopy, otherServer] = page.view.bookResults
    assert.equal(page.view.itemLink(withCopy), '/item/li1?localLibraryItemId=local_1')
    assert.equal(withCopy.localLibraryItem.id, 'local_1')
    assert.equal(otherServer.localLibraryItem, null, "another server's copy is not this item's")
    assert.equal(page.view.itemLink(otherServer), '/item/li2')
    const [downloadedEpisode, notDownloaded] = page.view.episodeResults
    assert.equal(page.view.episodeLink(downloadedEpisode), '/item/local_2/local_ep_1', serverState)
    assert.equal(notDownloaded.localEpisode, null)
    assert.equal(page.view.episodeLink(notDownloaded), '/item/pod1/ep2')
  }
  const online = await searchPage({ serverState: 'online', user: { id: 'u' }, search: answer, localItems })
  await online.view.runSearch('x')
  assert.equal(online.view.episodeLink(online.view.episodeResults[0]), '/item/pod1/ep1', 'online the server page')

  const template = templateOf(await read('../pages/search.vue'))
  assert.match(template, /:to="itemLink\(item\)"[\s\S]*:local-library-item="item\.localLibraryItem"/)
  assert.match(template, /:to="episodeLink\(item\)"[\s\S]*:local-library-item="item\.localLibraryItem"/)

  // The item page without a server connection opens the copy the link names
  const itemPage = await read('../pages/item/_id/index.vue')
  // Its imports (mixins, plugins) are not used by asyncData: stand-ins
  const importNames = [...itemPage.match(/<script>([\s\S]*?)<\/script>/)[1].matchAll(/^import\s+(?:\{([^}]*)\}|(\w+))/gm)].flatMap((m) => (m[1] ? m[1].split(',').map((n) => n.trim().split(/\s+as\s+/).pop()) : [m[2]])).filter(Boolean)
  const asyncData = new Function(...importNames, scriptOf(itemPage))(...importNames.map(() => ({}))).asyncData
  const redirects = []
  await asyncData({ store: { state: { user: { serverConnectionConfig: null } }, getters: {} }, params: { id: 'li1' }, query: { localLibraryItemId: 'local_1' }, redirect: (path) => redirects.push(path), app: {} })
  assert.deepEqual(redirects, ['/item/local_1?noredirect=1'])
})

test('a read of downloaded copies started before a change does not fill the cache', async () => {
  const page = await searchPage({ search: ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results: { book: [book('li1')] }, replica: replica() }) })
  const pending = []
  page.view.$db.getLocalLibraryItems = () => new Promise((resolve) => pending.push(resolve))
  const search = page.view.runSearch('a')
  await flush()
  const changed = page.view.localItemsChanged()
  await flush()
  pending[1]([{ id: 'local_1', libraryItemId: 'li1', serverConnectionConfigId: 'a', media: {} }])
  await changed
  pending[0]([])
  await search
  assert.equal(page.view.localItems.length, 1, 'the older read (no copies) did not overwrite the newer one')
  assert.equal(page.view.bookResults[0].localLibraryItem.id, 'local_1')
})

test('results of an unfinished catalog copy carry the partial note while the server is away', async () => {
  const shown = async (replicaState, serverState, results = { book: [book('li1')] }) => {
    const page = await searchPage({ serverState, search: ({ libraryId, q }) => ({ serverConfigId: 'a', libraryId, q, results, replica: replicaState }) })
    await page.view.runSearch('x')
    return page.view
  }
  assert.equal((await shown({ reconciledAt: null, complete: false }, 'offline')).showCatalogPartial, true)
  assert.equal((await shown({ reconciledAt: 5, complete: false }, 'unauthorized')).showCatalogPartial, true)
  assert.equal((await shown(replica(), 'offline')).showCatalogPartial, false, 'a full copy')
  assert.equal((await shown({ reconciledAt: 5, complete: false }, 'online')).showCatalogPartial, false)
  const nothing = await shown({ reconciledAt: null, complete: false }, 'offline', {})
  assert.equal(nothing.showCatalogPartial, false, 'nothing found: "catalog not loaded" alone')
  assert.equal(nothing.catalogNotLoaded, true)
  await nothing.runSearch('')
  assert.equal(nothing.replicaIncomplete, false)
  const template = templateOf(await read('../pages/search.vue'))
  const tabs = (await read('../components/bookshelf/LazyBookshelf.vue')).match(/<div v-if="showCatalogPartial"[^>]*>\{\{ \$strings\.MessageCatalogPartial \}\}<\/div>/)[0]
  assert.ok(template.includes(tabs), 'the same note as the catalog tabs')
})
