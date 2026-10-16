// "Update once a minute" (player menu): the player keeps polling the native position every second, but draws H:MM times,
// stepped bars and a sleep timer in minutes, and writes to the page only when one of those coarse values changes (each
// write is a visible refresh on e-paper). Off by default, where every second is drawn exactly as before.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const clock = await import(`data:text/javascript;base64,${Buffer.from(await read('../utils/playerClock.js')).toString('base64')}`)
const { coarseTime, coarseClock, sleepRemainingLabel, createPainter } = clock

test('coarseTime: whole steps from an origin, never before it', () => {
  assert.equal(coarseTime(0), 0)
  assert.equal(coarseTime(59.9), 0)
  assert.equal(coarseTime(60), 60)
  assert.equal(coarseTime(3725.4), 3720)
  assert.equal(coarseTime(179, 90), 90, 'a minute of listening at 1.5x is 90 s of media')
  assert.equal(coarseTime(180, 90), 180)
  assert.equal(coarseTime(250, 120), 240)
  assert.equal(coarseTime(1037.5, 60, 1000.25), 1000.25, 'counted from a fractional chapter start')
  assert.equal(coarseTime(1061, 60, 1000.25), 1060.25)
  assert.equal(coarseTime(990, 60, 1000.25), 1000.25, 'never before the origin')
  for (const bad of [NaN, -5, Infinity]) assert.equal(coarseTime(bad), 0, String(bad))
  assert.equal(coarseTime(125, 0), 120, 'a zero step counts as a minute')
})

test('coarseClock: H:MM, elapsed rounds down, remaining rounds up', () => {
  assert.equal(coarseClock(0), '0:00')
  assert.equal(coarseClock(59), '0:00')
  assert.equal(coarseClock(60), '0:01')
  assert.equal(coarseClock(3725), '1:02')
  assert.equal(coarseClock(36000), '10:00')
  assert.equal(coarseClock(3725, true), '1:03')
  assert.equal(coarseClock(60, true), '0:01')
  assert.equal(coarseClock(1, true), '0:01')
  assert.equal(coarseClock(0, true), '0:00')
  assert.equal(coarseClock(5400.0000001, true), '1:30', 'float noise from rate-scaled steps')
  assert.equal(coarseClock(5399.9999999), '1:30', 'float noise from rate-scaled steps')
  assert.equal(coarseClock(NaN), '0:00')
  assert.equal(coarseClock(-10), '0:00')
})

test('sleepRemainingLabel: minutes only when coarse; the previous seconds below 92 s otherwise', () => {
  assert.equal(sleepRemainingLabel(0, true), '0s')
  assert.equal(sleepRemainingLabel(45, true), '1m')
  assert.equal(sleepRemainingLabel(61, true), '2m')
  assert.equal(sleepRemainingLabel(45, false), '45s')
  assert.equal(sleepRemainingLabel(91, false), '91s')
  assert.equal(sleepRemainingLabel(120, false), '2m')
})

test('painter: writes a changed value, skips an unchanged one, always writes when asked, and is per element', () => {
  const paint = createPainter()
  const writes = []
  const write = (el, v) => writes.push([el.id, v])
  const a = { id: 'a' }
  const b = { id: 'b' }
  assert.equal(paint(a, 'width', '10%', write), true)
  assert.equal(paint(a, 'width', '10%', write), false)
  assert.equal(paint(a, 'left', '10%', write), true, 'keys are separate')
  assert.equal(paint(b, 'width', '10%', write), true, 'a new element is always painted')
  assert.equal(paint(a, 'width', '10%', write, true), true, 'always')
  assert.equal(paint(a, 'width', '11%', write), true)
  assert.equal(paint(null, 'width', '12%', write), false)
  assert.deepEqual(writes, [
    ['a', '10%'],
    ['a', '10%'],
    ['b', '10%'],
    ['a', '10%'],
    ['a', '11%']
  ])
})

// AudioPlayer's own computed properties and methods, run against a minimal component context with counting elements
async function loadPlayer() {
  const source = await read('../components/app/AudioPlayer.vue')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { ...clock, AbsAudioPlayer: { seek: () => {}, setPlaybackSpeed: () => {} }, Capacitor: {}, Dialog: {}, jumpLabelMixin: {}, console: { log() {}, error() {}, warn() {} } }
  vm.runInNewContext(script, sandbox)
  return sandbox.component
}

const countingElement = (counts, name) => {
  const style = {}
  for (const prop of ['width', 'left']) {
    let value = ''
    Object.defineProperty(style, prop, {
      get: () => value,
      set: (v) => {
        value = v
        counts[name] = (counts[name] || 0) + 1
      }
    })
  }
  let text = '0:00'
  return {
    style,
    clientWidth: 360,
    classList: { add() {}, remove() {} },
    get innerText() {
      return text
    },
    set innerText(v) {
      text = v
      counts[name] = (counts[name] || 0) + 1
    }
  }
}

const secondsToTimestamp = (seconds) => {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`
}

function makePlayer(component, { everyMinute, rate = 1, chapters = null, scaled = true, duration = 36000 }) {
  const counts = {}
  const ctx = { ...component.data.call({}) }
  ctx.playerSettings.updateEveryMinute = everyMinute
  ctx.playerSettings.useChapterTrack = !!chapters
  ctx.playerSettings.scaleElapsedTimeBySpeed = scaled
  ctx.playbackSession = chapters ? { chapters } : null
  ctx.$eventBus = { $emit() {} }
  ctx.totalDuration = duration
  ctx.currentPlaybackRate = rate
  ctx.sleepTimeRemaining = 0
  ctx.$emit = () => {}
  ctx.$secondsToTimestamp = secondsToTimestamp
  ctx.$refs = {}
  for (const ref of ['currentTimestamp', 'playedTrack', 'bufferedTrack', 'trackCursor', 'trackCursorHit', 'track']) ctx.$refs[ref] = countingElement(counts, ref)
  for (const [name, fn] of Object.entries(component.computed)) Object.defineProperty(ctx, name, { get: () => fn.call(ctx) })
  for (const [name, fn] of Object.entries(component.methods)) ctx[name] = fn.bind(ctx)
  component.created.call(ctx)
  return { ctx, counts }
}

// One poll per second, as startPlayInterval does, collecting the template readouts too
function play(ctx, { from, seconds, rate = 1 }) {
  const remaining = []
  for (let s = 0; s < seconds; s++) {
    ctx.currentTime = from + s * rate
    ctx.bufferedTime = ctx.currentTime + 30
    ctx.timeupdate()
    remaining.push(ctx.timeRemainingPretty)
  }
  return remaining
}
const changes = (values) => values.filter((v, i) => i === 0 || v !== values[i - 1]).length

test('AudioPlayer, off (default): every second is drawn as before', async () => {
  const component = await loadPlayer()
  assert.equal(component.data.call({}).playerSettings.updateEveryMinute, false)
  const { ctx, counts } = makePlayer(component, { everyMinute: false })
  play(ctx, { from: 1000, seconds: 300 })
  for (const ref of ['currentTimestamp', 'playedTrack', 'bufferedTrack', 'trackCursor', 'trackCursorHit']) assert.equal(counts[ref], 300, ref)
  assert.equal(ctx.$refs.currentTimestamp.innerText, '21:39')
})

test('AudioPlayer, once a minute: the page is written only when the coarse value changes', async () => {
  const component = await loadPlayer()
  const { ctx, counts } = makePlayer(component, { everyMinute: true })
  // 16:40 to 21:39: the minute turns at 17, 18, 19, 20 and 21, plus the first paint
  const remaining = play(ctx, { from: 1000, seconds: 300 })
  for (const ref of ['currentTimestamp', 'playedTrack', 'bufferedTrack']) assert.equal(counts[ref], 6, ref)
  // The thumb is placed in whole pixels: a minute of a 10-hour book is 0.6px of this track, so it moves even less often
  for (const ref of ['trackCursor', 'trackCursorHit']) assert.ok(counts[ref] >= 1 && counts[ref] <= 6, `${ref}: ${counts[ref]}`)
  assert.equal(ctx.$refs.currentTimestamp.innerText, '0:21')
  assert.equal(ctx.$refs.playedTrack.style.width, `${(1260 / 36000) * 100}%`)
  assert.equal(changes(remaining), 6, 'the remaining time changes at the same moments')
  assert.equal(remaining.at(-1), '-9:39')
})

test('AudioPlayer, once a minute at 2x: still once per minute of listening, not per minute of book time', async () => {
  const component = await loadPlayer()
  const { ctx, counts } = makePlayer(component, { everyMinute: true, rate: 2 })
  play(ctx, { from: 1200, seconds: 300, rate: 2 })
  assert.equal(counts.currentTimestamp, 5)
  assert.equal(counts.playedTrack, 5)
})

test('AudioPlayer, once a minute: a seek is drawn at once as the coarse position playback will report', async () => {
  const component = await loadPlayer()
  const { ctx, counts } = makePlayer(component, { everyMinute: true })
  play(ctx, { from: 1000, seconds: 5 })
  const before = { ...counts }
  ctx.seek(1130) // another minute: bar and elapsed time move at once
  assert.equal(counts.playedTrack, before.playedTrack + 1)
  assert.equal(ctx.$refs.playedTrack.style.width, `${(1080 / 36000) * 100}%`)
  assert.equal(ctx.$refs.currentTimestamp.innerText, '0:18')
  // ...and when playback reports the position, the position is not redrawn (the buffered bar follows the new buffer)
  const position = ({ bufferedTrack, ...rest }) => rest
  const afterSeek = position(counts)
  play(ctx, { from: 1131, seconds: 3 })
  assert.deepEqual(position(counts), afterSeek)
  ctx.seekLoading = false
  ctx.seek(1135) // same minute: nothing to redraw
  assert.deepEqual(position(counts), afterSeek)
})

test('AudioPlayer, once a minute: a drag is drawn exactly, and its release snaps to the coarse position once', async () => {
  const component = await loadPlayer()
  const { ctx, counts } = makePlayer(component, { everyMinute: true })
  play(ctx, { from: 1000, seconds: 5 })
  ctx.isDraggingCursor = true
  ctx.draggingCurrentTime = 1012.5
  ctx.updateTimestamp()
  ctx.updateTrack()
  assert.equal(ctx.$refs.currentTimestamp.innerText, '16:52')
  assert.equal(ctx.$refs.playedTrack.style.width, `${(1012.5 / 36000) * 100}%`)

  ctx.draggingCurrentTime = 1500.5
  ctx.touchend({ changedTouches: [{ pageY: 0 }] })
  assert.equal(ctx.isDraggingCursor, false)
  assert.equal(ctx.$refs.playedTrack.style.width, `${(1500 / 36000) * 100}%`)
  assert.equal(ctx.$refs.currentTimestamp.innerText, '0:25')
  const position = ({ bufferedTrack, ...rest }) => rest
  const released = position(counts)
  ctx.seekLoading = false
  play(ctx, { from: 1501, seconds: 3 })
  assert.deepEqual(position(counts), released, 'playback reporting the released position does not redraw it')
})

const chapters = [
  { id: 0, start: 0, end: 95.5, title: 'One' },
  { id: 1, start: 95.5, end: 130.25, title: 'Two' },
  { id: 2, start: 130.25, end: 3000, title: 'Three' }
]

test('AudioPlayer, once a minute, chapter track: short chapters and fractional starts never draw before the chapter', async () => {
  const component = await loadPlayer()
  for (const rate of [1, 1.5, 2]) {
    const { ctx } = makePlayer(component, { everyMinute: true, chapters, duration: 3000, rate })
    for (let t = 0; t < 400; t += 0.5) {
      ctx.currentTime = t
      ctx.bufferedTime = t + 30
      ctx.timeupdate()
      const chapter = ctx.currentChapter
      const width = parseFloat(ctx.$refs.playedTrack.style.width)
      const left = parseFloat(ctx.$refs.trackCursor.style.left)
      assert.ok(width >= 0 && width <= 100, `${rate}x t=${t}: width ${width}`)
      assert.ok(left >= -14, `${rate}x t=${t}: thumb ${left}`)
      assert.ok(ctx.timeRemaining >= 0 && ctx.timeRemaining <= (chapter.end - chapter.start) / rate + 1e-9, `${rate}x t=${t}: remaining ${ctx.timeRemaining}`)
      assert.ok(!ctx.$refs.currentTimestamp.innerText.startsWith('-'), `${rate}x t=${t}`)
    }
  }
})

test('AudioPlayer, once a minute: the end of the book is drawn exactly, with no time left', async () => {
  const component = await loadPlayer()
  const { ctx } = makePlayer(component, { everyMinute: true, duration: 3725.4 })
  play(ctx, { from: 3700, seconds: 20 })
  assert.equal(ctx.$refs.playedTrack.style.width, `${(3660 / 3725.4) * 100}%`)
  ctx.onMetadata({ duration: 3725.4, currentTime: 3725.4, playerState: 'ENDED' })
  assert.equal(ctx.$refs.playedTrack.style.width, '100%')
  assert.equal(ctx.timeRemainingPretty, '-0:00')
  assert.equal(ctx.totalTimeRemainingPretty, '-0:00')
  assert.equal(ctx.$refs.currentTimestamp.innerText, '1:02')
})

test('AudioPlayer, once a minute, elapsed not scaled by speed: media minutes are drawn one by one at 1.5x', async () => {
  const component = await loadPlayer()
  const { ctx } = makePlayer(component, { everyMinute: true, rate: 1.5, scaled: false })
  const shown = []
  for (let s = 0; s < 200; s++) {
    ctx.currentTime = s * 1.5
    ctx.bufferedTime = 0
    ctx.timeupdate()
    if (shown.at(-1) !== ctx.$refs.currentTimestamp.innerText) shown.push(ctx.$refs.currentTimestamp.innerText)
  }
  assert.deepEqual(shown, ['0:00', '0:01', '0:02', '0:03', '0:04'])
})

test('AudioPlayer, once a minute: the buffered bar steps by a fixed minute of media, whatever the speed', async () => {
  const component = await loadPlayer()
  for (const rate of [1, 2]) {
    const { ctx } = makePlayer(component, { everyMinute: true, rate })
    ctx.currentTime = 10
    ctx.bufferedTime = 179
    ctx.timeupdate()
    assert.equal(ctx.$refs.bufferedTrack.style.width, `${(120 / 36000) * 100}%`, `${rate}x`)
  }
})

test('AudioPlayer, once a minute: a speed or scaling change redraws the bar with the time, also while paused', async () => {
  const component = await loadPlayer()
  const { ctx } = makePlayer(component, { everyMinute: true, rate: 1 })
  ctx.currentTime = 100
  ctx.bufferedTime = 100
  ctx.timeupdate()
  assert.equal(ctx.$refs.playedTrack.style.width, `${(60 / 36000) * 100}%`)
  ctx.setPlaybackSpeed(2)
  assert.equal(ctx.$refs.playedTrack.style.width, '0%', 'a 120 s step at 2x')
  ctx.playerSettings.scaleElapsedTimeBySpeed = true
  ctx.clickMenuAction = component.methods.clickMenuAction.bind(ctx)
  ctx.$hapticsImpact = async () => {}
  ctx.$nextTick = (fn) => fn()
  ctx.savePlayerSettings = () => {}
  await ctx.clickMenuAction('scale_elapsed_time')
  assert.equal(ctx.$refs.playedTrack.style.width, `${(60 / 36000) * 100}%`, 'media minutes when not scaled')
})

test('AudioPlayer, once a minute: before the duration is known, the position is not drawn as the end', async () => {
  const component = await loadPlayer()
  const { ctx, counts } = makePlayer(component, { everyMinute: true, duration: 0 })
  ctx.currentTime = 125
  ctx.bufferedTime = 125
  assert.equal(ctx.drawnTime, 120)
  ctx.timeupdate()
  ctx.setPlaybackSpeed(2)
  assert.equal(counts.playedTrack, undefined, 'the bar is not drawn, so it never reads as finished')
})

test('AudioPlayer, once a minute: the sleep timer readout is in minutes', async () => {
  const component = await loadPlayer()
  const { ctx } = makePlayer(component, { everyMinute: true })
  ctx.sleepTimeRemaining = 75
  assert.equal(ctx.sleepTimeRemainingPretty, '2m')
  ctx.playerSettings.updateEveryMinute = false
  assert.equal(ctx.sleepTimeRemainingPretty, '75s')
})

test('AudioPlayer: the native position is still polled every second, and the setting is saved and restored', async () => {
  const source = await read('../components/app/AudioPlayer.vue')
  assert.match(source, /this\.playInterval = setInterval\(async \(\) => \{\s*var data = await AbsAudioPlayer\.getCurrentTime\(\)[\s\S]*?\}, 1000\)/)
  assert.match(source, /this\.playerSettings\.updateEveryMinute = !!savedPlayerSettings\.updateEveryMinute/)
  assert.match(source, /value: 'update_every_minute'/)
  // No per-second write to the seek row bypasses the painter
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  assert.doesNotMatch(script, /\$refs\.(playedTrack|bufferedTrack|trackCursor|trackCursorHit|totalPlayedTrack|totalBufferedTrack)\.style\.(width|left) =/)
  assert.doesNotMatch(script, /ts\.innerText =/)
})
