import test from 'node:test'
import assert from 'node:assert/strict'
import { findLocalCopy } from '../utils/localCopy.js'
test('an evicted copy is not a downloaded copy', () => {
  const evicted = { id: 'local_1', libraryItemId: 'li1', serverConnectionConfigId: 'a', isInvalid: true }
  assert.equal(findLocalCopy([evicted], { id: 'li1' }, 'a'), null)
  assert.equal(findLocalCopy([{ ...evicted, isInvalid: false }], { id: 'li1' }, 'a')?.id, 'local_1')
})
