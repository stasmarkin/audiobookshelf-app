// The Authors tab on the catalog replica (offline-first 4c): $absData reads authors from native replica.db a page of
// at most 100 at a time, falls back to the server while the authors were never read, and the tab follows native's
// catalog notices instead of the socket. Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { createDataService, catalogTopics } = await importSource('../utils/dataService.js')
const { shouldRefreshShelf, createDebouncer, isReplicaIncomplete } = await importSource('../utils/catalogRefresh.js')
const plain = (value) => JSON.parse(JSON.stringify(value))
const replica = (reconciledAt = 5) => ({ reconciledAt, complete: reconciledAt != null })
const flush = async (n = 20) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}

const authorsOf = (libraryId, count) => Array.from({ length: count }, (_, i) => ({ id: `${libraryId}-a${i}`, name: `Author ${i}`, numBooks: 1 }))

/** A replica answer of `getAuthors` cut from `all`. */
const pageOf = (all, { limit, page }, extra = {}) => ({
  serverConfigId: 'a',
  results: all.slice(page * limit, page * limit + limit),
  total: all.length,
  limit,
  page,
  replica: replica(),
  ...extra
})

const authorsScript = (await read('../pages/bookshelf/authors.vue'))
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '')
  .replace('export default', 'globalThis.component =')

/** authors.vue over fakes: data, computed getters and methods on one object, as Vue would bind them. */
function authorsPage({ getAuthors, state = {}, getters = {} }) {
  const timers = []
  const fakeDebouncer = (options) => createDebouncer({ ...options, setTimer: (fn, ms) => (timers.push({ fn, ms }), timers.at(-1)), clearTimer: (t) => timers.includes(t) && timers.splice(timers.indexOf(t), 1) })
  const errors = []
  const sandbox = { console: { ...console, log() {}, error: (...args) => errors.push(args) }, window: { innerWidth: 464 }, shouldRefreshShelf, createDebouncer: fakeDebouncer, isReplicaIncomplete }
  vm.runInNewContext(authorsScript, sandbox)
  const component = sandbox.component
  const store = {
    state: { serverState: 'online', activeServerConfigId: 'a', libraries: { currentLibraryId: 'l1' }, ...state },
    getters: { getHasActiveServer: true, 'libraries/getCurrentLibraryMediaType': 'book', ...getters }
  }
  const emitted = []
  const handlers = {}
  const dataListeners = new Set()
  const httpCalls = []
  const view = {
    ...component.data(),
    $store: store,
    $absData: {
      getAuthors,
      onDataChanged: (cb) => (dataListeners.add(cb), () => dataListeners.delete(cb))
    },
    $nativeHttp: { get: async (path) => (httpCalls.push(path), { authors: [] }) },
    $eventBus: { $emit: (name, value) => emitted.push([name, value]), $on: (name, fn) => (handlers[name] = fn), $off: (name) => delete handlers[name] },
    $router: { replace() {} }
  }
  for (const [name, get] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: get.bind(view) })
  for (const [name, fn] of Object.entries(component.methods)) view[name] = fn.bind(view)
  return {
    view,
    component,
    store,
    emitted,
    handlers,
    httpCalls,
    errors,
    timers,
    mount: () => component.mounted.call(view),
    destroy: () => component.beforeDestroy.call(view),
    send: (change) => dataListeners.forEach((cb) => cb(change)),
    dataListeners
  }
}

test('authors page loads every page through $absData', async () => {
  const all = authorsOf('l1', 230)
  const calls = []
  const page = authorsPage({ getAuthors: async (args) => (calls.push(args), pageOf(all, args)) })
  await page.view.init()
  assert.deepEqual(plain(calls), [0, 1, 2].map((p) => ({ libraryId: 'l1', limit: 100, page: p })))
  assert.equal(page.view.authors.length, 230)
  assert.deepEqual(plain(page.view.authors.map((a) => a.id)), all.map((a) => a.id))
  assert.deepEqual(page.emitted.at(-1), ['bookshelf-total-entities', 230])
  assert.deepEqual(page.httpCalls, [], '$nativeHttp is not asked')
  assert.equal(page.view.catalogNotLoaded, false)

  // Each page shows as it comes
  let release
  const shown = []
  const slow = authorsPage({
    getAuthors: async (args) => {
      shown.push(slow.view.authors.length)
      if (args.page === 1) await new Promise((resolve) => (release = resolve))
      return pageOf(all, args)
    }
  })
  const loading = slow.view.init()
  await flush()
  assert.equal(slow.view.authors.length, 100, 'the first page is on screen while the second loads')
  release()
  await loading
  assert.deepEqual(shown, [0, 100, 200])

  // A re-read replaces the list in place and drops what went away
  const fewer = all.slice(0, 150)
  const lengths = []
  const reread = authorsPage({ getAuthors: async (args) => (lengths.push(reread.view.authors.length), pageOf(reread.source, args)) })
  reread.source = all
  await reread.view.init()
  reread.source = fewer
  await reread.view.init()
  assert.deepEqual(lengths, [0, 100, 200, 230, 230], 'not emptied before the new pages arrive')
  assert.equal(reread.view.authors.length, 150)
  assert.deepEqual(reread.emitted.at(-1), ['bookshelf-total-entities', 150])
})

test('a library switch during loading drops the old pages', async () => {
  const pending = []
  const page = authorsPage({ getAuthors: (args) => new Promise((resolve) => pending.push({ args, resolve })) })
  page.view.init()
  await flush()
  page.store.state.libraries.currentLibraryId = 'l2'
  const second = page.view.init()
  await flush()
  // The second library answers first, then the old one's page arrives late
  pending[1].resolve(pageOf(authorsOf('l2', 3), pending[1].args))
  await second
  pending[0].resolve(pageOf(authorsOf('l1', 230), pending[0].args))
  // Not awaited: a load that ignored the switch would wait on its next page for good
  await flush()
  assert.deepEqual(plain(page.view.authors.map((a) => a.id)), ['l2-a0', 'l2-a1', 'l2-a2'])
  assert.equal(pending.length, 2, 'the old library asks no further page')
  assert.deepEqual(page.emitted, [['bookshelf-total-entities', 3]])

  // An answer for a server that is no longer active is dropped too
  const other = authorsPage({ getAuthors: async (args) => pageOf(authorsOf('l1', 2), args, { serverConfigId: 'b' }) })
  await other.view.init()
  assert.deepEqual(plain(other.view.authors), [])
  assert.deepEqual(other.emitted, [])
})

test('never-reconciled authors read the server while online', async () => {
  const native = []
  const plugin = (answer) => ({
    addListener: () => ({ remove() {} }),
    getServerState: async () => ({}),
    async getAuthors(args) {
      native.push(args)
      return { serverConfigId: 'a', results: [], total: 0, limit: args.limit, page: args.page, replica: replica(answer) }
    }
  })
  const store = (serverState) => ({ state: { serverState, activeServerConfigId: 'a', user: { serverConnectionConfig: { id: 'a' } }, libraries: { currentLibraryId: 'l 1' } }, commit() {}, async dispatch() {} })
  const http = (answer) => {
    const paths = []
    return { paths, get: async (path) => (paths.push(path), answer) }
  }

  const server = http({ results: [{ id: 'au1', name: 'Ann' }], total: 1, limit: 100, page: 0 })
  const online = createDataService({ plugin: plugin(null), store: store('online'), nativeHttp: () => server })
  const answer = await online.getAuthors({ libraryId: 'l 1', limit: 100, page: 0 })
  assert.deepEqual(native, [{ libraryId: 'l 1', limit: 100, page: 0 }])
  assert.deepEqual(server.paths, ['/api/libraries/l%201/authors?sort=name&desc=0&limit=100&page=0'], 'sorted, so the pages do not overlap')
  assert.equal(answer.fromServer, true)
  assert.deepEqual(plain(answer.results), [{ id: 'au1', name: 'Ann' }])
  assert.equal(answer.replica.reconciledAt, null, "native's replica state is kept")

  // A server before 2.15.0 ignores limit and page and answers every author as `authors`
  const all = authorsOf('l1', 230)
  const legacy = createDataService({ plugin: plugin(null), store: store('online'), nativeHttp: () => http({ authors: all }) })
  const second = await legacy.getAuthors({ libraryId: 'l1', limit: 100, page: 1 })
  assert.deepEqual(plain(second.results).map((a) => a.id), all.slice(100, 200).map((a) => a.id))
  assert.equal(second.total, 230)
  assert.equal(second.page, 1)
  assert.equal(second.fromServer, true)
  const legacyTab = authorsPage({ getAuthors: (args) => legacy.getAuthors(args) })
  await legacyTab.view.init()
  assert.equal(legacyTab.view.authors.length, 230, 'pages of the whole list, each author once')

  const reconciled = createDataService({ plugin: plugin(9), store: store('online'), nativeHttp: () => http(assert.fail) })
  assert.equal((await reconciled.getAuthors({ libraryId: 'l1', limit: 100, page: 0 })).fromServer, undefined, 'a read replica answers')

  // The tab: an empty server answer is an empty list, an empty never-read replica offline is "not downloaded yet"
  const emptyServer = createDataService({ plugin: plugin(null), store: store('online'), nativeHttp: () => http({ results: [], total: 0 }) })
  const shownOnline = authorsPage({ getAuthors: (args) => emptyServer.getAuthors(args) })
  await shownOnline.view.init()
  assert.equal(shownOnline.view.catalogNotLoaded, false)

  const offlineService = createDataService({ plugin: plugin(null), store: store('offline'), nativeHttp: () => http(assert.fail) })
  const shownOffline = authorsPage({ getAuthors: (args) => offlineService.getAuthors(args), state: { serverState: 'offline' } })
  await shownOffline.view.init()
  assert.equal(shownOffline.view.catalogNotLoaded, true)
  const template = (await read('../pages/bookshelf/authors.vue')).match(/<template>([\s\S]*)<\/template>/)[1]
  assert.match(template, /v-if="catalogNotLoaded"[\s\S]*\$strings\.MessageCatalogNotLoaded/)

  // A failed read is no endless load
  const failing = authorsPage({ getAuthors: async () => Promise.reject(new Error('bridge')) })
  await failing.view.init()
  assert.deepEqual(plain(failing.view.authors), [])
  assert.equal(failing.view.loading, false)
  assert.equal(failing.errors.length, 1)

  // Without an active server nothing is read, as the other catalog tabs
  const noServer = authorsPage({ getAuthors: async () => assert.fail('no read without an active server'), getters: { getHasActiveServer: false } })
  await noServer.view.init()
  assert.deepEqual(plain(noServer.view.authors), [])
})

test('in the browser authors go to $axios in native shape', async () => {
  const gets = []
  const $axios = { $get: async (path, options) => (gets.push([path, options?.params ?? null]), { results: [{ id: 'au1' }], total: 1 }) }
  const data = createDataService({ plugin: { addListener: () => ({}), getServerState: async () => ({}) }, store: { state: { activeServerConfigId: 'a' }, commit() {} }, platform: 'web', $axios })
  const answer = await data.getAuthors({ libraryId: 'l1', limit: 100, page: 1 })
  assert.deepEqual(plain(gets), [['/api/libraries/l1/authors', { sort: 'name', desc: 0, limit: 100, page: 1 }]])
  assert.equal(answer.replica.complete, true)
  assert.equal(answer.total, 1)
  const web = await read('../plugins/capacitor/AbsData.js')
  assert.match(web, /async getAuthors\(/, 'the web plugin answers an empty replica')
})

test('a catalog notice for the authors re-reads the tab', async () => {
  let reads = 0
  const page = authorsPage({ getAuthors: async (args) => (reads++, pageOf(authorsOf('l1', 2), args)) })
  page.mount()
  await flush()
  assert.equal(reads, 1)
  assert.equal(page.handlers['server-reachable'], undefined, 'native notices, not the server coming back')

  page.send({ scope: 'catalog', ids: ['authors:l2', 'items:l1'] })
  assert.equal(page.timers.length, 0, "another library's authors or the books are not this tab")
  page.send({ scope: 'catalog', ids: ['authors:l1'] })
  page.send({ scope: 'catalog', ids: ['state:l1'] })
  assert.equal(page.timers.length, 1, 'debounced')
  assert.equal(page.timers[0].ms, 1000)
  page.timers.shift().fn()
  await flush()
  assert.equal(reads, 2)

  page.send({ scope: 'catalog', ids: ['authors:l1'] })
  page.destroy()
  assert.equal(page.timers.length, 0, 'a pending re-read is dropped with the page')
  assert.equal(page.dataListeners.size, 0)
  assert.equal(page.handlers['library-changed'], undefined)

  const source = await read('../pages/bookshelf/authors.vue')
  assert.doesNotMatch(source, /\$socket|\$nativeHttp|server-reachable/)
})

test('catalog topics of author events', () => {
  assert.deepEqual(catalogTopics('authors_num_books_updated', { libraryId: 'l1', authors: [{ id: 'au1', numBooks: 2 }] }), ['authors:l1'])
  assert.deepEqual(catalogTopics('authors_num_books_updated', { authors: [] }), [])
  assert.deepEqual(catalogTopics('author_added', { id: 'au1', libraryId: 'l1' }), ['authors:l1', 'library:l1'])
  assert.deepEqual(catalogTopics('author_removed', { id: 'au1', libraryId: 'l1' }), ['authors:l1', 'library:l1'])
})

const imageScript = (await read('../components/covers/AuthorImage.vue')).match(/<script>([\s\S]*?)<\/script>/)[1].replace('export default', 'return')
const imageTemplate = (await read('../components/covers/AuthorImage.vue')).match(/<template>([\s\S]*)<\/template>/)[1]

/** AuthorImage's state over fakes. */
function authorImage({ serverState = 'online', author = { id: 'au1', imagePath: '/metadata/au1.jpg', updatedAt: 7 } } = {}) {
  const component = new Function(imageScript)()
  const view = {
    ...component.data(),
    author,
    $store: {
      state: { serverState },
      getters: { 'user/getServerAddress': 'https://abs.example', getDoesServerImagesRequireToken: false, 'user/getToken': 't' }
    }
  }
  for (const [name, get] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: get.bind(view) })
  for (const [name, fn] of Object.entries(component.methods)) view[name] = fn.bind(view)
  return { view, component }
}

test('author photo falls back to the placeholder offline', () => {
  assert.match(imageTemplate, /<svg v-if="!imgSrc"/, 'the placeholder follows imgSrc, not imagePath')
  assert.match(imageTemplate, /<img [^>]*@error="imageError"/)

  assert.equal(authorImage({ serverState: 'offline' }).view.imgSrc, null)
  assert.equal(authorImage({ serverState: 'unauthorized' }).view.imgSrc, null)
  const online = authorImage()
  assert.equal(online.view.imgSrc, 'https://abs.example/api/authors/au1/image?ts=7')

  online.view.imageError()
  assert.equal(online.view.imgSrc, null, 'a photo that fails to load shows the placeholder')
  // Another author (or a new photo) gets its own try
  online.component.watch.authorId.call(online.view)
  assert.equal(online.view.imgSrc, 'https://abs.example/api/authors/au1/image?ts=7')
  online.view.imageError()
  online.component.watch.updatedAt.call(online.view)
  assert.notEqual(online.view.imgSrc, null)

  assert.equal(authorImage({ author: { id: 'au2', imagePath: null } }).view.imgSrc, null, 'no photo, as before')
})

test('a partly read authors list says so while offline, like the Library tab', async () => {
  const cases = [
    [{ total: 3, replica: { reconciledAt: null, complete: false } }, 'offline', true],
    [{ total: 3, replica: { reconciledAt: 9, complete: false } }, 'offline', true],
    [{ total: 3, replica: { reconciledAt: 9, complete: false } }, 'online', false],
    [{ total: 3, replica: { reconciledAt: 9, complete: true } }, 'offline', false],
    [{ total: 0, replica: { reconciledAt: null, complete: false } }, 'offline', false],
    [{ total: 3, fromServer: true, replica: { reconciledAt: null, complete: false } }, 'offline', false]
  ]
  for (const [answer, serverState, shown] of cases) {
    const page = authorsPage({ getAuthors: async (args) => ({ ...pageOf(authorsOf('l1', answer.total), args), ...answer }), state: { serverState } })
    await page.view.init()
    assert.equal(page.view.showCatalogPartial, shown, JSON.stringify([answer, serverState]))
  }
  const source = await read('../pages/bookshelf/authors.vue')
  const shelf = await read('../components/bookshelf/LazyBookshelf.vue')
  const note = (text) => text.match(/<div v-if="showCatalogPartial"[^>]*>\{\{ \$strings\.MessageCatalogPartial \}\}<\/div>/)?.[0]
  assert.ok(note(source), 'the note is there')
  const withoutOverlap = (markup) => markup.replace(/; margin-bottom: -24px/, '')
  assert.equal(note(source), withoutOverlap(note(shelf)), "the Library tab's note, without the overlap its fixed-height rows allow")
  // #bookshelf-wrapper of the parent page scrolls; an overflow container in between would carry the sticky note away
  const template = source.match(/<template>([\s\S]*)<\/template>/)[1]
  const scroller = template.indexOf('<div id="bookshelf"')
  assert.ok(template.indexOf('v-if="showCatalogPartial"') < scroller, 'the note is outside #bookshelf')
  assert.match(template.slice(scroller), /^<div id="bookshelf" class="[^"]*overflow-y-auto/, 'the overflow container it stays out of')
  assert.match(source, /import \{[^}]*isReplicaIncomplete[^}]*\} from '@\/utils\/catalogRefresh'/, 'one rule for both')
  assert.match(shelf, /isReplicaIncomplete\(payload\)/)
})

test('no author count over "the catalog isn\'t downloaded yet"', async () => {
  const counts = (page) => page.emitted.filter(([name]) => name === 'bookshelf-total-entities')
  const never = authorsPage({ getAuthors: async (args) => ({ ...pageOf([], args), replica: replica(null) }), state: { serverState: 'offline' } })
  await never.view.init()
  assert.equal(never.view.catalogNotLoaded, true)
  assert.deepEqual(counts(never).at(-1), ['bookshelf-total-entities', null])

  const noLibrary = authorsPage({ getAuthors: async () => assert.fail('no library to read'), state: { libraries: { currentLibraryId: null } } })
  await noLibrary.view.init()
  assert.deepEqual(counts(noLibrary).at(-1), ['bookshelf-total-entities', null])

  const noServer = authorsPage({ getAuthors: async () => assert.fail('no server'), getters: { getHasActiveServer: false } })
  await noServer.view.init()
  assert.deepEqual(counts(noServer).at(-1), ['bookshelf-total-entities', 0], 'as the Library tab without an active server')

  const empty = authorsPage({ getAuthors: async (args) => pageOf([], args) })
  await empty.view.init()
  assert.deepEqual(counts(empty).at(-1), ['bookshelf-total-entities', 0], 'a read library without authors counts 0')
})
