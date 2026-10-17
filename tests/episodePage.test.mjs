// Podcast episodes from the replica (offline-first release 6): the episode page reads its podcast through
// $absData.getLibraryItem, "Latest" through $absData.getRecentEpisodes, and offline an episode plays only when it is
// downloaded; server actions of the episode rows are disabled. Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const requiresServer = (await importSource('../mixins/requiresServer.js')).default
const { findLocalCopy } = await importSource('../utils/localCopy.js')
const { isCopyOfActiveServer } = await importSource('../utils/progressTarget.js')
const podcastEpisodes = await importSource('../utils/podcastEpisodes.js')
const { createDebouncer } = await importSource('../utils/catalogRefresh.js')
const templateOf = (source) => source.match(/<template>([\s\S]*)<\/template>/)[1]
const flush = async (n = 30) => {
  for (let i = 0; i < n; i++) await Promise.resolve()
}
const strings = { LabelRequiresServer: 'Requires the server', LabelNotAvailableOffline: 'Not available offline', MessageCatalogNotLoaded: 'Catalog not loaded', ToastEpisodeNotAvailable: 'Not available yet' }

/** A component's options with every import stubbed unless given in `deps`. */
async function load(path, deps = {}) {
  const script = (await read(path)).match(/<script>([\s\S]*?)<\/script>/)[1]
  const names = [...script.matchAll(/^import\s+(?:\{([^}]+)\}|(\w+))\s+from/gm)].flatMap((m) => (m[1] ? m[1].split(',').map((n) => n.split(' as ').pop().trim()) : [m[2]]))
  const known = { requiresServer, findLocalCopy, isCopyOfActiveServer, ...podcastEpisodes }
  const sandbox = { console: { ...console, log() {}, error() {}, warn() {} }, setTimeout: () => 0, ...Object.fromEntries(names.map((n) => [n, n in known ? known[n] : {}])), ...deps }
  vm.runInNewContext(script.replace(/^import .*$/gm, '').replace('export default', 'globalThis.component ='), sandbox)
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

const fakeTimers = () => {
  const timers = []
  return {
    createDebouncer: (options) => createDebouncer({ ...options, setTimer: (fn, ms) => (timers.push({ fn, ms }), timers.at(-1)), clearTimer: (t) => timers.includes(t) && timers.splice(timers.indexOf(t), 1) }),
    run: async () => {
      const due = timers.splice(0)
      due.forEach((t) => t.fn())
      await flush()
      return due.map((t) => t.ms)
    }
  }
}

const storeOf = ({ serverState = 'offline', activeServerConfigId = 'a', user = null } = {}) => ({
  state: { serverState, activeServerConfigId, user: { user, serverConnectionConfig: user ? { id: activeServerConfigId } : null, settings: {} }, libraries: { currentLibraryId: 'l1' }, playerIsPlaying: false, playerIsStartingPlayback: false, isCasting: false, lastItemScrollData: {} },
  getters: {
    getIsServerOnline: serverState === 'online',
    getHasActiveServer: activeServerConfigId != null,
    'user/getUserCanDownload': true,
    'user/getUserMediaProgress': () => null,
    'user/getIsAdminOrUp': true,
    'globals/getLocalMediaProgressById': () => null,
    'globals/getDownloadItem': () => null,
    'globals/getLibraryItemCoverSrcById': () => '',
    'libraries/getBookCoverAspectRatio': 1.6,
    'libraries/getCurrentLibraryMediaType': 'podcast',
    getIsMediaStreaming: () => false
  },
  commits: [],
  commit(name, payload) {
    this.commits.push([name, payload])
  },
  dispatch: async () => {}
})

const episode = (id, extra = {}) => ({ id, libraryItemId: 'pod1', title: `Episode ${id}`, duration: 60, audioFile: { metadata: {} }, ...extra })
const podcast = (episodes = [episode('e1'), episode('e2')]) => ({ id: 'pod1', ino: 'ino-pod1', libraryId: 'l1', mediaType: 'podcast', media: { metadata: { title: 'Pod', author: 'A' }, episodes } })
const podcastCopy = (serverEpisodeIds = ['e1']) => ({ id: 'local_pod1', libraryItemId: 'pod1', serverConnectionConfigId: 'a', mediaType: 'podcast', isLocal: true, media: { episodes: serverEpisodeIds.map((sid) => ({ id: `local_${sid}`, serverEpisodeId: sid, audioTrack: {} })) } })
const answerOf = (libraryItem, detailLevel = 'expanded') => ({ serverConfigId: 'a', libraryItem, detailLevel, replica: { reconciledAt: 5 } })

/** pages/item/_id/_episode/index.vue: asyncData over fakes, then the page bound to the same store. */
async function episodePage({ episodeId = 'e1', answers, localItems = [], ...storeOptions }) {
  const def = await load('../pages/item/_id/_episode/index.vue')
  const store = storeOf(storeOptions)
  const reads = []
  let changed = null
  const absData = {
    getLibraryItem: async (args) => (reads.push({ ...args }), structuredClone(answers.length > 1 ? answers.shift() : answers[0])),
    onDataChanged: (cb) => ((changed = cb), () => (changed = null))
  }
  const db = { getLocalLibraryItems: async () => localItems, getLocalLibraryItem: async () => null }
  let redirected = null
  const data = await def.asyncData({ store, params: { id: 'pod1', episode: episodeId }, redirect: (to) => (redirected = to), app: { $absData: absData, $db: db } })
  const emitted = []
  const view = redirected
    ? null
    : bind(def, { ...(data || {}), $store: store, $absData: absData, $db: db, $strings: strings, $eventBus: { $on() {}, $off() {}, $emit: (n, p) => emitted.push([n, p]) }, $hapticsImpact: async () => {}, $router: { replace() {} }, $toast: { error() {}, info() {} } })
  return { view, reads, emitted, store, get redirected() { return redirected }, change: (ids) => changed?.({ scope: 'catalog', ids }), get subscribed() { return !!changed } }
}

test('podcast episode page opens offline from the replica', async () => {
  const page = await episodePage({ answers: [answerOf(podcast())] })
  assert.equal(page.redirected, null, 'no redirect to Home without a live user')
  assert.equal(page.view.episode.id, 'e1')
  assert.deepEqual(page.reads, [{ id: 'pod1' }], 'the replica, no server read offline')
  assert.equal(page.view.notAvailableOffline, true, 'not downloaded')
})

test('a new episode online refreshes the podcast; offline goes to the podcast', async () => {
  const online = await episodePage({ episodeId: 'e3', serverState: 'online', user: { id: 'u1' }, answers: [answerOf(podcast()), answerOf(podcast([episode('e1'), episode('e3')]))] })
  assert.deepEqual(online.reads, [{ id: 'pod1' }, { id: 'pod1', refresh: true, force: true }])
  assert.equal(online.redirected, null)
  assert.equal(online.view.episode.id, 'e3')

  const stillMissing = await episodePage({ episodeId: 'e3', serverState: 'online', user: { id: 'u1' }, answers: [answerOf(podcast())] })
  assert.equal(stillMissing.redirected, '/item/pod1?error=episode')

  const offline = await episodePage({ episodeId: 'e3', answers: [answerOf(podcast())] })
  assert.deepEqual(offline.reads, [{ id: 'pod1' }], 'no refresh offline')
  assert.equal(offline.redirected, '/item/pod1?error=episode')

  const gone = await episodePage({ episodeId: 'e1', answers: [answerOf(null, null)] })
  assert.equal(gone.redirected, '/item/pod1?error=episode')
})

test('a partly downloaded podcast offline', async () => {
  const localItems = [podcastCopy(['e1'])]
  const downloaded = await episodePage({ episodeId: 'e1', answers: [answerOf(podcast())], localItems })
  assert.equal(downloaded.view.notAvailableOffline, false)
  await downloaded.view.playClick()
  assert.equal(downloaded.emitted[0][1].episodeId, 'local_e1')

  const other = await episodePage({ episodeId: 'e2', answers: [answerOf(podcast())], localItems })
  assert.equal(other.view.notAvailableOffline, true, "the podcast's copy does not make e2 playable")
  await other.view.playClick()
  assert.deepEqual(other.emitted, [])
  assert.match(templateOf(await read('../pages/item/_id/_episode/index.vue')), /<ui-btn color="success"[^>]*:disabled="notAvailableOffline && !playerIsPlaying"[^>]*@click="playClick"/)

  // The podcast's Play (next episode) chooses only among the downloaded episodes
  const itemPage = async (copies) => {
    const def = await load('../pages/item/_id/index.vue', { createDebouncer })
    const store = storeOf()
    const libraryItem = { ...podcast(), localLibraryItem: copies[0] || null }
    const emitted = []
    const view = bind(def, { libraryItem, libraryItemId: 'pod1', detailLevel: 'expanded', $store: store, $eventBus: { $emit: (n, p) => emitted.push([n, p]) }, $strings: strings })
    return { view, emitted }
  }
  const partly = await itemPage(localItems)
  assert.deepEqual(partly.view.playableEpisodes.map((e) => e.id), ['e1'])
  assert.equal(partly.view.playDisabled, false)
  await partly.view.play()
  assert.equal(partly.emitted[0][1].episodeId, 'local_e1')
  const none = await itemPage([])
  assert.equal(none.view.playDisabled, true)
  await none.view.play()
  assert.deepEqual(none.emitted, [])
})

/** pages/bookshelf/latest.vue over fakes. */
async function latestPage({ answers, localItems = [], serverState = 'offline' }) {
  const timers = fakeTimers()
  const def = await load('../pages/bookshelf/latest.vue', { createDebouncer: timers.createDebouncer })
  const store = storeOf({ serverState })
  const reads = []
  const http = []
  const toasts = []
  let changed = null
  const view = bind(def, {
    $store: store,
    $absData: {
      getRecentEpisodes: async (args) => {
        reads.push({ ...args })
        const answer = answers.length > 1 ? answers.shift() : answers[0]
        if (answer instanceof Error) throw answer
        return structuredClone(answer)
      },
      getLibraryItem: async ({ id }) => answerOf({ ...podcast(), id }),
      onDataChanged: (cb) => ((changed = cb), () => (changed = null))
    },
    $nativeHttp: { get: async (path) => (http.push(path), {}) },
    $db: { getLocalLibraryItems: async () => localItems },
    $eventBus: { $on() {}, $off() {} },
    $toast: { error: (m) => toasts.push(m) },
    $router: { replace() {} },
    $strings: strings
  })
  return { view, def, reads, http, toasts, timers, store, change: (ids) => changed?.({ scope: 'catalog', ids }), get subscribed() { return !!changed } }
}

const recent = (episodes, extra = {}) => ({ serverConfigId: 'a', episodes, total: episodes.length, limit: 50, page: 0, replica: { reconciledAt: 5 }, ...extra })

test('latest episodes come from the replica and survive a failed read', async () => {
  const page = await latestPage({ answers: [recent([episode('e1'), episode('e2')])] })
  await page.view.loadRecentEpisodes()
  assert.equal(page.view.recentEpisodes.length, 2)
  assert.deepEqual(page.reads, [{ libraryId: 'l1', limit: 50, page: 0 }])
  assert.deepEqual(page.http, [])

  const failed = await latestPage({ answers: [new Error('bridge')] })
  await failed.view.loadRecentEpisodes()
  assert.equal(failed.view.recentEpisodes.length, 0)
  assert.equal(failed.toasts.length, 1)

  // A replica whose episodes were never read says so instead of "nothing"
  const pending = await latestPage({ answers: [recent([], { replica: { reconciledAt: null } })] })
  await pending.view.loadRecentEpisodes()
  assert.equal(pending.view.catalogNotLoaded, true)
  assert.match(templateOf(await read('../pages/bookshelf/latest.vue')), /v-if="catalogNotLoaded"[^>]*>\{\{ \$strings\.MessageCatalogNotLoaded \}\}/)

  // Only the active server's copies are joined; "add to playlist" takes the item from $absData, not the server
  const joined = await latestPage({ answers: [recent([episode('e1')])], localItems: [{ ...podcastCopy(['e1']), serverConnectionConfigId: 'b' }] })
  await joined.view.loadLocalPodcastLibraryItems()
  assert.equal(joined.view.localEpisodeMap.e1, undefined, "another server's copy")
  joined.store.state.serverState = 'online'
  joined.store.getters.getIsServerOnline = true
  await joined.view.addEpisodeToPlaylist(episode('e1'))
  assert.deepEqual(joined.http, [])
  assert.equal(joined.store.commits.find(([name]) => name === 'globals/setSelectedPlaylistItems')[1][0].libraryItem.id, 'pod1')
})

test('an open page re-reads after the episodes topic changes', async () => {
  const page = await latestPage({ answers: [recent([episode('e1')]), recent([episode('e1'), episode('e2')])] })
  page.def.mounted.call(page.view)
  await flush()
  assert.equal(page.subscribed, true)
  page.change(['episodes:l2'])
  assert.deepEqual(await page.timers.run(), [])
  page.change(['episodes:l1'])
  assert.deepEqual(await page.timers.run(), [1000])
  assert.equal(page.view.recentEpisodes.length, 2)
  page.def.beforeDestroy.call(page.view)
  assert.equal(page.subscribed, false)

  // The podcast page follows its library's episodes too
  const timers = fakeTimers()
  const def = await load('../pages/item/_id/index.vue', { createDebouncer: timers.createDebouncer })
  const reads = []
  let changed = null
  const item = bind(def, {
    libraryItem: podcast(),
    libraryItemId: 'pod1',
    detailLevel: 'expanded',
    $store: storeOf(),
    $absData: { getLibraryItem: async (args) => (reads.push({ ...args }), answerOf(podcast([episode('e1'), episode('e2'), episode('e3')]))), onDataChanged: (cb) => ((changed = cb), () => (changed = null)) },
    $db: { getLocalLibraryItems: async () => [] }
  })
  item.stopDataChanged = item.$absData.onDataChanged(item.dataChanged)
  changed({ scope: 'catalog', ids: ['episodes:l1'] })
  assert.deepEqual(await timers.run(), [1000])
  assert.deepEqual(reads, [{ id: 'pod1' }])
  assert.equal(item.libraryItem.media.episodes.length, 3)
})

/** An episode row's computed values and handlers over a fake store. */
async function row(path, { serverState, localEpisode = null }) {
  const downloads = []
  const def = await load(path, { AbsDownloader: { downloadLibraryItem: async (p) => (downloads.push(p), {}) }, cellularPermissionHelpers: { methods: { checkCellularPermission: async () => true } }, CellularPermissionHelpers: { methods: { checkCellularPermission: async () => true } }, appStorageFolderFor: async () => ({ id: 'app' }), safFoldersFor: () => [], WAITING_LABEL_KEYS: {} })
  const emitted = []
  const view = bind(def, {
    libraryItemId: 'pod1',
    episode: episode('e1', { podcast: { metadata: { title: 'Pod' } } }),
    localEpisode,
    localLibraryItemId: localEpisode ? 'local_pod1' : null,
    isLocal: false,
    $store: storeOf({ serverState }),
    $strings: strings,
    $eventBus: { $emit: (n, p) => emitted.push([n, p]) },
    $emit: (n, p) => emitted.push([n, p]),
    $hapticsImpact: async () => {},
    $db: { getLocalFolders: async () => [] },
    $toast: { error() {} },
    $platform: 'android'
  })
  return { view, emitted, downloads }
}

test('episode rows disable server actions offline', async () => {
  for (const path of ['../components/tables/podcast/EpisodeRow.vue', '../components/tables/podcast/LatestEpisodeRow.vue']) {
    const offline = await row(path, { serverState: 'offline' })
    assert.equal(offline.view.notAvailableOffline, true, path)
    await offline.view.playClick()
    offline.view.addToPlaylist()
    await offline.view.downloadClick()
    assert.deepEqual(offline.emitted, [], `${path}: no play, no playlist offline`)
    assert.deepEqual(offline.downloads, [], `${path}: no download offline`)

    const downloaded = await row(path, { serverState: 'offline', localEpisode: { id: 'local_e1', serverEpisodeId: 'e1' } })
    assert.equal(downloaded.view.notAvailableOffline, false, path)
    await downloaded.view.playClick()
    assert.equal(downloaded.emitted[0][1].episodeId, 'local_e1', path)

    const online = await row(path, { serverState: 'online' })
    online.view.addToPlaylist()
    await online.view.downloadClick()
    await flush()
    assert.equal(online.emitted[0][0], 'addToPlaylist', path)
    assert.equal(online.downloads.length, 1, path)

    const template = templateOf(await read(path))
    assert.match(template, /class="episode-play-control[^"]*"[^>]*:class="[^"]*notAvailableOffline[^"]*"[^>]*:aria-disabled="notAvailableOffline/, `${path}: play`)
    assert.match(template, /<button v-if="!isLocal"[^>]*:disabled="!isServerOnline"[^>]*@click\.stop="addToPlaylist"/, `${path}: playlist`)
  }
})
