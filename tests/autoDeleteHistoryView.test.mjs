import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

test('evicted server books offer streaming/download while keeping the same linked history identity', () => {
  const source = fs.readFileSync(new URL('../pages/item/_id/index.vue', import.meta.url), 'utf8')
  const body = source.match(/hasLocal\(\) \{([\s\S]*?)\n    \}/)[1]
  const hasLocal = new Function(body)
  const linked = { id: 'stable-local', isInvalid: true, media: { title: 'Synthetic' } }
  const context = { isLocal: false, libraryItem: { localLibraryItem: linked } }
  assert.equal(hasLocal.call(context), false)
  assert.equal(context.libraryItem.localLibraryItem.id, 'stable-local')
  linked.isInvalid = false
  assert.equal(hasLocal.call(context), true)
  context.isLocal = true
  assert.equal(hasLocal.call(context), true)
})

test('both book-card projections avoid offering an evicted copy as playable', () => {
  for (const name of ['LazyBookCard', 'LazyListBookCard']) {
    const source = fs.readFileSync(new URL(`../components/cards/${name}.vue`, import.meta.url), 'utf8')
    const set = new Function('localLibraryItem', source.match(/setLocalLibraryItem\(localLibraryItem\) \{([\s\S]*?)\n    \}/)[1])
    const context = {}; const record = { id: 'stable', isInvalid: true }
    set.call(context, record)
    assert.equal(context.localLibraryItem, null)
    assert.equal(record.id, 'stable')
    record.isInvalid = false
    set.call(context, record)
    assert.equal(context.localLibraryItem, record)
  }
})
