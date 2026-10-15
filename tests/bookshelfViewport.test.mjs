// LazyBookshelf virtualization against a modelled page: the real component code (LazyBookshelf.vue and the
// bookshelfCardsHelpers mixin) runs on Vue 2.7 with its real render scheduling, over a minimal DOM in which
// a #shelf-N element exists only once Vue has rendered that shelf, and #bookshelf is as tall as its page
// makes it: the scroll viewport when it is the page root, its own content when a page wraps it in an
// unsized <div> (the series page).
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createPageRetry } from '../utils/pageRetry.js'

const require = createRequire(import.meta.url)
// Vue installs its DOM patcher only when it loads in a browser-like global
global.window = { navigator: { userAgent: 'node' } }
global.document = {}
const Vue = require('vue')
const { parseComponent } = require('vue-template-compiler')
Vue.config.productionTip = false
Vue.config.devtools = false

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8')
// An ES module's `export default {...}` as a function of its imports
const loadDefault = (source, names) => {
  const body = source.replace(/^import .*$/gm, '').replace('export default', 'return')
  return new Function(...names, body)
}

const VIEWPORT = 700 // #bookshelf-wrapper height (px)
const WIDTH = 412

// ---- Minimal DOM: enough for Vue's node ops and the bookshelf's getElementById lookups
class El {
  constructor(tag) {
    this.tagName = tag.toUpperCase()
    this.childNodes = []
    this.parentNode = null
    this.style = {}
    this.attrs = {}
    this.listeners = {}
    this.classList = { add() {}, remove() {} }
  }
  get nextSibling() {
    if (!this.parentNode) return null
    const s = this.parentNode.childNodes
    return s[s.indexOf(this) + 1] || null
  }
  appendChild(c) {
    if (c.parentNode) c.parentNode.removeChild(c)
    c.parentNode = this
    this.childNodes.push(c)
    return c
  }
  insertBefore(c, ref) {
    if (!ref) return this.appendChild(c)
    if (c.parentNode) c.parentNode.removeChild(c)
    c.parentNode = this
    this.childNodes.splice(this.childNodes.indexOf(ref), 0, c)
    return c
  }
  removeChild(c) {
    this.childNodes = this.childNodes.filter((n) => n !== c)
    c.parentNode = null
    return c
  }
  remove() {
    if (this.parentNode) this.parentNode.removeChild(this)
  }
  setAttribute(k, v) {
    this.attrs[k] = String(v)
  }
  removeAttribute(k) {
    delete this.attrs[k]
  }
  getAttribute(k) {
    return this.attrs[k]
  }
  hasAttribute(k) {
    return k in this.attrs
  }
  addEventListener(t, fn) {
    ;(this.listeners[t] = this.listeners[t] || []).push(fn)
  }
  removeEventListener(t, fn) {
    this.listeners[t] = (this.listeners[t] || []).filter((f) => f !== fn)
  }
  set textContent(v) {
    this._text = v
  }
  get id() {
    return this.attrs.id
  }
  set id(v) {
    this.attrs.id = v
  }
}

const makeWorld = ({ wrapped }) => {
  const docRoot = new El('body')
  const wrapper = docRoot.appendChild(new El('div'))
  wrapper.id = 'bookshelf-wrapper'
  wrapper.clientHeight = VIEWPORT
  wrapper.clientWidth = WIDTH
  wrapper.scrollTop = 0
  // The series page's unsized wrapper <div>; the other bookshelf pages mount the bookshelf as their root
  const pageRoot = wrapped ? wrapper.appendChild(new El('div')) : wrapper
  const attached = (el) => {
    for (let n = el; n; n = n.parentNode) if (n === docRoot) return true
    return false
  }
  const find = (n, id) => {
    if (n.attrs && n.attrs.id === id) return n
    for (const c of n.childNodes || []) {
      const f = find(c, id)
      if (f) return f
    }
    return null
  }
  const document = {
    createElement: (t) => new El(t),
    createElementNS: (ns, t) => new El(t),
    createTextNode: (t) => Object.assign(new El('#text'), { text: t }),
    createComment: (t) => Object.assign(new El('#comment'), { text: t }),
    getElementById: (id) => find(docRoot, id),
    addEventListener() {},
    removeEventListener() {}
  }
  return { docRoot, wrapper, pageRoot, document, attached }
}

// ---- The real component, with a render that produces what its template produces: #bookshelf holding one
// #shelf-N per shelf, each shelfHeight tall
const loadBookshelf = async () => {
  const sfc = parseComponent(await read('../components/bookshelf/LazyBookshelf.vue'))
  const mixin = loadDefault(await read('../mixins/bookshelfCardsHelpers.js'), ['Vue'])(Vue)
  const component = loadDefault(sfc.script.content, ['bookshelfCardsHelpers', 'createPageRetry', 'textScale'])(mixin, createPageRetry, () => 1)
  return { component, mixin }
}

const FakeCard = (log) =>
  function Card({ propsData }) {
    this.index = propsData.index
    this.$mount = () => {
      this.$el = new El('div')
      this.$el.id = `book-card-${this.index}`
    }
    this.setEntity = (e) => (this.entity = e)
    this.setLocalLibraryItem = () => {}
    this.setSelectionMode = () => {}
    this.destroy = () => log.push(['destroyed', this.index])
  }

const setup = async ({ wrapped, total = 13, listView = true, delay = () => 5, httpFail = () => false }) => {
  const world = makeWorld({ wrapped })
  const { component } = await loadBookshelf()
  const errors = []
  const cardLog = []
  const requests = []
  global.document = world.document
  global.window = { navigator: { userAgent: 'node' }, innerWidth: WIDTH, location: { protocol: 'http:', host: 'localhost', pathname: '/bookshelf/series/s1', search: '' }, history: { replaceState() {} } }
  window.window = window
  global.screen = { orientation: { addEventListener() {}, removeEventListener() {} } }
  const emitter = () => ({ $on() {}, $off() {}, $emit() {} })
  const store = {
    state: { user: { user: { id: 'u1' } }, globals: { bookshelfListView: listView, series: { libraryId: 'lib' } }, libraries: { currentLibraryId: 'lib' }, lastBookshelfScrollData: {} },
    getters: {
      getServerSetting: () => false,
      getHasActiveServer: true,
      'user/getUserSetting': (k) => ({ mobileOrderBy: 'media.metadata.title', mobileOrderDesc: false, mobileFilterBy: 'all', collapseSeries: false, collapseBookSeries: false }[k]),
      'libraries/getBookCoverAspectRatio': 1.6,
      'libraries/getCurrentLibraryMediaType': 'book',
      getAltViewEnabled: true
    },
    commit() {},
    dispatch() {}
  }
  const http = {
    get: (url) => {
      const page = +/[?&]page=(\d+)/.exec(url)[1]
      const limit = +/[?&]limit=(\d+)/.exec(url)[1]
      requests.push(page)
      return new Promise((resolve, reject) =>
        setTimeout(() => {
          if (httpFail(page)) return reject(new Error('offline'))
          const results = []
          for (let i = page * limit; i < Math.min(total, (page + 1) * limit); i++) results.push({ id: `b${i}`, media: {} })
          resolve({ results, total, limit, page })
        }, delay(page))
      )
    }
  }
  // A series page reads its books from the catalog replica (offline-first 4b), page by page as the server did
  const absData = { getLibraryItems: ({ page, limit }) => http.get(`?page=${page}&limit=${limit}`), onDataChanged: () => () => {} }
  Object.assign(Vue.prototype, { $store: store, $absData: absData, $db: { getLocalLibraryItems: async () => [] }, $eventBus: emitter(), $socket: emitter(), $encode: (s) => s, $platform: 'android', $strings: {} })
  const origError = console.error
  console.error = (...a) => errors.push(a.join(' '))
  console.log = () => {}
  console.warn = () => {}

  let live = null
  // As in the WebView: when the rows shrink, scrollTop is clamped and a scroll event follows
  const clampScroll = () => {
    const bookshelf = world.document.getElementById('bookshelf')
    if (!live || !bookshelf) return
    const max = Math.max(0, bookshelf.childNodes.length * live.shelfHeight - VIEWPORT)
    if (world.wrapper.scrollTop > max) {
      world.wrapper.scrollTop = max
      live.scroll({ target: world.wrapper })
    }
  }
  // mounted() runs once the bookshelf is in the page, as it does in the app
  const { mounted, ...options } = component
  const Bookshelf = Vue.extend({
    mixins: [options],
    render(h) {
      const shelves = []
      for (let s = 0; s < this.totalShelves; s++) shelves.push(h('div', { key: s, attrs: { id: `shelf-${s}` } }))
      return h('div', { attrs: { id: 'bookshelf' } }, shelves)
    },
    methods: { getComponentClass: () => FakeCard(cardLog) },
    updated() {
      clampScroll()
    }
  })
  const mount = () => {
    const vm = new Bookshelf({ propsData: { page: 'series-books', seriesId: 's1' } })
    vm.$mount()
    world.pageRoot.appendChild(vm.$el)
    // #bookshelf is h-full: the viewport under the wrapper, its content height inside an unsized <div>
    Object.defineProperty(vm.$el, 'clientHeight', { get: () => (wrapped ? vm.$el.childNodes.length * vm.shelfHeight : VIEWPORT), configurable: true })
    Object.defineProperty(vm.$el, 'clientWidth', { get: () => WIDTH, configurable: true })
    mounted.call(vm)
    live = vm
    return vm
  }
  const settle = async (ms = 50) => {
    for (let i = 0; i < 6; i++) {
      await new Promise((r) => setTimeout(r, ms))
      await Vue.nextTick()
    }
  }
  const scrollTo = async (vm, top) => {
    world.wrapper.scrollTop = top
    vm.handleScroll(top)
    await settle(10)
  }
  // Rows inside the viewport, and which of them show a card that has its book
  const visibleRows = (vm) => {
    const first = Math.floor(world.wrapper.scrollTop / vm.shelfHeight)
    const last = Math.min(vm.totalShelves - 1, Math.floor((world.wrapper.scrollTop + VIEWPORT - 1) / vm.shelfHeight))
    const rows = []
    for (let s = first; s <= last; s++) {
      const shelf = world.document.getElementById(`shelf-${s}`)
      const cards = shelf ? shelf.childNodes.filter((c) => /^book-card-/.test(c.id || '')) : []
      rows.push({ shelf: s, filled: cards.length > 0 })
    }
    return rows
  }
  const restore = () => {
    console.error = origError
  }
  return { world, mount, settle, scrollTo, visibleRows, errors, requests, cardLog, restore }
}

const blankRows = (rows) => rows.filter((r) => !r.filled).map((r) => r.shelf)

test('series page (bookshelf inside an unsized page <div>): every visible row gets its card, at the top and after scrolling', async () => {
  const h = await setup({ wrapped: true })
  try {
    const vm = h.mount()
    await h.settle()
    assert.equal(vm.totalShelves, 13)
    assert.deepEqual(blankRows(h.visibleRows(vm)), [], 'rows blank after the first load')
    for (const top of [300, 600, 0]) {
      await h.scrollTo(vm, top)
      assert.deepEqual(blankRows(h.visibleRows(vm)), [], `rows blank at scrollTop ${top}`)
    }
    assert.deepEqual(h.errors, [])
  } finally {
    h.restore()
  }
})

test('series page in grid view: every visible row gets its cards', async () => {
  const h = await setup({ wrapped: true, listView: false, total: 13 })
  try {
    const vm = h.mount()
    await h.settle()
    assert.deepEqual(blankRows(h.visibleRows(vm)), [])
    await h.scrollTo(vm, 500)
    assert.deepEqual(blankRows(h.visibleRows(vm)), [])
  } finally {
    h.restore()
  }
})

test('library page (bookshelf is the page root): every visible row gets its card', async () => {
  const h = await setup({ wrapped: false, total: 300 })
  try {
    const vm = h.mount()
    await h.settle()
    assert.deepEqual(blankRows(h.visibleRows(vm)), [])
    for (const top of [2000, 9000, 0]) {
      await h.scrollTo(vm, top)
      assert.deepEqual(blankRows(h.visibleRows(vm)), [], `rows blank at scrollTop ${top}`)
    }
  } finally {
    h.restore()
  }
})

test('series page: a failed first page is retried and then fills the visible rows', async () => {
  let failures = 1
  const h = await setup({ wrapped: true, httpFail: (page) => page === 0 && failures-- > 0 })
  try {
    const vm = h.mount()
    await h.settle(400) // pageRetry waits 1.5s before its first retry
    await new Promise((r) => setTimeout(r, 1600))
    await h.settle()
    assert.deepEqual(h.requests.slice(0, 2), [0, 0])
    assert.equal(vm.totalShelves, 13)
    assert.deepEqual(blankRows(h.visibleRows(vm)), [])
  } finally {
    h.restore()
  }
})

test('a bookshelf left while its first page is still loading mounts nothing afterwards', async () => {
  const h = await setup({ wrapped: true, delay: () => 30 })
  try {
    const vm = h.mount()
    await new Promise((r) => setTimeout(r, 5)) // init is waiting for page 0
    vm.$destroy()
    vm.$el.remove()
    await h.settle()
    // Without the guard it tried to mount cards into shelves of a page that is gone ("invalid shelf")
    assert.deepEqual(
      h.errors.filter((e) => e.includes('invalid shelf')),
      []
    )
    assert.equal(vm.entityIndexesMounted.length, 0)
  } finally {
    h.restore()
  }
})

test('series page: a reset (library or filter change), also while a page is loading, refills the visible rows', async () => {
  const h = await setup({ wrapped: true, delay: () => 20 })
  try {
    const vm = h.mount()
    await h.settle()
    await h.scrollTo(vm, 400)
    vm.resetEntities()
    await h.settle()
    assert.deepEqual(blankRows(h.visibleRows(vm)), [])
    // A reset requested mid-load runs when that load returns
    vm.resetEntities()
    await new Promise((r) => setTimeout(r, 5))
    vm.resetEntities()
    await h.settle()
    assert.equal(vm.totalShelves, 13)
    assert.deepEqual(blankRows(h.visibleRows(vm)), [])
    assert.deepEqual(h.errors, [])
  } finally {
    h.restore()
  }
})
