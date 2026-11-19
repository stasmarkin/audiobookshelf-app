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
const context = { jumpLabelMixin: {}, diagnosticLogMixin: {}, normalizeDelayDays: (v) => v ?? 1, normalizeLimitGb: (v) => v ?? 0, LONG_JUMP_SECONDS_OPTIONS, normalizeLongJumpTime }
vm.runInNewContext(script.content.replace(/^import .*$/gm, '').replace('export default', 'globalThis.page ='), context)

test('the headset extend option is off by default and survives a reload when on', () => {
  assert.equal(context.page.data.call({ $strings: {} }).settings.enableSleepTimerHeadsetExtend, false)
  for (const [saved, expected] of [[undefined, false], [false, false], [true, true]]) {
    const page = { deviceData: { deviceSettings: { enableSleepTimerHeadsetExtend: saved } }, settings: {} }
    context.page.methods.setDeviceSettings.call(page)
    assert.equal(page.settings.enableSleepTimerHeadsetExtend, expected)
  }
})

test('toggling the option saves it through the existing settings save', async () => {
  const saved = []
  const page = {
    settings: { enableSleepTimerHeadsetExtend: false, enableSleepTimerAlmostDoneChime: true, languageCode: 'en-us' },
    $hapticsImpact: async () => {},
    loadDownloadsStorage: async () => {},
    $db: { updateDeviceSettings: async (payload) => { saved.push(structuredClone(payload)); return { deviceSettings: payload } } },
    $store: { commit: () => {} }, $setLanguageCode: () => {}
  }
  page.setDeviceSettings = () => context.page.methods.setDeviceSettings.call(page)
  page.saveSettings = () => context.page.methods.saveSettings.call(page)
  context.page.methods.toggleSleepTimerHeadsetExtend.call(page)
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(saved[0].enableSleepTimerHeadsetExtend, true)
  assert.equal(saved[0].enableSleepTimerAlmostDoneChime, true)
})

test('the toggle renders next to the sleep timer chime with info and English strings', async () => {
  assert.match(template.content, /v-model="settings.enableSleepTimerHeadsetExtend"/)
  assert.match(template.content, /showInfo\('enableSleepTimerHeadsetExtend'\)/)
  assert.ok(template.content.indexOf('LabelSleepTimerHeadsetExtend') > template.content.indexOf('LabelSleepTimerAlmostDoneChime'))
  assert.deepEqual(compile(template.content).errors, [])
  const strings = JSON.parse(await readFile(new URL('../strings/en-us.json', import.meta.url), 'utf8'))
  assert.match(strings.LabelSleepTimerHeadsetExtend, /headset/)
  assert.match(strings.LabelSleepTimerHeadsetExtendHelp, /15 seconds/)
  const info = context.page.data.call({ $strings: strings }).settingInfo.enableSleepTimerHeadsetExtend
  assert.equal(info.name, strings.LabelSleepTimerHeadsetExtend)
  assert.equal(info.message, strings.LabelSleepTimerHeadsetExtendHelp)
})
