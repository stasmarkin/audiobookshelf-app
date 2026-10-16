// RICE core (docs/rice-customization.md): strict schema, bounded JSON, contrast pair graph, deterministic role mapping,
// device mapping parity fixture and the ten-starter catalog with its pinned sources and bundled notices.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, access } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createHash } from 'node:crypto'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const S = require('../theme/rice/schema.js')
const { parseStrictJson } = require('../theme/rice/strictJson.js')
const { sha256Hex } = require('../theme/rice/sha256.js')
const { contrast, luminance, parseHex } = require('../theme/rice/color.js')
const { evaluateContrast, repairColor } = require('../theme/rice/contrast.js')
const { mapPalette, mapDevice, snapshotFromTheme, widgetColors } = require('../theme/rice/mapping.js')
const { STARTERS } = require('../theme/rice/catalog.js')

const clone = (v) => JSON.parse(JSON.stringify(v))
const starter = (id) => STARTERS.find((s) => s.id === id)
const validSnapshot = () => clone(starter('catppuccin-mocha').snapshot)

test('sha256 matches node:crypto for ASCII, multi-byte and long input', () => {
  for (const text of ['', 'abc', 'é✓😀'.repeat(40), 'x'.repeat(5000), '{"a":1}']) assert.equal(sha256Hex(text), createHash('sha256').update(text, 'utf8').digest('hex'))
})

test('strict JSON rejects duplicates, prototype keys, trailing data and over-limit input', () => {
  assert.deepEqual(parseStrictJson('{"a":[1,2,{"b":"x"}],"c":null}'), { a: [1, 2, { b: 'x' }], c: null })
  for (const bad of ['{"a":1,"a":2}', '{"__proto__":{"x":1}}', '{"x":{"constructor":1}}', '{"prototype":1}', '[1,]', '{"a":1}x', '{"a":NaN}', '"\u0001"']) {
    assert.throws(() => parseStrictJson(bad), { name: 'StrictJsonError' }, bad)
  }
  assert.throws(() => parseStrictJson('[[[[[[[[[1]]]]]]]]]', { maxDepth: 8 }), /too deep/)
  assert.throws(() => parseStrictJson(JSON.stringify('x'.repeat(100)), { maxBytes: 50 }), /too large/)
  assert.throws(() => parseStrictJson(JSON.stringify(Array(50).fill(1)), { maxMembers: 10 }), /too many members/)
  const parsed = parseStrictJson('{"a":{"b":1}}')
  assert.equal(Object.getPrototypeOf(parsed), Object.prototype)
  assert.equal({}.polluted, undefined)
})

test('snapshot schema accepts every starter and rejects unknown, missing and malformed fields', () => {
  for (const s of STARTERS) assert.ok(S.validateSnapshot(s.snapshot).ok, s.id)
  const cases = [
    ['unknown root key', (x) => (x.extra = 1)],
    ['missing token', (x) => delete x.tokens['surface.base']],
    ['unknown token', (x) => (x.tokens['surface.extra'] = [1, 2, 3])],
    ['fractional channel', (x) => (x.tokens['surface.base'] = [1.5, 2, 3])],
    ['string channel', (x) => (x.tokens['surface.base'] = ['1', 2, 3])],
    ['four channels', (x) => (x.tokens['surface.base'] = [1, 2, 3, 4])],
    ['out of range', (x) => (x.tokens['surface.base'] = [256, 0, 0])],
    ['css string', (x) => (x.tokens['accent.primary'] = 'red; background:url(x)')],
    ['gradient overlay', (x) => (x.tokens['overlay.player'] = { kind: 'linear', angle: 0, stops: [] })],
    ['alpha overlay', (x) => (x.tokens['overlay.player'] = { kind: 'solid', color: [1, 2, 3, 0.5] })],
    ['equipment finish on a standard base', (x) => (x.tokens['presentation.finish'] = 'equipment')],
    ['legacy cover policy', (x) => (x.tokens['presentation.cover-color'] = 'legacy')],
    ['bad enum', (x) => (x.appearance.radius = 6)],
    ['unknown appearance key', (x) => (x.appearance.glow = 'max')],
    ['scheme mismatch', (x) => (x.colorScheme = 'light')],
    ['unknown base', (x) => (x.baseId = 'rice-custom')],
    ['wrong prototype', (x) => Object.setPrototypeOf(x.tokens, { injected: true })]
  ]
  for (const [name, mutate] of cases) {
    const x = validSnapshot()
    mutate(x)
    assert.equal(S.validateSnapshot(x).ok, false, name)
  }
})

test('LLAMA copies keep the frozen recipe: geometry and type options are locked', () => {
  const { snapshot } = snapshotFromTheme(engine.getTheme('llama'))
  assert.equal(snapshot.baseId, 'llama')
  assert.equal(snapshot.tokens['presentation.finish'], 'equipment')
  assert.ok(S.validateSnapshot(snapshot).ok)
  const changed = clone(snapshot)
  changed.appearance.radius = 12
  assert.equal(S.validateSnapshot(changed).ok, false)
})

test('E-ink copies keep its colors exactly on the Light base; E-ink stays a selectable built-in', () => {
  const eink = engine.getTheme('eink')
  const { snapshot, adjustments } = snapshotFromTheme(eink)
  assert.equal(snapshot.baseId, 'light')
  assert.equal(snapshot.tokens['presentation.finish'], 'standard', 'the paint-only finish is not a custom option')
  assert.deepEqual(adjustments, [], 'E-ink passes the RICE contrast graph as it is')
  for (const name of ['surface.base', 'text.primary', 'text.muted', 'border.default', 'accent.primary', 'progress.played', 'progress.track', 'state.error', 'state.success-action']) {
    assert.deepEqual(snapshot.tokens[name], eink.tokens[name], name)
  }
  assert.ok(S.validateSnapshot(snapshot).ok)
  assert.ok(evaluateContrast(snapshot).ok)
  assert.ok(S.validateSelection({ kind: 'builtin', id: 'eink' }).ok)
  assert.ok(S.validateSource({ kind: 'builtin', id: 'eink' }).ok)
  assert.ok(!S.BASE_IDS.includes('eink'))
})

test('preset, selection and envelope validation', () => {
  const preset = { format: S.PRESET_FORMAT, schemaVersion: 1, tokenSchemaVersion: 1, id: 'p-0123456789abcdef01234567', name: 'My theme', createdAt: '2026-10-12T00:00:00.000Z', updatedAt: '2026-10-12T00:00:00.000Z', snapshot: validSnapshot(), reset: validSnapshot(), source: { kind: 'starter', id: 'catppuccin-mocha' } }
  assert.ok(S.validatePreset(preset).ok)
  for (const [name, mutate] of [
    ['long name', (p) => (p.name = 'x'.repeat(49))],
    ['empty name', (p) => (p.name = '')],
    ['padded name', (p) => (p.name = ' x')],
    ['control character', (p) => (p.name = 'a\u0007b')],
    ['bad id', (p) => (p.id = 'p-XYZ')],
    ['future schema', (p) => (p.schemaVersion = 2)],
    ['image source (reserved)', (p) => (p.source = { kind: 'image' })],
    ['source extra field', (p) => (p.source.url = 'https://example.com')]
  ]) {
    const p = clone(preset)
    mutate(p)
    assert.equal(S.validatePreset(p).ok, false, name)
  }
  assert.equal(S.normalizeName('  Hello   world '), 'Hello world')
  assert.equal(S.normalizeName('😀'.repeat(48)), '😀'.repeat(48), '48 code points, not UTF-16 units')
  assert.equal(S.normalizeName('😀'.repeat(49)), null)
  assert.ok(S.validateSelection({ kind: 'device', mode: 'follow' }).ok)
  assert.equal(S.validateSelection({ kind: 'device', mode: 'light' }).ok, false, 'future modes are rejected, not treated as enabled')
  assert.equal(S.validateSelection({ kind: 'starter', id: 'https://evil.example' }).ok, false)
  assert.match(
    S.newPresetId((n) => new Uint8Array(n).fill(171)),
    /^p-(ab){12}$/
  )
})

test('github source records keep only canonical, bounded provenance', () => {
  const source = {
    kind: 'github',
    url: 'https://github.com/owner/repo',
    owner: 'owner',
    repo: 'repo',
    commit: 'a'.repeat(40),
    path: 'Colors/colors',
    sha256: 'b'.repeat(64),
    format: 'hex-list-v1',
    colors: [
      [1, 2, 3],
      [4, 5, 6]
    ],
    mappingVersion: 1,
    attribution: 'owner/repo',
    license: 'unknown',
    adjustments: []
  }
  assert.ok(S.validateSource(source).ok)
  for (const [name, patch] of [
    ['query in url', { url: 'https://github.com/owner/repo?token=x' }],
    ['other host', { url: 'https://evil.example/owner/repo' }],
    ['traversal', { path: '../secret' }],
    ['absolute path', { path: '/etc/passwd' }],
    ['short commit', { commit: 'abc' }],
    ['unknown format', { format: 'gtk-css' }],
    ['too many colors', { colors: Array(25).fill([1, 2, 3]) }]
  ])
    assert.equal(S.validateSource({ ...source, ...patch }).ok, false, name)
})

test('contrast math matches WCAG reference values and is never rounded', () => {
  assert.equal(contrast([255, 255, 255], [0, 0, 0]), 21)
  assert.ok(Math.abs(luminance([255, 255, 255]) - 1) < 1e-12)
  // #777 on white is 4.478:1 - just under 4.5 - and must fail
  const report = evaluateContrast({ ...validSnapshot(), tokens: { ...validSnapshot().tokens, 'text.muted': [119, 119, 119], 'surface.base': [255, 255, 255] } })
  assert.ok(report.failures.some((f) => f.a === 'text.muted' && f.ratio > 4.47 && f.ratio < 4.5))
})

test('repair finds the smallest passing change, deterministically', () => {
  const bg = [30, 30, 46]
  const r1 = repairColor([88, 91, 112], [{ against: bg, min: 4.5 }])
  const r2 = repairColor([88, 91, 112], [{ against: bg, min: 4.5 }])
  assert.ok(r1.changed && r1.satisfied)
  assert.deepEqual(r1.color, r2.color)
  assert.ok(contrast(r1.color, bg) >= 4.5)
  assert.ok(luminance(r1.color) > luminance([88, 91, 112]), 'moved toward white on a dark background')
  assert.deepEqual(repairColor([255, 255, 255], [{ against: bg, min: 4.5 }]).changed, false)
  assert.equal(repairColor([128, 128, 128], [{ against: [128, 128, 128], min: 22 }]).satisfied, false)
})

test('mapPalette produces a complete validated snapshot, derives missing roles and records adjustments', () => {
  const { snapshot, adjustments } = mapPalette({ background: parseHex('#172126'), foreground: parseHex('#F5E9C8'), accent: parseHex('#FF7D86'), progress: parseHex('#FEBBA2') })
  assert.ok(S.validateSnapshot(snapshot).ok)
  assert.ok(evaluateContrast(snapshot).ok)
  assert.equal(snapshot.colorScheme, 'dark')
  assert.deepEqual(snapshot.tokens['surface.base'], [23, 33, 38])
  assert.ok(Array.isArray(adjustments))
  const light = mapPalette({ background: [253, 246, 227], foreground: [101, 123, 131] })
  assert.equal(light.snapshot.baseId, 'light')
  assert.equal(light.snapshot.tokens['system.bar-icons'], 'dark')
  assert.ok(evaluateContrast(light.snapshot).ok)
  const strong = mapPalette({ background: [30, 30, 46], foreground: [140, 140, 150], highContrast: true })
  assert.ok(evaluateContrast(strong.snapshot, { highContrast: true }).ok, 'high contrast targets 7:1 text')
})

test('built-in copies take the theme as it is and repair only the copy', () => {
  for (const theme of engine.THEMES) {
    const before = JSON.stringify(theme.tokens)
    const { snapshot } = snapshotFromTheme(theme)
    assert.ok(S.validateSnapshot(snapshot).ok, theme.id)
    assert.ok(evaluateContrast(snapshot).ok, theme.id)
    assert.equal(JSON.stringify(theme.tokens), before, `${theme.id} built-in untouched`)
    assert.ok(Object.isFrozen(engine.getTheme(theme.id).tokens))
  }
})

test('device mapping: Android 12+ tones map to validated snapshots; no palette means no mapping', async () => {
  const fixture = JSON.parse(await readFile(new URL('./fixtures/rice-device-mapping.json', import.meta.url), 'utf8'))
  assert.equal(fixture.cases.length, 12)
  for (const c of fixture.cases) {
    const mapped = mapDevice(c.tones, { night: c.night, highContrast: c.highContrast })
    assert.ok(S.validateSnapshot(mapped.snapshot).ok, c.name)
    assert.equal(mapped.snapshot.colorScheme, c.night ? 'dark' : 'light')
    assert.deepEqual(widgetColors(mapped.snapshot), c.expected, `${c.name} night=${c.night} hc=${c.highContrast} (shared with DevicePaletteMapperTest.kt)`)
  }
  assert.equal(mapDevice(null, { night: true }), null)
  assert.equal(mapDevice({ neutral1: {} }, { night: true }), null, 'missing tones are unavailable, never black')
})

test('starter catalog: ten pinned, attributable, contrast-passing adaptations kept apart from the built-ins', async () => {
  assert.deepEqual(
    STARTERS.map((s) => s.id),
    S.STARTER_IDS
  )
  assert.equal(STARTERS.length, 10)
  for (const s of STARTERS) assert.ok(!engine.THEMES.some((t) => t.id === s.id), `${s.id}: starters are not built-in themes`)
  for (const s of STARTERS) {
    assert.ok(S.validateSnapshot(s.snapshot).ok, s.id)
    assert.ok(evaluateContrast(s.snapshot).ok, s.id)
    assert.deepEqual(s.unresolved, [], s.id)
    assert.match(s.attribution.commit, /^[0-9a-f]{40}$/)
    assert.ok(s.attribution.source.includes(s.attribution.commit))
    assert.ok(s.attribution.notices.length > 0)
    for (const notice of s.attribution.notices) await access(new URL(`../static${notice}`, import.meta.url))
    assert.ok(Object.isFrozen(s.snapshot.tokens))
  }
  assert.match(starter('dracula').attribution.note, /not Dracula PRO/)
  assert.match(starter('tokyo-night').attribution.license, /Apache-2\.0/)
  assert.match(starter('gruvbox-dark').attribution.license, /declared in README/)
  // Anchors are the source values; only recorded adjustments may differ
  const mocha = starter('catppuccin-mocha').snapshot.tokens
  assert.deepEqual(mocha['surface.base'], [30, 30, 46])
  assert.deepEqual(mocha['accent.primary'], [203, 166, 247])
  const solarized = starter('solarized-dark')
  assert.ok(
    solarized.adjustments.some((a) => a.token === 'text.primary'),
    'Solarized base0 text is corrected and the correction is recorded'
  )
})

test('starter snapshots are pinned: any mapper or anchor change is a reviewed fixture change', async () => {
  const digest = createHash('sha256')
    .update(JSON.stringify(STARTERS.map((s) => [s.id, s.snapshot])))
    .digest('hex')
  const pinned = (await readFile(new URL('./fixtures/rice-catalog.sha256', import.meta.url), 'utf8')).trim()
  assert.equal(digest, pinned)
})
