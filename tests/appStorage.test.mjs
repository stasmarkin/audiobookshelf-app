import test from 'node:test'
import assert from 'node:assert/strict'
import { appStorageDownloadFolder, appStorageFolderIds, appStorageLabel, isAppStorageFolderId, safFoldersFor, visibleFolders } from '../utils/appStorage.js'

test('isAppStorageFolderId', () => {
  assert.equal(isAppStorageFolderId('internal-book'), true)
  assert.equal(isAppStorageFolderId('appsd-79EC-9B33-podcast'), true)
  assert.equal(isAppStorageFolderId('cHJpbWFyeTpBdWRpb2Jvb2tzL0FCU1RFU1Q='), false)
  assert.equal(isAppStorageFolderId(undefined), false)
  assert.equal(isAppStorageFolderId(null), false)
})

test('noModalWithoutSaf', () => {
  const saf = { id: 'cHJpbWFyeTpBdWRpb2Jvb2tzL0FCU1RFU1Q=', mediaType: 'podcast' }
  const folders = [
    { id: 'internal-podcast', mediaType: 'podcast' },
    { id: 'appsd-79EC-9B33-podcast', mediaType: 'podcast' },
    { id: 'cHJpbWFyeTpCb29rcw==', mediaType: 'book' }
  ]
  assert.deepEqual(safFoldersFor(folders, 'podcast'), [])
  assert.deepEqual(safFoldersFor([...folders, saf], 'podcast'), [saf])
  assert.deepEqual(safFoldersFor(null, 'podcast'), [])
})

test('appStorageFolderHasEmptyId', () => {
  assert.deepEqual(appStorageDownloadFolder('book', 'X'), { id: '', name: 'X', mediaType: 'book' })
  const strings = { LabelAppStorageLocation: 'App storage · {0}', LabelDownloadStorageInternal: 'Internal', LabelDownloadStorageSdCard: 'SD card', LabelDownloadStorageUnavailable: 'SD card not available' }
  const card = { uuid: '79EC-9B33', mounted: true, freeBytes: 1 }
  assert.equal(appStorageLabel(strings, 'internal'), 'App storage · Internal')
  assert.equal(appStorageLabel(strings, 'internal', { ...card, mounted: false }), 'App storage · Internal')
  assert.equal(appStorageLabel(strings, 'sd:79EC-9B33', card), 'App storage · SD card')
  assert.equal(appStorageLabel(strings, 'sd:79EC-9B33', { ...card, mounted: false }), 'App storage · SD card not available')
  assert.equal(appStorageLabel(strings, 'sd:79EC-9B33', null), 'App storage · SD card not available')
  assert.equal(appStorageLabel(strings, 'sd:79EC-9B33', { ...card, uuid: '1111-2222' }), 'App storage · SD card not available', 'another card is not the configured one')
  assert.equal(appStorageLabel(strings, undefined), 'App storage · Internal')
})

test('appStorageFolderIds', () => {
  assert.deepEqual(appStorageFolderIds('internal'), ['internal-book', 'internal-podcast'])
  assert.deepEqual(appStorageFolderIds('sd:79EC-9B33'), ['appsd-79EC-9B33-book', 'appsd-79EC-9B33-podcast'])
  assert.deepEqual(appStorageFolderIds(undefined), ['internal-book', 'internal-podcast'])
  assert.deepEqual(appStorageFolderIds('sd:'), ['internal-book', 'internal-podcast'])
})

test('visibleFolders', () => {
  const saf = { id: 'cHJpbWFyeTpBdWRpb2Jvb2tzL0FCU1RFU1Q=', mediaType: 'book' }
  const internalBook = { id: 'internal-book', mediaType: 'book' }
  const internalPodcast = { id: 'internal-podcast', mediaType: 'podcast' }
  const sdBook = { id: 'appsd-79EC-9B33-book', mediaType: 'book' }
  const folders = [internalBook, internalPodcast, sdBook, saf]
  const current = appStorageFolderIds('sd:79EC-9B33')
  assert.ok(!visibleFolders(folders, {}, current).includes(internalBook), 'empty internal folder is hidden while storage is the SD card')
  assert.ok(visibleFolders(folders, { 'internal-book': 2 }, current).includes(internalBook), 'internal folder with items is shown')
  assert.ok(visibleFolders(folders, {}, current).includes(sdBook), 'current folder is shown even when empty')
  assert.ok(visibleFolders(folders, {}, current).includes(saf), 'user-picked folder is always shown')
  assert.deepEqual(visibleFolders(folders, { 'appsd-79EC-9B33-book': 0 }, appStorageFolderIds('internal')), [internalBook, internalPodcast, saf])
  assert.deepEqual(visibleFolders(null, {}, current), [])
})
