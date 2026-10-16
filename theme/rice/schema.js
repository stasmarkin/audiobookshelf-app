/**
 * Strict RICE record validation (docs/rice-customization.md, "Preset schema").
 *
 * The built-in engine (theme/engine.js) is deliberately tolerant: it falls back per token. RICE records come from
 * storage and imports, so they are checked here first and rejected whole on any unknown or duplicate key, wrong
 * prototype, wrong shape or out-of-range value. Only then are they serialized by the engine. No field is CSS: colors
 * are integer channels, everything else is a fixed enum, so a record can't inject selectors, URLs or markup.
 */
const { TOKENS } = require('../tokens')
const { BUILTIN_THEMES } = require('../builtins')
const { isRgb } = require('./color')

const SCHEMA_VERSION = 1
const TOKEN_SCHEMA_VERSION = 1
const PRESET_FORMAT = 'absplus-rice-preset'
const STORE_FORMAT = 'absplus-rice-store'

// BASE_IDS anchor custom snapshots; BUILTIN_IDS are what can be selected. E-ink has no custom base: its finish isn't an option
const BASE_IDS = Object.freeze(['dark', 'black', 'light', 'llama'])
const BUILTIN_IDS = Object.freeze(BUILTIN_THEMES.map((t) => t.id))
const STARTER_IDS = Object.freeze(['catppuccin-mocha', 'catppuccin-latte', 'dracula', 'nord', 'gruvbox-dark', 'tokyo-night', 'rose-pine', 'solarized-dark', 'solarized-light', 'everforest-dark'])
const DEVICE_MODES = Object.freeze(['follow', 'dark'])
const SOURCE_KINDS = Object.freeze(['builtin', 'starter', 'device', 'github', 'manual'])
const IMPORT_FORMATS = Object.freeze(['pywal-json-v1', 'kitty-colors-v1', 'base-palette-v1', 'hex-list-v1'])

/** Bounded presentation options. The first value of each list is the app default (renders like the base theme). */
const APPEARANCE_OPTIONS = Object.freeze({
  interfaceFont: Object.freeze(['system', 'source-sans']),
  displayFont: Object.freeze(['system', 'system-mono', 'ubuntu-mono']),
  weight: Object.freeze([400, 600]),
  textScale: Object.freeze([1, 1.1, 1.2]),
  radius: Object.freeze([4, 0, 8, 12]),
  frame: Object.freeze(['none', 'inset']),
  borderWidth: Object.freeze([0, 1, 2]),
  shadow: Object.freeze(['none', 'soft']),
  finish: Object.freeze(['flat', 'bevel'])
})
const DEFAULT_APPEARANCE = Object.freeze(Object.fromEntries(Object.entries(APPEARANCE_OPTIONS).map(([key, values]) => [key, values[0]])))

const LIMITS = Object.freeze({ presets: 32, nameCodePoints: 48, attribution: 256, url: 256, path: 256, sourceColors: 24, adjustments: 29, errors: 20 })
const PRESET_ID_PATTERN = /^p-[0-9a-f]{24}$/
const ISO_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/
const HEX40 = /^[0-9a-f]{40}$/
const HEX64 = /^[0-9a-f]{64}$/
const GITHUB_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/

const TOKEN_BY_NAME = Object.freeze(Object.fromEntries(TOKENS.map((t) => [t.name, t])))

const isPlainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value) && Object.getPrototypeOf(value) === Object.prototype

class Checker {
  constructor() {
    this.errors = []
  }
  fail(path, message) {
    if (this.errors.length < LIMITS.errors) this.errors.push(`${path}: ${message}`)
    return false
  }
  /** Exactly these own keys (optional ones may be absent). */
  keys(value, path, required, optional = []) {
    if (!isPlainObject(value)) return this.fail(path, 'not a plain object')
    const allowed = new Set([...required, ...optional])
    let ok = true
    for (const key of Object.keys(value)) if (!allowed.has(key)) ok = this.fail(path, `unknown key ${String(key).slice(0, 40)}`)
    for (const key of required) if (!Object.prototype.hasOwnProperty.call(value, key)) ok = this.fail(path, `missing ${key}`)
    return ok
  }
  oneOf(value, path, values) {
    return values.includes(value) || this.fail(path, 'not an allowed value')
  }
  rgb(value, path) {
    return isRgb(value) || this.fail(path, 'not an [r, g, b] integer color')
  }
  text(value, path, max, pattern) {
    if (typeof value !== 'string' || value.length === 0 || [...value].length > max) return this.fail(path, 'bad text')
    if (/[\u0000-\u001f\u007f]/.test(value)) return this.fail(path, 'control character')
    if (pattern && !pattern.test(value)) return this.fail(path, 'bad format')
    return true
  }
}

function checkAppearance(c, value, path) {
  if (!c.keys(value, path, Object.keys(APPEARANCE_OPTIONS))) return false
  let ok = true
  for (const [key, values] of Object.entries(APPEARANCE_OPTIONS)) ok = c.oneOf(value[key], `${path}.${key}`, values) && ok
  return ok
}

function checkSnapshot(c, value, path) {
  if (!c.keys(value, path, ['baseId', 'colorScheme', 'tokens', 'appearance'])) return false
  let ok = c.oneOf(value.baseId, `${path}.baseId`, BASE_IDS)
  ok = c.oneOf(value.colorScheme, `${path}.colorScheme`, ['dark', 'light']) && ok
  if (!ok) return false
  // The base is the CSS anchor: its scheme must match (Light is the only light base; LLAMA is dark only)
  if ((value.baseId === 'light') !== (value.colorScheme === 'light')) return c.fail(path, 'base and color scheme disagree')
  if (!c.keys(value.tokens, `${path}.tokens`, Object.keys(TOKEN_BY_NAME))) return false
  for (const [name, token] of Object.entries(TOKEN_BY_NAME)) {
    const v = value.tokens[name]
    const p = `${path}.tokens.${name}`
    if (token.type === 'rgb') ok = c.rgb(v, p) && ok
    else if (token.type === 'overlay') {
      // custom-v1: solid overlays only; gradients and alpha stay in trusted built-in recipes
      ok = c.keys(v, p, ['kind', 'color']) && c.oneOf(v.kind, `${p}.kind`, ['solid']) && c.rgb(v.color, `${p}.color`) && ok
    } else if (name === 'system.bar-icons') ok = c.oneOf(v, p, ['light', 'dark']) && ok
    else if (name === 'presentation.finish') ok = c.oneOf(v, p, [value.baseId === 'llama' ? 'equipment' : 'standard']) && ok
    else if (name === 'presentation.cover-color') ok = c.oneOf(v, p, ['theme']) && ok
    else ok = c.fail(p, 'unsupported token') && ok
  }
  ok = checkAppearance(c, value.appearance, `${path}.appearance`) && ok
  if (ok && value.baseId === 'llama') {
    // LLAMA copies keep the frozen LLAMA recipe: geometry and type options stay at their defaults
    for (const [key, def] of Object.entries(DEFAULT_APPEARANCE)) if (value.appearance[key] !== def) ok = c.fail(`${path}.appearance.${key}`, 'locked for LLAMA copies')
  }
  return ok
}

function checkSource(c, value, path) {
  if (!isPlainObject(value)) return c.fail(path, 'not a plain object')
  switch (value.kind) {
    case 'builtin':
      return c.keys(value, path, ['kind', 'id']) && c.oneOf(value.id, `${path}.id`, BUILTIN_IDS)
    case 'starter':
      return c.keys(value, path, ['kind', 'id']) && c.oneOf(value.id, `${path}.id`, STARTER_IDS)
    case 'device':
      return c.keys(value, path, ['kind', 'mode']) && c.oneOf(value.mode, `${path}.mode`, DEVICE_MODES)
    case 'manual':
      return c.keys(value, path, ['kind'])
    case 'github': {
      if (!c.keys(value, path, ['kind', 'url', 'owner', 'repo', 'commit', 'path', 'sha256', 'format', 'colors', 'mappingVersion', 'attribution', 'license', 'adjustments'], ['licenseUrl'])) return false
      let ok = c.text(value.url, `${path}.url`, LIMITS.url, /^https:\/\/github\.com\/[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/)
      ok = c.text(value.owner, `${path}.owner`, 100, GITHUB_NAME) && ok
      ok = c.text(value.repo, `${path}.repo`, 100, GITHUB_NAME) && ok
      ok = c.text(value.commit, `${path}.commit`, 40, HEX40) && ok
      ok = c.text(value.path, `${path}.path`, LIMITS.path, /^[^\\]+$/) && !value.path.split('/').some((s) => s === '' || s === '.' || s === '..') && ok
      ok = c.text(value.sha256, `${path}.sha256`, 64, HEX64) && ok
      ok = c.oneOf(value.format, `${path}.format`, IMPORT_FORMATS) && ok
      ok = c.oneOf(value.mappingVersion, `${path}.mappingVersion`, [1]) && ok
      ok = c.text(value.attribution, `${path}.attribution`, LIMITS.attribution) && ok
      ok = c.text(value.license, `${path}.license`, 64) && ok
      if (value.licenseUrl !== undefined) ok = c.text(value.licenseUrl, `${path}.licenseUrl`, LIMITS.url, /^https:\/\/github\.com\/[^\s]+$/) && ok
      if (!Array.isArray(value.colors) || value.colors.length < 2 || value.colors.length > LIMITS.sourceColors) ok = c.fail(`${path}.colors`, 'bad color list')
      else value.colors.forEach((color, i) => (ok = c.rgb(color, `${path}.colors.${i}`) && ok))
      if (!Array.isArray(value.adjustments) || value.adjustments.length > LIMITS.adjustments) ok = c.fail(`${path}.adjustments`, 'bad adjustments')
      else
        value.adjustments.forEach((adj, i) => {
          const p = `${path}.adjustments.${i}`
          ok = c.keys(adj, p, ['token', 'from', 'to']) && c.oneOf(adj.token, `${p}.token`, Object.keys(TOKEN_BY_NAME)) && c.rgb(adj.from, `${p}.from`) && c.rgb(adj.to, `${p}.to`) && ok
        })
      return ok
    }
    default:
      // 'image' is reserved for a later version and rejected until it exists
      return c.fail(`${path}.kind`, 'unsupported source')
  }
}

function checkPreset(c, value, path) {
  if (!c.keys(value, path, ['format', 'schemaVersion', 'tokenSchemaVersion', 'id', 'name', 'createdAt', 'updatedAt', 'snapshot', 'reset', 'source'])) return false
  let ok = c.oneOf(value.format, `${path}.format`, [PRESET_FORMAT])
  ok = c.oneOf(value.schemaVersion, `${path}.schemaVersion`, [SCHEMA_VERSION]) && ok
  ok = c.oneOf(value.tokenSchemaVersion, `${path}.tokenSchemaVersion`, [TOKEN_SCHEMA_VERSION]) && ok
  ok = c.text(value.id, `${path}.id`, 26, PRESET_ID_PATTERN) && ok
  ok = c.text(value.name, `${path}.name`, LIMITS.nameCodePoints) && value.name.trim() === value.name && ok
  ok = c.text(value.createdAt, `${path}.createdAt`, 24, ISO_PATTERN) && ok
  ok = c.text(value.updatedAt, `${path}.updatedAt`, 24, ISO_PATTERN) && ok
  ok = checkSnapshot(c, value.snapshot, `${path}.snapshot`) && ok
  ok = checkSnapshot(c, value.reset, `${path}.reset`) && ok
  ok = checkSource(c, value.source, `${path}.source`) && ok
  return ok
}

function checkSelection(c, value, path) {
  if (!isPlainObject(value)) return c.fail(path, 'not a plain object')
  switch (value.kind) {
    case 'builtin':
      return c.keys(value, path, ['kind', 'id']) && c.oneOf(value.id, `${path}.id`, BUILTIN_IDS)
    case 'starter':
      return c.keys(value, path, ['kind', 'id']) && c.oneOf(value.id, `${path}.id`, STARTER_IDS)
    case 'preset':
      return c.keys(value, path, ['kind', 'id']) && c.text(value.id, `${path}.id`, 26, PRESET_ID_PATTERN)
    case 'device':
      return c.keys(value, path, ['kind', 'mode']) && c.oneOf(value.mode, `${path}.mode`, DEVICE_MODES)
    default:
      return c.fail(`${path}.kind`, 'unsupported selection')
  }
}

const WIDGET_COLOR_ROLES = Object.freeze(['background', 'surface', 'text', 'muted', 'icon', 'track', 'played'])

function checkWidget(c, value, path) {
  if (value === null) return true
  if (!c.keys(value, path, ['revision', 'selectionKind', 'deviceMode', 'family', 'colors'])) return false
  let ok = (Number.isSafeInteger(value.revision) && value.revision > 0) || c.fail(`${path}.revision`, 'bad revision')
  ok = c.oneOf(value.selectionKind, `${path}.selectionKind`, ['starter', 'preset', 'device']) && ok
  ok = c.oneOf(value.deviceMode, `${path}.deviceMode`, value.selectionKind === 'device' ? DEVICE_MODES : [null]) && ok
  ok = c.oneOf(value.family, `${path}.family`, ['standard', 'llama']) && ok
  if (c.keys(value.colors, `${path}.colors`, WIDGET_COLOR_ROLES)) WIDGET_COLOR_ROLES.forEach((role) => (ok = c.rgb(value.colors[role], `${path}.colors.${role}`) && ok))
  else ok = false
  return ok
}

function checkEnvelope(c, value, path = 'store') {
  if (!c.keys(value, path, ['format', 'schemaVersion', 'revision', 'riceEnabled', 'lastBuiltin', 'selection', 'rememberedRiceSelection', 'presets', 'widget'])) return false
  let ok = c.oneOf(value.format, `${path}.format`, [STORE_FORMAT])
  ok = c.oneOf(value.schemaVersion, `${path}.schemaVersion`, [SCHEMA_VERSION]) && ok
  ok = (Number.isSafeInteger(value.revision) && value.revision > 0) || c.fail(`${path}.revision`, 'bad revision')
  ok = c.oneOf(value.riceEnabled, `${path}.riceEnabled`, [true, false]) && ok
  ok = c.oneOf(value.lastBuiltin, `${path}.lastBuiltin`, BUILTIN_IDS) && ok
  ok = checkSelection(c, value.selection, `${path}.selection`) && ok
  if (value.rememberedRiceSelection !== null) ok = checkSelection(c, value.rememberedRiceSelection, `${path}.rememberedRiceSelection`) && ok
  if (!Array.isArray(value.presets) || value.presets.length > LIMITS.presets) ok = c.fail(`${path}.presets`, 'bad preset list')
  else {
    const ids = new Set()
    value.presets.forEach((preset, i) => {
      ok = checkPreset(c, preset, `${path}.presets.${i}`) && ok
      if (preset && ids.has(preset.id)) ok = c.fail(`${path}.presets.${i}.id`, 'duplicate id')
      if (preset) ids.add(preset.id)
    })
  }
  ok = checkWidget(c, value.widget, `${path}.widget`) && ok
  return ok
}

const run = (fn) => (value) => {
  const c = new Checker()
  const ok = fn(c, value, undefined) && c.errors.length === 0
  return ok ? { ok: true, errors: [] } : { ok: false, errors: c.errors.length ? c.errors : ['invalid'] }
}

const validateSnapshot = (value) => run((c, v) => checkSnapshot(c, v, 'snapshot'))(value)
const validateAppearance = (value) => run((c, v) => checkAppearance(c, v, 'appearance'))(value)
const validatePreset = (value) => run((c, v) => checkPreset(c, v, 'preset'))(value)
const validateSelection = (value) => run((c, v) => checkSelection(c, v, 'selection'))(value)
const validateEnvelope = (value) => run((c, v) => checkEnvelope(c, v, 'store'))(value)
const validateSource = (value) => run((c, v) => checkSource(c, v, 'source'))(value)

/** Trimmed, plain-text preset name of 1-48 code points, or null. */
function normalizeName(name) {
  if (typeof name !== 'string') return null
  const trimmed = name.replace(/\s+/g, ' ').trim()
  const points = [...trimmed]
  if (points.length === 0 || points.length > LIMITS.nameCodePoints) return null
  if (/[\u0000-\u001f\u007f]/.test(trimmed)) return null
  return trimmed
}

/** p- + 24 lowercase hex from the platform's cryptographic random source. */
function newPresetId(randomBytes) {
  const bytes = randomBytes ? randomBytes(12) : globalThis.crypto.getRandomValues(new Uint8Array(12))
  return 'p-' + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

const deepClone = (value) => JSON.parse(JSON.stringify(value))

function deepFreeze(value) {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(deepFreeze)
    Object.freeze(value)
  }
  return value
}

module.exports = {
  SCHEMA_VERSION,
  TOKEN_SCHEMA_VERSION,
  PRESET_FORMAT,
  STORE_FORMAT,
  BASE_IDS,
  BUILTIN_IDS,
  STARTER_IDS,
  DEVICE_MODES,
  SOURCE_KINDS,
  IMPORT_FORMATS,
  APPEARANCE_OPTIONS,
  DEFAULT_APPEARANCE,
  LIMITS,
  WIDGET_COLOR_ROLES,
  isPlainObject,
  validateSnapshot,
  validateAppearance,
  validatePreset,
  validateSelection,
  validateEnvelope,
  validateSource,
  normalizeName,
  newPresetId,
  deepClone,
  deepFreeze
}
