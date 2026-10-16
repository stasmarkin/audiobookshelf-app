// RICE persistence (theme/rice/store.js): two-slot commits with verified read-back, head-pointer recovery, legacy
// fallback, newer-version protection, serialized writers and the pure preset operations. Faults are injected into a
// fake Preferences; this models logical interruptions only - Android's SharedPreferences.apply() durability on real
// disk is outside what a unit test (or the store) can prove.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const store = require('../theme/rice/store.js')
const S = require('../theme/rice/schema.js')
const { STARTERS } = require('../theme/rice/catalog.js')
const { sha256Hex } = require('../theme/rice/sha256.js')
const { PresetStore, KEYS } = store

function fakePrefs(initial = {}, faults = {}) {
  const data = { ...initial }
  const writes = []
  return {
    data,
    writes,
    async get(key) {
      if (faults.getFails && faults.getFails(key)) throw new Error('bridge')
      return key in data ? data[key] : null
    },
    async set(key, value) {
      writes.push(key)
      if (faults.setFails && faults.setFails(key, writes.length)) throw new Error('bridge')
      // A "silent" fault stores something else (models a write that read-back catches)
      data[key] = faults.corrupt && faults.corrupt(key) ? value.slice(0, -1) : value
    }
  }
}

const NOW = '2026-10-12T00:00:00.000Z'
const preset = (id, name = 'Mine') => store.makePreset({ id, name, snapshot: STARTERS[0].snapshot, source: { kind: 'starter', id: STARTERS[0].id }, now: NOW })
const pid = (n) => 'p-' + String(n).padStart(24, '0')

async function committedStore(prefs = fakePrefs({ theme: 'black' })) {
  const s = new PresetStore(prefs)
  const { envelope } = await s.load()
  const enabled = await s.commit(store.setRiceEnabled(envelope, true), envelope.revision)
  return { s, prefs, envelope: enabled }
}

test('fresh install: nothing persisted, RICE off, the legacy built-in is the selection; nothing is written by loading', async () => {
  const prefs = fakePrefs({ theme: 'light' })
  const s = new PresetStore(prefs)
  const loaded = await s.load()
  assert.equal(loaded.persisted, false)
  assert.equal(loaded.envelope.riceEnabled, false)
  assert.deepEqual(loaded.envelope.selection, { kind: 'builtin', id: 'light' })
  assert.deepEqual(prefs.writes, [])
  const odd = await new PresetStore(fakePrefs({ theme: '<script>' })).load()
  assert.equal(odd.envelope.lastBuiltin, 'dark', 'unknown legacy values resolve to Dark')
  const eink = await new PresetStore(fakePrefs({ theme: 'eink' })).load()
  assert.deepEqual(eink.envelope.selection, { kind: 'builtin', id: 'eink' }, 'every built-in is a valid legacy value')
})

test('commit writes the inactive slot, verifies it, then the head; slots alternate and the legacy mirror follows', async () => {
  const { s, prefs, envelope } = await committedStore()
  assert.equal(envelope.revision, 1)
  assert.deepEqual(prefs.writes, [KEYS.slotA, KEYS.head, KEYS.legacy])
  const head = JSON.parse(prefs.data[KEYS.head])
  assert.deepEqual(head.current, { slot: 'A', revision: 1, sha256: sha256Hex(prefs.data[KEYS.slotA]) })
  const second = await s.commit(store.selectAppearance(envelope, { kind: 'starter', id: 'nord' }), 1)
  assert.equal(JSON.parse(prefs.data[KEYS.head]).current.slot, 'B')
  assert.deepEqual(JSON.parse(prefs.data[KEYS.head]).previous.slot, 'A')
  assert.equal(prefs.data.theme, 'black', 'legacy mirror keeps the last built-in for downgrade fallback')
  const reloaded = await new PresetStore(prefs).load()
  assert.deepEqual(reloaded.envelope, second)
})

test('a detected write or read-back failure never reports success and leaves the committed state active', async () => {
  for (const [name, faults] of [
    ['slot write throws', { setFails: (key) => key === KEYS.slotB }],
    ['head write throws', { setFails: (key, n) => key === KEYS.head && n > 3 }],
    ['slot read-back differs', { corrupt: (key) => key === KEYS.slotB }]
  ]) {
    const prefs = fakePrefs({ theme: 'dark' }, faults)
    const { s, envelope } = await committedStore(prefs)
    await assert.rejects(s.commit(store.selectAppearance(envelope, { kind: 'starter', id: 'nord' }), envelope.revision), { name: 'StoreError' }, name)
    const reloaded = await new PresetStore(prefs).load()
    assert.equal(reloaded.envelope.revision, 1, `${name}: the old head still points at revision 1`)
    assert.equal(reloaded.envelope.selection.kind, 'builtin')
  }
})

test('an interrupted commit (slot written, head not) is never activated, even though its revision is higher', async () => {
  const prefs = fakePrefs({ theme: 'dark' }, { setFails: (key, n) => key === KEYS.head && n > 3 })
  const { s, envelope } = await committedStore(prefs)
  await assert.rejects(s.commit(store.selectAppearance(envelope, { kind: 'starter', id: 'dracula' }), 1))
  assert.ok(prefs.data[KEYS.slotB].includes('dracula'), 'the staged slot exists')
  const reloaded = await new PresetStore(prefs).load()
  assert.deepEqual(reloaded.envelope.selection, envelope.selection)
  assert.equal(reloaded.envelope.revision, 1)
})

test('recovery: corrupt current slot -> referenced previous; both bad or bad head -> validated legacy built-in', async () => {
  const { s, prefs, envelope } = await committedStore()
  await s.commit(store.selectAppearance(envelope, { kind: 'starter', id: 'nord' }), 1)
  prefs.data[KEYS.slotB] = prefs.data[KEYS.slotB].replace('nord', 'dracula') // hash no longer matches
  const recovered = await new PresetStore(prefs).load()
  assert.equal(recovered.recovery, 'previous')
  assert.equal(recovered.envelope.revision, 1)
  prefs.data[KEYS.slotA] = '{}'
  const legacy = await new PresetStore(prefs).load()
  assert.equal(legacy.recovery, 'legacy')
  assert.deepEqual(legacy.envelope.selection, { kind: 'builtin', id: 'black' })
  prefs.data[KEYS.head] = 'not json'
  assert.equal((await new PresetStore(prefs).load()).recovery, 'legacy')
})

test('the next commit after recovering from the previous slot replaces the bad slot', async () => {
  const { s, prefs, envelope } = await committedStore()
  await s.commit(store.selectAppearance(envelope, { kind: 'starter', id: 'nord' }), 1)
  prefs.data[KEYS.slotB] = 'garbage'
  const s2 = new PresetStore(prefs)
  const { envelope: e1 } = await s2.load()
  const e2 = await s2.commit(store.selectAppearance(e1, { kind: 'starter', id: 'rose-pine' }), e1.revision)
  assert.equal(JSON.parse(prefs.data[KEYS.head]).current.slot, 'B')
  assert.deepEqual((await new PresetStore(prefs).load()).envelope, e2)
})

test('data from a newer app version is never overwritten', async () => {
  const { prefs } = await committedStore()
  const future = JSON.parse(prefs.data[KEYS.slotA])
  future.schemaVersion = 2
  prefs.data[KEYS.slotA] = JSON.stringify(future)
  prefs.data[KEYS.head] = JSON.stringify({ current: { slot: 'A', revision: 1, sha256: sha256Hex(prefs.data[KEYS.slotA]) }, previous: null })
  const s = new PresetStore(prefs)
  const loaded = await s.load()
  assert.equal(loaded.recovery, 'newer-version')
  assert.equal(loaded.envelope.selection.kind, 'builtin')
  const before = prefs.data[KEYS.slotA]
  await assert.rejects(s.commit(store.setRiceEnabled(loaded.envelope, true), 0), { code: 'newer-version' })
  assert.equal(prefs.data[KEYS.slotA], before)
})

test('writers are serialized and a stale expected revision fails instead of overwriting', async () => {
  const { s, envelope } = await committedStore()
  const a = s.commit(store.selectAppearance(envelope, { kind: 'starter', id: 'nord' }), 1)
  const b = s.commit(store.selectAppearance(envelope, { kind: 'starter', id: 'dracula' }), 1)
  assert.equal((await a).selection.id, 'nord')
  await assert.rejects(b, { code: 'stale-revision' })
})

test('invalid candidates are rejected before anything is written', async () => {
  const { s, prefs, envelope } = await committedStore()
  const writes = prefs.writes.length
  const bad = JSON.parse(JSON.stringify(envelope))
  bad.presets.push({ ...preset(pid(1)), snapshot: { evil: true } })
  await assert.rejects(s.commit(bad, 1), { code: 'invalid' })
  assert.equal(prefs.writes.length, writes)
})

test('preset operations: create, rename, duplicate, capacity, delete active, deleted remembered selection', async () => {
  let env = store.setRiceEnabled(store.initialEnvelope('dark'), true)
  env = store.addPreset(env, preset(pid(1)))
  assert.throws(() => store.addPreset(env, preset(pid(2), '')), { code: 'invalid-name' })
  env = store.updatePreset(env, pid(1), { name: '  Renamed  ', now: NOW })
  assert.equal(env.presets[0].name, 'Renamed')
  env = store.duplicatePreset(env, pid(1), { newId: pid(2), name: 'Renamed copy', now: NOW })
  assert.equal(env.presets.length, 2)
  assert.notEqual(env.presets[1].id, env.presets[0].id)
  env = store.selectAppearance(env, { kind: 'preset', id: pid(2) })
  env = store.deletePreset(env, pid(2))
  assert.deepEqual(env.selection, { kind: 'builtin', id: 'dark' }, 'deleting the active preset selects the last built-in')
  assert.equal(env.rememberedRiceSelection, null)
  let full = env
  for (let i = 3; full.presets.length < S.LIMITS.presets; i++) full = store.addPreset(full, preset(pid(i)))
  assert.throws(() => store.addPreset(full, preset(pid(99))), { code: 'capacity' }, 'at capacity nothing is evicted')
  assert.throws(() => store.selectAppearance(store.setRiceEnabled(env, false), { kind: 'starter', id: 'nord' }), { code: 'rice-disabled' })
})

test('turning RICE off restores the last built-in, remembers the custom choice and keeps presets', () => {
  let env = store.setRiceEnabled(store.initialEnvelope('light'), true)
  env = store.addPreset(env, preset(pid(1)))
  env = store.selectAppearance(env, { kind: 'preset', id: pid(1) })
  const off = store.setRiceEnabled(env, false)
  assert.deepEqual(off.selection, { kind: 'builtin', id: 'light' })
  assert.deepEqual(off.rememberedRiceSelection, { kind: 'preset', id: pid(1) })
  assert.equal(off.presets.length, 1)
  assert.equal(off.widget, null)
  const on = store.setRiceEnabled(off, true)
  assert.deepEqual(on.selection, { kind: 'builtin', id: 'light' }, 'enabling does not recolor the app')
})

test('presets hold appearance data only: no credentials, server or playback fields can be stored', () => {
  const p = preset(pid(1))
  for (const key of ['token', 'serverConnectionConfig', 'currentTime', 'libraryItemId']) {
    assert.equal(S.validatePreset({ ...p, [key]: 'x' }).ok, false, key)
    assert.equal(S.validatePreset({ ...p, source: { ...p.source, [key]: 'x' } }).ok, false, key)
  }
})

test('the native store reader accepts exactly the built-ins the app can save', async () => {
  const kt = await readFile(new URL('../android/app/src/main/java/app/absplus/android/appearance/AppearanceSnapshotReader.kt', import.meta.url), 'utf8')
  const list = kt.match(/BUILTIN_IDS = setOf\(([^)]*)\)/)
  assert.ok(list, 'AppearanceSnapshotReader.BUILTIN_IDS')
  assert.deepEqual([...list[1].matchAll(/"([a-z-]+)"/g)].map((m) => m[1]).sort(), [...S.BUILTIN_IDS].sort())
})
