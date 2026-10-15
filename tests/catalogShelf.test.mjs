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
async function bookshelf({ hasActiveServer = true, user = null, currentLibraryId = 'l1', activeServerConfigId = 'a', answer = null } = {}) {
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
  const store = {
    state: { user: { user }, activeServerConfigId, libraries: { currentLibraryId }, globals: { bookshelfListView: false }, lastBookshelfScrollData: {} },
    getters: {
      getHasActiveServer: hasActiveServer,
      getServerSetting: () => false,
      'user/getUserSetting': (k) => ({ mobileOrderBy: 'media.metadata.title', mobileOrderDesc: false, mobileFilterBy: 'all', collapseSeries: false }[k]),
      'libraries/getCurrentLibraryMediaType': 'book',
      'libraries/getBookCoverAspectRatio': 1.6
    }
  }
  const view = {
    ...component.data(),
    page: 'books',
    $store: store,
    $eventBus: { $emit: (name, value) => emitted.push([name, value]), $on() {}, $off() {} },
    $db: { getLocalLibraryItems: async () => [] },
    $absData: {
      getLibraryItems: async (args) => (reads.push(args), answer ?? { results: [], total: 0, replica: { reconciledAt: 5, complete: true } }),
      onDataChanged: () => () => {}
    },
    $nativeHttp: { get: async () => assert.fail('the Library tab does not read the server directly') },
    mountEntites() {},
    initSizeData() {},
    destroyEntityComponents() {}
  }
  for (const [name, def] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: (def.get || def).bind(view), configurable: true })
  for (const [name, fn] of Object.entries(component.methods)) if (!(name in view)) view[name] = fn.bind(view)
  return { component, view, reads, emitted, refreshes, store }
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
  assert.match(mixin, /var localLibraryItem = this\.findLocalCopy\(entity\)/)
})

test('library tab reads books through $absData and is not gated by the live user', async () => {
  const shelf = await read('../components/bookshelf/LazyBookshelf.vue')
  const fetch = shelf.slice(shelf.indexOf('async fetchEntities(page)'), shelf.indexOf('/** The downloaded copy'))
  const books = fetch.slice(fetch.indexOf("if (this.entityName === 'books') {"), fetch.indexOf('} else {'))
  assert.match(books, /this\.\$absData\s*\.getLibraryItems\(/)
  assert.doesNotMatch(books, /\$nativeHttp/)
  assert.match(fetch, /const entityPath = this\.entityName === 'series-books' \? `items` : this\.entityName/, 'books never take the server path')
  assert.match(shelf, /canLoad\(\) \{\n\s*return this\.entityName === 'books' \? !!this\.\$store\.getters\.getHasActiveServer : !!this\.user/)
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
