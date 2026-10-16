/**
 * Fixed presentation recipes selected by the validated `presentation.finish` token.
 *
 * - 'standard' produces no rules at all, so Dark/Black/Light CSS is exactly the token variables.
 * - 'equipment' is a repository-owned recipe (bevels, recessed wells, ...). Its selectors, property
 *   names and structure live here, in code. Theme data only chooses the recipe and supplies validated
 *   colors: rules reference token variables, and the derived edge colors below are computed from
 *   validated channels with fixed blend factors that theme data cannot influence.
 * - 'eink' is a repository-owned, paint-only recipe that removes shadows, gradients and translucency and draws 1px
 *   ink lines instead (see EINK_RULES). Its values reference only token variables.
 *
 * Selectors are built only from validated built-in theme ids (engine.themeSelector).
 * CommonJS on purpose: used by tailwind.config.js at build time.
 */
const engine = require('./engine')
const playerLayout = require('./playerLayout')

const WHITE = [255, 255, 255]
const BLACK = [0, 0, 0]
// Fixed, repository-owned blend factors (not theme data)
const EDGE_LIGHT_TOWARD_WHITE = 0.45
const EDGE_DARK_TOWARD_BLACK = 0.6
const PAGE_TOWARD_BASE = 0.25
const LEGEND_GLOW = [255, 236, 96]
const LEGEND_TOWARD_GLOW = 0.4

const mix = (from, to, t) => from.map((channel, i) => Math.round(channel + (to[i] - channel) * t))
const channels = (c) => `${c[0]} ${c[1]} ${c[2]}`

/** Colors the equipment recipe derives from validated theme tokens. */
function equipmentDerivedDeclarations(tokens) {
  return {
    // Lighter top/left bevel edge, from the raised surface
    '--color-edge-light': channels(mix(tokens['surface.raised'], WHITE, EDGE_LIGHT_TOWARD_WHITE)),
    // Darker bottom/right bevel edge, from the base surface
    '--color-edge-dark': channels(mix(tokens['surface.base'], BLACK, EDGE_DARK_TOWARD_BLACK)),
    // Graphite page behind the equipment (the approved mockup's near-black browsing background, sampled #0B151A): the
    // recessed well color with a quarter of the chassis in it, so content pages read as the dark room the steel panels sit in
    '--color-page': channels(mix(tokens['surface.recessed'], tokens['surface.base'], PAGE_TOWARD_BASE)),
    // Illuminated key legend: the played-progress amber lifted toward a warm yellow, as the mockup's lit key legends are
    // brighter and yellower than its amber progress fill
    '--color-legend': channels(mix(tokens['progress.played'], LEGEND_GLOW, LEGEND_TOWARD_GLOW))
  }
}

/**
 * The equipment recipe: [selector suffix relative to the theme root, declarations].
 * An empty suffix targets the theme root itself. Values may only reference CSS variables emitted by
 * the token layer or by equipmentDerivedDeclarations.
 */
// --- Shared equipment primitives ---
// Paint-only building blocks (no border widths, padding, transforms or layout properties). One light source:
// light upper/left edges, dark lower/right edges, drop shadows falling down. Later gates compose rules from
// these instead of adding one-off values. All are fixed repository values; theme data cannot supply any.

// Radius scale: squared, restrained geometry. Every key, the primary Play/Pause key included, uses `key`.
const RADIUS = Object.freeze({
  frame: '2px', // artwork frames and fine detail
  key: '4px', // wells, panels and equipment keys
  none: '0px' // square inner corners where two wells join into one (the dual-track display)
})

// Edge treatments
const RAISED_BEVEL = 'inset 1px 1px 0 rgb(var(--color-edge-light) / 0.55), inset -1px -1px 0 rgb(var(--color-edge-dark))'
const PRESSED_BEVEL = 'inset 1px 1px 0 rgb(var(--color-edge-dark)), inset -1px -1px 0 rgb(var(--color-edge-light) / 0.35)'
const RECESSED_WELL = 'inset 1px 1px 0 rgb(var(--color-edge-dark)), inset -1px -1px 0 rgb(var(--color-edge-light) / 0.25), inset 0 2px 6px rgb(0 0 0 / 0.45)'
const STEEL_SHEEN = 'linear-gradient(180deg, rgb(var(--color-edge-light) / 0.18) 0%, rgb(var(--color-edge-light) / 0) 55%, rgb(0 0 0 / 0.18) 100%)'
const CHASSIS_SHEEN = 'linear-gradient(180deg, rgb(var(--color-edge-light) / 0.1) 0%, rgb(var(--color-edge-light) / 0) 40%, rgb(0 0 0 / 0.2) 100%)'
// Faithful pass: the mockup frames every cover in a bevelled steel band (~3px, lit #899FC9 on top) seated on a dark seam,
// with a dark outer line. Drawn outside the artwork with spread shadows, so the image keeps its size, crop and layout
// Material polish: the frame is a recessed window. Out from the art: a near-black channel, the window's inner wall (in shade
// at the top and left, catching light at the bottom and right, so the art sits below the frame), the n px steel band lit
// by direction (one strip per side over the raised steel), the band's outer lip (lit upper-left, dark lower-right) and a
// dark outer ring of r px. Outer shadows only, so the artwork's box, crop and layout never change; the drop is added by
// each rule and kept short (ARTWORK_DROP) so it never reaches neighbouring text
const artFrame = (n, r) =>
  [
    '0 0 0 1px rgb(0 0 0 / 0.9)',
    '0 -2px 0 0 rgb(0 0 0 / 0.3)',
    '-2px 0 0 0 rgb(0 0 0 / 0.25)',
    '0 2px 0 0 rgb(var(--color-edge-light) / 0.3)',
    '2px 0 0 0 rgb(var(--color-edge-light) / 0.18)',
    `0 -${n}px 0 0 rgb(var(--color-edge-light) / 0.34)`,
    `-${n}px 0 0 0 rgb(var(--color-edge-light) / 0.16)`,
    `${n}px 0 0 0 rgb(0 0 0 / 0.3)`,
    `0 ${n}px 0 0 rgb(0 0 0 / 0.45)`,
    `0 0 0 ${n}px rgb(var(--color-edge-light) / 0.22)`,
    `0 0 0 ${n}px rgb(var(--color-secondary))`,
    `-1px -1px 0 ${n}px rgb(var(--color-edge-light) / 0.85)`,
    `1px 1px 0 ${n}px rgb(var(--color-edge-dark))`,
    `0 0 0 ${n + r}px rgb(0 0 0 / 0.7)`
  ].join(', ')
// Covers (cards, detail pages, the mini-player): a 3px band and a 1px ring, 4px in all, the same footprint as before
const ARTWORK_FRAME = artFrame(3, 1)
const ARTWORK_DROP = '0 2px 3px rgb(0 0 0 / 0.55)'
// The same frame drawn inside a cover's own box (finishing corrections), for covers whose container clips everything
// outside them (the Home shelves scroll sideways, so they clip vertically too): from the edge in, the dark outer ring, the
// band's lips (lit upper-left, dark lower-right), the n px steel band lit by direction, and the near-black channel at the art
const artFrameInset = (n) =>
  [
    'inset 0 0 0 1px rgb(0 0 0 / 0.7)',
    'inset 2px 2px 0 0 rgb(var(--color-edge-light) / 0.85)',
    'inset -2px -2px 0 0 rgb(var(--color-edge-dark))',
    `inset 0 ${n}px 0 0 rgb(var(--color-edge-light) / 0.34)`,
    `inset ${n}px 0 0 0 rgb(var(--color-edge-light) / 0.16)`,
    `inset -${n}px 0 0 0 rgb(0 0 0 / 0.3)`,
    `inset 0 -${n}px 0 0 rgb(0 0 0 / 0.45)`,
    `inset 0 0 0 ${n}px rgb(var(--color-edge-light) / 0.22)`,
    `inset 0 0 0 ${n}px rgb(var(--color-secondary))`,
    `inset 0 0 0 ${n + 1}px rgb(0 0 0 / 0.9)`
  ].join(', ')
const ARTWORK_FRAME_INSET = artFrameInset(3)
// A seam cut into the bottom of an element: dark inset line with a faint light return line below it.
// Drawn inside the element's own box (inset), so row dimensions never change.
const ENGRAVED_SEPARATOR = 'inset 0 -1px 0 rgb(var(--color-edge-light) / 0.12), inset 0 -2px 0 rgb(var(--color-edge-dark))'
// The same seam cut into the top of an element (dark line on top, faint light return under it)
const ENGRAVED_SEPARATOR_TOP = 'inset 0 1px 0 rgb(var(--color-edge-dark)), inset 0 2px 0 rgb(var(--color-edge-light) / 0.12)'
// Recessed display face as a background layer: dark upper lip with an inner shade falling down and a faint
// light lower lip. Unlike an inset box-shadow it follows background-clip, so it fits a content-box well
const RECESSED_FACE = 'linear-gradient(180deg, rgb(var(--color-edge-dark)) 0, rgb(var(--color-edge-dark)) 1px, rgb(0 0 0 / 0.4) 1px, rgb(0 0 0 / 0) 6px, rgb(0 0 0 / 0) calc(100% - 1px), rgb(var(--color-edge-light) / 0.22) calc(100% - 1px))'

// Elevation: the only drop shadows the recipe uses (each value matches what existing rules already used)
const ELEVATION = Object.freeze({
  raised: '0 2px 3px rgb(0 0 0 / 0.45)', // subtle raised surface: buttons
  panel: '3px 3px 6px rgb(0 0 0 / 0.5)', // equipment panel / framed object: cards, artwork
  overlay: '0 6px 16px rgb(0 0 0 / 0.55)' // prominent frame floating above the chassis: dialogs, menus
})

// Control states as complete declaration sets, for rules to reuse as-is
// Subtle key cap for an existing bare-glyph control: a bevelled steel face on the control's own box (no fill
// change, no size change); pressed inverts the bevel
const KEY_CAP = Object.freeze({ 'border-radius': RADIUS.key, 'background-image': STEEL_SHEEN, 'box-shadow': RAISED_BEVEL })
const KEY_CAP_PRESSED = Object.freeze({ 'background-image': 'none', 'box-shadow': PRESSED_BEVEL })
// Player key: the player's own transport and secondary keys (never the shared KEY_CAP surfaces). The face is darker
// than the deck it sits in: a translucent fill of the recessed token, so it follows the deck's own gradient instead
// of a fixed color. It is lit from the upper left by a soft sheen and edged in two steps: a lit/dark inner bevel with
// a faint second highlight line, and a dark outer ring (the channel the key sits in) with a short drop shadow. Every
// layer is background or shadow paint on the key's existing box, so size, position and hit area cannot change.
// Pressed: the sheen and drop shadow go, the ring stays, and the face is cut in (inverted bevel plus an inner shade).
// Phase 4B painted these sets on each key's own box; since Phase 4J the keys are fixed-size faces (below) built from the
// same sheen, bevel and ring layers, so no rule applies PLAYER_KEY itself any more: it stays the family's reference finish
const PLAYER_KEY_FACE = 'rgb(var(--color-recessed) / 0.5)'
// The collapsed player's deck is already close to the recessed color, so its face goes deeper to keep the same step
const PLAYER_KEY_FACE_MINI = 'rgb(var(--color-recessed) / 0.85)'
const PLAYER_KEY_SHEEN = 'linear-gradient(180deg, rgb(var(--color-edge-light) / 0.2) 0%, rgb(var(--color-edge-light) / 0) 45%, rgb(0 0 0 / 0.22) 100%)'
const PLAYER_KEY_RING = '0 0 0 1px rgb(var(--color-edge-dark))'
const PLAYER_KEY = Object.freeze({
  'border-radius': RADIUS.key,
  'background-color': PLAYER_KEY_FACE,
  'background-image': PLAYER_KEY_SHEEN,
  'box-shadow': `inset 1px 1px 0 rgb(var(--color-edge-light) / 0.6), inset 2px 2px 0 rgb(var(--color-edge-light) / 0.12), inset -1px -1px 0 rgb(var(--color-edge-dark)), ${PLAYER_KEY_RING}, 0 2px 3px rgb(0 0 0 / 0.5)`
})
const PLAYER_KEY_PRESSED = Object.freeze({
  'background-image': 'none',
  'box-shadow': `inset 1px 1px 0 rgb(var(--color-edge-dark)), inset -1px -1px 0 rgb(var(--color-edge-light) / 0.3), inset 0 3px 6px rgb(0 0 0 / 0.55), ${PLAYER_KEY_RING}`
})
// Primary Play/Pause key (Phase 4H): the same dark equipment key as the player keys, made primary by its size and
// central position (the player's own 65px / 40px box), a stronger bezel and a deeper drop, not by a lighter face. The
// player sets the button's fill inline (surface.raised), so the darker face is a translucent recessed layer over that
// fill under the player-key sheen; the collapsed player's deck is already dark, so its face goes deeper. Bezel: the
// player key's lit/dark inner bevel with its faint second highlight, a 1px dark channel, a lit return ring outside it
// and a 0 3px 5px drop (player keys: 0 2px 3px). Squared on the key radius. Paint only, on the existing box.
// Pressed: sheen and drop shadow go, the face deepens, the bevel inverts with an inner shade; channel and ring stay
// --- Steel keys (faithful pass, 2026-10-10) ---
// The approved mockup's keys are raised steel, lighter than the deck they sit in (its key faces sample #384B6E at the top
// to #23324D at the bottom, in a near-black channel, #0B111D), and each has a framed inner face: a steel bezel band, a dark
// groove and a lit lip, with the legend on the inner face. Built from paint only on each key's existing box: the face is a
// top-lit gradient from the raised steel to the panel steel; the outer bevel (lit upper-left, dark lower-right) is drawn
// first, then the bezel band k px wide in the raised steel, the groove (1px dark) and the inner face's lit upper-left lip;
// outside, the channel ring and a short drop. k follows the key's size, so every key keeps the same proportions:
// mini 3px (34px keys), utility/chrome 4px, transport 5px, primary 6px. Pressed: the face darkens and inverts its light,
// the outer bevel inverts and an inner shade falls from the top; the frame and channel stay, so the key reads as pushed in
// Material polish (2026-10-10): the faces were flat. The face now falls off like the mockup's (#384C6F at the top to
// #202F49 at the bottom): the steel gradient reaches the panel steel by mid-key, a shade deepens the lower half and a faint
// diagonal shade darkens the lower right. Nothing lightens the face, so its brightest point (the top edge, the raised
// steel under 8% shade) stays darker than the draft's raised-steel top and the legend's contrast only rises. The bezel
// band is lit directionally (see keyBevel) and the inner face is a raised panel inside its groove (lit upper-left lip,
// shaded lower-right lip)
const STEEL_FACE = 'linear-gradient(135deg, rgb(0 0 0 / 0) 45%, rgb(0 0 0 / 0.1) 100%), linear-gradient(180deg, rgb(0 0 0 / 0.08) 0%, rgb(0 0 0 / 0) 30%, rgb(0 0 0 / 0) 45%, rgb(0 0 0 / 0.3) 100%), linear-gradient(180deg, rgb(var(--color-secondary)) 0%, rgb(var(--color-bg)) 65%, rgb(var(--color-bg)) 100%)'
// Pressed: the panel steel, shaded from the top where the bezel overhangs it (never lighter than the panel steel)
const STEEL_FACE_PRESSED = 'linear-gradient(180deg, rgb(0 0 0 / 0.3) 0%, rgb(0 0 0 / 0.08) 45%, rgb(0 0 0 / 0.04) 100%), linear-gradient(180deg, rgb(var(--color-bg)) 0%, rgb(var(--color-bg)) 100%)'
// The channel a key sits in: a near-black line and a soft dark socket 2px beyond it (the mockup's keys sit in dark wells)
const STEEL_CHANNEL = '0 0 0 1px rgb(0 0 0 / 0.85), 0 0 0 3px rgb(0 0 0 / 0.22)'
// Directional bevel inside a key's box, starting o px in (0 for a key, 3 for a navigation tab inside its gap): a bright 1px
// specular top edge, a softer lit left edge and dark right and bottom edges, then the k px bezel band shaded by direction
// (top lit, left faintly lit, right and bottom in shade: inset strips, one per side, over the band's flat raised steel).
// With framed = true the band ends in the dark groove and the inner face's two lips; without, the face runs to the band
const keyBevel = (o, k, framed = true) => {
  const at = o ? ` ${o}px` : ''
  return [
    `inset 0 1px 0${at} rgb(var(--color-edge-light))`,
    `inset 1px 0 0${at} rgb(var(--color-edge-light) / 0.5)`,
    `inset -1px 0 0${at} rgb(var(--color-edge-dark) / 0.9)`,
    `inset 0 -1px 0${at} rgb(var(--color-edge-dark))`,
    `inset 0 ${k}px 0${at} rgb(var(--color-edge-light) / 0.28)`,
    `inset ${k}px 0 0${at} rgb(var(--color-edge-light) / 0.12)`,
    `inset -${k}px 0 0${at} rgb(0 0 0 / 0.22)`,
    `inset 0 -${k}px 0${at} rgb(0 0 0 / 0.42)`,
    ...(framed ? [`inset 0 0 0 ${o + k}px rgb(var(--color-secondary))`, `inset 0 0 0 ${o + k + 1}px rgb(0 0 0 / 0.8)`, `inset 1px 1px 0 ${o + k + 1}px rgb(var(--color-edge-light) / 0.75)`, `inset -1px -1px 0 ${o + k + 1}px rgb(0 0 0 / 0.5)`] : [])
  ].join(', ')
}
// Pressed: the edges and the band's light invert (dark top, faint lit bottom), the groove stays and a shade falls from the top
const keyBevelPressed = (k) =>
  ['inset 0 1px 0 rgb(var(--color-edge-dark))', 'inset 1px 0 0 rgb(var(--color-edge-dark) / 0.8)', 'inset 0 -1px 0 rgb(var(--color-edge-light) / 0.35)', `inset 0 ${k}px 0 rgb(0 0 0 / 0.3)`, `inset 0 -${k}px 0 rgb(var(--color-edge-light) / 0.1)`, `inset 0 0 0 ${k}px rgb(var(--color-secondary) / 0.85)`, `inset 0 0 0 ${k + 1}px rgb(0 0 0 / 0.85)`, `inset 0 ${k + 3}px 6px rgb(0 0 0 / 0.4)`].join(', ')
const steelKey = (k, drop) =>
  Object.freeze({
    'background-color': 'rgb(var(--color-bg))',
    'background-image': STEEL_FACE,
    'box-shadow': `${keyBevel(0, k)}, ${STEEL_CHANNEL}, ${drop}`
  })
const steelKeyPressed = (k) =>
  Object.freeze({
    'background-color': 'rgb(var(--color-bg))',
    'background-image': STEEL_FACE_PRESSED,
    'box-shadow': `${keyBevelPressed(k)}, ${STEEL_CHANNEL}`
  })
const KEY_DEPTH = Object.freeze({ mini: 3, utility: 4, transport: 5, primary: 6 })
// Primary Play/Pause: the same steel key, made primary by its size, the widest frame and the deepest drop. The player sets
// the button's fill inline (surface.raised), so the face is painted with background-image only
const PRIMARY_KEY = Object.freeze({ 'border-radius': RADIUS.key, ...steelKey(KEY_DEPTH.primary, '0 3px 5px rgb(0 0 0 / 0.7)') })
const PRIMARY_KEY_MINI = Object.freeze({ ...steelKey(KEY_DEPTH.mini, '0 2px 3px rgb(0 0 0 / 0.55)') })
const PRIMARY_KEY_PRESSED = steelKeyPressed(KEY_DEPTH.primary)
const PRIMARY_KEY_PRESSED_MINI = steelKeyPressed(KEY_DEPTH.mini)
// Its legend (Play, Pause, the seek-pending glyph and the loading spinner alike) is the playback amber, solid and large:
// 2.8rem in the full player (from 2.1rem) and 1.875rem collapsed (from 1.5rem, the jump glyphs' size). The glyph size is
// the recipe's second authorized geometry (Phase 4H): it sizes the glyph inside the unchanged 65px / 40px box only
const PRIMARY_GLYPH_SIZE = Object.freeze({ full: Object.freeze({ 'font-size': '2.8rem' }), mini: Object.freeze({ 'font-size': '1.875rem' }) })
// Control legend: the played-progress amber, the illuminated legend of the deck's physical keys (transport and utility).
// LLAMA's equipment rule: amber = an illuminated physical-control legend, green = a readout or live display
const PLAYBACK_LEGEND = Object.freeze({ color: 'rgb(var(--color-legend))' })
// Heavier playback glyphs. The bundled icon font has only a FILL axis (no weight axis, and FILL leaves these stroke
// glyphs unchanged), so the transport glyphs get a hairline stroke in their own color. It adds ink, never layout, and
// the utility keys stay at the font's regular weight, which keeps the transport legends the heavier tier
const PLAYBACK_GLYPH_WEIGHT = Object.freeze({ '-webkit-text-stroke': '0.5px currentColor' })

// --- Control deck (Phase 4J, recomposed in the Phase 4 finishing pass) ---
// Every secondary key's own box is its hardware: a fixed CSS px size, never content- or font-driven (the system font scale
// zooms legends, not px lengths), with the face painted on the element itself, so the visible key and its touch region are
// the same box at every font scale. (Phase 4J first drew enlarged faces as a ::before around glyph-sized boxes; the
// finishing pass replaced that with real boxes: no pseudo-element hit area, no stacking context, and a deck whose geometry
// no longer grows with the font scale.) The full player's five transport keys are packed and centered as one bank with
// one gap; the primary keeps its 65px box and sits a few px higher than the secondary keys' center line.
const DECK = Object.freeze({
  transport: Object.freeze({ width: 60, height: 60, gap: 8 }),
  primaryLift: 3,
  utility: Object.freeze({ width: 54, height: 52 }),
  // The seam between the transport and utility sections. The player puts the transport row's bottom edge (the seam) 78px
  // above the deck's bottom; the seam drops a few px inside the deck (the row grows by a bottom padding as its offset
  // shrinks by the same amount), so the bank keeps its place, its drop shadows clear the seam, and the deck keeps its height
  seam: Object.freeze({ base: 78, drop: 5 }),
  jumpGlyph: '2rem',
  mini: Object.freeze({ width: 34, height: 34 }),
  // Top chrome (collapse, cast, overflow): three utility-family keys on one line, 20px from the top
  chrome: Object.freeze({ width: 44, height: 44, top: 20 })
})
const keyBox = ({ width, height }) => Object.freeze({ width: `${width}px`, height: `${height}px`, flex: 'none', display: 'flex', 'align-items': 'center', 'justify-content': 'center', 'border-radius': RADIUS.key })
const KEY_BOX = Object.freeze({ transport: keyBox(DECK.transport), utility: keyBox(DECK.utility), mini: keyBox(DECK.mini) })
const TRANSPORT_BANK = Object.freeze({ 'justify-content': 'center', gap: `${DECK.transport.gap}px` })
const PRIMARY_IN_BANK = Object.freeze({ margin: '0', top: `-${DECK.primaryLift}px` })
const TRANSPORT_SECTION = Object.freeze({ bottom: `${DECK.seam.base - DECK.seam.drop}px`, 'padding-bottom': `${DECK.seam.drop}px` })
// The utility keys sit centered in the section under the seam (the odd px above them): no padding, the row's own offset
const UTILITY_SECTION_DEPTH = DECK.seam.base - DECK.seam.drop
const UTILITY_ROW = Object.freeze({ bottom: `${Math.floor((UTILITY_SECTION_DEPTH - DECK.utility.height) / 2)}px`, 'padding-top': '0px', 'padding-bottom': '0px' })
// Face finish, the Phase 4H primary key's family: the same dark recessed face, sheen and inner bevel (lit edge, faint second
// highlight, dark edge) and the same pressed model (sheen and drop go, the face deepens, the bevel inverts with an inner
// shade, the channel stays). Secondary transport is one step below the primary: a weaker lit return ring (0.18, primary
// 0.3) and a shorter drop (0 2px 4px, primary 0 3px 5px), on a smaller face
const KEY_FACE_BEVEL = 'inset 1px 1px 0 rgb(var(--color-edge-light) / 0.6), inset 2px 2px 0 rgb(var(--color-edge-light) / 0.12), inset -1px -1px 0 rgb(var(--color-edge-dark))'
const keyPressedBevel = (shade) => `inset 1px 1px 0 rgb(var(--color-edge-dark)), inset -1px -1px 0 rgb(var(--color-edge-light) / 0.3), inset 0 3px 6px rgb(0 0 0 / ${shade})`
const TRANSPORT_KEY_RING = `${PLAYER_KEY_RING}, 0 0 0 2px rgb(var(--color-edge-light) / 0.18)`
// Steel keys since the faithful pass (see steelKey); the dark-face family above stays as the reference finish
const TRANSPORT_KEY = steelKey(KEY_DEPTH.transport, '0 3px 4px rgb(0 0 0 / 0.65)')
const TRANSPORT_KEY_PRESSED = steelKeyPressed(KEY_DEPTH.transport)
// Collapsed player and list rows: the same key at the small keys' frame depth
const TRANSPORT_KEY_MINI = steelKey(KEY_DEPTH.mini, '0 2px 3px rgb(0 0 0 / 0.5)')
const TRANSPORT_KEY_PRESSED_MINI = steelKeyPressed(KEY_DEPTH.mini)
// Unavailable key: a sunken socket in the same place, unavailable by shape and luminance rather than color. A flat, darker
// face cut into the deck (dark inner edge, inner shade, a faint lit lip below), no raised bevel, ring or drop, and a
// strongly dimmed legend
const KEY_SOCKET = Object.freeze({
  'background-color': 'rgb(var(--color-recessed) / 0.4)',
  'background-image': 'none',
  'box-shadow': 'inset 0 0 0 1px rgb(var(--color-edge-dark)), inset 0 2px 4px rgb(0 0 0 / 0.5), 0 1px 0 rgb(var(--color-edge-light) / 0.14)'
})
const KEY_SOCKET_LEGEND = Object.freeze({ color: 'rgb(var(--color-fg) / 0.22)' })
// Utility keys (queue, bookmark, sleep, chapters): the same face family with a restrained bezel, the inner bevel and the dark
// channel only (no lit return) and the player keys' short drop, on a smaller face in the row below. Their legends are the
// deck's illuminated amber, like the transport keys' (the tiers differ by size, bezel and row, and the transport glyphs are
// heavier), at the font's regular weight; never the success green, which is the readouts'
const UTILITY_KEY = steelKey(KEY_DEPTH.utility, '0 2px 4px rgb(0 0 0 / 0.55)')
const UTILITY_KEY_PRESSED = steelKeyPressed(KEY_DEPTH.utility)
const UTILITY_LEGEND = Object.freeze({ ...PLAYBACK_LEGEND })
// The top chrome (navigation and menus, not deck controls) keeps a neutral legend, the primary text at 0.8
// (faithful pass: the mockup lights its top chrome keys, collapse and overflow, with the same amber legend as the deck)
const CHROME_LEGEND = Object.freeze({ ...PLAYBACK_LEGEND })
// Top player chrome: collapse, cast and the overflow menu are neutral utility-family keys of one fixed size, aligned on one
// line (their containers sit 16px and 24px down, so each key is shifted onto the line by a margin), with a 2rem glyph that
// stays inside the key at font scale 1.3. The click stays on the same element, now the whole key: collapse goes from its
// 48px glyph box to the 44px key, cast and overflow grow from 30px (39px at 1.3). Cast keeps its slot (64px from the right
// edge), 4px from the overflow key, so the landscape metadata readout still ends before it (Phase 4E-R2: 111px from the
// right edge, plate 4px, cast face 108px). The playback method (Direct/Local/Transcode) is a small recessed green display
// centered on the same line
const CHROME_KEY = Object.freeze({ ...keyBox(DECK.chrome), ...UTILITY_KEY, ...CHROME_LEGEND, 'font-size': '2rem' })
const CHROME_KEY_LINE = Object.freeze({ collapse: Object.freeze({ 'margin-top': `${DECK.chrome.top - 16}px` }), right: Object.freeze({ 'margin-top': `${DECK.chrome.top - 24}px` }) })
const PLAYBACK_METHOD_READOUT = Object.freeze({
  width: 'fit-content',
  padding: '4px 10px',
  top: `${DECK.chrome.top + DECK.chrome.height / 2 - 11.5}px`,
  'border-radius': RADIUS.key,
  'background-color': 'rgb(var(--color-recessed))',
  'box-shadow': `${RECESSED_WELL}, 0 0 0 1px rgb(var(--color-edge-dark)), 0 1px 0 1px rgb(var(--color-edge-light) / 0.2)`,
  color: 'rgb(var(--color-accent))'
})
// The queue key's "Q" badge sits in the key's lower-right corner, 3px inside it (it used to hang off the glyph box's
// corner), with a thin dark ring that seats it on the face. Same 14px badge
const QUEUE_BADGE = Object.freeze({ bottom: '3px', right: '3px', 'box-shadow': '0 0 0 1px rgb(var(--color-edge-dark))' })
// Readouts in the secondary row (speed, running sleep countdown): information, not keys. Each is a recessed display on its
// own box, the utility keys' height (never a raised face, never amber), with the phosphor green text inside. Its width
// follows its value (and the font scale) from a utility key's width up, and it keeps its own click
const READOUT_BOX = Object.freeze({
  display: 'inline-flex',
  'align-items': 'center',
  'justify-content': 'center',
  height: `${DECK.utility.height}px`,
  'min-width': `${DECK.utility.width}px`,
  width: 'auto',
  padding: '0 10px',
  'line-height': '1',
  'border-radius': RADIUS.key,
  'background-color': 'rgb(var(--color-recessed))',
  // Faithful pass: a steel key holding a glass display (the mockup's 1.5x key): the utility key's outer bevel and steel
  // band, a dark groove, then the glass with its top shade; the channel and drop outside
  'box-shadow': `${keyBevel(0, KEY_DEPTH.utility, false)}, inset 0 0 0 ${KEY_DEPTH.utility}px rgb(var(--color-secondary)), inset 0 0 0 ${KEY_DEPTH.utility + 1}px rgb(0 0 0 / 0.9), inset 0 ${KEY_DEPTH.utility + 3}px 6px -2px rgb(0 0 0 / 0.7), ${STEEL_CHANNEL}, 0 2px 4px rgb(0 0 0 / 0.55)`
})
// Transport legends, sized for the 60px keys. The jump arrow is 2rem (from 1.875rem) and sits 2px lower so the arrow and its
// bold dynamic duration are centered as one legend (a transform, so nothing moves): at font scale 1.3 the legend is
// 31.6x51.8 and 4.1px from the top and bottom of the key, at 1.0 24.4x40. The chapter steps' glyph is 2.25rem (from 2rem):
// safe now that the key's box is fixed (the glyph box no longer sizes the key). The collapsed jumps' arrow is drawn at a
// fixed 0.78 scale, 1px lower, so it stays inside its 34x34 key at font scale 1.3 (the WebView's text zoom multiplies
// every computed font size, so a legend cannot be capped by font size)
const JUMP_GLYPH = Object.freeze({ 'font-size': DECK.jumpGlyph, transform: 'translateY(2px)' })
const JUMP_DURATION = Object.freeze({ 'font-weight': '700' })
const CHAPTER_GLYPH = Object.freeze({ 'font-size': '2.25rem' })
const MINI_JUMP_GLYPH = Object.freeze({ transform: 'translateY(1px) scale(0.78)' })
// Selected equipment key: pressed in (inset bevel and inner shade, so it reads as a different physical state,
// not just a color) with an accent ring inside the edge
// LCD glow: the mockup's green readouts bloom slightly on their glass. A soft text shadow in the readout's own color
const LCD_GLOW = Object.freeze({ 'text-shadow': '0 0 6px rgb(var(--color-accent) / 0.35)' })
const LCD_TEXT = Object.freeze({ color: 'rgb(var(--color-accent))', ...LCD_GLOW })
// Lit label: the mockup's amber section strips (NOW PLAYING, UP NEXT, Playlist Items), condensed caps in the legend amber
const LIT_LABEL = Object.freeze({ color: 'rgb(var(--color-legend))', 'font-family': 'sans-serif-condensed, sans-serif', 'letter-spacing': '0.06em' })
// Faint horizontal scan lines on the graphite page (the mockup's dark backgrounds carry a fine line texture). Finishing
// corrections: 0.02 read as stripes across the screen on the device; at 0.007 the lines are a graphite texture seen on
// close inspection (about 2 levels of 255 over the near-black page)
const SCANLINES = 'repeating-linear-gradient(180deg, rgb(255 255 255 / 0.007) 0, rgb(255 255 255 / 0.007) 1px, rgb(0 0 0 / 0) 1px, rgb(0 0 0 / 0) 3px)'
// A steel frame drawn around a glass panel (the mockup's Up Next and list frames): a dark seam at the glass, a 5px steel
// band with a lit upper-left lip and a dark lower-right lip, and a dark outer line. Outer spread shadows: no layout
const STEEL_FRAME_OUTSIDE = '0 0 0 1px rgb(0 0 0 / 0.85), 0 0 0 5px rgb(var(--color-secondary)), -1px -1px 0 5px rgb(var(--color-edge-light) / 0.6), 1px 1px 0 5px rgb(var(--color-edge-dark)), 0 0 0 6px rgb(0 0 0 / 0.7)'
// Seek thumb: a steel slider block (10x20, the mockup's knob) with a lit top, a dark base and a center grip line. It is
// the decorative inner element of the thumb (pointer-events-none); the touch target is the separate hit element
const SEEK_THUMB = Object.freeze({
  width: '10px',
  height: '20px',
  'border-radius': '2px',
  'background-color': 'rgb(var(--color-bg))',
  'background-image': `linear-gradient(90deg, rgb(0 0 0 / 0) 4px, rgb(0 0 0 / 0.45) 4px, rgb(0 0 0 / 0.45) 5px, rgb(var(--color-edge-light) / 0.45) 5px, rgb(var(--color-edge-light) / 0.45) 6px, rgb(0 0 0 / 0) 6px), ${STEEL_FACE}`,
  'box-shadow': `inset 1px 1px 0 rgb(var(--color-edge-light) / 0.85), inset -1px -1px 0 rgb(var(--color-edge-dark)), 0 0 0 1px rgb(0 0 0 / 0.8), 0 2px 3px rgb(0 0 0 / 0.6)`
})
// Navigation tab keys: each tab is drawn as a separate steel key inside its own box. A 2px ring in the page color makes the
// gap between neighbouring keys (the tabs touch), then a dark channel line and the key's lit upper-left and dark lower-right
// lips. Selected: a black glass key with a 2px green ring and a soft green glow inside the same gap (the mockup's Home key)
const NAV_KEY = Object.freeze({
  'background-image': STEEL_FACE,
  // Material polish: the steel keys' directional bevel and band (3px), inside the gap and channel
  'box-shadow': `inset 0 0 0 2px rgb(var(--color-page)), inset 0 0 0 3px rgb(0 0 0 / 0.75), ${keyBevel(3, 4, false)}`
})
const NAV_KEY_SELECTED = Object.freeze({
  'background-color': 'rgb(var(--color-recessed))',
  'background-image': 'none',
  'box-shadow': 'inset 0 0 0 2px rgb(var(--color-page)), inset 0 0 0 4px rgb(var(--color-accent) / 0.85), inset 0 0 12px 4px rgb(var(--color-accent) / 0.22)',
  color: 'rgb(var(--color-accent))',
  'text-shadow': '0 0 6px rgb(var(--color-accent) / 0.35)'
})
// Action key (final consistency pass, 2026-10-10): an app-owned primary action (the success-colored ui-btn: Play, Stream,
// Create, Update, Create New Playlist, Save, New Folder) is the deck's steel key with the lit amber legend, instead of a
// green rectangle. The same steel face, bevel, band, groove and channel as the player keys (utility depth; small buttons
// the mini depth), on the button's own box (paint only: its size, padding and hit area are unchanged). Pressed: the
// player keys' pressed state. Unavailable: the deck's sunken socket with the dimmed legend. Destructive (bg-error) and
// secondary buttons keep their own finishes, so primary, secondary and destructive stay distinct
const ACTION_KEY = Object.freeze({ 'border-radius': RADIUS.key, 'border-color': 'rgb(var(--color-edge-dark))', ...steelKey(KEY_DEPTH.utility, '0 2px 4px rgb(0 0 0 / 0.55)'), color: 'rgb(var(--color-legend))' })
const ACTION_KEY_SMALL = Object.freeze({ 'box-shadow': steelKey(KEY_DEPTH.mini, '0 2px 3px rgb(0 0 0 / 0.5)')['box-shadow'] })
const ACTION_KEY_PRESSED = steelKeyPressed(KEY_DEPTH.utility)
const ACTION_KEY_PRESSED_SMALL = Object.freeze({ 'box-shadow': steelKeyPressed(KEY_DEPTH.mini)['box-shadow'] })
const ACTION_KEY_UNAVAILABLE = Object.freeze({ ...KEY_SOCKET, ...KEY_SOCKET_LEGEND, 'border-color': 'rgb(var(--color-edge-dark))' })
// Dialog panel (final consistency pass): every app-owned dialog panel is a black recessed glass surface inside a steel
// frame drawn outside it (Up Next's and Chapters' finish), never a broad brushed face; steel is kept for keys and frames
const DIALOG_PANEL = Object.freeze({ 'background-color': 'rgb(var(--color-recessed))', 'background-image': 'none', 'border-color': 'rgb(0 0 0 / 0.8)', 'box-shadow': `${RECESSED_WELL}, ${STEEL_FRAME_OUTSIDE}, ${ELEVATION.overlay}` })
const SELECTED_KEY = Object.freeze({
  'background-image': 'none',
  'box-shadow': `inset 0 0 0 1px rgb(var(--color-accent) / 0.9), ${PRESSED_BEVEL}, inset 0 2px 5px rgb(0 0 0 / 0.45)`
})

// Current playback entry in a list (Up Next, bookmarks): a 2px amber edge along the left inside the row, the
// same played-progress amber and width as Chapters' current-chapter marker, without adding an element
const CURRENT_MARKER = 'inset 2px 0 0 rgb(var(--color-track-cursor))'
// Slot and thumb edges, the same values as the player's seek channel and seek thumb
const INSET_SLOT = 'inset 1px 1px 0 rgb(var(--color-edge-dark)), inset -1px -1px 0 rgb(var(--color-edge-light) / 0.3)'
const THUMB_EDGE = '0 0 0 1px rgb(var(--color-edge-dark)), 0 1px 2px rgb(0 0 0 / 0.6)'

// Full-player metadata readout (Phase 4E): the title/author block becomes a recessed display mounted in a bezel plate
// on the chassis. The well is the shared recessed well; the plate (deck color, lit upper-left lip, dark lower-right
// lip, soft drop) is drawn with outer shadows, so it sits around the box and adds no layout. The seek well has no
// plate, which keeps the two displays related but distinct. Text colors are not touched: title and author stay neutral
const METADATA_READOUT = Object.freeze({
  'background-color': 'rgb(var(--color-recessed))',
  'border-radius': RADIUS.key,
  'box-shadow': `${RECESSED_WELL}, 0 0 0 1px rgb(var(--color-edge-dark)), 0 0 0 3px rgb(var(--color-secondary)), -1px -1px 0 3px rgb(var(--color-edge-light) / 0.35), 1px 1px 0 3px rgb(var(--color-edge-dark)), 0 3px 8px 3px rgb(0 0 0 / 0.4)`
})
// The one authorized geometry in the recipe (Phase 4E, LLAMA full player only): the readout spans the seek wells'
// column (24px gutters), gets an inner inset, and sits 22px lower in the free band above the deck so that it can grow
// without reaching the artwork. Bottom-anchored like the original block (the same property, so the expand/collapse
// transition is kept). Its floor keeps the plate clear of the 200px deck (plate 4px + 6px of chassis): portrait never
// reaches it (the band leaves at least 28px), but in landscape the block sits beside the artwork right on the deck and
// would otherwise cover the seek timestamps. With the total-track display shown, the readout also stays clear of it
const READOUT_ANCHOR = 'calc(50% - var(--cover-image-height) / 2 + 28px)'
const METADATA_READOUT_LAYOUT = Object.freeze({
  left: '24px',
  width: 'calc(100% - 48px)',
  padding: '6px 12px',
  bottom: `max(${READOUT_ANCHOR}, 210px)`
})
// Clearance over the total-track display on screens too short for the anchor alone: its 215px offset plus its height at
// the default font scale (a 19.2px timestamp line and the 4px channel), plus 10px (the plate's 4px and 6px of chassis).
// A px value: the system font scale zooms text in the WebView but not rem/em lengths, so CSS cannot follow the line's
// growth; on the 842px-tall reference screen the anchor already clears it at font scale 1.3
const METADATA_READOUT_ABOVE_TOTAL_TRACK = Object.freeze({ bottom: `max(${READOUT_ANCHOR}, 249px)` })
// Short portrait screens with the total-track display (Phase 4E-R1). Below about 792px the artwork is height-limited, so
// its bottom (and its 2px frame) sits a fixed 320px above the viewport bottom, and the total-track display's top is at
// 244px at font scale 1.3: a 76px band that cannot hold the readout plus its plate (the overlap appears below ~825px).
// There the readout keeps its well, column and side plate but loses the plate's vertical extent: a 1px seam all round,
// the plate and its lips as side rails only, and the vertical inset replaced by a fixed band height with the text
// centered in it. The band height leaves 3px of chassis under the artwork frame (320 - 2 - 3 - 1 - 247 = 67px; capped
// at 70px between 792 and 824px), and its bottom sits 2px plus the seam above the total-track display at font scale
// 1.3. Text larger than the band grows the box upward rather than being clipped. Font scale is not visible to CSS, so
// font scale 1.0 gets the same band, with more room around the text
const METADATA_READOUT_COMPACT_QUERY = '(orientation: portrait) and (max-height: 824px)'
const METADATA_READOUT_COMPACT = Object.freeze({
  'box-shadow': `${RECESSED_WELL}, 0 0 0 1px rgb(var(--color-edge-dark)), -4px 0 0 0 rgb(var(--color-secondary)), 4px 0 0 0 rgb(var(--color-secondary)), -5px 0 0 0 rgb(var(--color-edge-light) / 0.35), 5px 0 0 0 rgb(var(--color-edge-dark)), 0 2px 4px rgb(0 0 0 / 0.4)`,
  display: 'flex',
  'flex-direction': 'column',
  'justify-content': 'center',
  padding: '0 12px',
  bottom: '247px',
  'min-height': 'min(calc(50% - var(--cover-image-height) / 2 - 133px), 70px)'
})
// Landscape with the total-track display (Phase 4E-R2). The block sits in the right half (the player's own landscape
// rule: left 50%, width 50%, both !important), between the top-bar controls and the total-track display. At font scale
// 1.3 the band under the cast and overflow controls (63px down) and above the total-track display (120px down) is 57px,
// less than the text alone (62px), so the readout cannot go below them; it stays where the 249px floor puts it and ends
// before them instead. max-width still limits the !important width: the cast control's 64px right offset plus its width
// at font scale 1.3 (39px), the 4px plate and 4px of chassis leave the readout 111px short of the right edge, and a 24px
// margin aligns its left edge with the seek well (50% - 24px - 111px). Vertical placement and paint are unchanged
const METADATA_READOUT_LANDSCAPE_QUERY = '(orientation: landscape)'
const METADATA_READOUT_LANDSCAPE = Object.freeze({ 'margin-left': '24px', 'max-width': 'calc(50% - 135px)' })

// Phase 6B faceplate paint. The geometry (module budgets, artwork size, the plates' boxes) is theme/playerLayout.js and
// the player's own stylesheet; the recipe only paints the hook classes and plates the layout projection adds.
// A module plate is raised hardware the readouts and keys sit in: the deck color with the chassis sheen, the raised
// bevel, a dark seam around it and the panel drop
// Faithful pass: the mockup's module plates are brushed steel (#3C5077, the raised steel) with a bright specular top edge
// (#AEBDD8), a dark lower edge, a near-black seam around them and a screw head in each corner. All paint: the rivets and the
// brushing are background layers on the plate's own box
// Material polish: quieter screw heads, every one lit the same way (a soft highlight in the head, a dark socket ring)
const rivet = (x, y) => `radial-gradient(circle at ${x} ${y}, rgb(var(--color-edge-light) / 0.75) 0, rgb(var(--color-edge-light) / 0.45) 1px, rgb(var(--color-edge-dark)) 2.4px, rgb(0 0 0 / 0.4) 3.2px, rgb(0 0 0 / 0) 3.8px)`
const RIVETS = [rivet('7px', '7px'), rivet('calc(100% - 7px)', '7px'), rivet('7px', 'calc(100% - 7px)'), rivet('calc(100% - 7px)', 'calc(100% - 7px)')].join(', ')
// Material polish: the brushing was one even stripe every 3px. It is now three faint grains at unrelated periods (3, 5 and
// 11px), so the lines vary in strength instead of repeating, with restrained lighting over them: a soft light falling from
// the upper left and a slow left-to-right variation, as on the mockup's plates. Every layer is a few percent of white or black
const BRUSHED = [
  'radial-gradient(ellipse 120% 100% at 15% 0%, rgb(var(--color-edge-light) / 0.12) 0%, rgb(var(--color-edge-light) / 0) 65%)',
  'linear-gradient(90deg, rgb(255 255 255 / 0.03) 0%, rgb(255 255 255 / 0) 25%, rgb(0 0 0 / 0.05) 55%, rgb(255 255 255 / 0.02) 80%, rgb(0 0 0 / 0.04) 100%)',
  'repeating-linear-gradient(180deg, rgb(255 255 255 / 0.018) 0, rgb(255 255 255 / 0.018) 1px, rgb(0 0 0 / 0) 1px, rgb(0 0 0 / 0) 3px)',
  'repeating-linear-gradient(180deg, rgb(0 0 0 / 0.04) 0, rgb(0 0 0 / 0.04) 1px, rgb(0 0 0 / 0) 1px, rgb(0 0 0 / 0) 5px)',
  'repeating-linear-gradient(180deg, rgb(255 255 255 / 0.018) 0, rgb(255 255 255 / 0.018) 2px, rgb(0 0 0 / 0) 2px, rgb(0 0 0 / 0) 11px)'
].join(', ')
// Plate edges: a bright specular top line with a fainter second line under it, a lit left edge, and a dark right and
// bottom edge with a shade line inside the bottom one (the seam's shadow side)
const STEEL_PLATE_EDGE = 'inset 0 1px 0 rgb(var(--color-edge-light)), inset 0 2px 0 rgb(var(--color-edge-light) / 0.14), inset 1px 0 0 rgb(var(--color-edge-light) / 0.45), inset -1px 0 0 rgb(var(--color-edge-dark)), inset 0 -1px 0 rgb(var(--color-edge-dark)), inset 0 -2px 0 rgb(0 0 0 / 0.3)'
// The seam around a plate: a near-black line with a faint lit return under it
const PLATE_SEAM = '0 0 0 1px rgb(0 0 0 / 0.85), 0 1px 0 1px rgb(var(--color-edge-light) / 0.12)'
const FACEPLATE_PLATE = Object.freeze({
  'background-color': 'rgb(var(--color-secondary))',
  'background-image': `${RIVETS}, ${BRUSHED}, ${CHASSIS_SHEEN}`,
  'border-radius': RADIUS.key,
  'box-shadow': `${STEEL_PLATE_EDGE}, ${PLATE_SEAM}, 0 3px 8px rgb(0 0 0 / 0.5)`
})
// The top chrome plate carries navigation: the same steel plate (the mockup's top bar is a full module plate)
const FACEPLATE_TOP_PLATE = Object.freeze({
  'box-shadow': `${STEEL_PLATE_EDGE}, ${PLATE_SEAM}, 0 3px 8px rgb(0 0 0 / 0.5)`
})
// Artwork bay: a bezel drawn outside the artwork with spread shadows only (no layout, no crop): a dark seam at the art,
// the deck-colored bezel with a lit upper-left lip and a dark lower-right lip, a dark outer ring and a soft drop. Its
// width is the layout tier's (playerLayout.TIERS), which also reserves the room for it
const artworkBay = (n) =>
  Object.freeze({
    'box-shadow': `${artFrame(n, playerLayout.FACEPLATE.bezelRing)}, 0 2px 4px ${n + playerLayout.FACEPLATE.bezelRing}px rgb(0 0 0 / 0.5)`
  })
const ARTWORK_BAY = Object.freeze({ regular: artworkBay(playerLayout.TIERS.regular.bezel), compact: artworkBay(playerLayout.TIERS.compact.bezel) })
// The deck becomes chassis behind its console plate: no panel paint of its own
const FACEPLATE_DECK = Object.freeze({ 'background-color': 'rgb(var(--color-page))', 'background-image': 'none', 'box-shadow': 'none' })
// Console banks centered in their framed sections (Phase 6B S26 correction). The console plate's top edge is
// playerLayout.FACEPLATE.console above the deck bottom and its bottom edge FACEPLATE.consoleInset above it; the engraved
// divider stays where the deck puts it (the transport section's bottom, DECK.seam). The rows had kept the offsets they
// had in the full-height panel, so both sat low inside the plate. Now each bank box is exactly its section, with its row
// centered: the transport row by its key envelope (from the lifted primary's top to the secondary keys' bottom: the 65px
// primary box, lifted DECK.primaryLift, and the 60px keys centered in it), the utility row by its keys. Key boxes, their
// horizontal layout and the divider are unchanged
const FACEPLATE_DIVIDER = DECK.seam.base - DECK.seam.drop
const PRIMARY_KEY_BOX = 65 // the full player's Play/Pause box (AudioPlayer.vue, unchanged)
const FACEPLATE_TRANSPORT_BANK = Object.freeze({
  height: `${playerLayout.FACEPLATE.console - FACEPLATE_DIVIDER}px`,
  'padding-top': `${DECK.primaryLift + (PRIMARY_KEY_BOX - DECK.transport.height) / 2}px`,
  'padding-bottom': '0px',
  display: 'flex',
  'flex-direction': 'column',
  'justify-content': 'center'
})
// Chapter + book progress (two rails): the two rows are one recessed well split by an engraved seam. The upper (book)
// section carries the well's top lip and the seam's dark line at its bottom; the lower (chapter) section carries the
// seam's faint light line at its top and the well's bottom lip. The seam uses ENGRAVED_SEPARATOR's two colors, and the
// lips RECESSED_FACE's, so the pair reads as one display with an internal division. Paint only (background layers on the
// content box, as RECESSED_FACE)
const DUAL_TRACK_UPPER = Object.freeze({
  'background-image': 'linear-gradient(180deg, rgb(var(--color-edge-dark)) 0, rgb(var(--color-edge-dark)) 1px, rgb(0 0 0 / 0.4) 1px, rgb(0 0 0 / 0) 6px, rgb(0 0 0 / 0) calc(100% - 1px), rgb(var(--color-edge-dark)) calc(100% - 1px))',
  'border-radius': RADIUS.key,
  'border-bottom-left-radius': RADIUS.none,
  'border-bottom-right-radius': RADIUS.none
})
const DUAL_TRACK_LOWER = Object.freeze({
  'background-image': 'linear-gradient(180deg, rgb(var(--color-edge-light) / 0.12) 0, rgb(var(--color-edge-light) / 0.12) 1px, rgb(0 0 0 / 0) 1px, rgb(0 0 0 / 0) calc(100% - 1px), rgb(var(--color-edge-light) / 0.22) calc(100% - 1px))',
  'border-radius': RADIUS.key,
  'border-top-left-radius': RADIUS.none,
  'border-top-right-radius': RADIUS.none
})
const FACEPLATE_UTILITY_BANK = Object.freeze({
  bottom: `${playerLayout.FACEPLATE.consoleInset}px`,
  height: `${FACEPLATE_DIVIDER - playerLayout.FACEPLATE.consoleInset}px`,
  display: 'flex',
  'flex-direction': 'column',
  'justify-content': 'center'
})

// Collapsed mini-player (Phase 7B, Candidate B): the full player's type and readout language at the mini's fixed 120px,
// with no geometry. Metadata and times take the condensed system face (no font asset) the faceplate uses; the metadata
// stays neutral. The times grow to 14px on the same line box (14px x 1.3714286 = the previous 12.8px x 1.5 = 19.2px, and
// 24.96px at font scale 1.3), so the time row, rail and every offset keep their bounds
const CONDENSED_FACE = 'sans-serif-condensed, sans-serif'
const MINI_TITLE_TYPE = Object.freeze({ 'font-family': CONDENSED_FACE, 'letter-spacing': '0.01em' })
const MINI_AUTHOR_TYPE = Object.freeze({ 'font-family': CONDENSED_FACE, 'letter-spacing': '0.02em' })
const MINI_TIME_TYPE = Object.freeze({ 'font-family': CONDENSED_FACE, 'font-size': '0.875rem', 'line-height': '1.3714286', 'font-variant-numeric': 'tabular-nums', 'letter-spacing': '0.02em' })
// The times and rail as one recessed readout strip across the panel: the seek row's own box gets the recessed face with
// a shaded top edge (in place of the engraved seam, at the same line), and two offset shadows of that box carry the face
// 6px below the rail and end it with a lit line. Shadows are paint outside the box, so nothing moves and nothing gains a
// hit area. Full width, so it has no side edges or corners to meet the artwork, which at font scale 1.3 sits only ~2px
// above the row: an inset well there would land on the artwork
const MINI_READOUT_STRIP = Object.freeze({
  'background-color': 'rgb(var(--color-recessed))',
  'background-image': 'linear-gradient(180deg, rgb(var(--color-edge-dark)) 0, rgb(var(--color-edge-dark)) 1px, rgb(0 0 0 / 0.4) 1px, rgb(0 0 0 / 0) 6px)',
  'box-shadow': '0 6px 0 0 rgb(var(--color-recessed)), 0 7px 0 0 rgb(var(--color-edge-light) / 0.22)'
})
// The mini artwork keeps its frame and gains a soft mount shadow: no bezel, ring or size change
const MINI_ARTWORK_MOUNT = Object.freeze({ 'box-shadow': `${ARTWORK_FRAME}, ${ARTWORK_DROP}` })

// Browsing chrome (Phase 8B, Candidate B of the Phase 8A audit): the content environment around the frozen players takes
// their language in restrained form. Paint and type only; every box keeps its geometry.
// Navigation strip: the content chassis (base surface, chassis sheen, faint lit top lip, dark seam below) instead of the
// brighter steel strip, so the app bar, navigation and content read as one chassis
const BROWSE_NAV_CHASSIS = Object.freeze({
  'background-color': 'rgb(var(--color-bg))',
  'background-image': CHASSIS_SHEEN,
  'box-shadow': 'inset 0 1px 0 rgb(var(--color-edge-light) / 0.18), inset 0 -1px 0 rgb(var(--color-edge-dark))'
})
// Selected tab: a recessed dark key (the players' key language) pressed into the strip; it keeps the accent underline
// and its text label (in book libraries the unselected tabs show only an icon; podcast tabs show both), so the state never reads by color alone
const BROWSE_NAV_SELECTED = Object.freeze({
  'background-color': 'rgb(var(--color-recessed))',
  'background-image': RECESSED_FACE,
  'box-shadow': `${PRESSED_BEVEL}, inset 0 -2px 0 rgb(var(--color-accent))`
})
// Section labels (shelf headings, search result groups): the condensed system face, neutral, same size and weight
const SECTION_LABEL_TYPE = Object.freeze({ 'font-family': CONDENSED_FACE, 'letter-spacing': '0.03em' })
// Shelf heading rule: an engraved line (dark seam over a faint lit return) painted by the heading band's own background,
// 6px above the band's bottom edge (so 6px above the artwork) and inset 20px, the band's own text inset
const SHELF_HEADING_RULE = Object.freeze({
  'background-image': `linear-gradient(rgb(var(--color-edge-dark)), rgb(var(--color-edge-dark))), linear-gradient(rgb(var(--color-edge-light) / 0.14), rgb(var(--color-edge-light) / 0.14))`,
  'background-size': 'calc(100% - 40px) 1px, calc(100% - 40px) 1px',
  'background-position': '20px calc(100% - 6px), 20px calc(100% - 5px)',
  'background-repeat': 'no-repeat'
})
// Toolbar item count: condensed with tabular figures, neutral (a count is not a live readout)
const COUNT_TYPE = Object.freeze({ 'font-family': CONDENSED_FACE, 'letter-spacing': '0.02em', 'font-variant-numeric': 'tabular-nums' })
// List-row play button: the mini-player's key face (dark, sheened, squared, ring and drop) on the button's own box, with
// the playback amber legend
const LIST_PLAY_KEY = Object.freeze({ ...TRANSPORT_KEY_MINI, 'border-radius': RADIUS.key })
// Amber like every ordinary action legend (finishing corrections; the faithful pass had lit it green, which is kept for
// displays, selection and activity)
const LIST_PLAY_LEGEND = PLAYBACK_LEGEND

const PRIMITIVES = Object.freeze({
  RADIUS,
  ELEVATION,
  RAISED_BEVEL,
  PRESSED_BEVEL,
  RECESSED_WELL,
  STEEL_SHEEN,
  CHASSIS_SHEEN,
  ARTWORK_FRAME,
  ARTWORK_DROP,
  ENGRAVED_SEPARATOR,
  ENGRAVED_SEPARATOR_TOP,
  RECESSED_FACE,
  KEY_CAP,
  KEY_CAP_PRESSED,
  SELECTED_KEY,
  PLAYER_KEY,
  PLAYER_KEY_PRESSED,
  PRIMARY_KEY,
  PRIMARY_KEY_MINI,
  PRIMARY_KEY_PRESSED,
  PRIMARY_KEY_PRESSED_MINI,
  PRIMARY_GLYPH_SIZE,
  PLAYER_KEY_FACE_MINI,
  PLAYBACK_LEGEND,
  PLAYBACK_GLYPH_WEIGHT,
  DECK,
  KEY_BOX,
  TRANSPORT_BANK,
  PRIMARY_IN_BANK,
  UTILITY_ROW,
  TRANSPORT_SECTION,
  CHROME_KEY,
  CHROME_KEY_LINE,
  PLAYBACK_METHOD_READOUT,
  TRANSPORT_KEY,
  TRANSPORT_KEY_PRESSED,
  KEY_SOCKET,
  KEY_SOCKET_LEGEND,
  JUMP_GLYPH,
  JUMP_DURATION,
  UTILITY_KEY,
  UTILITY_KEY_PRESSED,
  UTILITY_LEGEND,
  CHROME_LEGEND,
  QUEUE_BADGE,
  READOUT_BOX,
  CHAPTER_GLYPH,
  TRANSPORT_KEY_MINI,
  TRANSPORT_KEY_PRESSED_MINI,
  MINI_JUMP_GLYPH,
  METADATA_READOUT,
  METADATA_READOUT_LAYOUT,
  METADATA_READOUT_ABOVE_TOTAL_TRACK,
  METADATA_READOUT_COMPACT,
  METADATA_READOUT_LANDSCAPE,
  FACEPLATE_PLATE,
  FACEPLATE_TOP_PLATE,
  ARTWORK_BAY,
  FACEPLATE_DECK,
  FACEPLATE_DIVIDER,
  PRIMARY_KEY_BOX,
  FACEPLATE_TRANSPORT_BANK,
  FACEPLATE_UTILITY_BANK,
  DUAL_TRACK_UPPER,
  DUAL_TRACK_LOWER,
  CONDENSED_FACE,
  MINI_TITLE_TYPE,
  MINI_AUTHOR_TYPE,
  MINI_TIME_TYPE,
  MINI_READOUT_STRIP,
  MINI_ARTWORK_MOUNT,
  BROWSE_NAV_CHASSIS,
  BROWSE_NAV_SELECTED,
  SECTION_LABEL_TYPE,
  SHELF_HEADING_RULE,
  COUNT_TYPE,
  LIST_PLAY_KEY,
  LIST_PLAY_LEGEND,
  // Faithful pass (2026-10-10)
  STEEL_FACE,
  STEEL_FACE_PRESSED,
  STEEL_CHANNEL,
  KEY_DEPTH,
  RIVETS,
  BRUSHED,
  SCANLINES,
  ARTWORK_FRAME_INSET,
  STEEL_PLATE_EDGE,
  STEEL_FRAME_OUTSIDE,
  SEEK_THUMB,
  LCD_GLOW,
  LCD_TEXT,
  LIT_LABEL,
  NAV_KEY,
  NAV_KEY_SELECTED,
  ACTION_KEY,
  ACTION_KEY_SMALL,
  ACTION_KEY_PRESSED,
  ACTION_KEY_PRESSED_SMALL,
  ACTION_KEY_UNAVAILABLE,
  DIALOG_PANEL
})

const EQUIPMENT_RULES = [
  // Navigation chrome: bevelled chassis strips
  ['#appbar', { 'background-image': CHASSIS_SHEEN, 'box-shadow': `${RAISED_BEVEL}, 0 1px 0 rgb(var(--color-edge-dark))` }],
  // Navigation strip: the content chassis (Phase 8B; it was a steel strip, the brightest surface in the top chrome)
  ['#bookshelf-navbar', BROWSE_NAV_CHASSIS],
  // Selected navigation tab: a recessed dark key pressed into the strip (Phase 8B), keeping its accent underline and its
  // text label, so the state never reads by color alone
  // (BookshelfNavBar binds bg-primary on the active tab; runtime-only router classes would be pruned by Tailwind)
  ['#bookshelf-navbar a.bg-primary', BROWSE_NAV_SELECTED],

  // Buttons: steel sheen over the existing (semantic) button color, raised; pressed = inset
  ['.btn:not(:disabled)', { 'background-image': STEEL_SHEEN, 'box-shadow': `${RAISED_BEVEL}, ${ELEVATION.raised}` }],
  ['.btn:not(:disabled):active', KEY_CAP_PRESSED],
  // Only the bordered icon buttons (IconBtn/ReadIconBtn add `border` unless borderless) are steel keys;
  // borderless icon buttons are intentionally bare glyphs and stay that way
  ['.icon-btn.border:not(:disabled)', { 'background-image': STEEL_SHEEN, 'box-shadow': RAISED_BEVEL }],
  ['.icon-btn.border:not(:disabled):active', KEY_CAP_PRESSED],
  // Destructive key glyph: LLAMA's near-white text on the sheened error fill is 2.80:1; pure white keeps the
  // icon above the 3:1 non-text minimum (Gate H, measured 3.2:1)
  ['.icon-btn.border.bg-error:not(:disabled) > .material-symbols', { color: 'rgb(255 255 255)' }],

  // Text fields and selects: recessed display wells
  ['input:not([type=range]):not([type=checkbox]):not([type=radio])', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': RECESSED_WELL }],
  ['textarea', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': RECESSED_WELL }],

  // Dialog / menu panels: raised chassis
  ['.modal .rounded-lg.bg-primary', { 'background-image': CHASSIS_SHEEN, 'box-shadow': `${RAISED_BEVEL}, ${ELEVATION.overlay}` }],
  // Up Next list: a recessed playlist well inside the chassis
  ['.modal .queue-panel.rounded-lg.bg-primary', { 'background-color': 'rgb(var(--color-recessed))', 'background-image': 'none', 'box-shadow': `${RECESSED_WELL}, ${ELEVATION.overlay}` }],
  // Chapters list: the same recessed well under a blue-gray chassis header strip (ChaptersModal hook class)
  ['.modal .chapters-panel.bg-secondary', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': `${RECESSED_WELL}, ${ELEVATION.overlay}` }],
  ['.modal .chapters-panel > .sticky.bg-secondary', { 'background-image': CHASSIS_SHEEN, 'box-shadow': `${RAISED_BEVEL}, 0 1px 0 rgb(var(--color-edge-dark))` }],
  ['.modal .chapters-panel > .sticky.bg-secondary.shadow-md', { 'box-shadow': `${RAISED_BEVEL}, 0 1px 0 rgb(var(--color-edge-dark)), 0 4px 8px rgb(0 0 0 / 0.5)` }],
  ['.modal .chapters-panel > .sticky p.text-fg-muted', { color: 'rgb(var(--color-fg) / 0.85)' }],
  // Current chapter: a lit row on the dark well (the amber marker stays)
  ['.modal .chapters-panel li.bg-primary', { 'background-color': 'rgb(var(--color-bg))' }],
  // Current-chapter marker: the same played-progress amber as the seek bar (same element, size and position)
  ['.modal .chapters-panel li > .bg-yellow-400', { 'background-color': 'rgb(var(--color-track-cursor))' }],

  // --- Player-adjacent overlays (Gate C): Up Next, playback speed, sleep timer, bookmarks ---
  // Hierarchy: raised chassis panel (the existing dialog rule) > recessed list well > engraved row seams >
  // current/selected state. Rows stay flat display entries, never a stack of keys.
  // Up Next: the Now Playing block is the lit current entry, matching Chapters' current row (lit + amber
  // left marker), pressed in so it reads by shape as well as color; its divider becomes an engraved seam
  ['.modal .queue-panel .queue-current', { 'background-color': 'rgb(var(--color-bg))', 'border-color': 'rgb(var(--color-edge-dark))', 'box-shadow': `${CURRENT_MARKER}, ${PRESSED_BEVEL}, inset 0 -1px 0 rgb(var(--color-edge-light) / 0.12)` }],
  ['.modal .queue-panel .queue-row:not(:last-child)', { 'box-shadow': ENGRAVED_SEPARATOR }],
  // Speed and sleep option lists: a recessed well inside the chassis panel, seamed rows, and the selected
  // speed as a selected equipment key (pressed in, accent ring) on a lit row instead of a flat wash
  ['.modal .playback-option-panel ul[role=listbox]', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': RECESSED_WELL }],
  ['.modal .playback-option-panel li[role=option]:not(:last-child)', { 'box-shadow': ENGRAVED_SEPARATOR }],
  ['.modal .playback-option-panel li[role=option].option-selected', { 'background-color': 'rgb(var(--color-bg))', ...SELECTED_KEY }],
  // Speed stepper strip: a raised chassis strip under the well; its steppers are equipment keys
  ['.modal .playback-option-panel .option-panel-footer', { 'background-image': CHASSIS_SHEEN, 'border-color': 'rgb(var(--color-edge-dark))', 'box-shadow': RAISED_BEVEL }],
  ['.modal .playback-option-panel .icon-num-btn:not(:disabled)', KEY_CAP],
  ['.modal .playback-option-panel .icon-num-btn:not(:disabled):active', KEY_CAP_PRESSED],
  // Live readouts in these overlays: the current speed and the running sleep countdown
  ['.modal .speed-readout', { color: 'rgb(var(--color-accent))' }],
  ['.modal .sleep-readout', { color: 'rgb(var(--color-accent))' }],
  // Bookmarks: recessed list with seamed rows; the bookmark at the current position is a lit, pressed entry
  // with the amber position marker, and its icon is amber (current position), not success green
  ['.modal .bookmarks-list', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': RECESSED_WELL }],
  ['.modal .bookmark-row:not(:last-child)', { 'box-shadow': ENGRAVED_SEPARATOR }],
  ['.modal .bookmark-row.bookmark-current', { 'background-color': 'rgb(var(--color-bg))', 'box-shadow': `${CURRENT_MARKER}, ${PRESSED_BEVEL}` }],
  ['.modal .bookmark-current .bookmark-icon', { color: 'rgb(var(--color-track-cursor))' }],

  // Side drawer: chassis edge facing the page
  ['.layout-wrapper .w-64.bg-bg', { 'background-image': CHASSIS_SHEEN, 'box-shadow': 'inset 1px 0 0 rgb(var(--color-edge-light) / 0.45), -2px 0 8px rgb(0 0 0 / 0.5)' }],

  // Artwork: thin outer frame (outside the image, so the artwork stays fully visible and unresized).
  // Card artwork is marked by the `card-artwork` hook: the grid card itself (it is the cover) and, in list
  // rows, only the cover box, never the whole row
  ['.cover-wrapper', { 'box-shadow': ARTWORK_FRAME, 'border-radius': RADIUS.frame }],
  ['.card-artwork', { 'box-shadow': `${ARTWORK_FRAME}, ${ARTWORK_DROP}` }],

  // Player artwork uses the squared frame radius above (mini 3px, fullscreen 16px before); same box, crop and
  // aspect ratio. Fullscreen artwork sits mounted on the chassis like card artwork
  ['.fullscreen .cover-wrapper', { 'box-shadow': `${ARTWORK_FRAME}, ${ARTWORK_DROP}` }],
  // Fullscreen transport deck: the existing bottom player panel becomes a raised chassis panel (sheen, light
  // top edge, dark seam above it against the artwork zone). Same box; the controls inside are untouched
  ['.fullscreen #playerContent', { 'background-color': 'rgb(var(--color-bg))', 'background-image': CHASSIS_SHEEN, 'box-shadow': `${RAISED_BEVEL}, 0 -1px 0 rgb(var(--color-edge-dark))` }],
  // Phase 6B faceplate (theme/playerLayout.js): the decorative plates, the artwork bay and the deck as chassis. These
  // selectors only match the hook classes and plate elements the layout projection adds to the full player
  ['.player-plate', FACEPLATE_PLATE],
  ['.player-plate-top', FACEPLATE_TOP_PLATE],
  ['#streamContainer.faceplate .cover-wrapper', ARTWORK_BAY.regular],
  ['#streamContainer.faceplate-compact .cover-wrapper', ARTWORK_BAY.compact],
  ['#streamContainer.faceplate #playerContent', FACEPLATE_DECK],
  ['#streamContainer.faceplate-flat #playerContent', FACEPLATE_DECK],
  // ...and each console bank centered in its section. The utility section (divider to plate bottom) is the same on every
  // plate path; the transport has its own section only on the faceplate (on the previous-geometry paths the console plate
  // runs up around the seek row, so the transport keeps its place)
  ['#streamContainer.fullscreen.faceplate #playerControls', FACEPLATE_TRANSPORT_BANK],
  ['#streamContainer.fullscreen.faceplate #playerContent .utility-row', FACEPLATE_UTILITY_BANK],
  ['#streamContainer.fullscreen.faceplate-flat #playerContent .utility-row', FACEPLATE_UTILITY_BANK],
  // Chapter + book progress (two rails): one dual-track instrument above the console (theme/playerLayout.js places it);
  // the deck is chassis, the two rows one recessed well with an engraved seam, the utility bank centered as above
  ['#streamContainer.faceplate-dual #playerContent', FACEPLATE_DECK],
  ['#streamContainer.faceplate-dual .total-track', DUAL_TRACK_UPPER],
  ['#streamContainer.faceplate-dual #playerTrack', DUAL_TRACK_LOWER],
  ['#streamContainer.fullscreen.faceplate-dual #playerContent .utility-row', FACEPLATE_UTILITY_BANK],
  // Panel seam between the primary transport row and the secondary control row
  ['.fullscreen #playerControls', { 'box-shadow': ENGRAVED_SEPARATOR }],
  // Mini-player: the seek region is a recessed readout strip across the panel (Phase 7B; it replaces the engraved seam
  // that was drawn at its top edge)
  ['#streamContainer:not(.fullscreen) #playerTrack', MINI_READOUT_STRIP],
  // Control deck (Phase 4J, recomposed in the finishing pass): every secondary key's own fixed box is its face, painted
  // in the primary key's family; pressed cuts it in, an unavailable key is a sunken socket with a strongly dimmed legend.
  // Full-player transport: chapter start/end and both jumps, packed with the primary into one centered bank
  // The transport section reaches a few px further down (the seam under it drops; the bank stays where it is)
  ['#streamContainer.fullscreen #playerControls', TRANSPORT_SECTION],
  ['.fullscreen #playerControls > div', TRANSPORT_BANK],
  ['.fullscreen #playerControls .play-btn', PRIMARY_IN_BANK],
  ['.fullscreen #playerControls .player-key', { ...KEY_BOX.transport, ...TRANSPORT_KEY }],
  ['.fullscreen #playerControls .player-key:not(.key-disabled):active', TRANSPORT_KEY_PRESSED],
  ['.fullscreen #playerControls .player-key.key-disabled', { ...KEY_SOCKET, ...KEY_SOCKET_LEGEND }],
  ['.fullscreen #playerControls .jump-icon > .material-symbols', JUMP_GLYPH],
  ['.fullscreen #playerControls .jump-label', JUMP_DURATION],
  ['.fullscreen #playerControls .next-icon', CHAPTER_GLYPH],
  // Collapsed player transport: both jumps, deeper face on the darker deck
  ['#streamContainer:not(.fullscreen) #playerControls .player-key', { ...KEY_BOX.mini, ...TRANSPORT_KEY_MINI }],
  ['#streamContainer:not(.fullscreen) #playerControls .player-key:not(.key-disabled):active', TRANSPORT_KEY_PRESSED_MINI],
  ['#streamContainer:not(.fullscreen) #playerControls .player-key.key-disabled', { ...KEY_SOCKET, ...KEY_SOCKET_LEGEND }],
  ['#streamContainer:not(.fullscreen) #playerControls .jump-icon > .material-symbols', MINI_JUMP_GLYPH],
  // Utility keys: restrained bezel, one neutral legend; chapters without chapters is a socket
  ['#playerContent .utility-key', { ...KEY_BOX.utility, ...UTILITY_KEY }],
  ['#playerContent .utility-key:not(.key-disabled):active', UTILITY_KEY_PRESSED],
  ['#playerContent .utility-key.key-disabled', { ...KEY_SOCKET, ...KEY_SOCKET_LEGEND }],
  ['#playerContent .utility-key:not(.key-disabled)', UTILITY_LEGEND],
  ['#playerContent .utility-key:not(.key-disabled) > .material-symbols', UTILITY_LEGEND],
  ['#playerContent .utility-key:not(.key-disabled) > svg', UTILITY_LEGEND],
  ['#playerContent .queue-key > span.absolute', QUEUE_BADGE],
  // Readouts: speed and the running sleep countdown are recessed green displays, not keys
  ['.fullscreen #playerContent .utility-row', UTILITY_ROW],
  ['.fullscreen #playerContent .speed-readout', READOUT_BOX],
  ['.fullscreen #playerContent .sleep-display', READOUT_BOX],
  // Top chrome (finishing pass): collapse, cast and overflow are utility-family keys on one line; the playback method is a
  // recessed green display between them
  ['#streamContainer.fullscreen .chrome-key', CHROME_KEY],
  ['#streamContainer.fullscreen .chrome-key:active', UTILITY_KEY_PRESSED],
  ['#streamContainer.fullscreen .collapse-key', CHROME_KEY_LINE.collapse],
  ['#streamContainer.fullscreen .cast-key', CHROME_KEY_LINE.right],
  ['#streamContainer.fullscreen .menu-key', CHROME_KEY_LINE.right],
  ['#streamContainer.fullscreen .playback-method', PLAYBACK_METHOD_READOUT],
  // Playback legends: only the transport keys (both jumps, chapter start/end) take the amber; the utility keys
  // (queue, bookmark, sleep, chapters) keep their neutral glyphs. An unavailable key is excluded and keeps its dimmed glyph
  ['#playerContent .jump-icon:not(.key-disabled)', PLAYBACK_LEGEND],
  ['#playerContent .jump-icon:not(.key-disabled) > .material-symbols', PLAYBACK_GLYPH_WEIGHT],
  ['#playerContent .next-icon:not(.key-disabled)', { ...PLAYBACK_LEGEND, ...PLAYBACK_GLYPH_WEIGHT }],
  // Live sleep countdown is a readout: phosphor green like the other readouts (state.success keeps meaning
  // finished/complete everywhere else)
  ['#playerContent .sleep-readout', { color: 'rgb(var(--color-accent))' }],

  // Player: recessed display behind the fullscreen seek/readout rows (content box only: padding stays chassis)
  ['.fullscreen #playerTrack', { 'background-color': 'rgb(var(--color-recessed))', 'background-image': RECESSED_FACE, 'background-clip': 'content-box', 'border-radius': RADIUS.key }],
  ['.fullscreen .total-track', { 'background-color': 'rgb(var(--color-recessed))', 'background-image': RECESSED_FACE, 'background-clip': 'content-box', 'border-radius': RADIUS.key }],
  // Seek channels read as inset slots; the played portion stays the amber token even after the player's
  // seek code swaps in its settled-state class (bg-gray-200)
  ['#playerTrack div.relative.rounded-full', { 'box-shadow': 'inset 1px 1px 0 rgb(var(--color-edge-dark)), inset -1px -1px 0 rgb(var(--color-edge-light) / 0.3)' }],
  ['.total-track div.relative.rounded-full', { 'box-shadow': 'inset 1px 1px 0 rgb(var(--color-edge-dark)), inset -1px -1px 0 rgb(var(--color-edge-light) / 0.3)' }],
  ['#playerTrack .bg-track-cursor.bg-gray-200', { 'background-color': 'rgb(var(--color-track-cursor))' }],
  // Pending seek (the seek code's bg-yellow-300 state until playback confirms the position): the same amber,
  // broken into segments, so it reads as not-yet-settled by pattern rather than by a near-identical yellow
  ['#playerTrack .bg-track-cursor.bg-yellow-300', { 'background-color': 'transparent', 'background-image': 'repeating-linear-gradient(90deg, rgb(var(--color-track-cursor)) 0 4px, rgb(var(--color-track-cursor) / 0.3) 4px 7px)' }],
  // ...but a drag started before that seek settles (seek-dragging hook) keeps the line solid under the finger
  ['#playerTrack .seek-dragging > .bg-track-cursor.bg-yellow-300', { 'background-color': 'rgb(var(--color-track-cursor))', 'background-image': 'none' }],
  ['#playerTrack .pointer-events-auto > .bg-track-cursor', { 'box-shadow': '0 0 0 1px rgb(var(--color-edge-dark)), 0 1px 2px rgb(0 0 0 / 0.6)' }],
  // Phosphor-green readouts: timestamps, playback speed and the playback-method label (titles stay neutral)
  ['#playerTrack p.font-mono', { color: 'rgb(var(--color-accent))' }],
  ['.total-track p.font-mono', { color: 'rgb(var(--color-accent))' }],
  ['#playerContent .speed-readout', { color: 'rgb(var(--color-accent))' }],
  ['#streamContainer p.tracking-widest', { color: 'rgb(var(--color-accent) / 0.85)' }],
  // Full-player metadata readout (title/chapter and author): recessed display in a bezel plate, plus its authorized
  // metadata-only layout. The collapsed player's block (no .fullscreen) is untouched
  ['.fullscreen .title-author-texts', { ...METADATA_READOUT, ...METADATA_READOUT_LAYOUT }],
  ['.fullscreen .total-track ~ .title-author-texts', METADATA_READOUT_ABOVE_TOTAL_TRACK],
  // Transport: the primary Play/Pause key (Phase 4H), squared dark key with the primary bezel; pressed cuts it in.
  // Its glyph, seek-pending glyph and loading spinner share the playback amber; the glyph is sized up in its box
  ['#playerControls .play-btn', PRIMARY_KEY],
  ['#playerControls .play-btn:active', PRIMARY_KEY_PRESSED],
  ['#streamContainer:not(.fullscreen) #playerControls .play-btn', PRIMARY_KEY_MINI],
  ['#streamContainer:not(.fullscreen) #playerControls .play-btn:active', PRIMARY_KEY_PRESSED_MINI],
  ['#playerControls .play-btn .material-symbols', PLAYBACK_LEGEND],
  ['#playerControls .play-btn .la-ball-spin-clockwise', PLAYBACK_LEGEND],
  ['.fullscreen #playerControls .play-btn .material-symbols', PRIMARY_GLYPH_SIZE.full],
  ['#streamContainer:not(.fullscreen) #playerControls .play-btn .material-symbols', PRIMARY_GLYPH_SIZE.mini],
  // Collapsed mini-player: chassis top edge (same footprint)
  ['#streamContainer:not(.fullscreen) #playerContent', { 'box-shadow': 'inset 0 1px 0 rgb(var(--color-edge-light) / 0.45), 0 -8px 8px rgb(0 0 0 / 0.33)' }],
  // Collapsed mini-player (Phase 7B, Candidate B): condensed neutral metadata, condensed tabular times on their unchanged
  // line box, and the artwork mounted with a soft shadow. Type and paint only; the 120px panel and every box keep their
  // geometry. None of these match the full player (.fullscreen)
  ['#streamContainer:not(.fullscreen) .title-author-texts .title-text', MINI_TITLE_TYPE],
  ['#streamContainer:not(.fullscreen) .title-author-texts .author-text', MINI_AUTHOR_TYPE],
  ['#streamContainer:not(.fullscreen) #playerTrack p.font-mono', MINI_TIME_TYPE],
  ['#streamContainer:not(.fullscreen) .cover-wrapper', MINI_ARTWORK_MOUNT],

  // Bookshelf view: the wood material becomes graphite/blue-gray equipment (same boxes, labels and layout)
  ['.bookshelfRow', { 'background-image': 'linear-gradient(180deg, rgb(var(--color-primary)) 0%, rgb(var(--color-bg)) 100%)' }],
  ['.bookshelfDivider', { 'background-color': 'rgb(var(--color-secondary))', 'background-image': STEEL_SHEEN, 'box-shadow': `${RAISED_BEVEL}, 2px 10px 8px rgb(0 0 0 / 0.5)` }],
  ['.shinyBlack', { 'background-color': 'rgb(var(--color-recessed))', 'background-image': 'none', 'border-color': 'rgb(var(--color-border))', color: 'rgb(var(--color-fg))' }],
  ['.altBookshelfLabel', { 'background-color': 'rgb(var(--color-recessed))', 'background-image': 'none', 'border-color': 'rgb(var(--color-border))', color: 'rgb(var(--color-fg))' }],

  // --- Browsing and detail surfaces (Gate D) ---
  // Content stays dominant: artwork is mounted, information sits in recessed displays, tappable section
  // headers are raised strips, and only real controls become keys. Card artwork for every entity type uses
  // the card-artwork hook above (series/collection/playlist covers, author portraits, group-table row covers)
  // on its artwork box only, never a card root or row.
  // Home sections: an engraved seam closes each shelf (drawn under the shelf content, so the standard
  // shelf's own divider covers it and it only shows in the alternative view)
  ['.shelf-section', { 'box-shadow': ENGRAVED_SEPARATOR }],
  // Bookshelf toolbar: a chassis strip under the navigation bar, seamed off from the content below
  ['.browse-toolbar', { 'background-image': CHASSIS_SHEEN, 'box-shadow': ENGRAVED_SEPARATOR }],
  // Active-filter indicator: an active state, so the accent (success green only means completion)
  ['.browse-toolbar .filter-indicator', { 'background-color': 'rgb(var(--color-accent))', 'border-color': 'rgb(var(--color-edge-dark))' }],
  // Library selector (app bar): a physical key; pressed inverts it
  ['.library-selector', KEY_CAP],
  ['.library-selector:active', KEY_CAP_PRESSED],
  // Browsing labels and the list-row play key (Phase 8B, Candidate B). Section labels are condensed and neutral; the shelf
  // heading band paints an engraved rule clear of the artwork; the toolbar count is condensed with tabular figures; the
  // list-row play button wears the mini-player's key face and amber legend. Type and paint only: cards, titles, authors,
  // navigation labels and the library selector are deliberately unchanged
  ['.shelf-heading', SECTION_LABEL_TYPE],
  ['.shelf-heading-band', SHELF_HEADING_RULE],
  ['.search-section-label', SECTION_LABEL_TYPE],
  ['.toolbar-count', COUNT_TYPE],
  ['.list-play-key', LIST_PLAY_KEY],
  ['.list-play-key > .material-symbols', LIST_PLAY_LEGEND],
  // Library list: recessed well, seamed rows; the current library is a selected key (pressed, accent ring) on
  // a lit row, and its marker uses the accent instead of warning orange (selection is not a warning)
  ['.modal .library-option-panel ul[role=listbox]', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': RECESSED_WELL }],
  ['.modal .library-option-panel li[role=option]:not(:last-child)', { 'box-shadow': ENGRAVED_SEPARATOR }],
  ['.modal .library-option-panel li[role=option].option-selected', { 'background-color': 'rgb(var(--color-bg))', ...SELECTED_KEY }],
  ['.modal .library-option-panel .option-marker', { 'background-color': 'rgb(var(--color-accent))' }],
  // Detail artwork (item, collection and playlist pages): the same mounted frame as card artwork
  ['.detail-artwork', { 'border-radius': RADIUS.frame, 'box-shadow': `${ARTWORK_FRAME}, ${ARTWORK_DROP}` }],
  // Item progress: a recessed information display (text and values unchanged)
  ['.detail-progress', { 'background-color': 'rgb(var(--color-recessed))', 'border-radius': RADIUS.key, 'box-shadow': RECESSED_WELL }],
  // Tappable section headers (chapters/tracks/ebook files, collection/playlist items): raised chassis strips
  ['.section-bar', { 'background-image': CHASSIS_SHEEN, 'box-shadow': RAISED_BEVEL }],
  // Their count badge and the total-duration value are small recessed readouts
  ['.section-bar .section-count', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': RECESSED_WELL, color: 'rgb(var(--color-accent))' }],
  ['.section-bar .section-readout', { color: 'rgb(var(--color-accent))' }],
  // Detail tables (assets/app.css tracksTable): a recessed display with a dark zebra instead of bright strips
  ['.tracksTable tr', { 'background-color': 'rgb(var(--color-recessed))' }],
  ['.tracksTable tr:nth-child(even)', { 'background-color': 'rgb(var(--color-primary))' }],
  // Group detail items (collection/playlist): a recessed well; collection rows are seamed display entries
  ['.group-items', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': RECESSED_WELL }],
  ['.group-items .group-row:not(:last-child)', { 'box-shadow': ENGRAVED_SEPARATOR }],
  // Row play buttons are real controls: equipment keys (glyph color and size unchanged)
  ['.row-play-btn', KEY_CAP],
  ['.row-play-btn:active', KEY_CAP_PRESSED],

  // --- Forms, shared dialogs and interactive controls (Gate E) ---
  // Segmented toggles (ui/ToggleBtns, existing toggle-btn / selected hooks): raised steel segments; the
  // selected choice is a selected key (pressed in, accent ring) on its existing lit fill, so it reads by shape
  // and not color alone; pressing any segment inverts its bevel. Segment shapes and borders are unchanged
  ['.toggle-btn', { 'background-image': STEEL_SHEEN, 'box-shadow': RAISED_BEVEL }],
  ['.toggle-btn.selected', SELECTED_KEY],
  ['.toggle-btn:active', KEY_CAP_PRESSED],
  // Unselected segment labels keep a readability margin on the steel face (the component dims them to fg/0.5,
  // 3.3:1 under the sheen); selection still reads by the pressed bevel, accent ring and lit fill
  ['.toggle-btn:not(.selected)', { color: 'rgb(var(--color-fg) / 0.78)' }],
  // Range input (ui/RangeInput, range-input hook): a recessed slot like the seek channels, with a steel thumb
  // rather than the playback amber (a setting is not playback progress); focus keeps its outline, in the accent
  ['.range-input input[type=range]::-webkit-slider-runnable-track', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': INSET_SLOT }],
  ['.range-input input[type=range]::-webkit-slider-thumb', { 'background-color': 'rgb(var(--color-edge-light))', 'background-image': STEEL_SHEEN, 'box-shadow': THUMB_EDGE }],
  ['.range-input input[type=range]:focus::-webkit-slider-thumb', { 'border-color': 'rgb(var(--color-edge-dark))', 'outline-color': 'rgb(var(--color-accent))' }],
  // Checkbox (ui/Checkbox): an empty recessed well; checked = lit, pressed in, accent edge and accent check
  // (checked is not success); disabled = flat, dark edge and muted check, so it never reads as active.
  // The native input stays (invisible, as before); its keyboard focus now shows on the box
  ['.checkbox-box', { 'background-color': 'rgb(var(--color-recessed))', 'border-color': 'rgb(var(--color-border))', 'box-shadow': RECESSED_WELL }],
  ['.checkbox-box.checkbox-checked', { 'background-color': 'rgb(var(--color-bg))', 'border-color': 'rgb(var(--color-accent))', 'box-shadow': PRESSED_BEVEL }],
  ['.checkbox-box .checkbox-mark', { color: 'rgb(var(--color-accent))' }],
  ['.checkbox-box.checkbox-disabled', { 'background-color': 'rgb(var(--color-primary))', 'border-color': 'rgb(var(--color-edge-dark))', 'box-shadow': 'none' }],
  ['.checkbox-box.checkbox-disabled .checkbox-mark', { color: 'rgb(var(--color-fg-muted))' }],
  ['.checkbox-box:has(input:focus-visible)', { outline: '2px solid rgb(var(--color-accent))', 'outline-offset': '2px' }],
  // Dropdown (ui/Dropdown): the trigger is a key (a disabled trigger stays flat); its open list is a recessed
  // module floating above the page with seamed options (it has no selected state of its own)
  ['.dropdown-button:not(:disabled)', KEY_CAP],
  ['.dropdown-button:not(:disabled):active', KEY_CAP_PRESSED],
  ['.dropdown-menu', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': `${RECESSED_WELL}, ${ELEVATION.overlay}` }],
  ['.dropdown-menu li:not(:last-child)', { 'box-shadow': ENGRAVED_SEPARATOR }],
  // Shared Dialog (menus and option lists everywhere): the chassis panel (dialog rule above) holds a recessed
  // list with seamed rows; a selected option is a selected key on a lit row instead of a success-green wash.
  // Panel radius, size, position, animation and dismissal are unchanged
  ['.modal .dialog-panel ul[role=listbox]', { 'background-color': 'rgb(var(--color-recessed))', 'border-radius': RADIUS.key, 'box-shadow': RECESSED_WELL }],
  ['.modal .dialog-panel ul[role=listbox] > li:not(:last-child)', { 'box-shadow': ENGRAVED_SEPARATOR }],
  ['.modal .dialog-panel ul[role=listbox] > li.option-selected', { 'background-color': 'rgb(var(--color-bg))', ...SELECTED_KEY }],
  // Playlists modal row: membership is a state, so its marker uses the accent (the row's Add/Remove buttons
  // and the cover's card-artwork frame come from the existing rules)
  ['.membership-marker', { 'background-color': 'rgb(var(--color-accent))' }],

  // Toggle switch (ui/ToggleSwitch): recessed slot with a steel thumb; state still reads from thumb position
  ['.w-10.rounded-full.border-gray-400', { 'border-color': 'rgb(var(--color-border))', 'box-shadow': 'inset 1px 1px 0 rgb(var(--color-edge-dark)), inset 0 1px 3px rgb(0 0 0 / 0.5)' }],
  ['.w-10.rounded-full.border-gray-400.bg-primary', { 'background-color': 'rgb(var(--color-recessed))' }],
  ['.w-10.rounded-full.border-gray-400 > span.bg-white', { 'background-color': 'rgb(var(--color-edge-light))', 'background-image': STEEL_SHEEN, 'border-color': 'rgb(var(--color-edge-dark))' }],
  // Disabled thumb (bg-gray-300) must read dimmer than the enabled steel thumb, not brighter
  ['.w-10.rounded-full.border-gray-400 > span.bg-gray-300', { 'background-color': 'rgb(var(--color-bg-hover))', 'border-color': 'rgb(var(--color-edge-dark))' }],

  // Unfinished playback progress on book/series/list cards, playlist rows and the item cover uses the amber
  // played-progress token; finished (bg-success) and other yellow uses (badges, chapter marker) are untouched
  ['.absolute.bottom-0.left-0.z-10.bg-yellow-400', { 'background-color': 'rgb(var(--color-track-cursor))' }],

  // Contrast safety margin for the two tightest measured roles (LLAMA only, existing values, no geometry):
  // small uppercase muted section headers and inactive navigation icons use primary text at reduced alpha
  ['#content p.uppercase.text-fg-muted', { color: 'rgb(var(--color-fg) / 0.78)' }],
  ['#bookshelf-navbar a.text-fg-muted', { color: 'rgb(var(--color-fg) / 0.8)' }],

  // --- Faithful pass (2026-10-10): the approved mockup's material language across the app ---
  // Paint and type only, layered over the rules above (a repeated selector merges into its earlier entry, later values
  // win). Every box keeps its geometry; the seek thumb's decorative inner element is the one exception (see SEEK_THUMB).
  // Graphite room: content pages sit on the mockup's near-black graphite with faint horizontal scan lines. Only the page
  // wrapper (layouts/default.vue); the player container also carries layout-wrapper but never bg-bg
  ['.layout-wrapper.bg-bg', { 'background-color': 'rgb(var(--color-page))', 'background-image': SCANLINES }],
  // App bar: a brushed steel module plate with corner screws; its glyphs are lit amber legends, and the library selector
  // is a glass display with the library name in phosphor green (the mockup's top bar)
  ['#appbar', { 'background-color': 'rgb(var(--color-secondary))', 'background-image': `${RIVETS}, ${BRUSHED}, ${CHASSIS_SHEEN}`, 'box-shadow': `${STEEL_PLATE_EDGE}, 0 1px 0 rgb(0 0 0 / 0.8)` }],
  ['#appbar .material-symbols', PLAYBACK_LEGEND],
  ['#appbar .library-selector', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': `${RECESSED_WELL}, 0 0 0 1px rgb(0 0 0 / 0.8), 0 1px 0 1px rgb(var(--color-edge-light) / 0.3)`, ...LCD_TEXT }],
  ['#appbar .library-selector p', { 'font-family': CONDENSED_FACE, 'letter-spacing': '0.02em' }],
  // Navigation: each tab is a steel key on the strip; the selected tab is a lit glass key with a green ring and green
  // legend (the mockup's Home key), so the state reads by shape (glass vs steel) as well as color
  ['#bookshelf-navbar', { 'background-color': 'rgb(var(--color-page))', 'background-image': 'none', 'box-shadow': 'inset 0 -1px 0 rgb(0 0 0 / 0.8)' }],
  ['#bookshelf-navbar a', NAV_KEY],
  ['#bookshelf-navbar a.text-fg-muted', { color: 'rgb(var(--color-fg) / 0.92)' }],
  ['#bookshelf-navbar a.bg-primary', NAV_KEY_SELECTED],
  ['#bookshelf-navbar a p', { 'font-family': CONDENSED_FACE, 'letter-spacing': '0.03em' }],
  // Headings and counts are lit readouts: shelf headings and search groups in phosphor green, the toolbar count too
  ['.shelf-heading', LCD_TEXT],
  ['.search-section-label', LCD_TEXT],
  ['.toolbar-count', LCD_TEXT],
  // Section headers (settings groups and other small uppercase labels): lit amber caps
  ['#content p.uppercase.text-fg-muted', LIT_LABEL],
  // Dialogs: black recessed panels in a steel frame (final consistency pass; they were brushed steel plates); their
  // titles are green displays and their close key glyph amber
  ['.modal .rounded-lg.bg-primary', DIALOG_PANEL],
  ['.modal .z-40 > p.text-white.truncate', { ...LCD_TEXT, 'font-family': CONDENSED_FACE, 'letter-spacing': '0.02em' }],
  ['.modal .top-11.right-4 .material-symbols', PLAYBACK_LEGEND],
  // Up Next: a glass list inside a steel frame; the section labels are amber strips and the now-playing title is green
  ['.modal .queue-panel.rounded-lg.bg-primary', { 'background-color': 'rgb(var(--color-recessed))', 'background-image': 'none', 'box-shadow': `${RECESSED_WELL}, ${STEEL_FRAME_OUTSIDE}, ${ELEVATION.overlay}` }],
  ['.modal .queue-panel p.uppercase', LIT_LABEL],
  // The now-playing entry is the lit glass display of the mockup (it keeps the amber current marker), not a raised strip
  ['.modal .queue-panel .queue-current', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': `${CURRENT_MARKER}, inset 0 -1px 0 rgb(var(--color-edge-light) / 0.18), inset 0 -2px 0 rgb(0 0 0 / 0.8)` }],
  ['.modal .queue-panel .queue-current p.text-sm', LCD_TEXT],
  // Chapters: the same glass list in a steel frame; chapter times in the condensed readout face with tabular figures
  ['.modal .chapters-panel.bg-secondary', { 'box-shadow': `${RECESSED_WELL}, ${STEEL_FRAME_OUTSIDE}, ${ELEVATION.overlay}` }],
  ['.modal .chapters-panel .font-mono', { 'font-family': CONDENSED_FACE, 'font-variant-numeric': 'tabular-nums', 'letter-spacing': '0.02em' }],
  // Speed and sleep option lists: values in the condensed readout face
  ['.modal .playback-option-panel li[role=option]', { 'font-family': CONDENSED_FACE, 'letter-spacing': '0.02em' }],
  // Full player: the metadata readout is a green display (chapter title bright, author a step dimmer, as in the mockup),
  // and every readout glows
  ['.fullscreen .title-author-texts .title-text', LCD_TEXT],
  ['.fullscreen .title-author-texts .author-text', { color: 'rgb(var(--color-accent) / 0.82)', ...LCD_GLOW }],
  ['#playerTrack p.font-mono', LCD_GLOW],
  ['.total-track p.font-mono', LCD_GLOW],
  ['#playerContent .speed-readout', LCD_GLOW],
  ['#playerContent .sleep-readout', LCD_GLOW],
  ['#streamContainer p.tracking-widest', LCD_TEXT],
  // Seek thumb: the mockup's steel slider block instead of the amber dot. The channel stops clipping it, so the block
  // stands above and below the rail; its touch target is the separate hit element, unchanged
  ['#playerTrack div.relative.rounded-full', { overflow: 'visible' }],
  ['#playerTrack .pointer-events-auto > .bg-track-cursor', SEEK_THUMB],
  // Collapsed player: a steel plate (its overlay token is the steel gradient) with a lit top edge
  ['#streamContainer:not(.fullscreen) #playerContent', { 'box-shadow': `${STEEL_PLATE_EDGE}, 0 -1px 0 rgb(0 0 0 / 0.8)` }],
  // Material polish: the steel gradient is an inline background on the plate's overlay layer, which covers the container's
  // inset edges; the same plate edge is drawn on that layer (box-shadow only: the inline background stays)
  ['#streamContainer:not(.fullscreen) #playerContent > .absolute.h-full.pointer-events-none', { 'box-shadow': STEEL_PLATE_EDGE }],
  // Side drawer: a graphite panel with a steel edge; the current page is a glass entry with the amber marker
  ['.layout-wrapper .w-64.bg-bg', { 'background-color': 'rgb(var(--color-page))', 'background-image': SCANLINES, 'box-shadow': `inset 3px 0 0 rgb(var(--color-secondary)), inset 4px 0 0 rgb(var(--color-edge-light) / 0.5), -2px 0 8px rgb(0 0 0 / 0.6)` }],
  ['.layout-wrapper .w-64 a.text-fg:not(.text-fg-muted)', { 'background-color': 'rgb(var(--color-recessed))', 'box-shadow': `inset 3px 0 0 rgb(var(--color-legend)), ${PRESSED_BEVEL}`, ...LCD_TEXT }],

  // --- Final consistency pass (2026-10-10): dialogs and primary actions ---
  // Dialog content on the black panel: the bookmark editor's heading and time are green readouts, its field label a
  // restrained amber; the custom sleep/rewind time is a green readout
  ['.modal .rounded-lg.bg-primary > .p-4 > .flex.mb-4 > p', LCD_TEXT],
  ['.modal .rounded-lg.bg-primary > .p-4 p.font-semibold', { color: 'rgb(var(--color-legend))' }],
  ['.modal .rounded-lg.bg-primary > .p-4 p.font-mono.text-2xl', LCD_TEXT],
  // Primary actions are steel keys with amber legends (see ACTION_KEY); the bookmark list's Create Bookmark bar too
  ['.btn.bg-success-action:not(:disabled)', ACTION_KEY],
  ['.btn.bg-success-action.text-sm:not(:disabled)', ACTION_KEY_SMALL],
  ['.btn.bg-success-action:not(:disabled):active', ACTION_KEY_PRESSED],
  ['.btn.bg-success-action.text-sm:not(:disabled):active', ACTION_KEY_PRESSED_SMALL],
  ['.btn.bg-success-action:disabled', ACTION_KEY_UNAVAILABLE],
  ['.modal .rounded-lg.bg-primary > .bg-success-action', { ...ACTION_KEY, ...ACTION_KEY_SMALL }],
  ['.modal .rounded-lg.bg-primary > .bg-success-action:active', { ...ACTION_KEY_PRESSED, ...ACTION_KEY_PRESSED_SMALL }],
  // App-owned full-screen sheets with the 96px header (Add to Playlist, RSS feed) sit on the graphite room like the pages,
  // with a green heading; the e-reader's table of contents (an 80px header, its own reader theme) is not matched
  ['.modal-bg > .relative.text-fg.bg-bg:has(> .h-24)', { 'background-color': 'rgb(var(--color-page))', 'background-image': SCANLINES }],
  ['.modal-bg > .bg-bg > .h-24 > h1.text-lg', LCD_TEXT],

  // --- Finishing corrections (2026-10-10) ---
  // Browsing Play controls are ordinary actions: amber legends like the list-row play key (series toolbar Play, the
  // collection and playlist row play buttons, podcast episode play pills). Selection, focus, readouts and activity stay
  // green. Color only: faces, labels, geometry and hit areas unchanged
  ['.browse-toolbar .series-play-action', PLAYBACK_LEGEND],
  ['.row-play-btn > .material-symbols', PLAYBACK_LEGEND],
  ['.episode-play-control > .material-symbols', PLAYBACK_LEGEND],
  // The downloaded book page's Play (pages/localMedia/item): the same ordinary action
  ['.local-play-action', PLAYBACK_LEGEND],
  // Home shelves scroll sideways, so their scroller clips the outer frame's top edge: there the frame is painted inside the
  // cover's own bounds, on the card's decorative card-frame element (above the art, below badges and progress), and the
  // cover keeps only its short drop. Paint only: the element, its box and stacking come from the card markup
  ['.shelf-section .card-artwork', { 'box-shadow': ARTWORK_DROP }],
  ['.shelf-section .card-frame', { 'box-shadow': ARTWORK_FRAME_INSET }],

  // Visible focus for keyboard/switch access
  [':focus-visible', { outline: '2px solid rgb(var(--color-accent))', 'outline-offset': '2px' }]
]

// --- E-ink finish ---
// The equipment recipe's opposite: e-paper dithers soft shadows, gradients and translucent fills into gray noise and
// ghosts them on every refresh, so this recipe removes them and separates surfaces with 1px ink lines instead. Paint
// only: lines are hard, unblurred box-shadows (inside the box or a ring around it), so no size or position changes.
const INK = 'rgb(var(--color-fg))'
const PAPER = 'rgb(var(--color-bg))'
const MUTED = 'rgb(var(--color-fg-muted))'
const EINK_FRAME = `0 0 0 1px ${INK}`
const EINK_FRAME_INSIDE = `inset 0 0 0 1px ${INK}`
const EINK_RULE_BELOW = `inset 0 -1px 0 ${INK}`
const EINK_RULE_ABOVE = `inset 0 1px 0 ${INK}`
const NO_SHADOW = Object.freeze({ 'box-shadow': 'none' })
const PAPER_LABEL = Object.freeze({ 'background-color': PAPER, 'background-image': 'none', 'border-color': INK, color: INK })

// Order matters where two rules share an element at equal specificity: shadow removal first, frames and lines after it
const EINK_RULES = [
  // Drop shadows (app.css utilities and Tailwind's)
  ['.box-shadow-book', NO_SHADOW],
  ['.box-shadow-md', NO_SHADOW],
  ['.box-shadow-progressbar', NO_SHADOW],
  ['.shadow', NO_SHADOW],
  ['.shadow-sm', NO_SHADOW],
  ['.shadow-md', NO_SHADOW],
  ['.shadow-lg', NO_SHADOW],

  // Gradients and photo textures: the modal's top fade, the card play corner, the wooden shelf and its placards
  ['.modal > .bg-gradient-to-b', { 'background-image': 'none' }],
  ['.bg-gradient-to-r.to-black', { 'background-image': 'none', 'background-color': INK }],
  ['.bookshelfRow', { 'background-image': 'none' }],
  ['.bookshelfDivider', { 'background-color': INK, 'background-image': 'none', 'box-shadow': 'none' }],
  ['.shinyBlack', PAPER_LABEL],
  ['.altBookshelfLabel', PAPER_LABEL],

  // Chrome: lines under the app bar and the bookshelf navigation; the current tab is inverted (it also keeps its label)
  ['#appbar', { 'box-shadow': EINK_RULE_BELOW }],
  ['#bookshelf-navbar', { 'box-shadow': EINK_RULE_BELOW }],
  ['#bookshelf-navbar a.bg-primary', { 'background-color': INK, color: PAPER }],
  ['.library-selector', { 'background-color': PAPER, 'box-shadow': EINK_FRAME_INSIDE }],

  // Modals: an opaque paper backdrop instead of a translucent one, so titles and the close glyph (white over the old
  // dark fade) become ink, and panels are framed
  ['.modal.modal-bg', { 'background-color': 'rgb(var(--color-primary))' }],
  ['.modal > .z-40.text-white', { color: INK }],
  ['.modal > .z-40 .text-white', { color: INK }],
  ['.modal .rounded-lg.bg-primary', { 'box-shadow': EINK_FRAME }],
  ['.modal .chapters-panel.bg-secondary', { 'box-shadow': EINK_FRAME }],
  ['.dialog-panel', { 'border-color': INK }],
  // Selected option: a solid gray row instead of a translucent success wash
  ['.option-selected', { 'background-color': 'rgb(var(--color-bg-hover))' }],

  // Side drawer: no dimming backdrop; a line on the drawer's page edge
  ['.layout-wrapper > .bg-black.bg-opacity-60', { 'background-color': 'transparent' }],
  ['.layout-wrapper .w-64.bg-bg', { 'box-shadow': `inset 1px 0 0 ${INK}` }],

  // Toggle switch: ink outline and thumb edge (on is the ink success fill, off is paper)
  ['.w-10.rounded-full.border-gray-400', { 'border-color': INK }],
  ['.w-10.rounded-full.border-gray-400 > span.bg-white', { 'border-color': INK }],
  ['.w-10.rounded-full.border-gray-400 > span.bg-gray-300', { 'background-color': 'rgb(var(--color-bg-hover))', 'border-color': INK }],

  // Artwork keeps its colors inside a 1px ink frame
  ['.card-artwork', { 'box-shadow': EINK_FRAME }],
  ['.cover-wrapper', { 'box-shadow': EINK_FRAME }],
  // Playback progress on covers: solid ink (finished bars are the ink success color), set off from the art by a paper line
  ['.absolute.bottom-0.left-0.z-10.bg-yellow-400', { 'background-color': 'rgb(var(--color-track-cursor))', 'box-shadow': `0 -1px 0 ${PAPER}` }],
  ['.absolute.bottom-0.left-0.z-10.bg-success', { 'box-shadow': `0 -1px 0 ${PAPER}` }],
  ['.modal .chapters-panel li > .bg-yellow-400', { 'background-color': 'rgb(var(--color-track-cursor))' }],
  // List-row play button: paper with an ink ring instead of translucent gray, so its white glyph becomes ink
  ['.list-play-key', { 'background-color': PAPER, 'box-shadow': EINK_FRAME_INSIDE }],
  ['.list-play-key > .material-symbols', { color: INK }],
  ['.list-play-key > div.rounded-full', { 'background-color': PAPER }],

  // Player: seek channels are solid gray in an ink outline; the played line is ink in every seek state (settled and
  // pending), and the thumb keeps a paper ring so it reads over the played line
  ['#playerTrack div.relative.rounded-full', { 'background-color': 'rgb(var(--color-track))', 'box-shadow': EINK_FRAME_INSIDE }],
  ['.total-track div.relative.rounded-full', { 'background-color': 'rgb(var(--color-track))', 'box-shadow': EINK_FRAME_INSIDE }],
  ['#playerTrack .bg-track-cursor.bg-gray-200', { 'background-color': 'rgb(var(--color-track-cursor))' }],
  ['#playerTrack .bg-track-cursor.bg-yellow-300', { 'background-color': 'rgb(var(--color-track-cursor))' }],
  ['#playerTrack .pointer-events-auto > .bg-track-cursor', { 'box-shadow': `0 0 0 1px ${PAPER}` }],
  // Mini-player: a line above it instead of its upward shadow (the full player keeps its own none)
  ['#streamContainer:not(.fullscreen) #playerContent', { 'box-shadow': EINK_RULE_ABOVE }],
  // Play/Pause: a paper key with an ink ring and ink glyph (its theme fill is paper, and its glyph would be paper too)
  ['.play-btn', { color: INK, 'box-shadow': `inset 0 0 0 2px ${INK}` }],

  // Visibility on paper: foregrounds hardcoded for the dark themes (white, light grays, yellow and blue accents) would
  // vanish on paper, so they become ink or the secondary gray. Where they sit on a dark box, they stay paper
  ['.text-gray-50', { color: INK }],
  ['.text-gray-200', { color: MUTED }],
  ['.text-gray-300', { color: MUTED }],
  ['.text-gray-400', { color: MUTED }],
  ['.bg-black .text-gray-200', { color: PAPER }],
  ['.bg-gradient-to-r.to-black .text-gray-200', { color: PAPER }],
  ['.text-yellow-300', { color: INK }],
  ['.text-yellow-400', { color: INK }],
  // Timestamp links in episode descriptions: ink, so they keep an underline to read as links
  ['.text-blue-400', { color: INK, 'text-decoration-line': 'underline' }],
  ['.border-white', { 'border-color': INK }],
  // Read-status ("mark as finished") key: its outline glyph is white
  ['.icon-btn > div.text-white', { color: INK }],
  // A finished episode's play control: dimmed white (or 40% ink, under 3:1) becomes the secondary gray
  ['.episode-play-control.text-white', { color: MUTED }],
  ['.episode-play-control.text-opacity-40', { color: MUTED }],
  // Chip actions shown over a paper chip
  ['.bg-opacity-75 > .material-symbols.text-white', { color: INK }],
  ['.row-play-btn', { 'border-color': INK }],
  // Episode number, season and type chips: half-transparent paper on paper, so they get an outline
  ['.rounded-full.bg-primary.bg-opacity-50', { 'box-shadow': EINK_FRAME_INSIDE }],
  // Text fields without a border (search) would be invisible on paper
  ['input.outline-none:not(.border)', { 'box-shadow': EINK_FRAME_INSIDE }],
  ['.checkbox-box', { 'border-color': INK }],
  ['.checkbox-box .checkbox-mark', { color: INK }],
  // Ink fills whose label inherits ink: buttons and icon keys in the state colors get a paper label
  ['.btn.bg-error', { color: PAPER }],
  ['.btn.bg-info', { color: PAPER }],
  ['.btn.bg-warning', { color: PAPER }],
  // ui/Btn's loading spinner is ink (text-fg) over the button: on the ink fills it would vanish
  ['.btn.bg-success-action > div.text-fg', { color: PAPER }],
  ['.btn.bg-error > div.text-fg', { color: PAPER }],
  ['.btn.bg-info > div.text-fg', { color: PAPER }],
  ['.btn.bg-warning > div.text-fg', { color: PAPER }],
  ['.icon-btn.bg-error > .material-symbols', { color: PAPER }],
  ['.bg-black\\/80.text-fg', { color: PAPER }],
  ['.bg-black\\/75 svg.animate-spin', { color: PAPER }],
  // Unavailable player keys: 10% ink is nearly blank on e-paper; the light gray still shows the key, clearly dimmed
  ['#playerContent .key-disabled', { color: 'rgb(var(--color-bg-hover))' }]
]

const rootedRules = (theme, entries, base = {}) => {
  const root = engine.themeSelector(theme.id)
  const rules = Object.keys(base).length ? { [root]: base } : {}
  for (const [suffix, declarations] of entries) {
    const selector = suffix ? `${root} ${suffix}` : root
    rules[selector] = { ...(rules[selector] || {}), ...declarations }
  }
  return rules
}

const RECIPES = {
  standard: () => ({}),
  equipment: (theme) => rootedRules(theme, EQUIPMENT_RULES, equipmentDerivedDeclarations(theme.tokens)),
  eink: (theme) => rootedRules(theme, EINK_RULES)
}

/** Fixed presentation rules for the given validated themes ({ selector: declarations }). */
function presentationRules(themes) {
  const rules = {}
  for (const theme of themes) {
    const recipe = RECIPES[theme.tokens['presentation.finish']]
    if (!recipe) continue // unreachable for validated themes; never guess
    for (const [selector, declarations] of Object.entries(recipe(theme))) {
      rules[selector] = { ...(rules[selector] || {}), ...declarations }
    }
  }
  return rules
}

const builtinPresentationRules = () => presentationRules(engine.THEMES)

// Equipment rules that apply only under a fixed media query: [query, selector suffix, declarations]. Kept apart from
// EQUIPMENT_RULES so every unconditional rule stays a flat selector map; the entries are the readout's short-screen and
// landscape variants with the total-track display
const EQUIPMENT_MEDIA_RULES = [
  [METADATA_READOUT_COMPACT_QUERY, '.fullscreen .total-track ~ .title-author-texts', METADATA_READOUT_COMPACT],
  [METADATA_READOUT_LANDSCAPE_QUERY, '.fullscreen .total-track ~ .title-author-texts', METADATA_READOUT_LANDSCAPE]
]

/** Fixed media-conditional presentation rules for the given validated themes ({ query: { selector: declarations } }). */
function presentationMediaRules(themes) {
  const rules = {}
  for (const theme of themes) {
    if (theme.tokens['presentation.finish'] !== 'equipment') continue
    const root = engine.themeSelector(theme.id)
    for (const [query, suffix, declarations] of EQUIPMENT_MEDIA_RULES) {
      const media = `@media ${query}`
      rules[media] = { ...(rules[media] || {}), [`${root} ${suffix}`]: { ...declarations } }
    }
  }
  return rules
}

const builtinPresentationMediaRules = () => presentationMediaRules(engine.THEMES)

module.exports = { presentationRules, builtinPresentationRules, presentationMediaRules, builtinPresentationMediaRules, equipmentDerivedDeclarations, EQUIPMENT_RULES, EQUIPMENT_MEDIA_RULES, EINK_RULES, PRIMITIVES }
