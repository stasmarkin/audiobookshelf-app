/**
 * Semantic presentation tokens for Audiobookshelf+ themes.
 *
 * Every theme must provide a value for every token below. A token names a visual *purpose*; `cssVar`
 * is the CSS custom property the existing Tailwind classes and component styles already consume, so
 * components keep working unchanged while their colors come from validated theme data.
 *
 * Value types (see engine.js for validation/serialization):
 * - rgb:      [r, g, b] integers 0-255 (emitted as "r g b" so Tailwind's /<alpha> modifiers work)
 * - overlay:  { kind: 'solid', color: [r, g, b] }
 *             { kind: 'linear', angle: 0-360, stops: [{ color: [r, g, b] | [r, g, b, a], at: 0-100 }, ...] }
 * - enum:     one of the listed `values`
 *
 * CommonJS on purpose: tailwind.config.js (Node, build time) and the app bundle share this file.
 */
const TOKENS = [
  // Surfaces
  { name: 'surface.base', type: 'rgb', cssVar: '--color-primary', purpose: 'App chrome and base layer: body, app bar, dialogs, menus, modal backdrop' },
  { name: 'surface.content', type: 'rgb', cssVar: '--color-bg', purpose: 'Page/content background, cards, inputs' },
  { name: 'surface.raised', type: 'rgb', cssVar: '--color-secondary', purpose: 'Raised strips: bookshelf navigation bar, alternating table rows' },
  { name: 'surface.hover', type: 'rgb', cssVar: '--color-bg-hover', purpose: 'Hovered/selected list rows and drawer items' },
  { name: 'surface.recessed', type: 'rgb', cssVar: '--color-recessed', purpose: 'Sunken display/readout wells (used by the equipment finish; unused by standard themes)' },

  // Text
  { name: 'text.default', type: 'rgb', cssVar: '--color-text-default', purpose: 'Inherited document text color (the root `color`)' },
  { name: 'text.primary', type: 'rgb', cssVar: '--color-fg', purpose: 'Primary foreground text and icons' },
  { name: 'text.muted', type: 'rgb', cssVar: '--color-fg-muted', purpose: 'Secondary/de-emphasized text and icons' },

  // Lines
  { name: 'border.default', type: 'rgb', cssVar: '--color-border', purpose: 'Borders of buttons, inputs and panels' },

  // Controls
  { name: 'control.toggle', type: 'rgb', cssVar: '--color-bg-toggle', purpose: 'Unselected segment of toggle buttons' },
  { name: 'control.toggle-selected', type: 'rgb', cssVar: '--color-bg-toggle-selected', purpose: 'Selected segment of toggle buttons' },

  // Progress / seek
  { name: 'progress.track', type: 'rgb', cssVar: '--color-track', purpose: 'Unplayed track of seek bars and range inputs' },
  { name: 'progress.buffered', type: 'rgb', cssVar: '--color-track-buffered', purpose: 'Buffered/ready portion of the seek bar' },
  { name: 'progress.played', type: 'rgb', cssVar: '--color-track-cursor', purpose: 'Played portion and thumb of seek bars' },

  // Overlays (layered over content-derived cover colors)
  { name: 'overlay.item-header', type: 'overlay', cssVar: '--gradient-item-page', purpose: 'Fade over the cover-colored item page header' },
  { name: 'overlay.player', type: 'overlay', cssVar: '--gradient-audio-player', purpose: 'Fade over the cover-colored full-screen player' },
  { name: 'overlay.mini-player', type: 'overlay', cssVar: '--gradient-minimized-audio-player', purpose: 'Fade over the cover-colored mini player' },

  // Accent and states
  { name: 'accent.primary', type: 'rgb', cssVar: '--color-accent', purpose: 'Accent highlights' },
  { name: 'state.success', type: 'rgb', cssVar: '--color-success', purpose: 'Success state: finished/completed items and positive status indicators (actionable success controls use state.success-action)' },
  { name: 'state.success-strong', type: 'rgb', cssVar: '--color-success-dark', purpose: 'Darker success variant' },
  { name: 'state.success-action', type: 'rgb', cssVar: '--color-success-action', purpose: 'Fill of actionable success controls that carry white text or icons (success buttons); indicators keep state.success' },
  { name: 'state.warning', type: 'rgb', cssVar: '--color-warning', purpose: 'Warnings' },
  { name: 'state.error', type: 'rgb', cssVar: '--color-error', purpose: 'Errors and destructive states' },
  { name: 'state.info', type: 'rgb', cssVar: '--color-info', purpose: 'Informational highlights' },

  // Android system bars. See docs/theme-architecture.md: the icon style is applied at runtime; the bar
  // colors describe the native window background, which is still static in res/values (styles.xml).
  { name: 'system.bar-icons', type: 'enum', values: ['light', 'dark'], purpose: 'Status/navigation bar icon brightness (light icons for dark bars)' },
  { name: 'system.status-bar', type: 'rgb', purpose: 'Status bar background (native; not yet applied at runtime)' },
  { name: 'system.navigation-bar', type: 'rgb', purpose: 'Navigation bar background (native; not yet applied at runtime)' },

  // Presentation policies: validated enums selecting repository-owned behavior, never CSS values.
  // finish: 'standard' = no extra rules; 'equipment' and 'eink' = the fixed recipes in theme/presets.js
  { name: 'presentation.finish', type: 'enum', values: ['standard', 'equipment', 'eink'], purpose: 'Which fixed presentation recipe applies' },
  // cover-color: 'legacy' = player/item chrome follows the cover's average color (existing behavior);
  // 'theme' = that chrome uses the theme palette instead (cover art itself is unchanged)
  { name: 'presentation.cover-color', type: 'enum', values: ['legacy', 'theme'], purpose: 'Whether player/item chrome is tinted from cover art' }
]

const TOKEN_NAMES = TOKENS.map((t) => t.name)

module.exports = { TOKENS, TOKEN_NAMES }
