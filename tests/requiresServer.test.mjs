// Server-only actions offline (offline-first release 6): every action that only the server can carry out stays visible,
// disabled and labeled "Requires the server", and its handler does nothing; the "not available offline" rule for server
// items lives in mixins/requiresServer.js. Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const requiresServer = (await importSource('../mixins/requiresServer.js')).default
const strings = { LabelRequiresServer: 'Requires the server', LabelNotAvailableOffline: 'Not available offline' }

const scriptOf = (source) => source.match(/<script>([\s\S]*?)<\/script>/)[1]
const templateOf = (source) => source.match(/<template>([\s\S]*)<\/template>/)[1]

/** A component's options object with its imports given as `deps`. */
function componentDef(source, deps = {}) {
  const script = scriptOf(source).replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =')
  const sandbox = { console: { ...console, log() {}, error() {}, warn() {} }, setTimeout: () => 0, ...deps }
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

const storeOf = (serverState) => ({ state: { serverState }, getters: { getIsServerOnline: serverState === 'online' } })

test('requiresServer mixin: a server known to be away, and the not-available-offline rule', () => {
  const mixin = (serverState) => bind({ mixins: [requiresServer] }, { $store: storeOf(serverState), $strings: strings })
  assert.equal(mixin('offline').isServerAway, true)
  assert.equal(mixin('unauthorized').isServerAway, true)
  assert.equal(mixin('unknown').isServerAway, false, 'not while the first check is pending')
  assert.equal(mixin('online').isServerAway, false)

  const offline = mixin('offline')
  const item = { id: 'li1' }
  assert.equal(offline.isNotAvailableOffline(item, null), true)
  assert.equal(offline.isNotAvailableOffline({ id: 'local_1', isLocal: true }, null), false, 'a local item')
  assert.equal(offline.isNotAvailableOffline(item, { id: 'local_1' }), false, 'a downloaded copy')
  // An episode needs this episode downloaded, not the podcast's other ones
  const copy = { id: 'local_1', media: { episodes: [{ id: 'lep1', serverEpisodeId: 'ep1' }] } }
  assert.equal(offline.isNotAvailableOffline(item, copy, 'ep1'), false)
  assert.equal(offline.isNotAvailableOffline(item, copy, 'ep2'), true)
  assert.equal(offline.isNotAvailableOffline(item, { id: 'local_1', media: {} }, 'ep1'), true)
  assert.equal(mixin('online').isNotAvailableOffline(item, null), false)
  assert.equal(mixin('unknown').isNotAvailableOffline(item, null, 'ep1'), false)
  assert.equal(offline.notAvailableOfflineLabel(), 'Not available offline')
  assert.equal(offline.requiresServerLabel(), 'Requires the server')
})

test('requiresServer mixin works on an instance without $store', () => {
  const view = bind({ mixins: [requiresServer] }, { $nuxt: { $store: storeOf('offline') }, $strings: strings })
  assert.equal(view.isServerOnline, false)
  assert.equal(view.isServerAway, true)
  assert.equal(view.isNotAvailableOffline({ id: 'li1' }, null), true)
})

/**
 * Every server action: `bind` — how the element is tied to the server state in the file (by element type: `:disabled`
 * on buttons, a disabled class and aria-disabled on icons, `disabled`/`subtext` on menu items), `gates` — handlers that
 * leave at once while the server is not online, `label` — whether the file shows the reason itself (menu items carry it
 * in `serverOnlyItem`).
 */
const SERVER_ACTIONS = [
  {
    file: 'pages/item/_id/_episode/index.vue',
    bind: [/<ui-btn v-if="showDownload"[^>]*:disabled="!isServerOnline"/, /value: 'playlist',[^}]*\.\.\.this\.serverOnlyItem/, /value: 'remove_from_server',[^}]*\.\.\.this\.serverOnlyItem/],
    gates: ['downloadClick', 'deleteEpisodeFromServerClick']
  },
  ...['components/tables/podcast/EpisodeRow.vue', 'components/tables/podcast/LatestEpisodeRow.vue'].map((file) => ({
    file,
    bind: [/<button v-if="!isLocal"[^>]*:disabled="!isServerOnline"[^>]*@click\.stop="addToPlaylist"/, /\{ 'opacity-50': !isServerOnline \}\]" :aria-disabled="!isServerOnline"[^>]*@click\.stop="downloadClick"/],
    gates: ['addToPlaylist', 'downloadClick']
  })),
  {
    file: 'components/tables/podcast/EpisodesTable.vue',
    bind: [/:class="\{ 'opacity-50': !isServerOnline \}" :aria-disabled="!isServerOnline"[^>]*@click="clearDownloadQueue"/, /<button v-if="isAdminOrUp && !fetchingRSSFeed"[^>]*:disabled="!isServerOnline"[^>]*@click="searchEpisodes"/],
    gates: ['clearDownloadQueue', 'searchEpisodes', 'addEpisodeToPlaylist']
  },
  { file: 'pages/bookshelf/latest.vue', bind: [], gates: ['addEpisodeToPlaylist'], label: false },
  { file: 'pages/item/_id/index.vue', bind: [/<ui-btn v-if="showDownload"[^>]*:disabled="!isServerOnline && !downloadItem"/], gates: ['downloadClick'] },
  {
    file: 'components/modals/ItemMoreMenuModal.vue',
    bind: [/value: 'sendEbook',[^}]*\.\.\.this\.serverOnlyItem/, /value: 'rssFeed',[^}]*\.\.\.this\.serverOnlyItem/, /value: 'openWebClient',[^}]*\.\.\.this\.serverOnlyItem/],
    gates: ['sendEbookToDeviceAction', 'clickRSSFeed', 'openWebClient'],
    label: false
  },
  {
    file: 'components/modals/rssfeeds/RssFeedModal.vue',
    bind: [/<ui-btn v-if="currentFeed"[^>]*:disabled="!isServerOnline"[^>]*@click="closeFeed"/, /<ui-btn v-else[^>]*:disabled="!isServerOnline"[^>]*@click="openFeed"/],
    gates: ['openFeed', 'closeFeed']
  },
  { file: 'components/tables/ebook/EbookFilesTable.vue', bind: [/'updateStatus' \? \{ \.\.\.item, \.\.\.this\.serverOnlyItem \}/], gates: ['updateEbookStatus'], label: false },
  { file: 'pages/stats.vue', bind: [/v-if="!isServerOnline"[^>]*>\{\{ requiresServerLabel\(\) \}\}/], gates: ['init'] },
  {
    file: 'components/stats/YearInReviewBanner.vue',
    bind: [/<ui-btn[^>]*:disabled="!isServerOnline[^"]*"[^>]*@click\.stop="clickShowYearInReview"/],
    gates: ['clickShowYearInReview', 'refreshYearInReview', 'refreshYearInReviewServer']
  },
  { file: 'components/stats/YearInReview.vue', bind: [], gates: ['init'], label: false },
  { file: 'components/stats/YearInReviewServer.vue', bind: [], gates: ['init'], label: false },
  { file: 'components/stats/YearInReviewShort.vue', bind: [], gates: ['init'], label: false },
  { file: 'pages/bookshelf/add-podcast.vue', bind: [/:disabled="processing \|\| !isServerOnline"/, /v-if="!isServerOnline"[^>]*>[^<]*\{\{ requiresServerLabel\(\) \}\}/], gates: ['submit', 'selectPodcast'] },
  { file: 'components/forms/NewPodcastForm.vue', bind: [/<ui-btn[^>]*:disabled="!isServerOnline"[^>]*@click="submit"/], gates: ['submit'] },
  { file: 'components/modals/PodcastEpisodesFeedModal.vue', bind: [/<ui-btn[^>]*:disabled="[^"]*!isServerOnline[^"]*"[^>]*@click\.stop="downloadEpisodes"/], gates: ['downloadEpisodes'] },
  { file: 'components/home/BookshelfToolbar.vue', bind: [/<span v-if="seriesBookPage"[^>]*:class="\{ 'opacity-50': !isServerOnline \}"[^>]*:aria-disabled="!isServerOnline"[^>]*@click="downloadSeries">download/], gates: ['downloadSeries'] },
  { file: 'pages/bookshelf/series/_id.vue', bind: [], gates: ['downloadSeriesClick', 'onDownloadOrStreamDownloadAndPlay'], label: false },
  { file: 'pages/collection/_id.vue', bind: [], gates: ['onDownloadOrStreamDownloadAndPlay'], label: false },
  { file: 'pages/playlist/_id.vue', bind: [], gates: ['onDownloadOrStreamDownloadAndPlay'], label: false },
  { file: 'components/modals/DownloadOrStreamModal.vue', bind: [/value: 'downloadAndPlay',[^}]*\.\.\.this\.serverOnlyItem/], gates: [], label: false },
  { file: 'pages/downloading.vue', bind: [/:disabled="!isServerOnline && action === 'retry'"/], gates: ['retry'] },
  { file: 'components/app/SideDrawer.vue', bind: [/:disabled="item\.requiresServer && !isServerOnline"/, /requiresServer: true/], gates: ['openWebClient'] }
]

/** The handler's first statement leaves while the server is not online (a comment line may come first). */
const gated = (script, name) => new RegExp(`\\n\\s+(?:async )?${name}\\([^)]*\\) \\{\\n(?:\\s*//[^\\n]*\\n)?\\s*if \\(!this\\.isServerOnline\\b`).test(script)

test('every server action is disabled and labeled offline', async () => {
  for (const { file, bind: bindings, gates, label = true } of SERVER_ACTIONS) {
    const source = await read(`../${file}`)
    const script = scriptOf(source)
    assert.match(script, /^import requiresServer from '@\/mixins\/requiresServer'$/m, `${file}: imports the mixin`)
    assert.match(script, /mixins: \[[^\]]*\brequiresServer\b/, `${file}: uses the mixin`)
    for (const binding of bindings) assert.match(source, binding, `${file}: ${binding}`)
    for (const name of gates) assert.ok(gated(script, name), `${file}: ${name} leaves at once without the server`)
    if (label) assert.match(source, /requiresServerLabel\(\)/, `${file}: says why`)
  }
})

test('server actions do nothing offline when invoked', async () => {
  for (const serverState of ['offline', 'online']) {
    const online = serverState === 'online'
    const calls = []
    const http = {
      get: async (path) => (calls.push(['get', path]), {}),
      post: async (path) => (calls.push(['post', path]), { podcast: { episodes: [] } }),
      patch: async (path) => (calls.push(['patch', path]), {})
    }
    const common = { $store: { ...storeOf(serverState), getters: { ...storeOf(serverState).getters, 'libraries/getCurrentLibrary': { id: 'l1', folders: [] } } }, $strings: strings, $nativeHttp: http, $toast: { success() {}, error() {}, info() {} }, $emit() {}, $hapticsImpact: async () => {} }

    const menu = bind(componentDef(await read('../components/modals/ItemMoreMenuModal.vue'), { requiresServer, progressTarget: () => null, isCopyOfActiveServer: () => false, Dialog: {}, AbsFileSystem: {} }), { ...common, libraryItem: { id: 'li1', media: {} } })
    menu.sendEbookToDeviceAction('Kindle')

    const ebooks = bind(componentDef(await read('../components/tables/ebook/EbookFilesTable.vue'), { requiresServer }), { ...common, libraryItem: { id: 'li1' } })
    ebooks.selectedFile = { ino: 'ino1' }
    ebooks.updateEbookStatus()

    const form = bind(componentDef(await read('../components/forms/NewPodcastForm.vue'), { requiresServer, Path: { join: (...p) => p.join('/') } }), { ...common, $sanitizeFilename: (s) => s })
    form.submit()

    const stats = bind(componentDef(await read('../pages/stats.vue'), { requiresServer }), { ...common })
    await stats.init()

    const downloads = []
    const series = bind(componentDef(await read('../pages/bookshelf/series/_id.vue'), { requiresServer, cellularPermissionHelpers: { methods: { checkCellularPermission: async () => true } }, Dialog: {}, AbsDownloader: {}, buildQueueSourceItems() {}, downloadMissingItems: () => downloads.push('missing'), appStorageFolderFor() {}, safFoldersFor() {} }), { ...common })
    series.download = () => downloads.push('series')
    await series.downloadSeriesClick()
    series.startSeriesPlayback = () => {}
    series.onDownloadOrStreamDownloadAndPlay()

    const episodePage = bind(componentDef(await read('../pages/item/_id/_episode/index.vue'), { requiresServer, cellularPermissionHelpers: {}, progressTarget: () => null, isCopyOfActiveServer: () => true, Capacitor: {}, Dialog: { confirm: async () => ({ value: true }) }, AbsFileSystem: {}, AbsDownloader: {}, appStorageFolderFor() {}, safFoldersFor() {}, findLocalCopy() {}, createDebouncer() {} }), { ...common, libraryItem: { id: 'pod1' }, episode: { id: 'e1' }, $getString: () => '', $router: { replace() {} }, $nativeHttp: { ...http, delete: async (path) => (calls.push(['delete', path]), {}) } })
    await episodePage.deleteEpisodeFromServerClick()

    const episodesTable = bind(componentDef(await read('../components/tables/podcast/EpisodesTable.vue'), { requiresServer, Dialog: {}, AbsDownloader: {}, podcastSortOverrideKey() {}, resolvePodcastEpisodeSort() {}, sortPodcastEpisodes() {} }), { ...common, libraryItem: { id: 'pod1', media: { metadata: { feedUrl: 'https://feed' } } } })
    await episodesTable.searchEpisodes()

    const retried = []
    const downloading = bind(componentDef(await read('../pages/downloading.vue'), { requiresServer, AbsDownloader: { retryDownload: async (a) => (retried.push(a), {}) }, AbsFileSystem: {}, downloadState() {}, downloadActions() {}, downloadProgress() {}, lostFolder() {}, isWaitingState() {}, DownloadAction: {}, DownloadState: {}, isAppStorageFolderId() {} }), { ...common })
    await downloading.retry({ id: 'd1' })

    await new Promise((resolve) => setImmediate(resolve))
    const paths = calls.map(([method, path]) => `${method} ${path}`)
    const expected = ['post /api/emails/send-ebook-to-device', 'patch /api/items/li1/ebook/ino1/status', 'post /api/podcasts', 'get /api/me/listening-stats', 'delete /api/podcasts/pod1/episode/e1?hard=1', 'post /api/podcasts/feed']
    assert.deepEqual(paths, online ? expected : [], serverState)
    assert.deepEqual(downloads, online ? ['series', 'missing'] : [], serverState)
    assert.equal(retried.length, online ? 1 : 0, serverState)
  }
})
