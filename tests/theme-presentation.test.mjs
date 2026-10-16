import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const presets = require('../theme/presets.js')
const { TOKENS } = require('../theme/tokens.js')

const STANDARD_IDS = ['dark', 'black', 'light']
const policyTokens = TOKENS.filter((t) => t.name.startsWith('presentation.'))

// A validated synthetic theme using the equipment finish (independent of any built-in palette)
function equipmentTheme(id = 'rig') {
  const dark = engine.getTheme('dark')
  const { theme, errors } = engine.validateTheme({ id, labelKey: 'LabelRig', colorScheme: 'dark', tokens: { ...dark.tokens, 'presentation.finish': 'equipment', 'presentation.cover-color': 'theme' } }, dark.tokens)
  assert.deepEqual(errors, [])
  return theme
}

test('surface.recessed and both presentation policies are part of the schema', () => {
  const recessed = TOKENS.find((t) => t.name === 'surface.recessed')
  assert.equal(recessed.type, 'rgb')
  assert.equal(recessed.cssVar, '--color-recessed')
  assert.deepEqual(
    policyTokens.map((t) => [t.name, t.type, t.values, t.cssVar]),
    [
      ['presentation.finish', 'enum', ['standard', 'equipment', 'eink'], undefined],
      ['presentation.cover-color', 'enum', ['legacy', 'theme'], undefined]
    ]
  )
})

test('Dark, Black and Light use the standard finish and legacy cover color', () => {
  for (const id of STANDARD_IDS) {
    const tokens = engine.getTheme(id).tokens
    assert.equal(tokens['presentation.finish'], 'standard', id)
    assert.equal(tokens['presentation.cover-color'], 'legacy', id)
    assert.ok(Array.isArray(tokens['surface.recessed']), id)
  }
})

test('the standard finish produces no presentation rules, so Dark/Black/Light CSS is only their token variables', () => {
  const standard = engine.THEMES.filter((t) => STANDARD_IDS.includes(t.id))
  assert.deepEqual(presets.presentationRules(standard), {})
  for (const selector of Object.keys(presets.builtinPresentationRules())) {
    for (const id of STANDARD_IDS) assert.ok(!selector.startsWith(engine.themeSelector(id)), selector)
  }
})

test('presentation policies are never serialized as CSS', () => {
  for (const theme of [...engine.THEMES, equipmentTheme()]) {
    const declarations = engine.themeDeclarations(theme.tokens)
    for (const [property, value] of Object.entries(declarations)) {
      assert.doesNotMatch(property, /presentation|finish|cover/)
      assert.doesNotMatch(value, /standard|equipment|legacy|theme/)
    }
  }
})

test('invalid or unknown presentation values fall back to the standard policies and never reach CSS', () => {
  const dark = engine.getTheme('dark').tokens
  const hostile = ['Equipment', 'equipment;}', "equipment'] *{", 'custom', 'css', '', ' standard', 1, true, null, ['equipment'], { equipment: true }, 'url(x)', '<style>']
  for (const value of hostile) {
    const { tokens, errors } = engine.validateTokens({ ...dark, 'presentation.finish': value, 'presentation.cover-color': value }, dark)
    assert.equal(tokens['presentation.finish'], 'standard', String(value))
    assert.equal(tokens['presentation.cover-color'], 'legacy', String(value))
    assert.ok(errors.includes('presentation.finish: invalid value'))
    assert.ok(errors.includes('presentation.cover-color: invalid value'))
  }
  const { tokens, errors } = engine.validateTokens({ ...dark, 'presentation.skin': 'equipment', 'presentation.css': 'body{}' }, dark)
  assert.equal('presentation.skin' in tokens, false)
  assert.equal('presentation.css' in tokens, false)
  assert.ok(errors.includes('presentation.skin: unknown token ignored'))
  assert.deepEqual(presets.presentationRules([engine.validateTheme({ id: 'x', labelKey: 'LabelX', colorScheme: 'dark', tokens: { ...dark, 'presentation.skin': 'equipment' } }, dark).theme]), {})
})

test('hostile presentation containers cannot pollute prototypes or select a recipe', () => {
  const dark = engine.getTheme('dark').tokens
  const input = JSON.parse(`{"__proto__": {"presentation.finish": "equipment"}, ${JSON.stringify(dark).slice(1)}`)
  const { tokens } = engine.validateTokens(input, dark)
  assert.equal(tokens['presentation.finish'], 'standard')
  assert.equal({}['presentation.finish'], undefined)
  const inherited = Object.create({ 'presentation.finish': 'equipment' })
  assert.equal(engine.validateTokens(inherited, dark).tokens['presentation.finish'], 'standard')
})

// Every recipe value must be free of anything that can escape or extend a declaration, quotes included. (Phase 4J briefly
// needed one exact exception, the empty `content` of its ::before key faces; the finishing pass paints the faces on the keys
// themselves, so the recipe has no quoted value at all again.)
const UNSAFE_VALUE = /url|expression|import|javascript|[;{}<>"'\@]/i

test('the equipment recipe emits only fixed selectors, fixed properties and safe values under its own theme root', () => {
  const theme = equipmentTheme()
  const root = engine.themeSelector(theme.id)
  const rules = presets.presentationRules([theme])
  const allowedProps = new Set(['--color-edge-light', '--color-edge-dark', '--color-page', '--color-legend', ...presets.EQUIPMENT_RULES.flatMap(([, d]) => Object.keys(d))])
  assert.ok(Object.keys(rules).length >= 1)
  for (const [selector, declarations] of Object.entries(rules)) {
    assert.ok(selector === root || selector.startsWith(`${root} `), selector)
    for (const [property, value] of Object.entries(declarations)) {
      assert.ok(allowedProps.has(property), property)
      assert.doesNotMatch(value, UNSAFE_VALUE, `${selector}: ${property}: ${value}`)
    }
  }
  assert.match(rules[root]['--color-edge-light'], /^\d{1,3} \d{1,3} \d{1,3}$/)
  assert.match(rules[root]['--color-edge-dark'], /^\d{1,3} \d{1,3} \d{1,3}$/)
  assert.match(rules[root]['--color-page'], /^\d{1,3} \d{1,3} \d{1,3}$/)
  assert.match(rules[root]['--color-legend'], /^\d{1,3} \d{1,3} \d{1,3}$/)
})

test('no recipe value is quoted or generated content, and hostile theme data cannot add one', () => {
  for (const [selector, declarations] of presets.EQUIPMENT_RULES) {
    assert.ok(!/::(before|after)/.test(selector), `${selector}: no pseudo-element rule`)
    assert.equal(declarations.content, undefined, selector)
    for (const value of Object.values(declarations)) assert.doesNotMatch(value, /['"]/, selector)
  }
  // Hostile theme data (quotes and CSS fragments in every token) never reaches a rule
  const dark = engine.getTheme('dark')
  const hostile = {}
  for (const name of Object.keys(dark.tokens)) hostile[name] = "''; } body { content: 'pwned'"
  hostile['presentation.finish'] = 'equipment'
  const { theme } = engine.validateTheme({ id: 'rig', labelKey: 'LabelRig', colorScheme: 'dark', tokens: hostile }, dark.tokens)
  const rules = presets.presentationRules([theme || equipmentTheme()])
  for (const [selector, declarations] of Object.entries(rules)) {
    assert.equal(declarations.content, undefined, selector)
    for (const value of Object.values(declarations)) assert.doesNotMatch(value, /pwned|body|['"]/, selector)
  }
  for (const t of TOKENS) assert.notEqual(t.cssVar, 'content', t.name)
})

test('derived edge colors are deterministic blends of validated surfaces, within channel range', () => {
  const tokens = { ...engine.getTheme('dark').tokens, 'surface.raised': [100, 110, 120], 'surface.base': [20, 30, 40] }
  const derived = presets.equipmentDerivedDeclarations(tokens)
  assert.equal(derived['--color-edge-light'], '170 175 181')
  assert.equal(derived['--color-edge-dark'], '8 12 16')
  // Faithful pass: the graphite page (recessed blended a quarter toward base) and the lit key legend (played progress
  // lifted toward a warm yellow)
  assert.equal(derived['--color-page'], '31 34 36')
  assert.equal(derived['--color-legend'], '239 233 179')
  for (const extreme of [
    [0, 0, 0],
    [255, 255, 255]
  ]) {
    const values = presets.equipmentDerivedDeclarations({ ...tokens, 'surface.raised': extreme, 'surface.base': extreme })
    for (const v of Object.values(values))
      assert.ok(
        v
          .split(' ')
          .map(Number)
          .every((n) => Number.isInteger(n) && n >= 0 && n <= 255),
        v
      )
  }
})

test('a theme whose id or finish fails validation contributes no presentation rules', () => {
  const dark = engine.getTheme('dark').tokens
  const bad = engine.validateTheme({ id: "rig'] body {", labelKey: 'LabelRig', colorScheme: 'dark', tokens: { ...dark, 'presentation.finish': 'equipment' } }, dark)
  assert.equal(bad.theme, null)
  assert.deepEqual(presets.presentationRules([engine.validateTheme({ id: 'rig', labelKey: 'LabelRig', colorScheme: 'dark', tokens: { ...dark, 'presentation.finish': 'chrome' } }, dark).theme]), {})
})

test('every equipment rule survives the real Tailwind build (no selector silently pruned)', async () => {
  const { readFile } = await import('node:fs/promises')
  const postcss = require('postcss')
  const tailwind = require('tailwindcss')
  const config = require('../tailwind.config.js')
  const source = (await readFile(new URL('../assets/tailwind.css', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  // Real content globs, resolved from the project root like the Nuxt build
  const content = config.content.map((glob) => new URL(`../${glob}`, import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))
  const { css } = await postcss([tailwind({ ...config, content })]).process(source, { from: undefined })
  const rules = presets.builtinPresentationRules()
  assert.ok(Object.keys(rules).length > 1)
  for (const selector of Object.keys(rules)) assert.ok(css.includes(`${selector} {`), `pruned or missing: ${selector}`)
})
