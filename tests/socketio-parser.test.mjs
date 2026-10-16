// P03 (socket.io-parser 4.2.4 -> 4.2.7, GHSA-677m-j7p3-52f9 and GHSA-2m8v-j782-fhvr): the parser the app's socket uses
// refuses binary packets that announce zero or too many attachments, and still decodes the events the server sends.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { Encoder, Decoder, PacketType } = require('socket.io-parser')

const atLeast = (version, floor) => {
  const [a, b] = [version, floor].map((v) => v.split('.').map(Number))
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i]
  return true
}

const decodeAll = (chunks) => {
  const decoder = new Decoder()
  const packets = []
  decoder.on('decoded', (p) => packets.push(p))
  for (const c of chunks) decoder.add(c)
  return packets
}

test('every locked socket.io-parser is 4.2.7 or later', async () => {
  const lock = JSON.parse(await readFile(new URL('../package-lock.json', import.meta.url), 'utf8'))
  const parsers = Object.entries(lock.packages).filter(([path]) => path.endsWith('node_modules/socket.io-parser'))
  assert.ok(parsers.length >= 1)
  for (const [path, entry] of parsers) assert.ok(atLeast(entry.version, '4.2.7'), `${path} is ${entry.version}`)
  // socket.io-client resolves the patched copy rather than a nested older one
  assert.equal(lock.packages['node_modules/socket.io-client/node_modules/socket.io-parser'], undefined)
})

test('a binary packet announcing zero attachments is refused', () => {
  assert.throws(() => decodeAll(['50-["progress",{"_placeholder":true,"num":0}]']), /Illegal attachments/)
})

test('a binary packet announcing more than ten attachments is refused', () => {
  assert.throws(() => decodeAll(['511-["progress",{}]']), /too many attachments/)
  assert.throws(() => decodeAll(['5999999-["progress",{}]']), /too many attachments/)
})

test('server events still decode: plain, with a namespace and an id, and with binary attachments', () => {
  const [plain] = decodeAll(['2["user_item_progress_updated",{"id":"p1","data":{"progress":0.5}}]'])
  assert.equal(plain.type, PacketType.EVENT)
  assert.deepEqual(plain.data, ['user_item_progress_updated', { id: 'p1', data: { progress: 0.5 } }])

  const [withNsp] = decodeAll(['2/admin,7["init",{"userId":"u1"}]'])
  assert.equal(withNsp.nsp, '/admin')
  assert.equal(withNsp.id, 7)

  const encoded = new Encoder().encode({ type: PacketType.EVENT, nsp: '/', data: ['cover', Buffer.from([1, 2, 3]), Buffer.from([4])] })
  assert.equal(encoded.length, 3)
  const [binary] = decodeAll(encoded)
  assert.equal(binary.type, PacketType.EVENT)
  assert.deepEqual([...binary.data[1]], [1, 2, 3])
  assert.deepEqual([...binary.data[2]], [4])
})
