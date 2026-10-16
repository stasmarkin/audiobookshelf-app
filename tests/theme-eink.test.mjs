// E-ink built-in theme: paper and ink with two grays, and the paint-only `eink` finish that removes shadows, gradients and
// translucency and draws 1px ink lines instead.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const presets = require('../theme/presets.js')
const { TOKENS, TOKEN_NAMES } = require('../theme/tokens.js')
const { BUILTIN_THEMES } = require('../theme/builtins.js')
const { coverColorPresentation } = require('../theme/coverPresentation.js')
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

const eink = () => engine.getTheme('eink')
const ROOT = "html[data-theme='eink']"
const INK = [0, 0, 0]
const PAPER = [255, 255, 255]
const INK_VAR = 'rgb(var(--color-fg))'
const PAPER_VAR = 'rgb(var(--color-bg))'
const MUTED_VAR = 'rgb(var(--color-fg-muted))'

const luminance = ([r, g, b]) => {
  const lin = (c) => ((c /= 255) <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

test('E-ink is a registered light built-in that defines every token explicitly', () => {
  const definition = BUILTIN_THEMES.find((t) => t.id === 'eink')
  for (const name of TOKEN_NAMES) assert.ok(Object.prototype.hasOwnProperty.call(definition.tokens, name), name)
  assert.deepEqual(engine.validateTheme(definition, null).errors, [])
  assert.equal(eink().colorScheme, 'light')
  assert.equal(engine.resolveThemeId('eink'), 'eink')
  assert.equal(engine.readerShellId(eink()), 'light')
  assert.equal(engine.THEMES.at(-1).id, 'eink')
})

// Audiobookshelf+ strings live in en-us.json; plugins/i18n.js falls back to it key by key for every other locale
test('E-ink label exists in en-us, which every locale falls back to', async () => {
  const strings = JSON.parse(await read('../strings/en-us.json'))
  assert.equal(strings[eink().labelKey], 'E-ink')
  assert.match(await read('../plugins/i18n.js'), /strings\[key\] \|\| translations\[defaultCode\]\[key\]/)
})

test('E-ink palette: paper, ink and at most two grays, far apart; overlays are solid paper', () => {
  const colors = TOKENS.filter((t) => t.type === 'rgb' && !t.name.startsWith('system.')).map((t) => eink().tokens[t.name])
  const grays = new Set(colors.filter((c) => c.join() !== INK.join() && c.join() !== PAPER.join()).map((c) => c.join()))
  for (const c of colors) assert.ok(c[0] === c[1] && c[1] === c[2], `neutral: ${c}`)
  assert.ok(grays.size <= 2, [...grays].join(' | '))
  const [light, dark] = [...grays].map((g) => g.split(',').map(Number)).sort((a, b) => b[0] - a[0])
  assert.ok(contrast(light, dark) >= 4.5, 'the two grays are far apart')
  for (const name of ['overlay.item-header', 'overlay.player', 'overlay.mini-player']) assert.deepEqual(eink().tokens[name], { kind: 'solid', color: PAPER }, name)
})

test('E-ink contrast: text and secondary text on paper, the played line against the track', () => {
  const t = eink().tokens
  assert.ok(contrast(t['text.primary'], t['surface.content']) >= 7)
  assert.ok(contrast(t['text.muted'], t['surface.content']) >= 7)
  assert.ok(contrast(t['progress.played'], t['progress.track']) >= 7)
  assert.ok(contrast([255, 255, 255], t['state.success-action']) >= 4.5, 'white button labels')
  assert.ok(contrast(t['text.primary'], t['surface.hover']) >= 7, 'text on a selected row')
})

test('E-ink uses its own finish and theme cover color: chrome is paper, cover art untouched', () => {
  assert.equal(eink().tokens['presentation.finish'], 'eink')
  assert.equal(eink().tokens['presentation.cover-color'], 'theme')
  const chrome = coverColorPresentation(eink(), { rgb: 'rgb(200, 30, 30)', isLight: false })
  assert.equal(chrome.usesTheme, true)
  assert.equal(chrome.darkForeground, true)
  assert.equal(chrome.controlWash, false)
  assert.doesNotMatch(chrome.backdrop, /200/)
})

const PAINT_ONLY = /^(background-color|background-image|box-shadow|border-color|color|text-decoration-line)$/
// A hard line: no offset beyond 1px, no blur, an ink or paper token color
const LINE = /^(inset )?(0|-?1px) (0|-?1px) 0( [12]px)? rgb\(var\(--color-(fg|bg)\)\)$/

test('E-ink recipe: paint only, under its own root, no gradients, translucency or soft shadows', () => {
  const rules = presets.presentationRules([eink()])
  assert.ok(Object.keys(rules).length >= 20)
  for (const [selector, declarations] of Object.entries(rules)) {
    assert.ok(selector.startsWith(`${ROOT} `), selector)
    assert.ok(!/::(before|after)/.test(selector), selector)
    for (const [property, value] of Object.entries(declarations)) {
      assert.match(property, PAINT_ONLY, `${selector}: ${property}`)
      assert.doesNotMatch(value, /gradient|url|['"]|\//, `${selector}: ${property}: ${value}`)
      if (property === 'box-shadow') assert.ok(value === 'none' || LINE.test(value), `${selector}: ${value}`)
      else if (property === 'background-image') assert.equal(value, 'none', selector)
      else if (property === 'text-decoration-line') assert.equal(value, 'underline', selector)
      else assert.match(value, /^(rgb\(var\(--color-[a-z-]+\)\)|transparent)$/, `${selector}: ${value}`)
    }
  }
})

test('E-ink recipe: played progress is the ink played token in every seek state and on every cover bar', () => {
  const rules = presets.presentationRules([eink()])
  const played = 'rgb(var(--color-track-cursor))'
  for (const s of ['#playerTrack .bg-track-cursor.bg-gray-200', '#playerTrack .bg-track-cursor.bg-yellow-300', '.absolute.bottom-0.left-0.z-10.bg-yellow-400', '.modal .chapters-panel li > .bg-yellow-400']) {
    assert.equal(rules[`${ROOT} ${s}`]['background-color'], played, s)
  }
  for (const s of ['#playerTrack div.relative.rounded-full', '.total-track div.relative.rounded-full']) assert.equal(rules[`${ROOT} ${s}`]['background-color'], 'rgb(var(--color-track))', s)
})

test('E-ink recipe: controls it turns paper never keep a white glyph', async () => {
  const rules = presets.presentationRules([eink()])
  // The list-row play key's glyph is text-white over the key; on the paper key it must be ink
  assert.match(await read('../components/cards/LazyListBookCard.vue'), /class="list-play-key[^"]*"[^>]*>\s*<span [^>]*class="material-symbols[^"]*text-white/)
  assert.equal(rules[`${ROOT} .list-play-key > .material-symbols`].color, 'rgb(var(--color-fg))')
  // The full player's key is drawn paper with an ink glyph
  assert.equal(rules[`${ROOT} .play-btn`].color, 'rgb(var(--color-fg))')
  // Modal titles and the close glyph sit on the paper backdrop
  assert.equal(rules[`${ROOT} .modal > .z-40.text-white`].color, 'rgb(var(--color-fg))')
  assert.equal(rules[`${ROOT} .modal > .z-40 .text-white`].color, 'rgb(var(--color-fg))')
})

// Foregrounds hardcoded for the dark themes, where they sit on paper in E-ink: [template, class it uses, eink selector, color]
const ON_PAPER = [
  ['../components/ui/ReadIconBtn.vue', 'w-5 h-5 text-white', '.icon-btn > div.text-white', INK_VAR],
  ['../components/tables/podcast/EpisodeRow.vue', "'text-white text-opacity-40'", '.episode-play-control.text-white', MUTED_VAR],
  ['../components/tables/podcast/LatestEpisodeRow.vue', "'text-fg text-opacity-40'", '.episode-play-control.text-opacity-40', MUTED_VAR],
  ['../components/ui/MultiSelect.vue', 'text-gray-50', '.text-gray-50', INK_VAR],
  ['../components/modals/AutoSleepTimerRewindLengthModal.vue', 'text-gray-50', '.text-gray-50', INK_VAR],
  ['../components/ui/MultiSelect.vue', 'material-symbols text-white', '.bg-opacity-75 > .material-symbols.text-white', INK_VAR],
  ['../components/ui/MultiSelect.vue', 'text-yellow-400', '.text-yellow-400', INK_VAR],
  ['../components/modals/OrderModal.vue', 'text-yellow-300', '.text-yellow-300', INK_VAR],
  ['../pages/localMedia/folders/index.vue', 'text-yellow-400', '.text-yellow-400', INK_VAR],
  ['../components/cards/AuthorCard.vue', 'truncate text-gray-200', '.text-gray-200', MUTED_VAR],
  ['../components/cards/ItemSearchCard.vue', 'text-gray-300', '.text-gray-300', MUTED_VAR],
  ['../components/modals/PodcastEpisodesFeedModal.vue', 'text-gray-300', '.text-gray-300', MUTED_VAR],
  ['../components/modals/downloads/DownloadItem.vue', 'text-gray-400', '.text-gray-400', MUTED_VAR],
  ['../pages/item/_id/_episode/index.vue', 'text-blue-400', '.text-blue-400', INK_VAR],
  ['../components/ui/Checkbox.vue', 'checkbox-mark', '.checkbox-box .checkbox-mark', INK_VAR]
]

test('E-ink: foregrounds hardcoded light for the dark themes are ink or the secondary gray on paper', async () => {
  const rules = presets.presentationRules([eink()])
  for (const [file, used, selector, color] of ON_PAPER) {
    assert.ok((await read(file)).includes(used), `${file} still uses ${used}`)
    assert.equal(rules[`${ROOT} ${selector}`]?.color, color, `${file}: ${selector}`)
  }
  // On a dark box they stay paper (author name overlay on the portrait, the card's play corner)
  assert.equal(rules[`${ROOT} .bg-black .text-gray-200`].color, PAPER_VAR)
  assert.equal(rules[`${ROOT} .bg-gradient-to-r.to-black .text-gray-200`].color, PAPER_VAR)
  // Light borders and borderless fields get ink lines
  for (const s of ['.border-white', '.row-play-btn', '.checkbox-box']) assert.equal(rules[`${ROOT} ${s}`]['border-color'], INK_VAR, s)
  assert.equal(rules[`${ROOT} input.outline-none:not(.border)`]['box-shadow'], 'inset 0 0 0 1px rgb(var(--color-fg))')
  // Unavailable player keys stay visible (light gray), distinct from the available ink keys
  assert.match(await read('../components/app/AudioPlayer.vue'), /'text-opacity-10 key-disabled'/)
  assert.equal(rules[`${ROOT} #playerContent .key-disabled`].color, 'rgb(var(--color-bg-hover))')
})

test('E-ink: ink fills never carry an ink label', async () => {
  const rules = presets.presentationRules([eink()])
  // ui/Btn colors other than success keep the inherited (ink) label on a state fill, which is ink in E-ink
  assert.match(await read('../components/ui/Btn.vue'), /list\.push\(`bg-\$\{this\.color\}`\)/)
  for (const fill of ['error', 'info', 'warning']) assert.equal(rules[`${ROOT} .btn.bg-${fill}`].color, PAPER_VAR, fill)
  // ...and its loading spinner (text-fg, ink) shows on every ink fill, success-action included
  assert.match(await read('../components/ui/Btn.vue'), /<div v-if="loading" class="text-fg absolute/)
  for (const fill of ['success-action', 'error', 'info', 'warning']) assert.equal(rules[`${ROOT} .btn.bg-${fill} > div.text-fg`].color, PAPER_VAR, fill)
  assert.equal(rules[`${ROOT} .icon-btn.bg-error > .material-symbols`].color, PAPER_VAR)
  assert.equal(rules[`${ROOT} .bg-black\\/80.text-fg`].color, PAPER_VAR)
  assert.equal(rules[`${ROOT} .bg-black\\/75 svg.animate-spin`].color, PAPER_VAR, 'server connect spinner on its dark overlay')
  for (const id of ['state.error', 'state.info', 'state.warning']) assert.deepEqual(eink().tokens[id], INK, id)
})

test('E-ink recipe: frames and lines come after shadow removal, so they win on shared elements', () => {
  const order = presets.EINK_RULES.map(([s]) => s)
  const lastShadowReset = Math.max(...presets.EINK_RULES.map(([, d], i) => (d['box-shadow'] === 'none' && Object.keys(d).length === 1 ? i : -1)))
  for (const s of ['.card-artwork', '.cover-wrapper', '.play-btn', '.absolute.bottom-0.left-0.z-10.bg-yellow-400', '.modal .rounded-lg.bg-primary']) assert.ok(order.indexOf(s) > lastShadowReset, s)
})

test('E-ink adds nothing to other themes: LLAMA rules and the standard themes are unchanged', () => {
  for (const id of ['dark', 'black', 'light']) assert.deepEqual(presets.presentationRules([engine.getTheme(id)]), {})
  for (const selector of Object.keys(presets.presentationRules([engine.getTheme('llama')]))) assert.ok(!selector.startsWith(ROOT), selector)
  assert.deepEqual(presets.presentationMediaRules([eink()]), {})
  // Geometry is untouched: the player keeps the standard layout
  const playerLayout = require('../theme/playerLayout.js')
  const light = engine.getTheme('light')
  const screen = { width: 412, height: 915, aspectRatio: 1, twoRail: true }
  assert.deepEqual(playerLayout.fullscreenLayout(eink(), screen), playerLayout.fullscreenLayout(light, screen))
  assert.deepEqual(playerLayout.miniLayout(eink()), playerLayout.miniLayout(light))
})

test('E-ink compiles through the real Tailwind build: variables and every recipe rule under its root', async () => {
  const postcss = require('postcss')
  const tailwind = require('tailwindcss')
  const config = require('../tailwind.config.js')
  const source = await read('../assets/tailwind.css')
  const content = config.content.map((glob) => new URL(`../${glob}`, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))
  const { css } = await postcss([tailwind({ ...config, content })]).process(source, { from: undefined })
  const blocks = [...css.matchAll(/html\[data-theme='eink'\] \{([^}]*)\}/g)].map((m) => m[1]).join('\n')
  for (const [property, value] of Object.entries(engine.themeDeclarations(eink().tokens))) assert.ok(blocks.includes(`${property}: ${value};`), property)
  for (const selector of Object.keys(presets.presentationRules([eink()]))) assert.ok(css.includes(`${selector} {`), `pruned or missing: ${selector}`)
})

test('the home-screen widget keeps the standard look under E-ink (no native palette)', async () => {
  const generator = require('../scripts/generate-widget-theme.js')
  assert.ok(!generator.widgetThemes().some((t) => t.id === 'eink'))
  const kotlin = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetTheme.kt')
  assert.doesNotMatch(kotlin.slice(kotlin.indexOf('fun fromThemeId'), kotlin.indexOf('fun current')), /"eink"/)
})
