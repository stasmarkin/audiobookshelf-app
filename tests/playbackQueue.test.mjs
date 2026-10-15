import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
// Load the Nuxt ES module without changing this project's CommonJS package type.
const helperSource = await readFile(new URL('../utils/playbackQueue.js', import.meta.url), 'utf8')
const helpers = await import(`data:text/javascript;base64,${Buffer.from(helperSource).toString('base64')}`)
const queueItemPayloadOf = helpers.queueItemPayload
const { downloadedBookItems, fetchSeriesBooks, nativeQueueItems, queueItemDisplay, resolvePlaybackQueue, resolveQueueSourceAppendItems, sessionToQueueItem } = helpers

const book = (id) => ({ id, mediaType: 'book', media: { numTracks: 1 } })
// Queue entry as the Play controls build it: server identity plus the local copy when one is complete.
const queueEntry = (libraryItemId, localId = null) => ({ libraryItemId, episodeId: null, localLibraryItem: localId ? { id: localId } : null })
const local = (id, overrides = {}) => ({ id: `local_device_${id}`, libraryItemId: id, serverConnectionConfigId: 'server', mediaType: 'book', media: { tracks: [{}] }, ...overrides })
function context(localItems = []) {
  return {
    $platform: 'android',
    $encode: (value) => Buffer.from(value).toString('base64'),
    $db: { getLocalLibraryItems: async () => localItems },
    $store: { state: { activeServerConfigId: 'server' }, getters: { 'user/getServerConnectionConfigId': 'server', 'globals/getLocalMediaProgressById': () => null, 'user/getUserMediaProgress': () => null } }
  }
}

// Since "stream past download gaps" (15ec49ec), every playable book in the source stays in the queue. Native gets server
// IDs for all of them and plays the complete local copy itself (PlaybackSourceResolver); the display item keeps the copy.
test('queue items preserve source order, carry server IDs for downloads and stream the gaps', () => {
  const books = ['c', 'b', 'a', 'notdownloaded', 'invalid', 'ebookonly', 'foreign', 'badlocal', 'missing'].map(book)
  books[4].isInvalid = true
  books[5].media = { numTracks: 0 }
  books[8].isMissing = true
  const items = downloadedBookItems(books, [local('a'), local('c'), local('invalid'), local('ebookonly'), local('foreign', { serverConnectionConfigId: 'other' }), local('badlocal', { media: { tracks: [] } })], 'server')
  assert.deepEqual(nativeQueueItems(items, 'server'), [
    { libraryItemId: 'c', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 },
    { libraryItemId: 'b', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 },
    { libraryItemId: 'a', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 },
    { libraryItemId: 'notdownloaded', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 },
    // Another server's download, or a local record with no tracks, is never used - the book streams
    { libraryItemId: 'foreign', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 },
    { libraryItemId: 'badlocal', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 }
  ])
  assert.deepEqual(
    items.map((item) => item.localLibraryItem?.id || null),
    ['local_device_c', null, 'local_device_a', null, null, null]
  )
  // Streamed entries carry the server book so Up Next can show a title/cover
  assert.equal(items[1].libraryItem, books[1])
  assert.equal(items[0].libraryItem, null)
})

test('partial downloads stream instead of playing the incomplete local copy', () => {
  const partial = { ...book('a'), media: { numTracks: 3 } }
  const items = downloadedBookItems([partial], [local('a')], 'server')
  assert.equal(items.length, 1)
  assert.equal(items[0].localLibraryItem, null)
  assert.deepEqual(nativeQueueItems(items), [{ libraryItemId: 'a', episodeId: null, expectedTracks: 3 }])
  const complete = downloadedBookItems([partial], [local('a', { media: { tracks: [{}, {}, {}] } })], 'server')
  assert.equal(complete[0].localLibraryItem.id, 'local_device_a')
})

test('a book downloaded through another library is matched by ino', () => {
  const items = downloadedBookItems([{ ...book('server-b'), ino: '42' }], [local('other-library-id', { ino: '42' })], 'server')
  assert.equal(items[0].localLibraryItem.id, 'local_device_other-library-id')
  // Native finds that copy again by ino and the copy's server
  assert.deepEqual(nativeQueueItems(items), [{ libraryItemId: 'server-b', episodeId: null, ino: '42', serverConnectionConfigId: 'server', expectedTracks: 1 }])
  assert.equal(downloadedBookItems([book('server-b')], [local('other-library-id', { ino: '42' })], 'server')[0].localLibraryItem, null)
})

test('queue items carry server ids', () => {
  const books = [{ ...book('s1'), ino: '101' }, { ...book('s2'), ino: '102' }, book('s3')]
  const items = downloadedBookItems(books, [local('s1', { ino: '101' }), local('other-lib', { ino: '102' })], 'server')
  assert.deepEqual(nativeQueueItems(items, 'server'), [
    { libraryItemId: 's1', episodeId: null, ino: '101', serverConnectionConfigId: 'server', expectedTracks: 1 },
    { libraryItemId: 's2', episodeId: null, ino: '102', serverConnectionConfigId: 'server', expectedTracks: 1 },
    { libraryItemId: 's3', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 }
  ])
  // The server's track count lets native stream a partial download instead of playing it
  const partial = downloadedBookItems([{ ...book('s4'), media: { numTracks: 3 } }], [local('s4')], 'server')
  assert.equal(nativeQueueItems(partial)[0].expectedTracks, 3)
  assert.equal(queueItemPayloadOf(partial[0]).expectedTracks, 3)
  // Without the active server known, a streamed item carries none
  assert.deepEqual(nativeQueueItems([items[2]]), [{ libraryItemId: 's3', episodeId: null, expectedTracks: 1 }])
})

// The series' books come from the catalog replica through $absData (offline-first 4b), 100 a call
const seriesReader = (answer) => {
  const calls = []
  return { calls, getLibraryItems: async (args) => (calls.push(args), answer(args)) }
}

test('fetchSeriesBooks pages through $absData in server sequence order and never collapses', async () => {
  const books = Array.from({ length: 230 }, (_, i) => book(String(230 - i)))
  const absData = seriesReader(({ page }) => ({ results: books.slice(page * 100, page * 100 + 100), total: 230 }))
  assert.deepEqual(await fetchSeriesBooks(absData, 'library', 'series', () => 'encoded'), books)
  assert.deepEqual(
    absData.calls,
    [0, 1, 2].map((page) => ({ libraryId: 'library', filter: 'series.encoded', sort: 'sequence', desc: false, limit: 100, page }))
  )
})

test('incomplete, failed or repeated series pages fail instead of playing a partial queue', async () => {
  await assert.rejects(fetchSeriesBooks(seriesReader(() => ({ results: [], total: 5 })), 'l', 's', String))
  await assert.rejects(
    fetchSeriesBooks(
      seriesReader(() => {
        throw new Error('offline')
      }),
      'l',
      's',
      String
    ),
    /offline/
  )
  await assert.rejects(fetchSeriesBooks(seriesReader(() => ({ results: [book('a')], total: 5 })), 'l', 's', String), /Series changed while loading/)
})

test('series queue offline recognizes downloaded copies', async () => {
  // Offline there is no signed-in connection; the copies belong to the active saved server
  const ctx = context([local('1'), local('3')])
  ctx.$store.getters['user/getServerConnectionConfigId'] = null
  ctx.$absData = seriesReader(() => ({ results: ['1', '2', '3'].map(book), total: 3 }))
  const result = await resolvePlaybackQueue(ctx, { libraryItemId: '1', queueSource: { sourceType: 'series', sourceId: 's', libraryId: 'l' } })
  assert.deepEqual(
    result.queue.items.map((item) => item.localLibraryItem?.id || null),
    ['local_device_1', null, 'local_device_3']
  )
  assert.equal(result.payload.libraryItemId, 'local_device_1')
  assert.equal(result.payload.startTime, undefined, 'native picks where a book starts')
})

test('Play All starts at the first unfinished book, local progress taking precedence over server progress', async () => {
  const ctx = context([local('a'), local('c'), local('d')])
  // a: finished locally even though the server still thinks it is in progress
  ctx.$store.getters['globals/getLocalMediaProgressById'] = (id) => ({ isFinished: id === 'local_device_a' })
  // b (streamed, no local copy) is finished according to the server
  ctx.$store.getters['user/getUserMediaProgress'] = (id) => ({ isFinished: id === 'b' })
  const result = await resolvePlaybackQueue(ctx, { queueSource: { sourceType: 'collection', sourceId: 'c', books: ['a', 'b', 'c', 'd'].map(book) } })
  assert.equal(result.queue.currentIndex, 2)
  assert.equal(result.payload.libraryItemId, 'local_device_c')
  assert.equal(result.payload.serverLibraryItemId, 'c')
  assert.equal(result.payload.episodeId, null)
  assert.deepEqual(
    result.queue.items.map((item) => item.libraryItemId),
    ['a', 'b', 'c', 'd']
  )
})

test('Play All streams an unfinished gap before later downloads', async () => {
  const ctx = context([local('a'), local('c')])
  ctx.$store.getters['globals/getLocalMediaProgressById'] = (id) => ({ isFinished: id === 'local_device_a' })
  const result = await resolvePlaybackQueue(ctx, { queueSource: { sourceType: 'collection', sourceId: 'c', books: ['a', 'b', 'c'].map(book) } })
  assert.equal(result.queue.currentIndex, 1)
  assert.equal(result.payload.libraryItemId, 'b')
  assert.deepEqual(nativeQueueItems(result.queue.items).slice(result.queue.currentIndex), [
    { libraryItemId: 'b', episodeId: null, expectedTracks: 1 },
    { libraryItemId: 'c', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 }
  ])
})

test('Play All with everything finished, or nothing playable, reports it instead of playing', async () => {
  const ctx = context([local('a')])
  ctx.$store.getters['globals/getLocalMediaProgressById'] = () => ({ isFinished: true })
  ctx.$store.getters['user/getUserMediaProgress'] = () => ({ isFinished: true })
  await assert.rejects(resolvePlaybackQueue(ctx, { queueSource: { sourceType: 'collection', sourceId: 'c', books: ['a', 'b'].map(book) } }), /finished/)
  await assert.rejects(resolvePlaybackQueue(context(), { queueSource: { sourceType: 'collection', sourceId: 'c', books: [{ ...book('e'), media: { numTracks: 0 } }] } }), /No downloaded audiobooks/)
})

test('starting a middle series book constructs the full queue and points at that book', async () => {
  const ctx = context(['1', '2', '3', '4'].map((id) => local(id)))
  ctx.$absData = seriesReader(() => ({ results: ['1', '2', '3', '4'].map(book), total: 4 }))
  const result = await resolvePlaybackQueue(ctx, { libraryItemId: '3', queueSource: { sourceType: 'series', sourceId: 's', libraryId: 'l' } })
  assert.equal(result.queue.currentIndex, 2)
  assert.equal(result.queue.items[result.queue.currentIndex + 1].localLibraryItem.id, 'local_device_4')
  assert.equal(nativeQueueItems(result.queue.items)[result.queue.currentIndex + 1].libraryItemId, '4')
})

test('collection middle start follows collection order; a missing download streams in its place', async () => {
  const result = await resolvePlaybackQueue(context([local('1'), local('3'), local('4')]), { libraryItemId: 'local_device_3', queueSource: { sourceType: 'collection', sourceId: 'c', books: ['4', '3', '2', '1'].map(book) } })
  assert.equal(result.queue.currentIndex, 1)
  assert.deepEqual(nativeQueueItems(result.queue.items).slice(2), [
    { libraryItemId: '2', episodeId: null, expectedTracks: 1 },
    { libraryItemId: '1', episodeId: null, serverConnectionConfigId: 'server', expectedTracks: 1 }
  ])
})

test('selecting an undownloaded book streams it and continues through the source', async () => {
  const payload = { libraryItemId: '2', queueSource: { sourceType: 'collection', sourceId: 'c', books: ['1', '2', '3'].map(book) } }
  const result = await resolvePlaybackQueue(context([local('1'), local('3')]), payload)
  assert.equal(result.queue.currentIndex, 1)
  assert.equal(result.payload.libraryItemId, '2')
  assert.equal(result.queue.items[2].localLibraryItem.id, 'local_device_3')
})

test('no source never acquires a queue; an item that is not a playable member of its source plays alone', async () => {
  assert.equal((await resolvePlaybackQueue(context([local('1')]), { libraryItemId: '1' })).queue, null)
  const outside = { libraryItemId: 'elsewhere', queueSource: { sourceType: 'collection', sourceId: 'c', books: ['1', '2'].map(book) } }
  const result = await resolvePlaybackQueue(context([local('1')]), outside)
  assert.equal(result.queue, null)
  assert.equal(result.payload, outside)
  const audioless = { libraryItemId: 'e', queueSource: { sourceType: 'collection', sourceId: 'c', books: [book('1'), { ...book('e'), media: { numTracks: 0 } }] } }
  assert.equal((await resolvePlaybackQueue(context(), audioless)).queue, null)
})

test('adding a source to a playing queue skips finished and already-queued items (by queue identity)', async () => {
  const ctx = context([local('1'), local('3')])
  ctx.$store.getters['user/getUserMediaProgress'] = (id) => ({ isFinished: id === '2' })
  const existing = [queueEntry('1', 'local_device_1')]
  const added = await resolveQueueSourceAppendItems(ctx, { sourceType: 'collection', sourceId: 'c', books: ['1', '2', '3', '4'].map(book) }, existing)
  assert.deepEqual(
    added.map((item) => [item.libraryItemId, item.localLibraryItem?.id || null]),
    [
      ['3', 'local_device_3'],
      ['4', null]
    ]
  )
})

test('session-synthesized, playlist, streamed and downloaded queue items all display a title', () => {
  const session = { libraryItemId: 's', episodeId: null, localLibraryItem: { id: 'local_s' }, localEpisodeId: null, displayTitle: 'Session', displayAuthor: 'Author', coverPath: '/c' }
  assert.deepEqual(nativeQueueItems([sessionToQueueItem(session)]), [{ libraryItemId: 's', episodeId: null }])
  // A Local Media item has no server id: native gets the device id
  assert.deepEqual(nativeQueueItems([sessionToQueueItem({ ...session, libraryItemId: null })]), [{ libraryItemId: 'local_s', episodeId: null }])
  assert.equal(queueItemDisplay(sessionToQueueItem(session)).title, 'Session')
  assert.equal(queueItemDisplay({ libraryItem: { media: { metadata: { title: 'Streamed', authorName: 'A' } } } }).title, 'Streamed')
  assert.equal(queueItemDisplay({ localLibraryItem: { media: { metadata: { title: 'Downloaded' } } } }).title, 'Downloaded')
  assert.equal(queueItemDisplay({}).title, 'Unknown')
})

test('iOS and web do not build Android queues or call the database', async () => {
  for (const platform of ['ios', 'web']) {
    const result = await resolvePlaybackQueue({ $platform: platform }, { libraryItemId: '1', queueSource: { sourceType: 'series' } })
    assert.equal(result.queue, null)
  }
})

test('podcast playlist sends server episode identity for downloads and streaming entries', async () => {
  const items = [
    { libraryItemId: 'p', episodeId: 'e1', localLibraryItem: { id: 'local_p' }, localEpisode: { id: 'local_e1' } },
    { libraryItemId: 'p', episodeId: 'e2' }
  ]
  const result = await resolvePlaybackQueue(context(), { libraryItemId: 'local_p', episodeId: 'local_e1', queueSource: { sourceType: 'playlist', sourceId: 'p1', items } })
  assert.equal(result.queue.currentIndex, 0)
  assert.deepEqual(nativeQueueItems(result.queue.items), [
    { libraryItemId: 'p', episodeId: 'e1' },
    { libraryItemId: 'p', episodeId: 'e2' }
  ])
  // An episode only on the device (no server episode id) keeps the device ids
  const deviceOnly = [{ libraryItemId: 'p', episodeId: 'local_e9', localLibraryItem: { id: 'local_p' }, localEpisode: { id: 'local_e9' } }]
  assert.deepEqual(nativeQueueItems(deviceOnly), [{ libraryItemId: 'local_p', episodeId: 'local_e9' }])
  assert.equal(result.payload.serverEpisodeId, 'e1')
})

// Exercise the real container methods with a mocked Capacitor boundary.
async function container(native) {
  const source = (await readFile(new URL('../components/app/AudioPlayerContainer.vue', import.meta.url), 'utf8')).replace(/\r\n/g, '\n')
  const script = source
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'globalThis.component =')
  const sandbox = { ...helpers, AbsAudioPlayer: native, AbsLogger: { info: async () => {} }, CellularPermissionHelpers: {}, console: { ...console, log: () => {} } }
  vm.runInNewContext(script, sandbox)
  const ctx = context([local('1'), local('2')])
  ctx.$diag = { debug: () => {} }
  ctx.savedQueue = null
  ctx.$localStore = { getPlaybackQueue: async () => ctx.savedQueue }
  ctx.$store.state = { playbackQueue: null, activeServerConfigId: 'server' }
  ctx.$store.commit = (name, payload) => {
    if (name === 'setPlaybackQueue') ctx.$store.state.playbackQueue = payload
    if (name === 'clearPlaybackQueue') ctx.$store.state.playbackQueue = null
  }
  ctx.$toast = {
    error: (message) => {
      ctx.error = message
    },
    info: (message) => {
      ctx.info = message
    }
  }
  ctx.$refs = {}
  ctx.$store.getters.getIsMediaStreaming = () => false
  Object.assign(ctx, sandbox.component.data())
  for (const [name, fn] of Object.entries(sandbox.component.methods)) ctx[name] = fn.bind(ctx)
  return ctx
}

test('unrelated playback clears both queues before starting; failed queue fetch does not play', async () => {
  const calls = []
  const ctx = await container({ clearPlaylistQueue: async () => calls.push('clear') })
  ctx.$store.state.playbackQueue = { sourceType: 'series' }
  ctx.startLibraryItem = async (_, queue) => {
    assert.equal(queue, null)
    calls.push('play')
  }
  await ctx.playLibraryItem({ libraryItemId: 'unrelated' })
  assert.deepEqual(calls, ['clear', 'play'])
  assert.equal(ctx.$store.state.playbackQueue, null)
  ctx.$absData = {
    getLibraryItems: async () => {
      throw new Error('offline')
    }
  }
  await ctx.playLibraryItem({ queueSource: { sourceType: 'series', sourceId: 's' } })
  assert.equal(ctx.error, 'offline')
  assert.equal(calls.filter((call) => call === 'play').length, 1)
})

test('native queue installation completes before local book preparation', async () => {
  const calls = []
  const ctx = await container({
    clearPlaylistQueue: async () => calls.push('clear'),
    setPlaylistQueue: async (queue) => {
      await Promise.resolve()
      assert.equal(queue.items[1].libraryItemId, '2')
      assert.equal(queue.items[1].serverConnectionConfigId, 'server')
      assert.equal(queue.items[1].episodeId, null)
      calls.push('queue')
    },
    prepareLibraryItem: async (payload) => {
      calls.push('play')
      assert.equal(payload.libraryItemId, 'local_device_1')
      assert.equal(payload.episodeId, null)
      return {}
    }
  })
  await ctx.playLibraryItem({ queueSource: { sourceType: 'collection', sourceId: 'c', books: [book('1'), book('2')] } })
  assert.deepEqual(calls, ['clear', 'queue', 'play'])
  assert.equal(ctx.$store.state.playbackQueue.sourceType, 'collection')
})

test('failed native queue installation prevents playback', async () => {
  let played = false
  const ctx = await container({
    clearPlaylistQueue: async () => {},
    setPlaylistQueue: async () => {
      throw new Error('bridge failure')
    },
    prepareLibraryItem: async () => {
      played = true
    }
  })
  await ctx.playLibraryItem({ queueSource: { sourceType: 'collection', sourceId: 'c', books: [book('1')] } })
  assert.equal(played, false)
  assert.equal(ctx.$store.state.playbackQueue, null)
  assert.equal(ctx.error, 'bridge failure')
})

test('delayed ENDED reads authoritative native index; stale responses cannot replace a new queue', async () => {
  let resolve
  const ctx = await container({
    getPlaylistQueue: () =>
      new Promise((done) => {
        resolve = done
      })
  })
  const queue = { sourceType: 'collection', sourceId: 'c', items: [{ libraryItemId: 'a' }, { libraryItemId: 'b' }, { libraryItemId: 'c' }], currentIndex: 0 }
  ctx.$store.state.playbackQueue = queue
  const sync = ctx.onPlaybackEnded()
  resolve({ items: nativeQueueItems(queue.items), currentIndex: 2 })
  await sync
  assert.equal(ctx.$store.state.playbackQueue.currentIndex, 2)
  const stale = ctx.onPlaybackEnded()
  const replacement = { ...queue, sourceId: 'new' }
  ctx.$store.state.playbackQueue = replacement
  resolve({ items: [], currentIndex: -1 })
  await stale
  assert.equal(ctx.$store.state.playbackQueue, replacement)
  const ended = ctx.onPlaybackEnded()
  resolve({ items: [], currentIndex: -1 })
  await ended
  assert.equal(ctx.$store.state.playbackQueue, null)
})

// Mirrors the approved device scenario: the UI is removed while a 12-item queue keeps playing (or is
// rebuilt by a media-button restore), then a restarted UI reattaches to the native session.
// Objects built inside the vm sandbox have another realm's prototypes; compare them as plain data.
const plain = (value) => JSON.parse(JSON.stringify(value))
// removeFromQueue/reorderQueue start mutateQueue without returning its promise.
const settle = () => new Promise((resolve) => setImmediate(resolve))
const twelve = Array.from({ length: 12 }, (_, i) => queueEntry(`b${i}`, `local_device_b${i}`))
const nativeTwelve = (currentIndex) => ({ queue: { items: nativeQueueItems(twelve), currentIndex } })

test('reattached UI restores the Up Next display from the native queue and its current index', async () => {
  const ctx = await container({})
  ctx.savedQueue = { sourceType: 'series', sourceId: 's', items: twelve, currentIndex: 3 }
  await ctx.nativePlaybackReattached(nativeTwelve(7))
  assert.equal(ctx.$store.state.playbackQueue.items.length, 12)
  assert.equal(ctx.$store.state.playbackQueue.currentIndex, 7)
  assert.equal(ctx.$store.state.playbackQueue.sourceId, 's')
})

test('reattach never shows a saved display copy that no longer matches the native queue', async () => {
  const ctx = await container({})
  ctx.savedQueue = { sourceType: 'series', sourceId: 's', items: twelve.slice(0, 11), currentIndex: 3 }
  await ctx.nativePlaybackReattached(nativeTwelve(7))
  assert.equal(ctx.$store.state.playbackQueue, null)
  const reordered = [twelve[1], twelve[0], ...twelve.slice(2)]
  ctx.savedQueue = { sourceType: 'series', sourceId: 's', items: reordered, currentIndex: 0 }
  await ctx.nativePlaybackReattached(nativeTwelve(7))
  assert.equal(ctx.$store.state.playbackQueue, null)
})

test('reattach tolerates missing native or saved queue data and never replaces a newer queue', async () => {
  const ctx = await container({})
  ctx.savedQueue = { sourceType: 'series', sourceId: 's', items: twelve, currentIndex: 0 }
  await ctx.nativePlaybackReattached(null)
  await ctx.nativePlaybackReattached({ queue: { items: [], currentIndex: -1 } })
  assert.equal(ctx.$store.state.playbackQueue, null)
  ctx.savedQueue = null
  await ctx.nativePlaybackReattached(nativeTwelve(2))
  assert.equal(ctx.$store.state.playbackQueue, null)
  ctx.savedQueue = { items: null }
  await ctx.nativePlaybackReattached(nativeTwelve(2))
  assert.equal(ctx.$store.state.playbackQueue, null)
  const newer = { sourceType: 'collection', sourceId: 'new', items: [twelve[0]], currentIndex: 0 }
  ctx.$store.state.playbackQueue = newer
  ctx.savedQueue = { sourceType: 'series', sourceId: 's', items: twelve, currentIndex: 0 }
  await ctx.nativePlaybackReattached(nativeTwelve(2))
  assert.equal(ctx.$store.state.playbackQueue, newer)
})

test('queue edits that race a native auto-advance resync instead of overwriting native', async () => {
  const writes = []
  const ctx = await container({
    getPlaylistQueue: async () => ({ items: nativeQueueItems(twelve), currentIndex: 4 }),
    setPlaylistQueue: async (queue) => writes.push(queue)
  })
  // Vue still thinks item 3 is current, but native already advanced to 4
  ctx.$store.state.playbackQueue = { sourceType: 'series', sourceId: 's', items: twelve, currentIndex: 3 }
  ctx.removeFromQueue(twelve[10])
  await settle()
  assert.equal(writes.length, 0)
  assert.equal(ctx.error, 'Queue changed, try again')
  assert.equal(ctx.$store.state.playbackQueue.currentIndex, 4)
  // Once in sync the edit goes through, keeping native's index
  ctx.removeFromQueue(twelve[10])
  await settle()
  assert.equal(writes.length, 1)
  assert.equal(writes[0].currentIndex, 4)
  assert.equal(writes[0].items.length, 11)
  assert.ok(!writes[0].items.some((item) => item.libraryItemId === 'b10'))
  // Already played items (index <= current) are never removed
  ctx.removeFromQueue(twelve[1])
  await settle()
  assert.equal(writes[1].items.length, 11)
})

test('reordering keeps the played prefix and current item fixed', async () => {
  const writes = []
  const ctx = await container({ getPlaylistQueue: async () => ({ items: nativeQueueItems(twelve), currentIndex: 2 }), setPlaylistQueue: async (queue) => writes.push(queue) })
  ctx.$store.state.playbackQueue = { sourceType: 'series', sourceId: 's', items: twelve, currentIndex: 2 }
  ctx.reorderQueue(twelve.slice(3).reverse())
  await settle()
  assert.deepEqual(
    writes[0].items.map((item) => item.libraryItemId),
    [
      'b0',
      'b1',
      'b2',
      ...twelve
        .slice(3)
        .reverse()
        .map((item) => item.libraryItemId)
    ]
  )
  assert.equal(writes[0].currentIndex, 2)
})

test('adding to the queue while nothing is queued synthesizes one from the playing session', async () => {
  const writes = []
  const ctx = await container({ setPlaylistQueue: async (queue) => writes.push(queue) })
  ctx.currentPlaybackSession = { libraryItemId: 'p', episodeId: null, localLibraryItem: { id: 'local_p' }, displayTitle: 'Playing' }
  await ctx.addSingleItemToQueue(queueEntry('n', 'local_device_n'))
  assert.deepEqual(plain(writes[0]), {
    items: [
      { libraryItemId: 'p', episodeId: null, serverConnectionConfigId: 'server' },
      { libraryItemId: 'n', episodeId: null, serverConnectionConfigId: 'server' }
    ],
    currentIndex: 0
  })
  assert.equal(ctx.$store.state.playbackQueue.sourceType, 'adhoc')
})

// Up Next display copy persistence (plugins/localStore.js) with a mocked Capacitor Preferences.
async function localStoreWith(prefs) {
  const source = (await readFile(new URL('../plugins/localStore.js', import.meta.url), 'utf8')).replace(/^import .*$/gm, '').replace('export default', 'globalThis.plugin =')
  const sandbox = { Preferences: prefs, console: { ...console, error: () => {}, log: () => {} } }
  vm.runInNewContext(source, sandbox)
  let store
  sandbox.plugin({ app: {}, store: {} }, (name, value) => (store = value))
  return store
}

test('Up Next display copy round-trips, clears, and survives malformed or missing saved data', async () => {
  const data = {}
  const store = await localStoreWith({
    set: async ({ key, value }) => (data[key] = value),
    get: async ({ key }) => ({ value: data[key] ?? null }),
    remove: async ({ key }) => delete data[key]
  })
  assert.equal(await store.getPlaybackQueue(), null)
  const queue = { sourceType: 'series', sourceId: 's', items: twelve, currentIndex: 5 }
  await store.setPlaybackQueue(queue)
  assert.deepEqual(plain(await store.getPlaybackQueue()), plain(queue))
  await store.setPlaybackQueue(null)
  assert.equal('playbackQueue' in data, false)
  data.playbackQueue = '{not json'
  assert.equal(await store.getPlaybackQueue(), null)
  const bridgeDown = async () => {
    throw new Error('bridge')
  }
  const failing = await localStoreWith({ get: bridgeDown, set: bridgeDown, remove: bridgeDown })
  assert.equal(await failing.getPlaybackQueue(), null)
  await failing.setPlaybackQueue(queue)
})

// Vuex queue state (store/index.js): mutations persist the display copy; getters expose current/upcoming.
async function storeModule() {
  const source = (await readFile(new URL('../store/index.js', import.meta.url), 'utf8')).replace(/^import .*$/gm, '').replace(/export const (\w+) =/g, 'globalThis.$1 =')
  const sandbox = {}
  vm.runInNewContext(source, sandbox)
  return sandbox
}

test('Vuex queue mutations persist the display copy and getters expose current/upcoming items', async () => {
  const { state, mutations, getters } = await storeModule()
  const s = state()
  const saved = []
  const self = { $localStore: { setPlaybackQueue: (queue) => saved.push(queue) } }
  mutations.setPlaybackQueue.call(self, s, { sourceType: 'series', sourceId: 's', items: twelve, currentIndex: 10 })
  assert.equal(getters.getPlaybackQueueCurrentItem(s), twelve[10])
  assert.deepEqual(plain(getters.getPlaybackQueueUpcomingItems(s)), plain([twelve[11]]))
  mutations.clearPlaybackQueue.call(self, s)
  assert.equal(s.playbackQueue, null)
  assert.deepEqual(
    saved.map((queue) => queue && queue.currentIndex),
    [10, null]
  )
  assert.equal(getters.getPlaybackQueueCurrentItem(s), null)
  assert.deepEqual(plain(getters.getPlaybackQueueUpcomingItems(s)), [])
})
