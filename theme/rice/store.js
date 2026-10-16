/**
 * RICE persistence (docs/rice-customization.md, "Storage").
 *
 * Capacitor Preferences keys (the existing CapacitorStorage group): rice.v1.slotA, rice.v1.slotB and rice.v1.head.
 * Each slot holds a complete validated envelope; the head points at the current slot (and the previous one) with
 * its revision and SHA-256. A commit writes the inactive slot, reads it back, then writes the head and reads that
 * back: the head is the logical commit point, so an interrupted slot write is never active. Loading prefers the
 * current slot, then the referenced previous one, then the legacy `theme` key. A detected write or read-back failure
 * never reports success.
 *
 * Durability boundary: the Android Preferences implementation writes with SharedPreferences.Editor.apply(), which
 * does not acknowledge the disk write. Read-back checks the in-process value; sudden power loss can still lose the
 * latest save. This store recovers from logical interruptions and detected corruption, not every disk error.
 *
 * PresetStore is the only writer. Native widgets only read the committed head (AppearanceSnapshotReader.kt).
 */
const { parseStrictJson } = require('./strictJson')
const { sha256Hex } = require('./sha256')
const S = require('./schema')

const KEYS = Object.freeze({ slotA: 'rice.v1.slotA', slotB: 'rice.v1.slotB', head: 'rice.v1.head', legacy: 'theme' })
const MAX_ENVELOPE_BYTES = 1024 * 1024
const MAX_PRESET_BYTES = 24 * 1024
const HEAD_PATTERN = { slot: ['A', 'B'] }

class StoreError extends Error {
  constructor(code, message) {
    super(message || code)
    this.name = 'StoreError'
    this.code = code
  }
}

function parseHead(text) {
  const head = parseStrictJson(text, { maxBytes: 1024, maxDepth: 3, maxMembers: 16 })
  const pointer = (p) => S.isPlainObject(p) && Object.keys(p).sort().join() === 'revision,sha256,slot' && HEAD_PATTERN.slot.includes(p.slot) && Number.isSafeInteger(p.revision) && p.revision > 0 && /^[0-9a-f]{64}$/.test(p.sha256)
  if (!S.isPlainObject(head) || Object.keys(head).sort().join() !== 'current,previous' || !pointer(head.current) || !(head.previous === null || pointer(head.previous))) throw new StoreError('bad-head')
  return head
}

/** Validated legacy built-in id (the pre-RICE Preferences `theme` value), else 'dark'. */
const legacyBuiltin = (value) => (S.BUILTIN_IDS.includes(value) ? value : 'dark')

/** The in-memory starting envelope before anything is persisted (revision 0 = not yet saved). */
function initialEnvelope(lastBuiltin) {
  return { format: S.STORE_FORMAT, schemaVersion: S.SCHEMA_VERSION, revision: 0, riceEnabled: false, lastBuiltin, selection: { kind: 'builtin', id: lastBuiltin }, rememberedRiceSelection: null, presets: [], widget: null }
}

class PresetStore {
  /** @param {{ get(key): Promise<string|null>, set(key, value): Promise<void> }} prefs */
  constructor(prefs) {
    this.prefs = prefs
    this.queue = Promise.resolve()
    this.head = null
    this.committed = null
    this.blocked = null // set when stored data needs a newer app version: never overwritten
  }

  /** @returns {Promise<{ envelope, persisted: boolean, recovery: null|'previous'|'legacy'|'newer-version' }>} */
  async load() {
    const legacy = legacyBuiltin(await this.prefs.get(KEYS.legacy).catch(() => null))
    let headText = null
    try {
      headText = await this.prefs.get(KEYS.head)
    } catch (e) {
      headText = null
    }
    if (headText === null || headText === undefined) {
      this.committed = initialEnvelope(legacy)
      return { envelope: this.committed, persisted: false, recovery: null }
    }
    let head
    try {
      head = parseHead(headText)
    } catch (e) {
      // Unreadable head: never guess at a staged slot
      this.committed = initialEnvelope(legacy)
      this.blocked = 'bad-head'
      return { envelope: this.committed, persisted: false, recovery: 'legacy' }
    }
    for (const [pointer, recovery] of [
      [head.current, null],
      [head.previous, 'previous']
    ]) {
      if (!pointer) continue
      const result = await this.readSlot(pointer)
      if (result.envelope) {
        this.head = head
        this.committed = result.envelope
        // A recovered previous slot becomes the base for the next commit, which then replaces the bad current slot
        if (recovery) this.head = { current: pointer, previous: null }
        return { envelope: result.envelope, persisted: true, recovery }
      }
      if (result.newer) {
        this.blocked = 'newer-version'
        this.committed = initialEnvelope(legacy)
        return { envelope: this.committed, persisted: false, recovery: 'newer-version' }
      }
    }
    this.committed = initialEnvelope(legacy)
    this.blocked = 'unrecoverable'
    return { envelope: this.committed, persisted: false, recovery: 'legacy' }
  }

  async readSlot(pointer) {
    try {
      const text = await this.prefs.get(pointer.slot === 'A' ? KEYS.slotA : KEYS.slotB)
      if (typeof text !== 'string' || text.length > MAX_ENVELOPE_BYTES || sha256Hex(text) !== pointer.sha256) return {}
      const envelope = parseStrictJson(text, { maxBytes: MAX_ENVELOPE_BYTES })
      if (S.isPlainObject(envelope) && envelope.format === S.STORE_FORMAT && Number.isSafeInteger(envelope.schemaVersion) && envelope.schemaVersion > S.SCHEMA_VERSION) return { newer: true }
      if (!S.validateEnvelope(envelope).ok || envelope.revision !== pointer.revision) return {}
      return { envelope: S.deepFreeze(envelope) }
    } catch (e) {
      return {}
    }
  }

  /**
   * Validates and persists `candidate` as revision expectedRevision + 1. Serialized: concurrent commits run one at a
   * time, and a stale expected revision fails instead of overwriting newer edits.
   */
  commit(candidate, expectedRevision) {
    const run = () => this.commitNow(candidate, expectedRevision)
    const result = this.queue.then(run, run)
    this.queue = result.catch(() => {})
    return result
  }

  async commitNow(candidate, expectedRevision) {
    if (this.blocked === 'newer-version') throw new StoreError('newer-version', 'Saved appearance needs a newer app version')
    if (!this.committed || expectedRevision !== this.committed.revision) throw new StoreError('stale-revision')
    const envelope = { ...S.deepClone(candidate), revision: expectedRevision + 1 }
    const check = S.validateEnvelope(envelope)
    if (!check.ok) throw new StoreError('invalid', check.errors[0])
    for (const preset of envelope.presets) if (JSON.stringify(preset).length > MAX_PRESET_BYTES) throw new StoreError('preset-too-large')
    const text = JSON.stringify(envelope)
    if (text.length > MAX_ENVELOPE_BYTES) throw new StoreError('too-large')
    const digest = sha256Hex(text)
    const slot = this.head && this.head.current.slot === 'A' ? 'B' : 'A'
    const slotKey = slot === 'A' ? KEYS.slotA : KEYS.slotB
    try {
      await this.prefs.set(slotKey, text)
      if ((await this.prefs.get(slotKey)) !== text) throw new StoreError('slot-readback')
      const head = { current: { slot, revision: envelope.revision, sha256: digest }, previous: this.head ? this.head.current : null }
      const headText = JSON.stringify(head)
      await this.prefs.set(KEYS.head, headText)
      if ((await this.prefs.get(KEYS.head)) !== headText) throw new StoreError('head-readback')
      this.head = head
    } catch (error) {
      throw error instanceof StoreError ? error : new StoreError('write-failed')
    }
    this.blocked = null
    this.committed = S.deepFreeze(envelope)
    // Downgrade/recovery mirror only; the envelope is authoritative. A failed mirror doesn't undo the commit.
    await this.prefs.set(KEYS.legacy, envelope.lastBuiltin).catch(() => {})
    return this.committed
  }
}

// --- Pure envelope operations: each returns a new candidate; the service commits it ---

const clone = S.deepClone

function setRiceEnabled(env, enabled) {
  const next = clone(env)
  if (enabled) next.riceEnabled = true
  else {
    if (next.selection.kind !== 'builtin') next.rememberedRiceSelection = next.selection
    next.selection = { kind: 'builtin', id: next.lastBuiltin }
    next.riceEnabled = false
    next.widget = null
  }
  return next
}

function selectAppearance(env, selection) {
  if (!S.validateSelection(selection).ok) throw new StoreError('invalid-selection')
  if (selection.kind !== 'builtin' && !env.riceEnabled) throw new StoreError('rice-disabled')
  if (selection.kind === 'preset' && !env.presets.some((p) => p.id === selection.id)) throw new StoreError('missing-preset')
  const next = clone(env)
  next.selection = clone(selection)
  if (selection.kind === 'builtin') {
    next.lastBuiltin = selection.id
    next.widget = null
  } else next.rememberedRiceSelection = clone(selection)
  return next
}

function makePreset({ id, name, snapshot, source, now }) {
  const normalized = S.normalizeName(name)
  if (!normalized) throw new StoreError('invalid-name')
  return { format: S.PRESET_FORMAT, schemaVersion: S.SCHEMA_VERSION, tokenSchemaVersion: S.TOKEN_SCHEMA_VERSION, id, name: normalized, createdAt: now, updatedAt: now, snapshot: clone(snapshot), reset: clone(snapshot), source: clone(source) }
}

function addPreset(env, preset) {
  if (env.presets.length >= S.LIMITS.presets) throw new StoreError('capacity')
  if (!S.validatePreset(preset).ok) throw new StoreError('invalid-preset', S.validatePreset(preset).errors[0])
  const next = clone(env)
  next.presets.push(clone(preset))
  return next
}

function updatePreset(env, id, { snapshot, name, now }) {
  const index = env.presets.findIndex((p) => p.id === id)
  if (index < 0) throw new StoreError('missing-preset')
  const next = clone(env)
  const preset = next.presets[index]
  if (snapshot) preset.snapshot = clone(snapshot)
  if (name !== undefined) {
    const normalized = S.normalizeName(name)
    if (!normalized) throw new StoreError('invalid-name')
    preset.name = normalized
  }
  preset.updatedAt = now
  return next
}

function duplicatePreset(env, id, { newId, name, now }) {
  const original = env.presets.find((p) => p.id === id)
  if (!original) throw new StoreError('missing-preset')
  const copy = { ...clone(original), id: newId, name: S.normalizeName(name) || original.name, createdAt: now, updatedAt: now }
  return addPreset(env, copy)
}

/** Deleting the active preset selects the last built-in in the same commit. */
function deletePreset(env, id) {
  if (!env.presets.some((p) => p.id === id)) throw new StoreError('missing-preset')
  const next = clone(env)
  next.presets = next.presets.filter((p) => p.id !== id)
  const points = (s) => s && s.kind === 'preset' && s.id === id
  if (points(next.selection)) {
    next.selection = { kind: 'builtin', id: next.lastBuiltin }
    next.widget = null
  }
  if (points(next.rememberedRiceSelection)) next.rememberedRiceSelection = null
  return next
}

function setWidget(env, widget) {
  const next = clone(env)
  next.widget = widget ? clone(widget) : null
  return next
}

module.exports = { PresetStore, StoreError, KEYS, MAX_ENVELOPE_BYTES, initialEnvelope, legacyBuiltin, setRiceEnabled, selectAppearance, makePreset, addPreset, updatePreset, duplicatePreset, deletePreset, setWidget }
