// The Library tab on the catalog replica (offline-first 4a): LazyBookshelf reads books through $absData, shows
// "catalog not loaded" for a replica that was never filled, follows native's change notices, and book cards mark
// what cannot be played offline. Real component code against fakes; synthetic ids only.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const { shouldRefreshShelf, createDebouncer, decodeFilterValue } = await import(`data:text/javascript;base64,${Buffer.from(await read('../utils/catalogRefresh.js')).toString('base64')}`)
const encode = (text) => encodeURIComponent(Buffer.from(text).toString('base64'))

const flush = async (n = 20) => {
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

test('catalogShelf: shouldRefreshShelf decides by tab, library, sort and filter', () => {
  const catalog = (...ids) => ({ scope: 'catalog', ids })
  const shelf = (entity, more = {}) => ({ entity, libraryId: 'l1', sort: 'media.metadata.title', filter: 'all', collapseSeries: false, ...more })
  const table = [
    [catalog('items:l1'), shelf('books'), true],
    [catalog('items:l2'), shelf('books'), false],
    [catalog('state:l1'), shelf('books'), true],
    [catalog('series:l1'), shelf('books'), false],
    [catalog('series:l1'), shelf('books', { collapseSeries: true }), true],
    [catalog('series:l1'), shelf('books', { filter: `series.${encode('s1')}` }), true],
    [catalog('library:l1', 'libraries'), shelf('books'), false],
    [catalog('series:l1'), shelf('series'), true],
    [catalog('items:l1'), shelf('series-books'), true],
    [catalog('authors:l1'), shelf('authors'), true],
    [catalog('items:l1'), shelf('authors'), false],
    [catalog('collections:l1'), shelf('collections'), true],
    [catalog('items:l1'), shelf('collections'), true],
    [catalog('playlists:l1'), shelf('playlists'), true],
    [catalog('series:l1'), shelf('playlists'), false],
    [{ scope: 'progress', ids: ['li1'] }, shelf('books', { sort: 'progress' }), true],
    [{ scope: 'progress', ids: ['li1'] }, shelf('books', { sort: 'progress.finishedAt' }), true],
    [{ scope: 'progress', ids: ['li1'] }, shelf('books', { sort: 'addedAt' }), false],
    [{ scope: 'progress', ids: ['li1'] }, shelf('books', { sort: 'addedAt', filter: `progress.${encode('finished')}` }), true],
    [{ scope: 'progress', ids: ['li1'] }, shelf('authors', { sort: 'progress' }), false],
    [{ scope: 'playback', ids: ['li1'] }, shelf('books', { sort: 'progress' }), true],
    [{ scope: 'playback', ids: ['li1'] }, shelf('books', { sort: 'addedAt' }), false],
    [{ scope: 'playback', ids: ['li1'] }, shelf('books', { sort: 'addedAt', filter: 'progress.' + encode('not-started') }), true],
    [{ scope: 'playback', ids: ['li1'] }, shelf('books', { sort: 'addedAt', filter: 'progress.' + encode('in-progress') }), true],
    [{ scope: 'playback', ids: ['li1'] }, shelf('books', { sort: 'addedAt', filter: 'progress.' + encode('finished') }), false],
    [{ scope: 'playback', ids: ['li1'] }, shelf('books', { sort: 'addedAt', filter: 'progress.%%%broken' }), false],
    [{ scope: 'bookmarks', ids: ['li1'] }, shelf('books', { sort: 'progress' }), false]
  ]
  table.forEach(([change, options, expected], i) => assert.equal(shouldRefreshShelf(change, options), expected, `row ${i}: ${JSON.stringify([change, options])}`))
  assert.equal(decodeFilterValue(encode('in-progress')), 'in-progress')
  assert.equal(decodeFilterValue('!!!'), null)
})

test('a burst of catalog changes refreshes the shelf once', async () => {
  const timers = fakeTimers()
  const debouncer = createDebouncer({ delayMs: 1000, ...timers })
  let refreshed = 0
  for (let i = 0; i < 5; i++) debouncer.schedule(() => refreshed++)
  assert.equal(refreshed, 0)
  assert.deepEqual(timers.run(), [1000])
  assert.equal(refreshed, 1)

  // And through the shelf itself: five notices, one re-read of the loaded pages
  const shelf = await bookshelf()
  shelf.view.refreshDebouncer = createDebouncer({ delayMs: 1000, ...timers })
  shelf.view.refreshLoadedPages = () => shelf.refreshes.push(1)
  for (let i = 0; i < 5; i++) shelf.view.dataChanged({ scope: 'catalog', ids: ['items:l1'] })
  timers.run()
  assert.equal(shelf.refreshes.length, 1)
})

/** LazyBookshelf's script as a plain object over fakes ($absData, store, DOM helpers stubbed). */
async function bookshelf({ hasActiveServer = true, user = null, currentLibraryId = 'l1', activeServerConfigId = 'a', answer = null, page = 'books', seriesId = null, settings = {} } = {}) {
  const source = await read('../components/bookshelf/LazyBookshelf.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const component = new Function('bookshelfCardsHelpers', 'createPageRetry', 'textScale', 'shouldRefreshShelf', 'createDebouncer', script)(
    {},
    () => ({ reset() {}, succeeded() {}, failed() {} }),
    () => 1,
    shouldRefreshShelf,
    createDebouncer
  )
  const reads = []
  const emitted = []
  const refreshes = []
  const subscriptions = []
  const store = {
    state: { user: { user }, activeServerConfigId, libraries: { currentLibraryId }, globals: { bookshelfListView: false }, lastBookshelfScrollData: {} },
    getters: {
      getHasActiveServer: hasActiveServer,
      getServerSetting: () => false,
      'user/getUserSetting': (k) => ({ mobileOrderBy: 'media.metadata.title', mobileOrderDesc: false, mobileFilterBy: 'all', collapseSeries: false, collapseBookSeries: false, ...settings }[k]),
      'libraries/getCurrentLibraryMediaType': 'book',
      'libraries/getBookCoverAspectRatio': 1.6
    }
  }
  const view = {
    ...component.data(),
    page,
    seriesId,
    $encode: encode,
    $store: store,
    $eventBus: { $emit: (name, value) => emitted.push([name, value]), $on() {}, $off() {} },
    $db: { getLocalLibraryItems: async () => [] },
    $absData: {
      getLibraryItems: async (args) => (reads.push(args), answer ?? { results: [], total: 0, replica: { reconciledAt: 5, complete: true } }),
      getSeries: async (args) => (reads.push({ series: args }), answer ?? { results: [], total: 0, replica: { reconciledAt: 5, complete: true } }),
      onDataChanged: (cb) => (subscriptions.push(cb), () => {})
    },
    $nativeHttp: { get: async () => assert.fail('the Library tab does not read the server directly') },
    mountEntites() {},
    initSizeData() {},
    destroyEntityComponents() {}
  }
  for (const [name, def] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: (def.get || def).bind(view), configurable: true })
  for (const [name, fn] of Object.entries(component.methods)) if (!(name in view)) view[name] = fn.bind(view)
  return { component, view, reads, emitted, refreshes, store, subscriptions }
}

test('no current library with an active server shows catalog-not-loaded', async () => {
  const { view, reads } = await bookshelf({ currentLibraryId: '' })
  await view.init()
  assert.equal(view.catalogNotLoaded, true)
  assert.deepEqual(reads, [])
})

test('without an active server the shelf behaves as before', async () => {
  const { view, reads, emitted } = await bookshelf({ hasActiveServer: false, user: { id: 'u1' } })
  await view.init()
  assert.deepEqual(reads, [], 'no replica read')
  assert.deepEqual(emitted.at(-1), ['bookshelf-total-entities', 0], 'the "offline support not available" branch')
  assert.equal(view.catalogNotLoaded, false)
})

test('empty replica offline shows catalog-not-loaded', async () => {
  const never = await bookshelf({ answer: { results: [], total: 0, replica: { reconciledAt: null, complete: false } } })
  await never.view.init()
  assert.equal(never.view.catalogNotLoaded, true)
  assert.equal(never.reads[0].limit, never.view.booksPerFetch)
  assert.deepEqual({ ...never.reads[0], limit: 0 }, { libraryId: 'l1', sort: 'media.metadata.title', desc: false, filter: null, collapseseries: false, limit: 0, page: 0, sortingIgnorePrefix: false })

  const fromServer = await bookshelf({ answer: { results: [], total: 0, fromServer: true, replica: { reconciledAt: null, complete: false } } })
  await fromServer.view.init()
  assert.equal(fromServer.view.catalogNotLoaded, false, 'an empty answer of the server is the usual "No books"')

  const unsupported = await bookshelf({ answer: { results: [], total: 0, unsupportedFilter: true, replica: { reconciledAt: 5, complete: true } } })
  await unsupported.view.init()
  assert.equal(unsupported.view.requiresServer, true)
  assert.equal(unsupported.view.catalogNotLoaded, false)
})

test('a copy of another account is not matched', async () => {
  const { view } = await bookshelf({ activeServerConfigId: 'a' })
  view.localLibraryItems = [{ id: 'local_b', libraryItemId: 'li1', serverConnectionConfigId: 'b' }]
  assert.equal(view.findLocalCopy({ id: 'li1' }), null)
  view.localLibraryItems.push({ id: 'local_a', libraryItemId: 'li1', serverConnectionConfigId: 'a' })
  assert.equal(view.findLocalCopy({ id: 'li1' }).id, 'local_a')
  // The card helper attaches copies through the same lookup
  const mixin = await read('../mixins/bookshelfCardsHelpers.js')
  assert.match(mixin, /instance\.setLocalLibraryItem\(this\.findLocalCopy\(entity\)\)/)
})

test('library tab reads books through $absData and is not gated by the live user', async () => {
  const shelf = await read('../components/bookshelf/LazyBookshelf.vue')
  const fetch = shelf.slice(shelf.indexOf('async fetchEntities(page)'), shelf.indexOf('/** The downloaded copy'))
  const catalog = shelf.slice(shelf.indexOf('readCatalogPage(page) {'), shelf.indexOf('/** The downloaded copy'))
  assert.match(catalog, /this\.\$absData\.getLibraryItems\(/)
  assert.doesNotMatch(catalog, /\$nativeHttp/)
  assert.match(fetch, /if \(this\.readsCatalog\) \{/, 'books never take the server path')
  assert.match(shelf, /canLoad\(\) \{\n\s*return this\.readsCatalog \? !!this\.\$store\.getters\.getHasActiveServer : !!this\.user/)
  assert.match(shelf, /readsCatalog\(\) \{\n\s*return CATALOG_ENTITIES\.includes\(this\.entityName\)/)
  assert.match(shelf, /const CATALOG_ENTITIES = \['books', 'series', 'series-books'\]/)
  for (const gate of ['async init()', 'async retryPage(page)', 'scroll(e)', 'async resetEntities()']) {
    const body = shelf.slice(shelf.indexOf(gate), shelf.indexOf('\n    },', shelf.indexOf(gate)))
    assert.match(body, /this\.canLoad/, `${gate} is gated by canLoad`)
    assert.doesNotMatch(body, /this\.user\b/, `${gate} no longer needs the live user`)
  }
  assert.match(shelf, /this\.booksPerFetch = Math\.min\(MAX_PAGE_SIZE,/)
  assert.match(shelf, /const MAX_PAGE_SIZE = 100/)
})

test('pages larger than 100 are split without gaps', async () => {
  const { component, view } = await bookshelf()
  // initSizeData from a page needing 140 cards: 70 shelves of 2
  const bookshelfEl = { clientHeight: 7000, clientWidth: 412 }
  globalThis.document = { getElementById: (id) => (id === 'bookshelf' ? bookshelfEl : null) }
  globalThis.window = { innerWidth: 412 }
  Object.defineProperty(view, 'shelfHeight', { get: () => 100, configurable: true })
  Object.defineProperty(view, 'totalEntityCardWidth', { get: () => 190, configurable: true })
  component.methods.initSizeData.call(view)
  assert.equal(view.entitiesPerShelf, 2)
  assert.ok(view.shelvesPerPage * view.entitiesPerShelf >= 140)
  assert.equal(view.booksPerFetch, 100)

  // 140 cards in view from index 90: pages 0 (90–99), 1 (100–199) and 2 (200–229) all load
  const loaded = []
  view.loadPage = (page) => {
    loaded.push(page)
    view.pagesLoaded[page] = true
  }
  view.entitiesPerShelf = 2
  view.totalEntities = 1000
  view.totalShelves = 500
  view.bookshelfHeight = 6900
  view.entityIndexesMounted = []
  view.handleScroll(4500)
  assert.deepEqual(loaded.sort(), [0, 1, 2])
  delete globalThis.document
  delete globalThis.window
})

/** A book card's computed values over a fake store, as mounted on a shelf. */
async function card(path, { serverState, localLibraryItem = null, queueSource = { sourceType: 'series' } }) {
  const source = await read(path)
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const component = new Function('Capacitor', script)({ isNativePlatform: () => true, getPlatform: () => 'android' })
  const view = {
    ...component.data(),
    queueSource,
    libraryItem: { id: 'li1', mediaType: 'book', media: { numTracks: 3, metadata: { title: 'A' } } },
    localLibraryItem,
    isSelectionMode: false,
    $store: { state: { serverState, isCasting: false, playerIsPlaying: false, globals: {} }, getters: new Proxy({}, { get: () => () => null }) }
  }
  for (const [name, def] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: (def.get || def).bind(view), configurable: true })
  return view
}

test('undownloaded card offline is marked and not playable', async () => {
  for (const path of ['../components/cards/LazyBookCard.vue', '../components/cards/LazyListBookCard.vue']) {
    const offline = await card(path, { serverState: 'offline' })
    assert.equal(offline.notAvailableOffline, true, path)
    assert.ok(!offline.showPlayButton, `${path}: no play offline`)
    const downloaded = await card(path, { serverState: 'offline', localLibraryItem: { id: 'local_1' } })
    assert.equal(downloaded.notAvailableOffline, false)
    assert.ok(downloaded.showPlayButton, `${path}: a downloaded copy plays offline`)
    const online = await card(path, { serverState: 'online' })
    assert.equal(online.notAvailableOffline, false)
    assert.ok(online.showPlayButton)
    const template = (await read(path)).match(/<template>([\s\S]*)<\/template>/)[1]
    assert.match(template, /v-if="notAvailableOffline"[^>]*>[\s\S]*?\$strings\.LabelNotAvailableOffline/)
  }
  // The card's play starts the item without a position: native picks where to start (release 3)
  const grid = await read('../components/cards/LazyBookCard.vue')
  const play = grid.slice(grid.indexOf('async play()'), grid.indexOf('async playEpisode()'))
  assert.doesNotMatch(play, /startTime/)
})

test('a re-read card loses the downloaded copy of the book that was on it', async () => {
  const shelf = await bookshelf({ answer: { results: [{ id: 'A' }], total: 1, replica: { reconciledAt: 5, complete: true } } })
  await shelf.view.init()
  shelf.view.localLibraryItems = [{ id: 'local_A', libraryItemId: 'A', serverConnectionConfigId: 'a' }]
  const locals = []
  shelf.view.entityComponentRefs[0] = { setEntity() {}, setLocalLibraryItem: (lli) => locals.push(lli?.id ?? null) }
  await shelf.view.fetchEntities(0)
  // The list moved: B (not downloaded) now sits where A was
  shelf.view.$absData.getLibraryItems = async () => ({ results: [{ id: 'B' }], total: 1, replica: { reconciledAt: 5, complete: true } })
  await shelf.view.fetchEntities(0)
  assert.deepEqual(locals, ['local_A', null])
})

test('a series-filtered item can be set on a card twice', async () => {
  const source = await read('../components/cards/LazyBookCard.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const component = new Function('Capacitor', script)({})
  const view = { ...component.data() }
  for (const [name, def] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: (def.get || def).bind(view), configurable: true })
  const item = (sequence) => ({ id: 'li1', media: { metadata: { title: 'A', series: { id: 's1', name: 'S', sequence } } } })
  component.methods.setEntity.call(view, item('1'))
  component.methods.setEntity.call(view, item('2'))
  assert.equal(view.libraryItem.media.metadata.series.sequence, '2')
})

test('the offline marker waits for the first server check', async () => {
  for (const path of ['../components/cards/LazyBookCard.vue', '../components/cards/LazyListBookCard.vue']) {
    assert.equal((await card(path, { serverState: 'unknown' })).notAvailableOffline, false, path)
    assert.equal((await card(path, { serverState: 'unauthorized' })).notAvailableOffline, true, path)
  }
})

test('a refresh reads only pages near the view and stops at a reset', async () => {
  const shelf = await bookshelf({ answer: { results: [{ id: 'A' }], total: 1000, replica: { reconciledAt: 5, complete: true } } })
  const { view } = shelf
  await view.init()
  Object.defineProperty(view, 'shelfHeight', { get: () => 100, configurable: true })
  Object.assign(view, { entitiesPerShelf: 2, booksPerFetch: 20, bookshelfHeight: 500, currScrollTop: 2000, initialized: true })
  view.pagesLoaded = { 0: true, 1: true, 2: true, 3: true, 4: true, 9: true }
  const fetched = []
  let resets = 0
  view.resetEntities = async () => {
    resets++
    view.resetGeneration++
  }
  view.fetchEntities = async (page) => {
    fetched.push(page)
    // A filter change resets the list while the refresh is running
    if (page === 1) await view.resetEntities()
  }
  await view.refreshLoadedPages()
  assert.deepEqual(fetched, [1], 'stopped after the reset')
  assert.equal(resets, 1, 'no second reset of its own')
  assert.ok(!view.pagesLoaded[9], 'a page far from the view loads again when scrolled to')
})

test('a list read from the server still follows the socket', async () => {
  const fromServer = await bookshelf({ answer: { results: [], total: 0, fromServer: true, replica: { reconciledAt: null, complete: false } } })
  await fromServer.view.init()
  let scheduled = 0
  fromServer.view.refreshDebouncer = { schedule: () => scheduled++, cancel() {} }
  fromServer.view.libraryItemAdded({ id: 'x', libraryId: 'l1' })
  fromServer.view.libraryItemsAdded([{ id: 'x', libraryId: 'l1' }])
  fromServer.view.libraryItemRemoved({ id: 'x', libraryId: 'l1' })
  assert.equal(scheduled, 3, 'each one schedules a re-read of the loaded pages')

  const replica = await bookshelf({ answer: { results: [], total: 0, replica: { reconciledAt: 5, complete: true } } })
  await replica.view.init()
  let replicaResets = 0
  replica.view.resetEntities = () => replicaResets++
  replica.view.libraryItemAdded({ id: 'x' })
  replica.view.libraryItemRemoved({ id: 'x' })
  assert.equal(replicaResets, 0, "the replica's own notice refreshes it")
})

test('a server-read list follows the socket by reading its pages again, keeping the scroll', async () => {
  const page = (total, ids, extra = {}) => ({ results: ids.map((id) => ({ id, libraryId: 'l1', ...extra[id] })), total, fromServer: true, replica: { reconciledAt: null, complete: false } })
  const collapsed = { s1: { collapsedSeries: { id: 'ser', numBooks: 3 } } }
  const shelf = await bookshelf({ answer: page(300, ['s1', 'b1', 'b2'], collapsed) })
  const { view } = shelf
  await view.init()
  const timers = fakeTimers()
  view.refreshDebouncer = createDebouncer({ delayMs: 1000, ...timers })
  const wrapper = { scrollTop: 700 }
  globalThis.document = { getElementById: (id) => (id === 'bookshelf-wrapper' ? wrapper : null) }
  globalThis.window = { innerWidth: 412 }
  Object.defineProperty(view, 'shelfHeight', { get: () => 100, configurable: true })
  Object.assign(view, { currScrollTop: 700, bookshelfHeight: 500, pagesLoaded: { 0: true } })
  let scrolled = null
  view.handleScroll = (top) => (scrolled = top)
  // As in the WebView: rebuilding the rows drops the scroll position
  const rebuild = view.resetEntities
  view.resetEntities = async () => {
    wrapper.scrollTop = 0
    return rebuild()
  }
  // The server after the removal of b1: one less, the series card is still first
  view.$absData.getLibraryItems = async () => page(299, ['s1', 'b2'], collapsed)

  view.libraryItemRemoved({ id: 'b1', libraryId: 'l1' })
  view.libraryItemRemoved({ id: 'x', libraryId: 'other' })
  assert.deepEqual(timers.run(), [1000], 'one debounced re-read')
  await flush()

  assert.equal(view.totalEntities, 299)
  assert.equal(view.entities[0].collapsedSeries.id, 'ser', 'the collapsed series stays')
  assert.equal(wrapper.scrollTop, 700)
  assert.equal(scrolled, 700, 'the scroll position stays')
  delete globalThis.document
  delete globalThis.window

  // Only books of this library move it
  view.libraryItemsAdded([{ id: 'y', libraryId: 'other' }])
  assert.deepEqual(timers.run(), [])
})

test('a late answer of a re-read is dropped after a reset', async () => {
  const shelf = await bookshelf({ answer: { results: [{ id: 'a', libraryId: 'l1' }], total: 1, fromServer: true, replica: { reconciledAt: null, complete: false } } })
  const { view } = shelf
  await view.init()
  let release
  view.$absData.getLibraryItems = () => new Promise((resolve) => (release = resolve))
  view.pagesLoaded = { 0: true }
  globalThis.window = { innerWidth: 412 }
  Object.defineProperty(view, 'shelfHeight', { get: () => 100, configurable: true })
  const refresh = view.refreshLoadedPages()
  await flush()
  // A filter change resets the list while the re-read waits for the server
  let resets = 0
  const reset = view.resetEntities
  view.resetEntities = async () => {
    resets++
    return reset()
  }
  view.resetEntities()
  release({ results: [{ id: 'stale', libraryId: 'l1' }], total: 1, fromServer: true, replica: { reconciledAt: null } })
  await refresh
  await flush()
  assert.ok(!view.entities.some((e) => e && e.id === 'stale'), 'the old answer is not written into the new list')
  assert.ok(resets >= 1)
  delete globalThis.window
})

test('empty series replica offline shows catalog-not-loaded', async () => {
  const never = await bookshelf({ page: 'series', user: null, answer: { results: [], total: 0, replica: { reconciledAt: null, complete: false } } })
  await never.view.init()
  assert.equal(never.view.catalogNotLoaded, true)
  assert.deepEqual({ ...never.reads[0].series, limit: 0 }, { libraryId: 'l1', sort: 'name', desc: false, limit: 0, page: 0, sortingIgnorePrefix: false }, 'read without a signed-in user')

  const read = await bookshelf({ page: 'series', answer: { results: [], total: 0, replica: { reconciledAt: 5, complete: true } } })
  await read.view.init()
  assert.equal(read.view.catalogNotLoaded, false, 'a read replica without series is the usual "No series"')
})

test('a series page lists its books through $absData and follows the catalog', async () => {
  const shelf = await bookshelf({ page: 'series-books', seriesId: 's1', settings: { collapseBookSeries: true } })
  await shelf.view.init()
  globalThis.document = { getElementById: () => null, addEventListener() {} }
  globalThis.screen = {}
  shelf.view.$socket = { $on() {} }
  shelf.view.initListeners()
  delete globalThis.document
  delete globalThis.screen
  assert.deepEqual({ ...shelf.reads[0], limit: 0 }, { libraryId: 'l1', sort: null, desc: false, filter: `series.${encode('s1')}`, collapseseries: true, limit: 0, page: 0, sortingIgnorePrefix: false })
  assert.equal(shelf.subscriptions.length, 1, 'subscribed to native notices')

  let refreshed = 0
  shelf.view.refreshDebouncer = { schedule: () => refreshed++, cancel() {} }
  shelf.view.dataChanged({ scope: 'catalog', ids: ['series:l1'] })
  shelf.view.dataChanged({ scope: 'catalog', ids: ['items:l2'] })
  assert.equal(refreshed, 1)

  const socketResets = []
  shelf.view.resetEntities = () => socketResets.push(1)
  shelf.view.libraryItemAdded({ id: 'x' })
  assert.deepEqual(socketResets, [], "the replica's notice refreshes it, not the socket")
})


test('a series page read from the server follows a removal over the socket', async () => {
  const shelf = await bookshelf({ page: 'series-books', seriesId: 's1', answer: { results: [{ id: 'x' }, { id: 'y' }], total: 2, fromServer: true, replica: { reconciledAt: null, complete: false } } })
  await shelf.view.init()
  let scheduled = 0
  shelf.view.refreshDebouncer = { schedule: () => scheduled++, cancel() {} }
  shelf.view.resetEntities = () => assert.fail('no reset to the top')
  shelf.view.libraryItemRemoved({ id: 'x', libraryId: 'l1' })
  shelf.view.libraryItemUpdated({ id: 'y', libraryId: 'l1' })
  shelf.view.libraryItemRemoved({ id: 'z', libraryId: 'l2' })
  assert.equal(scheduled, 2, "its loaded pages are read again; another library's book is ignored")

  const series = await bookshelf({ page: 'series', answer: { results: [], total: 0, fromServer: true, replica: { reconciledAt: null, complete: false } } })
  await series.view.init()
  series.view.refreshDebouncer = { schedule: () => scheduled++, cancel() {} }
  series.view.resetEntities = () => assert.fail('no reset to the top')
  series.view.libraryItemsAdded([{ id: 'n', libraryId: 'l1' }])
  assert.equal(scheduled, 3)
})
