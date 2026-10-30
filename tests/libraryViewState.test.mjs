import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const toModule = (source) => import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const { REMEMBERED_TABS, bookshelfTabs, emptyViewState, otherLibraryId, parseLibraryViewState, planViewSwitch, recordTab, restoreTab, tabPath } = await toModule(await read('../utils/libraryViewState.js'))

const navBarSource = await read('../components/home/BookshelfNavBar.vue')
const { default: navBar } = await toModule(navBarSource.slice(navBarSource.indexOf('<script>') + 8, navBarSource.indexOf('</script>')))

test('bookshelfTabs lists the tabs of each library type', () => {
  assert.deepEqual(bookshelfTabs({ mediaType: 'book', hasPlaylists: false, isAdmin: true }), ['bookshelf', 'bookshelf-library', 'bookshelf-series', 'bookshelf-collections', 'bookshelf-authors'])
  assert.deepEqual(bookshelfTabs({ mediaType: 'podcast', hasPlaylists: true, isAdmin: false }), ['bookshelf', 'bookshelf-latest', 'bookshelf-library', 'bookshelf-playlists'])
  assert.ok(bookshelfTabs({ mediaType: 'podcast', hasPlaylists: false, isAdmin: true }).includes('bookshelf-add-podcast'))
})

test('bookshelfTabs matches BookshelfNavBar for every library type, role and playlist state', () => {
  for (const mediaType of ['book', 'podcast']) {
    for (const isAdmin of [false, true]) {
      for (const hasPlaylists of [false, true]) {
        const vm = { $strings: {}, isPodcast: mediaType === 'podcast', userIsAdminOrUp: isAdmin, userHasPlaylists: hasPlaylists ? 1 : 0, currentLibraryIcon: 'database' }
        const items = navBar.computed.items.call(vm)
        assert.deepEqual(
          items.map((item) => item.routeName),
          bookshelfTabs({ mediaType, hasPlaylists, isAdmin }),
          `${mediaType} admin=${isAdmin} playlists=${hasPlaylists}`
        )
        for (const item of items) assert.equal(tabPath(item.routeName), item.to)
      }
    }
  }
})

test('parseLibraryViewState falls back to an empty state and drops invalid fields', () => {
  for (const raw of [null, '{', 'null', '[]', '"text"']) assert.deepEqual(parseLibraryViewState(raw), emptyViewState(), String(raw))
  const parsed = parseLibraryViewState(
    JSON.stringify({
      owner: 42,
      libraries: {
        A: { tab: 'bookshelf-series', mobileOrderBy: 42, mobileOrderDesc: 'yes', mobileFilterBy: 'progress.finished' },
        B: { tab: 'item', mobileOrderBy: 'media.metadata.title', mobileOrderDesc: false },
        C: { tab: 'bookshelf-add-podcast' },
        D: 'broken'
      }
    })
  )
  assert.deepEqual(parsed, {
    owner: null,
    libraries: {
      A: { tab: 'bookshelf-series', mobileFilterBy: 'progress.finished' },
      B: { mobileOrderBy: 'media.metadata.title', mobileOrderDesc: false }
    }
  })
  assert.equal(parseLibraryViewState(JSON.stringify({ owner: 'A', libraries: [] })).owner, 'A')
})

test('recordTab remembers bookshelf tabs only and skips unchanged ones', () => {
  const state = { owner: 'A', libraries: { A: { tab: 'bookshelf', mobileOrderBy: 'addedAt' } } }
  assert.equal(recordTab(state, 'A', 'bookshelf-add-podcast'), null)
  assert.equal(recordTab(state, 'A', 'item-id'), null)
  assert.equal(recordTab(state, 'A', 'bookshelf'), null)
  const next = recordTab(state, 'A', 'bookshelf-latest')
  assert.deepEqual(next.libraries.A, { tab: 'bookshelf-latest', mobileOrderBy: 'addedAt' })
  assert.equal(state.libraries.A.tab, 'bookshelf', 'the input state is not mutated')
  assert.equal(recordTab(next, 'A', 'bookshelf-latest'), null)
  assert.deepEqual(recordTab(emptyViewState(), 'B', 'bookshelf-series').libraries, { B: { tab: 'bookshelf-series' } })
})

test('restoreTab returns the saved tab only when the library still has it', () => {
  const state = { owner: 'A', libraries: { A: { tab: 'bookshelf-playlists' }, B: { tab: 'bookshelf-series' } } }
  assert.equal(restoreTab(state, 'A', bookshelfTabs({ mediaType: 'book', hasPlaylists: false, isAdmin: false })), '/bookshelf')
  assert.equal(restoreTab(state, 'B', bookshelfTabs({ mediaType: 'book', hasPlaylists: false, isAdmin: false })), '/bookshelf/series')
  assert.equal(restoreTab(state, 'C', bookshelfTabs({ mediaType: 'book', hasPlaylists: true, isAdmin: false })), '/bookshelf')
})

test('planViewSwitch: the owner keeps the current sort and filter, the new library gets its saved ones', () => {
  const current = { mobileOrderBy: 'media.metadata.publishedYear', mobileOrderDesc: true, mobileFilterBy: 'progress.not-started', collapseSeries: true }

  const first = planViewSwitch(emptyViewState(), 'B', current)
  assert.equal(first.settingsUpdate, null)
  assert.equal(first.state.owner, 'B')

  const state = { owner: 'A', libraries: { A: { tab: 'bookshelf-library' }, B: { tab: 'bookshelf-latest', mobileOrderBy: 'media.metadata.title', mobileOrderDesc: false } } }
  const toB = planViewSwitch(state, 'B', current)
  assert.deepEqual(toB.state.libraries.A, { tab: 'bookshelf-library', mobileOrderBy: 'media.metadata.publishedYear', mobileOrderDesc: true, mobileFilterBy: 'progress.not-started' })
  assert.deepEqual(toB.settingsUpdate, { mobileOrderBy: 'media.metadata.title', mobileOrderDesc: false, mobileFilterBy: 'all' })
  assert.equal(toB.state.owner, 'B')

  const same = planViewSwitch(toB.state, 'B', current)
  assert.equal(same.settingsUpdate, null)
  assert.equal(same.state, toB.state)
})

test('planViewSwitch: a library without saved view resets the filter and keeps the owner snapshot', () => {
  const plan = planViewSwitch({ owner: 'A', libraries: {} }, 'B', { mobileOrderBy: 'addedAt', mobileOrderDesc: true, mobileFilterBy: 'series.x' })
  assert.deepEqual(plan.settingsUpdate, { mobileFilterBy: 'all' })
  assert.equal(plan.state.libraries.A.mobileFilterBy, 'series.x')
  assert.equal(plan.state.libraries.B, undefined)
})

test('otherLibraryId names the second library only when there are exactly two', () => {
  const two = [{ id: 'A' }, { id: 'B' }]
  assert.equal(otherLibraryId(two, 'A'), 'B')
  assert.equal(otherLibraryId(two, 'B'), 'A')
  assert.equal(otherLibraryId([{ id: 'A' }], 'A'), null)
  assert.equal(otherLibraryId([...two, { id: 'C' }], 'A'), null)
  assert.equal(otherLibraryId(null, 'A'), null)
})

test('every remembered tab maps to its bookshelf page', () => {
  assert.deepEqual(REMEMBERED_TABS.map(tabPath), ['/bookshelf', '/bookshelf/latest', '/bookshelf/library', '/bookshelf/series', '/bookshelf/collections', '/bookshelf/authors', '/bookshelf/playlists'])
})
