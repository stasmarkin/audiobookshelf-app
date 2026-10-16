// RICE controller + the existing $theme service (plugins/theme.client.js), with fake document, Preferences, native
// appearance bridge and widgets: enable/disable, starters, presets, built-in cleanup, restart, recovery, device mode
// ordering/de-duplication, and the boundaries (no playback calls, built-in output unchanged).
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import vm from 'node:vm'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const { RiceController } = require('../theme/rice/controller.js')
const { PresetStore, KEYS } = require('../theme/rice/store.js')
const { STARTERS } = require('../theme/rice/catalog.js')
const runtime = require('../theme/rice/runtime.js')
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const fixture = JSON.parse(await read('./fixtures/rice-device-mapping.json'))

function fakeDocument() {
  const nodes = []
  const head = {
    appendChild(node) {
      node.parentNode = head
      nodes.push(node)
    },
    removeChild(node) {
      nodes.splice(nodes.indexOf(node), 1)
      node.parentNode = null
    }
  }
  return {
    nodes,
    head,
    documentElement: { dataset: {} },
    getElementById: (id) => nodes.find((n) => n.id === id) || null,
    createElement: () => {
      let text = ''
      const node = { id: '', writes: 0 }
      Object.defineProperty(node, 'textContent', {
        get: () => text,
        set: (v) => {
          node.writes++
          text = v
        }
      })
      return node
    }
  }
}

function fakePrefs(initial = {}) {
  const data = { ...initial }
  return { data, get: async (k) => (k in data ? data[k] : null), set: async (k, v) => void (data[k] = v) }
}

async function loadThemeService(doc, localTheme) {
  const source =
    (await read('../plugins/theme.client.js'))
      .replace(/^import .*$/gm, '')
      .replace('export class ThemeService', 'class ThemeService')
      .replace('export default', 'globalThis.plugin =') + '\nglobalThis.ThemeService = ThemeService'
  const calls = { setStyle: [], refreshWidgets: 0 }
  const sandbox = {
    Vue: { observable: (o) => o },
    Capacitor: { getPlatform: () => 'android' },
    StatusBar: { setStyle: async (o) => calls.setStyle.push(o.style) },
    Style: { Dark: 'DARK', Light: 'LIGHT' },
    themeEngine: engine,
    AbsDatabase: { refreshWidgets: async () => calls.refreshWidgets++ },
    document: doc,
    console: { ...console, error: () => {} }
  }
  vm.runInNewContext(source, sandbox)
  let service
  sandbox.plugin({ app: { $localStore: { getTheme: async () => localTheme.value, setTheme: async (v) => void (localTheme.value = v) } } }, (name, value) => (service = value))
  await service.ready
  return { service, calls }
}

function fakeAppearance() {
  const calls = { native: [] }
  let listener = null
  let current = { apiLevel: 34, paletteAvailable: true, night: true, contrast: 0, fontScale: 1, sessionId: 's1', sequence: 1, paletteRevision: 'a', tones: fixture.cases[0].tones }
  return {
    calls,
    set(snapshot) {
      current = { ...current, ...snapshot }
    },
    emit(snapshot) {
      current = { ...current, ...snapshot }
      listener(current)
    },
    getDeviceAppearance: async () => ({ ...current }),
    addListener: async (name, fn) => {
      assert.equal(name, 'deviceAppearanceChanged')
      listener = fn
    },
    applyNativeAppearance: async (options) => calls.native.push(options)
  }
}

let counter = 0
async function setup({ prefs = fakePrefs({ theme: 'dark' }), appearance = fakeAppearance() } = {}) {
  const doc = fakeDocument()
  const local = { value: prefs.data.theme || null }
  const { service, calls } = await loadThemeService(doc, local)
  const rice = new RiceController({ service, store: new PresetStore(prefs), doc, appearance, now: () => '2026-10-12T00:00:00.000Z', randomBytes: (n) => new Uint8Array(n).fill(++counter % 256) })
  service.attachRice(rice)
  await rice.init()
  await rice.subscribe()
  await new Promise((r) => setImmediate(r))
  return { doc, service, rice, prefs, appearance, calls }
}

const marker = (doc) => doc.documentElement.dataset.rice
const styleNode = (doc) => doc.getElementById(runtime.STYLE_ID)

test('RICE starts off for existing users, and enabling it does not recolor anything', async () => {
  const { doc, service, rice, prefs } = await setup({ prefs: fakePrefs({ theme: 'light' }) })
  assert.equal(rice.state.riceEnabled, false)
  assert.equal(service.id, 'light')
  assert.equal(marker(doc), undefined)
  assert.equal(prefs.data[KEYS.head], undefined, 'nothing written until the user acts')
  await rice.setRiceEnabled(true)
  assert.equal(rice.state.riceEnabled, true)
  assert.equal(styleNode(doc), null)
  assert.equal(service.theme, engine.getTheme('light'))
})

test('selecting a starter applies one scoped style node, the base anchor and a validated theme object', async () => {
  const { doc, service, rice, calls, appearance } = await setup()
  await rice.setRiceEnabled(true)
  await rice.select({ kind: 'starter', id: 'catppuccin-latte' })
  assert.equal(marker(doc), 'active')
  assert.equal(doc.documentElement.dataset.theme, 'light')
  assert.equal(doc.nodes.filter((n) => n.id === runtime.STYLE_ID).length, 1)
  assert.match(styleNode(doc).textContent, /^html\[data-theme\]\[data-rice='active'\] \{ --color-primary: 239 241 245;/)
  assert.equal(service.theme.id, 'rice-custom')
  assert.equal(service.theme.colorScheme, 'light')
  assert.equal(service.theme.tokens['presentation.cover-color'], 'theme')
  assert.equal(service.id, 'light', 'legacy callers still see a built-in base id')
  assert.ok(calls.refreshWidgets >= 1)
  assert.deepEqual(appearance.calls.native.at(-1).background, [239, 241, 245])
  assert.equal(appearance.calls.native.at(-1).enabled, true)
  assert.equal(rice.envelope.widget.family, 'standard')
})

test('every built-in afterwards removes all RICE traces and is exactly the original theme', async () => {
  const { doc, service, rice, appearance } = await setup()
  await rice.setRiceEnabled(true)
  for (const { id } of engine.THEMES) {
    await rice.select({ kind: 'starter', id: 'dracula' })
    assert.equal(marker(doc), 'active')
    // The existing Settings picker: same call as before RICE
    await service.select(id)
    assert.equal(styleNode(doc), null, id)
    assert.equal(marker(doc), undefined, id)
    assert.equal(doc.documentElement.dataset.theme, id)
    assert.equal(service.theme, engine.getTheme(id), `${id}: the frozen built-in object`)
    assert.equal(appearance.calls.native.at(-1).enabled, false)
    assert.equal(rice.envelope.widget, null)
    assert.deepEqual(rice.envelope.selection, { kind: 'builtin', id })
  }
})

test('E-ink: a saved E-ink choice survives a cold start, before and after the first RICE commit', async () => {
  const { doc, service, rice, prefs } = await setup({ prefs: fakePrefs({ theme: 'eink' }) })
  assert.equal(service.id, 'eink', 'RICE startup keeps the legacy E-ink choice')
  assert.equal(doc.documentElement.dataset.theme, 'eink')
  await rice.setRiceEnabled(true)
  assert.equal(rice.envelope.lastBuiltin, 'eink')
  const reopened = await setup({ prefs: fakePrefs(prefs.data) })
  assert.equal(reopened.service.id, 'eink', 'the committed store restores E-ink')
  assert.equal(reopened.doc.documentElement.dataset.theme, 'eink')
})

test('turning RICE off restores the last built-in and keeps presets; "use last" brings the custom choice back', async () => {
  const { doc, service, rice } = await setup()
  await service.select('black')
  await rice.setRiceEnabled(true)
  const { presetId } = await rice.saveDraft({ snapshot: STARTERS[3].snapshot, name: 'Nordish', source: { kind: 'starter', id: 'nord' } })
  assert.equal(rice.state.kind, 'preset')
  await rice.restoreDefaults()
  assert.equal(rice.state.riceEnabled, false)
  assert.equal(service.id, 'black')
  assert.equal(marker(doc), undefined)
  assert.equal(rice.state.presets.length, 1)
  await rice.setRiceEnabled(true)
  assert.equal(marker(doc), undefined, 're-enabling does not reapply the remembered theme')
  await rice.useRemembered()
  assert.deepEqual(rice.envelope.selection, { kind: 'preset', id: presetId })
  assert.equal(marker(doc), 'active')
})

test('presets: save-only keeps the active appearance; editing the active preset updates $theme.theme (same id, new object)', async () => {
  const { service, rice } = await setup()
  await rice.setRiceEnabled(true)
  await rice.select({ kind: 'starter', id: 'nord' })
  const { presetId } = await rice.saveDraft({ snapshot: STARTERS[0].snapshot, name: 'Saved only', source: { kind: 'starter', id: STARTERS[0].id }, mode: 'save' })
  assert.deepEqual(rice.envelope.selection, { kind: 'starter', id: 'nord' })
  await rice.select({ kind: 'preset', id: presetId })
  const before = service.theme
  const edited = JSON.parse(JSON.stringify(STARTERS[0].snapshot))
  edited.tokens['accent.primary'] = [250, 179, 135]
  await rice.saveDraft({ snapshot: edited, name: 'Saved only', source: { kind: 'starter', id: STARTERS[0].id }, presetId })
  assert.notEqual(service.theme, before)
  assert.deepEqual(service.theme.tokens['accent.primary'], [250, 179, 135])
  await rice.rename(presetId, 'Renamed')
  await rice.duplicate(presetId, 'Renamed copy')
  assert.deepEqual(
    rice.state.presets.map((p) => p.name),
    ['Renamed', 'Renamed copy']
  )
  await rice.remove(presetId)
  assert.equal(rice.state.kind, 'builtin', 'deleting the active preset returns to the last built-in')
})

test('a draft that fails contrast cannot be saved or applied', async () => {
  const { rice } = await setup()
  await rice.setRiceEnabled(true)
  const bad = JSON.parse(JSON.stringify(STARTERS[0].snapshot))
  bad.tokens['text.primary'] = bad.tokens['surface.base'].slice()
  await assert.rejects(rice.saveDraft({ snapshot: bad, name: 'Unreadable', source: { kind: 'manual' } }), { code: 'contrast' })
  assert.equal(rice.state.presets.length, 0)
})

test('restart / WebView recreation restores the committed appearance without network or reselecting', async () => {
  const prefs = fakePrefs({ theme: 'dark' })
  const first = await setup({ prefs })
  await first.rice.setRiceEnabled(true)
  await first.rice.select({ kind: 'starter', id: 'everforest-dark' })
  const second = await setup({ prefs })
  assert.equal(marker(second.doc), 'active')
  assert.equal(styleNode(second.doc).textContent, styleNode(first.doc).textContent)
  assert.equal(second.rice.state.kind, 'starter')
})

test('a corrupt store falls back to the built-in and reports recovery', async () => {
  const prefs = fakePrefs({ theme: 'dark' })
  const first = await setup({ prefs })
  await first.rice.setRiceEnabled(true)
  await first.rice.select({ kind: 'starter', id: 'nord' })
  // Current slot (B) damaged: the referenced previous slot (A, RICE on with the built-in) is used
  prefs.data[KEYS.slotB] = prefs.data[KEYS.slotB].replace('nord', 'dracula')
  const previous = await setup({ prefs })
  assert.equal(previous.rice.state.recovery, 'previous')
  assert.equal(previous.rice.state.riceEnabled, true)
  // Both damaged: the validated legacy built-in
  prefs.data[KEYS.slotA] = prefs.data[KEYS.slotA].replace('dark', 'drak')
  const again = await setup({ prefs })
  assert.equal(again.rice.state.recovery, 'legacy')
  assert.equal(marker(again.doc), undefined)
  assert.equal(again.service.id, 'dark')
})

test('device mode follows night changes, ignores stale and duplicate events, and accepts a new native session', async () => {
  const { doc, rice, calls } = await setup()
  await rice.setRiceEnabled(true)
  await rice.select({ kind: 'device', mode: 'follow' })
  assert.equal(rice.state.kind, 'device')
  assert.equal(doc.documentElement.dataset.theme, 'dark')
  const writes = styleNode(doc).writes
  const refreshes = calls.refreshWidgets
  rice.onDeviceAppearance({ ...rice.device, sequence: rice.device.sequence + 1, night: false, paletteRevision: 'a' })
  assert.equal(doc.documentElement.dataset.theme, 'light', 'night off -> light device palette')
  await new Promise((r) => setImmediate(r))
  assert.equal(calls.refreshWidgets, refreshes + 1)
  assert.equal(rice.onDeviceAppearance({ ...rice.device, sequence: rice.device.sequence - 1, night: true }), false, 'older sequence ignored')
  assert.equal(doc.documentElement.dataset.theme, 'light')
  const w = styleNode(doc).writes
  assert.equal(rice.onDeviceAppearance({ ...rice.device, sequence: rice.device.sequence + 1 }), false, 'same content: no work')
  assert.equal(styleNode(doc).writes, w)
  assert.ok(rice.onDeviceAppearance({ ...rice.device, sessionId: 's2', sequence: 1, night: true }), 'a recreated plugin starts a new session')
  assert.equal(doc.documentElement.dataset.theme, 'dark')
  assert.ok(styleNode(doc).writes > writes)
})

test('"always dark" ignores the device light setting; without a platform palette device mode follows light/dark with built-ins', async () => {
  const { doc, rice } = await setup()
  await rice.setRiceEnabled(true)
  await rice.select({ kind: 'device', mode: 'dark' })
  rice.onDeviceAppearance({ ...rice.device, sequence: 99, night: false, paletteRevision: 'b' })
  assert.equal(doc.documentElement.dataset.theme, 'dark')
  const old = fakeAppearance()
  old.set({ apiLevel: 30, paletteAvailable: false, tones: null, night: false })
  const legacy = await setup({ appearance: old })
  await legacy.rice.setRiceEnabled(true)
  await legacy.rice.select({ kind: 'device', mode: 'follow' })
  assert.equal(marker(legacy.doc), undefined, 'Android 11: built-in Light, no custom CSS')
  assert.equal(legacy.service.theme, engine.getTheme('light'))
  assert.equal(legacy.rice.state.error, 'device-unavailable')
})

test('device events do nothing while another appearance is selected', async () => {
  const { doc, rice, calls } = await setup()
  await rice.setRiceEnabled(true)
  await rice.select({ kind: 'starter', id: 'nord' })
  const css = styleNode(doc).textContent
  const refreshes = calls.refreshWidgets
  rice.onDeviceAppearance({ ...rice.device, sequence: 50, night: false, paletteRevision: 'z' })
  assert.equal(styleNode(doc).textContent, css)
  assert.equal(calls.refreshWidgets, refreshes)
})

test('freezing device colors into a preset makes an independent snapshot', async () => {
  const { rice } = await setup()
  await rice.setRiceEnabled(true)
  const from = rice.sourceFor({ kind: 'device', mode: 'follow' })
  assert.deepEqual(from.source, { kind: 'device', mode: 'follow' })
  const { presetId } = await rice.saveDraft({ snapshot: from.snapshot, name: 'Wallpaper today', source: from.source })
  rice.onDeviceAppearance({ ...rice.device, sequence: 77, night: false, paletteRevision: 'c' })
  assert.deepEqual(rice.resolve({ kind: 'preset', id: presetId }).snapshot, from.snapshot)
})

test('concurrent changes: the store serializes them and the stale one fails', async () => {
  const { rice } = await setup()
  await rice.setRiceEnabled(true)
  const a = rice.select({ kind: 'starter', id: 'nord' })
  const b = rice.select({ kind: 'starter', id: 'dracula' })
  await a
  await assert.rejects(b, { code: 'stale-revision' })
  assert.deepEqual(rice.envelope.selection, { kind: 'starter', id: 'nord' })
})

test('boundaries: RICE code never reaches playback, queue, downloads, auth or arbitrary CSS', async () => {
  for (const file of ['controller.js', 'runtime.js', 'store.js', 'mapping.js', 'catalog.js', 'import/parsers.js']) {
    const source = await read(`../theme/rice/${file}`)
    assert.doesNotMatch(source, /AbsAudioPlayer|playbackQueue|AbsDownloader|nativeHttp|\$axios|authRenewal|refreshToken|innerHTML|v-html|eval\(|new Function/, file)
  }
  assert.throws(() => runtime.buildRiceCss({ baseId: 'dark' }), /Invalid RICE snapshot/)
})

test('the applied appearance never restyles the editor preview, and the preview states its own type', () => {
  const custom = JSON.parse(JSON.stringify(STARTERS[0].snapshot))
  Object.assign(custom.appearance, { radius: 12, textScale: 1.2, borderWidth: 2, frame: 'inset', displayFont: 'ubuntu-mono' })
  const committed = runtime.buildRiceCss(custom)
  const scoped = committed.split('\n').slice(1)
  assert.ok(scoped.length >= 10)
  for (const rule of scoped) for (const selector of rule.slice(0, rule.indexOf(' {')).split(', ')) assert.match(selector, /:not\(\.rice-preview\):not\(\.rice-preview \*\)$/, selector)
  const preview = runtime.buildRiceCss(STARTERS[1].snapshot, '.rice-preview')
  assert.match(preview, /^\.rice-preview \{ --color-primary: /)
  assert.match(preview, /\.rice-preview \{ font-family: 'Source Sans Pro'/)
  assert.match(preview, /\.rice-preview \{ font-weight: 400; \}/)
  assert.doesNotMatch(preview, /html/)
})

test('the player play glyph is black on light RICE schemes and unchanged for built-ins (S22 Follow device, light)', async () => {
  const { coverColorPresentation } = require('../theme/coverPresentation.js')
  const player = await read('../components/app/AudioPlayer.vue')
  const binding = player.match(/<span v-if="!showLoadingState" class="material-symbols fill" :class="(\{[^"]*\})">/)
  assert.ok(binding, 'play glyph class binding')
  const classes = (coverChrome) =>
    Object.entries(vm.runInNewContext(`(${binding[1]})`, { coverChrome, seekLoading: false }))
      .filter(([, on]) => on)
      .map(([c]) => c)
  const lightRice = coverColorPresentation({ id: 'light', colorScheme: 'light', tokens: { 'presentation.cover-color': 'theme' } }, { rgb: 'rgb(9, 9, 9)', isLight: false })
  const darkRice = coverColorPresentation({ id: 'dark', colorScheme: 'dark', tokens: { 'presentation.cover-color': 'theme' } }, { rgb: 'rgb(9, 9, 9)', isLight: false })
  assert.deepEqual(classes(lightRice), ['text-black']) // the contrast model's '#black' glyph pair (theme/rice/contrast.js)
  assert.deepEqual(classes(darkRice), ['text-white'])
  // Built-ins (legacy cover policy, and LLAMA's dark theme policy) render exactly as before
  for (const id of ['dark', 'black', 'light', 'llama']) {
    for (const cover of [
      { rgb: 'rgb(9, 9, 9)', isLight: false },
      { rgb: 'rgb(240, 240, 240)', isLight: true }
    ]) {
      const chrome = coverColorPresentation(engine.getTheme(id), cover)
      assert.deepEqual(classes(chrome), chrome.control && !chrome.isLight ? ['text-white'] : [], `${id} ${cover.rgb}`)
    }
  }
})
