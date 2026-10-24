// The item page on the catalog replica (offline-first 6): $absData reads an item (book, podcast with its episodes) from
// native replica.db, which asks the server for the expanded copy only when the page asks to refresh, and the latest
// episodes of a podcast library, falling back to the server while the episodes were never read. The page opens offline
// without a live user and disables what cannot work: Play and Read of an undownloaded book, Download without the server.
// Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { createDataService } = await importSource('../utils/dataService.js')
const replica = (reconciledAt = 5) => ({ reconciledAt, complete: reconciledAt != null })

const fakeStore = ({ serverState = 'online', activeServerConfigId = 'a' } = {}) => ({
  state: { serverState, activeServerConfigId, user: { serverConnectionConfig: { id: activeServerConfigId } }, libraries: { currentLibraryId: 'lib1' } },
  commit() {},
  async dispatch() {}
})

const fakeHttp = (answer) => {
  const paths = []
  return { paths, get: async (path) => (paths.push(path), answer(path)) }
}

function fakePlugin(overrides = {}) {
  const calls = []
  return {
    calls,
    addListener: () => ({ remove() {} }),
    getServerState: async () => ({}),
    ...Object.fromEntries(Object.entries(overrides).map(([name, fn]) => [name, async (args) => (calls.push([name, args]), fn(args))]))
  }
}

const episode = (id) => ({ id, libraryItemId: 'pod1', title: `Episode ${id}`, libraryId: 'lib1', podcast: { libraryItemId: 'pod1', metadata: { title: 'Pod' }, episodes: [] } })

test('item read goes to native with refresh only when asked', async () => {
  const book = { id: 'li1', libraryId: 'lib1', mediaType: 'book', media: { numChapters: 2, metadata: { title: 'Book' } } }
  const plugin = fakePlugin({ getLibraryItem: () => ({ serverConfigId: 'a', libraryItem: book, detailLevel: 'minified', replica: replica() }) })
  const $absData = createDataService({ plugin, store: fakeStore(), nativeHttp: () => fakeHttp(() => assert.fail('native reads the server for an item')) })

  const answer = await $absData.getLibraryItem({ id: 'li1' })
  assert.deepEqual(plugin.calls.at(-1), ['getLibraryItem', { id: 'li1', refresh: false, force: false }])
  assert.equal(answer.libraryItem.id, 'li1')
  assert.equal(answer.detailLevel, 'minified')

  await $absData.getLibraryItem({ id: 'li1', refresh: true })
  assert.deepEqual(plugin.calls.at(-1), ['getLibraryItem', { id: 'li1', refresh: true, force: false }])
  await $absData.getLibraryItem({ id: 'li1', refresh: true, force: true })
  assert.deepEqual(plugin.calls.at(-1), ['getLibraryItem', { id: 'li1', refresh: true, force: true }])
  assert.equal(plugin.calls.length, 3, 'one native call a read')

  // Native answered for the server that was active before a switch: read once more for the current one
  let answers = [{ serverConfigId: 'old', libraryItem: null, detailLevel: null, replica: replica() }, { serverConfigId: 'a', libraryItem: book, detailLevel: 'expanded', replica: replica() }]
  const switched = fakePlugin({ getLibraryItem: () => answers.shift() })
  const afterSwitch = createDataService({ plugin: switched, store: fakeStore() })
  const fresh = await afterSwitch.getLibraryItem({ id: 'li1' })
  assert.equal(switched.calls.length, 2)
  assert.equal(fresh.serverConfigId, 'a')
  assert.equal(fresh.libraryItem.id, 'li1')

  // Native's "removed" and "not in the replica" answers reach the page as they are, without a server read in JS
  answers = [{ serverConfigId: 'a', libraryItem: null, detailLevel: null, removed: true, replica: replica() }]
  const removed = await createDataService({ plugin: fakePlugin({ getLibraryItem: () => answers.shift() }), store: fakeStore(), nativeHttp: () => fakeHttp(() => assert.fail('no fallback')) }).getLibraryItem({ id: 'li1', refresh: true })
  assert.equal(removed.libraryItem, null)
  assert.equal(removed.removed, true)
})

test('item read in the browser goes to the server', async () => {
  const paths = []
  const $axios = {
    async $get(path) {
      paths.push(path)
      if (path.includes('gone')) throw Object.assign(new Error('Not Found'), { response: { status: 404 } })
      return { id: 'li 1', media: { chapters: [] } }
    }
  }
  const web = createDataService({ plugin: fakePlugin(), store: fakeStore(), platform: 'web', $axios })
  const found = await web.getLibraryItem({ id: 'li 1', refresh: true })
  assert.equal(paths[0], '/api/items/li%201?expanded=1&include=rssfeed')
  assert.equal(found.libraryItem.id, 'li 1')
  assert.equal(found.detailLevel, 'expanded')
  assert.equal(found.serverConfigId, 'a')
  const gone = await web.getLibraryItem({ id: 'gone' })
  assert.equal(gone.libraryItem, null)
})

test('recent episodes fall back to the server while episodes are pending', async () => {
  const pending = () => fakePlugin({ getRecentEpisodes: ({ limit, page }) => ({ serverConfigId: 'a', episodes: [], total: 0, limit, page, replica: replica(null) }) })
  const serverEpisodes = Array.from({ length: 50 }, (_, i) => episode(`e${i}`))
  const http = fakeHttp(() => ({ episodes: serverEpisodes, total: 120, limit: 50, page: 0 }))
  const plugin = pending()
  const online = createDataService({ plugin, store: fakeStore(), nativeHttp: () => http })

  const answer = await online.getRecentEpisodes({ libraryId: 'lib1', limit: 50, page: 0 })
  assert.deepEqual(plugin.calls.at(-1), ['getRecentEpisodes', { libraryId: 'lib1', limit: 50, page: 0 }])
  assert.deepEqual(http.paths, ['/api/libraries/lib1/recent-episodes?limit=50&page=0'])
  assert.equal(answer.fromServer, true)
  assert.equal(answer.total, 120)
  assert.equal(answer.episodes.length, 50)
  assert.equal(answer.limit, 50)
  assert.equal(answer.page, 0)
  assert.equal(answer.serverConfigId, 'a')
  assert.equal(answer.replica.reconciledAt, null, "native's replica state is kept")

  // The server sends no total: unknown, not the length of the page; 50 a page by default
  const noTotalHttp = fakeHttp(() => ({ episodes: serverEpisodes }))
  const noTotal = createDataService({ plugin: pending(), store: fakeStore(), nativeHttp: () => noTotalHttp })
  const unknownTotal = await noTotal.getRecentEpisodes({ libraryId: 'lib1' })
  assert.equal(unknownTotal.total, null)
  assert.equal(unknownTotal.limit, 50)
  assert.deepEqual(noTotalHttp.paths, ['/api/libraries/lib1/recent-episodes?limit=50&page=0'])

  // Offline: native's answer as it is
  const offlinePlugin = pending()
  const offline = createDataService({ plugin: offlinePlugin, store: fakeStore({ serverState: 'offline' }), nativeHttp: () => fakeHttp(() => assert.fail('no server offline')) })
  const fromReplica = await offline.getRecentEpisodes({ libraryId: 'lib1', limit: 50, page: 0 })
  assert.equal(fromReplica.fromServer, undefined)
  assert.deepEqual(fromReplica.episodes, [])

  // Episodes read once: the replica answers online too
  const read = fakePlugin({ getRecentEpisodes: ({ limit, page }) => ({ serverConfigId: 'a', episodes: [episode('e1'), episode('e2')], total: 2, limit, page, replica: replica() }) })
  const reconciled = createDataService({ plugin: read, store: fakeStore(), nativeHttp: () => fakeHttp(() => assert.fail('a read replica does not go to the server')) })
  assert.equal((await reconciled.getRecentEpisodes({ libraryId: 'lib1', limit: 50, page: 1 })).episodes.length, 2)

  // The browser: the server, in the same shape
  const $axios = { $get: async (path) => (assert.equal(path, '/api/libraries/lib1/recent-episodes?limit=50&page=2'), { episodes: [episode('e1')], total: 101 }) }
  const web = await createDataService({ plugin: fakePlugin(), store: fakeStore(), platform: 'web', $axios }).getRecentEpisodes({ libraryId: 'lib1', limit: 50, page: 2 })
  assert.deepEqual({ total: web.total, limit: web.limit, page: web.page, count: web.episodes.length }, { total: 101, limit: 50, page: 2, count: 1 })
})

// The item page (6.4)
const requiresServer = (await importSource('../mixins/requiresServer.js')).default
const { findLocalCopy } = await importSource('../utils/localCopy.js')
const { isCopyOfActiveServer } = await importSource('../utils/progressTarget.js')
const podcastEpisodes = await importSource('../utils/podcastEpisodes.js')
const { createDebouncer } = await importSource('../utils/catalogRefresh.js')
const { leavesOnLibraryChange, copyLeavesOnLibraryChange } = await importSource('../utils/libraryScope.js')

const pageSource = await read('../pages/item/_id/index.vue')
const templateOf = (source) => source.match(/<template>([\s\S]*)<\/template>/)[1]
const flush = async (n = 30) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}
const strings = {
  LabelRequiresServer: 'Requires the server',
  LabelNotAvailableOffline: 'Not available offline',
  MessageChaptersAfterOnline: 'Chapters appear after you open this book online once',
  ToastEpisodeNotAvailable: 'This episode is not available yet',
  HeaderConfirm: 'Confirm'
}

/** A book as the replica keeps it before it was ever opened online: counters, no tracks, chapters or files. */
const minifiedBook = (id = 'li1', extra = {}) => ({
  id,
  ino: `ino-${id}`,
  libraryId: 'l1',
  mediaType: 'book',
  media: { numTracks: 5, numChapters: 3, duration: 600, ebookFormat: 'epub', metadata: { title: 'Book', authors: [{ id: 'a1', name: 'Ann Lee' }, { id: null, name: 'John Smith' }], narrators: [], series: [] }, ...extra }
})
const chapters = (n) => Array.from({ length: n }, (_, i) => ({ id: i, start: i * 60, end: i * 60 + 60, title: `Chapter ${i + 1}` }))
const expandedBook = (id = 'li1') => ({ ...minifiedBook(id), media: { ...minifiedBook(id).media, tracks: [{ index: 1 }], chapters: chapters(3), ebookFile: { ino: 'e1', ebookFormat: 'epub' } }, libraryFiles: [] })
const copyOf = (item, serverConnectionConfigId = 'a', extra = {}) => ({ id: `local_${item.id}`, libraryItemId: item.id, serverConnectionConfigId, isLocal: true, mediaType: item.mediaType, media: { chapters: chapters(4), tracks: [{}], ...extra } })

/** The page over fakes: asyncData, then the component with its mixins as Vue would bind them. */
async function itemPage({ id = 'li1', serverState = 'offline', activeServerConfigId = 'a', user = null, cachedUser = { id: 'u1', permissions: { download: true } }, answers = [], localItems = [], query = {}, local = null } = {}) {
  const timers = []
  const fakeDebouncer = (options) => createDebouncer({ ...options, setTimer: (fn, ms) => (timers.push({ fn, ms }), timers.at(-1)), clearTimer: (t) => timers.includes(t) && timers.splice(timers.indexOf(t), 1) })
  const emitted = []
  const commits = []
  const downloads = []
  const sandbox = {
    console: { ...console, log() {}, error() {}, warn() {} },
    window: { innerWidth: 400, outerHeight: 800, addEventListener() {}, removeEventListener() {} },
    document: { documentElement: { style: { setProperty() {} } } },
    setTimeout: () => 0,
    URLSearchParams,
    Dialog: { confirm: async () => ({ value: true }), alert() {} },
    AbsFileSystem: { addListener: async () => ({ remove() {} }) },
    AbsDownloader: { downloadLibraryItem: async (payload) => (downloads.push(payload), {}) },
    getAverageColorFromCoverUrl: async () => null,
    ...podcastEpisodes,
    coverPresentation: { coverColorPresentation: () => ({}) },
    cellularPermissionHelpers: { methods: { checkCellularPermission: async () => true } },
    downloadState: () => null,
    DownloadState: {},
    leavesOnLibraryChange,
    copyLeavesOnLibraryChange,
    appStorageFolderFor: async () => ({ id: 'app', name: 'App' }),
    safFoldersFor: () => [],
    requiresServer,
    findLocalCopy,
    isCopyOfActiveServer,
    createDebouncer: fakeDebouncer
  }
  vm.runInNewContext(pageSource.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component ='), sandbox)
  const def = sandbox.component

  const reads = []
  let changed = null
  const absData = {
    getLibraryItem: async (args) => {
      reads.push(args)
      const answer = answers.length > 1 ? answers.shift() : answers[0]
      if (answer instanceof Error) throw answer
      return typeof answer === 'function' ? answer(args) : structuredClone(answer)
    },
    onDataChanged: (cb) => ((changed = cb), () => (changed = null))
  }
  const store = {
    state: {
      serverState,
      activeServerConfigId,
      socketConnected: serverState === 'online',
      user: { user, cachedUser, serverConnectionConfig: user ? { id: activeServerConfigId, address: 'https://abs' } : null, settings: {} },
      libraries: { currentLibraryId: 'l1' },
      lastItemScrollData: {},
      playerIsPlaying: false,
      playerIsStartingPlayback: false,
      isCasting: false
    },
    getters: {
      getIsServerOnline: serverState === 'online',
      getHasActiveServer: activeServerConfigId != null,
      'user/getUserCanDownload': !!(user || cachedUser)?.permissions?.download,
      'user/getServerAddress': user ? 'https://abs' : null,
      'user/getServerConnectionConfigId': user ? activeServerConfigId : null,
      'user/getUserMediaProgress': () => null,
      'user/getIsAdminOrUp': false,
      'globals/getLocalMediaProgressById': () => null,
      'globals/getDownloadItem': () => null,
      'libraries/getBookCoverAspectRatio': 1.6,
      getIsMediaStreaming: () => false,
      getIsCurrentSessionLocal: false
    },
    commit: (name, payload) => commits.push([name, payload]),
    dispatch: async () => {}
  }
  const db = { getLocalFolders: async () => [], getLocalLibraryItems: async () => localItems, getLocalLibraryItem: async (lid) => local || localItems.find((l) => l.id === lid) || null }

  let redirected = null
  const data = await def.asyncData({ store, params: { id }, query, redirect: (to) => (redirected = to), app: { $absData: absData, $db: db } })

  const replaced = []
  const toasts = []
  const ctx = {
    $store: store,
    $absData: absData,
    $db: db,
    $route: { query },
    $router: { replace: (to) => replaced.push(to), push: (to) => replaced.push(to) },
    $toast: { error: (m) => toasts.push(m), info: (m) => toasts.push(m), success() {} },
    $strings: strings,
    $getString: (key) => key,
    $secondsToTimestamp: (s) => `${s}`,
    $eventBus: { $on() {}, $off() {}, $emit: (name, payload) => emitted.push([name, payload]) },
    $hapticsImpact: async () => {},
    $localStore: { setLastLibraryId() {} },
    $theme: { theme: {} },
    $platform: 'android',
    $refs: {}
  }
  const view = { ...def.data.call(ctx), ...(data || {}), ...ctx }
  for (const part of [...def.mixins, def]) {
    for (const [name, d] of Object.entries(part.computed || {})) Object.defineProperty(view, name, { get: (d.get || d).bind(view), set: d.set?.bind(view), configurable: true })
    for (const [name, fn] of Object.entries(part.methods || {})) view[name] = fn.bind(view)
  }
  const mount = async () => {
    if (redirected) return
    await def.mounted.call(view)
    await flush()
  }
  const runTimers = async () => {
    const due = timers.splice(0)
    due.forEach((t) => t.fn())
    await flush()
    return due.map((t) => t.ms)
  }
  return { view, def, data, reads, emitted, commits, downloads, replaced, toasts, mount, runTimers, change: (ids) => changed?.({ scope: 'catalog', ids }), get redirected() { return redirected }, get subscribed() { return !!changed } }
}

const replicaAnswer = (libraryItem, detailLevel = 'minified') => ({ serverConfigId: 'a', libraryItem, detailLevel, replica: { reconciledAt: 5 } })

test('a never-opened book opens offline with metadata and no chapters', async () => {
  const page = await itemPage({ user: null, answers: [replicaAnswer(minifiedBook())] })
  await page.mount()
  assert.equal(page.redirected, null, 'no redirect to Home without a live user')
  assert.deepEqual(page.replaced, [])
  assert.equal(page.view.libraryItem.id, 'li1')
  assert.equal(page.view.showChaptersHint, true)
  assert.equal(page.view.showPlay, true)
  assert.equal(page.view.playDisabled, true)
  assert.deepEqual(page.reads.map((r) => !!r.refresh), [false, true], 'the replica first, then a refresh')
})

test('a never-opened book offline shows Play disabled, not hidden', async () => {
  const page = await itemPage({ answers: [replicaAnswer(minifiedBook())] })
  await page.mount()
  const v = page.view
  assert.equal(v.numTracks, 5)
  assert.ok(v.showPlay && v.showRead, 'by the counters of the minified item')
  assert.equal(v.notAvailableOffline, true)
  assert.equal(v.ebookFormat, 'epub')
  assert.equal(v.showDownload, true)
  assert.equal(v.numChapters, 3)
  assert.equal(v.showChaptersHint, true)
  const template = templateOf(pageSource)
  assert.match(template, /<ui-btn v-if="showPlay"[^>]*:disabled="playDisabled && !playerIsPlaying"/)
  assert.match(template, /<ui-btn v-if="showRead"[^>]*:disabled="readDisabled"/)
  assert.equal(v.readDisabled, true)
  assert.match(template, /<div v-if="showPlay \|\| showRead"[\s\S]*?<\/div>\n\s*<ui-btn v-else-if="isMissing"/, 'the labels do not break the Missing button chain')
  assert.match(template, /v-if="\(showPlay && playDisabled\) \|\| \(showRead && notAvailableOffline\)"[^>]*>\{\{ notAvailableOfflineLabel\(\) \}\}/)
  assert.match(template, /v-(?:else-)?if="showChaptersHint"[^>]*>\{\{ \$strings\.MessageChaptersAfterOnline \}\}/)
  assert.match(template, /<tables-chapters-table v-if="chapters\.length"/)
  assert.match(template, /<tables-tracks-table v-if="tracks\.length"/)
})

test("a downloaded never-opened book offline shows the copy's chapters and plays", async () => {
  const book = minifiedBook()
  const page = await itemPage({ answers: [replicaAnswer(book)], localItems: [copyOf(book)] })
  await page.mount()
  const v = page.view
  assert.equal(v.chapters.length, 4)
  assert.equal(v.chaptersLibraryItem.media.chapters.length, 4)
  assert.equal(v.showChaptersHint, false)
  assert.equal(v.playDisabled, false)
  await v.play()
  assert.equal(page.emitted[0][0], 'play-item')
  assert.equal(page.emitted[0][1].libraryItemId, 'local_li1')
  assert.equal(page.emitted[0][1].serverLibraryItemId, 'li1')
  assert.equal(page.emitted[0][1].startTime, null, 'Play leaves the start position to native')
})

test('chapter tap and ebook read do nothing for an undownloaded book offline', async () => {
  const book = expandedBook()
  const undownloaded = await itemPage({ answers: [replicaAnswer(book, 'expanded')] })
  await undownloaded.mount()
  await undownloaded.view.play(120)
  undownloaded.view.readBook()
  assert.deepEqual(undownloaded.emitted, [])
  assert.deepEqual(undownloaded.commits.filter(([name]) => name === 'showReader' || name === 'setPlayerIsStartingPlayback'), [])

  const downloaded = await itemPage({ answers: [replicaAnswer(book, 'expanded')], localItems: [copyOf(book, 'a', { ebookFile: { ino: 'e1' } })] })
  await downloaded.mount()
  await downloaded.view.play(120)
  downloaded.view.readBook()
  assert.equal(downloaded.emitted[0][1].startTime, 120, 'a chapter passes its own start')
  assert.equal(downloaded.commits.filter(([name]) => name === 'showReader').length, 1)

  // The e-book files table opens the server's file: only while the server is online, also for a downloaded book
  const tableSource = await read('../components/tables/ebook/EbookFilesTable.vue')
  const sandbox = { requiresServer }
  vm.runInNewContext(tableSource.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component ='), sandbox)
  for (const isServerOnline of [false, true]) {
    const shown = []
    sandbox.component.methods.readEbook.call({ isServerOnline, libraryItem: book, $store: { commit: (name) => shown.push(name) } }, 'ino1')
    assert.deepEqual(shown, isServerOnline ? ['showReader'] : [])
  }
  assert.match(tableSource, /<tables-ebook-files-table-row [^>]*:read-disabled="!isServerOnline"/)
  assert.match(await read('../components/tables/ebook/EbookFilesTableRow.vue'), /<ui-icon-btn icon="auto_stories"[^>]*:disabled="readDisabled"/)
})

test('an opened-online book shows its chapters offline', async () => {
  const page = await itemPage({ answers: [replicaAnswer(expandedBook(), 'expanded')] })
  await page.mount()
  assert.equal(page.view.chapters.length, 3)
  assert.equal(page.view.showChaptersHint, false)
})

test('a failed refresh keeps the replica page', async () => {
  const book = minifiedBook()
  for (const second of [replicaAnswer(book), new Error('timeout')]) {
    const page = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(book), second] })
    await page.mount()
    assert.deepEqual(page.toasts, [])
    assert.deepEqual(page.replaced, [])
    assert.equal(page.view.libraryItem.id, 'li1')
  }
})

test('a refresh brings the expanded copy and keeps the downloaded copy', async () => {
  const book = minifiedBook()
  const page = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(book), { ...replicaAnswer(expandedBook(), 'expanded'), fromServer: true }], localItems: [copyOf(book)] })
  await page.mount()
  assert.equal(page.view.detailLevel, 'expanded')
  assert.equal(page.view.libraryItem.media.chapters.length, 3)
  assert.equal(page.view.libraryItem.localLibraryItem.id, 'local_li1')
})

test('a book gone from the server opens its downloaded copy', async () => {
  const book = minifiedBook()
  const gone = { serverConfigId: 'a', libraryItem: null, removed: true, replica: { reconciledAt: 5 } }
  const withQuery = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(book), gone], query: { localLibraryItemId: 'local_li1' } })
  await withQuery.mount()
  assert.deepEqual(withQuery.replaced, ['/item/local_li1?noredirect=1'])
  assert.deepEqual(withQuery.toasts, [])

  const joined = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(book), gone], localItems: [copyOf(book)] })
  await joined.mount()
  assert.deepEqual(joined.replaced, ['/item/local_li1?noredirect=1'])

  const none = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(book), gone] })
  await none.mount()
  assert.deepEqual(none.replaced, ['/bookshelf'])
  assert.deepEqual(none.toasts, ['Failed to get library item from server'])

  // Not in the replica at all: offline the copy the link names, or "not available offline" and back
  const missing = await itemPage({ answers: [replicaAnswer(null, null)], query: { localLibraryItemId: 'local_li1' } })
  assert.equal(missing.redirected, '/item/local_li1?noredirect=1')
  // Online the server is asked first: a new book the replica does not have yet opens its server page
  const fresh = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(null, null), { ...replicaAnswer(expandedBook(), 'expanded'), fromServer: true }], query: { localLibraryItemId: 'local_li1' } })
  assert.equal(fresh.redirected, null)
  await fresh.mount()
  assert.deepEqual(fresh.replaced, [])
  assert.equal(fresh.view.libraryItem.id, 'li1')
  const offline = await itemPage({ answers: [replicaAnswer(null, null)] })
  await offline.mount()
  assert.deepEqual(offline.toasts, ['Not available offline'])
  assert.deepEqual(offline.replaced, ['/bookshelf'])
})

test('an open page whose item was removed leaves it', async () => {
  const book = minifiedBook()
  const page = await itemPage({ answers: [replicaAnswer(book), replicaAnswer(book), replicaAnswer(null, null)] })
  await page.mount()
  assert.equal(page.subscribed, true)
  page.change(['items:l2'])
  assert.deepEqual(await page.runTimers(), [], 'another library')
  page.change(['items:l1'])
  assert.deepEqual(await page.runTimers(), [1000])
  assert.deepEqual({ ...page.reads.at(-1) }, { id: 'li1' }, 'a re-read, not a refresh')
  assert.deepEqual(page.toasts, ['Not available offline'])
  assert.deepEqual(page.replaced, ['/bookshelf'])
  page.def.beforeDestroy.call(page.view)
  assert.equal(page.subscribed, false)
})

test('a local item page makes no server read', async () => {
  const copy = { ...copyOf(minifiedBook()), media: { tracks: [{}], chapters: [] } }
  const page = await itemPage({ id: 'local_li1', local: copy, query: { noredirect: '1' }, answers: [replicaAnswer(null, null)] })
  await page.mount()
  assert.deepEqual(page.reads, [])
  assert.deepEqual(page.replaced, [])
  assert.equal(page.view.libraryItem.id, 'local_li1')
  assert.equal(page.subscribed, false)
})

test('a copy of another account is not joined', async () => {
  const book = minifiedBook()
  const page = await itemPage({ answers: [replicaAnswer(book)], localItems: [copyOf(book, 'b')] })
  await page.mount()
  assert.equal(page.view.libraryItem.localLibraryItem, null)
  assert.equal(page.view.playDisabled, true)
  await page.view.play()
  assert.deepEqual(page.emitted, [])
})

test('an offline downloaded book keeps its server id', async () => {
  const copy = copyOf(minifiedBook())
  const page = await itemPage({ id: 'local_li1', local: copy, query: { noredirect: '1' } })
  assert.equal(page.view.serverLibraryItemId, 'li1')
  const other = await itemPage({ id: 'local_li1', local: { ...copy, serverConnectionConfigId: 'b' }, query: { noredirect: '1' } })
  assert.equal(other.view.serverLibraryItemId, null, 'a copy of another server')
})

test('download is visible and disabled offline', async () => {
  const page = await itemPage({ user: null, answers: [replicaAnswer(minifiedBook())] })
  await page.mount()
  assert.equal(page.view.showDownload, true, 'by the cached user')
  await page.view.downloadClick()
  assert.deepEqual(page.downloads, [])
  assert.match(templateOf(pageSource), /<ui-btn v-if="showDownload"[^>]*:disabled="!isServerOnline && !downloadItem"/)
  // Both reasons show at once: Play and Read not available offline, Download requires the server
  assert.match(templateOf(pageSource), /<p v-if="showDownload && isServerAway && !downloadItem"[^>]*>\{\{ requiresServerLabel\(\) \}\}/)
  assert.doesNotMatch(templateOf(pageSource), /v-else-if="showDownload/)

  const online = await itemPage({ serverState: 'online', user: { id: 'u1', permissions: { download: true } }, answers: [replicaAnswer(minifiedBook())] })
  await online.mount()
  await online.view.downloadClick()
  await flush()
  assert.equal(online.downloads.length, 1)
})

test('an episode the podcast does not have says so on the podcast page', async () => {
  const podcast = { id: 'pod1', libraryId: 'l1', mediaType: 'podcast', media: { metadata: { title: 'P', author: 'A' }, episodes: [] } }
  const page = await itemPage({ id: 'pod1', answers: [replicaAnswer(podcast, 'expanded')], query: { error: 'episode' } })
  await page.mount()
  assert.deepEqual(page.toasts, ['This episode is not available yet'])
})

test('item page reads the item through $absData', async () => {
  assert.doesNotMatch(pageSource, /\$nativeHttp\.get\(`\/api\/items\//)
  assert.doesNotMatch(pageSource, /\$socket\.\$on\('item_updated'/)
  assert.doesNotMatch(pageSource, /\$socket\.\$on\('rss_feed_/)
  assert.doesNotMatch(pageSource, /getLocalLibraryItemByLId/)
  // An author without an id (only a name in the replica) is text, not a link
  assert.match(templateOf(pageSource), /<nuxt-link v-if="author\.id"[\s\S]*?<span v-else[^>]*>\{\{ author\.name \}\}<\/span\n/)
})

test('a downloaded copy of another account stays on its own page', async () => {
  const copy = { ...copyOf(minifiedBook()), serverAddress: 'https://abs' }
  const page = (serverConnectionConfigId) => itemPage({ id: 'local_li1', local: { ...copy, serverConnectionConfigId }, serverState: 'online', user: { id: 'u1' } })
  assert.equal((await page('a')).redirected, '/item/li1?localLibraryItemId=local_li1', 'a copy of the active server opens the server page')
  assert.equal((await page('b')).redirected, null, 'same address, another account: the copy is not the active server item')
})

test('Read on a minified book online waits for the full item', async () => {
  const pending = { resolve: null }
  const page = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(minifiedBook()), () => new Promise((resolve) => (pending.resolve = resolve))] })
  page.def.mounted.call(page.view)
  await flush()
  assert.equal(page.view.showRead, true, 'shown by the e-book format')
  assert.equal(page.view.readDisabled, true, 'no file to open yet')
  page.view.readBook()
  assert.deepEqual(page.commits.filter(([name]) => name === 'showReader'), [])
  pending.resolve({ ...replicaAnswer(expandedBook(), 'expanded'), fromServer: true })
  await flush()
  assert.equal(page.view.readDisabled, false)
  page.view.readBook()
  assert.equal(page.commits.filter(([name]) => name === 'showReader').length, 1)
})

test('a page drawn from the replica subscribes before the refresh answers', async () => {
  const pending = { resolve: null }
  const page = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(minifiedBook()), () => new Promise((resolve) => (pending.resolve = resolve))] })
  page.def.mounted.call(page.view)
  await flush()
  assert.equal(page.subscribed, true, 'init() does not wait for the server')
  pending.resolve({ ...replicaAnswer(expandedBook(), 'expanded'), fromServer: true })
  await flush()
  assert.equal(page.view.detailLevel, 'expanded')

  // Without an item from the replica the page waits for the refresh before anything else
  const missing = { resolve: null }
  const empty = await itemPage({ serverState: 'online', user: { id: 'u1' }, answers: [replicaAnswer(null, null), () => new Promise((resolve) => (missing.resolve = resolve))] })
  empty.def.mounted.call(empty.view)
  await flush()
  assert.equal(empty.subscribed, false)
  missing.resolve({ ...replicaAnswer(expandedBook(), 'expanded'), fromServer: true })
  await flush()
  assert.equal(empty.subscribed, true)
})

test('a notice that names other items does not re-read the page', async () => {
  const page = await itemPage({ answers: [replicaAnswer(minifiedBook())] })
  await page.mount()
  const before = page.reads.length
  page.view.dataChanged({ scope: 'catalog', ids: ['items:l1'], itemIds: ['li9'] })
  assert.deepEqual(await page.runTimers(), [])
  page.view.dataChanged({ scope: 'catalog', ids: ['items:l1'], itemIds: ['li9', 'li1'] })
  assert.deepEqual(await page.runTimers(), [1000])
  page.view.dataChanged({ scope: 'catalog', ids: ['items:l1'] })
  assert.deepEqual(await page.runTimers(), [1000], 'without item ids: as before')
  assert.equal(page.reads.length, before + 2)
})

test('a download that lands while the local copies are read is not undone by that read', async () => {
  const book = minifiedBook()
  const page = await itemPage({ answers: [replicaAnswer(book)] })
  await page.mount()
  page.view.$set = (target, key, value) => (target[key] = value)
  let answer
  page.view.$db = { ...page.view.$db, getLocalLibraryItems: () => new Promise((resolve) => (answer = resolve)) }
  const reload = page.view.reloadLocalLibraryItem()
  const copy = copyOf(book)
  page.view.newLocalLibraryItem(copy)
  answer([])
  await reload
  assert.equal(page.view.libraryItem.localLibraryItem?.id, copy.id)
})
