// Exercise the actual browser-development AbsDatabase fallback using Node's real Fetch and
// owned loopback servers. Android uses TokenRefresher; these tests do not claim browser CORS coverage.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createServer } from 'node:http'

const src = (await readFile(new URL('../plugins/capacitor/AbsDatabase.js', import.meta.url), 'utf8'))
  .replace(/^import .* from '@capacitor\/core'\r?\n/, '')
  .replace('export { AbsDatabase }', 'return AbsDatabase')

async function server() {
  const seen = []
  const state = { status: 200, location: null, before: () => {} }
  const http = createServer((req, res) => {
    seen.push({ url: req.url, method: req.method, headers: req.headers })
    req.on('end', () => {
      state.before()
      res.writeHead(state.status, { 'Content-Type': 'application/json', ...(state.location ? { Location: state.location } : {}) })
      res.end(JSON.stringify({ user: { accessToken: 'synthetic-access-new', refreshToken: 'synthetic-refresh-new' } }))
    })
    req.resume()
  })
  await new Promise((resolve) => http.listen(0, '127.0.0.1', resolve))
  return { seen, state, url: `http://127.0.0.1:${http.address().port}`, close: () => new Promise((resolve) => http.close(resolve)) }
}

function harness(address) {
  const data = new Map([
    ['device', JSON.stringify({ lastServerConnectionConfigId: 'B', serverConnectionConfigs: [{ id: 'A', address, token: 'synthetic-access-A' }, { id: 'B', address: 'http://b.invalid', token: 'synthetic-access-B' }] })],
    ['refresh_token_A', 'synthetic-refresh-A'], ['refresh_token_B', 'synthetic-refresh-B']
  ])
  const storage = { getItem: (key) => data.get(key) ?? null, setItem: (key, value) => data.set(key, String(value)), removeItem: (key) => data.delete(key) }
  const logs = []
  const api = new Function('registerPlugin', 'Capacitor', 'WebPlugin', 'localStorage', 'fetch', 'console', src)(
    (_, { web }) => web(), {}, class {}, storage,
    (url, options) => fetch(url, { ...options, signal: AbortSignal.timeout(5000) }),
    { log: (...args) => logs.push(args.join(' ')), error: (...args) => logs.push(args.join(' ')) })
  return { api, data, logs, refresh: () => api.refreshAccessToken({ serverConnectionConfigId: 'A' }) }
}

test('web refresh refuses cross-origin redirects before forwarding a synthetic refresh token', async () => {
  const source = await server(), target = await server()
  try {
    for (const status of [301, 302, 303, 307, 308]) {
      source.state.status = status
      source.state.location = `${target.url}/capture`
      const h = harness(source.url)
      const result = await h.refresh()
      assert.equal(target.seen.length, 0, `R01: no redirect destination request for ${status}`)
      assert.deepEqual(result, { status: 'transient' })
      assert.equal(h.data.get('refresh_token_A'), 'synthetic-refresh-A')
      assert.equal(h.data.get('refresh_token_B'), 'synthetic-refresh-B')
      assert.equal(JSON.parse(h.data.get('device')).lastServerConnectionConfigId, 'B')
      assert.equal(h.logs.some((line) => line.includes('synthetic-')), false)
    }
  } finally { await source.close(); await target.close() }
})

test('web refresh blocks same-origin, relative, userinfo, traversal, scheme and chained redirects', async () => {
  const source = await server(), target = await server()
  try {
    for (const location of ['/next', 'next', '../outside', '/abs/%2e%2e/outside', `${source.url}/auth/refresh`,
      `${target.url}/next`, target.url.replace('://', '://user:password@'), source.url.replace('http:', 'https:')]) {
      source.state.status = 302; source.state.location = location
      const before = source.seen.length
      assert.deepEqual(await harness(`${source.url}/abs`).refresh(), { status: 'transient' })
      assert.equal(source.seen.length, before + 1)
      assert.equal(target.seen.length, 0)
    }
  } finally { await source.close(); await target.close() }
})

test('direct retry after blocked redirect renews only saved A while B is active', async () => {
  const source = await server()
  try {
    source.state.status = 302; source.state.location = '/next'
    const h = harness(`${source.url}/reverse proxy`)
    assert.deepEqual(await h.refresh(), { status: 'transient' })
    source.state.status = 200; source.state.location = null
    assert.deepEqual(await h.refresh(), { status: 'success', accessToken: 'synthetic-access-new' })
    assert.deepEqual(await h.refresh(), { status: 'success', accessToken: 'synthetic-access-new' })
    assert.deepEqual(source.seen.map((r) => r.headers['x-refresh-token']), ['synthetic-refresh-A', 'synthetic-refresh-A', 'synthetic-refresh-new'])
    assert.ok(source.seen.every((r) => r.method === 'POST' && r.url === '/reverse%20proxy/auth/refresh' && !r.headers.authorization && !r.headers.cookie))
    assert.equal(h.data.get('refresh_token_B'), 'synthetic-refresh-B')
    const device = JSON.parse(h.data.get('device'))
    assert.equal(device.lastServerConnectionConfigId, 'B')
    assert.equal(device.serverConnectionConfigs[1].token, 'synthetic-access-B')
  } finally { await source.close() }
})

test('web refresh does not send a removed owners token or overwrite a newer login', async () => {
  const source = await server()
  try {
    const h = harness(source.url)
    const saved = h.data.get('device')
    const device = JSON.parse(saved)
    device.serverConnectionConfigs = device.serverConnectionConfigs.filter((s) => s.id !== 'A')
    h.data.set('device', JSON.stringify(device))
    assert.deepEqual(await h.refresh(), { status: 'rejected' })
    assert.equal(source.seen.length, 0)
    h.data.set('device', saved)
    source.state.before = () => h.data.set('refresh_token_A', 'synthetic-new-login')
    assert.deepEqual(await h.refresh(), { status: 'transient' })
    assert.equal(h.data.get('refresh_token_A'), 'synthetic-new-login')
  } finally { await source.close() }
})
