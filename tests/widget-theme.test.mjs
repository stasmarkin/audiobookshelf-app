import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const presets = require('../theme/presets.js')
const generator = require('../scripts/generate-widget-theme.js')
// Source files are read LF-normalized, so a Windows (core.autocrlf) checkout and CI see the same text
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

test('the committed native widget palette matches the canonical built-in theme data', async () => {
  const committed = (await read('../android/app/src/main/res/values/widget_theme_colors.xml')).replace(/\r\n/g, '\n')
  assert.equal(committed, generator.render(), 'stale: run node scripts/generate-widget-theme.js')
})

test('native widget colors equal the LLAMA tokens and derived bevel edges', async () => {
  const xml = await read('../android/app/src/main/res/values/widget_theme_colors.xml')
  const colors = Object.fromEntries([...xml.matchAll(/<color name="([a-z_]+)">#([0-9A-F]{6})<\/color>/g)].map(([, n, h]) => [n, [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16))]))
  const t = engine.getTheme('llama').tokens
  assert.deepEqual(colors.widget_llama_base, t['surface.base'])
  assert.deepEqual(colors.widget_llama_recessed, t['surface.recessed'])
  assert.deepEqual(colors.widget_llama_accent, t['accent.primary'])
  assert.deepEqual(colors.widget_llama_played, t['progress.played'])
  assert.deepEqual(colors.widget_llama_text, t['text.primary'])
  const derived = presets.equipmentDerivedDeclarations(t)
  assert.deepEqual(colors.widget_llama_edge_light, derived['--color-edge-light'].split(' ').map(Number))
  assert.deepEqual(colors.widget_llama_edge_dark, derived['--color-edge-dark'].split(' ').map(Number))
  // The app's lit key legend, the transport glyph tint since the final consistency pass
  assert.deepEqual(colors.widget_llama_legend, derived['--color-legend'].split(' ').map(Number))
  assert.deepEqual(colors.widget_llama_raised, t['surface.raised'])
  assert.deepEqual(colors.widget_llama_content, t['surface.content'])
  // Token colors, the two bevel edges, the legend, and the derived key colors (Phase 4D, steel since the final pass)
  assert.equal(Object.keys(colors).length, generator.TOKEN_COLORS.length + 3 + generator.keyColors(t).length)
})

test('every generated widget color is used by a widget resource (the palette carries no dead colors)', async () => {
  const { readdir } = await import('node:fs/promises')
  const res = new URL('../android/app/src/main/res/', import.meta.url)
  let sources = ''
  for (const dir of (await readdir(res)).filter((d) => d.startsWith('drawable') || d.startsWith('layout'))) {
    for (const file of (await readdir(new URL(`${dir}/`, res))).filter((f) => f.endsWith('.xml'))) sources += await read(`../android/app/src/main/res/${dir}/${file}`)
  }
  const xml = await read('../android/app/src/main/res/values/widget_theme_colors.xml')
  const names = [...xml.matchAll(/<color name="([a-z_]+)">/g)].map(([, n]) => n)
  assert.ok(names.length > 0)
  for (const name of names) assert.ok(sources.includes(`@color/${name}`), `${name} is generated but no drawable or layout uses it`)
})

test('only equipment-finish built-ins get a native widget palette; standard themes keep the existing look', () => {
  assert.deepEqual(
    generator.widgetThemes().map((t) => t.id),
    ['llama']
  )
})

const LAYOUTS = '../android/app/src/main/res/layout/'
const VARIANTS = [
  ['media_player_widget.xml', 'media_player_widget_llama.xml'],
  ['media_player_widget_wide.xml', 'media_player_widget_wide_llama.xml'],
  ['media_player_widget_full.xml', 'media_player_widget_full_llama.xml'],
  ['media_player_widget_full_expanded.xml', 'media_player_widget_full_expanded_llama.xml'],
  ['media_player_widget_full_expanded_large.xml', 'media_player_widget_full_expanded_large_llama.xml']
]
const STACKED_LAYOUTS = ['media_player_widget_full_expanded.xml', 'media_player_widget_full_expanded_llama.xml', 'media_player_widget_full_expanded_large.xml', 'media_player_widget_full_expanded_large_llama.xml']
const FULL_LAYOUTS = ['media_player_widget_full.xml', 'media_player_widget_full_llama.xml', ...STACKED_LAYOUTS]
const viewIds = (xml) => [...xml.matchAll(/android:id="@\+id\/([A-Za-z]+)"/g)].map((m) => m[1]).sort()

test('each LLAMA layout has exactly the view ids (and so the actions) of its standard layout', async () => {
  for (const [standard, llama] of VARIANTS) {
    const ids = viewIds(await read(LAYOUTS + standard))
    assert.ok(ids.includes('widgetPlayPauseButton') && ids.includes('widgetBackground'))
    assert.deepEqual(viewIds(await read(LAYOUTS + llama)), ids, llama)
  }
})

test('LLAMA layouts paint only with generated/LLAMA resources; standard layouts never use them', async () => {
  for (const [standard, llama] of VARIANTS) {
    const llamaXml = await read(LAYOUTS + llama)
    for (const [, ref] of llamaXml.matchAll(/"@(?:color|drawable)\/([a-z_0-9]+)"/g)) {
      assert.ok(/^widget_llama_/.test(ref) || ['icon', 'icon_monochrome', 'exo_icon_rewind', 'exo_icon_fastforward', 'ic_media_play_dark'].includes(ref), `${llama}: ${ref}`)
    }
    assert.doesNotMatch(llamaXml, /colorAccent|#[0-9A-Fa-f]{6}/, `${llama} has no hard-coded or standard colors`)
    assert.doesNotMatch(await read(LAYOUTS + standard), /llama/, `${standard} is unchanged by LLAMA`)
  }
})

const attrs = (xml, id) => {
  const start = xml.indexOf(`android:id="@+id/${id}"`)
  return xml.slice(xml.lastIndexOf('<', start), xml.indexOf('>', start))
}

test('FULL artwork is bounded (both dimensions wrap, aspect kept, no crop, fallback cap) in every FULL layout', async () => {
  const dimens = await read('../android/app/src/main/res/values/dimens.xml')
  const cap = Number(dimens.match(/<dimen name="widget_full_artwork_max">(\d+)dp<\/dimen>/)[1])
  // Fallback before the launcher reports a size: above a normal two-row FULL cover (~137dp), so that size
  // renders as before, and far below the cover's intrinsic size (WidgetArtworkTest), so the cap is what bounds it
  assert.ok(cap < 300 && cap >= 137, `cap ${cap}dp`)
  for (const file of FULL_LAYOUTS) {
    const art = attrs(await read(LAYOUTS + file), 'widgetAlbumArt')
    for (const expected of ['android:layout_width="wrap_content"', 'android:layout_height="wrap_content"', 'android:adjustViewBounds="true"', 'android:maxWidth="@dimen/widget_full_artwork_max"', 'android:maxHeight="@dimen/widget_full_artwork_max"', 'android:scaleType="fitCenter"']) {
      assert.ok(art.includes(expected), `${file}: ${expected}`)
    }
  }
  for (const file of ['media_player_widget_full_llama.xml', 'media_player_widget_full_expanded_llama.xml', 'media_player_widget_full_expanded_large_llama.xml']) {
    const llamaArt = attrs(await read(LAYOUTS + file), 'widgetAlbumArt')
    for (const frame of ['android:padding="2dp"', 'android:background="@drawable/widget_llama_artwork_frame"', 'android:cropToPadding="true"']) assert.ok(llamaArt.includes(frame), `${file}: LLAMA frame stays on the artwork view: ${frame}`)
  }
  const renderer = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetRenderer.kt')
  assert.match(renderer, /widgetArtwork\(it\)/, 'loaded covers get the fixed-density widget copy')
})

test('responsive FULL bounds are applied only to FULL, from the reported size, on the artwork view', async () => {
  const renderer = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetRenderer.kt')
  // The helper sets max width/height and forces a relayout (hidden, then shown again in the same update)
  const helper = renderer.slice(renderer.indexOf('internal fun applyArtworkBounds'), renderer.indexOf('\n  }\n', renderer.indexOf('internal fun applyArtworkBounds')))
  assert.deepEqual(
    [...helper.matchAll(/views\.setInt\(viewId, "(\w+)", ([\w-]+)\)/g)].map((m) => `${m[1]}(${m[2]})`),
    ['setMaxWidth(maxWidthPx)', 'setMaxHeight(maxHeightPx)']
  )
  assert.match(helper, /views\.setViewVisibility\(viewId, View\.GONE\)\n\s*views\.setViewVisibility\(viewId, View\.VISIBLE\)/)
  // ...and is called once, on the artwork, inside the FULL-only block
  const fullBlock = renderer.slice(renderer.indexOf('if (size == WidgetSize.FULL) {'), renderer.indexOf('return views', renderer.indexOf('if (size == WidgetSize.FULL) {')))
  assert.match(fullBlock, /applyArtworkBounds\(views, R\.id\.widgetAlbumArt,/, 'bounds are set inside the FULL-only block')
  assert.equal(renderer.split('applyArtworkBounds(').length - 1, 2, 'defined once and called once')
  assert.equal(renderer.split('"setMaxWidth"').length - 1, 1, 'and set nowhere else')
  assert.match(renderer, /FullArtwork\.sizeDp\(options\)/)
})

test('FullArtwork constants match the FULL layouts they reserve space for', async () => {
  const kotlin = await read('../android/app/src/main/java/app/absplus/android/widget/FullArtwork.kt')
  const k = (name) => {
    const expr = kotlin.match(new RegExp(`const val ${name} = ([0-9f .+*]+)`))[1]
    return expr.split('+').reduce((sum, part) => sum + part.split('*').reduce((p, f) => p * Number(f.trim().replace(/f$/, '')), 1), 0)
  }
  const dp = (xml, id, attr) => Number(attrs(xml, id).match(new RegExp(`android:${attr}="(\\d+)(?:dp|sp)"`))[1])
  const std = await read(LAYOUTS + 'media_player_widget_full.xml')
  const llama = await read(LAYOUTS + 'media_player_widget_full_llama.xml')
  const expanded = [await read(LAYOUTS + 'media_player_widget_full_expanded.xml'), await read(LAYOUTS + 'media_player_widget_full_expanded_llama.xml')]
  const large = [await read(LAYOUTS + 'media_player_widget_full_expanded_large.xml'), await read(LAYOUTS + 'media_player_widget_full_expanded_large_llama.xml')]
  // NORMAL: the readout's frame (which also holds the panel icon slot) keeps the readout margin beside the cover
  for (const xml of [std, llama]) {
    const at = xml.indexOf('android:id="@+id/widgetReadout"')
    const frame = xml.slice(xml.lastIndexOf('<FrameLayout', at), at)
    assert.equal(Number(frame.match(/android:layout_marginStart="(\d+)dp"/)[1]), k('READOUT_MARGIN_DP'))
  }
  // EXPANDED and LARGE: the full-width readout sits EXPANDED_READOUT_GAP_DP below the cover area
  for (const xml of [...expanded, ...large]) assert.equal(dp(xml, 'widgetReadout', 'layout_marginTop'), k('EXPANDED_READOUT_GAP_DP'))
  for (const [xml, isLarge] of [std, llama, ...expanded].map((x) => [x, false]).concat(large.map((x) => [x, true]))) {
    assert.equal(dp(xml, 'widgetContent', 'padding'), k('CONTENT_PADDING_DP'))
    assert.equal(dp(xml, 'tinyCornerIcon', 'layout_width'), k('ICON_SIZE_DP'))
    assert.equal(dp(xml, 'widgetMediaTitle', 'textSize'), k(isLarge ? 'LARGE_TITLE_TEXT_SP' : 'TITLE_TEXT_SP'))
    assert.ok(attrs(xml, 'widgetMediaTitle').includes('android:maxLines="2"'), 'title wraps to at most two lines')
    assert.ok(attrs(xml, 'widgetArtistText').includes('android:maxLines="1"') && attrs(xml, 'widgetArtistText').includes('android:ellipsize="end"'), 'author: one line, ellipsized')
    assert.equal(dp(xml, 'widgetArtistText', 'textSize'), k(isLarge ? 'LARGE_TIME_TEXT_SP' : 'TIME_TEXT_SP'))
    // The constants are a reserve: a layout may use less than they model, never more. Only LLAMA NORMAL uses less
    // (Phase 2C Gate F: tighter vertical spacing so a two-line title, author and time fit its readout)
    const gaps = dp(xml, 'widgetArtistText', 'layout_marginTop') + dp(xml, 'widgetTimeRow', 'layout_marginTop')
    if (xml === llama) assert.ok(gaps <= k('READOUT_LINE_GAPS_DP'), `LLAMA NORMAL gaps ${gaps}dp within the reserve`)
    else assert.equal(gaps, k('READOUT_LINE_GAPS_DP'))
    assert.equal(dp(xml, 'widgetButtonContainer', 'layout_marginTop') + dp(xml, 'widgetButtonContainer', 'layout_height'), k(isLarge ? 'LARGE_CONTROLS_DP' : 'CONTROLS_DP'))
    for (const id of ['widgetElapsedText', 'widgetRemainingText']) {
      assert.equal(dp(xml, id, 'textSize'), k(isLarge ? 'LARGE_TIME_TEXT_SP' : 'TIME_TEXT_SP'))
      assert.ok(attrs(xml, id).includes('android:fontFamily="monospace"'), `${id} is monospace`)
    }
  }
  // Width reserve uses the larger readout padding; height backstop the smaller progress bar (the layout limits height anyway)
  const all = [std, llama, ...expanded, ...large]
  const padding = (xml, axis) => {
    const a = attrs(xml, 'widgetReadout')
    const m = a.match(new RegExp(`android:padding${axis}="(\\d+)dp"`)) || a.match(/android:padding="(\d+)dp"/)
    return Number(m[1])
  }
  assert.equal(Math.max(...all.map((xml) => padding(xml, 'Horizontal'))), k('READOUT_PADDING_DP'))
  assert.equal(Math.min(...all.filter((xml) => xml !== llama).map((xml) => padding(xml, 'Vertical'))), k('READOUT_PADDING_MIN_DP'))
  assert.equal(padding(llama, 'Horizontal'), k('READOUT_PADDING_DP'), 'LLAMA NORMAL keeps the width reserve exactly')
  assert.ok(padding(llama, 'Vertical') <= k('READOUT_PADDING_DP'), 'LLAMA NORMAL vertical padding within the height reserve')
  const progress = (xml) => dp(xml, 'widgetProgress', 'layout_marginTop') + dp(xml, 'widgetProgress', 'layout_height')
  assert.equal(Math.min(...all.map(progress)), k('PROGRESS_DP'))
})

test('EXPANDED and LARGE FULL stack cover, readout, progress and controls without overlap', async () => {
  for (const file of STACKED_LAYOUTS) {
    const xml = await read(LAYOUTS + file)
    const at = (id) => xml.indexOf(`android:id="@+id/${id}"`)
    // One vertical column inside widgetContent, in this order: cover area, readout row, progress, controls
    assert.ok(attrs(xml, 'widgetContent').includes('android:orientation="vertical"'), file)
    assert.ok(at('widgetAlbumArt') < at('widgetReadout') && at('widgetReadout') < at('widgetProgress') && at('widgetProgress') < at('widgetButtonContainer'), `${file}: order`)
    // The cover alone fills the flexible area (weight 1); once it stops growing it sits on the readout and spare height
    // goes above it (S26 polish), so cover, readout, progress and controls stay one block at the bottom
    const area = xml.slice(xml.lastIndexOf('<LinearLayout', at('widgetAlbumArt')), at('widgetAlbumArt'))
    for (const a of ['android:layout_height="0dp"', 'android:layout_weight="1"', 'android:gravity="bottom|center_horizontal"']) assert.ok(area.includes(a), `${file}: cover area ${a}`)
    assert.ok(attrs(xml, 'widgetAlbumArt').includes('android:layout_gravity="center_horizontal"'), `${file}: cover centered`)
    assert.ok(attrs(xml, 'widgetReadout').includes('android:layout_height="wrap_content"'), `${file}: readout keeps its height`)
    assert.equal(xml.slice(at('widgetAlbumArt'), at('widgetReadout')).match(/<\/LinearLayout>/g).length, 1, `${file}: the readout is outside the cover area`)
  }
  const renderer = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetRenderer.kt')
  for (const suffix of ['', '_llama']) {
    for (const [presentation, layout] of [
      ['NORMAL', 'full'],
      ['EXPANDED', 'full_expanded'],
      ['LARGE', 'full_expanded_large']
    ]) {
      assert.match(renderer, new RegExp(`FullArtwork\\.Presentation\\.${presentation} -> R\\.layout\\.media_player_widget_${layout}${suffix}\\n`), `${presentation}${suffix}`)
    }
  }
})

test('LARGE is EXPANDED with larger readout text and controls only', async () => {
  const sizes = {
    widgetMediaTitle: ['textSize', '16sp', '22sp'],
    widgetArtistText: ['textSize', '13sp', '18sp'],
    widgetElapsedText: ['textSize', '13sp', '18sp'],
    widgetRemainingText: ['textSize', '13sp', '18sp'],
    widgetButtonContainer: ['layout_height', '52dp', '80dp'],
    widgetRewindButton: ['padding', '10dp', '16dp'],
    widgetPlayPauseButton: ['padding', '10dp', '16dp'],
    widgetFastForwardButton: ['padding', '10dp', '16dp']
  }
  for (const suffix of ['', '_llama']) {
    const expandedXml = await read(LAYOUTS + `media_player_widget_full_expanded${suffix}.xml`)
    let largeXml = await read(LAYOUTS + `media_player_widget_full_expanded_large${suffix}.xml`)
    for (const [id, [attr, from, to]] of Object.entries(sizes)) {
      assert.ok(attrs(expandedXml, id).includes(`android:${attr}="${from}"`), `expanded${suffix} ${id} ${from}`)
      assert.ok(attrs(largeXml, id).includes(`android:${attr}="${to}"`), `large${suffix} ${id} ${to}`)
      const at = largeXml.indexOf(`android:id="@+id/${id}"`),
        start = largeXml.lastIndexOf('<', at),
        end = largeXml.indexOf('>', at)
      largeXml = largeXml.slice(0, start) + largeXml.slice(start, end).replace(`android:${attr}="${to}"`, `android:${attr}="${from}"`) + largeXml.slice(end)
    }
    // With those sizes reverted, LARGE is EXPANDED apart from its header comment: same structure, ids, paint, actions and icon
    const body = (xml) => xml.replace(/\r\n/g, '\n').replace(/^<!--.*?-->\n/, '')
    assert.equal(body(largeXml), body(expandedXml), `large${suffix} structure`)
  }
})

test('the brand icon never takes layout space: COMPACT/WIDE beside the controls, FULL floating', async () => {
  const tags = (xml) => [...xml.replace(/<!--[\s\S]*?-->/g, '').matchAll(/<[^>]+>/g)].map((m) => m[0].replace(/\s+/g, ' '))
  const all = [...VARIANTS.flat()]
  for (const file of all) {
    const xml = await read(LAYOUTS + file)
    assert.ok(xml.includes('android:id="@+id/tinyCornerIcon"'), `${file} keeps the icon`)
    assert.doesNotMatch(xml, /widgetCorner/, `${file}: no reserved icon column`)
  }
  // COMPACT/WIDE: the former icon column's width (std 36dp, LLAMA 33/30dp) now belongs to the title/author; the
  // buttons keep exactly their old width (a row [buttons, icon cell of that width]); the icon sits in that cell
  for (const [file, column] of [
    ['media_player_widget.xml', 36],
    ['media_player_widget_llama.xml', 33],
    ['media_player_widget_wide.xml', 36],
    ['media_player_widget_wide_llama.xml', 30]
  ]) {
    const t = tags(await read(LAYOUTS + file))
    const buttons = t.findIndex((x) => x.includes('android:id="@+id/widgetButtonContainer"'))
    const row = t[buttons - 1]
    assert.ok(row.startsWith('<FrameLayout') && !row.includes('android:id'), `${file}: controls row`)
    assert.ok(t[buttons].includes('android:layout_width="match_parent"') && t[buttons].includes(`android:layout_marginEnd="${column}dp"`) && !t[buttons].includes('layout_weight'), `${file}: buttons keep their width (end margin = former column)`)
    const icon = t.find((x) => x.includes('android:id="@+id/tinyCornerIcon"'))
    assert.ok(icon.includes('android:layout_gravity="end|center_vertical"'), `${file}: icon at the row's end`)
    const cell = Number(icon.match(/layout_width="(\d+)dp"/)[1]) + 2 * Number(icon.match(/layout_marginEnd="([\d.]+)dp"/)[1])
    assert.equal(cell, column, `${file}: icon centered in the former column's width`)
    const containerClose = t.indexOf('</LinearLayout>', buttons)
    assert.ok(t[containerClose + 1] === icon && t[containerClose + 2] === '</FrameLayout>', `${file}: icon right after the buttons, closing the controls row`)
    // Title/author (and their block) only keep edge padding no wider than their start inset (8dp), never the icon's width
    const xml = await read(LAYOUTS + file)
    for (const id of ['widgetMediaTitle', 'widgetArtistText']) {
      const at = xml.indexOf(`android:id="@+id/${id}"`)
      const block = xml.slice(xml.lastIndexOf('<LinearLayout', at), at)
      for (const tag of [attrs(xml, id), block]) {
        for (const m of tag.matchAll(/android:(?:layout_margin|padding)(?:End|Right)="(\d+)dp"/g)) assert.ok(Number(m[1]) <= 8, `${file}: ${id} end inset ${m[1]}dp`)
      }
    }
  }
  // COMPACT: the readout's base width is the former column, so the weighted cover keeps its exact width
  for (const [file, column] of [
    ['media_player_widget.xml', 36],
    ['media_player_widget_llama.xml', 33]
  ]) {
    const readout = attrs(await read(LAYOUTS + file), 'widgetReadout')
    assert.ok(readout.includes(`android:layout_width="${column}dp"`) && readout.includes('android:layout_weight="3"'), `${file}: readout base width`)
    assert.ok(attrs(await read(LAYOUTS + file), 'widgetAlbumArt').includes('android:layout_weight="2"'))
  }
  // FULL: the corner icon is the last child of a FrameLayout around widgetContent, top|end at the floating inset
  for (const file of FULL_LAYOUTS) {
    const t = tags(await read(LAYOUTS + file))
    const icon = t.find((x) => x.includes('android:id="@+id/tinyCornerIcon"'))
    for (const a of ['android:layout_gravity="top|end"', 'android:layout_marginTop="@dimen/widget_floating_icon_inset"', 'android:layout_marginEnd="@dimen/widget_floating_icon_inset"']) assert.ok(icon.includes(a), `${file} icon ${a}`)
    const frameOpen = t.findIndex((x) => x.startsWith('<FrameLayout'))
    assert.ok(t[frameOpen + 1].includes('android:id="@+id/widgetContent"'), `${file}: the frame wraps widgetContent`)
    assert.deepEqual(t.slice(-3), [icon, '</FrameLayout>', '</LinearLayout>'], `${file}: icon is the frame's last child`)
    const readout = t.find((x) => x.includes('android:id="@+id/widgetReadout"'))
    assert.ok(readout.includes('android:layout_width="match_parent"') && !readout.includes('layout_weight'), `${file}: readout fills its width`)
  }
  // NORMAL: the readout fills a weighted frame beside the cover; the frame's other child is the panel icon slot
  for (const file of ['media_player_widget_full.xml', 'media_player_widget_full_llama.xml']) {
    const xml = await read(LAYOUTS + file)
    const t = tags(xml)
    const readout = t.findIndex((x) => x.includes('android:id="@+id/widgetReadout"'))
    assert.ok(t[readout - 1].startsWith('<FrameLayout') && t[readout - 1].includes('android:layout_weight="1"'), `${file}: readout frame`)
    const panel = t.find((x) => x.includes('android:id="@+id/tinyCornerIconPanel"'))
    for (const a of ['android:layout_gravity="top|end"', 'android:layout_margin="@dimen/widget_panel_icon_inset"', 'android:visibility="gone"']) assert.ok(panel.includes(a), `${file} panel icon ${a}`)
    assert.ok(attrs(xml, 'tinyCornerIcon').includes('android:visibility="gone"'), `${file}: hidden until the renderer places it`)
  }
  // the insets match FullArtwork
  const dimens = await read('../android/app/src/main/res/values/dimens.xml')
  const kotlin = await read('../android/app/src/main/java/app/absplus/android/widget/FullArtwork.kt')
  for (const [dimen, constant] of [
    ['widget_floating_icon_inset', 'FLOATING_ICON_INSET_DP'],
    ['widget_panel_icon_inset', 'PANEL_ICON_INSET_DP']
  ]) {
    assert.equal(Number(dimens.match(new RegExp(`<dimen name="${dimen}">(\\d+)dp</dimen>`))[1]), Number(kotlin.match(new RegExp(`const val ${constant} = (\\d+)f`))[1]), dimen)
  }
  // the renderer only toggles the FULL icons, inside the FULL-only block, from the plan
  const renderer = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetRenderer.kt')
  const fullBlock = renderer.slice(renderer.indexOf('if (size == WidgetSize.FULL) {'), renderer.indexOf('return views', renderer.indexOf('if (size == WidgetSize.FULL) {')))
  assert.match(fullBlock, /setViewVisibility\(R\.id\.tinyCornerIcon, if \(full\.icon == FullArtwork\.IconSlot\.CORNER\)/)
  assert.match(fullBlock, /setViewVisibility\(R\.id\.tinyCornerIconPanel, if \(full\.icon == FullArtwork\.IconSlot\.PANEL\)/)
  assert.equal(renderer.split('R.id.tinyCornerIcon').length - 1, 2, 'toggled nowhere else')
})

test('COMPACT and WIDE artwork keep their existing sizing (row height, crop) and get no responsive bounds', async () => {
  const expected = {
    'media_player_widget.xml': ['android:layout_width="0dp"', 'android:layout_height="match_parent"', 'android:layout_weight="2"', 'android:scaleType="centerCrop"'],
    'media_player_widget_llama.xml': ['android:layout_width="0dp"', 'android:layout_height="match_parent"', 'android:layout_weight="2"', 'android:scaleType="centerCrop"'],
    'media_player_widget_wide.xml': ['android:layout_width="wrap_content"', 'android:layout_height="match_parent"', 'android:adjustViewBounds="true"', 'android:scaleType="centerCrop"'],
    'media_player_widget_wide_llama.xml': ['android:layout_width="wrap_content"', 'android:layout_height="match_parent"', 'android:adjustViewBounds="true"', 'android:scaleType="centerCrop"']
  }
  for (const [file, attributes] of Object.entries(expected)) {
    const art = attrs(await read(LAYOUTS + file), 'widgetAlbumArt')
    for (const a of attributes) assert.ok(art.includes(a), `${file}: ${a}`)
    assert.doesNotMatch(art, /maxWidth|maxHeight/, `${file} has no artwork cap`)
  }
})

test('provider identity is unchanged: one MediaPlayerWidget receiver with the same metadata', async () => {
  const manifest = await read('../android/app/src/main/AndroidManifest.xml')
  assert.equal([...manifest.matchAll(/android:name="android\.appwidget\.provider"/g)].length, 1)
  assert.match(manifest, /<receiver\s+android:name="MediaPlayerWidget"\s+android:exported="false">/)
  assert.match(manifest, /android:name="android\.appwidget\.provider"\s+android:resource="@xml\/media_player_widget_info"/)
  assert.match(manifest, /android:name="com\.samsung\.android\.appwidget\.provider"\s+android:resource="@xml\/samsung_cover_widget_info"/)
  const info = await read('../android/app/src/main/res/xml/media_player_widget_info.xml')
  assert.match(info, /android:minWidth="275dp"/)
  assert.match(info, /android:updatePeriodMillis="86400000"/) // the existing daily update, unchanged
})

test('widget actions are exactly play/pause, jump back, jump forward and open, on every layout', async () => {
  const renderer = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetRenderer.kt')
  const clicks = [...renderer.matchAll(/setOnClickPendingIntent\(R\.id\.(\w+), actions\.(\w+)\)/g)].map((m) => `${m[1]}=${m[2]}`)
  assert.deepEqual(clicks.sort(), ['widgetBackground=open', 'widgetFastForwardButton=fastForward', 'widgetPlayPauseButton=playPause', 'widgetRewindButton=rewind'])
  for (const action of ['ACTION_PLAY_PAUSE', 'ACTION_FAST_FORWARD', 'ACTION_REWIND']) assert.match(renderer, new RegExp(`buildMediaButtonPendingIntent\\(context, PlaybackStateCompat\\.${action}\\)`))
  assert.match(renderer, /FLAG_UPDATE_CURRENT or PendingIntent\.FLAG_IMMUTABLE/)
})

test('widget code schedules nothing (no alarms, jobs, timers, delayed posts or Chronometer)', async () => {
  const dir = '../android/app/src/main/java/app/absplus/android/'
  const files = ['MediaPlayerWidget.kt', 'widget/WidgetRenderer.kt', 'widget/WidgetSize.kt', 'widget/WidgetTheme.kt', 'widget/WidgetText.kt', 'widget/FullArtwork.kt']
  for (const file of files) {
    assert.doesNotMatch(await read(dir + file), /AlarmManager|WorkManager|JobScheduler|postDelayed|scheduleAtFixedRate|Timer\(|Chronometer|setChronometer/, file)
  }
  for (const [standard, llama] of VARIANTS) for (const file of [standard, llama]) assert.doesNotMatch(await read(LAYOUTS + file), /Chronometer/, file)
})

test('the native allow-list names only real built-in theme ids that have a generated palette', async () => {
  const kotlin = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetTheme.kt')
  const allowList = kotlin.slice(kotlin.indexOf('fun fromThemeId'), kotlin.indexOf('fun current'))
  const ids = [...allowList.matchAll(/"([a-z0-9-]+)" ->/g)].map((m) => m[1])
  assert.deepEqual(ids, ['llama'])
  const palettes = generator.widgetThemes().map((t) => t.id)
  for (const id of ids) {
    assert.equal(engine.resolveThemeId(id), id, `${id} is a built-in`)
    assert.ok(palettes.includes(id), `${id} has a generated palette`)
  }
  assert.match(allowList, /else -> STANDARD/)
})

// --- Phase 2C Gate F: LLAMA widget presentation (text containment, readout and control paint) ---

const { createHash } = require('node:crypto')
const sha256 = async (path) =>
  createHash('sha256')
    .update((await read(path)).replace(/\r\n/g, '\n'))
    .digest('hex')
const RES = '../android/app/src/main/res/'
const WIDGET_SRC = '../android/app/src/main/java/app/absplus/android/'

test('Gate F freeze: standard widget layouts, sizing, artwork planning, provider and renderer are unchanged', async () => {
  // Gate F is LLAMA-only. These hashes pin the files it was not authorized to change (line endings normalized);
  // a deliberate future change updates them together with the reason.
  // Phase 5.1 (lint cleanup): media_player_widget.xml gained content descriptions (the LLAMA layout's: @null for the
  // cover and corner icon, the existing action strings for the three keys) and takes its placeholder "Artist"/"Title"
  // from string resources with the same text; the compiled layout differs only there. app_widget_background.xml moved
  // unchanged from drawable-v21/ (minSdk 24 always matched v21); the unused app_widget_inner_view_background.xml and
  // its unused style were removed.
  const frozen = {
    'layout/media_player_widget.xml': '636cce2873d1a0bb277efa018cc39b0ae58ed40f6d856588c3403cee1e7fc91a',
    'layout/media_player_widget_wide.xml': '45bb68e7bd899fa4d5b36c2dde590180d111fe004c7f2d7d7b389ad2eced62cf',
    'layout/media_player_widget_full.xml': '34c8f9fc8756cd2bef2b743f8d665fd8ef462f8863a741ed0264163b3f36030b',
    'layout/media_player_widget_full_expanded.xml': 'f33a537436ee09a53142d616db19272ed3f245fad59ff98cd0564a220ede9b9c',
    'layout/media_player_widget_full_expanded_large.xml': 'c84e33a140c6413bb4bbb397458296f80bacb2fdb85bac00c4e80f57a7e5b549',
    'drawable/widget_button_bg.xml': '81b7dc37b6f89994fbf022dcc2f59d18aab9af84dac6197b9053d081e9458d55',
    'drawable/widget_progress_default.xml': '290753fd4695f61acfea9c5c4906413cfd108bebb8e54a992e448f2ab53ea9ba',
    'drawable/app_widget_background.xml': '8f7ccb90ee0370347bcbdfe0bc174fad9bbf08a57b168edea6096b8f18c6fd72',
    'xml/media_player_widget_info.xml': '944832659a039ad724946788ac38c06daf5b2b1c0a1b4f9ef11de8deb614b650'
  }
  for (const [file, hash] of Object.entries(frozen)) assert.equal(await sha256(RES + file), hash, `${file} changed`)
  const code = {
    'widget/FullArtwork.kt': '676422c235694a9e2f893a8fe95788950ed66ddab3e59e2a53aab8097e3c7d55',
    'widget/WidgetSize.kt': '8ebc256cdc9c8aa65fb407070f3700c1fce9667c0416b3b6fff27a75827d946f',
    // RICE customization: the renderer reads WidgetTheme.look() (layout family + optional RICE palette) instead of
    // WidgetTheme.current() and calls WidgetPaint.apply() once after building; WidgetPaint does nothing without a RICE
    // palette, so built-in widgets render exactly as before. Layouts, ids, actions, sizing and artwork are unchanged.
    // Offline-first 6.8: State carries the book a waiting queue is on (title, author, cover from AwaitingDisplay) and
    // updateAppWidget takes a whole State; layouts, ids, actions, sizing and artwork bounds are unchanged.
    'widget/WidgetRenderer.kt': '488ef2776cf83b8a3c8b295e1762168a29a0bbb5fe6fde524a2e71045bafaeba',
    'MediaPlayerWidget.kt': '7da12e737c8c666b4e9c5be96125eb6dfa4ef1acbbfb876282d33596bc7033e8'
  }
  for (const [file, hash] of Object.entries(code)) assert.equal(await sha256(WIDGET_SRC + file), hash, `${file} changed`)
})

test('Gate F LLAMA text containment: smaller COMPACT text, no font padding, tighter FULL NORMAL readout spacing', async () => {
  const compact = await read(LAYOUTS + 'media_player_widget_llama.xml')
  assert.match(attrs(compact, 'widgetMediaTitle'), /android:includeFontPadding="false"[\s\S]*android:textSize="13sp"/)
  assert.match(attrs(compact, 'widgetArtistText'), /android:includeFontPadding="false"[\s\S]*android:textSize="12sp"/)
  const wide = await read(LAYOUTS + 'media_player_widget_wide_llama.xml')
  for (const id of ['widgetMediaTitle', 'widgetArtistText']) assert.match(attrs(wide, id), /android:includeFontPadding="false"/, `WIDE ${id}`)
  const normal = await read(LAYOUTS + 'media_player_widget_full_llama.xml')
  assert.match(attrs(normal, 'widgetReadout'), /android:paddingHorizontal="8dp"\s+android:paddingVertical="4dp"/)
  assert.match(attrs(normal, 'widgetArtistText'), /android:layout_marginTop="0dp"/)
  assert.match(attrs(normal, 'widgetTimeRow'), /android:layout_marginTop="4dp"/)
  for (const id of ['widgetMediaTitle', 'widgetArtistText', 'widgetElapsedText', 'widgetRemainingText']) assert.match(attrs(normal, id), /android:includeFontPadding="false"/, `NORMAL ${id}`)
  // Text sizes and line limits are the shared ones (readable, never shrunk to force a fit)
  assert.match(attrs(normal, 'widgetMediaTitle'), /android:maxLines="2"[\s\S]*android:textSize="16sp"/)
  // EXPANDED and LARGE never clipped, so their spacing is untouched
  for (const file of ['media_player_widget_full_expanded_llama.xml', 'media_player_widget_full_expanded_large_llama.xml']) {
    const xml = await read(LAYOUTS + file)
    assert.doesNotMatch(xml, /includeFontPadding/, file)
    assert.match(attrs(xml, 'widgetReadout'), /android:padding="8dp"/, file)
  }
})

const KEY_IDS = ['widgetRewindButton', 'widgetPlayPauseButton', 'widgetFastForwardButton']

test('Gate F LLAMA readout and controls: accent time readouts, amber transport glyphs, 4dp keys and readout', async () => {
  const llamaLayouts = VARIANTS.map(([, llama]) => llama)
  for (const file of llamaLayouts) {
    const xml = await read(LAYOUTS + file)
    // The accent is reserved for readouts; the transport glyphs are the app's lit key legend (the playback amber until
    // the final consistency pass)
    for (const id of KEY_IDS) assert.match(attrs(xml, id), /android:tint="@color\/widget_llama_legend"/, `${file} ${id}`)
    if (xml.includes('android:id="@+id/widgetElapsedText"')) {
      for (const id of ['widgetElapsedText', 'widgetRemainingText']) assert.match(attrs(xml, id), /android:textColor="@color\/widget_llama_accent"/, `${file} ${id}`)
    }
    // Titles stay neutral and authors muted
    assert.match(attrs(xml, 'widgetMediaTitle'), /android:textColor="@color\/widget_llama_text"/, file)
    assert.match(attrs(xml, 'widgetArtistText'), /android:textColor="@color\/widget_llama_text_muted"/, file)
  }
  const button = await read(RES + 'drawable/widget_llama_button.xml')
  assert.equal((button.match(/<corners android:radius="4dp" \/>/g) || []).length, 6)
  assert.doesNotMatch(button, /radius="(?!4dp)/)
  const readout = await read(RES + 'drawable/widget_llama_readout.xml')
  assert.equal((readout.match(/<corners android:radius="4dp" \/>/g) || []).length, 3)
  // The chassis still follows the launcher's widget radius; the artwork frame stays square
  assert.match(await read(RES + 'drawable/widget_llama_chassis.xml'), /\?attr\/appWidgetRadius/)
  assert.doesNotMatch(await read(RES + 'drawable/widget_llama_artwork_frame.xml'), /corners/)
})

// --- Phase 4D: LLAMA widget control-finish parity (resource-level paint only) ---

const LLAMA_FILES = VARIANTS.map(([, llama]) => llama)
const norm = (text) => text.replace(/\r\n/g, '\n')
const generatedColors = async () => {
  const xml = await read(RES + 'values/widget_theme_colors.xml')
  return Object.fromEntries([...xml.matchAll(/<color name="widget_llama_([a-z_]+)">#([0-9A-F]{6})<\/color>/g)].map(([, n, h]) => [n, [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16))]))
}
const luminance = ([r, g, b]) => {
  const lin = (c) => ((c /= 255) <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
}
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

test('Phase 4D secondary-key colors are generated from the LLAMA tokens with fixed blend factors', async () => {
  const t = engine.getTheme('llama').tokens
  const colors = await generatedColors()
  const derived = generator.keyColors(t)
  assert.deepEqual(
    derived.map(([name]) => name),
    ['key_face', 'key_lit', 'key_shade', 'key_pressed_shade']
  )
  for (const [name, rgb] of derived) assert.deepEqual(colors[name], rgb, name)
  // Only the generator may introduce them: the resource file is checked byte-for-byte against render() elsewhere
  const standardRes = norm(await read(RES + 'values/colors.xml'))
  assert.doesNotMatch(standardRes, /key_face|key_lit|key_shade/)
})

test('Phase 4D the key face is a steel face set in the steel housing and the legends clear contrast with margin', async () => {
  const c = await generatedColors()
  const { face, lit, shade, key_pressed_shade: pressed } = { face: c.key_face, lit: c.key_lit, shade: c.key_shade, key_pressed_shade: c.key_pressed_shade }
  // Final consistency pass: steel-blue faces (the panel steel) in a steel housing that runs from the raised steel to the
  // navy base. The lit top stays below the housing's raised top and above its base, so the key reads as steel set into
  // the plate, separated by its dark socket ring and lit bevel rather than by a darker face
  assert.deepEqual(face, c.content, 'the face is the panel steel')
  assert.ok(luminance(lit) < luminance(c.raised) && luminance(lit) > luminance(c.base), 'the lit top sits between the housing top and base')
  const step = contrast(lit, c.raised)
  assert.ok(step >= 1.1 && step <= 2, `lit top vs housing top ${step.toFixed(2)}:1 stays a visible, restrained step`)
  assert.ok(contrast(c.edge_dark, face) >= 1.5, 'the dark socket ring is distinct from the face')
  // Sheen order: lit top > face > shaded bottom; pressed is the deepest
  assert.ok(luminance(lit) > luminance(face) && luminance(face) > luminance(shade) && luminance(shade) > luminance(pressed))
  // The legend tint on every resting and pressed face (worst case = the lightest face behind the glyph)
  for (const [label, face_] of [
    ['lit', lit],
    ['face', face],
    ['shade', shade],
    ['pressed shade', pressed]
  ])
    assert.ok(contrast(c.legend, face_) >= 4.5, `legend on ${label}`)
  // Primary Play/Pause shares this face since Phase 4H (see the Phase 4H tests for its stricter 7:1 target)
})

test('Phase 4D rewind and fast-forward are dark keys with amber legends; Play/Pause keeps its primary key drawable', async () => {
  for (const file of LLAMA_FILES) {
    const xml = await read(LAYOUTS + file)
    for (const id of ['widgetRewindButton', 'widgetFastForwardButton']) {
      const a = attrs(xml, id)
      assert.match(a, /android:background="@drawable\/widget_llama_button"/, `${file} ${id}`)
      assert.match(a, /android:tint="@color\/widget_llama_legend"/, `${file} ${id}`)
    }
    const play = attrs(xml, 'widgetPlayPauseButton')
    assert.match(play, /android:background="@drawable\/widget_llama_button_primary"/, file)
    assert.match(play, /android:tint="@color\/widget_llama_legend"/, file)
    // Amber stays a control color: the legend is on exactly the three transport keys in each layout, never on text,
    // artwork or the brand icon (the played amber stays in the progress drawable)
    assert.equal((xml.match(/widget_llama_legend/g) || []).length, 3, `${file} amber is on exactly the three transport keys`)
    assert.doesNotMatch(xml, /widget_llama_played/, file)
  }
})

test('Phase 4D key drawables: dark gradient face for the secondary key; generated colors only, 4dp corners', async () => {
  const secondary = norm(await read(RES + 'drawable/widget_llama_button.xml'))
  const primary = norm(await read(RES + 'drawable/widget_llama_button_primary.xml'))
  // Secondary: the generated key colors, a sheen gradient at rest, a cut-in gradient while pressed, no steel fill
  for (const name of ['widget_llama_key_lit', 'widget_llama_key_face', 'widget_llama_key_shade', 'widget_llama_key_pressed_shade']) assert.match(secondary, new RegExp(name), name)
  assert.doesNotMatch(secondary, /widget_llama_raised|widget_llama_content/)
  assert.match(secondary, /android:state_pressed="true"/)
  assert.equal((secondary.match(/<gradient/g) || []).length, 2, 'one gradient face per state')
  // Both keys keep the 4dp corners (the primary's own structure is checked by the Phase 4H tests)
  for (const xml of [secondary, primary]) assert.ok([...xml.matchAll(/<corners android:radius="([^"]+)"/g)].every((m) => m[1] === '4dp'))
  // Every color the key drawables use is a generated LLAMA color
  for (const xml of [secondary, primary]) for (const [, ref] of xml.matchAll(/"@color\/([a-z_0-9]+)"/g)) assert.match(ref, /^widget_llama_/, ref)
  assert.doesNotMatch(secondary + primary, /#[0-9A-Fa-f]{6,8}/, 'no hard-coded colors')
})

test('Phase 4D key geometry attributes are frozen in every LLAMA layout', async () => {
  // layout -> [padding dp, margin dp]: the values before Phase 4D. The three keys share width 0dp (weighted) and full height
  const expected = {
    'media_player_widget_llama.xml': [7, 3],
    'media_player_widget_wide_llama.xml': [6, 2],
    'media_player_widget_full_llama.xml': [10, 4],
    'media_player_widget_full_expanded_llama.xml': [10, 4],
    'media_player_widget_full_expanded_large_llama.xml': [16, 4]
  }
  for (const file of LLAMA_FILES) {
    const xml = await read(LAYOUTS + file)
    for (const id of KEY_IDS) {
      const a = attrs(xml, id)
      assert.match(a, new RegExp(`android:padding="${expected[file][0]}dp"`), `${file} ${id} padding`)
      assert.match(a, new RegExp(`android:layout_margin="${expected[file][1]}dp"`), `${file} ${id} margin`)
      assert.match(a, /android:layout_width="0dp"/, `${file} ${id} width`)
      assert.match(a, /android:layout_height="match_parent"/, `${file} ${id} height`)
    }
  }
})

test('Phase 4D standard widget layouts and drawables are untouched by the LLAMA key finish', async () => {
  for (const [standard] of VARIANTS) {
    const xml = await read(LAYOUTS + standard)
    assert.doesNotMatch(xml, /llama|key_face/, standard)
    for (const id of KEY_IDS) assert.match(attrs(xml, id), /android:background="@drawable\/widget_button_bg"/, `${standard} ${id}`)
  }
  assert.doesNotMatch(norm(await read(RES + 'drawable/widget_button_bg.xml')), /llama|key_face/)
  // The protected widget architecture is not touched by this finish: no key color or drawable is set from Kotlin
  const renderer = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetRenderer.kt')
  assert.doesNotMatch(renderer, /R\.(drawable|color)\.widget_llama|key_face|setColorFilter|setBackgroundResource/)
})

// --- Phase 4H: LLAMA widget primary Play/Pause key (resource-level paint only) ---

const layers = (xml, state) => {
  const block = state === 'pressed' ? xml.slice(xml.indexOf('android:state_pressed="true"'), xml.indexOf('</layer-list>')) : xml.slice(xml.lastIndexOf('<layer-list>'))
  return [...block.matchAll(/<item([^>]*)>\s*<shape[^>]*>\s*<corners android:radius="4dp" \/>\s*(?:<solid android:color="@color\/([a-z_0-9]+)" \/>|<gradient([^>]*)\/>)/g)].map((m) => {
    const inset = Object.fromEntries(['left', 'top', 'right', 'bottom'].map((k) => [k, Number((m[1].match(new RegExp(`android:${k}="(\\d+)dp"`)) || [0, 0])[1])]))
    return { inset: [inset.left, inset.top, inset.right, inset.bottom].join(','), fill: m[2] || [...m[3].matchAll(/@color\/([a-z_0-9]+)/g)].map((c) => c[1]).join('>') }
  })
}

test('Phase 4H widget primary key: dark face with the primary bezel (return ring, channel, inner edge), inside its own bounds', async () => {
  const primary = norm(await read(RES + 'drawable/widget_llama_button_primary.xml'))
  // Resting, outside in: lit return ring, dark channel, lit top/left (full-strength edge since the final consistency pass)
  // and dark bottom/right inner edge, sheen face
  assert.deepEqual(layers(primary, 'rest'), [
    { inset: '0,0,0,0', fill: 'widget_llama_edge_light_35' },
    { inset: '1,1,1,1', fill: 'widget_llama_edge_dark' },
    { inset: '2,2,3,3', fill: 'widget_llama_edge_light' },
    { inset: '3,3,3,3', fill: 'widget_llama_key_face>widget_llama_key_shade>widget_llama_key_lit' }
  ])
  // Pressed: ring and channel stay, the inner edge inverts (dark top/left, lit bottom/right), the face is cut in
  assert.deepEqual(layers(primary, 'pressed'), [
    { inset: '0,0,0,0', fill: 'widget_llama_edge_light_35' },
    { inset: '1,1,1,1', fill: 'widget_llama_edge_dark' },
    { inset: '3,3,2,2', fill: 'widget_llama_edge_light_35' },
    { inset: '3,3,3,3', fill: 'widget_llama_key_face>widget_llama_key_pressed_shade' }
  ])
  // The face is the secondary key's dark face (same generated colors), never the old lighter steel
  assert.doesNotMatch(primary, /widget_llama_raised|widget_llama_content|widget_llama_text/)
  // Layer insets only paint inside the view: the key's padding is explicit in every layout, so no inset reaches its bounds
  const secondary = norm(await read(RES + 'drawable/widget_llama_button.xml'))
  assert.equal(layers(secondary, 'rest').length, 3, 'the secondary key keeps its single bevel (no ring)')
  for (const file of LLAMA_FILES) assert.match(attrs(await read(LAYOUTS + file), 'widgetPlayPauseButton'), /android:padding="\d+dp"/, file)
})

test('Phase 4H widget primary glyph: amber, at least 7:1 on every face point, existing bitmap assets and padding', async () => {
  const c = await generatedColors()
  for (const [label, face] of [
    ['lit top', c.key_lit],
    ['face', c.key_face],
    ['shade', c.key_shade],
    ['pressed shade', c.key_pressed_shade]
  ])
    assert.ok(contrast(c.legend, face) >= 7, `legend on ${label}: ${contrast(c.legend, face).toFixed(2)}`)
  for (const file of LLAMA_FILES) {
    const play = attrs(await read(LAYOUTS + file), 'widgetPlayPauseButton')
    assert.match(play, /android:tint="@color\/widget_llama_legend"/, file)
    assert.match(play, /android:src="@drawable\/ic_media_play_dark"/, file)
  }
  // The renderer still swaps the same play/pause bitmaps and sets no tint or color from Kotlin
  const renderer = await read('../android/app/src/main/java/app/absplus/android/widget/WidgetRenderer.kt')
  assert.match(renderer, /val playPauseResource = if \(state\.isPlaying\) androidx\.mediarouter\.R\.drawable\.ic_media_pause_dark else androidx\.mediarouter\.R\.drawable\.ic_media_play_dark/)
  assert.doesNotMatch(renderer, /setColorFilter|setInt\([^)]*[Tt]int/)
})

// --- Final consistency pass (2026-10-10): the app's steel material on the LLAMA widget (resource-level paint only) ---

test('Final pass widget: steel housing, socketed steel keys and a recessed artwork lip, all inside their own bounds', async () => {
  const chassis = norm(await read(RES + 'drawable/widget_llama_chassis.xml'))
  // Housing: dark outer edge, lit top/left bevel, then the steel face lit from the top (raised > panel > base)
  assert.match(chassis, /android:startColor="@color\/widget_llama_raised"/)
  assert.match(chassis, /android:centerColor="@color\/widget_llama_content"/)
  assert.match(chassis, /android:endColor="@color\/widget_llama_base"/)
  // A narrow (1dp) full-strength lit top/left edge on the housing, like the app's plate edge
  assert.match(chassis, /<item android:bottom="1dp" android:right="1dp">\s*<shape[^>]*>\s*<corners[^>]*\/>\s*<solid android:color="@color\/widget_llama_edge_light" \/>/)
  assert.match(chassis, /\?attr\/appWidgetRadius/)
  // Secondary key, resting: dark socket ring, narrow lit top/left bevel (the socket is the dark bottom/right edge), steel face
  const secondary = norm(await read(RES + 'drawable/widget_llama_button.xml'))
  assert.deepEqual(layers(secondary, 'rest'), [
    { inset: '0,0,0,0', fill: 'widget_llama_edge_dark' },
    { inset: '1,1,2,2', fill: 'widget_llama_edge_light' },
    { inset: '2,2,2,2', fill: 'widget_llama_key_face>widget_llama_key_shade>widget_llama_key_lit' }
  ])
  // Pressed: the bevel inverts (lit bottom/right), the face is cut in; the socket stays
  assert.deepEqual(layers(secondary, 'pressed'), [
    { inset: '0,0,0,0', fill: 'widget_llama_edge_dark' },
    { inset: '2,2,1,1', fill: 'widget_llama_edge_light_35' },
    { inset: '2,2,2,2', fill: 'widget_llama_key_face>widget_llama_key_pressed_shade' }
  ])
  // Artwork: a steel lip and a near-black channel inside the view's 2dp padding (the cover is never covered), square
  const frame = norm(await read(RES + 'drawable/widget_llama_artwork_frame.xml'))
  for (const color of ['widget_llama_edge_dark', 'widget_llama_edge_light_55', 'widget_llama_recessed']) assert.match(frame, new RegExp(`@color/${color}`))
  assert.doesNotMatch(frame, /corners/)
  // Paint only: no bitmap grain, no hard-coded colors, generated or LLAMA colors only
  for (const xml of [chassis, secondary, frame]) {
    assert.doesNotMatch(xml, /<bitmap|android:src=|#[0-9A-Fa-f]{6,8}/)
    for (const [, ref] of xml.matchAll(/"@color\/([a-z_0-9]+)"/g)) assert.match(ref, /^widget_llama_/, ref)
  }
})
