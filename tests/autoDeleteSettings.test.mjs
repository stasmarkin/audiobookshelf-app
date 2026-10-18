import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

// Load the Nuxt ES module without changing this project's CommonJS package type.
const source = await readFile(new URL('../utils/autoDeleteSettings.js', import.meta.url), 'utf8')
const { normalizeDelayDays, normalizeLimitGb, downloadsFreeBytes } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

test('a missing or unknown delay reads as one day, and zero stays right away', () => {
  assert.equal(normalizeDelayDays(null), 1)
  assert.equal(normalizeDelayDays(undefined), 1)
  assert.equal(normalizeDelayDays(5), 1)
  assert.equal(normalizeDelayDays(0), 0)
  assert.equal(normalizeDelayDays(7), 7)
})

test('a missing or unknown limit reads as off', () => {
  assert.equal(normalizeLimitGb(null), 0)
  assert.equal(normalizeLimitGb(3), 0)
  assert.equal(normalizeLimitGb(10), 10)
})

test('free space is that of the storage downloads go to', () => {
  const sd = { uuid: '79EC-9B33', mounted: true, freeBytes: 500 }
  assert.equal(downloadsFreeBytes({ downloadStorage: 'internal', internalFreeBytes: 100, sd }), 100)
  assert.equal(downloadsFreeBytes({ downloadStorage: 'sd:79EC-9B33', internalFreeBytes: 100, sd }), 500)
})

test('free space is unknown while the configured card is out or another one is in', () => {
  assert.equal(downloadsFreeBytes({ downloadStorage: 'sd:79EC-9B33', internalFreeBytes: 100, sd: null }), null)
  assert.equal(downloadsFreeBytes({ downloadStorage: 'sd:79EC-9B33', internalFreeBytes: 100, sd: { uuid: '79EC-9B33', mounted: false, freeBytes: 0 } }), null)
  assert.equal(downloadsFreeBytes({ downloadStorage: 'sd:79EC-9B33', internalFreeBytes: 100, sd: { uuid: 'OTHER', mounted: true, freeBytes: 7 } }), null)
  assert.equal(downloadsFreeBytes(null), null)
})
