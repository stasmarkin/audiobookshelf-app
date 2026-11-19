import test from 'node:test'
import assert from 'node:assert/strict'
import { isSessionOf, endPlaybackOf } from '../utils/finishPlaying.js'

const serverEpisodeSession = { libraryItemId: 'li_pod', episodeId: 'ep_1' }
const localEpisodeSession = { libraryItemId: 'li_pod', episodeId: 'ep_1', localLibraryItem: { id: 'local_pod' }, localEpisodeId: 'local_ep_1' }
const bookSession = { libraryItemId: 'li_book', episodeId: null }

test('isSessionOf matches the playing episode by server ids', () => {
  assert.equal(isSessionOf(serverEpisodeSession, { libraryItemId: 'li_pod', episodeId: 'ep_1' }), true)
  assert.equal(isSessionOf(serverEpisodeSession, { libraryItemId: 'li_pod', episodeId: 'ep_2' }), false)
  assert.equal(isSessionOf(serverEpisodeSession, { libraryItemId: 'li_pod' }), false)
})

test('isSessionOf matches a downloaded episode by local ids alone', () => {
  assert.equal(isSessionOf(localEpisodeSession, { localLibraryItemId: 'local_pod', localEpisodeId: 'local_ep_1' }), true)
  assert.equal(isSessionOf(localEpisodeSession, { localLibraryItemId: 'local_pod', localEpisodeId: 'local_ep_2' }), false)
})

test('isSessionOf matches a book only without an episode id', () => {
  assert.equal(isSessionOf(bookSession, { libraryItemId: 'li_book' }), true)
  assert.equal(isSessionOf(bookSession, { libraryItemId: 'li_book', episodeId: 'ep_1' }), false)
  assert.equal(isSessionOf(bookSession, { libraryItemId: 'li_other' }), false)
})

test('isSessionOf is false without a session or ids', () => {
  assert.equal(isSessionOf(null, { libraryItemId: 'li_book' }), false)
  assert.equal(isSessionOf(bookSession, {}), false)
  assert.equal(isSessionOf({ episodeId: null }, { localLibraryItemId: undefined }), false)
})

test('endPlaybackOf closes and waits only when the item is playing', async () => {
  const events = []
  const close = async () => {
    await new Promise((resolve) => setTimeout(resolve, 10))
    events.push('closed')
  }
  assert.equal(await endPlaybackOf(serverEpisodeSession, { libraryItemId: 'li_pod', episodeId: 'ep_2' }, close), false)
  assert.deepEqual(events, [])
  assert.equal(await endPlaybackOf(serverEpisodeSession, { libraryItemId: 'li_pod', episodeId: 'ep_1' }, close), true)
  assert.deepEqual(events, ['closed'])
})

test('endPlaybackOf gives up waiting after the timeout and survives a failed close', async () => {
  const hanging = () => new Promise(() => {})
  assert.equal(await endPlaybackOf(bookSession, { libraryItemId: 'li_book' }, hanging, 20), true)
  const failing = async () => {
    throw new Error('no player')
  }
  assert.equal(await endPlaybackOf(bookSession, { libraryItemId: 'li_book' }, failing, 20), true)
})
