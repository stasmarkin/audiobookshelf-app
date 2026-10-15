// The connection indicator in the app bar (components/widgets/ConnectionIndicator.vue) as a pure model: which icon,
// tone and message a combination of server reachability, network and socket shows.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../utils/serverIndicator.js', import.meta.url), 'utf8')
const { indicatorModel } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

const live = { serverState: 'online', networkConnected: true, hasServer: true, socketConnected: true, syncState: null, attemptingConnection: false, networkConnectionType: 'wifi', isNetworkUnmetered: true }
const model = (overrides) => indicatorModel({ ...live, ...overrides })

test('indicator shows offline for a dead server on live Wi-Fi', () => {
  const m = model({ serverState: 'offline', socketConnected: false })
  assert.equal(m.labelKey, 'LabelServerUnreachable')
  assert.equal(m.tone, 'warning')
  assert.equal(m.icon, 'cloud_off')
})

test('indicator shows no network', () => {
  for (const serverState of ['online', 'offline', 'unknown', 'unauthorized']) {
    const m = model({ serverState, networkConnected: false, attemptingConnection: true })
    assert.equal(m.labelKey, 'MessageNoNetworkConnection', serverState)
    assert.equal(m.icon, 'wifi_off')
    assert.equal(m.tone, 'error')
  }
})

test('indicator asks to sign in again', () => {
  const m = model({ serverState: 'unauthorized', socketConnected: false })
  assert.equal(m.labelKey, 'LabelSignInAgain')
  assert.equal(m.tone, 'error')
})

test('indicator is hidden without a configured server', () => {
  assert.equal(model({ hasServer: false, serverState: 'unknown' }).icon, null)
  assert.equal(model({ hasServer: false, serverState: 'unknown', attemptingConnection: true }).icon, 'cloud_sync', 'first connection in progress')
})

test('indicator keeps current online states', () => {
  assert.equal(model({ attemptingConnection: true, socketConnected: false }).icon, 'cloud_sync')
  assert.equal(model({ attemptingConnection: true }).labelKey, 'MessageAttemptingServerConnection')

  const cellular = model({ networkConnectionType: 'cellular' })
  assert.equal(cellular.icon, 'signal_cellular_alt')
  assert.equal(cellular.tone, 'muted')
  assert.equal(cellular.labelKey, 'MessageSocketConnectedOverUnmeteredCellular')
  assert.equal(model({ networkConnectionType: 'cellular', isNetworkUnmetered: false }).labelKey, 'MessageSocketConnectedOverMeteredCellular')

  const metered = model({ isNetworkUnmetered: false })
  assert.equal(metered.tone, 'metered')
  assert.equal(metered.icon, 'cloud_done')
  assert.equal(metered.labelKey, 'MessageSocketConnectedOverMeteredWifi')

  const wifi = model({})
  assert.deepEqual([wifi.icon, wifi.tone, wifi.labelKey], ['cloud_done', 'ok', 'MessageSocketConnectedOverUnmeteredWifi'])
})

test('a live server without its socket still shows the socket warning', () => {
  for (const serverState of ['online', 'unknown']) {
    const m = model({ serverState, socketConnected: false })
    assert.deepEqual([m.icon, m.tone, m.labelKey], ['cloud_off', 'warning', 'MessageSocketNotConnected'], serverState)
  }
})
