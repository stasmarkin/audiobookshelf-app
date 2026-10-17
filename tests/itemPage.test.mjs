// The item page on the catalog replica (offline-first 6): $absData reads an item (book, podcast with its episodes) from
// native replica.db, which asks the server for the expanded copy only when the page asks to refresh, and the latest
// episodes of a podcast library, falling back to the server while the episodes were never read.
// Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

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
