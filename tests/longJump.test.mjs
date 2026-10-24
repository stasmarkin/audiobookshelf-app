// Long jumps: a second, longer jump in each direction, Off by default, shown only in the full player
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const engine = require('../theme/engine.js')
const presets = require('../theme/presets.js')
const read = (p) => readFile(new URL(p, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

const utilSource = await read('../utils/longJump.js')
const { LONG_JUMP_SECONDS_OPTIONS, normalizeLongJumpTime } = await import(`data:text/javascript;base64,${Buffer.from(utilSource).toString('base64')}`)

const playerComponent = async () => {
  const source = await read('../components/app/AudioPlayer.vue')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const seeks = []
  const AbsAudioPlayer = { seekBackward: ({ value }) => seeks.push(['backward', value]), seekForward: ({ value }) => seeks.push(['forward', value]) }
  const sandbox = { coverPresentation: {}, playerLayout: {}, Capacitor: {}, AbsAudioPlayer, Dialog: {}, getAverageColorFromCoverUrl: async () => null, WrappingMarquee: function () {}, jumpLabelMixin: {}, console }
  vm.runInNewContext(script, sandbox)
  return { component: sandbox.component, seeks, template: source.slice(0, source.indexOf('</template>')) }
}

test('Long jump options: Off, then 15/20/30/45 seconds, then minutes up to 30', () => {
  assert.deepEqual(LONG_JUMP_SECONDS_OPTIONS, [0, 15, 20, 30, 45, 60, 120, 180, 300, 600, 900, 1800])
})

test('A stored long jump is whole positive seconds, anything else is Off', () => {
  for (const value of [300, '300', 15]) assert.equal(normalizeLongJumpTime(value), Number(value))
  for (const value of [undefined, null, '', 0, -60, 12.5, NaN, 'abc', {}]) assert.equal(normalizeLongJumpTime(value), 0, String(value))
})

test('Unsupported stored durations cannot become arbitrary long-jump bridge values', () => {
  for (const value of [true, 1, 59, 86400, 2147483647, Infinity]) assert.equal(normalizeLongJumpTime(value), 0, String(value))
  for (const seconds of LONG_JUMP_SECONDS_OPTIONS) assert.equal(normalizeLongJumpTime(seconds), seconds)
})

test('Store getters read the long jumps from device settings, Off for settings saved before they existed', async () => {
  const source = (await read('../store/index.js')).replace(/^import .*$/gm, '').replace(/^export const /gm, 'exports.')
  const sandbox = { exports: {}, normalizeLongJumpTime, Network: {}, AbsAudioPlayer: {}, PlayMethod: {} }
  vm.runInNewContext(source, sandbox)
  const { getters } = sandbox.exports
  for (const deviceData of [null, { deviceSettings: null }, { deviceSettings: { jumpForwardTime: 10, jumpBackwardsTime: 10 } }]) {
    assert.equal(getters.getLongJumpForwardTime({ deviceData }), 0)
    assert.equal(getters.getLongJumpBackwardsTime({ deviceData }), 0)
  }
  const deviceData = { deviceSettings: { longJumpForwardTime: 60, longJumpBackwardsTime: 300 } }
  assert.equal(getters.getLongJumpForwardTime({ deviceData }), 60)
  assert.equal(getters.getLongJumpBackwardsTime({ deviceData }), 300)
})

test('Full player row: chapter start, long back, short back, play, short forward, long forward, chapter end', async () => {
  const { template } = await playerComponent()
  const row = template.slice(template.indexOf('id="playerControls"'), template.indexOf('<!-- Chapter progress bar -->'))
  // One chevron family in the full player; the collapsed keys have no duration label and keep the circular arrows
  const glyphs = [...row.matchAll(/>(first_page|keyboard_double_arrow_left|\{\{ showFullscreen \? 'chevron_left' : 'replay' \}\}|\{\{ showFullscreen \? 'chevron_right' : 'forward_media' \}\}|keyboard_double_arrow_right|last_page)</g)].map((m) => m[1])
  assert.deepEqual(glyphs, ['first_page', 'keyboard_double_arrow_left', "{{ showFullscreen ? 'chevron_left' : 'replay' }}", "{{ showFullscreen ? 'chevron_right' : 'forward_media' }}", 'keyboard_double_arrow_right', 'last_page'])
  assert.ok(row.indexOf('play-btn') > row.indexOf("'chevron_left'") && row.indexOf('play-btn') < row.indexOf("'chevron_right'"))
  // Long keys are transport keys (same theme paint and disabled state) with their own dynamic duration
  assert.match(row, /<div v-if="showLongJumpBackwards" class="player-key jump-icon [^"]*"[^>]*@click\.stop="longJumpBackwards">/)
  assert.match(row, /<div v-if="showLongJumpForward" class="player-key jump-icon [^"]*"[^>]*@click\.stop="longJumpForward">/)
  assert.match(row, /class="jump-label [^"]*">\{\{ longJumpBackwardsLabel \}\}</)
  assert.match(row, /class="jump-label [^"]*">\{\{ longJumpForwardLabel \}\}</)
  assert.match(row, /\{ 'long-jumps': showLongJumpBackwards \|\| showLongJumpForward \}/)
})

test('With seven keys every transport key may shrink, so none is pushed off a narrow screen; the primary keeps its box', async () => {
  const player = await read('../components/app/AudioPlayer.vue')
  const style = player.slice(player.indexOf('<style>'))
  assert.match(style, /\.fullscreen #playerControls \.long-jumps \.player-key \{\s*flex: 0 1 auto;\s*min-width: 0;\s*\}/)
  assert.doesNotMatch(style, /\.long-jumps \.play-btn/)
  assert.match(style, /\.fullscreen #playerControls \.play-btn \{\s*height: 65px;\s*width: 65px;\s*min-width: 65px;/)
})

test('Every full-player glyph has one size and centers on Play: room for the duration above it, none on LLAMA', async () => {
  const player = await read('../components/app/AudioPlayer.vue')
  const style = player.slice(player.indexOf('<style>'))
  assert.match(style, /\.fullscreen #playerControls \.jump-icon > \.material-symbols \{\s*font-size: 2rem;\s*\}/)
  assert.match(style, /\.fullscreen #playerControls \.next-icon \{\s*font-size: 2rem;\s*\}/)
  // The top padding mirrors the duration's box (text-[10px] leading-tight = 12.5px, 2px gap), so the glyph sits mid-key
  assert.match(player, /class="jump-label text-\[10px\] font-semibold leading-tight"/)
  assert.match(style, /#playerControls \.jump-label \{\s*margin-top: 2px;\s*\}/)
  assert.match(style, /\.fullscreen #playerControls \.jump-icon \{\s*font-size: 2\.4rem;\s*padding-top: 14\.5px;\s*\}/)
  assert.equal(10 * 1.25 + 2, 14.5)
  // The duration stays in the layout: never taken out of flow, never clipped
  assert.doesNotMatch(style, /\.jump-label \{[^}]*position: absolute/)
  assert.doesNotMatch(style, /\.player-key \{[^}]*overflow/)
  const root = engine.themeSelector('llama')
  const rules = presets.presentationRules([engine.getTheme('llama')])
  assert.deepEqual({ ...rules[`${root} .fullscreen #playerControls .jump-icon`] }, { 'padding-top': '0' })
  assert.deepEqual({ ...rules[`${root} .fullscreen #playerControls .jump-label`] }, { 'font-weight': '700' })
})

test('Long jump keys show only in the unlocked full player, per direction, when that long jump is on', async () => {
  const { component } = await playerComponent()
  const c = component.computed
  const ctx = (showFullscreen, lockUi, back, forward) => ({
    showFullscreen,
    playerSettings: { lockUi },
    longJumpBackwardsTime: back,
    longJumpForwardTime: forward
  })
  assert.equal(c.showLongJumpBackwards.call(ctx(true, false, 300, 0)), true)
  assert.equal(c.showLongJumpForward.call(ctx(true, false, 300, 0)), false)
  assert.equal(c.showLongJumpForward.call(ctx(true, false, 0, 60)), true)
  for (const [full, lock] of [[false, false], [true, true], [false, true]]) {
    assert.equal(c.showLongJumpBackwards.call(ctx(full, lock, 300, 60)), false, `fullscreen ${full} lock ${lock}`)
    assert.equal(c.showLongJumpForward.call(ctx(full, lock, 300, 60)), false, `fullscreen ${full} lock ${lock}`)
  }
})

test('Long jump keys seek by the long jump length and do nothing while loading', async () => {
  const { component, seeks } = await playerComponent()
  const m = component.methods
  const ctx = { $hapticsImpact: async () => {}, showLoadingState: false, longJumpBackwardsTime: 300, longJumpForwardTime: 60 }
  await m.longJumpBackwards.call(ctx)
  await m.longJumpForward.call(ctx)
  ctx.showLoadingState = true
  await m.longJumpBackwards.call(ctx)
  await m.longJumpForward.call(ctx)
  assert.deepEqual(seeks, [
    ['backward', 300],
    ['forward', 60]
  ])
})

test('Seven keys fit a 360px-wide LLAMA full player: the secondary keys shrink, the primary keeps its box', () => {
  const root = engine.themeSelector('llama')
  const rules = presets.presentationRules([engine.getTheme('llama')])
  const bank = rules[`${root} .fullscreen #playerControls > div.long-jumps`]
  const key = rules[`${root} .fullscreen #playerControls .long-jumps .player-key`]
  assert.deepEqual({ ...bank }, { gap: '4px' })
  assert.deepEqual({ ...key }, { flex: '0 1 60px', 'min-width': '0' })
  // 360px viewport minus the row's 24px side padding; the 65px primary and six gaps leave each key room for its 2rem glyph.
  // Narrower screens still keep all seven keys on screen (they shrink further; the glyph may overflow its key)
  const keyWidth = (360 - 48 - 65 - 6 * 4) / 6
  assert.ok(keyWidth >= 32, String(keyWidth))
})

test('Settings offer both long jumps with an Off label; strings exist in en-us and ru', async () => {
  const settings = await read('../pages/settings.vue')
  assert.match(settings, /\$strings\.LabelLongJumpBackwardsTime/)
  assert.match(settings, /\$strings\.LabelLongJumpForwardsTime/)
  assert.match(settings, /LONG_JUMP_SECONDS_OPTIONS\.map/)
  assert.match(settings, /normalizeLongJumpTime\(seconds\) \? this\.getJumpLabel\(seconds\) : this\.\$strings\.LabelOff/)
  for (const locale of ['en-us', 'ru']) {
    const strings = JSON.parse(await read(`../strings/${locale}.json`))
    for (const key of ['LabelLongJumpBackwardsTime', 'LabelLongJumpForwardsTime', 'LabelOff']) assert.ok(strings[key], `${locale} ${key}`)
  }
})
