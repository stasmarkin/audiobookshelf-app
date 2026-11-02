// Covers offline (offline-first release 7): on native, cover getters build the app's cover route, which native answers
// from its disk cache; web dev mode keeps the server URL. A failed route draws the book without a cover, not
// "Invalid Cover". Real getter and component code against fakes; synthetic ids only.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { coverSrc, isCoverRoute, shownGroupCovers } = await importSource('../utils/coverUrl.js')
const { drawnCoverIds, loadCoverImages } = await importSource('../utils/coverImages.js')
const scriptOf = (source) => source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')

const globalsSource = (await read('../store/globals.js')).replace(/^import .*$/gm, '').replace(/^export const /gm, 'const ')
const fakeVue = { set: (target, key, value) => (target[key] = value) }
const gettersFor = (native, platform = native ? 'android' : 'web') => new Function('Vue', 'Capacitor', 'coverSrc', `${globalsSource}\nreturn getters`)(fakeVue, { getPlatform: () => platform }, coverSrc)

const rootGetters = (serverAddress, tokenRequired = false) => ({ 'user/getServerAddress': serverAddress, 'user/getToken': 't', getDoesServerImagesRequireToken: tokenRequired })
const coverOf = (native, item, serverAddress, raw = false) => gettersFor(native).getLibraryItemCoverSrc({ coverEpochs: {} }, null, {}, rootGetters(serverAddress))(item, '/book_placeholder.jpg', raw)
const book = (extra = {}) => ({ id: 'li1', updatedAt: 1700, media: { coverPath: '/metadata/items/li1/cover.jpg' }, ...extra })

test('native builds the cover route', () => {
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, native: true, serverAddress: null }), '/_absplus/cover/li1?ts=1700')
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, raw: true, native: true }), '/_absplus/cover/li1?ts=1700&raw=1')
  assert.equal(coverSrc({ itemId: 'li1', native: true }), '/_absplus/cover/li1')
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, native: true, token: 't', tokenRequired: true }), '/_absplus/cover/li1?ts=1700', 'no token in the route')

  assert.equal(coverOf(true, book(), null), '/_absplus/cover/li1?ts=1700', 'offline without a server address the getter still gives the route')
  assert.equal(coverOf(false, book(), null), '/book_placeholder.jpg', 'web mode without a server address: the placeholder, as before')
  assert.equal(gettersFor(true).getLibraryItemCoverSrcById({ coverEpochs: {} }, null, {}, rootGetters(null))('li1'), '/_absplus/cover/li1')
  assert.equal(gettersFor(true, 'ios').getLibraryItemCoverSrc({ coverEpochs: {} }, null, {}, rootGetters('https://srv'))(book(), '/p.jpg'), 'https://srv/api/items/li1/cover?ts=1700', 'iOS has no route handler: server URL')
})

test('web keeps the server URL', () => {
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, native: false, serverAddress: 'https://srv' }), 'https://srv/api/items/li1/cover?ts=1700')
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, raw: true, native: false, serverAddress: 'https://srv', token: 't', tokenRequired: true }), 'https://srv/api/items/li1/cover?ts=1700&raw=1&token=t')
  assert.equal(coverSrc({ itemId: 'li1', native: false, serverAddress: null }), null)

  const before = Date.now()
  const ts = Number(new URL(coverSrc({ itemId: 'li1', native: false, serverAddress: 'https://srv' })).searchParams.get('ts'))
  assert.ok(ts >= before, 'without updatedAt the web URL keeps ts=Date.now()')

  assert.equal(coverSrc({ itemId: 'li1', native: false, serverAddress: 'https://srv', versioned: false }), 'https://srv/api/items/li1/cover')
  assert.equal(coverSrc({ itemId: 'li1', native: false, serverAddress: 'https://srv', versioned: false, token: 't', tokenRequired: true }), 'https://srv/api/items/li1/cover?token=t')
})

test('the getter keeps placeholders', () => {
  assert.equal(coverOf(true, book({ media: { coverPath: null } }), 'https://srv'), '/book_placeholder.jpg')
  assert.equal(coverOf(true, null, 'https://srv'), '/book_placeholder.jpg')
  assert.equal(coverOf(true, book({ media: { coverPath: 'https://cdn/cover.jpg' } }), 'https://srv'), 'https://cdn/cover.jpg')
  assert.equal(coverOf(true, book({ updatedAt: null }), 'https://srv'), '/_absplus/cover/li1', 'no updatedAt: the route goes without ts')
})

test('a failed cover route draws the no-cover placeholder', async () => {
  const component = new Function('Capacitor', 'isCoverRoute', scriptOf(await read('../components/covers/BookCover.vue')))({ convertFileSrc: (s) => s }, isCoverRoute)
  const vmFor = (src) => {
    const vm = { loading: true, imageFailed: false, coverUnavailable: false, coverSrc: src, hasCover: true }
    vm.showTitlePlaceholder = () => component.computed.showTitlePlaceholder.call(vm)
    return vm
  }

  const route = vmFor('/_absplus/cover/li1?ts=1700')
  component.methods.imageError.call(route, new Event('error'))
  assert.equal(route.coverUnavailable, true)
  assert.equal(route.showTitlePlaceholder(), true, 'title and author are drawn')
  assert.equal(route.imageFailed, false, 'no "Invalid Cover"')

  const server = vmFor('https://srv/api/items/li1/cover?ts=1700')
  const error = console.error
  console.error = () => {}
  try {
    component.methods.imageError.call(server, new Event('error'))
  } finally {
    console.error = error
  }
  assert.equal(server.coverUnavailable, false)
  assert.equal(server.showTitlePlaceholder(), false)
  assert.equal(server.imageFailed, true, 'a broken server image still says "Invalid Cover"')
})

test('a group whose only cover route failed shows its name', () => {
  const covers = [{ id: 'li1', coverUrl: '/_absplus/cover/li1?ts=1' }]
  assert.deepEqual(shownGroupCovers(covers, [false]), [], 'nothing to draw: the group name is shown')
  assert.deepEqual(shownGroupCovers(covers, [true]), covers)
  const server = [{ id: 'li2', coverUrl: 'https://srv/api/items/li2/cover' }]
  assert.deepEqual(shownGroupCovers(server, [false]), server, 'a server URL is drawn as before')
})

test('a cover-ready notice makes the cover route ask again', async () => {
  const mutations = new Function('Vue', `${globalsSource}\nreturn mutations`)(fakeVue)
  const state = { coverEpochs: {} }
  const getters = gettersFor(true)
  const src = (raw = false) => getters.getLibraryItemCoverSrc(state, null, {}, rootGetters(null))(book(), '/p.jpg', raw)
  const byId = (options) => getters.getLibraryItemCoverSrcById(state, null, {}, rootGetters(null))('li1', null, options)

  assert.equal(src(), '/_absplus/cover/li1?ts=1700', 'no notice: no epoch')
  mutations.bumpCoverEpochs(state, ['li1'])
  assert.equal(src(), '/_absplus/cover/li1?ts=1700&e=1')
  assert.equal(byId(), '/_absplus/cover/li1?e=1')
  assert.equal(src(true), '/_absplus/cover/li1?ts=1700&raw=1', 'the streamed original is not loaded again for a stored standard copy')
  mutations.bumpCoverEpochs(state, ['other'])
  assert.equal(src(), '/_absplus/cover/li1?ts=1700&e=1', 'another item does not change this cover')
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, native: false, serverAddress: 'https://srv', epoch: 3 }), 'https://srv/api/items/li1/cover?ts=1700', 'web mode ignores it')

  const { createDataService, COVER_READY_BATCH_MS } = await importSource('../utils/dataService.js')
  const listeners = {}
  const commits = []
  createDataService({
    plugin: new Proxy({ addListener: (name, cb) => (listeners[name] = cb) }, { get: (target, key) => target[key] ?? (async () => ({})) }),
    store: { state: {}, commit: (type, payload) => commits.push([type, payload]) }
  })
  listeners.onCoverReady({ itemId: 'li1' })
  listeners.onCoverReady({ itemId: 'li2' })
  listeners.onCoverReady({ itemId: 'li1' })
  const bumps = () => commits.filter(([type]) => type === 'globals/bumpCoverEpochs')
  assert.deepEqual(bumps(), [], 'notices wait for the batch')
  await new Promise((resolve) => setTimeout(resolve, COVER_READY_BATCH_MS + 50))
  assert.deepEqual(bumps(), [['globals/bumpCoverEpochs', ['li1', 'li2']]], 'one mutation for the batch')
})

test('Year in Review waits for a missing cover', () => {
  const state = { coverEpochs: {} }
  assert.equal(gettersFor(true).getLibraryItemCoverSrcById(state, null, {}, rootGetters(null))('li1', null, { wait: true }), '/_absplus/cover/li1?wait=1')
  assert.equal(gettersFor(false).getLibraryItemCoverSrcById(state, null, {}, rootGetters('https://srv'))('li1', null, { wait: true }), 'https://srv/api/items/li1/cover', 'web mode as before')
})

test('Year in Review loads its covers all at once', async () => {
  const images = []
  const fakeImage = () => {
    const listeners = {}
    const img = { addEventListener: (name, cb) => (listeners[name] = cb), fire: (name) => listeners[name]() }
    images.push(img)
    return img
  }
  const loading = loadCoverImages(['a', 'b', 'a', 'c'], (id) => `/_absplus/cover/${id}?wait=1`, fakeImage)

  assert.deepEqual(images.map((img) => img.src), ['/_absplus/cover/a?wait=1', '/_absplus/cover/b?wait=1', '/_absplus/cover/c?wait=1'], 'each cover once, all asked before any arrives')
  images[2].fire('load')
  images[1].fire('error')
  images[0].fire('load')
  const loaded = await loading
  assert.deepEqual([...loaded.keys()], ['a', 'c'], 'a failed cover is left out')
  assert.equal(loaded.get('a'), images[0])
})

test('Year in Review loads only the covers its grid draws', () => {
  const books = Array.from({ length: 13 }, (_, i) => `b${i}`)
  assert.deepEqual(drawnCoverIds(books, 4 * 3), books.slice(0, 12), 'a 4x3 grid of 13 books loads the first 12')
  assert.deepEqual(drawnCoverIds(['a', 'b'], 5 * 5), ['a', 'b'], 'fewer books than tiles: the grid repeats them')
})
