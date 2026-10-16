/**
 * Deterministic role mapping: a few source colors -> a complete, validated 29-token RICE snapshot
 * (docs/rice-customization.md, "Role mapping"). Used by the starter catalog, imports, device colors and copies of
 * built-ins. The steps and their order are fixed, and the native widget mapper (DevicePaletteMapper.kt) repeats
 * steps 1-6 exactly; tests/fixtures/rice-device-mapping.json checks both sides agree.
 *
 *  1. Scheme: explicit, else dark when the background is darker than mid-gray.
 *  2. Surfaces: base = content = background; raised and hover are given or blended 8% / 14% toward the raw
 *     foreground (linear RGB); recessed is 30% toward black on dark schemes, the background on light ones.
 *  3. Foreground: repaired to the text threshold against base, raised and hover.
 *  4. Muted text: given or a 75% foreground / 25% background blend, repaired against base and raised.
 *  5. Border: given or 55% foreground / 45% background, repaired to the control threshold against base.
 *  6. Progress: played = given progress, else accent, else foreground, repaired against base; track = given or 25%
 *     toward the foreground, then repaired against played; buffered = 30% from track toward the foreground.
 *  7. Accent repaired against base; status colors from a reviewed app palette, repaired; overlays solid.
 */
const { blend, isDark, contrast } = require('./color')
const { repairColor, thresholds, evaluateContrast, WHITE, BLACK } = require('./contrast')
const { DEFAULT_APPEARANCE, BASE_IDS, deepFreeze } = require('./schema')

const MAPPING_VERSION = 1

// Reviewed app-owned status palettes (not terminal ANSI slots). Fills that carry white labels are dark enough for 4.5:1.
const STATUS = Object.freeze({
  dark: Object.freeze({ success: [129, 199, 132], 'success-strong': [102, 187, 106], 'success-action': [46, 125, 50], warning: [255, 183, 77], error: [198, 40, 40], info: [100, 181, 246] }),
  light: Object.freeze({ success: [46, 125, 50], 'success-strong': [27, 94, 32], 'success-action': [46, 125, 50], warning: [176, 96, 0], error: [198, 40, 40], info: [21, 101, 192] })
})

/**
 * @param {object} input  { background, foreground, accent?, progress?, raised?, hover?, muted?, border?, track?,
 *                          scheme?: 'dark'|'light', baseId?: 'dark'|'black'|'llama', highContrast?: boolean,
 *                          appearance?: object }
 * @returns {{ snapshot: object, adjustments: Array<{token, from, to}>, unresolved: string[] }}
 */
function mapPalette(input) {
  const bg = input.background
  const rawFg = input.foreground
  const scheme = input.scheme || (isDark(bg) ? 'dark' : 'light')
  const min = thresholds(!!input.highContrast)
  const adjustments = []
  const unresolved = []
  const repair = (token, color, constraints) => {
    const result = repairColor(color, constraints)
    if (result.changed) adjustments.push({ token, from: color.slice(), to: result.color.slice() })
    if (!result.satisfied) unresolved.push(token)
    return result.color
  }

  // 2. Surfaces
  const base = bg.slice()
  const raised = input.raised ? input.raised.slice() : blend(bg, rawFg, 0.08)
  const hover = input.hover ? input.hover.slice() : blend(bg, rawFg, 0.14)
  const recessed = scheme === 'dark' ? blend(bg, BLACK, 0.3) : bg.slice()

  // 3. Foreground
  const fg = repair(
    'text.primary',
    rawFg,
    [base, raised, hover].map((s) => ({ against: s, min: min.text }))
  )

  // 4. Muted
  const muted = repair(
    'text.muted',
    input.muted ? input.muted.slice() : blend(fg, bg, 0.25),
    [base, raised].map((s) => ({ against: s, min: min.text }))
  )

  // 5. Border
  const border = repair('border.default', input.border ? input.border.slice() : blend(fg, bg, 0.45), [{ against: base, min: min.control }])

  // 6. Progress
  const played = repair('progress.played', (input.progress || input.accent || fg).slice(), [{ against: base, min: min.control }])
  const track = repair('progress.track', input.track ? input.track.slice() : blend(bg, fg, 0.25), [{ against: played, min: min.control }])
  const buffered = blend(track, fg, 0.3)

  // 7. Accent and status
  const accent = repair('accent.primary', (input.accent || fg).slice(), [{ against: base, min: min.control }])
  const status = STATUS[scheme]
  const fill = (token, color) => repair(token, color, [{ against: WHITE, min: min.text }])
  const indicator = (token, color) => repair(token, color, [{ against: base, min: min.control }])

  const isLlama = input.baseId === 'llama' && scheme === 'dark'
  const baseId = scheme === 'light' ? 'light' : isLlama ? 'llama' : input.baseId === 'black' ? 'black' : 'dark'
  const tokens = {
    'surface.base': base,
    'surface.content': base.slice(),
    'surface.raised': raised,
    'surface.hover': hover,
    'surface.recessed': recessed,
    'text.default': fg.slice(),
    'text.primary': fg,
    'text.muted': muted,
    'border.default': border,
    'control.toggle': raised.slice(),
    'control.toggle-selected': hover.slice(),
    'progress.track': track,
    'progress.buffered': buffered,
    'progress.played': played,
    'overlay.item-header': { kind: 'solid', color: base.slice() },
    'overlay.player': { kind: 'solid', color: base.slice() },
    'overlay.mini-player': { kind: 'solid', color: raised.slice() },
    'accent.primary': accent,
    'state.success': indicator('state.success', status.success),
    'state.success-strong': status['success-strong'].slice(),
    'state.success-action': fill('state.success-action', status['success-action']),
    'state.warning': indicator('state.warning', status.warning),
    'state.error': fill('state.error', status.error),
    'state.info': indicator('state.info', status.info),
    'system.bar-icons': scheme === 'dark' ? 'light' : 'dark',
    'system.status-bar': base.slice(),
    'system.navigation-bar': base.slice(),
    'presentation.finish': isLlama ? 'equipment' : 'standard',
    'presentation.cover-color': 'theme'
  }
  const appearance = isLlama ? { ...DEFAULT_APPEARANCE } : { ...DEFAULT_APPEARANCE, ...(input.appearance || {}) }
  const snapshot = { baseId, colorScheme: scheme, tokens, appearance }
  // Glyph pairs (fixed white/black player icons) depend only on the surfaces; report rather than move backgrounds
  evaluateContrast(snapshot, { highContrast: !!input.highContrast })
    .failures.filter((f) => f.a === '#white' || f.a === '#black')
    .forEach((f) => unresolved.push(f.b))
  return { snapshot, adjustments, unresolved: [...new Set(unresolved)] }
}

const solidColor = (overlay) => (overlay.kind === 'solid' ? overlay.color : overlay.stops[overlay.stops.length - 1].color.slice(0, 3))

/**
 * A RICE snapshot copied from a validated built-in theme. Colors are taken as they are; gradients become their final
 * opaque stop; anything the copy needs for contrast is repaired on the copy only and reported.
 */
function snapshotFromTheme(theme) {
  const isLlama = theme.id === 'llama'
  const tokens = {}
  for (const [name, value] of Object.entries(theme.tokens)) {
    if (value && typeof value === 'object' && !Array.isArray(value)) tokens[name] = { kind: 'solid', color: solidColor(value).slice() }
    else tokens[name] = Array.isArray(value) ? value.slice() : value
  }
  tokens['presentation.finish'] = isLlama ? 'equipment' : 'standard'
  tokens['presentation.cover-color'] = 'theme'
  // A built-in without its own base (E-ink) is copied as colors on the standard base of its scheme
  const baseId = BASE_IDS.includes(theme.id) ? theme.id : theme.colorScheme === 'light' ? 'light' : 'dark'
  const snapshot = { baseId, colorScheme: theme.colorScheme, tokens, appearance: { ...DEFAULT_APPEARANCE } }
  const adjustments = []
  const min = thresholds(false)
  const fix = (token, constraints) => {
    const result = repairColor(tokens[token], constraints)
    if (result.changed) {
      adjustments.push({ token, from: tokens[token].slice(), to: result.color.slice() })
      tokens[token] = result.color
    }
  }
  const surfaces = ['surface.base', 'surface.content', 'surface.raised', 'surface.hover'].map((s) => ({ against: tokens[s], min: min.text }))
  fix('text.primary', surfaces)
  fix('text.default', surfaces)
  fix('text.muted', surfaces.slice(0, 3))
  for (const overlay of ['overlay.item-header', 'overlay.player', 'overlay.mini-player']) {
    if (contrast(tokens['text.primary'], tokens[overlay].color) < min.text) tokens[overlay] = { kind: 'solid', color: tokens['surface.base'].slice() }
  }
  fix('border.default', [
    { against: tokens['surface.base'], min: min.control },
    { against: tokens['surface.content'], min: min.control }
  ])
  fix('accent.primary', [
    { against: tokens['surface.base'], min: min.control },
    { against: tokens['surface.content'], min: min.control }
  ])
  fix('progress.played', [{ against: tokens['surface.base'], min: min.control }])
  fix('progress.track', [{ against: tokens['progress.played'], min: min.control }])
  fix('state.success-action', [{ against: WHITE, min: min.text }])
  fix('state.error', [{ against: WHITE, min: min.text }])
  for (const state of ['state.success', 'state.warning', 'state.info']) fix(state, [{ against: tokens['surface.base'], min: min.control }])
  return { snapshot, adjustments }
}

// Public Android tone resources per role (SAMSUNG-INTEGRATION): [dark, light]
const DEVICE_ROLES = Object.freeze({
  background: [
    ['neutral1', '900'],
    ['neutral1', '10']
  ],
  raised: [
    ['neutral1', '800'],
    ['neutral1', '50']
  ],
  hover: [
    ['neutral2', '700'],
    ['neutral2', '100']
  ],
  foreground: [
    ['neutral1', '50'],
    ['neutral1', '900']
  ],
  muted: [
    ['neutral2', '200'],
    ['neutral2', '700']
  ],
  accent: [
    ['accent1', '200'],
    ['accent1', '600']
  ],
  border: [
    ['neutral2', '400'],
    ['neutral2', '500']
  ]
})

/**
 * Device colors (AbsAppearance tones) -> snapshot. `night` picks the dark or light role column.
 * Returns null when the platform palette is unavailable (API < 31).
 */
function mapDevice(tones, { night, highContrast = false }) {
  if (!tones) return null
  const column = night ? 0 : 1
  const pick = (role) => {
    const [group, tone] = DEVICE_ROLES[role][column]
    return tones[group] && tones[group][tone] ? tones[group][tone].slice() : null
  }
  const roles = Object.fromEntries(Object.keys(DEVICE_ROLES).map((role) => [role, pick(role)]))
  if (Object.values(roles).some((v) => v === null)) return null
  return mapPalette({ ...roles, progress: roles.accent, scheme: night ? 'dark' : 'light', highContrast })
}

/** The committed widget colors for a snapshot (WidgetPalette.kt applies them). */
function widgetColors(snapshot) {
  const t = snapshot.tokens
  return { background: t['surface.base'].slice(), surface: t['surface.raised'].slice(), text: t['text.primary'].slice(), muted: t['text.muted'].slice(), icon: t['text.primary'].slice(), track: t['progress.track'].slice(), played: t['progress.played'].slice() }
}

module.exports = { mapPalette, snapshotFromTheme, mapDevice, widgetColors, DEVICE_ROLES, STATUS, MAPPING_VERSION, deepFreeze }
