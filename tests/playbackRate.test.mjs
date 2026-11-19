import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

// Load the Nuxt ES module without changing this project's CommonJS package type.
const source = await readFile(new URL('../utils/playbackRate.js', import.meta.url), 'utf8')
const { normalizePlaybackRate, formatPlaybackRate, listeningSeconds } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
// Source files are read LF-normalized, so a Windows (core.autocrlf) checkout and CI see the same text
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

// The speed a 32-bit native float reports for each value the app offers, widened to a double (as the bridge delivers it)
const asNativeFloat = (rate) => Math.fround(rate)

test('native float speeds normalize to the speed the user chose', () => {
  assert.equal(asNativeFloat(1.4), 1.399999976158142)
  for (const rate of [0.5, 0.6, 0.7, 0.8, 0.9, 1, 1.1, 1.2, 1.25, 1.3, 1.4, 1.5, 1.6, 1.7, 1.75, 1.8, 1.9, 2, 2.5, 3, 3.3, 5.7, 9.9, 10]) {
    assert.equal(normalizePlaybackRate(asNativeFloat(rate)), rate, String(rate))
    assert.equal(normalizePlaybackRate(rate), rate, String(rate))
    assert.equal(normalizePlaybackRate(String(asNativeFloat(rate))), rate, `string ${rate}`)
  }
})

test('invalid speeds are rejected instead of shown', () => {
  for (const value of [undefined, null, '', 'abc', NaN, Infinity, 0, -1]) assert.equal(normalizePlaybackRate(value), null, String(value))
})

test('speed labels are concise for every offered speed', () => {
  const cases = { 0.5: '0.5x', 1: '1x', 1.2: '1.2x', 1.25: '1.25x', 1.4: '1.4x', 1.5: '1.5x', 1.75: '1.75x', 2: '2x', 3: '3x', 9.9: '9.9x' }
  for (const [rate, label] of Object.entries(cases)) {
    assert.equal(formatPlaybackRate(asNativeFloat(Number(rate))), label, rate)
    assert.ok(label.length <= 5, label)
  }
  assert.equal(formatPlaybackRate(undefined), '1x')
})

test('the player normalizes every native speed and shows the concise label', async () => {
  const player = await read('../components/app/AudioPlayer.vue')
  const template = player.slice(0, player.indexOf('</template>'))
  // The label, never the raw number
  assert.match(template, /class="speed-readout [^"]*"[^>]*>\{\{ playbackRateLabel \}\}<\/span>/)
  assert.doesNotMatch(template, /\{\{ currentPlaybackRate \}\}x/)
  assert.match(player, /playbackRateLabel\(\) \{\s*return formatPlaybackRate\(this\.currentPlaybackRate\)/)
  // Both native entry points: the speed-changed event and the reattach after the UI restarts (e.g. a font-size change
  // recreates the activity while the native player keeps running)
  const speedChanged = player.match(/onPlaybackSpeedChanged\(data\) \{[\s\S]*?\n {4}\}/)[0]
  assert.match(speedChanged, /normalizePlaybackRate\(data\.value\)/)
  assert.doesNotMatch(speedChanged, /Number\(data\.value\)/)
  const reattach = player.match(/async reattachNativePlayback\(\) \{[\s\S]*?\n {4}\}/)[0]
  assert.match(reattach, /normalizePlaybackRate\(state\.playbackRate\)/)
  assert.doesNotMatch(reattach, /Number\(state\.playbackRate\)/)
})

test('book time is shown as listening time at the playback speed', () => {
  assert.equal(listeningSeconds(3600, 1), 3600)
  assert.equal(listeningSeconds(3600, 2), 1800)
  assert.equal(listeningSeconds(5400, 1.5), 3600)
  assert.equal(listeningSeconds(3600, asNativeFloat(1.5)), 2400)
  for (const rate of [undefined, null, 0, -1, NaN, 'abc', 0.004]) assert.equal(listeningSeconds(3600, rate), 3600, `rate ${rate}`)
  for (const seconds of [undefined, null]) assert.equal(listeningSeconds(seconds, 2), seconds, `seconds ${seconds}`)
  assert.ok(Number.isNaN(listeningSeconds(NaN, 2)))
})

test('book durations, remaining times and positions outside the player are shown at the playback speed', async () => {
  const wrapped = {
    '../components/cards/LazyBookCard.vue': [/\$elapsedPrettyExtended\(this\.\$listeningSeconds\(this\.media\.duration\), false\)/],
    '../components/cards/LazyListBookCard.vue': [/\$elapsedPretty\(\$listeningSeconds\(duration\)\)/],
    '../components/tables/collection/BookTableRow.vue': [/\$elapsedPretty\(this\.\$listeningSeconds\(this\.media\.duration\)\)/],
    '../components/tables/collection/CollectionBooksTable.vue': [/\$elapsedPrettyExtended\(this\.\$listeningSeconds\(this\.totalDuration\)\)/],
    '../components/tables/playlist/PlaylistItemsTable.vue': [/\$elapsedPrettyExtended\(this\.\$listeningSeconds\(this\.totalDuration\)\)/],
    '../components/tables/playlist/ItemTableRow.vue': [/\$elapsedPretty\(this\.\$listeningSeconds\(this\.episode\.duration\)\)/, /\$elapsedPretty\(this\.\$listeningSeconds\(this\.media\.duration\)\)/],
    '../components/tables/ChaptersTable.vue': [/\$secondsToTimestamp\(Math\.round\(\$listeningSeconds\(chapter\.start\)\)\)/, /\$secondsToTimestamp\(Math\.round\(\$listeningSeconds\(Math\.max\(0, chapter\.end - chapter\.start\)\)\)\)/],
    '../pages/item/_id/index.vue': [/\$elapsedPretty\(\$listeningSeconds\(userTimeRemaining\)\)/, /\$elapsedPretty\(\$listeningSeconds\(duration\)\)/, /\$secondsToTimestamp\(Math\.round\(this\.\$listeningSeconds\(startTime\)\)\)/],
    '../pages/media/_id/history.vue': [/\$secondsToTimestampFull\(Math\.round\(\$listeningSeconds\(evt\.currentTime\)\)\)/]
  }
  const episodeTime = [
    /\$secondsToTimestamp\(Math\.round\(this\.\$listeningSeconds\(this\.episode\.duration\)\)\)/,
    /\$elapsedPretty\(this\.\$listeningSeconds\(this\.episode\.duration\)\)/,
    /\$elapsedPretty\(this\.\$listeningSeconds\(remaining\)\)\} left/
  ]
  wrapped['../components/tables/podcast/EpisodeRow.vue'] = episodeTime
  wrapped['../components/tables/podcast/LatestEpisodeRow.vue'] = episodeTime
  wrapped['../pages/item/_id/_episode/index.vue'] = [...episodeTime, /\$elapsedPretty\(\$listeningSeconds\(userTimeRemaining\)\)/]
  for (const [path, patterns] of Object.entries(wrapped)) {
    const source = await read(path)
    for (const pattern of patterns) assert.match(source, pattern, path)
  }
  // The history row still seeks to the book position, not the scaled one
  assert.match(await read('../pages/media/_id/history.vue'), /@click="clickPlaybackTime\(evt\.currentTime\)"/)
  // File durations, real-time sleep remaining and listening statistics are not book time at a speed
  for (const path of ['../components/tables/TracksTable.vue', '../components/modals/SleepTimerModal.vue', '../pages/stats.vue']) {
    assert.doesNotMatch(await read(path), /\$listeningSeconds/, path)
  }
})

test('a speed changed outside the app UI reaches the store the screens read', async () => {
  const player = await read('../components/app/AudioPlayer.vue')
  const speedChanged = player.match(/onPlaybackSpeedChanged\(data\) \{[\s\S]*?\n {4}\}/)[0]
  assert.match(speedChanged, /if \(rate === null\) return[\s\S]*\$store\.commit\('user\/setPlaybackRateSetting', rate\)/)
  // The speed modal and bookmarks read the container's copy
  assert.match(speedChanged, /\$emit\('nativeSpeedChanged', rate\)/)
  assert.match(await read('../components/app/AudioPlayerContainer.vue'), /@nativeSpeedChanged="\(rate\) => \(playbackSpeed = rate\)"/)

  const store = await read('../store/user.js')
  const mutation = store.match(/setPlaybackRateSetting\(state, rate\) \{[\s\S]*?\n {2}\}/)[0]
  const setPlaybackRateSetting = new Function(`return function ${mutation}`)()
  const state = { settings: { playbackRate: 1, mobileOrderBy: 'addedAt' } }
  const before = state.settings
  setPlaybackRateSetting(state, 1.5)
  assert.deepEqual(state.settings, { playbackRate: 1.5, mobileOrderBy: 'addedAt' })
  assert.notEqual(state.settings, before, 'a new settings object keeps the getter reactive')
  // Memory only: the native side already saved it, and a full save could overwrite settings not loaded yet
  assert.doesNotMatch(mutation, /localStore|\$eventBus/)
})

test('the screens read the user speed through one helper that works without a component store', async () => {
  const init = await read('../plugins/init.client.js')
  assert.match(init, /import \{ listeningSeconds \} from '@\/utils\/playbackRate'/)
  const helper = init.match(/Vue\.prototype\.\$listeningSeconds = function \(seconds\) \{[\s\S]*?\n\}/)[0]
  assert.match(helper, /this\.\$store \|\| this\.\$nuxt\.\$store/)
  assert.match(helper, /getUserSetting'\]\('playbackRate'\)/)
})
