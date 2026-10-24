import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

// Build-root safeguard (scripts/verify-frontend-source.js): a frontend built from the wrong checkout must fail loudly.
const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const script = path.join(root, 'scripts', 'verify-frontend-source.js')
const { storeKeys, missingKeys, escapingEntries } = require(script)
const run = (args, cwd = root) => spawnSync(process.execPath, [script, ...args], { cwd, encoding: 'utf8', env: { ...process.env, SOURCE_COMMIT: 'test-commit' } })
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'abs-prov-'))

test('the store source declares getters, mutations and actions, including the PR35 long-jump getters', () => {
  const keys = storeKeys()
  assert.ok(keys.length > 50)
  assert.ok(keys.some((k) => k.kind === 'getters' && k.name === 'getLongJumpForwardTime'))
  assert.ok(keys.some((k) => k.kind === 'mutations'))
})

test('a bundle without a source getter is reported', () => {
  const keys = storeKeys()
  const full = keys.map((k) => `${k.name}:function(e){}`).join(',')
  assert.deepEqual(missingKeys(full, keys), [])
  const stale = keys.filter((k) => k.name !== 'getLongJumpForwardTime').map((k) => `${k.name}:function(e){}`).join(',')
  assert.deepEqual(missingKeys(stale, keys).map((k) => k.name), ['getLongJumpForwardTime'])
})

test('generated entries that resolve outside the checkout are detected', () => {
  const base = tmp()
  const checkout = path.join(base, 'checkout')
  const other = path.join(base, 'other')
  fs.mkdirSync(path.join(checkout, '.nuxt'), { recursive: true })
  fs.mkdirSync(path.join(checkout, 'store'), { recursive: true })
  fs.mkdirSync(path.join(other, 'store'), { recursive: true })
  fs.writeFileSync(path.join(checkout, 'store', 'index.js'), '')
  fs.writeFileSync(path.join(other, 'store', 'index.js'), '')
  fs.writeFileSync(path.join(checkout, '.nuxt', 'ok.js'), "import s from '../store/index.js'")
  assert.deepEqual(escapingEntries(path.join(checkout, '.nuxt'), fs.realpathSync(checkout)), [])
  fs.writeFileSync(path.join(checkout, '.nuxt', 'store.js'), `require('../../other/store/index.js')`)
  const bad = escapingEntries(path.join(checkout, '.nuxt'), fs.realpathSync(checkout))
  assert.equal(bad.length, 1)
  assert.match(bad[0], /store\.js/)
})

test('preflight fails from the wrong directory and passes from the checkout', () => {
  const wrong = run(['preflight'], os.tmpdir())
  assert.notEqual(wrong.status, 0)
  assert.match(wrong.stderr, /run from the repository root/)
  const right = run(['preflight'])
  assert.equal(right.status, 0, right.stderr)
})

test('nuxt.config.js keeps the private build directory that generate honors', () => {
  const cfg = fs.readFileSync(path.join(root, 'nuxt.config.js'), 'utf8')
  assert.match(cfg, /static:\s*\{\s*cacheDir:\s*'\.nuxt'/)
})

test('verify-dir needs the provenance stamp and notices changed or incomplete output', () => {
  const dist = tmp()
  fs.mkdirSync(path.join(dist, '_nuxt'))
  const bundle = storeKeys().map((k) => `${k.name}:function(e){}`).join(',')
  fs.writeFileSync(path.join(dist, '_nuxt', 'app.js'), bundle)
  fs.writeFileSync(path.join(dist, '.nojekyll'), '')
  const unstamped = run(['verify-dir', dist])
  assert.notEqual(unstamped.status, 0)
  assert.match(unstamped.stderr, /frontend-provenance\.json/)
  const sha = (buf) => require('node:crypto').createHash('sha256').update(buf).digest('hex')
  const stamp = { schema: 1, commit: 'test-commit', sourceHash: 'x', files: { '_nuxt/app.js': sha(fs.readFileSync(path.join(dist, '_nuxt', 'app.js'))) } }
  fs.writeFileSync(path.join(dist, 'frontend-provenance.json'), JSON.stringify(stamp))
  assert.equal(run(['verify-dir', dist]).status, 0)
  assert.notEqual(run(['verify-dir', dist, '--commit', 'another-commit']).status, 0)
  fs.writeFileSync(path.join(dist, '_nuxt', 'app.js'), bundle + ';')
  const changed = run(['verify-dir', dist])
  assert.notEqual(changed.status, 0)
  assert.match(changed.stderr, /differ from what was stamped/)
  // a bundle from another checkout (getter missing) is refused even if the stamp matches it
  const stale = bundle.replace('getLongJumpForwardTime:function(e){}', '')
  fs.writeFileSync(path.join(dist, '_nuxt', 'app.js'), stale)
  stamp.files['_nuxt/app.js'] = sha(Buffer.from(stale))
  fs.writeFileSync(path.join(dist, 'frontend-provenance.json'), JSON.stringify(stamp))
  const missing = run(['verify-dir', dist])
  assert.notEqual(missing.status, 0)
  assert.match(missing.stderr, /getLongJumpForwardTime/)
})
