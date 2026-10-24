import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { createRequire } from 'node:module'
import { LONG_JUMP_SECONDS_OPTIONS, normalizeLongJumpTime } from '../utils/longJump.js'
const require = createRequire(import.meta.url)
const { parseComponent, compile } = require('vue-template-compiler')
const source = await readFile(new URL('../pages/settings.vue', import.meta.url), 'utf8')
const { script, template } = parseComponent(source)
// The page's other imports are stripped below; these two are called while loading device settings
const context = { jumpLabelMixin: {}, diagnosticLogMixin: {}, normalizeDelayDays: (v) => v ?? 1, normalizeLimitGb: (v) => v ?? 0, LONG_JUMP_SECONDS_OPTIONS, normalizeLongJumpTime }
vm.runInNewContext(script.content.replace(/^import .*$/gm, '').replace('export default', 'globalThis.page ='), context)

test('existing settings and unknown navigation modes default to time; explicit selections survive reload', () => {
  for (const [saved, expected] of [[undefined, 'time'], [null, 'time'], ['bad', 'time'], ['time', 'time'], ['chapters', 'chapters'], ['files', 'files']]) {
    const page = { deviceData: { deviceSettings: { externalMediaNavigation: saved, jumpForwardTime: 30, jumpBackwardsTime: 15 } }, settings: {} }
    context.page.methods.setDeviceSettings.call(page)
    assert.equal(page.settings.externalMediaNavigation, expected)
    assert.equal(page.settings.jumpForwardTime, 30)
    assert.equal(page.settings.jumpBackwardsTime, 15)
  }
})

test('the accessible Android playback selector uses all three persisted modes and semantic theme colors', () => {
  const select = template.content.match(/<select id="external-media-navigation"[\s\S]*?<\/select>/)?.[0]
  assert.ok(select)
  assert.match(template.content, /<label for="external-media-navigation"/)
  assert.match(select, /v-model="settings.externalMediaNavigation"/)
  assert.match(select, /@change="saveSettings"/)
  assert.match(select, /bg-bg text-fg border border-border/)
  assert.deepEqual([...select.matchAll(/value="(.*?)"/g)].map((m) => m[1]), ['time', 'chapters', 'files'])
  assert.deepEqual(compile(template.content).errors, [])
})

test('all new labels have English fallback, with explicit metadata and queue boundary explanations', async () => {
  const strings = JSON.parse(await readFile(new URL('../strings/en-us.json', import.meta.url), 'utf8'))
  const keys = [...template.content.matchAll(/\$strings\.(\w*ExternalMedia\w*)/g)].map((m) => m[1])
  for (const key of keys) assert.equal(typeof strings[key], 'string', key)
  assert.match(strings.MessageExternalMediaControls, /Fast Forward and Rewind/)
  assert.match(strings.MessageExternalMediaControls, /current book or episode/)
  const i18n = await readFile(new URL('../plugins/i18n.js', import.meta.url), 'utf8')
  assert.match(i18n, /strings\[key\] \|\| translations\[defaultCode\]\[key\]/)
})

test('saving the selector uses the existing database bridge and preserves unrelated settings', async () => {
  const saved = []
  const page = {
    settings: { externalMediaNavigation: 'files', jumpForwardTime: 30, jumpBackwardsTime: 15, languageCode: 'en-us', autoDownloadEnabled: true },
    $hapticsImpact: async () => {},
    loadDownloadsStorage: async () => {},
    $db: { updateDeviceSettings: async (payload) => { saved.push(structuredClone(payload)); return { deviceSettings: payload } } },
    $store: { commit: () => {} }, $setLanguageCode: () => {}
  }
  page.setDeviceSettings = () => context.page.methods.setDeviceSettings.call(page)
  await context.page.methods.saveSettings.call(page)
  assert.equal(saved[0].externalMediaNavigation, 'files')
  assert.equal(saved[0].autoDownloadEnabled, true)
  assert.equal(saved[0].jumpForwardTime, 30)
  assert.equal(page.deviceData.deviceSettings.externalMediaNavigation, 'files')
})
