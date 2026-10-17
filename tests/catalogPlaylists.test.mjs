// The Playlists tab, the playlist page and playlist edits on the catalog replica (offline-first 4e): $absData reads the
// user's playlists from native replica.db (a list page carries 4 cover items and the ids of every item per playlist, the
// items come 100 a call, episodes with their JSON), falls back to the server while they were never read, the page
// follows native's catalog notices, and edits (they write to the server) are disabled and labeled offline.
// Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { createDataService, catalogTopics, CATALOG_EVENTS, playlistListEntry } = await importSource('../utils/dataService.js')
const { shouldRefreshShelf, createDebouncer, isReplicaIncomplete } = await importSource('../utils/catalogRefresh.js')
const { findLocalCopy: localCopyOf } = await importSource('../utils/localCopy.js')
const { trackCount, fetchPlaylistItems } = await importSource('../utils/catalogItem.js')
const { queueItemPayload, resolveItemProgress } = await importSource('../utils/playbackQueue.js')
const requiresServer = (await importSource('../mixins/requiresServer.js')).default
const { leavesOnLibraryChange, copyLeavesOnLibraryChange } = await importSource('../utils/libraryScope.js')
const plain = (value) => JSON.parse(JSON.stringify(value))
const replica = (reconciledAt = 5) => ({ reconciledAt, complete: reconciledAt != null })
const flush = async (n = 30) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}
const strings = { LabelRequiresServer: 'Requires the server', ToastPlaylistLoadFailed: 'Could not open the playlist', ToastPlaylistCreateFailed: 'Failed to create playlist' }

/** A minified book as the replica keeps it (no `media.tracks`). */
const book = (id, numTracks = 3) => ({ id, ino: `ino-${id}`, libraryId: 'l1', mediaType: 'book', media: { numTracks, duration: 100, metadata: { title: `Book ${id}`, authorName: 'Ann Lee' } } })
const podcast = { id: 'pod1', ino: 'ino-pod1', libraryId: 'l1', mediaType: 'podcast', media: { numEpisodes: 2, metadata: { title: 'Pod', author: 'Pod Author' } } }
/** A playlist item as native gives it: an episode with its JSON (`audioFile`), a book with `episode` null. */
const entry = (libraryItem, episodeId = null) => ({
  libraryItemId: libraryItem.id,
  episodeId,
  libraryItem,
  episode: episodeId ? { id: episodeId, libraryItemId: libraryItem.id, title: `Episode ${episodeId}`, duration: 61.5, audioFile: { index: 1, duration: 61.5 } } : null
})
const itemsOf = (count) => Array.from({ length: count }, (_, i) => entry(book(`li${i}`)))
const keyOf = (item) => ({ libraryItemId: item.libraryItemId, episodeId: item.episodeId })

/** A fake native AbsData with one playlist `p1` of library `l1` holding `items`. */
function fakePlugin({ items = itemsOf(3), reconciledAt = 5, serverConfigId = 'a', overrides = {} } = {}) {
  const calls = []
  return {
    calls,
    addListener: () => ({ remove() {} }),
    getServerState: async () => ({}),
    async getPlaylists(args) {
      calls.push(['getPlaylists', args])
      const results = [{ id: 'p1', libraryId: 'l1', name: 'Мой', numItems: items.length, items: items.slice(0, 4).map((i) => ({ ...i, episode: null })), itemKeys: items.map(keyOf) }]
      return { serverConfigId, results, total: 1, limit: args.limit, page: args.page, replica: replica(reconciledAt) }
    },
    async getPlaylist(args) {
      calls.push(['getPlaylist', args])
      return { serverConfigId, playlist: { id: args.id, libraryId: 'l1', name: 'Мой', numItems: items.length }, replica: replica(reconciledAt) }
    },
    async getPlaylistItems(args) {
      calls.push(['getPlaylistItems', args])
      const { limit, page } = args
      return { serverConfigId, results: items.slice(page * limit, page * limit + limit), total: items.length, limit, page, found: true, replica: replica(reconciledAt) }
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

/** The server's `/api/playlists/:id` (`toOldJSONExpanded`): books expanded (`media.tracks`), episodes with their podcast. */
const serverPlaylist = (count) => ({
  id: 'p1',
  name: 'Мой',
  libraryId: 'l1',
  userId: 'u1',
  description: null,
  lastUpdate: 2,
  createdAt: 1,
  items: Array.from({ length: count }, (_, i) => ({ libraryItemId: `li${i}`, libraryItem: { id: `li${i}`, mediaType: 'book', media: { tracks: [{}, {}], metadata: { title: `Book ${i}`, authors: [{ name: 'Ann Lee' }] } } } }))
})

/** A component's script run on fakes; `deps` are the names its imports bind. */
function componentDef(source, deps = {}) {
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { console: { ...console, log() {}, error() {}, warn() {} }, ...deps }
  vm.runInNewContext(script, sandbox)
  return sandbox.component
}

/** Data, mixins, computed getters and methods on one object, as Vue would bind them. */
function bind(def, ctx) {
  const view = { ...(def.data ? def.data.call(ctx) : {}), ...ctx }
  for (const part of [...(def.mixins || []), def]) {
    for (const [name, d] of Object.entries(part.computed || {})) {
      if (Object.prototype.hasOwnProperty.call(ctx, name)) continue
      Object.defineProperty(view, name, { get: (d.get || d).bind(view), set: d.set?.bind(view), configurable: true })
    }
    for (const [name, fn] of Object.entries(part.methods || {})) view[name] = fn.bind(view)
  }
  return view
}

const pageSource = await read('../pages/playlist/_id.vue')

/** The playlist page over fakes. */
function playlistPage({ absData, localItems = {}, activeServerConfigId = 'a', user = null, progress = {} } = {}) {
  const timers = []
  const fakeDebouncer = (options) => createDebouncer({ ...options, setTimer: (fn, ms) => (timers.push({ fn, ms }), timers.at(-1)), clearTimer: (t) => timers.includes(t) && timers.splice(timers.indexOf(t), 1) })
  const component = componentDef(pageSource, { trackCount, fetchPlaylistItems, createDebouncer: fakeDebouncer, downloadMissingItems() {}, queueItemPayload, resolveItemProgress, leavesOnLibraryChange, requiresServer })
  const store = {
    state: { activeServerConfigId, user: { user }, playerIsPlaying: false, playerIsStartingPlayback: false, libraries: { currentLibraryId: 'l1' } },
    getters: {
      getIsMediaStreaming: () => false,
      getAskBeforeStreamingIncomplete: false,
      'user/getServerConnectionConfigId': null,
      'user/getUserMediaProgress': (id, episodeId) => progress[`${id}|${episodeId || ''}`] || null,
      'globals/getLocalMediaProgressById': () => null
    },
    commit() {}
  }
  const dataListeners = new Set()
  const replaced = []
  const emitted = []
  const localCalls = []
  const app = {
    $absData: { ...absData, onDataChanged: (cb) => (dataListeners.add(cb), () => dataListeners.delete(cb)) },
    $db: { getLocalLibraryItems: async (mediaType) => (localCalls.push(mediaType), localItems[mediaType] || []) },
    $nativeHttp: { get: async () => assert.fail('the playlist page reads through $absData') },
    $socket: { $on: () => assert.fail('the playlist page follows $absData, not the socket'), $off() {} }
  }
  const asyncData = async (id = 'p1') => {
    const out = { redirected: null }
    const result = await component.asyncData({ params: { id }, app, store, redirect: (path) => (out.redirected = path), route: { path: `/playlist/${id}` } })
    return { ...out, result }
  }
  const mount = async (id = 'p1') => {
    const { result } = await asyncData(id)
    const view = bind(component, {
      ...result,
      ...app,
      $store: store,
      $hapticsImpact: async () => {},
      $router: { replace: (to) => replaced.push(to) },
      $eventBus: { $on() {}, $off() {}, $emit: (name, payload) => emitted.push([name, payload]) }
    })
    component.mounted.call(view)
    return view
  }
  return { component, store, asyncData, mount, timers, replaced, emitted, localCalls, dataListeners, send: (change) => dataListeners.forEach((cb) => cb(change)), destroy: (view) => component.beforeDestroy.call(view) }
}

const episodeCopy = (id, serverConnectionConfigId) => ({ id, libraryItemId: 'pod1', serverConnectionConfigId, mediaType: 'podcast', media: { episodes: [{ id: `${id}_ep1`, serverEpisodeId: 'ep1' }] } })

test('playlist page opens without a live user and plays episodes from the replica', async () => {
  const items = [entry(podcast, 'ep1'), entry(book('li1')), entry(podcast, 'ep2')]
  const absData = createDataService({ plugin: fakePlugin({ items }), store: fakeStore({ serverState: 'offline' }) })
  const page = playlistPage({
    absData,
    user: null,
    localItems: {
      podcast: [episodeCopy('local_pod_b', 'b'), episodeCopy('local_pod_a', 'a')],
      book: [{ id: 'local_li1_b', libraryItemId: 'li1', serverConnectionConfigId: 'b' }]
    }
  })
  const { redirected, result } = await page.asyncData()
  assert.equal(redirected, null, 'no /connect redirect')
  assert.equal(result.playlist.name, 'Мой')
  assert.deepEqual(result.playlist.items.map((i) => [i.libraryItemId, i.episodeId]), [['pod1', 'ep1'], ['li1', null], ['pod1', 'ep2']])
  assert.deepEqual([...page.localCalls].sort(), ['book', 'podcast'], 'the copies of each kind of item')
  assert.equal(result.playlist.items[0].localLibraryItem.id, 'local_pod_a', "the active server's copy, not another account's")
  assert.equal(result.playlist.items[0].localEpisode.id, 'local_pod_a_ep1')
  assert.equal(result.playlist.items[1].localLibraryItem, undefined, "another account's copy with the same id is not this book's")
  assert.equal(result.playlist.items[2].localLibraryItem, undefined, 'an episode not downloaded')

  const view = await page.mount()
  assert.equal(view.playableItems.length, 3, 'episodes by their audioFile, books by numTracks')
  assert.ok(view.showPlayButton)

  const gone = playlistPage({ absData: { getPlaylist: async () => ({ serverConfigId: 'a', playlist: null, replica: replica() }) } })
  assert.equal((await gone.asyncData()).redirected, '/bookshelf/playlists')
  const failed = playlistPage({ absData: { getPlaylist: async () => Promise.reject(new Error('bridge')) } })
  assert.equal((await failed.asyncData()).redirected, '/bookshelf/playlists?error=playlist')

  const tab = componentDef(await read('../pages/bookshelf/playlists.vue'))
  const toasts = []
  const replaced = []
  const tabView = { $route: { path: '/bookshelf/playlists', query: {} }, $toast: { error: (m) => toasts.push(m) }, $router: { replace: (to) => replaced.push(to) }, $strings: strings }
  const watcher = tab.watch['$route.query.error']
  assert.equal(watcher.immediate, true)
  watcher.handler.call(tabView, undefined)
  watcher.handler.call(tabView, 'playlist')
  assert.deepEqual(toasts, ['Could not open the playlist'])
  assert.deepEqual(plain(replaced), [{ path: '/bookshelf/playlists', query: {} }])
  assert.equal(JSON.parse(await read('../strings/en-us.json')).ToastPlaylistLoadFailed, 'Could not open the playlist')
})

test('playlist play offline uses downloaded copies', async () => {
  const items = [entry(book('li0')), entry(podcast, 'ep1'), entry(book('li2', 4)), entry(podcast, 'ep2'), entry(book('li4', 0))]
  const localItems = {
    book: [{ id: 'local_li0', libraryItemId: 'li0', serverConnectionConfigId: 'a', mediaType: 'book' }],
    podcast: [episodeCopy('local_pod', 'a')]
  }
  const playFrom = async (finished) => {
    const progress = Object.fromEntries(finished.map((key) => [key, { isFinished: true }]))
    const page = playlistPage({ absData: createDataService({ plugin: fakePlugin({ items }), store: fakeStore({ serverState: 'offline' }) }), localItems, progress })
    const view = await page.mount()
    await view.playClick()
    const [name, payload] = page.emitted.at(-1)
    assert.equal(name, 'play-item')
    return payload
  }

  const first = await playFrom([])
  assert.deepEqual(plain(first.queueSource.items.map((i) => [i.libraryItemId, i.episodeId])), [['li0', null], ['pod1', 'ep1'], ['li2', null], ['pod1', 'ep2']], 'every playable item in order; a book without tracks is not')
  assert.equal(first.queueSource.sourceType, 'playlist')
  assert.equal(first.libraryItemId, 'local_li0', 'the downloaded book plays its copy')
  assert.equal(first.serverLibraryItemId, 'li0')
  assert.equal('startTime' in first, false, 'native picks the position')

  const episode = await playFrom(['li0|'])
  assert.deepEqual([episode.libraryItemId, episode.episodeId, episode.serverEpisodeId], ['local_pod', 'local_pod_ep1', 'ep1'], 'the downloaded episode plays its copy')
  assert.equal(episode.expectedTracks, undefined)

  const stream = await playFrom(['li0|', 'pod1|ep1'])
  assert.deepEqual([stream.libraryItemId, stream.episodeId, stream.serverLibraryItemId], ['li2', null, 'li2'], 'not downloaded: the server item, native waits for the server')
  assert.equal(stream.expectedTracks, 4)
  assert.equal('startTime' in stream, false)
})

test('playlist row shows the author of a minified book', async () => {
  const def = componentDef(await read('../components/tables/playlist/ItemTableRow.vue'), { trackCount })
  const row = (item) => bind(def, { item, $store: { getters: { getIsMediaStreaming: () => false, 'user/getUserMediaProgress': () => null }, state: {} } })
  assert.equal(row(entry(book('li1'))).authorName, 'Ann Lee')
  assert.equal(row({ libraryItem: { id: 'li2', media: { metadata: { authors: [{ name: 'Ann' }, { name: 'Bob' }], authorName: 'ignored' } } } }).authorName, 'Ann, Bob', 'an expanded book as before')
  assert.equal(row(entry(podcast, 'ep1')).authorName, 'Pod Author')
  assert.equal(row(entry(book('li3'))).showPlayBtn, true, 'a minified book counts its tracks')
  assert.equal(row(entry(book('li4', 0))).showPlayBtn, false)
  assert.equal(row({ ...entry(book('li5')), localLibraryItem: { id: 'local_li5' } }).itemUrl, '/item/li5?localLibraryItemId=local_li5', 'a downloaded book opens its copy')
  assert.equal(row(entry(podcast, 'ep1')).itemUrl, '/item/pod1/ep1')
  const table = componentDef(await read('../components/tables/playlist/PlaylistItemsTable.vue'), { trackCount })
  const view = bind(table, { items: [entry(book('li1')), entry(podcast, 'ep1'), entry(book('li2', 0))], $elapsedPrettyExtended: (s) => `${s}s` })
  assert.equal(view.playableItems.length, 2)
  assert.equal(view.totalDuration, 261.5)
})

const modalSource = await read('../components/modals/playlists/AddCreateModal.vue')

/** AddCreateModal over fakes; `serverState` decides whether writes may go. */
function addCreateModal({ getPlaylists, serverState = 'online', selected = [{ libraryItem: { id: 'li9' }, episode: null }], answer = () => ({}) } = {}) {
  const def = componentDef(modalSource, { requiresServer, playlistListEntry })
  const posts = []
  const commits = []
  const reads = []
  const toasts = []
  const dataListeners = new Set()
  const state = { serverState, libraries: { currentLibraryId: 'l1', numUserPlaylists: 0 }, globals: { showPlaylistsAddCreateModal: true, selectedPlaylistItems: selected } }
  const view = bind(def, {
    $store: { state, getters: { get getIsServerOnline() { return state.serverState === 'online' } }, commit: (...c) => commits.push(c) },
    $strings: strings,
    $hapticsImpact: async () => {},
    $toast: { error: (m) => toasts.push(m) },
    $absData: { getPlaylists: async (args) => (reads.push(args), getPlaylists(args)), onDataChanged: (cb) => (dataListeners.add(cb), () => dataListeners.delete(cb)) },
    $nativeHttp: { post: async (path, body) => (posts.push([path, body]), answer(path, body)), delete: async (path) => (posts.push(['DELETE', path]), {}), get: async () => assert.fail('the modal reads through $absData') },
    $socket: { $on: () => assert.fail('the modal follows $absData, not the socket'), $off() {} }
  })
  return { def, view, posts, reads, toasts, state, commits, dataListeners, send: (change) => dataListeners.forEach((cb) => cb(change)) }
}

/** 50 playlists of 12 items; p0..p49, item li9 is the 10th of every even one. */
const playlistsAnswer = ({ limit, page }) => {
  const all = Array.from({ length: 50 }, (_, n) => {
    const items = Array.from({ length: 12 }, (_, i) => entry(book(n % 2 === 0 ? `li${i}` : `other${i}`)))
    return { id: `p${n}`, libraryId: 'l1', name: `Playlist ${n}`, numItems: 12, items: items.slice(0, 4).map((i) => ({ ...i, episode: null })), itemKeys: items.map(keyOf) }
  })
  return { serverConfigId: 'a', results: all.slice(page * limit, page * limit + limit), total: all.length, limit, page, replica: replica() }
}

test('add-to-playlist modal loads every page', async () => {
  const modal = addCreateModal({ getPlaylists: async (args) => playlistsAnswer(args) })
  await modal.view.loadPlaylists()
  assert.deepEqual(plain(modal.reads), [0, 1, 2].map((page) => ({ libraryId: 'l1', limit: 20, page })))
  assert.equal(modal.view.playlists.length, 50)
  assert.equal(modal.view.loading, false)
  const included = modal.view.sortedPlaylists.filter((p) => p.isItemIncluded).map((p) => p.id)
  assert.equal(included.length, 25, 'the 10th item, beyond the 4 cover items, is found by itemKeys')
  assert.ok(included.includes('p48'))
  const last = modal.view.sortedPlaylists.find((p) => p.id === 'p49')
  assert.equal(last.isItemIncluded, false)
  await modal.view.clickPlaylist(last)
  assert.deepEqual(plain(modal.posts), [['/api/playlists/p49/batch/add', { items: [{ libraryItemId: 'li9', episodeId: null }] }]], 'a playlist of the last page is picked')
  await modal.view.clickPlaylist(modal.view.sortedPlaylists.find((p) => p.id === 'p48'))
  assert.equal(modal.posts.at(-1)[0], '/api/playlists/p48/batch/remove')

  // While open the list follows native's notices for this library; closed, nothing
  modal.view.setListeners()
  modal.send({ scope: 'catalog', ids: ['playlists:l2'] })
  assert.equal(modal.reads.length, 3)
  modal.send({ scope: 'catalog', ids: ['playlists:l1'] })
  await flush()
  assert.equal(modal.reads.length, 6)
  modal.view.unsetListeners()
  assert.equal(modal.dataListeners.size, 0)

  const failing = addCreateModal({ getPlaylists: async () => Promise.reject(new Error('bridge')) })
  await failing.view.loadPlaylists()
  assert.deepEqual(failing.toasts, ['Failed to load playlists'])
})

test('playlist page loads every page of items', async () => {
  const items = itemsOf(230)
  const plugin = fakePlugin({ items })
  const page = playlistPage({ absData: createDataService({ plugin, store: fakeStore() }) })
  const { result } = await page.asyncData()
  const itemCalls = plugin.calls.filter(([name]) => name === 'getPlaylistItems').map(([, args]) => args)
  assert.deepEqual(plain(itemCalls), [0, 1, 2].map((p) => ({ id: 'p1', limit: 100, page: p })))
  assert.deepEqual(result.playlist.items.map((i) => i.libraryItemId), items.map((i) => i.libraryItemId), 'all 230, in order')

  // The browser: the same shapes cut from /api/playlists/:id
  const gets = []
  const $axios = {
    $get: async (path) => {
      gets.push(path)
      if (path === '/api/playlists/p1') return serverPlaylist(230)
      if (path.startsWith('/api/libraries/l1/playlists')) return { results: [serverPlaylist(230)], total: 1, limit: '20', page: '0' }
      throw Object.assign(new Error('404'), { response: { status: 404 } })
    }
  }
  const web = createDataService({ plugin: { addListener: () => ({}), getServerState: async () => ({}) }, store: { state: { activeServerConfigId: 'a' }, commit() {} }, platform: 'web', $axios })
  const list = await web.getPlaylists({ libraryId: 'l1', limit: 20, page: 0 })
  assert.equal(gets[0], '/api/libraries/l1/playlists?limit=20&page=0')
  assert.equal(list.results[0].numItems, 230)
  assert.equal(list.results[0].items.length, 4, 'cover items only')
  assert.equal(list.results[0].itemKeys.length, 230)
  assert.deepEqual(list.results[0].itemKeys[229], { libraryItemId: 'li229', episodeId: null })
  const one = await web.getPlaylist({ id: 'p1' })
  assert.equal(one.playlist.numItems, 230)
  assert.equal(one.playlist.items, undefined)
  const webResult = (await playlistPage({ absData: web }).asyncData()).result
  assert.deepEqual(webResult.playlist.items.map((i) => i.libraryItemId), items.map((i) => i.libraryItemId))
  assert.deepEqual(gets.slice(-3), ['/api/playlists/p1', '/api/playlists/p1', '/api/playlists/p1'], 'one request a page of 100')
  const lastPage = await web.getPlaylistItems({ id: 'p1', limit: 100, page: 2 })
  assert.deepEqual({ total: lastPage.total, length: lastPage.results.length, found: lastPage.found }, { total: 230, length: 30, found: true })
  assert.deepEqual([lastPage.results[0].episodeId, lastPage.results[0].episode], [null, null], 'a book as native gives it')
  assert.equal((await web.getPlaylistItems({ id: 'gone', limit: 100, page: 0 })).found, false)
  assert.equal((await web.getPlaylist({ id: 'gone' })).playlist, null)

  // A list that moves while it loads is read again once
  let reads = 0
  const moving = {
    getPlaylistItems: async ({ limit, page }) => {
      if (page === 0) reads++
      const results = reads === 1 && page === 1 ? [items[0], ...items.slice(101, 200)] : items.slice(page * limit, page * limit + limit)
      return { serverConfigId: 'a', results, total: 230, limit, page, found: true }
    }
  }
  assert.equal((await fetchPlaylistItems(moving, 'p1')).length, 230)
  assert.equal(reads, 2)
  // The same episode of one podcast twice is a moved list, two episodes of it are not
  const twoEpisodes = [entry(podcast, 'ep1'), entry(podcast, 'ep2')]
  assert.equal((await fetchPlaylistItems({ getPlaylistItems: async () => ({ serverConfigId: 'a', results: twoEpisodes, total: 2, found: true }) }, 'p1')).length, 2)
})

test('playlist edits are disabled offline and labeled', async () => {
  // AddCreateModal: the list shows, every write is disabled, labeled and never sent
  assert.match(modalSource, /<ui-btn :loading="processing" :disabled="!isServerOnline" color="success"[^>]*@click\.stop="createPlaylist">/)
  assert.match(modalSource, /<ui-btn color="success" :loading="processing" :disabled="!isServerOnline"[^>]*@click\.stop="submitCreatePlaylist">/)
  assert.equal(modalSource.match(/<p v-if="!isServerOnline" class="requires-server-label[^"]*">\{\{ requiresServerLabel\(\) \}\}<\/p>/g)?.length, 2, 'both create buttons are labeled')
  assert.match(modalSource, /<modals-playlists-playlist-row [^>]*:disabled="!isServerOnline"/)
  const rowSource = await read('../components/modals/playlists/PlaylistRow.vue')
  assert.equal(rowSource.match(/<ui-btn v-(if|else)[^>]*:disabled="disabled"/g)?.length, 2)
  assert.match(rowSource, /<p v-if="disabled" class="requires-server-label[^"]*">\{\{ \$strings\.LabelRequiresServer \}\}<\/p>/)
  const rowDef = componentDef(rowSource)
  const clicks = []
  bind(rowDef, { playlist: { id: 'p1' }, disabled: true, $emit: (...e) => clicks.push(e) }).click()
  assert.deepEqual(clicks, [])
  bind(rowDef, { playlist: { id: 'p1' }, disabled: false, $emit: (...e) => clicks.push(e) }).click()
  assert.equal(clicks.length, 1)

  const offline = addCreateModal({ getPlaylists: async (args) => playlistsAnswer(args), serverState: 'offline' })
  await offline.view.loadPlaylists()
  assert.equal(offline.view.playlists.length, 50, 'the playlists are still shown')
  assert.equal(offline.view.isServerOnline, false)
  assert.equal(offline.view.requiresServerLabel(), 'Requires the server')
  offline.view.createPlaylist()
  assert.equal(offline.view.showPlaylistNameInput, false)
  offline.view.newPlaylistName = 'New'
  await offline.view.submitCreatePlaylist()
  await offline.view.clickPlaylist(offline.view.sortedPlaylists[0])
  await offline.view.clickPlaylist(offline.view.sortedPlaylists.at(-1))
  offline.view.addToPlaylist({ id: 'p1' })
  offline.view.removeFromPlaylist({ id: 'p0' })
  assert.deepEqual(offline.posts, [], 'nothing is written offline')

  const online = addCreateModal({ getPlaylists: async (args) => playlistsAnswer(args) })
  online.view.createPlaylist()
  assert.equal(online.view.showPlaylistNameInput, true)
  online.view.newPlaylistName = 'New'
  await online.view.submitCreatePlaylist()
  online.view.addToPlaylist({ id: 'p1' })
  online.view.removeFromPlaylist({ id: 'p0' })
  assert.deepEqual(online.posts.map(([path]) => path), ['/api/playlists', '/api/playlists/p1/batch/add', '/api/playlists/p0/batch/remove'])

  // ItemMoreMenuModal: Add to / Remove from playlist stay visible, disabled with the reason, and do nothing
  const menuDef = componentDef(await read('../components/modals/ItemMoreMenuModal.vue'), { requiresServer, progressTarget: () => ({}), isCopyOfActiveServer: () => false, Dialog: {}, AbsFileSystem: {} })
  const menu = (serverState) => {
    const commits = []
    const sent = []
    const getters = new Proxy({ getIsServerOnline: serverState === 'online' }, { get: (target, name) => (name in target ? target[name] : () => null) })
    const view = bind(menuDef, {
      value: true,
      libraryItem: book('li1'),
      episode: null,
      playlist: { id: 'p1' },
      rssFeed: null,
      hideRssFeedOption: true,
      processing: false,
      $platform: 'android',
      $strings: strings,
      $store: { state: { libraries: {}, activeServerConfigId: 'a' }, getters, commit: (...c) => commits.push(c) },
      $nativeHttp: { delete: async (path) => (sent.push(path), {}), post: async (path) => (sent.push(path), {}) },
      $toast: { error() {}, success() {} },
      $emit() {},
      $nextTick: (fn) => fn()
    })
    return { view, commits, sent }
  }
  const off = menu('offline')
  const edits = off.view.moreMenuItems.filter((i) => i.value === 'playlist' || i.value === 'removeFromPlaylist')
  assert.deepEqual(plain(edits.map((i) => [i.value, i.disabled, i.subtext])), [['playlist', true, 'Requires the server'], ['removeFromPlaylist', true, 'Requires the server']])
  off.view.moreMenuAction('playlist')
  off.view.moreMenuAction('removeFromPlaylist')
  await flush()
  assert.deepEqual([off.commits, off.sent], [[], []])
  const on = menu('online')
  const onEdits = on.view.moreMenuItems.filter((i) => i.value === 'playlist' || i.value === 'removeFromPlaylist')
  assert.equal(onEdits.length, 2)
  assert.ok(onEdits.every((i) => i.disabled === undefined && i.subtext === undefined), 'online as before')
  on.view.moreMenuAction('playlist')
  on.view.moreMenuAction('removeFromPlaylist')
  await flush()
  assert.deepEqual(on.commits.map(([type]) => type), ['globals/setSelectedPlaylistItems', 'globals/setShowPlaylistsAddCreateModal'])
  assert.deepEqual(on.sent, ['/api/playlists/p1/item/li1'])

  // The dialog shows a disabled item and its reason but sends no action for it; other dialogs are unchanged
  const dialogSource = await read('../components/modals/Dialog.vue')
  assert.match(dialogSource, /@click="selectItem\(item\)"/)
  assert.match(dialogSource, /<p v-if="item\.subtext" [^>]*>\{\{ item\.subtext \}\}<\/p>/)
  const dialogDef = componentDef(dialogSource)
  const actions = []
  const dialog = bind(dialogDef, { value: true, items: [], $emit: (name, value) => actions.push([name, value]) })
  dialog.selectItem({ value: 'playlist', disabled: true })
  dialog.selectItem({ value: 'history' })
  assert.deepEqual(actions, [['action', 'history']])

  // The episode page's own menu
  const episodeSource = await read('../pages/item/_id/_episode/index.vue')
  const episodeDef = componentDef(episodeSource, { requiresServer, cellularPermissionHelpers: {}, progressTarget: () => ({}), isCopyOfActiveServer: () => false, Capacitor: {}, Dialog: {}, AbsFileSystem: {}, AbsDownloader: {} })
  const episodePage = (serverState) => {
    const commits = []
    const getters = new Proxy({ getIsServerOnline: serverState === 'online' }, { get: (target, name) => (name in target ? target[name] : () => null) })
    const view = bind(episodeDef, { libraryItem: { id: 'pod1', mediaType: 'podcast', media: { metadata: {} } }, episode: { id: 'ep1' }, $strings: strings, $store: { state: {}, getters, commit: (...c) => commits.push(c) } })
    return { view, commits }
  }
  const episodeOffline = episodePage('offline')
  assert.deepEqual(plain(episodeOffline.view.moreMenuItems.find((i) => i.value === 'playlist')), { value: 'playlist', icon: 'playlist_add', disabled: true, subtext: 'Requires the server' })
  episodeOffline.view.moreMenuAction('playlist')
  assert.deepEqual(episodeOffline.commits, [])
  const episodeOnline = episodePage('online')
  assert.equal(episodeOnline.view.moreMenuItems.find((i) => i.value === 'playlist').disabled, undefined)
  episodeOnline.view.moreMenuAction('playlist')
  assert.equal(episodeOnline.commits.length, 2)
})

test('never-reconciled playlists read the server while online', async () => {
  const http = fakeHttp((path) => (path.startsWith('/api/playlists/') ? serverPlaylist(7) : { results: [serverPlaylist(7)], total: 1, limit: '20', page: '1' }))
  const plugin = fakePlugin({ items: [], reconciledAt: null })
  const online = createDataService({ plugin, store: fakeStore(), nativeHttp: () => http })

  const list = await online.getPlaylists({ libraryId: 'l 1', limit: 20, page: 1 })
  assert.equal(http.paths[0], '/api/libraries/l%201/playlists?limit=20&page=1')
  assert.equal(list.fromServer, true)
  assert.equal(list.results[0].numItems, 7)
  assert.equal(list.results[0].items.length, 4)
  assert.equal(list.results[0].itemKeys.length, 7)
  assert.equal(list.replica.reconciledAt, null, "native's replica state is kept")

  const one = await online.getPlaylist({ id: 'p 1' })
  assert.equal(http.paths[1], '/api/playlists/p%201')
  assert.deepEqual(plain(one), { playlist: { id: 'p1', name: 'Мой', libraryId: 'l1', userId: 'u1', description: null, lastUpdate: 2, createdAt: 1, numItems: 7 }, serverConfigId: 'a', replica: replica(null), fromServer: true })

  const items = await online.getPlaylistItems({ id: 'p1', limit: 5, page: 1 })
  assert.equal(http.paths[2], '/api/playlists/p1')
  assert.deepEqual(items.results.map((i) => i.libraryItemId), ['li5', 'li6'])
  assert.equal(items.total, 7)
  assert.equal(items.fromServer, true)

  // A read replica without the playlist (made since the last sync) asks the server; a 404 leaves native's answer
  const absent = fakePlugin({ overrides: { getPlaylist: async () => ({ serverConfigId: 'a', playlist: null, replica: replica() }), getPlaylistItems: async ({ limit, page }) => ({ serverConfigId: 'a', results: [], total: 0, limit, page, found: false, replica: replica() }) } })
  const newOnServer = createDataService({ plugin: absent, store: fakeStore(), nativeHttp: () => fakeHttp(() => serverPlaylist(2)) })
  assert.equal((await newOnServer.getPlaylist({ id: 'p1' })).playlist.numItems, 2)
  assert.equal((await newOnServer.getPlaylistItems({ id: 'p1', limit: 100, page: 0 })).results.length, 2)
  const notFound = { get: async () => Promise.reject(Object.assign(new Error('Not Found'), { status: 404 })) }
  const gone = createDataService({ plugin: absent, store: fakeStore(), nativeHttp: () => notFound })
  assert.equal((await gone.getPlaylist({ id: 'p1' })).playlist, null)
  assert.equal((await gone.getPlaylistItems({ id: 'p1', limit: 100, page: 0 })).found, false)

  // A read replica answers; offline nothing goes to the server
  const reconciled = createDataService({ plugin: fakePlugin(), store: fakeStore(), nativeHttp: () => fakeHttp(() => assert.fail('a read replica does not go to the server')) })
  assert.equal((await reconciled.getPlaylists({ libraryId: 'l1', limit: 20, page: 0 })).fromServer, undefined)
  assert.equal((await reconciled.getPlaylistItems({ id: 'p1', limit: 100, page: 0 })).results.length, 3)
  const offline = createDataService({ plugin: fakePlugin({ items: [], reconciledAt: null }), store: fakeStore({ serverState: 'offline' }), nativeHttp: () => fakeHttp(() => assert.fail('no server offline')) })
  assert.equal((await offline.getPlaylist({ id: 'p1' })).playlist.numItems, 0)
  assert.equal((await offline.getPlaylists({ libraryId: 'l1', limit: 20, page: 0 })).fromServer, undefined)
})

/** LazyBookshelf on the Playlists tab over fakes ($absData, store, DOM helpers stubbed). */
async function bookshelf({ getPlaylists, hasActiveServer = true, realSize = false }) {
  const source = await read('../components/bookshelf/LazyBookshelf.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  const component = new Function('bookshelfCardsHelpers', 'createPageRetry', 'textScale', 'shouldRefreshShelf', 'createDebouncer', 'isReplicaIncomplete', 'localCopyOf', script)(
    {},
    () => ({ reset() {}, succeeded() {}, failed() {} }),
    () => 1,
    shouldRefreshShelf,
    createDebouncer,
    isReplicaIncomplete,
    localCopyOf
  )
  const reads = []
  const emitted = []
  const store = {
    state: { user: { user: null }, serverState: 'offline', activeServerConfigId: 'a', libraries: { currentLibraryId: 'l1' }, globals: { bookshelfListView: false }, lastBookshelfScrollData: {} },
    getters: {
      getHasActiveServer: hasActiveServer,
      getServerSetting: () => false,
      'user/getUserSetting': () => null,
      'libraries/getCurrentLibraryMediaType': 'podcast',
      'libraries/getBookCoverAspectRatio': 1.6
    }
  }
  const view = {
    ...component.data(),
    page: 'playlists',
    $store: store,
    $eventBus: { $emit: (name, value) => emitted.push([name, value]), $on() {}, $off() {} },
    $db: { getLocalLibraryItems: async () => [] },
    $absData: { getPlaylists: async (args) => (reads.push(args), getPlaylists(args)), onDataChanged: () => () => {} },
    $nativeHttp: { get: async () => assert.fail('the Playlists tab does not read the server directly') },
    mountEntites() {},
    destroyEntityComponents() {}
  }
  if (!realSize) view.initSizeData = () => {}
  for (const [name, def] of Object.entries(component.computed)) Object.defineProperty(view, name, { get: (def.get || def).bind(view), configurable: true })
  for (const [name, fn] of Object.entries(component.methods)) if (!(name in view)) view[name] = fn.bind(view)
  return { component, view, reads, emitted }
}

test('empty playlists replica offline shows catalog-not-loaded', async () => {
  const never = await bookshelf({ getPlaylists: async ({ limit, page }) => ({ serverConfigId: 'a', results: [], total: 0, limit, page, replica: replica(null) }) })
  await never.view.init()
  assert.equal(never.view.catalogNotLoaded, true)
  assert.deepEqual(never.emitted.at(-1), ['bookshelf-total-entities', null], 'no count over it')

  const read = await bookshelf({ getPlaylists: async ({ limit, page }) => ({ serverConfigId: 'a', results: [], total: 0, limit, page, replica: replica() }) })
  await read.view.init()
  assert.equal(read.view.catalogNotLoaded, false, 'a read library without playlists: "no playlists"')

  const noServer = await bookshelf({ getPlaylists: async () => assert.fail('no read without an active server'), hasActiveServer: false })
  await noServer.view.init()
  assert.deepEqual(noServer.reads, [])
})

test('playlists tab reads the replica at most 20 a page', async () => {
  const shelf = await bookshelf({ realSize: true, getPlaylists: async ({ limit, page }) => ({ serverConfigId: 'a', results: [{ id: 'p1', items: [], itemKeys: [] }], total: 1, limit, page, replica: replica() }) })
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
  assert.equal(shelf.view.entities[0].id, 'p1')
  assert.equal(shelf.view.readsCatalog, true)
  assert.deepEqual(shouldRefreshShelf({ scope: 'catalog', ids: ['playlists:l1'] }, { entity: 'playlists', libraryId: 'l1' }), true)
  assert.deepEqual(shouldRefreshShelf({ scope: 'catalog', ids: ['playlists:l2'] }, { entity: 'playlists', libraryId: 'l1' }), false)
})

/** store/libraries.js `fetch` over `absData`; BookshelfNavBar over the resulting state. */
async function navFor(absData) {
  const source = (await read('../store/libraries.js')).replace(/^import .*$/gm, '').replace(/^export const (\w+) =/gm, 'globalThis.$1 =')
  const sandbox = { console: { ...console, error: () => {} }, require: () => ({ BookCoverAspectRatio: {} }) }
  vm.runInNewContext(source, sandbox)
  const state = { currentLibraryId: 'l1', libraries: [], numUserPlaylists: 0 }
  const ctx = {
    state,
    rootGetters: { getHasActiveServer: true },
    dispatch() {},
    commit: (type, value) => {
      if (type === 'setNumUserPlaylists') state.numUserPlaylists = value
    }
  }
  const fetch = () => sandbox.actions.fetch.call({ $absData: absData }, ctx, { libraryId: 'l1', onlyIfCurrent: true })
  const navDef = componentDef(await read('../components/home/BookshelfNavBar.vue'))
  const nav = bind(navDef, { $store: { state: { libraries: state }, getters: { 'libraries/getCurrentLibrary': { id: 'l1', mediaType: 'book' }, 'user/getIsAdminOrUp': false } }, $strings: {}, isPodcast: false })
  const tabs = () => nav.items.map((i) => i.to)
  return { fetch, state, tabs }
}

test('playlists tab visibility follows the replica', async () => {
  const plugin = fakePlugin({ overrides: { getLibrary: async ({ id }) => ({ serverConfigId: 'a', library: { id, mediaType: 'book' }, filterdata: {}, issues: 0, numUserPlaylists: 2, replica: replica() }) } })
  const absData = createDataService({ plugin, store: fakeStore({ serverState: 'offline' }), nativeHttp: () => fakeHttp(() => assert.fail('offline')) })
  const nav = await navFor({ ...absData, setCurrentLibrary: async () => {} })
  assert.ok(!nav.tabs().includes('/bookshelf/playlists'))
  await nav.fetch()
  assert.equal(nav.state.numUserPlaylists, 2)
  assert.ok(nav.tabs().includes('/bookshelf/playlists'), 'offline, from the replica')
})

test('playlist_added shows the tab through library:<id>', async () => {
  let numUserPlaylists = 0
  const listeners = {}
  const plugin = fakePlugin({
    overrides: {
      addListener: (name, cb) => ((listeners[name] = cb), { remove() {} }),
      getLibrary: async ({ id }) => ({ serverConfigId: 'a', library: { id, mediaType: 'book' }, filterdata: {}, issues: 0, numUserPlaylists, replica: replica() }),
      setCurrentLibrary: async () => {}
    }
  })
  let nav
  const store = { ...fakeStore(), dispatch: async (name, payload) => (name === 'libraries/fetch' ? nav.fetch(payload) : undefined) }
  const absData = createDataService({ plugin, store })
  nav = await navFor(absData)
  await nav.fetch()
  assert.ok(!nav.tabs().includes('/bookshelf/playlists'))

  // Native applied playlist_added: the library's count moved and it announces library:<id>
  assert.deepEqual(catalogTopics('playlist_added', { id: 'p1', libraryId: 'l1', items: [] }), ['playlists:l1', 'library:l1'])
  numUserPlaylists = 1
  listeners.onDataChanged({ scope: 'catalog', ids: ['playlists:l1', 'library:l1'] })
  await flush()
  assert.equal(nav.state.numUserPlaylists, 1)
  assert.ok(nav.tabs().includes('/bookshelf/playlists'))

  for (const event of ['playlist_added', 'playlist_updated', 'playlist_removed']) assert.ok(CATALOG_EVENTS.includes(event), event)
  assert.deepEqual(catalogTopics('playlist_removed', { id: 'p1' }), [])
  const server = await read('../plugins/server.js')
  assert.doesNotMatch(server, /onPlaylistAdded|'playlist_added'/, 'the socket handler is gone: native counts the playlists')
  const native = await read('../android/app/src/main/java/app/absplus/android/sync/CatalogEventApplier.kt')
  assert.match(native, /"playlist_added", "playlist_updated", "playlist_removed"/, 'native applies them')
  const web = await read('../plugins/capacitor/AbsData.js')
  for (const method of ['getPlaylists', 'getPlaylist', 'getPlaylistItems']) assert.match(web, new RegExp(`async ${method}\\(`), `web plugin ${method}`)
})

test('playlist page re-reads on playlists:<lib>', async () => {
  let items = itemsOf(3)
  let playlistGone = false
  const pending = []
  let holdNext = false
  const absData = {
    getPlaylist: async ({ id }) => ({ serverConfigId: 'a', playlist: playlistGone ? null : { id, libraryId: 'l1', name: 'Мой', numItems: items.length }, replica: replica() }),
    getPlaylistItems: async ({ limit, page }) => {
      const answer = { serverConfigId: 'a', results: items.slice(page * limit, page * limit + limit), total: items.length, limit, page, found: !playlistGone, replica: replica() }
      if (!holdNext) return answer
      holdNext = false
      return new Promise((resolve) => pending.push(() => resolve(answer)))
    }
  }
  const page = playlistPage({ absData })
  const view = await page.mount()
  assert.equal(page.dataListeners.size, 1)

  page.send({ scope: 'catalog', ids: ['playlists:l2', 'collections:l1', 'series:l1'] })
  page.send({ scope: 'progress', ids: ['li0'] })
  assert.equal(page.timers.length, 0, "another library's playlists are not this page")

  // Reordered in the web client
  items = [items[2], items[0], items[1]]
  page.send({ scope: 'catalog', ids: ['playlists:l1'] })
  page.send({ scope: 'catalog', ids: ['items:l1'] })
  assert.equal(page.timers.length, 1, 'debounced')
  assert.equal(page.timers[0].ms, 1000)
  page.timers.shift().fn()
  await flush()
  assert.deepEqual(view.playlist.items.map((i) => i.libraryItemId), ['li2', 'li0', 'li1'])

  // A late answer of an older read is dropped
  holdNext = true
  items = itemsOf(1)
  const slow = view.reload()
  await flush()
  items = itemsOf(2)
  await view.reload()
  pending.shift()()
  await slow
  assert.deepEqual(view.playlist.items.map((i) => i.libraryItemId), ['li0', 'li1'])

  playlistGone = true
  page.send({ scope: 'catalog', ids: ['playlists:l1'] })
  page.timers.shift().fn()
  await flush()
  assert.deepEqual(page.replaced, ['/bookshelf/playlists'], 'gone: back to the tab')

  page.send({ scope: 'catalog', ids: ['playlists:l1'] })
  page.destroy(view)
  assert.equal(page.timers.length, 0, 'a pending re-read is dropped with the page')
  assert.equal(page.dataListeners.size, 0)
})

test('requiresServer mixin reads the server state', () => {
  const view = bind({ mixins: [requiresServer] }, { $store: { getters: { getIsServerOnline: false } }, $strings: strings })
  assert.equal(view.isServerOnline, false)
  assert.equal(view.requiresServerLabel(), 'Requires the server')
  assert.equal(bind({ mixins: [requiresServer] }, { $store: { getters: { getIsServerOnline: true } }, $strings: strings }).isServerOnline, true)
})

/** The server's expanded playlist after a write: `ids` are its books. */
const expanded = (id, ids) => ({ id, libraryId: 'l1', name: `Playlist ${id}`, items: ids.map((i) => ({ libraryItemId: i, libraryItem: book(i) })) })

test('the add-to-playlist list follows a write at once', async () => {
  const modal = addCreateModal({
    getPlaylists: async (args) => playlistsAnswer(args),
    answer: (path, body) => {
      if (path === '/api/playlists/p49/batch/add') return expanded('p49', ['other0', 'li9'])
      if (path === '/api/playlists/p48/batch/remove') return expanded('p48', [])
      if (path === '/api/playlists') return { ...expanded('new', ['li9']), name: body.name }
      return {}
    }
  })
  await modal.view.loadPlaylists()
  await modal.view.clickPlaylist(modal.view.sortedPlaylists.find((p) => p.id === 'p49'))
  await flush()
  const added = modal.view.sortedPlaylists.find((p) => p.id === 'p49')
  assert.equal(added.isItemIncluded, true, 'marked without waiting for the catalog notice')
  assert.equal(added.numItems, 2)
  assert.equal(added.itemKeys.length, 2)
  await modal.view.clickPlaylist(modal.view.sortedPlaylists.find((p) => p.id === 'p48'))
  await flush()
  assert.equal(modal.view.playlists.some((p) => p.id === 'p48'), false, 'emptied: the server deleted it')
  modal.view.createPlaylist()
  modal.view.newPlaylistName = 'Новый'
  await modal.view.submitCreatePlaylist()
  await flush()
  assert.equal(modal.view.sortedPlaylists.find((p) => p.id === 'new').isItemIncluded, true)
  assert.deepEqual(plain(modal.commits), [['libraries/setNumUserPlaylists', 1]], 'the Playlists tab shows at once')
  modal.state.libraries.numUserPlaylists = 3
  await modal.view.submitCreatePlaylist()
  assert.equal(modal.commits.length, 1, 'a count already there stays')
})

test('a removed item leaves the open playlist page at once', async () => {
  const page = playlistPage({ absData: createDataService({ plugin: fakePlugin({ items: [entry(book('li0')), entry(podcast, 'ep1'), entry(podcast, 'ep2')] }), store: fakeStore() }) })
  const view = await page.mount()
  assert.match(pageSource, /@removedFromPlaylist="itemRemoved"/)
  view.itemRemoved({ libraryItemId: 'pod1', episodeId: 'ep1' })
  assert.deepEqual(view.playlist.items.map((i) => [i.libraryItemId, i.episodeId]), [['li0', null], ['pod1', 'ep2']])
  view.itemRemoved({ libraryItemId: 'li0', episodeId: null })
  view.itemRemoved({ libraryItemId: 'pod1', episodeId: 'ep2' })
  assert.deepEqual(page.replaced, ['/bookshelf/playlists'], 'the server deleted the emptied playlist')
})

/** ItemMoreMenuModal over fakes. */
async function moreMenu({ libraryItem, playlist = null, devices = [] }) {
  const def = componentDef(await read('../components/modals/ItemMoreMenuModal.vue'), { requiresServer, progressTarget: () => ({}), isCopyOfActiveServer: () => false, Dialog: {}, AbsFileSystem: {} })
  const emitted = []
  const getters = new Proxy({ getIsServerOnline: true }, { get: (target, name) => (name in target ? target[name] : () => null) })
  const view = bind(def, {
    value: true,
    libraryItem,
    episode: null,
    playlist,
    rssFeed: null,
    hideRssFeedOption: true,
    processing: false,
    $platform: 'android',
    $strings: strings,
    $store: { state: { libraries: { ereaderDevices: devices }, activeServerConfigId: 'a' }, getters, commit() {} },
    $nativeHttp: { delete: async () => ({}), post: async (path, body) => (emitted.push(['post', path, body]), {}) },
    $toast: { error() {}, success() {} },
    $emit: (...e) => emitted.push(e),
    $nextTick: (fn) => fn()
  })
  return { view, emitted }
}

test('removing from the playlist page tells the page', async () => {
  const { view, emitted } = await moreMenu({ libraryItem: book('li1'), playlist: { id: 'p1' } })
  view.removeFromPlaylistClick()
  await flush()
  assert.deepEqual(plain(emitted.find(([name]) => name === 'removedFromPlaylist')), ['removedFromPlaylist', { libraryItemId: 'li1', episodeId: null }])
})

test('a minified book with an e-book can be sent to a device', async () => {
  const minified = { ...book('li1'), media: { ...book('li1').media, ebookFormat: 'epub' } }
  const { view, emitted } = await moreMenu({ libraryItem: minified, devices: [{ name: 'Kindle' }] })
  assert.deepEqual(plain(view.ereaderDeviceItems), [{ text: 'Kindle', value: 'Kindle' }])
  assert.ok(view.moreMenuItems.some((i) => i.value === 'sendEbook'))
  view.sendEbookToDeviceAction('Kindle')
  assert.deepEqual(plain(emitted.find(([name]) => name === 'post')), ['post', '/api/emails/send-ebook-to-device', { libraryItemId: 'li1', deviceName: 'Kindle' }], 'by the item id')
  assert.deepEqual(plain((await moreMenu({ libraryItem: book('li2'), devices: [{ name: 'Kindle' }] })).view.ereaderDeviceItems), [], 'no e-book, no devices')
})

test('a downloaded episode in a playlist opens its copy', async () => {
  const def = componentDef(await read('../components/tables/playlist/ItemTableRow.vue'), { trackCount })
  const row = (item) => bind(def, { item, $store: { getters: { getIsMediaStreaming: () => false, 'user/getUserMediaProgress': () => null }, state: {} } })
  assert.equal(row({ ...entry(podcast, 'ep1'), localLibraryItem: { id: 'local_pod' }, localEpisode: { id: 'local_ep1' } }).itemUrl, '/item/local_pod/local_ep1')
  assert.equal(row(entry(podcast, 'ep1')).itemUrl, '/item/pod1/ep1')
})

test('a write in the add-to-playlist list is not undone by an older list read', async () => {
  const held = []
  const modal = addCreateModal({
    getPlaylists: (args) => new Promise((resolve) => held.push(() => resolve(playlistsAnswer(args)))),
    answer: () => expanded('p49', ['other0', 'li9'])
  })
  // The list is open; a re-read on a catalog notice starts before the write and answers after it
  const first = modal.view.loadPlaylists()
  for (let i = 0; i < 3; i++) {
    await flush()
    held.shift()()
  }
  await first
  const load = modal.view.loadPlaylists()
  await flush()
  await modal.view.addToPlaylist({ id: 'p49' })
  await flush()
  while (held.length) {
    held.shift()()
    await flush()
  }
  await load
  await flush()
  const p49 = modal.view.sortedPlaylists.find((p) => p.id === 'p49')
  assert.equal(p49.isItemIncluded, true, 'the write stays')
  assert.equal(modal.view.playlists.length, 50)
  assert.equal(modal.view.loading, false)
  // That read still showed the old items: the write still holds
  assert.ok(Object.keys(modal.view.writes).includes('p49'))
})

test('a playlist created while the list still loads joins the whole list', async () => {
  const held = []
  const modal = addCreateModal({
    getPlaylists: (args) => new Promise((resolve) => held.push(() => resolve(playlistsAnswer(args)))),
    answer: (path, body) => ({ ...expanded('new', ['li9']), name: body.name })
  })
  const load = modal.view.loadPlaylists()
  await flush()
  modal.view.createPlaylist()
  modal.view.newPlaylistName = 'Новый'
  await modal.view.submitCreatePlaylist()
  await flush()
  assert.deepEqual(plain(modal.view.playlists.map((p) => p.id)), ['new'])
  while (held.length) {
    held.shift()()
    await flush()
  }
  await load
  assert.equal(modal.view.playlists.length, 51, 'the 50 read and the new one')
  assert.equal(modal.view.sortedPlaylists.find((p) => p.id === 'new').isItemIncluded, true)
  assert.equal(modal.view.loading, false)
  // A read that shows it ends the write
  const later = addCreateModal({ getPlaylists: async (args) => playlistsAnswer(args) })
  later.view.applyServerPlaylist(expanded('p1', ['other0']))
  assert.ok('p1' in later.view.writes)
  await later.view.loadPlaylists()
  assert.equal('p1' in later.view.writes, true, 'the read still has the old items')
  later.view.writes = { p0: playlistsAnswer({ limit: 20, page: 0 }).results[0] }
  await later.view.loadPlaylists()
  assert.equal('p0' in later.view.writes, false, 'shown as written: dropped')
})

test('a removed item is not brought back by a read of the replica that has not caught up', async () => {
  let items = [entry(book('li0')), entry(book('li1'))]
  const pending = []
  let hold = false
  const absData = {
    getPlaylist: async ({ id }) => ({ serverConfigId: 'a', playlist: { id, libraryId: 'l1', name: 'Мой', numItems: items.length }, replica: replica() }),
    getPlaylistItems: async ({ limit, page }) => {
      const answer = { serverConfigId: 'a', results: items.slice(page * limit, page * limit + limit), total: items.length, limit, page, found: true, replica: replica() }
      return hold ? new Promise((resolve) => pending.push(() => resolve(answer))) : answer
    }
  }
  const page = playlistPage({ absData })
  const view = await page.mount()
  hold = true
  const running = view.reload()
  await flush()
  page.send({ scope: 'catalog', ids: ['items:l1'] })
  view.itemRemoved({ libraryItemId: 'li0', episodeId: null })
  assert.deepEqual(view.playlist.items.map((i) => i.libraryItemId), ['li1'])
  pending.shift()()
  await running
  assert.deepEqual(view.playlist.items.map((i) => i.libraryItemId), ['li1'], 'a read of the old replica does not bring it back')
  hold = false
  page.timers.shift().fn()
  await flush()
  assert.deepEqual(view.playlist.items.map((i) => i.libraryItemId), ['li1'], 'nor the due one, which still runs')
  items = [items[1]]
  await view.reload()
  assert.deepEqual(plain(view.removedKeys), [], 'the replica caught up: nothing left to hide')
})

test('a removed item and a playlist gone meanwhile leave for the tab', async () => {
  let gone = false
  const items = [entry(book('li0')), entry(book('li1'))]
  const absData = {
    getPlaylist: async ({ id }) => ({ serverConfigId: 'a', playlist: gone ? null : { id, libraryId: 'l1', name: 'Мой', numItems: 2 }, replica: replica() }),
    getPlaylistItems: async ({ limit, page }) => ({ serverConfigId: 'a', results: items, total: 2, limit, page, found: !gone, replica: replica() })
  }
  const page = playlistPage({ absData })
  const view = await page.mount()
  // The notice of the playlist's deletion elsewhere arrives just before the answer to this removal
  gone = true
  page.send({ scope: 'catalog', ids: ['playlists:l1'] })
  view.itemRemoved({ libraryItemId: 'li0', episodeId: null })
  assert.equal(page.timers.length, 1, 'the notice still reads again')
  page.timers.shift().fn()
  await flush()
  assert.deepEqual(page.replaced, ['/bookshelf/playlists'])
})

test('a sign-in or reconnect does not take the playlist page away', async () => {
  const page = playlistPage({ absData: createDataService({ plugin: fakePlugin(), store: fakeStore() }) })
  const view = await page.mount()
  view.libraryChanged()
  assert.deepEqual(page.replaced, [], 'the same library and server announced again')
  page.store.state.libraries.currentLibraryId = null
  view.libraryChanged()
  assert.deepEqual(page.replaced, [], 'no current library yet')
  page.store.state.libraries.currentLibraryId = 'l2'
  view.libraryChanged()
  assert.deepEqual(page.replaced, ['/bookshelf/playlists'], 'another library picked')

  // Another account of the same server: same library ids, someone else's playlists
  const other = playlistPage({ absData: createDataService({ plugin: fakePlugin(), store: fakeStore() }) })
  const otherView = await other.mount()
  other.store.state.activeServerConfigId = 'b'
  otherView.libraryChanged()
  assert.deepEqual(other.replaced, ['/bookshelf/playlists'])
})

test('the collection and item pages leave only for another library or server', async () => {
  const collectionDef = componentDef(await read('../pages/collection/_id.vue'), { leavesOnLibraryChange, trackCount, createDebouncer, downloadMissingItems() {}, buildQueueSourceItems() {}, requiresServer })
  const state = { libraries: { currentLibraryId: 'l1' }, activeServerConfigId: 'a' }
  const replaced = []
  const view = bind(collectionDef, { collection: { id: 'c1', libraryId: 'l1' }, $store: { state }, $router: { replace: (to) => replaced.push(to) }, $eventBus: { $on() {} }, $absData: { onDataChanged: () => () => {} } })
  collectionDef.mounted.call(view)
  view.libraryChanged()
  assert.deepEqual(replaced, [])
  state.activeServerConfigId = 'b'
  view.libraryChanged()
  assert.deepEqual(replaced, ['/bookshelf/collections'])
  state.activeServerConfigId = 'a'
  state.libraries.currentLibraryId = 'l2'
  view.libraryChanged()
  assert.equal(replaced.length, 2)

  const itemSource = await read('../pages/item/_id/index.vue')
  const itemDef = componentDef(itemSource, { leavesOnLibraryChange, copyLeavesOnLibraryChange, cellularPermissionHelpers: {}, coverPresentation: {}, Dialog: {}, AbsFileSystem: {}, AbsDownloader: {} })
  const itemState = { libraries: { currentLibraryId: 'l1' }, activeServerConfigId: 'a' }
  const left = []
  const item = bind({ methods: { libraryChanged: itemDef.methods.libraryChanged }, computed: { isLocal: itemDef.computed.isLocal } }, { libraryItem: { id: 'li1', libraryId: 'l1' }, loadedServerConfigId: 'a', $store: { state: itemState }, $router: { replace: (to) => left.push(to) } })
  item.libraryChanged()
  assert.deepEqual(left, [], 'a reconnect keeps the item page')
  itemState.libraries.currentLibraryId = 'l2'
  item.libraryChanged()
  assert.deepEqual(left, ['/bookshelf'])
  assert.match(itemSource, /this\.loadedServerConfigId = this\.\$store\.state\.activeServerConfigId\n\s*this\.\$eventBus\.\$on\('library-changed'/, 'the server it was opened for')

  assert.equal(leavesOnLibraryChange({ libraries: { currentLibraryId: 'l1' }, activeServerConfigId: null }, 'l1', null), false)
  assert.equal(leavesOnLibraryChange({ libraries: { currentLibraryId: 'l1' }, activeServerConfigId: 'a' }, 'l1', null), true)
})

test('authors and latest still read again when the same library is announced after the sign-in', async () => {
  // The layout announces a sign-in without naming a library: pages that cache by library id read again
  const layout = await read('../layouts/default.vue')
  assert.match(layout, /this\.\$eventBus\.\$emit\('library-changed'\)/)
  const data = await read('../utils/dataService.js')
  assert.doesNotMatch(data, /\$emit\('library-changed', /)

  const authorsDef = componentDef(await read('../pages/bookshelf/authors.vue'), { shouldRefreshShelf, createDebouncer, isReplicaIncomplete, window: { innerWidth: 412 } })
  const reads = []
  const authors = bind(authorsDef, {
    $store: { state: { libraries: { currentLibraryId: 'l1' }, activeServerConfigId: 'a', serverState: 'online' }, getters: { 'libraries/getCurrentLibraryMediaType': 'book', getHasActiveServer: true } },
    $absData: { getAuthors: async (args) => (reads.push(args), { serverConfigId: 'a', results: [], total: 0, replica: replica() }), onDataChanged: () => () => {} },
    $eventBus: { $emit() {}, $on() {}, $off() {} },
    $router: { replace() {} }
  })
  await authors.init()
  const before = reads.length
  authors.libraryChanged()
  await flush()
  assert.ok(reads.length > before, 'authors read again, now able to fall back to the server')

  const latestDef = componentDef(await read('../pages/bookshelf/latest.vue'))
  const gets = []
  const latest = bind(latestDef, {
    $store: { state: { libraries: { currentLibraryId: 'pods' } }, getters: { 'libraries/getCurrentLibraryMediaType': 'podcast' } },
    $nativeHttp: { get: async (path) => (gets.push(path), gets.length === 1 ? Promise.reject(new Error('offline')) : { episodes: [], total: 0 }) },
    $toast: { error() {} },
    $router: { replace() {} }
  })
  await latest.loadRecentEpisodes().catch(() => {})
  latest.libraryChanged()
  await flush()
  assert.equal(gets.length, 2, 'the failed offline load is tried again after the sign-in')
})

test('the offline label of the create button is its own line and a disabled row button looks disabled', async () => {
  const create = modalSource.slice(modalSource.indexOf('<!-- create playlist btn -->'), modalSource.indexOf('</modals-fullscreen-modal>'))
  const button = create.slice(create.indexOf('<ui-btn'), create.indexOf('</ui-btn>'))
  assert.doesNotMatch(button, /requires-server-label/, 'not inside the button, where it ran into its text')
  assert.match(create.slice(create.indexOf('</ui-btn>')), /<p v-if="!isServerOnline" class="requires-server-label[^"]*text-center[^"]*">/)
  const row = await read('../components/modals/playlists/PlaylistRow.vue')
  assert.equal(row.match(/:disabled="disabled" class="w-full" :class="disabled \? 'opacity-50' : ''"/g)?.length, 2)
})

test('the catalog-partial note stays pinned over every row', async () => {
  const shelf = await read('../components/bookshelf/LazyBookshelf.vue')
  // assets/app.css gives #bookshelf one screen of height: the sticky note left with it after one screen of rows
  const css = await read('../assets/app.css')
  const rule = css.match(/#bookshelf \{([^}]*)\}/)[1]
  assert.doesNotMatch(rule, /(^|[^-])height:/, 'no fixed height: one screen of it left the sticky note behind after one screen of rows')
  assert.match(shelf, /<div id="bookshelf" class="w-full max-w-full min-h-full">/, 'the box grows with the rows')
  assert.match(modalSource, /<div v-if="!showPlaylistNameInput" class="[^"]*min-h-20 pt-2 pb-2[^"]*">/, 'the create button and its label are not cut off')
})

test('a downloaded copy\'s page stays on a reconnect and leaves only for another server', async () => {
  const itemDef = componentDef(await read('../pages/item/_id/index.vue'), { leavesOnLibraryChange, copyLeavesOnLibraryChange, cellularPermissionHelpers: {}, coverPresentation: {}, Dialog: {}, AbsFileSystem: {}, AbsDownloader: {} })
  const page = (copy) => {
    const state = { libraries: { currentLibraryId: 'l1' }, activeServerConfigId: 'a' }
    const left = []
    // A downloaded copy has no libraryId
    const view = bind({ methods: { libraryChanged: itemDef.methods.libraryChanged }, computed: { isLocal: itemDef.computed.isLocal } }, { libraryItem: copy, loadedServerConfigId: 'a', $store: { state }, $router: { replace: (to) => left.push(to) } })
    return { view, state, left }
  }
  const own = page({ id: 'local_1', isLocal: true, libraryItemId: 'li1', serverConnectionConfigId: 'a' })
  own.view.libraryChanged()
  own.state.libraries.currentLibraryId = 'l2'
  own.view.libraryChanged()
  assert.deepEqual(own.left, [], 'a reconnect or another library: the copy plays from the device')
  own.state.activeServerConfigId = 'b'
  own.view.libraryChanged()
  assert.deepEqual(own.left, ['/bookshelf'], "another server: the copy isn't its")

  const fromB = page({ id: 'local_2', isLocal: true, libraryItemId: 'li2', serverConnectionConfigId: 'b' })
  fromB.state.activeServerConfigId = 'b'
  fromB.view.libraryChanged()
  assert.deepEqual(fromB.left, [], "switched to the copy's own server")
  const media = page({ id: 'local_3', isLocal: true })
  media.state.activeServerConfigId = 'b'
  media.view.libraryChanged()
  assert.deepEqual(media.left, [], 'Local Media belongs to no server')

  const forgotten = page({ id: 'local_4', isLocal: true, libraryItemId: 'li4', serverConnectionConfigId: 'a' })
  forgotten.state.activeServerConfigId = null
  forgotten.view.libraryChanged()
  assert.deepEqual(forgotten.left, [], 'no active server: nothing to confuse the copy with')

  // A copy of B opened while A is active: B is its own, then A again is not
  const back = page({ id: 'local_5', isLocal: true, libraryItemId: 'li5', serverConnectionConfigId: 'b' })
  back.state.activeServerConfigId = 'b'
  back.view.libraryChanged()
  assert.deepEqual(back.left, [])
  back.state.activeServerConfigId = 'a'
  back.view.libraryChanged()
  assert.deepEqual(back.left, ['/bookshelf'], 'back to A after B: leaves')
})

test('a collection or playlist without a library id leaves for another library', async () => {
  // The replica writes '' for a missing libraryId
  const playlist = playlistPage({ absData: createDataService({ plugin: fakePlugin(), store: fakeStore() }) })
  const view = await playlist.mount()
  view.playlist = { ...view.playlist, libraryId: '' }
  playlist.store.state.libraries.currentLibraryId = 'l2'
  view.libraryChanged()
  assert.deepEqual(playlist.replaced, ['/bookshelf/playlists'])
  const collectionDef = componentDef(await read('../pages/collection/_id.vue'), { leavesOnLibraryChange, trackCount, createDebouncer, downloadMissingItems() {}, buildQueueSourceItems() {}, requiresServer })
  const replaced = []
  const collection = bind(collectionDef, { collection: { id: 'c1', libraryId: '' }, $store: { state: { libraries: { currentLibraryId: 'l2' }, activeServerConfigId: 'a' } }, $router: { replace: (to) => replaced.push(to) }, $eventBus: { $on() {} }, $absData: { onDataChanged: () => () => {} } })
  collectionDef.mounted.call(collection)
  collection.libraryChanged()
  assert.deepEqual(replaced, ['/bookshelf/collections'])
})
