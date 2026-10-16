import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import vm from 'node:vm'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const { TOKENS, TOKEN_NAMES } = require('../theme/tokens.js')
// Source files are read LF-normalized, so a Windows (core.autocrlf) checkout and CI see the same text
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

const LEGACY_IDS = ['dark', 'black', 'light']
const legacySelector = { dark: ':root', black: "html[data-theme='black']", light: "html[data-theme='light']" }

// Parses the pre-token theme CSS (tests/fixtures/legacy-theme-c7a617bc.css) into { selector: { property: value } }
async function legacyRules() {
  const css = await read('./fixtures/legacy-theme-c7a617bc.css')
  const rules = {}
  for (const [, selector, body] of css.matchAll(/(:root|html\[data-theme='[a-z]+'\])\s*\{([^}]*)\}/g)) {
    rules[selector] = Object.fromEntries(
      body
        .split(';')
        .map((d) => d.trim())
        .filter(Boolean)
        .map((d) => [d.slice(0, d.indexOf(':')).trim(), d.slice(d.indexOf(':') + 1).trim()])
    )
  }
  const hex = Object.fromEntries([...css.matchAll(/(\w+): '#([0-9a-fA-F]{6})'/g)].map(([, name, h]) => [name, [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16))]))
  return { rules, hex }
}

test('schema: token names and CSS variables are unique, and every token has a known type', () => {
  assert.equal(new Set(TOKEN_NAMES).size, TOKEN_NAMES.length)
  const vars = TOKENS.filter((t) => t.cssVar).map((t) => t.cssVar)
  assert.equal(new Set(vars).size, vars.length)
  for (const token of TOKENS) {
    assert.ok(['rgb', 'overlay', 'enum'].includes(token.type), token.name)
    assert.match(token.name, /^[a-z]+\.[a-z-]+$/)
    if (token.cssVar) assert.match(token.cssVar, /^--[a-z-]+$/)
  }
})

test('every built-in theme satisfies the schema with no fallbacks', () => {
  assert.deepEqual(engine.BUILTIN_ERRORS, [])
  assert.deepEqual(
    engine.THEMES.map((t) => t.id),
    ['black', 'dark', 'light', 'llama', 'eink']
  )
  for (const theme of engine.THEMES) {
    for (const name of TOKEN_NAMES) assert.notEqual(theme.tokens[name], undefined, `${theme.id} ${name}`)
    assert.deepEqual(engine.validateTheme(JSON.parse(JSON.stringify(theme)), null).errors, [], theme.id)
  }
})

test('future themes cannot omit required tokens: every missing token is reported and filled from the default', () => {
  const dark = engine.getTheme('dark')
  const partial = { id: 'future', labelKey: 'LabelFuture', colorScheme: 'dark', tokens: { 'text.primary': [1, 2, 3] } }
  const { theme, errors } = engine.validateTheme(partial, dark.tokens)
  assert.equal(errors.length, TOKEN_NAMES.length - 1)
  assert.ok(errors.every((e) => e.endsWith(': missing')))
  assert.deepEqual(theme.tokens['text.primary'], [1, 2, 3])
  assert.deepEqual(theme.tokens['surface.base'], dark.tokens['surface.base'])
})

test('Dark, Black and Light reproduce the pre-token CSS exactly', async () => {
  const { rules, hex } = await legacyRules()
  for (const id of LEGACY_IDS) {
    const legacy = rules[legacySelector[id]]
    const generated = engine.themeDeclarations(engine.getTheme(id).tokens)
    const legacyVars = Object.keys(legacy).filter((p) => p.startsWith('--'))
    assert.equal(legacyVars.length, 15, id)
    for (const property of legacyVars) assert.equal(generated[property], legacy[property], `${id} ${property}`)
    // The root text color (was `color: white/black`)
    assert.deepEqual(engine.getTheme(id).tokens['text.default'], legacy.color === 'white' ? [255, 255, 255] : [0, 0, 0], id)
    // Status colors were fixed hex values in tailwind.config.js
    const t = engine.getTheme(id).tokens
    assert.deepEqual([t['accent.primary'], t['state.error'], t['state.info'], t['state.success'], t['state.success-strong'], t['state.warning']], [hex.accent, hex.error, hex.info, hex.success, hex.successDark, hex.warning])
  }
})

test('the real Tailwind build emits the theme variables for every built-in theme', async () => {
  const postcss = require('postcss')
  const tailwind = require('tailwindcss')
  const config = require('../tailwind.config.js')
  const source = await read('../assets/tailwind.css')
  const { css } = await postcss([tailwind({ ...config, content: [{ raw: '<div class="text-success bg-warning/10"></div>' }] })]).process(source, { from: undefined })
  for (const id of LEGACY_IDS) {
    const selector = engine.themeSelector(id)
    const start = css.indexOf(`${selector} {`)
    assert.ok(start >= 0, `${id} block missing`)
    const block = css.slice(start, css.indexOf('}', start))
    for (const [property, value] of Object.entries(engine.themeDeclarations(engine.getTheme(id).tokens))) {
      assert.ok(block.includes(`${property}: ${value};`), `${id} ${property}`)
    }
  }
  assert.ok(css.includes('color: rgb(var(--color-success) / var(--tw-text-opacity, 1))'))
  assert.ok(css.includes('background-color: rgb(var(--color-warning) / 0.1)'))
  assert.ok(!/@import|url\(/.test(css.slice(css.indexOf(':root {'), css.indexOf('}', css.indexOf(':root {')))))
})

test('selecting Dark, Black or Light resolves that theme; anything else resolves to Dark', () => {
  for (const id of LEGACY_IDS) {
    assert.equal(engine.resolveThemeId(id), id)
    assert.equal(engine.getTheme(id).id, id)
  }
  assert.equal(engine.getTheme('black').tokens['surface.base'][0], 0)
  assert.equal(engine.getTheme('light').tokens['surface.content'][0], 255)
  assert.equal(engine.getTheme('dark').tokens['surface.base'][0], 35)
  for (const value of [null, undefined, '', 'Dark', 'DARK', ' dark', 'foo', '__proto__', 'constructor', 'toString', 'hasOwnProperty', 42, {}, [], false, "light'] body{"]) {
    assert.equal(engine.resolveThemeId(value), 'dark', String(value))
  }
})

test('invalid token values fall back per token and are reported, never passed through', () => {
  const dark = engine.getTheme('dark').tokens
  const bad = {
    'surface.base': [256, 0, 0],
    'surface.content': [-1, 0, 0],
    'surface.raised': [1.5, 2, 3],
    'surface.hover': '0 0 0',
    'text.default': 'white',
    'text.primary': ['0', '0', '0'],
    'text.muted': [0, 0, 0, 0.5], // alpha not allowed for plain colors
    'border.default': [NaN, 0, 0],
    'control.toggle': [Infinity, 0, 0],
    'control.toggle-selected': null,
    'progress.track': { r: 0, g: 0, b: 0 },
    'progress.buffered': [0, 0],
    'progress.played': [0, 0, 0, 0, 0],
    'overlay.item-header': 'linear-gradient(red, blue)',
    'overlay.player': { kind: 'url', value: 'https://example.com/x.png' },
    'overlay.mini-player': { kind: 'linear', angle: '169deg', stops: [] },
    'accent.primary': '0 0 0; } body { display: none',
    'state.success': 'url(javascript:alert(1))',
    'system.bar-icons': 'purple',
    'unknown.token': [1, 2, 3]
  }
  const { tokens, errors } = engine.validateTokens(bad, dark)
  for (const name of Object.keys(bad).filter((n) => n !== 'unknown.token')) {
    assert.deepEqual(tokens[name], dark[name], name)
    assert.ok(errors.includes(`${name}: invalid value`), name)
  }
  assert.ok(errors.includes('unknown.token: unknown token ignored'))
  assert.equal('unknown.token' in tokens, false)
})

test('malformed overlays are rejected: too many stops, out-of-range angle/positions, bad stop colors', () => {
  const dark = engine.getTheme('dark').tokens
  const stop = { color: [0, 0, 0, 1], at: 0 }
  const cases = [
    { kind: 'linear', angle: 180, stops: Array(9).fill(stop) },
    { kind: 'linear', angle: 400, stops: [stop, stop] },
    { kind: 'linear', angle: 180, stops: [stop, { color: [0, 0, 0, 1], at: 101 }] },
    { kind: 'linear', angle: 180, stops: [stop, { color: [0, 0, 0, 2], at: 50 }] },
    { kind: 'linear', angle: 180, stops: [stop, { color: 'red', at: 50 }] },
    { kind: 'solid', color: [0, 0, 0, 0.5] }
  ]
  for (const overlay of cases) {
    const { tokens } = engine.validateTokens({ 'overlay.player': overlay }, dark)
    assert.deepEqual(tokens['overlay.player'], dark['overlay.player'], JSON.stringify(overlay).slice(0, 60))
  }
})

test('non-plain or hostile token containers fall back entirely without prototype pollution', () => {
  const dark = engine.getTheme('dark').tokens
  for (const input of [null, undefined, 'tokens', [], [[1, 2, 3]], new Map(), Object.create({ 'surface.base': [1, 2, 3] })]) {
    const { tokens } = engine.validateTokens(input, dark)
    assert.deepEqual(tokens, dark)
  }
  const hostile = JSON.parse('{"__proto__": {"polluted": true}, "constructor": {"prototype": {"polluted": true}}, "surface.base": [1, 2, 3]}')
  const { tokens, errors } = engine.validateTokens(hostile, dark)
  assert.deepEqual(tokens['surface.base'], [1, 2, 3])
  assert.equal({}.polluted, undefined)
  assert.equal(Object.prototype.polluted, undefined)
  assert.ok(errors.some((e) => e.startsWith('constructor')))
})

test('theme identity is constrained: bad ids, label keys or color schemes make a theme unusable', () => {
  const tokens = engine.getTheme('dark').tokens
  const base = { id: 'custom', labelKey: 'LabelCustom', colorScheme: 'dark', tokens }
  assert.ok(engine.validateTheme(base, tokens).theme)
  for (const patch of [{ id: "x'] body { color: red } html[x='" }, { id: 'Upper' }, { id: '' }, { id: 'a'.repeat(33) }, { labelKey: '<img src=x>' }, { colorScheme: 'sepia' }]) {
    assert.equal(engine.validateTheme({ ...base, ...patch }, tokens).theme, null, JSON.stringify(patch))
  }
  assert.throws(() => engine.themeSelector("black'] body{"))
  assert.equal(engine.themeSelector('dark'), ':root')
  assert.equal(engine.themeSelector('light'), "html[data-theme='light']")
})

test('serialized CSS contains only schema properties and safe value characters', () => {
  const allowedProps = new Set([...TOKENS.filter((t) => t.cssVar).map((t) => t.cssVar), 'color'])
  const random = (n) => Math.floor(Math.random() * n)
  const samples = [...engine.THEMES.map((t) => t.tokens)]
  for (let i = 0; i < 200; i++) {
    const tokens = {}
    for (const token of TOKENS) {
      if (token.type === 'rgb') tokens[token.name] = [random(256), random(256), random(256)]
      if (token.type === 'enum') tokens[token.name] = token.values[random(token.values.length)]
      if (token.type === 'overlay')
        tokens[token.name] = {
          kind: 'linear',
          angle: random(361),
          stops: [
            { color: [random(256), random(256), random(256), Math.random()], at: random(101) },
            { color: [random(256), random(256), random(256)], at: random(101) }
          ]
        }
    }
    samples.push(engine.validateTokens(tokens, null).tokens)
  }
  for (const tokens of samples) {
    const declarations = engine.themeDeclarations(tokens)
    for (const [property, value] of Object.entries(declarations)) {
      assert.ok(allowedProps.has(property), property)
      assert.match(value, /^[0-9a-z(),.%\s-]+$/i, value)
      assert.doesNotMatch(value, /url|expression|import|javascript|[;{}<>"'\\@]/i, value)
    }
    const css = engine.toCssText({ ':root': declarations })
    assert.equal((css.match(/\{/g) || []).length, 1)
  }
  // Serialization re-validates: a value smuggled past validation is dropped, not emitted
  const smuggled = { ...engine.getTheme('dark').tokens, 'surface.base': '0 0 0; } * { display: none' }
  assert.equal(engine.themeDeclarations(smuggled)['--color-primary'], undefined)
})

test('built-in theme data is immutable at runtime', () => {
  const dark = engine.getTheme('dark')
  assert.throws(() => {
    dark.tokens['surface.base'] = [255, 0, 0]
  })
  assert.throws(() => {
    dark.tokens['surface.base'][0] = 255
  })
  assert.throws(() => {
    engine.THEMES.push({})
  })
  assert.equal(engine.getTheme('dark').tokens['surface.base'][0], 35)
})

test('system-bar tokens match the native window background and current light-icon style', async () => {
  const colors = await read('../android/app/src/main/res/values/colors.xml')
  const hex = colors.match(/name="background_dark">#([0-9a-fA-F]{6})</)[1]
  const nativeRgb = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16))
  for (const theme of engine.THEMES) {
    assert.deepEqual(theme.tokens['system.status-bar'], nativeRgb, theme.id)
    assert.deepEqual(theme.tokens['system.navigation-bar'], nativeRgb, theme.id)
    assert.equal(theme.tokens['system.bar-icons'], 'light', theme.id)
  }
})

// ThemeService (plugins/theme.client.js) with mocked Vue/Capacitor/document boundaries
async function loadThemePlugin({ platform = 'android', stored = null, getThemeFails = false, refreshFails = false } = {}) {
  const source =
    (await read('../plugins/theme.client.js'))
      .replace(/^import .*$/gm, '')
      .replace('export class ThemeService', 'class ThemeService')
      .replace('export default', 'globalThis.plugin =') + '\nglobalThis.ThemeService = ThemeService'
  const calls = { setStyle: [], setTheme: [], refreshWidgets: [] }
  const store = { value: stored }
  const localStore = {
    getTheme: async () => (getThemeFails ? false : store.value),
    setTheme: async (value) => {
      calls.setTheme.push(value)
      store.value = value
    }
  }
  const AbsDatabase = {
    refreshWidgets: async (...args) => {
      // records what was saved when the bridge was called, and any arguments (there must be none)
      calls.refreshWidgets.push({ saved: store.value, args })
      if (refreshFails) throw new Error('not implemented')
    }
  }
  const root = { dataset: {} }
  const sandbox = {
    Vue: { observable: (o) => o },
    Capacitor: { getPlatform: () => platform },
    StatusBar: { setStyle: async (options) => calls.setStyle.push(options.style) },
    Style: { Dark: 'DARK', Light: 'LIGHT' },
    themeEngine: engine,
    AbsDatabase,
    document: { documentElement: root },
    console: { ...console, error: () => {} }
  }
  vm.runInNewContext(source, sandbox)
  let injected
  sandbox.plugin({ app: { $localStore: localStore } }, (name, value) => (injected = { name, value }))
  await injected.value.ready
  return { service: injected.value, name: injected.name, root, calls, store }
}

test('existing persisted selections restore unchanged: dark, black, light', async () => {
  for (const id of LEGACY_IDS) {
    const { service, name, root } = await loadThemePlugin({ stored: id })
    assert.equal(name, 'theme')
    assert.equal(service.id, id)
    assert.equal(root.dataset.theme, id)
  }
})

test('missing, unknown or unreadable persisted values restore the default without rewriting storage', async () => {
  for (const options of [{ stored: null }, { stored: 'sepia' }, { stored: "light'] *{" }, { getThemeFails: true }]) {
    const { service, root, calls } = await loadThemePlugin(options)
    assert.equal(service.id, 'dark')
    assert.equal(root.dataset.theme, 'dark')
    assert.deepEqual(calls.setTheme, [])
  }
})

test('selecting a theme applies and persists only valid ids', async () => {
  const { service, root, store } = await loadThemePlugin({ stored: 'dark' })
  assert.equal(await service.select('light'), 'light')
  assert.equal(root.dataset.theme, 'light')
  assert.equal(store.value, 'light')
  assert.equal(await service.select('<script>alert(1)</script>'), 'dark')
  assert.equal(root.dataset.theme, 'dark')
  assert.equal(store.value, 'dark')
})

test('selecting a theme asks the native widget to redraw after persisting, with no arguments', async () => {
  const { service, calls } = await loadThemePlugin({ stored: 'dark' })
  assert.deepEqual(calls.refreshWidgets, []) // restoring at startup never refreshes
  await service.select('llama')
  await new Promise((resolve) => setImmediate(resolve))
  assert.deepEqual(
    calls.refreshWidgets.map((c) => c.saved),
    ['llama']
  )
  assert.deepEqual(calls.refreshWidgets[0].args, [])
})

test('widget refresh failures never break a selection; web never calls the bridge', async () => {
  const failing = await loadThemePlugin({ stored: 'dark', refreshFails: true })
  assert.equal(await failing.service.select('light'), 'light')
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(failing.store.value, 'light')
  const web = await loadThemePlugin({ platform: 'web', stored: 'dark' })
  await web.service.select('llama')
  await new Promise((resolve) => setImmediate(resolve))
  assert.deepEqual(web.calls.refreshWidgets, [])
})

test('the status-bar icon style follows the theme token; web never touches the native status bar', async () => {
  const android = await loadThemePlugin({ stored: 'light' })
  // default applied immediately at startup, then the restored theme: both keep light icons today
  assert.deepEqual(android.calls.setStyle, ['DARK', 'DARK'])
  const web = await loadThemePlugin({ platform: 'web', stored: 'black' })
  assert.deepEqual(web.calls.setStyle, [])
  assert.equal(web.service.id, 'black')
})

test('the Settings options list comes from the registry in the original order and labels, then LLAMA and E-ink', async () => {
  const { service } = await loadThemePlugin()
  assert.deepEqual(
    service.themes.map((t) => [t.id, t.labelKey]),
    [
      ['black', 'LabelThemeBlack'],
      ['dark', 'LabelThemeDark'],
      ['light', 'LabelThemeLight'],
      ['llama', 'LabelThemeLlama'],
      ['eink', 'LabelThemeEink']
    ]
  )
  const strings = JSON.parse(await read('../strings/en-us.json'))
  for (const theme of service.themes) assert.equal(typeof strings[theme.labelKey], 'string')
})

// --- Phase 2C Gate H: actionable success fill (global) ---

const luminance = ([r, g, b]) => {
  const f = (v) => ((v /= 255) <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
}
const contrastRatio = (a, b) => {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p)
  return (x + 0.05) / (y + 0.05)
}

test('Gate H: success buttons use state.success-action, which carries white text at AA in every theme (and under the LLAMA sheen)', async () => {
  const presets = require('../theme/presets.js')
  const white = [255, 255, 255]
  for (const theme of engine.THEMES) {
    const t = theme.tokens
    if (t['presentation.finish'] === 'eink') {
      // E-ink: every state is ink (states read by icon and label on e-paper), and ink carries white text
      assert.deepEqual(t['state.success'], [0, 0, 0], theme.id)
      assert.deepEqual(t['state.success-action'], [0, 0, 0], theme.id)
    } else {
      // state.success keeps its pre-token value for indicators; the action fill is separate
      assert.deepEqual(t['state.success'], [76, 175, 80], theme.id)
      assert.deepEqual(t['state.success-action'], [46, 125, 50], theme.id)
      assert.ok(contrastRatio(white, t['state.success']) < 4.5, 'state.success alone is not a white-text fill')
    }
    assert.ok(contrastRatio(white, t['state.success-action']) >= 4.5, `${theme.id} white on success-action`)
    if (t['presentation.finish'] === 'equipment') {
      // Brightest point of the steel sheen on .btn: edge-light at 18% over the fill
      const edge = presets.equipmentDerivedDeclarations(t)['--color-edge-light'].split(' ').map(Number)
      const top = t['state.success-action'].map((c, i) => edge[i] * 0.18 + c * 0.82)
      assert.ok(contrastRatio(white, top) >= 4.5, `${theme.id} white on sheened success-action`)
    }
  }
  const btn = await read('../components/ui/Btn.vue')
  assert.match(btn, /if \(this\.color === 'success'\) \{\s*\/\/[^\n]*\n\s*list\.push\('text-white', 'bg-success-action'\)\s*\} else \{\s*list\.push\(`bg-\$\{this\.color\}`\)/)
  const bookmarks = await read('../components/modals/BookmarksModal.vue')
  assert.match(bookmarks, /bg-success-action cursor-pointer text-white sticky/)
  assert.doesNotMatch(bookmarks, /text-opacity-80/)
})

test('Gate H: the Tailwind build emits bg-success-action from the theme variable', async () => {
  const postcss = require('postcss')
  const tailwind = require('tailwindcss')
  const config = require('../tailwind.config.js')
  const source = '@tailwind base;\n@tailwind utilities;'
  const { css } = await postcss([tailwind({ ...config, content: [{ raw: '<div class="bg-success-action bg-success"></div>' }] })]).process(source, { from: undefined })
  assert.ok(css.includes('--color-success-action: 46 125 50'))
  assert.ok(css.includes('background-color: rgb(var(--color-success-action) / var(--tw-bg-opacity, 1))'))
  assert.ok(css.includes('background-color: rgb(var(--color-success) / var(--tw-bg-opacity, 1))'))
})
