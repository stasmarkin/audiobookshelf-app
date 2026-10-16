// The Collections tab and the collection page on the catalog replica (offline-first 4d): $absData reads collections
// from native replica.db (a list page carries 4 preview books per collection, the books come 100 a call), falls back to
// the server while they were never read, and the page follows native's catalog notices. Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { createDataService, catalogTopics, CATALOG_EVENTS } = await importSource('../utils/dataService.js')
const { shouldRefreshShelf, createDebouncer, isReplicaIncomplete } = await importSource('../utils/catalogRefresh.js')
const { trackCount, fetchCollectionBooks } = await importSource('../utils/catalogItem.js')
const plain = (value) => JSON.parse(JSON.stringify(value))
const replica = (reconciledAt = 5) => ({ reconciledAt, complete: reconciledAt != null })
const flush = async (n = 30) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

/** Minified books as the replica keeps them. */
const booksOf = (count, prefix = 'li') => Array.from({ length: count }, (_, i) => ({ id: `${prefix}${i}`, ino: `${1000 + i}`, mediaType: 'book', media: { numTracks: 2, metadata: { title: `Book ${i}`, authorName: 'Ann Lee' } } }))

/** A fake native AbsData with one collection `c1` of library `l1` holding `books`. */
function fakePlugin({ books = booksOf(3), reconciledAt = 5, serverConfigId = 'a', overrides = {} } = {}) {
  const calls = []
  return {
    calls,
    addListener: () => ({ remove() {} }),
    getServerState: async () => ({}),
    async getCollections(args) {
      calls.push(['getCollections', args])
      const results = [{ id: 'c1', libraryId: 'l1', name: 'Мои', numBooks: books.length, books: books.slice(0, 4) }]
      return { serverConfigId, results, total: 1, limit: args.limit, page: args.page, replica: replica(reconciledAt) }
    },
    async getCollection(args) {
      calls.push(['getCollection', args])
      return { serverConfigId, collection: { id: args.id, libraryId: 'l1', name: 'Мои', numBooks: books.length }, replica: replica(reconciledAt) }
    },
    async getCollectionBooks(args) {
      calls.push(['getCollectionBooks', args])
      const { limit, page } = args
      return { serverConfigId, results: books.slice(page * limit, page * limit + limit), total: books.length, limit, page, found: true, replica: replica(reconciledAt) }
    },
    ...overrides
  }
}

const fakeStore = ({ serverState = 'online' } = {}) => ({
  state: { serverState, activeServerConfigId: 'a', user: { serverConnectionConfig: { id: 'a' } }, libraries: { currentLibraryId: 'l1' } },
  commit() {},
  async dispatch() {}
})

const fakeHttp = (answer) => {
  const paths = []
  return { paths, get: async (path) => (paths.push(path), answer(path)) }
}

/** The server's `/api/collections/:id`: every book expanded (`media.tracks`). */
const serverCollection = (count) => ({
  id: 'c1',
  libraryId: 'l1',
  name: 'Мои',
  description: null,
  lastUpdate: 2,
  createdAt: 1,
  books: Array.from({ length: count }, (_, i) => ({ id: `li${i}`, mediaType: 'book', media: { tracks: [{}, {}, {}], metadata: { title: `Book ${i}`, authors: [{ name: 'Ann Lee' }] } } }))
})

const pageScript = (await read('../pages/collection/_id.vue'))
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '')
  .replace('export default', 'globalThis.component =')

/** The collection page over fakes: data, computed getters and methods on one object, as Vue would bind them. */
function collectionPage({ absData, localItems = [], activeServerConfigId = 'a', user = null } = {}) {
  const timers = []
  const fakeDebouncer = (options) => createDebouncer({ ...options, setTimer: (fn, ms) => (timers.push({ fn, ms }), timers.at(-1)), clearTimer: (t) => timers.includes(t) && timers.splice(timers.indexOf(t), 1) })
  const errors = []
  const sandbox = { console: { ...console, log() {}, error: (...args) => errors.push(args) }, trackCount, fetchCollectionBooks, createDebouncer: fakeDebouncer, downloadMissingItems() {}, buildQueueSourceItems() {} }
  vm.runInNewContext(pageScript, sandbox)
  const component = sandbox.component
  const store = { state: { activeServerConfigId, user: { user }, playerIsPlaying: false }, getters: { getIsMediaStreaming: () => false } }
  const dataListeners = new Set()
  const replaced = []
  const handlers = {}
  const app = {
    $absData: { ...absData, onDataChanged: (cb) => (dataListeners.add(cb), () => dataListeners.delete(cb)) },
    $db: { getLocalLibraryItems: async () => localItems },
    $nativeHttp: { get: async () => assert.fail('the collection page reads through $absData') }
  }
  const asyncData = async (id = 'c1') => {
    const out = { redirected: null }
    const result = await component.asyncData({ params: { id }, app, store, redirect: (path) => (out.redirected = path), route: { path: `/collection/${id}` } })
    return { ...out, result }
  }
  const mount = async (id = 'c1') => {
    const { result } = await asyncData(id)
    const view = { ...component.data(), ...result, ...app, $store: store, $router: { replace: (to) => replaced.push(to) }, $eventBus: { $on: (n, fn) => (handlers[n] = fn), $off: (n) => delete handlers[n] } }
    for (const [name, get] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: get.bind(view) })
    for (const [name, fn] of Object.entries(component.methods)) view[name] = fn.bind(view)
    component.mounted.call(view)
    return view
  }
  return { component, asyncData, mount, timers, replaced, errors, dataListeners, send: (change) => dataListeners.forEach((cb) => cb(change)), destroy: (view) => component.beforeDestroy.call(view) }
}

test('trackCount works for minified and expanded items', () => {
  assert.equal(trackCount({ media: { numTracks: 3 } }), 3)
  assert.equal(trackCount({ media: { tracks: [1, 2] } }), 2)
  assert.equal(trackCount({ media: {} }), 0)
  assert.equal(trackCount({}), 0)
  assert.equal(trackCount(null), 0)
  assert.equal(trackCount({ media: { numTracks: 0, tracks: [1] } }), 0, "a minified item's count is the count")
})

test('collection page opens without a live user', async () => {
  const absData = createDataService({ plugin: fakePlugin(), store: fakeStore({ serverState: 'offline' }) })
  const page = collectionPage({
    absData,
    user: null,
    localItems: [
      { id: 'local_b', libraryItemId: 'li0', serverConnectionConfigId: 'b' },
      { id: 'local_a', libraryItemId: 'li1', serverConnectionConfigId: 'a' },
      { id: 'local_ino', libraryItemId: 'other', ino: '1002', serverConnectionConfigId: 'a' }
    ]
  })
  const { redirected, result } = await page.asyncData()
  assert.equal(redirected, null, 'no /connect redirect')
  assert.equal(result.collection.name, 'Мои')
  assert.deepEqual(plain(result.collection.books.map((b) => b.id)), ['li0', 'li1', 'li2'])
  assert.equal(result.collection.books[0].localLibraryItem, undefined, "another account's copy is not this book's")
  assert.equal(result.collection.books[1].localLibraryItem.id, 'local_a')
  assert.equal(result.collection.books[2].localLibraryItem.id, 'local_ino', 'by ino too')

  const view = await page.mount()
  assert.equal(view.playableItems.length, 3, 'minified books count their tracks')
  assert.equal(view.showPlayButton, 3)

  // Gone, or a failed read: back to the tab, quietly or with a toast
  const gone = collectionPage({ absData: { getCollection: async () => ({ serverConfigId: 'a', collection: null, replica: replica() }) } })
  assert.equal((await gone.asyncData()).redirected, '/bookshelf/collections')
  const failed = collectionPage({ absData: { getCollection: async () => Promise.reject(new Error('bridge')) } })
  assert.equal((await failed.asyncData()).redirected, '/bookshelf/collections?error=collection')

  const tab = await read('../pages/bookshelf/collections.vue')
  const component = new Function(tab.match(/<script>([\s\S]*?)<\/script>/)[1].replace('export default', 'return'))()
  const toasts = []
  const replaced = []
  const tabView = { $route: { path: '/bookshelf/collections', query: {} }, $toast: { error: (m) => toasts.push(m) }, $router: { replace: (to) => replaced.push(to) }, $strings: { ToastCollectionLoadFailed: 'Could not open the collection' } }
  const watcher = component.watch['$route.query.error']
  assert.equal(watcher.immediate, true)
  watcher.handler.call(tabView, undefined)
  watcher.handler.call(tabView, 'collection')
  assert.deepEqual(toasts, ['Could not open the collection'])
  assert.deepEqual(replaced, [{ path: '/bookshelf/collections', query: {} }])
  const strings = JSON.parse(await read('../strings/en-us.json'))
  assert.equal(strings.ToastCollectionLoadFailed, 'Could not open the collection')
})

test('collection page loads every page of books', async () => {
  const books = booksOf(230)
  const plugin = fakePlugin({ books })
  const page = collectionPage({ absData: createDataService({ plugin, store: fakeStore() }) })
  const { result } = await page.asyncData()
  const bookCalls = plugin.calls.filter(([name]) => name === 'getCollectionBooks').map(([, args]) => args)
  assert.deepEqual(plain(bookCalls), [0, 1, 2].map((p) => ({ id: 'c1', limit: 100, page: p })))
  assert.deepEqual(result.collection.books.map((b) => b.id), books.map((b) => b.id), 'all 230, in order')

  // The browser: the same shapes cut from /api/collections/:id
  const gets = []
  const $axios = {
    $get: async (path) => {
      gets.push(path)
      if (path === '/api/collections/c1') return serverCollection(230)
      if (path.startsWith('/api/libraries/l1/collections')) return { results: [serverCollection(230)], total: 1, limit: '20', page: '0' }
      throw Object.assign(new Error('404'), { response: { status: 404 } })
    }
  }
  const web = createDataService({ plugin: { addListener: () => ({}), getServerState: async () => ({}) }, store: { state: { activeServerConfigId: 'a' }, commit() {} }, platform: 'web', $axios })
  const list = await web.getCollections({ libraryId: 'l1', limit: 20, page: 0 })
  assert.equal(gets[0], '/api/libraries/l1/collections?limit=20&page=0')
  assert.equal(list.results[0].numBooks, 230)
  assert.equal(list.results[0].books.length, 4, 'previews only')
  const one = await web.getCollection({ id: 'c1' })
  assert.equal(one.collection.numBooks, 230)
  assert.equal(one.collection.books, undefined)
  const webPage = collectionPage({ absData: web })
  const webResult = (await webPage.asyncData()).result
  assert.deepEqual(webResult.collection.books.map((b) => b.id), books.map((b) => b.id))
  assert.deepEqual(gets.slice(-3), ['/api/collections/c1', '/api/collections/c1', '/api/collections/c1'], 'one request a page of 100')
  const last = await web.getCollectionBooks({ id: 'c1', limit: 100, page: 2 })
  assert.deepEqual({ total: last.total, length: last.results.length, found: last.found }, { total: 230, length: 30, found: true })
  assert.equal((await web.getCollectionBooks({ id: 'gone', limit: 100, page: 0 })).found, false)
  assert.equal((await web.getCollection({ id: 'gone' })).collection, null)
})

test('a collection that moves while it loads is read again once', async () => {
  const books = booksOf(150)
  let reads = 0
  const moving = {
    getCollectionBooks: async ({ limit, page }) => {
      if (page === 0) reads++
      // The first read sees a book twice (moved between pages)
      const results = reads === 1 && page === 1 ? [books[0], ...books.slice(101, 150)] : books.slice(page * limit, page * limit + limit)
      return { serverConfigId: 'a', results, total: 150, limit, page, found: true }
    }
  }
  assert.equal((await fetchCollectionBooks(moving, 'c1')).length, 150)
  assert.equal(reads, 2)

  const alwaysMoving = { getCollectionBooks: async ({ page }) => ({ serverConfigId: 'a', results: [books[0]], total: 150, limit: 100, page, found: true }) }
  await assert.rejects(() => fetchCollectionBooks(alwaysMoving, 'c1'), /changed while loading/)

  const otherServer = { getCollectionBooks: async ({ page }) => ({ serverConfigId: 'b', results: [books[page]], total: 2, limit: 100, page, found: true }) }
  await assert.rejects(() => fetchCollectionBooks(otherServer, 'c1', { serverConfigId: 'a' }), /server changed/)
  assert.equal(await fetchCollectionBooks({ getCollectionBooks: async () => ({ serverConfigId: 'a', results: [], total: 0, found: false }) }, 'c1'), null)
})

test('a book removed or a source switched between pages reads the collection again', async () => {
  const books = booksOf(150)
  // Read 1: the first book leaves the collection after page 0, so page 1 starts one later and li100 would be lost
  let reads = 0
  const shrinking = {
    getCollectionBooks: async ({ limit, page }) => {
      if (page === 0) reads++
      const now = reads === 1 && page === 1 ? books.slice(1) : books
      return { serverConfigId: 'a', results: now.slice(page * limit, page * limit + limit), total: now.length, limit, page, found: true }
    }
  }
  const read = await fetchCollectionBooks(shrinking, 'c1')
  assert.equal(reads, 2)
  assert.deepEqual(read.map((b) => b.id), books.map((b) => b.id), 'no book silently lost')

  // The replica took over from the server between pages (its first reconcile finished)
  reads = 0
  const switching = {
    getCollectionBooks: async ({ limit, page }) => {
      if (page === 0) reads++
      return { serverConfigId: 'a', results: books.slice(page * limit, page * limit + limit), total: 150, limit, page, found: true, ...(reads === 1 && page === 0 ? { fromServer: true } : {}) }
    }
  }
  assert.equal((await fetchCollectionBooks(switching, 'c1')).length, 150)
  assert.equal(reads, 2)

  let count = 150
  const alwaysShrinking = { getCollectionBooks: async ({ limit, page }) => ({ serverConfigId: 'a', results: books.slice(page * limit, page * limit + limit), total: count--, limit, page, found: true }) }
  await assert.rejects(() => fetchCollectionBooks(alwaysShrinking, 'c1'), /changed while loading/)
})

test('never-reconciled collections read the server while online', async () => {
  const http = fakeHttp((path) => (path.startsWith('/api/collections/') ? serverCollection(7) : { results: [serverCollection(7)], total: 1, limit: '20', page: '1' }))
  const plugin = fakePlugin({ books: [], reconciledAt: null })
  const online = createDataService({ plugin, store: fakeStore(), nativeHttp: () => http })

  const list = await online.getCollections({ libraryId: 'l 1', limit: 20, page: 1 })
  assert.equal(http.paths[0], '/api/libraries/l%201/collections?limit=20&page=1')
  assert.equal(list.fromServer, true)
  assert.equal(list.results[0].numBooks, 7)
  assert.equal(list.results[0].books.length, 4)
  assert.equal(list.replica.reconciledAt, null, "native's replica state is kept")

  const one = await online.getCollection({ id: 'c 1' })
  assert.equal(http.paths[1], '/api/collections/c%201')
  assert.deepEqual(plain(one), { collection: { id: 'c1', libraryId: 'l1', name: 'Мои', description: null, lastUpdate: 2, createdAt: 1, numBooks: 7 }, serverConfigId: 'a', replica: replica(null), fromServer: true })

  const books = await online.getCollectionBooks({ id: 'c1', limit: 5, page: 1 })
  assert.equal(http.paths[2], '/api/collections/c1')
  assert.deepEqual(books.results.map((b) => b.id), ['li5', 'li6'])
  assert.equal(books.total, 7)
  assert.equal(books.fromServer, true)

  // A read replica without the collection (made since the last sync) asks the server; a 404 leaves native's answer
  const absent = fakePlugin({ overrides: { getCollection: async () => ({ serverConfigId: 'a', collection: null, replica: replica() }), getCollectionBooks: async ({ limit, page }) => ({ serverConfigId: 'a', results: [], total: 0, limit, page, found: false, replica: replica() }) } })
  const newOnServer = createDataService({ plugin: absent, store: fakeStore(), nativeHttp: () => fakeHttp(() => serverCollection(2)) })
  assert.equal((await newOnServer.getCollection({ id: 'c1' })).collection.numBooks, 2)
  assert.equal((await newOnServer.getCollectionBooks({ id: 'c1', limit: 100, page: 0 })).results.length, 2)
  const notFound = { get: async () => Promise.reject(Object.assign(new Error('Not Found'), { status: 404 })) }
  const gone = createDataService({ plugin: absent, store: fakeStore(), nativeHttp: () => notFound })
  assert.equal((await gone.getCollection({ id: 'c1' })).collection, null)
  assert.equal((await gone.getCollectionBooks({ id: 'c1', limit: 100, page: 0 })).found, false)

  // A read replica answers; offline nothing goes to the server
  const reconciled = createDataService({ plugin: fakePlugin(), store: fakeStore(), nativeHttp: () => fakeHttp(() => assert.fail('a read replica does not go to the server')) })
  assert.equal((await reconciled.getCollections({ libraryId: 'l1', limit: 20, page: 0 })).fromServer, undefined)
  assert.equal((await reconciled.getCollectionBooks({ id: 'c1', limit: 100, page: 0 })).results.length, 3)
  const offline = createDataService({ plugin: fakePlugin({ books: [], reconciledAt: null }), store: fakeStore({ serverState: 'offline' }), nativeHttp: () => fakeHttp(() => assert.fail('no server offline')) })
  assert.equal((await offline.getCollection({ id: 'c1' })).collection.numBooks, 0)

  // The tab: an empty server answer is the usual empty state, an empty never-read replica offline is "not downloaded yet"
  const empty = { getCollections: async ({ limit, page }) => ({ serverConfigId: 'a', results: [], total: 0, limit, page, replica: replica(null) }) }
  const emptyServer = createDataService({ plugin: fakePlugin({ overrides: empty }), store: fakeStore(), nativeHttp: () => fakeHttp(() => ({ results: [], total: 0 })) })
  const shownOnline = await bookshelf({ getCollections: (args) => emptyServer.getCollections(args) })
  await shownOnline.view.init()
  assert.equal(shownOnline.view.catalogNotLoaded, false)
  const emptyOffline = createDataService({ plugin: fakePlugin({ overrides: empty }), store: fakeStore({ serverState: 'offline' }), nativeHttp: () => fakeHttp(() => assert.fail('offline')) })
  const shownOffline = await bookshelf({ getCollections: (args) => emptyOffline.getCollections(args) })
  await shownOffline.view.init()
  assert.equal(shownOffline.view.catalogNotLoaded, true)
})

/** LazyBookshelf on the Collections tab over fakes ($absData, store, DOM helpers stubbed). */
async function bookshelf({ getCollections, hasActiveServer = true, currentLibraryId = 'l1', realSize = false }) {
  const source = await read('../components/bookshelf/LazyBookshelf.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const component = new Function('bookshelfCardsHelpers', 'createPageRetry', 'textScale', 'shouldRefreshShelf', 'createDebouncer', 'isReplicaIncomplete', script)(
    {},
    () => ({ reset() {}, succeeded() {}, failed() {} }),
    () => 1,
    shouldRefreshShelf,
    createDebouncer,
    isReplicaIncomplete
  )
  const reads = []
  const emitted = []
  const store = {
    state: { user: { user: null }, serverState: 'offline', activeServerConfigId: 'a', libraries: { currentLibraryId }, globals: { bookshelfListView: false }, lastBookshelfScrollData: {} },
    getters: {
      getHasActiveServer: hasActiveServer,
      getServerSetting: () => false,
      'user/getUserSetting': () => null,
      'libraries/getCurrentLibraryMediaType': 'book',
      'libraries/getBookCoverAspectRatio': 1.6
    }
  }
  const view = {
    ...component.data(),
    page: 'collections',
    $store: store,
    $eventBus: { $emit: (name, value) => emitted.push([name, value]), $on() {}, $off() {} },
    $db: { getLocalLibraryItems: async () => [] },
    $absData: { getCollections: async (args) => (reads.push(args), getCollections(args)), onDataChanged: () => () => {} },
    $nativeHttp: { get: async () => assert.fail('the Collections tab does not read the server directly') },
    mountEntites() {},
    destroyEntityComponents() {}
  }
  if (!realSize) view.initSizeData = () => {}
  for (const [name, def] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: (def.get || def).bind(view), configurable: true })
  for (const [name, fn] of Object.entries(component.methods)) if (!(name in view)) view[name] = fn.bind(view)
  return { component, view, reads, emitted }
}

test('empty collections replica offline shows catalog-not-loaded', async () => {
  const never = await bookshelf({ getCollections: async ({ limit, page }) => ({ serverConfigId: 'a', results: [], total: 0, limit, page, replica: replica(null) }) })
  await never.view.init()
  assert.equal(never.view.catalogNotLoaded, true)
  assert.deepEqual(never.emitted.at(-1), ['bookshelf-total-entities', null], 'no count over it')

  const read = await bookshelf({ getCollections: async ({ limit, page }) => ({ serverConfigId: 'a', results: [], total: 0, limit, page, replica: replica() }) })
  await read.view.init()
  assert.equal(read.view.catalogNotLoaded, false, 'a read library without collections: "no collections"')

  const noServer = await bookshelf({ getCollections: async () => assert.fail('no read without an active server'), hasActiveServer: false })
  await noServer.view.init()
  assert.deepEqual(noServer.reads, [])
})

test('collections tab reads the replica at most 20 a page', async () => {
  const shelf = await bookshelf({ realSize: true, getCollections: async ({ limit, page }) => ({ serverConfigId: 'a', results: [{ id: 'c1', books: [] }], total: 1, limit, page, replica: replica() }) })
  // A tall screen that would ask 100 books at once
  globalThis.document = { getElementById: (id) => (id === 'bookshelf' ? { clientHeight: 7000, clientWidth: 412 } : null) }
  globalThis.window = { innerWidth: 412 }
  try {
    await shelf.view.init()
  } finally {
    delete globalThis.document
    delete globalThis.window
  }
  assert.equal(shelf.view.booksPerFetch, 20)
  assert.deepEqual(plain(shelf.reads), [{ libraryId: 'l1', limit: 20, page: 0 }])
  assert.equal(shelf.view.entities[0].id, 'c1')
  assert.equal(shelf.view.readsCatalog, true)
})

test('collections tab loads every page the first screen shows', async () => {
  const page = ({ limit, page }) => ({ serverConfigId: 'a', results: Array.from({ length: Math.max(0, Math.min(limit, 45 - page * limit)) }, (_, i) => ({ id: `c${page * limit + i}`, books: [] })), total: 45, limit, page, replica: replica() })
  const shelf = await bookshelf({ realSize: true, getCollections: async (args) => page(args) })
  // A tablet: more than 20 cards in view, 20 collections a read
  globalThis.document = { getElementById: (id) => (id === 'bookshelf' ? { clientHeight: 7000, clientWidth: 1600 } : null) }
  globalThis.window = { innerWidth: 1600 }
  try {
    await shelf.view.init()
    assert.deepEqual(shelf.reads.map((r) => r.page), [0, 1, 2], 'no card in view stays empty without a scroll')
    assert.equal(shelf.view.entities[44].id, 'c44')
    shelf.reads.length = 0
    await shelf.view.resetEntities()
    assert.deepEqual(shelf.reads.map((r) => r.page), [0, 1, 2], 'and after a reset')
  } finally {
    delete globalThis.document
    delete globalThis.window
  }
})

test('a page that answers after a reset is dropped', async () => {
  // Library l1 has 45 collections; page 1 of l1 is held until the shelf was reset to l2
  const held = []
  const answer = ({ libraryId, limit, page }) => {
    const count = libraryId === 'l1' ? 45 : 3
    const results = Array.from({ length: Math.max(0, Math.min(limit, count - page * limit)) }, (_, i) => ({ id: `${libraryId}-c${page * limit + i}`, books: [] }))
    return { serverConfigId: 'a', results, total: count, limit, page, replica: replica() }
  }
  const shelf = await bookshelf({
    realSize: true,
    getCollections: (args) => (args.libraryId === 'l1' && args.page === 1 ? new Promise((resolve) => held.push(() => resolve(answer(args)))) : answer(args))
  })
  globalThis.document = { getElementById: (id) => (id === 'bookshelf' ? { clientHeight: 7000, clientWidth: 1600 } : null) }
  globalThis.window = { innerWidth: 1600 }
  try {
    const initDone = shelf.view.init()
    await flush()
    assert.equal(held.length, 1, 'page 1 of l1 is in flight')
    shelf.view.$store.state.libraries.currentLibraryId = 'l2'
    await shelf.view.resetEntities()
    assert.deepEqual(plain(shelf.view.entities.map((c) => c.id)), ['l2-c0', 'l2-c1', 'l2-c2'])
    held.forEach((release) => release())
    await initDone
    await flush()
    assert.equal(shelf.view.totalEntities, 3)
    assert.deepEqual(plain(shelf.view.entities.map((c) => c.id)), ['l2-c0', 'l2-c1', 'l2-c2'], 'no collection of l1 in the l2 shelf')
  } finally {
    delete globalThis.document
    delete globalThis.window
  }
})

test('collection page re-reads on collections:<lib>', async () => {
  let books = booksOf(3)
  let collectionGone = false
  const pending = []
  let holdNext = false
  const absData = {
    getCollection: async ({ id }) => ({ serverConfigId: 'a', collection: collectionGone ? null : { id, libraryId: 'l1', name: 'Мои', numBooks: books.length }, replica: replica() }),
    getCollectionBooks: async ({ limit, page }) => {
      const answer = { serverConfigId: 'a', results: books.slice(page * limit, page * limit + limit), total: books.length, limit, page, found: !collectionGone, replica: replica() }
      if (!holdNext) return answer
      holdNext = false
      return new Promise((resolve) => pending.push(() => resolve(answer)))
    }
  }
  const page = collectionPage({ absData })
  const view = await page.mount()
  assert.equal(page.dataListeners.size, 1)

  page.send({ scope: 'catalog', ids: ['collections:l2', 'series:l1', 'authors:l1'] })
  page.send({ scope: 'progress', ids: ['li0'] })
  assert.equal(page.timers.length, 0, "another library's collections are not this page")

  // Reordered in the web client
  books = [books[2], books[0], books[1]]
  page.send({ scope: 'catalog', ids: ['collections:l1'] })
  page.send({ scope: 'catalog', ids: ['items:l1'] })
  assert.equal(page.timers.length, 1, 'debounced')
  assert.equal(page.timers[0].ms, 1000)
  page.timers.shift().fn()
  await flush()
  assert.deepEqual(view.collection.books.map((b) => b.id), ['li2', 'li0', 'li1'])

  // A late answer of an older read is dropped
  holdNext = true
  books = booksOf(1)
  const slow = view.reload()
  await flush()
  books = booksOf(2)
  await view.reload()
  pending.shift()()
  await slow
  assert.deepEqual(view.collection.books.map((b) => b.id), ['li0', 'li1'])

  collectionGone = true
  page.send({ scope: 'catalog', ids: ['collections:l1'] })
  page.timers.shift().fn()
  await flush()
  assert.deepEqual(page.replaced, ['/bookshelf/collections'], 'gone: back to the tab')

  page.send({ scope: 'catalog', ids: ['collections:l1'] })
  page.destroy(view)
  assert.equal(page.timers.length, 0, 'a pending re-read is dropped with the page')
  assert.equal(page.dataListeners.size, 0)
})

const coverScript = (await read('../components/covers/BookCover.vue')).match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')

/** BookCover's placeholder text over a fake item. */
function coverAuthor(libraryItem) {
  const component = new Function('Capacitor', coverScript)({})
  const view = { ...component.data(), libraryItem }
  for (const [name, get] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: get.bind(view) })
  return view.authorCleaned
}

test('book cover placeholder shows the author of a minified book', () => {
  assert.equal(coverAuthor({ media: { coverPath: null, metadata: { title: 'A', authorName: 'Лев Толстой' } } }), 'Лев Толстой')
  assert.equal(coverAuthor({ media: { metadata: { authors: [{ name: 'Ann' }, { name: 'Bob' }], authorName: 'ignored' } } }), 'Ann, Bob', 'an expanded book as before')
  assert.equal(coverAuthor({ mediaType: 'podcast', media: { metadata: { title: 'Pod', author: 'Pod Author' } } }), 'Pod Author')
  assert.equal(coverAuthor({ media: { metadata: { title: 'Nobody' } } }), '')
  assert.equal(coverAuthor({}), '')
})

test('a downloaded book in a collection opens its downloaded copy', async () => {
  const source = await read('../components/tables/collection/BookTableRow.vue')
  assert.match(source, /<nuxt-link v-if="book" :to="itemLink"/)
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const component = new Function('trackCount', script)(trackCount)
  const linkOf = (book) => {
    const view = { book }
    for (const [name, get] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: get.bind(view) })
    return view.itemLink
  }
  // Offline on a cold start the server item page has nothing to read and leaves for the home page
  assert.equal(linkOf({ id: 'li1', localLibraryItem: { id: 'local_li1' } }), '/item/li1?localLibraryItemId=local_li1')
  assert.equal(linkOf({ id: 'li2' }), '/item/li2')
})

test('collection screens offer no server-only edits', async () => {
  const files = ['../pages/collection/_id.vue', '../components/tables/collection/CollectionBooksTable.vue', '../components/tables/collection/BookTableRow.vue', '../components/cards/LazyCollectionCard.vue']
  const writes = /\$nativeHttp\.(post|patch|put|delete)\b|\$axios\.\$?(post|patch|put|delete)\b/
  for (const file of files) assert.doesNotMatch(await read(file), writes, `${file} writes to the server`)
  assert.match('this.$nativeHttp.delete(`/api/collections/c1`)', writes, 'the guard sees a write')
  assert.match('await this.$axios.$patch(`/api/collections/c1`, {})', writes)
})

test('collection events are catalog events', async () => {
  for (const event of ['collection_added', 'collection_updated', 'collection_removed']) {
    assert.ok(CATALOG_EVENTS.includes(event), event)
    assert.deepEqual(catalogTopics(event, { id: 'c1', libraryId: 'l1', books: [] }), ['collections:l1'])
  }
  assert.deepEqual(catalogTopics('collection_removed', { id: 'c1' }), [])
  const native = await read('../android/app/src/main/java/app/absplus/android/sync/CatalogEventApplier.kt')
  assert.match(native, /"collection_added", "collection_updated", "collection_removed"/, 'native applies them too')
  const web = await read('../plugins/capacitor/AbsData.js')
  for (const method of ['getCollections', 'getCollection', 'getCollectionBooks']) assert.match(web, new RegExp(`async ${method}\\(`), `web plugin ${method}`)
  const shelf = await read('../components/bookshelf/LazyBookshelf.vue')
  assert.match(shelf, /this\.\$absData\.getCollections\(\{ libraryId: this\.currentLibraryId, limit: this\.booksPerFetch, page \}\)/)
})
