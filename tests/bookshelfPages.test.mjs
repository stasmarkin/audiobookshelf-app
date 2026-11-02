import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createPageRetry } from '../utils/pageRetry.js'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

// Deterministic timers: run() fires everything scheduled so far
const fakeTimers = () => {
  let queue = []
  return {
    setTimer: (fn, ms) => {
      const t = { fn, ms }
      queue.push(t)
      return t
    },
    clearTimer: (t) => (queue = queue.filter((q) => q !== t)),
    run: () => {
      const due = queue
      queue = []
      due.forEach((t) => t.fn())
      return due.map((t) => t.ms)
    },
    size: () => queue.length
  }
}

test('a failed page is retried with a growing delay, a bounded number of times', () => {
  const timers = fakeTimers()
  const retry = createPageRetry({ maxAttempts: 3, delayMs: 1000, ...timers })
  const retried = []
  const onRetry = (page) => retried.push(page)
  assert.equal(retry.failed(1, onRetry), true)
  assert.deepEqual(timers.run(), [1000])
  assert.equal(retry.failed(1, onRetry), true)
  assert.deepEqual(timers.run(), [2000])
  assert.equal(retry.failed(1, onRetry), true)
  assert.deepEqual(timers.run(), [3000])
  // Out of attempts: no retry loop against a server that keeps failing
  assert.equal(retry.failed(1, onRetry), false)
  assert.equal(timers.size(), 0)
  assert.deepEqual(retried, [1, 1, 1])
})

test('one pending retry per page, success resets the count, reset drops pending retries', () => {
  const timers = fakeTimers()
  const retry = createPageRetry({ maxAttempts: 2, delayMs: 500, ...timers })
  const noop = () => {}
  assert.equal(retry.failed(4, noop), true)
  assert.equal(retry.failed(4, noop), false, 'already pending')
  timers.run()
  retry.succeeded(4)
  assert.equal(retry.failed(4, noop), true, 'a later failure starts over')
  assert.equal(retry.pending(4), true)
  retry.reset()
  assert.equal(retry.pending(4), false)
  assert.equal(timers.size(), 0)
})

// The S26 blank library rows: a page fetch that failed stayed marked as loaded, so it was never fetched again and
// its rows stayed empty placeholders. A failed page is now unmarked and retried, and the rows in view are mounted.
test('LazyBookshelf retries a failed page instead of leaving its rows empty', async () => {
  const shelf = await read('../components/bookshelf/LazyBookshelf.vue')
  const fetch = shelf.slice(shelf.indexOf('async fetchEntities(page)'), shelf.indexOf('async loadPage(page)'))
  const failure = fetch.indexOf('if (!payload || !payload.results) {')
  assert.ok(failure > 0, 'failure branch')
  assert.ok(fetch.indexOf('delete this.pagesLoaded[page]', failure) > failure)
  assert.ok(fetch.indexOf('this.pageRetry.failed(page, this.retryPage)', failure) > failure)
  assert.ok(fetch.includes('this.pageRetry.succeeded(page)'))
  const retryPage = shelf.slice(shelf.indexOf('async retryPage(page)'), shelf.indexOf('mountEntites(fromIndex, toIndex)'))
  assert.match(retryPage, /if \(this\.pagesLoaded\[page\] \|\| !this\.canLoad\) return/)
  assert.match(retryPage, /await this\.\$nextTick\(\)/)
  assert.match(retryPage, /this\.handleScroll\(wrapper\.scrollTop\)/)
  // Pending retries never outlive the list they were for
  assert.match(shelf, /this\.destroyEntityComponents\(\)\n\s*this\.pageRetry\.reset\(\)/)
  assert.match(shelf, /beforeDestroy\(\) \{\n\s*this\.pageRetry\.reset\(\)/)
})
