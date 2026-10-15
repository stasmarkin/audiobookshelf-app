// The Series tab and series page on the catalog replica (offline-first 4b): $absData reads series from native
// replica.db, falls back to the server while the series were never read, and answers by id in native's shape in every
// mode; the page leaves for the Series tab when the series is gone. Fakes only, synthetic ids.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const { createDataService } = await import(`data:text/javascript;base64,${Buffer.from(await read('../utils/dataService.js')).toString('base64')}`)
const plain = (value) => JSON.parse(JSON.stringify(value))
const replica = (reconciledAt = 5) => ({ reconciledAt, complete: reconciledAt != null })

function fakePlugin(overrides = {}) {
  const calls = []
  return {
    calls,
    addListener: () => ({ remove() {} }),
    getServerState: async () => ({}),
    async getSeries(args) {
      calls.push(['getSeries', args])
      return { serverConfigId: 'a', results: [{ id: 's1', books: [] }], total: 1, replica: replica() }
    },
    async getSeriesById(args) {
      calls.push(['getSeriesById', args])
      return { serverConfigId: 'a', series: { id: args.id, name: 'Мир', libraryId: 'l1' }, replica: replica() }
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

const service = ({ plugin = fakePlugin(), store = fakeStore(), http = fakeHttp(() => ({})), platform = null, $axios = null } = {}) => ({
  plugin,
  http,
  data: createDataService({ plugin, store, platform, $axios, nativeHttp: () => http })
})

test('never-reconciled series read the server while online', async () => {
  const never = fakePlugin({
    getSeries: async () => ({ serverConfigId: 'a', results: [], total: 0, replica: replica(null) }),
    getSeriesById: async () => ({ serverConfigId: 'a', series: null, replica: replica(null) })
  })
  const http = fakeHttp((path) => (path.startsWith('/api/series/') ? { id: 's 1', name: 'Мир', libraryId: 'l1' } : { results: [{ id: 's1', books: [] }], total: 1 }))
  const online = service({ plugin: never, http })
  const list = await online.data.getSeries({ libraryId: 'l1', sort: 'name', desc: false, limit: 20, page: 1, sortingIgnorePrefix: true })
  assert.equal(http.paths[0], '/api/libraries/l1/series?sort=name&desc=0&limit=20&page=1&minified=1&include=rssfeed,numEpisodesIncomplete')
  assert.equal(list.fromServer, true)
  assert.deepEqual(plain(list.results), [{ id: 's1', books: [] }])
  assert.equal(list.replica.reconciledAt, null, "native's replica state is kept")

  const byId = await online.data.getSeriesById({ id: 's 1' })
  assert.equal(http.paths[1], '/api/series/s%201')
  assert.deepEqual(plain(byId), { series: { id: 's 1', name: 'Мир', libraryId: 'l1' }, serverConfigId: 'a', replica: replica(null), fromServer: true }, "native's shape")

  const offline = service({ plugin: never, http: fakeHttp(() => assert.fail('no server offline')), store: fakeStore({ serverState: 'offline' }) })
  assert.equal((await offline.data.getSeries({ libraryId: 'l1', limit: 20, page: 0 })).total, 0)
  assert.equal((await offline.data.getSeriesById({ id: 's1' })).series, null)

  // Read once: the replica answers, the server is not asked
  const read = service({ http: fakeHttp(() => assert.fail('a read replica does not go to the server')) })
  assert.equal((await read.data.getSeriesById({ id: 's1' })).series.name, 'Мир')
  assert.deepEqual(read.plugin.calls[0], ['getSeriesById', { id: 's1' }])
  await read.data.getSeries({ libraryId: 'l1', sort: 'name', limit: 20, page: 0, sortingIgnorePrefix: true })
  assert.deepEqual(plain(read.plugin.calls[1]), ['getSeries', { libraryId: 'l1', sort: 'name', desc: false, limit: 20, page: 0, sortingIgnorePrefix: true }])
})

test('in the browser series reads go to $axios in native shape', async () => {
  const gets = []
  const $axios = {
    $get: async (path, options) => {
      gets.push([path, options?.params ?? null])
      if (path === '/api/series/gone') throw Object.assign(new Error('404'), { response: { status: 404 } })
      return path.startsWith('/api/series/') ? { id: 's1', name: 'Мир' } : { results: [], total: 0 }
    }
  }
  const { data, plugin } = service({ platform: 'web', $axios })
  const list = await data.getSeries({ libraryId: 'l1', sort: 'name', desc: false, limit: 20, page: 0 })
  assert.equal(list.replica.complete, true)
  assert.equal((await data.getSeriesById({ id: 's1' })).series.name, 'Мир')
  assert.equal((await data.getSeriesById({ id: 'gone' })).series, null)
  assert.deepEqual(plain(gets.slice(0, 2)), [
    ['/api/libraries/l1/series', { limit: 20, page: 0, minified: 1, include: 'rssfeed,numEpisodesIncomplete', sort: 'name', desc: 0 }],
    ['/api/series/s1', null]
  ])
  assert.deepEqual(plugin.calls, [], 'native is not asked in the browser')
})

/** The series page's `asyncData` over fakes. */
async function seriesPage() {
  const source = await read('../pages/bookshelf/series/_id.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')
  return new Function('Dialog', 'AbsDownloader', 'cellularPermissionHelpers', 'buildQueueSourceItems', 'downloadMissingItems', script)({}, {}, {}, () => {}, () => {})
}

test('missing series redirects to the series tab', async () => {
  const page = await seriesPage()
  const run = async (answer) => {
    const out = { redirected: null, committed: [] }
    const result = await page.asyncData({
      params: { id: 's1' },
      app: { $absData: { getSeriesById: async () => answer() } },
      store: { commit: (name, value) => out.committed.push([name, value]) },
      redirect: (path) => (out.redirected = path)
    })
    return { ...out, result }
  }
  const missing = await run(() => ({ serverConfigId: 'a', series: null, replica: replica() }))
  assert.equal(missing.redirected, '/bookshelf/series')
  assert.deepEqual(missing.committed, [])
  const failed = await run(() => Promise.reject(new Error('bridge')))
  assert.equal(failed.redirected, '/bookshelf/series')

  const found = await run(() => ({ serverConfigId: 'a', series: { id: 's1', name: 'Мир', libraryId: 'l1' }, replica: replica() }))
  assert.equal(found.redirected, null)
  assert.deepEqual(found.committed, [['globals/setSeries', { id: 's1', name: 'Мир', libraryId: 'l1' }]])
  assert.equal(found.result.series.libraryId, 'l1')
  assert.equal(found.result.seriesId, 's1')
})

test('series tab and series page read the replica', async () => {
  const shelf = await read('../components/bookshelf/LazyBookshelf.vue')
  const fetch = shelf.slice(shelf.indexOf('async fetchEntities(page)'), shelf.indexOf('/** The downloaded copy'))
  assert.doesNotMatch(fetch, /series-books/, 'series books take the catalog path, not the server one')
  assert.match(shelf, /this\.\$absData\.getSeries\(/)
  const page = await read('../pages/bookshelf/series/_id.vue')
  const asyncData = page.slice(page.indexOf('async asyncData'), page.indexOf('data()'))
  assert.match(asyncData, /app\.\$absData\.getSeriesById\(\{ id: params\.id \}\)/)
  assert.doesNotMatch(asyncData, /\$nativeHttp|\/api\/series\//)
  assert.doesNotMatch(page, /\/oops/)
})
