// Phase 6B: the full player's layout projection (theme/playerLayout.js), the LLAMA "B+" faceplate.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const presets = require('../theme/presets.js')
const layout = require('../theme/playerLayout.js')
const read = (p) => readFile(new URL(p, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

const llama = () => engine.getTheme('llama')
const LEGACY_THEMES = ['dark', 'black', 'light']
// Viewports from the acceptance matrix (CSS px) and a few beyond it
const PORTRAIT = [
  [412, 842],
  [412, 799],
  [412, 734],
  [412, 933],
  [762, 986],
  [360, 640],
  [393, 852],
  [430, 932],
  [800, 1280]
]
const LANDSCAPE = [
  [915, 364],
  [842, 412],
  [1280, 800]
]
const ASPECTS = [1, 1.6]

// The previous AudioPlayer.vue fullscreenBookCoverWidth, copied verbatim, as the independent reference
function previousCoverWidth(windowWidth, windowHeight, bookCoverAspectRatio) {
  if (windowWidth < windowHeight) {
    let sideSpace = 20
    if (bookCoverAspectRatio === 1.6) sideSpace += (windowWidth - sideSpace) * 0.375
    const availableHeight = windowHeight - 400
    let width = windowWidth - sideSpace
    const totalHeight = width * bookCoverAspectRatio
    if (totalHeight > availableHeight) {
      width = availableHeight / bookCoverAspectRatio
    }
    return width
  } else {
    const heightScale = (windowHeight - 200) / 651
    if (bookCoverAspectRatio === 1) {
      return 260 * heightScale
    }
    return 190 * heightScale
  }
}

const at = (theme, [width, height], aspectRatio = 1, twoRail = false) => layout.fullscreenLayout(theme, { width, height, aspectRatio, twoRail })

test('Legacy themes: the projection is a strict no-op (previous artwork width, no classes, no variables, no plates)', () => {
  for (const id of LEGACY_THEMES) {
    for (const vp of [...PORTRAIT, ...LANDSCAPE]) {
      for (const ar of ASPECTS) {
        for (const twoRail of [false, true]) {
          const r = at(engine.getTheme(id), vp, ar, twoRail)
          assert.equal(r.path, 'standard')
          assert.equal(r.coverWidth, previousCoverWidth(vp[0], vp[1], ar), `${id} ${vp} ${ar}`)
          assert.deepEqual(r.classes, [])
          assert.deepEqual(r.vars, {})
          assert.deepEqual(r.plates, { top: false, console: false, seek: false })
        }
      }
    }
  }
  // A missing/unknown theme is treated the same way
  assert.equal(layout.fullscreenLayout(null, { width: 412, height: 842, aspectRatio: 1, twoRail: false }).path, 'standard')
})

test('legacyCoverWidth is the previous formula, unchanged', () => {
  for (const vp of [...PORTRAIT, ...LANDSCAPE]) for (const ar of ASPECTS) assert.equal(layout.legacyCoverWidth({ width: vp[0], height: vp[1], aspectRatio: ar }), previousCoverWidth(vp[0], vp[1], ar))
})

test('Normal single-rail LLAMA portrait selects the full faceplate (B+) geometry', () => {
  const r = at(llama(), [412, 842])
  assert.equal(r.path, 'faceplate')
  assert.deepEqual(r.classes, ['faceplate-type', 'faceplate', 'faceplate-top-plate'])
  assert.deepEqual(r.plates, { top: true, console: true, seek: true })
  // The approved prototype at 412x842: 384px artwork from 93px, readout band 76px, deck 247px, 24px times
  assert.equal(r.coverWidth, 384)
  assert.equal(r.vars['--faceplate-cover-top'], '93.33px')
  assert.equal(r.vars['--faceplate-band'], '76px')
  assert.equal(r.vars['--faceplate-deck'], '247px')
  assert.equal(r.vars['--faceplate-console-top'], '86px')
  assert.equal(r.vars['--faceplate-times'], '24px')
  assert.equal(r.vars['--faceplate-plate-height'], '60px')
  for (const value of Object.values(r.vars)) assert.match(value, /^\d+(\.\d+)?px$/)
})

test('Compact tier where the regular budget would make the artwork height-limited; artwork cost stays as approved', () => {
  const expected = [
    // viewport, artwork (approved prototype), previous artwork
    [[412, 799], 378, 392],
    [[412, 734], 313, 334],
    [[762, 986], 565, 586]
  ]
  for (const [vp, art, previous] of expected) {
    const r = at(llama(), vp)
    assert.equal(r.path, 'faceplate', String(vp))
    assert.ok(r.classes.includes('faceplate-compact'), String(vp))
    assert.equal(Math.round(r.coverWidth), art, String(vp))
    assert.equal(r.vars['--faceplate-times'], '22px')
    assert.equal(r.vars['--faceplate-band'], '72px')
    assert.equal(Math.round(previousCoverWidth(vp[0], vp[1], 1)), previous)
    // Approved costs: about 7% (799, wide) and 12% (734) of the artwork's area
    const loss = 1 - (r.coverWidth / previousCoverWidth(vp[0], vp[1], 1)) ** 2
    assert.ok(loss <= 0.13, `${vp}: ${loss}`)
  }
  // Standard and tall stay on the regular tier
  for (const vp of [
    [412, 842],
    [412, 933]
  ])
    assert.ok(!at(llama(), vp).classes.includes('faceplate-compact'), String(vp))
})

test('The tier switch follows the artwork, not a device height: both tiers give the full width at the boundary', () => {
  const { TIERS } = layout
  const b = layout.budget(TIERS.regular)
  const boundary = b.above + b.below + (412 - 20 - layout.FACEPLATE.bayAllowance)
  assert.equal(at(llama(), [412, boundary]).classes.includes('faceplate-compact'), false)
  const below = at(llama(), [412, boundary - 1])
  assert.ok(below.classes.includes('faceplate-compact'))
  // No artwork jump across the switch: the compact budget still affords the full width there
  assert.equal(below.coverWidth, 412 - 20 - layout.FACEPLATE.bayAllowance)
})

test('Tall screens share the extra room between three seams instead of one dead band', () => {
  const r = at(llama(), [412, 933])
  const { TIERS, FACEPLATE } = layout
  const b = layout.budget(TIERS.regular)
  const slack = (933 - b.above - b.below - r.coverWidth) / 3
  assert.ok(slack > 20)
  assert.equal(r.vars['--faceplate-cover-top'], `${Math.round((b.above + slack) * 100) / 100}px`)
  assert.equal(r.vars['--faceplate-readout-bottom'], `${Math.round((TIERS.regular.deck + TIERS.regular.seam + FACEPLATE.readoutPlate + slack) * 100) / 100}px`)
  assert.equal(r.coverWidth, 384)
})

test('Chapter Track + Total Track (two rails) selects the compatibility path: previous artwork geometry, B+ presentation', () => {
  for (const vp of PORTRAIT) {
    for (const ar of ASPECTS) {
      const r = at(llama(), vp, ar, true)
      assert.equal(r.path, 'compat', String(vp))
      assert.equal(r.coverWidth, previousCoverWidth(vp[0], vp[1], ar))
      // The two rails are one dual-track instrument (faceplate-dual), never the flat single-rail deck
      assert.ok(r.classes.includes('faceplate-type') && r.classes.includes('faceplate-dual'))
      assert.ok(!r.classes.includes('faceplate') && !r.classes.includes('faceplate-compact') && !r.classes.includes('faceplate-flat'))
      assert.equal(r.plates.console, true)
      assert.equal(r.plates.seek, true) // the seek plate, extended over the book section, is the instrument's frame
      assert.equal(r.vars['--faceplate-times'], '16px')
      assert.equal(r.vars['--faceplate-cover-top'], undefined)
    }
  }
  // The top plate only where it clears the artwork's frame
  assert.equal(at(llama(), [412, 842], 1, true).plates.top, true)
  assert.equal(at(llama(), [412, 734], 1, true).plates.top, true)
})

test('Landscape keeps the previous two-column geometry with the safe presentation only (no top plate, no bay)', () => {
  for (const vp of LANDSCAPE) {
    for (const twoRail of [false, true]) {
      const r = at(llama(), vp, 1, twoRail)
      assert.equal(r.path, 'landscape')
      // The previous cover width still drives the right column's readout offsets
      assert.equal(r.coverWidth, previousCoverWidth(vp[0], vp[1], 1))
      assert.deepEqual(r.classes, ['faceplate-type', twoRail ? 'faceplate-dual' : 'faceplate-flat', 'faceplate-landscape'])
      assert.deepEqual(r.plates, { top: false, console: true, seek: twoRail })
      assert.equal(r.vars['--faceplate-times'], '16px')
    }
  }
})

// --- Landscape artwork frame (LLAMA): the frame hugs the artwork, bottom-anchored, centered in the left column ---
// S26 Ultra (891x381 at 2.625), S22 Ultra (775x354 at 2.8125), small phones, a tablet, and enlarged display scaling
const LANDSCAPE_ART_VIEWPORTS = [
  [891, 381],
  [775, 354],
  [640, 360],
  [732, 412],
  [915, 364],
  [842, 412],
  [1280, 800],
  [693, 300]
]
// Square, the library's 1.6, tall portrait art, a 2:3 cover, landscape art and a very wide banner (height / width)
const ART_RATIOS = [1, 1.6, 2, 1.5, 0.75, 0.4]
const INSETS = [undefined, { top: 24 }, { bottom: 48 }, { left: 32, right: 32 }, { top: 24, bottom: 16, left: 20 }]

test('Landscape artwork: aspect ratio kept, frame flush with the bottom, below the top chrome band, centered in the left column', () => {
  const { topBand, side, frame } = layout.LANDSCAPE
  for (const [width, height] of LANDSCAPE_ART_VIEWPORTS) {
    for (const imageRatio of ART_RATIOS) {
      for (const insets of INSETS) {
        const id = `${width}x${height} r${imageRatio} ${JSON.stringify(insets)}`
        const ins = { top: 0, right: 0, bottom: 0, left: 0, ...insets }
        const art = layout.landscapeArtwork({ width, height, imageRatio, insets })
        const containerHeight = height - ins.top
        // Natural aspect ratio: no stretch (and the box is the artwork's, so object-fit has nothing to letterbox)
        assert.ok(Math.abs(art.height / art.width - imageRatio) < 1e-9, id)
        // The frame (outside the artwork) touches the bottom of the usable player: no gap, no overflow
        assert.equal(art.bottom - frame, ins.bottom, id)
        // ...and never reaches into the top chrome band (collapse/menu keys at 20-64px, the playback-method label)
        const frameTop = containerHeight - art.bottom - art.height - frame
        assert.ok(frameTop >= topBand - 1e-9, id)
        // Horizontally centered in the left column, the frame inside it with the side margin, never into the controls
        const columnLeft = ins.left
        const columnRight = width / 2
        assert.ok(Math.abs(art.left + art.width / 2 - (columnLeft + columnRight) / 2) < 1e-9, id)
        assert.ok(art.left - frame >= columnLeft + side - 1e-9, id)
        assert.ok(art.left + art.width + frame <= columnRight - side + 1e-9, id)
        // As large as fits: it reaches the top band or the column's side margins (no fixed window around it)
        const heightLimited = Math.abs(frameTop - topBand) < 1e-6
        const widthLimited = Math.abs(art.left - frame - (columnLeft + side)) < 1e-6
        assert.ok(heightLimited || widthLimited, id)
      }
    }
  }
})

test('Landscape artwork: square art gets a square frame; the S26 and S22 sizes; the library ratio until the art loads', () => {
  const { frame } = layout.LANDSCAPE
  // S26 Ultra landscape (891x381): square art 301x301 with its frame from 72px down to the bottom edge
  const s26 = layout.landscapeArtwork({ width: 891, height: 381, imageRatio: 1 })
  assert.equal(s26.width, s26.height)
  assert.equal(s26.height, 381 - 72 - 2 * frame)
  assert.equal(s26.left, (891 / 2 - s26.width) / 2)
  // S22 Ultra landscape (775x354): 274x274
  const s22 = layout.landscapeArtwork({ width: 775, height: 354, imageRatio: 1 })
  assert.deepEqual([s22.width, s22.height, s22.bottom], [274, 274, frame])
  // In the projection: the loaded art's own ratio wins over the library setting; without it, the library ratio
  const r = layout.fullscreenLayout(llama(), { width: 891, height: 381, aspectRatio: 1, twoRail: true, imageRatio: 1.6 })
  assert.equal(r.vars['--landscape-art-height'], '301px')
  assert.equal(r.vars['--landscape-art-width'], `${Math.round((301 / 1.6) * 100) / 100}px`)
  assert.equal(r.vars['--landscape-art-bottom'], `${frame}px`)
  for (const imageRatio of [0, undefined, NaN]) {
    const f = layout.fullscreenLayout(llama(), { width: 891, height: 381, aspectRatio: 1.6, twoRail: false, imageRatio })
    assert.equal(f.vars['--landscape-art-height'], '301px', String(imageRatio))
  }
})

test('Landscape artwork changes nothing else: portrait paths and the other themes ignore the image ratio and insets', () => {
  const extra = { imageRatio: 0.5, insets: { top: 24, bottom: 30, left: 12, right: 12 } }
  for (const vp of PORTRAIT) {
    for (const ar of ASPECTS) {
      for (const twoRail of [false, true]) {
        const base = { width: vp[0], height: vp[1], aspectRatio: ar, twoRail }
        assert.deepEqual(layout.fullscreenLayout(llama(), { ...base, ...extra }), layout.fullscreenLayout(llama(), base), `${vp} ${ar} ${twoRail}`)
      }
    }
  }
  for (const id of LEGACY_THEMES) {
    for (const vp of [...PORTRAIT, ...LANDSCAPE, ...LANDSCAPE_ART_VIEWPORTS]) {
      const r = layout.fullscreenLayout(engine.getTheme(id), { width: vp[0], height: vp[1], aspectRatio: 1, twoRail: true, ...extra })
      assert.equal(r.path, 'standard')
      assert.deepEqual([r.classes, r.vars], [[], {}])
    }
  }
})

test('Landscape artwork: the reserved frame is the frame LLAMA paints, and the stylesheet sizes the box from the projection only', async () => {
  // The fullscreen artwork frame's outermost zero-offset ring equals LANDSCAPE.frame
  const root = engine.themeSelector('llama')
  const rules = presets.presentationRules([llama()])
  const shadow = rules[`${root} .fullscreen .cover-wrapper`]['box-shadow']
  const rings = [...shadow.matchAll(/(?:^|, )(-?\d+)(?:px)? (-?\d+)(?:px)? 0 (\d+)px/g)].filter((m) => m[1] === '0' && m[2] === '0').map((m) => +m[3])
  assert.equal(Math.max(...rings), layout.LANDSCAPE.frame)
  // No faceplate bay overrides the frame in landscape (the bay hooks are portrait-only)
  assert.ok(!layout.fullscreenLayout(llama(), { width: 891, height: 381, aspectRatio: 1, twoRail: true }).classes.some((c) => c === 'faceplate' || c === 'faceplate-compact'))

  const player = await read('../components/app/AudioPlayer.vue')
  const box = player.match(/#streamContainer\.fullscreen\.faceplate-landscape \.cover-wrapper \{([^}]*)\}/)
  assert.ok(box, 'landscape artwork rule')
  for (const decl of ['top: auto', 'left: var(--landscape-art-left) !important', 'bottom: var(--landscape-art-bottom)', 'width: var(--landscape-art-width) !important', 'height: var(--landscape-art-height) !important']) {
    assert.ok(box[1].includes(decl), decl)
  }
  // The cover component fills that box and cannot be held wider by its own min-width (no crop); the image is contained
  const fill = player.match(/#streamContainer\.fullscreen\.faceplate-landscape \.cover-wrapper > div > div \{([^}]*)\}/)
  for (const decl of ['width: 100% !important', 'height: 100% !important', 'min-width: 0 !important']) assert.ok(fill[1].includes(decl), decl)
  assert.match(player, /#streamContainer\.fullscreen\.faceplate-landscape \.cover-wrapper img \{\s*object-fit: contain;/)
  // The player feeds the loaded artwork's ratio and the safe-area insets into the projection, and forgets the ratio
  // only when the artwork changes
  assert.match(player, /imageRatio: this\.coverImageRatio,\s*insets: this\.safeAreaInsets/)
  assert.match(player, /this\.coverImageRatio = img\.naturalHeight \/ img\.naturalWidth/)
  assert.match(player, /coverIdentity\(\) \{\s*this\.coverImageRatio = 0\s*\}/)
  assert.match(player, /this\.safeAreaInsets = this\.readSafeAreaInsets\(\)/)
})

test('Artwork never exceeds its bay and never falls below the floor: past it the compatibility geometry is used', () => {
  for (const vp of PORTRAIT) {
    for (const ar of ASPECTS) {
      const r = at(llama(), vp, ar)
      const previous = previousCoverWidth(vp[0], vp[1], ar)
      if (r.path === 'faceplate') {
        assert.ok(r.coverWidth <= layout.legacyCoverWidth({ width: vp[0], height: 1e9, aspectRatio: ar }) - layout.FACEPLATE.bayAllowance + 1e-9, String(vp))
        assert.ok(r.coverWidth >= previous * layout.MIN_ARTWORK_RATIO, String(vp))
      } else assert.equal(r.coverWidth, previous)
    }
  }
  // A 640px-tall phone cannot afford the faceplate without giving up too much artwork
  assert.equal(at(llama(), [360, 640]).path, 'compat')
})

test('Budgets hold their content at font scale 1.3 (band, seek module above the console)', () => {
  const { TIERS, FACEPLATE } = layout
  const zoom = 1.3
  for (const [name, tier] of Object.entries(TIERS)) {
    // Title (20.8px at most, line-height 1.25) + author (16px, line-height 1.2) + 6px top/bottom padding, zoomed
    const metadata = 20.8 * zoom * 1.25 + 16 * zoom * 1.2 + 12
    assert.ok(metadata <= tier.band, `${name} band ${tier.band} < ${metadata}`)
    // Seek module: deck padding 8 + well top 4 + row padding 5 + times line + 2 + 6px channel + 8 below + 6px plate
    const seek = 8 + 4 + 5 + tier.times * zoom * 1.15 + 2 + 6 + 8 + 6
    assert.ok(seek + 6 <= tier.deck - FACEPLATE.console, `${name}: seek module ${seek} + 6px seam over the console`)
  }
})

test('No-crop guarantee: the cover component, the bay and the CSS all take the layout width; min-width cannot hold it wider', async () => {
  const player = await read('../components/app/AudioPlayer.vue')
  // The component's width prop and the --cover-image-* variables both come from fullscreenBookCoverWidth = layout width
  assert.match(player, /:width="bookCoverWidth"/)
  assert.match(player, /if \(this\.showFullscreen\) return this\.fullscreenBookCoverWidth/)
  assert.match(player, /fullscreenBookCoverWidth\(\) \{\s*return this\.fullscreenLayout\.coverWidth\s*\}/)
  assert.match(player, /setProperty\('--cover-image-width', this\.fullscreenBookCoverWidth \+ 'px'\)/)
  // A theme switch or Chapter Track change re-sizes the cover
  assert.match(player, /fullscreenBookCoverWidth\(\) \{\s*this\.updateScreenSize\(\)\s*\}/)
  // The explicit guard: under the faceplate, the cover component fills the bay and its min-width cannot exceed it
  const guard = player.match(/#streamContainer\.fullscreen\.faceplate \.cover-wrapper > div > div \{([^}]*)\}/)
  assert.ok(guard, 'cover guard rule')
  for (const decl of ['width: 100% !important', 'height: 100% !important', 'min-width: 0 !important', 'max-width: 100% !important']) assert.ok(guard[1].includes(decl), decl)
})

test('Faceplate stylesheet: geometry only behind the hook classes, and never on the controls, seek target or mini-player', async () => {
  const player = await read('../components/app/AudioPlayer.vue')
  const style = player.slice(player.indexOf('<style>'))
  const block = style.slice(style.indexOf('/*\n * Faceplate layout'))
  assert.ok(block.length > 100)
  const rules = [...block.matchAll(/([^{}]+)\{[^}]*\}/g)].map((m) => m[1].replace(/\/\*[\s\S]*?\*\//g, '').trim())
  for (const selector of rules) {
    for (const s of selector.split(',').map((x) => x.trim())) {
      assert.match(s, /^(\.player-plate|\.faceplate-|#streamContainer\.fullscreen\.faceplate(-dual|-landscape)? )/, s)
      // Controls, keys, the seek channel and its touch target are frozen
      assert.doesNotMatch(s, /playerControls|play-btn|player-key|utility|trackCursor|chrome-key|\.relative \.|rounded-full/, s)
    }
  }
  // The seek row keeps its horizontal geometry (px-6): only its top padding changes
  assert.doesNotMatch(block, /#playerTrack \{[^}]*padding-(left|right)/)
  // The mini-player keeps its 120px deck; the faceplate deck height needs the fullscreen hook
  assert.match(style, /\.playerContainer \{\s*height: 120px;/)
  assert.match(block, /#streamContainer\.fullscreen\.faceplate \.playerContainer \{\s*height: var\(--faceplate-deck\);/)
})

test('Plates are decorative: aria-hidden, rendered only from the layout projection, never touch targets', async () => {
  const player = await read('../components/app/AudioPlayer.vue')
  const template = player.slice(0, player.indexOf('</template>'))
  const plates = [...template.matchAll(/<div [^>]*class="player-plate ([^"]+)"[^>]*\/>/g)]
  assert.deepEqual(plates.map((m) => m[1]).sort(), ['player-plate-console', 'player-plate-seek', 'player-plate-top'])
  for (const [tag, cls] of plates) {
    assert.match(tag, /aria-hidden="true"/)
    assert.match(tag, new RegExp(`v-if="faceplateHooks\\.plates\\.${cls.replace('player-plate-', '')}"`))
    assert.doesNotMatch(tag, /@click|@touch/)
  }
  assert.match(player, /\.player-plate \{\s*position: absolute;\s*pointer-events: none;/)
  // The hooks reach #streamContainer only through the projection
  assert.match(template, /id="streamContainer"[^>]*:class="\[\{ fullscreen: showFullscreen[^"]*\}, faceplateHooks\.classes\]" :style="faceplateHooks\.vars"/)
})

test('faceplateHooks: the compact collapsed layout (LLAMA) while collapsed, nothing for legacy themes; the projection while full screen', async () => {
  const source = await read('../components/app/AudioPlayer.vue')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { coverPresentation: {}, playerLayout: layout, Capacitor: {}, AbsAudioPlayer: {}, Dialog: {}, getAverageColorFromCoverUrl: async () => null, WrappingMarquee: function () {}, jumpLabelMixin: {}, console }
  vm.runInNewContext(script, sandbox)
  const c = sandbox.component.computed
  const ctx = { themeId: 'llama', showFullscreen: true, windowWidth: 412, windowHeight: 842, playerSettings: { useChapterTrack: false, useTotalTrack: true }, $store: { getters: { 'libraries/getBookCoverAspectRatio': 1 } } }
  Object.defineProperty(ctx, '$theme', { get: () => ({ theme: engine.getTheme(ctx.themeId) }) })
  for (const name of ['presentationTheme', 'bookCoverAspectRatio', 'fullscreenLayout', 'fullscreenBookCoverWidth', 'miniLayout']) Object.defineProperty(ctx, name, { get: () => c[name].call(ctx) })
  assert.deepEqual([...c.faceplateHooks.call(ctx).classes], ['faceplate-type', 'faceplate', 'faceplate-top-plate'])
  ctx.playerSettings.useChapterTrack = true
  assert.equal(ctx.fullscreenLayout.path, 'compat')
  ctx.showFullscreen = false
  assert.deepEqual(JSON.parse(JSON.stringify(c.faceplateHooks.call(ctx))), { classes: ['mini-compact'], vars: { '--mini-trim': '28px' }, plates: { top: false, console: false, seek: false } })
  ctx.showFullscreen = true
  for (const id of LEGACY_THEMES) {
    ctx.themeId = id
    assert.deepEqual(JSON.parse(JSON.stringify(c.faceplateHooks.call(ctx))), { classes: [], vars: {}, plates: { top: false, console: false, seek: false } })
    assert.equal(ctx.fullscreenBookCoverWidth, previousCoverWidth(412, 842, 1))
    ctx.showFullscreen = false
    assert.deepEqual(JSON.parse(JSON.stringify(c.faceplateHooks.call(ctx))), { classes: [], vars: {}, plates: { top: false, console: false, seek: false } }, `${id} collapsed`)
    assert.equal(ctx.miniLayout.height, 120, id)
    ctx.showFullscreen = true
  }
})

test('Console banks are vertically centered in their framed sections (transport by its key envelope, utility by its row)', async () => {
  const P = presets.PRIMITIVES
  const { FACEPLATE } = layout
  const root = engine.themeSelector('llama')
  const rules = presets.presentationRules([llama()])
  const px = (v) => parseFloat(v)
  const transport = rules[`${root} #streamContainer.fullscreen.faceplate #playerControls`]
  const utility = rules[`${root} #streamContainer.fullscreen.faceplate #playerContent .utility-row`]
  assert.deepEqual(transport, { ...P.FACEPLATE_TRANSPORT_BANK })
  assert.deepEqual(utility, { ...P.FACEPLATE_UTILITY_BANK })
  // Offsets measured up from the deck bottom. The divider is where the deck already puts it (DECK.seam, unchanged)
  const divider = P.DECK.seam.base - P.DECK.seam.drop
  assert.equal(P.FACEPLATE_DIVIDER, divider)
  const plateTop = FACEPLATE.console
  const plateBottom = FACEPLATE.consoleInset
  // Transport: the bank box is the section [divider, plateTop]; its 65px row is centered in the box below its top padding
  assert.equal(px(transport.height), plateTop - divider)
  assert.equal(transport.display, 'flex')
  assert.equal(transport['flex-direction'], 'column')
  assert.equal(transport['justify-content'], 'center')
  const row = P.PRIMARY_KEY_BOX
  const pad = px(transport['padding-top'])
  const rowTop = plateTop - pad - (plateTop - divider - pad - row) / 2 // distance of the row box's top above the deck bottom
  // Envelope: the lifted primary's top to the secondary keys' bottom (60px keys centered in the 65px row)
  const envelopeTop = rowTop + P.DECK.primaryLift
  const envelopeBottom = rowTop - row + (row - P.DECK.transport.height) / 2
  const transportTopClear = plateTop - envelopeTop
  const transportBottomClear = envelopeBottom - divider
  assert.ok(Math.abs(transportTopClear - transportBottomClear) < 0.01, `transport clearances ${transportTopClear}/${transportBottomClear}`)
  assert.ok(transportTopClear >= 8, 'the bank keeps breathing room')
  // Utility: the bank box is the section [plateBottom, divider]; its keys (and the 52px readouts) are centered in it
  assert.equal(px(utility.bottom), plateBottom)
  assert.equal(px(utility.height), divider - plateBottom)
  assert.equal(utility['justify-content'], 'center')
  const utilityClear = (divider - plateBottom - P.DECK.utility.height) / 2
  assert.ok(utilityClear >= 6, `utility clearance ${utilityClear}`)
  // The constants describe the real elements: the player's primary box, and the console plate's inset in its stylesheet
  const player = await read('../components/app/AudioPlayer.vue')
  assert.match(player, new RegExp(`\\.fullscreen #playerControls \\.play-btn \\{\\s*height: ${row}px;\\s*width: ${row}px;`))
  const plate = player.match(/\.player-plate-console \{([^}]*)\}/)[1]
  assert.match(plate, new RegExp(`bottom: ${plateBottom}px;`))
  assert.match(plate, /top: var\(--faceplate-console-top\);/)
  assert.equal(at(llama(), [412, 842]).vars['--faceplate-console-top'], `${layout.TIERS.regular.deck - plateTop}px`)
  // The utility section is the same on the previous-geometry plate paths (compat, landscape: plate bottom inset, same
  // divider), so the same centered bank applies there; the transport section exists only on the faceplate
  assert.deepEqual(rules[`${root} #streamContainer.fullscreen.faceplate-flat #playerContent .utility-row`], { ...P.FACEPLATE_UTILITY_BANK })
  assert.equal(rules[`${root} #streamContainer.fullscreen.faceplate-flat #playerControls`], undefined)
  const flatPlate = player.match(/\.faceplate-flat \.player-plate-console \{([^}]*)\}/)[1]
  assert.doesNotMatch(flatPlate, /bottom/, 'flat console plate keeps the same bottom inset')
  const banks = Object.keys(rules).filter((s) => /#playerControls$|\.utility-row$/.test(s) && /faceplate/.test(s))
  assert.deepEqual(banks.map((s) => s.slice(root.length + 1)).sort(), ['#streamContainer.fullscreen.faceplate #playerContent .utility-row', '#streamContainer.fullscreen.faceplate #playerControls', '#streamContainer.fullscreen.faceplate-dual #playerContent .utility-row', '#streamContainer.fullscreen.faceplate-flat #playerContent .utility-row'])
  // Legacy themes get none of it
  for (const id of LEGACY_THEMES) assert.deepEqual(presets.presentationRules([engine.getTheme(id)]), {})
})

test('Chapter + book progress is one dual-track instrument: adjacent sections, one frame, an engraved seam, keys unmoved', async () => {
  const P = presets.PRIMITIVES
  const { FACEPLATE } = layout
  const D = FACEPLATE.dual
  const root = engine.themeSelector('llama')
  const rules = presets.presentationRules([llama()])
  const player = await read('../components/app/AudioPlayer.vue')
  const px = (v) => parseFloat(v)
  for (const vp of [
    [412, 842],
    [412, 734],
    [412, 933],
    [762, 986],
    [915, 364]
  ]) {
    const r = at(llama(), vp, 1, true)
    assert.ok(r.classes.includes('faceplate-dual'), String(vp))
    // Directly stacked: the book section's bottom is the chapter section's top (no chassis between them)
    assert.equal(px(r.vars['--faceplate-dual-book-bottom']), px(r.vars['--faceplate-dual-main-bottom']) + px(r.vars['--faceplate-dual-section']))
    assert.equal(px(r.vars['--faceplate-dual-main-bottom']), D.console + D.seam + D.plate)
    assert.equal(px(r.vars['--faceplate-console-top']), FACEPLATE.legacyDeck - D.console)
    assert.equal(r.coverWidth, previousCoverWidth(vp[0], vp[1], 1), 'previous artwork geometry')
  }
  // One frame: the seek plate is extended over the book section (plate width above, below it), never a second plate
  const plate = player.match(/\.faceplate-dual \.player-plate-seek \{([^}]*)\}/)[1]
  assert.match(plate, new RegExp(`top: calc\\(-${D.plate}px - var\\(--faceplate-dual-section\\)\\);`))
  assert.match(plate, new RegExp(`bottom: -${D.plate}px;`))
  const template = player.slice(0, player.indexOf('</template>'))
  assert.equal((template.match(/class="player-plate /g) || []).length, 3, 'no extra plate element')
  // Both sections: same height, same 10px inset, same 6px rail, same 16px green condensed times on a tight line
  assert.match(player, /#streamContainer\.fullscreen\.faceplate-dual #playerTrack \{[^}]*bottom: var\(--faceplate-dual-main-bottom\);\s*height: var\(--faceplate-dual-section\);/)
  assert.match(player, /#streamContainer\.fullscreen\.faceplate-dual \.total-track \{\s*bottom: var\(--faceplate-dual-book-bottom\);\s*height: var\(--faceplate-dual-section\);/)
  assert.match(player, /\.faceplate-dual #playerTrack > div\.flex,\s*\.faceplate-dual \.total-track > div\.flex \{\s*padding: 0 10px;/)
  assert.match(player, /\.faceplate-dual \.total-track div\.relative \{\s*height: 6px;/)
  assert.match(player, /class="h-1\.5 w-full bg-track\/50 relative rounded-full/, 'the chapter rail is 6px (h-1.5)')
  assert.match(player, /\.faceplate-dual \.total-track p\.font-mono \{\s*font-size: var\(--faceplate-times\) !important;/)
  // Each section holds its content at font scale 1.3: 16px times on line-height 1, 2px, the 6px rail
  assert.ok(D.section >= FACEPLATE.flatTimes * 1.3 + 2 + 6, `section ${D.section}`)
  // Keys do not move: the console plate's top edge centers the transport bank where it already sits (divider + 7.5px,
  // the key envelope, 7.5px), and the utility bank is centered in its section as on the other plate paths
  const divider = P.DECK.seam.base - P.DECK.seam.drop
  const clear = P.DECK.seam.drop + (P.PRIMARY_KEY_BOX - P.DECK.transport.height) / 2
  const envelope = P.PRIMARY_KEY_BOX + P.DECK.primaryLift - (P.PRIMARY_KEY_BOX - P.DECK.transport.height) / 2
  assert.equal(D.console, divider + clear + envelope + clear)
  assert.equal(rules[`${root} #streamContainer.fullscreen.faceplate-dual #playerControls`], undefined, 'transport untouched')
  assert.deepEqual(rules[`${root} #streamContainer.fullscreen.faceplate-dual #playerContent .utility-row`], { ...P.FACEPLATE_UTILITY_BANK })
  // The instrument's top clears the metadata readout's floors with the two rails (Phase 4E: 249px, short screens 247px,
  // each with its 4px plate) by at least 6px
  const top = D.console + D.seam + D.plate + 2 * D.section + D.plate
  assert.ok(top + 6 <= 249 - 4 && top + 6 <= 247 - 4, `instrument top ${top}`)
  // One recessed well with an engraved seam: the book section ends on the seam's dark line, the chapter section starts on
  // its faint light line (ENGRAVED_SEPARATOR's colors); the inner corners are square, the outer ones the key radius
  assert.deepEqual(rules[`${root} #streamContainer.faceplate-dual .total-track`], { ...P.DUAL_TRACK_UPPER })
  assert.deepEqual(rules[`${root} #streamContainer.faceplate-dual #playerTrack`], { ...P.DUAL_TRACK_LOWER })
  assert.ok(P.ENGRAVED_SEPARATOR.includes('rgb(var(--color-edge-dark))') && P.ENGRAVED_SEPARATOR.includes('rgb(var(--color-edge-light) / 0.12)'))
  assert.match(P.DUAL_TRACK_UPPER['background-image'], /rgb\(var\(--color-edge-dark\)\) calc\(100% - 1px\)\)$/)
  assert.match(P.DUAL_TRACK_LOWER['background-image'], /^linear-gradient\(180deg, rgb\(var\(--color-edge-light\) \/ 0\.12\) 0, /)
  assert.equal(P.DUAL_TRACK_UPPER['border-bottom-left-radius'], P.RADIUS.none)
  assert.equal(P.DUAL_TRACK_LOWER['border-top-right-radius'], P.RADIUS.none)
  assert.deepEqual(rules[`${root} #streamContainer.faceplate-dual #playerContent`], { ...P.FACEPLATE_DECK })
  // Legacy themes: none of it
  for (const id of LEGACY_THEMES) assert.deepEqual(presets.presentationRules([engine.getTheme(id)]), {})
})

test('Single-rail progress (book only, chapter only) stays on the B+ faceplate; only both rails make the dual instrument', async () => {
  const source = await read('../components/app/AudioPlayer.vue')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { coverPresentation: {}, playerLayout: layout, Capacitor: {}, AbsAudioPlayer: {}, Dialog: {}, getAverageColorFromCoverUrl: async () => null, WrappingMarquee: function () {}, jumpLabelMixin: {}, console }
  vm.runInNewContext(script, sandbox)
  const c = sandbox.component.computed
  const ctx = { showFullscreen: true, windowWidth: 412, windowHeight: 842, playerSettings: {}, $store: { getters: { 'libraries/getBookCoverAspectRatio': 1 } } }
  Object.defineProperty(ctx, '$theme', { get: () => ({ theme: llama() }) })
  for (const name of ['presentationTheme', 'bookCoverAspectRatio', 'fullscreenLayout']) Object.defineProperty(ctx, name, { get: () => c[name].call(ctx) })
  for (const [chapter, total, path, dual] of [
    [false, true, 'faceplate', false], // book only (the default)
    [true, false, 'faceplate', false], // chapter only
    [true, true, 'compat', true] // chapter + book
  ]) {
    ctx.playerSettings = { useChapterTrack: chapter, useTotalTrack: total }
    assert.equal(ctx.fullscreenLayout.path, path, `${chapter}/${total}`)
    assert.equal([...ctx.fullscreenLayout.classes].includes('faceplate-dual'), dual)
  }
  // The second rail's own condition in the template is unchanged
  assert.match(source, /<div v-if="playerSettings\.useChapterTrack && playerSettings\.useTotalTrack && showFullscreen" class="absolute total-track w-full z-30 px-6">/)
})

test('Recipe paint for the faceplate: LLAMA only, paint only, bay widths from the layout tiers', () => {
  const root = engine.themeSelector('llama')
  const rules = presets.presentationRules([llama()])
  const P = presets.PRIMITIVES
  assert.deepEqual(rules[`${root} .player-plate`], { ...P.FACEPLATE_PLATE })
  assert.deepEqual(rules[`${root} .player-plate-top`], { ...P.FACEPLATE_TOP_PLATE })
  assert.deepEqual(rules[`${root} #streamContainer.faceplate .cover-wrapper`], { ...P.ARTWORK_BAY.regular })
  assert.deepEqual(rules[`${root} #streamContainer.faceplate-compact .cover-wrapper`], { ...P.ARTWORK_BAY.compact })
  assert.deepEqual(rules[`${root} #streamContainer.faceplate #playerContent`], { ...P.FACEPLATE_DECK })
  assert.deepEqual(rules[`${root} #streamContainer.faceplate-flat #playerContent`], { ...P.FACEPLATE_DECK })
  // Faithful pass: the bezel is the raised steel of the module plates
  assert.ok(P.ARTWORK_BAY.regular['box-shadow'].includes(`0 0 0 ${layout.TIERS.regular.bezel}px rgb(var(--color-secondary))`))
  assert.ok(P.ARTWORK_BAY.compact['box-shadow'].includes(`0 0 0 ${layout.TIERS.compact.bezel}px rgb(var(--color-secondary))`))
  // Legacy themes get no faceplate rules at all (the standard finish produces none)
  for (const id of LEGACY_THEMES) assert.deepEqual(presets.presentationRules([engine.getTheme(id)]), {})
  // Metadata stays neutral: nothing in the faceplate paint colors the title or author
  for (const [selector, d] of Object.entries(rules)) if (/faceplate|player-plate/.test(selector)) assert.equal(d.color, undefined, selector)
})

test('Full-player bookmark key: shown for every server book whether or not the socket is connected (never the invisible placeholder offline)', async () => {
  const src = await read('../components/app/AudioPlayer.vue')
  const key = src.match(/<span v-if="([^"]*)" class="player-key utility-key bookmark-key[^"]*"[^>]*>bookmark<\/span>/)
  assert.ok(key, 'the bookmark key exists in the utility row')
  // A downloaded copy of another saved server has bookmarks without the active server's ids (bookmarkable)
  assert.equal(key[1], '!isPodcast && (serverLibraryItemId || bookmarkable)')
  assert.match(key[0], /@click="\$emit\('showBookmarks'\)"/)
  // Order in the row: queue, bookmark, speed, sleep, chapters
  const row = src.slice(src.indexOf('class="utility-row'), src.indexOf('chapters-key'))
  const at = (s) => row.indexOf(s)
  assert.ok(at('queue-key') < at('bookmark-key') && at('bookmark-key') < at('speed-readout') && at('speed-readout') < at('sleep-key'))
})

// Release 3: a stream whose server went away pauses and waits for it (native AwaitingServer). The player stays open
// with a "Waiting for server" label instead of the "Playback Failed" toast and close.
test('awaiting server shows a label and keeps the player open', async () => {
  const strip = (source) => source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =')
  const storeSandbox = {}
  vm.runInNewContext((await read('../store/index.js')).replace(/^import .*$/gm, '').replace(/^export const (\w+) =/gm, 'globalThis.$1 ='), storeSandbox)
  const state = storeSandbox.state()
  const store = { state, commit: (name, payload) => storeSandbox.mutations[name].call({ $localStore: { setPlaybackQueue: () => {} } }, state, payload) }

  const native = { listeners: {}, closed: 0 }
  const AbsAudioPlayer = {
    addListener: async (name, fn) => {
      native.listeners[name] = fn
      return { remove: () => {} }
    },
    closePlayback: () => native.closed++
  }
  const containerSandbox = { AbsAudioPlayer, AbsLogger: { info: async () => {} }, CellularPermissionHelpers: {}, console: { ...console, log: () => {} } }
  vm.runInNewContext(strip(await read('../components/app/AudioPlayerContainer.vue')), containerSandbox)
  const toasts = []
  const bus = { $on: () => {}, $off: () => {} }
  const container = { $store: { ...store, getters: { 'user/getUserSetting': () => 1 } }, $toast: { error: (m) => toasts.push(m), info: () => {} }, $eventBus: bus }
  for (const [name, fn] of Object.entries(containerSandbox.component.methods)) container[name] = fn.bind(container)
  await containerSandbox.component.mounted.call(container)

  store.commit('setPlaybackSession', { id: 'stream-1', libraryItemId: 'li1' })
  native.listeners.onAwaitingServer({ awaiting: true })
  assert.equal(state.playerAwaitingServer, true)
  assert.deepEqual(toasts, [])
  assert.equal(native.closed, 0)
  assert.equal(state.currentPlaybackSession.id, 'stream-1', 'the player stays open')

  // The player shows the label in place of the author, and its Play key instead of the loading spinner
  const playerSandbox = { AbsAudioPlayer, console, Capacitor: {}, Dialog: {}, getAverageColorFromCoverUrl: () => null, normalizePlaybackRate: (v) => v, formatPlaybackRate: String, coverPresentation: {}, playerLayout: {}, WrappingMarquee: function () {}, jumpLabelMixin: {} }
  vm.runInNewContext(strip(await read('../components/app/AudioPlayer.vue')), playerSandbox)
  const computed = playerSandbox.component.computed
  const view = { $store: store, $strings: { LabelWaitingForServer: 'Waiting for server' }, authorName: 'Author', isLoading: true, isCheckingServerProgress: false }
  view.awaitingServer = computed.awaitingServer.call(view)
  assert.equal(computed.authorLine.call(view), 'Waiting for server')
  assert.equal(computed.showLoadingState.call(view), false)
  assert.match(await read('../components/app/AudioPlayer.vue'), /\{\{ authorLine \}\}/)

  native.listeners.onAwaitingServer({ awaiting: false })
  view.awaitingServer = computed.awaitingServer.call(view)
  assert.equal(computed.authorLine.call(view), 'Author')
  assert.equal(computed.showLoadingState.call(view), true)

  // A new session or a close ends the label even if no event said so
  native.listeners.onAwaitingServer({ awaiting: true })
  store.commit('setPlaybackSession', { id: 'stream-1', libraryItemId: 'li1' })
  assert.equal(state.playerAwaitingServer, true, 'the same session keeps waiting')
  store.commit('setPlaybackSession', { id: 'stream-2', libraryItemId: 'li2' })
  assert.equal(state.playerAwaitingServer, false)
  native.listeners.onAwaitingServer({ awaiting: true })
  store.commit('setPlaybackSession', null)
  assert.equal(state.playerAwaitingServer, false)

  const strings = JSON.parse(await read('../strings/en-us.json'))
  assert.equal(strings.LabelWaitingForServer, 'Waiting for server')
})
