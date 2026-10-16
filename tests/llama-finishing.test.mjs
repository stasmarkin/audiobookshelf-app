// LLAMA finishing corrections (2026-10-10): amber browsing Play legends, Home shelf frames drawn inside the cover, the
// compact collapsed player without the empty band, and a fainter page texture. LLAMA only; Dark, Black and Light unchanged.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const presets = require('../theme/presets.js')
const layout = require('../theme/playerLayout.js')
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

const P = presets.PRIMITIVES
const root = engine.themeSelector('llama')
const rules = () => presets.presentationRules([engine.getTheme('llama')])
const LEGACY = ['dark', 'black', 'light']

test('Browsing Play controls: amber action legends through purpose-named hooks; green stays for selection and displays', async () => {
  const r = rules()
  for (const s of ['.browse-toolbar .series-play-action', '.row-play-btn > .material-symbols', '.episode-play-control > .material-symbols']) {
    assert.deepEqual(r[`${root} ${s}`], { ...P.PLAYBACK_LEGEND }, s)
  }
  assert.deepEqual({ ...P.LIST_PLAY_LEGEND }, { ...P.PLAYBACK_LEGEND })
  // No rule names or uses the success color (the hooks carry the meaning, the markup keeps its classes)
  for (const [selector, declarations] of Object.entries(r)) {
    assert.doesNotMatch(selector.replace(/bg-success-action/g, ''), /success/, selector)
    for (const value of Object.values(declarations)) assert.doesNotMatch(value, /--color-success/, selector)
  }
  // The green selection outline of the navigation and the focus ring are untouched
  assert.match(JSON.stringify(r[`${root} :focus-visible`]), /--color-accent/)
  assert.ok(
    Object.entries(r).some(([s, d]) => /#bookshelf-navbar/.test(s) && /--color-accent/.test(JSON.stringify(d))),
    'selected navigation key keeps its green ring'
  )
  // Markup: hook classes added, behaviour, labels and classes otherwise unchanged
  const toolbar = await read('../components/home/BookshelfToolbar.vue')
  assert.match(toolbar, /<button v-if="seriesBookPage && \$platform === 'android'" type="button" class="series-play-action flex items-center px-2 text-success" :disabled="\$store\.state\.playerIsStartingPlayback" @click="\$eventBus\.\$emit\('play-series-click'\)"><span class="material-symbols text-2xl">play_arrow<\/span>\{\{ \$strings\.ButtonPlay \}\}<\/button>/)
  for (const f of ['EpisodeRow', 'LatestEpisodeRow']) {
    const src = await read(`../components/tables/podcast/${f}.vue`)
    assert.match(src, /<div class="episode-play-control h-(9|10) px-4 border border-border[^"]*"[^>]*@click\.stop="playClick">/, f)
  }
})

test('Home shelf frames: drawn inside the cover on an inert card-frame element, the same 4px frame, outer frame dropped there only', async () => {
  const r = rules()
  assert.deepEqual(r[`${root} .shelf-section .card-artwork`], { 'box-shadow': P.ARTWORK_DROP })
  assert.deepEqual(r[`${root} .shelf-section .card-frame`], { 'box-shadow': P.ARTWORK_FRAME_INSET })
  // Elsewhere (Library, series, detail) the outer frame is unchanged
  assert.equal(r[`${root} .card-artwork`]['box-shadow'], `${P.ARTWORK_FRAME}, ${P.ARTWORK_DROP}`)
  // Every layer is inset and none reaches deeper than the outer frame's 4px footprint
  const layers = P.ARTWORK_FRAME_INSET.split(/,\s*(?![^()]*\))/)
  assert.ok(layers.length >= 8)
  for (const layer of layers) {
    assert.match(layer, /^inset /, layer)
    const [x, y, , spread] = layer
      .replace(/^inset /, '')
      .match(/-?\d+(px)?/g)
      .map((n) => Math.abs(parseFloat(n)))
    assert.ok(Math.max(x, y) + spread <= 4, layer)
  }
  // The element: decorative, never a touch target, positioned over the cover by its own markup; above the art (z-10, after
  // the art), below the progress bar and badges (later in the markup or higher)
  const book = await read('../components/cards/LazyBookCard.vue')
  const series = await read('../components/cards/LazySeriesCard.vue')
  const author = await read('../components/cards/AuthorCard.vue')
  for (const [name, src, radius] of [
    ['book', book, 'rounded-sm'],
    ['series', series, 'rounded'],
    ['author', author, 'rounded-md']
  ]) {
    assert.equal((src.match(/card-frame/g) || []).length, 1, name)
    assert.ok(src.includes(`<div class="card-frame absolute inset-0 z-10 ${radius} pointer-events-none" aria-hidden="true" />`), name)
  }
  assert.ok(book.indexOf('card-frame') > book.indexOf('ref="cover"') && book.indexOf('card-frame') < book.indexOf('box-shadow-progressbar'))
  assert.ok(series.indexOf('card-frame') > series.indexOf('<covers-group-cover') && series.indexOf('card-frame') < series.indexOf('box-shadow-progressbar'))
  // Only the Home shelves (Shelf.vue) carry shelf-section; the scrollers keep their overflow
  const shelf = await read('../components/bookshelf/Shelf.vue')
  assert.match(shelf, /<div class="shelf-section w-full relative">/)
  assert.match(shelf, /class="flex items-end px-3 max-w-full overflow-x-auto"/)
  for (const [s, d] of Object.entries(r)) if (/shelf-section|card-artwork|card-frame|overflow-x-auto/.test(s)) assert.ok(!Object.keys(d).some((p) => /overflow/.test(p)), `${s}: no overflow change`)
})

test('Collapsed player: the equipment finish trims the empty band under the seek strip; other themes keep 120px', async () => {
  assert.deepEqual(layout.miniLayout(engine.getTheme('llama')), { classes: ['mini-compact'], vars: { '--mini-trim': '28px' }, height: 92 })
  for (const id of LEGACY) assert.deepEqual(layout.miniLayout(engine.getTheme(id)), { classes: [], vars: {}, height: 120 }, id)
  assert.equal(layout.MINI.legacyHeight - layout.MINI.trim, 92)
  // The trim leaves the strip's own 7px lip (the readout strip's shadow plate) below the strip, no more
  assert.equal(35 - layout.MINI.trim, 7)
  const player = await read('../components/app/AudioPlayer.vue')
  // Base geometry unchanged; the compact rules subtract the same trim from each previous offset
  for (const [selector, prop, value] of [
    ['.playerContainer', 'height', '120px'],
    ['#playerTrack', 'bottom', '35px'],
    ['.cover-wrapper', 'bottom', '68px'],
    ['.title-author-texts', 'bottom', '76px'],
    ['#playerControls', 'bottom', '70px']
  ]) {
    assert.match(player, new RegExp(`\\n${selector.replace(/[.#]/g, (c) => '\\' + c)} \\{[^}]*${prop}: ${value};`), selector)
    const compact = new RegExp(`#streamContainer\\.mini-compact ${selector.replace(/[.#]/g, (c) => '\\' + c)} \\{\\n  ${prop}: calc\\(${value} - var\\(--mini-trim\\)\\);\\n\\}`)
    assert.match(player, compact, selector)
  }
  assert.match(player, /miniLayout\(\) \{\n\s+return playerLayout\.miniLayout\(this\.presentationTheme\)/)
  assert.match(player, /'miniLayout\.height': \{\n\s+handler\(height\) \{\n\s+document\.documentElement\.style\.setProperty\('--mini-player-height', height \+ 'px'\)/)
  // The page content above the player: app bar + plate; 184px exactly as before when the variable is the default 120px
  const css = await read('../assets/app.css')
  for (const p of ['height', 'min-height', 'max-height']) assert.ok(css.includes(`  ${p}: calc(100% - 64px - var(--mini-player-height, 120px));`), p)
  assert.ok(!css.includes('184px'))
})

test('Page texture: the graphite scan lines are faint (a texture on close inspection), still present, LLAMA only', () => {
  const alphas = [...P.SCANLINES.matchAll(/rgb\(255 255 255 \/ ([\d.]+)\)/g)].map((m) => parseFloat(m[1]))
  assert.equal(alphas.length, 2)
  for (const a of alphas) assert.ok(a > 0 && a <= 0.008, String(a))
  const r = rules()
  assert.equal(r[`${root} .layout-wrapper.bg-bg`]['background-image'], P.SCANLINES)
  assert.equal(r[`${root} .layout-wrapper.bg-bg`]['background-color'], 'rgb(var(--color-page))')
  for (const id of LEGACY) assert.deepEqual(presets.presentationRules([engine.getTheme(id)]), {}, id)
})

test('Compiled CSS: the new hooks are styled only under the LLAMA root (and E-ink, which keeps them visible on paper)', async () => {
  const postcss = require('postcss')
  const tailwind = require('tailwindcss')
  const config = require('../tailwind.config.js')
  const source = await read('../assets/tailwind.css')
  const { css } = await postcss([tailwind({ ...config, content: [{ raw: '<div class="card-frame series-play-action episode-play-control row-play-btn mini-compact"></div>' }] })]).process(source, { from: undefined })
  for (const hook of ['card-frame', 'series-play-action', 'episode-play-control']) {
    assert.ok(css.includes(`html[data-theme='llama'] .`) && css.includes(hook), hook)
    for (const line of css.split('\n').filter((l) => l.includes(`.${hook}`) && l.includes('{'))) assert.match(line, /^html\[data-theme='(llama|eink)'\]/, line)
  }
})
