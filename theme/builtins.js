/**
 * Built-in themes as data. Values reproduce the pre-token CSS exactly (assets/tailwind.css and the
 * status colors in tailwind.config.js as of c7a617bc); tests/theme.test.mjs guards that parity.
 *
 * `id` is also the persisted value (Preferences key `theme`) and the <html data-theme> attribute, so
 * existing saved selections keep working. `dark` is the default theme and also the fallback.
 */

// Status colors were identical across themes (previously fixed hex values in tailwind.config.js)
const states = {
  'accent.primary': [26, 214, 145], // #1ad691
  'state.success': [76, 175, 80], // #4CAF50
  'state.success-strong': [59, 138, 62], // #3b8a3e
  // White text on #4CAF50 is 2.78:1; success buttons use this darker fill (5.13:1, 4.58:1 under the LLAMA sheen).
  // Not a pre-token value: added in Phase 2C Gate H. Indicators (finished bars, toggles) keep state.success.
  'state.success-action': [46, 125, 50], // #2E7D32
  'state.warning': [251, 140, 0], // #FB8C00
  'state.error': [255, 82, 82], // #FF5252
  'state.info': [33, 150, 243] // #2196F3
}

// The native window behind the status/navigation bars is @color/background_dark (#232323) with light
// icons for every theme today, including Light. Recorded as-is; see docs/theme-architecture.md.
const systemBars = {
  'system.bar-icons': 'light',
  'system.status-bar': [35, 35, 35],
  'system.navigation-bar': [35, 35, 35]
}

// Existing themes keep today's presentation: no extra recipe, player/item chrome tinted from cover art
const standardPresentation = {
  'presentation.finish': 'standard',
  'presentation.cover-color': 'legacy'
}

const linear = (angle, ...stops) => ({ kind: 'linear', angle, stops: stops.map(([color, at]) => ({ color, at })) })
const solid = (color) => ({ kind: 'solid', color })

const dark = {
  id: 'dark',
  labelKey: 'LabelThemeDark',
  colorScheme: 'dark',
  tokens: {
    'surface.base': [35, 35, 35],
    'surface.content': [56, 56, 56],
    'surface.raised': [47, 48, 48],
    'surface.hover': [102, 104, 107],
    'surface.recessed': [35, 35, 35],
    'text.default': [255, 255, 255],
    'text.primary': [230, 237, 243],
    'text.muted': [142, 147, 153],
    'border.default': [75, 85, 89],
    'control.toggle': [56, 56, 56],
    'control.toggle-selected': [81, 82, 84],
    'progress.track': [107, 114, 128],
    'progress.buffered': [75, 85, 99],
    'progress.played': [229, 231, 235],
    'overlay.item-header': linear(169, [[0, 0, 0, 0.4], 0], [[55, 56, 56, 1], 80]),
    'overlay.player': linear(180, [[0, 0, 0, 0], 0], [[38, 38, 38, 1], 80]),
    'overlay.mini-player': linear(145, [[38, 38, 38, 0.5], 0], [[38, 38, 38, 0.9], 20], [[38, 38, 38], 60]),
    ...states,
    ...systemBars,
    ...standardPresentation
  }
}

const black = {
  id: 'black',
  labelKey: 'LabelThemeBlack',
  colorScheme: 'dark',
  tokens: {
    'surface.base': [0, 0, 0],
    'surface.content': [0, 0, 0],
    'surface.raised': [0, 0, 0],
    'surface.hover': [0, 0, 0],
    'surface.recessed': [0, 0, 0],
    'text.default': [255, 255, 255],
    'text.primary': [230, 237, 243],
    'text.muted': [120, 126, 132],
    'border.default': [55, 62, 65],
    'control.toggle': [0, 0, 0],
    'control.toggle-selected': [35, 35, 35],
    'progress.track': [107, 114, 128],
    'progress.buffered': [75, 85, 99],
    'progress.played': [229, 231, 235],
    'overlay.item-header': solid([0, 0, 0]),
    'overlay.player': solid([0, 0, 0]),
    'overlay.mini-player': solid([0, 0, 0]),
    ...states,
    ...systemBars,
    ...standardPresentation
  }
}

const light = {
  id: 'light',
  labelKey: 'LabelThemeLight',
  colorScheme: 'light',
  tokens: {
    'surface.base': [222, 222, 222],
    'surface.content': [255, 255, 255],
    'surface.raised': [246, 248, 250],
    'surface.hover': [208, 210, 212],
    'surface.recessed': [222, 222, 222],
    'text.default': [0, 0, 0],
    'text.primary': [37, 37, 37],
    'text.muted': [101, 109, 118],
    'border.default': [189, 191, 191],
    'control.toggle': [222, 222, 222],
    'control.toggle-selected': [255, 255, 255],
    'progress.track': [189, 191, 191],
    'progress.buffered': [129, 131, 131],
    'progress.played': [101, 109, 118],
    'overlay.item-header': linear(169, [[255, 255, 255, 0.4], 0], [[255, 255, 255, 1], 80]),
    'overlay.player': linear(180, [[255, 255, 255, 0], 0], [[255, 255, 255, 1], 80]),
    'overlay.mini-player': linear(145, [[255, 255, 255, 0.5], 0], [[255, 255, 255, 0.9], 20], [[255, 255, 255], 60]),
    ...states,
    ...systemBars,
    ...standardPresentation
  }
}

// LLAMA: an original blue audio-equipment theme (late-1990s material language). Palette values are
// original to Audiobookshelf+. The chassis family (surfaces, border, toggles) is a deep navy with an indigo lean, tuned in
// the Phase 4 finishing pass toward the approved mockup's character: about 1.7x the earlier chroma and +5 degrees of hue at
// unchanged luminance, so every contrast and the depth order stay where they were. The near-black wells, the seek channel
// and every semantic color are unchanged. Semantic states keep the shared values so warning/error/success/info keep
// their meaning, and warning orange stays distinct from the yellow-amber played progress.
const llama = {
  id: 'llama',
  labelKey: 'LabelThemeLlama',
  colorScheme: 'dark',
  tokens: {
    'surface.base': [22, 33, 58], // deep navy chassis: app bar, dialogs, menus
    'surface.content': [39, 54, 87], // blue equipment panel: page surface, player deck
    'surface.raised': [63, 81, 120], // lighter blue-steel strips
    'surface.hover': [81, 101, 146], // blue-steel highlight for hovered/selected rows
    'surface.recessed': [10, 13, 18], // black-blue display wells
    'text.default': [235, 238, 242],
    'text.primary': [226, 232, 240], // neutral near-white
    'text.muted': [150, 162, 178], // subdued cool gray
    'border.default': [88, 109, 157], // blue-steel edge
    'control.toggle': [39, 54, 87],
    'control.toggle-selected': [81, 101, 146],
    'progress.track': [40, 46, 56], // recessed channel
    'progress.buffered': [86, 98, 116], // visibly lighter than the track
    'progress.played': [245, 190, 40], // yellow-amber
    // Faithful pass: the item header fades into the graphite page, the full player's chassis behind its steel plates is
    // the mockup's near-black (the same graphite as the browsing pages), and the collapsed player is a steel panel lit
    // from the top (raised steel to panel steel), like the mockup's mini-player plate
    'overlay.item-header': linear(169, [[10, 13, 18, 0.4], 0], [[13, 18, 28, 1], 80]),
    'overlay.player': solid([13, 18, 28]),
    'overlay.mini-player': linear(180, [[63, 81, 120], 0], [[39, 54, 87], 100]),
    ...states,
    // Phosphor readout green: the approved mockup's pale yellow-green display glow (its glyph cores sample #A8F05A to
    // #C6F24F), lighter and less saturated than the earlier #60E868. 14.4:1 on the recessed wells
    'accent.primary': [170, 240, 100],
    ...systemBars,
    'presentation.finish': 'equipment',
    'presentation.cover-color': 'theme'
  }
}

// E-ink: paper white and ink black with two grays far apart, because e-paper renders mid-tones, tints and translucency as
// dither (worse on color Kaleido panels). States are ink too: on e-paper they read by icon, label and shape, and colored
// fills dither. Cover art keeps its colors (`presentation.cover-color: 'theme'` only stops chrome from being tinted by it).
const EINK_INK = [0, 0, 0]
const EINK_PAPER = [255, 255, 255]
const EINK_LIGHT_GRAY = [204, 204, 204] // tracks, selected rows and segments (1.6:1 to paper, 13:1 to ink)
const EINK_DARK_GRAY = [85, 85, 85] // secondary text (7.5:1 on paper)
const eink = {
  id: 'eink',
  labelKey: 'LabelThemeEink',
  colorScheme: 'light',
  tokens: {
    'surface.base': EINK_PAPER,
    'surface.content': EINK_PAPER,
    'surface.raised': EINK_PAPER,
    'surface.hover': EINK_LIGHT_GRAY,
    'surface.recessed': EINK_PAPER,
    'text.default': EINK_INK,
    'text.primary': EINK_INK,
    'text.muted': EINK_DARK_GRAY,
    'border.default': EINK_INK,
    'control.toggle': EINK_PAPER,
    'control.toggle-selected': EINK_LIGHT_GRAY,
    'progress.track': EINK_LIGHT_GRAY,
    'progress.buffered': EINK_LIGHT_GRAY,
    'progress.played': EINK_INK,
    'overlay.item-header': solid(EINK_PAPER),
    'overlay.player': solid(EINK_PAPER),
    'overlay.mini-player': solid(EINK_PAPER),
    'accent.primary': EINK_INK,
    'state.success': EINK_INK,
    'state.success-strong': EINK_INK,
    'state.success-action': EINK_INK,
    'state.warning': EINK_INK,
    'state.error': EINK_INK,
    'state.info': EINK_INK,
    ...systemBars,
    'presentation.finish': 'eink',
    'presentation.cover-color': 'theme'
  }
}

const DEFAULT_THEME_ID = 'dark'

// Order shown in Settings (Black, Dark, Light as before, then LLAMA, then E-ink)
const BUILTIN_THEMES = [black, dark, light, llama, eink]

module.exports = { BUILTIN_THEMES, DEFAULT_THEME_ID }
