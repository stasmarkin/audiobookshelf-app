// Covers offline (offline-first release 7): on native, cover getters build the app's cover route, which native answers
// from its disk cache; web dev mode keeps the server URL. A failed route draws the book without a cover, not
// "Invalid Cover". Real getter and component code against fakes; synthetic ids only.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const importSource = async (path) => import(`data:text/javascript;base64,${Buffer.from(await read(path)).toString('base64')}`)
const { coverSrc, isCoverRoute, shownGroupCovers } = await importSource('../utils/coverUrl.js')
const scriptOf = (source) => source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'return')

const globalsSource = (await read('../store/globals.js')).replace(/^import .*$/gm, '').replace(/^export const /gm, 'const ')
const gettersFor = (native) => new Function('Capacitor', 'coverSrc', `${globalsSource}\nreturn getters`)({ isNativePlatform: () => native }, coverSrc)

const rootGetters = (serverAddress, tokenRequired = false) => ({ 'user/getServerAddress': serverAddress, 'user/getToken': 't', getDoesServerImagesRequireToken: tokenRequired })
const coverOf = (native, item, serverAddress, raw = false) => gettersFor(native).getLibraryItemCoverSrc(null, null, {}, rootGetters(serverAddress))(item, '/book_placeholder.jpg', raw)
const book = (extra = {}) => ({ id: 'li1', updatedAt: 1700, media: { coverPath: '/metadata/items/li1/cover.jpg' }, ...extra })

test('native builds the cover route', () => {
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, native: true, serverAddress: null }), '/_absplus/cover/li1?ts=1700')
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, raw: true, native: true }), '/_absplus/cover/li1?ts=1700&raw=1')
  assert.equal(coverSrc({ itemId: 'li1', native: true }), '/_absplus/cover/li1')
  assert.equal(coverSrc({ itemId: 'li1', updatedAt: 1700, native: true, token: 't', tokenRequired: true }), '/_absplus/cover/li1?ts=1700', 'no token in the route')

  assert.equal(coverOf(true, book(), null), '/_absplus/cover/li1?ts=1700', 'offline without a server address the getter still gives the route')
  assert.equal(coverOf(false, book(), null), '/book_placeholder.jpg', 'web mode without a server address: the placeholder, as before')
  assert.equal(gettersFor(true).getLibraryItemCoverSrcById(null, null, {}, rootGetters(null))('li1'), '/_absplus/cover/li1')
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
