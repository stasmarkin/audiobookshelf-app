#!/usr/bin/env node
/*
 * Frontend source provenance check (no dependencies).
 *
 *   node scripts/verify-frontend-source.js preflight        before `nuxt generate`: right root, private build directory
 *   node scripts/verify-frontend-source.js stamp            after `nuxt generate`: build dir contained, store entries present
 *                                                           in the bundle, writes dist/frontend-provenance.json
 *   node scripts/verify-frontend-source.js verify-dir DIR [--source] [--commit SHA]
 *                                                           DIR = dist or an Android assets/public folder
 *   node scripts/verify-frontend-source.js verify-apk FILE.apk [--source] [--commit SHA]
 *
 * Why: Nuxt 2 puts its generated entry files (store, router, components) in a build directory. When that directory
 * was node_modules/.cache/nuxt and node_modules was a link to another checkout, the generated store entry resolved
 * `../store/index.js` in the OTHER checkout, so a worktree build silently used another branch's store. The build
 * still succeeded. nuxt.config.js now sets static.cacheDir (the setting `nuxt generate` honors) to a private .nuxt inside
 * the checkout, and this script fails the build
 * when the root is wrong, when a generated entry resolves outside the checkout, or when a store getter/mutation/action
 * in the source is missing from the bundle.
 *
 * Environment: SOURCE_COMMIT (when there is no .git, e.g. an exported copy), ABS_ALLOW_UNKNOWN_SOURCE=1 (explicit opt-out).
 */
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const cp = require('child_process')

const ROOT = fs.realpathSync(path.join(__dirname, '..'))
const PROVENANCE = 'frontend-provenance.json'
const SOURCE_DIRS = ['assets', 'components', 'layouts', 'middleware', 'mixins', 'objects', 'pages', 'plugins', 'static', 'store', 'strings', 'theme', 'utils']
const SOURCE_FILES = ['nuxt.config.js', 'package.json', 'package-lock.json']
const TEXT_EXT = new Set(['.js', '.vue', '.json', '.css', '.mjs', '.html', '.md'])

function fail(msg) {
  console.error(`verify-frontend-source: FAIL: ${msg}`)
  process.exit(1)
}
const sha = (buf) => crypto.createHash('sha256').update(buf).digest('hex')
const norm = (p) => p.split(path.sep).join('/')

/** Walk files below dir in a deterministic order. */
function walk(dir, base = dir, out = []) {
  if (!fs.existsSync(dir)) return out
  for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1))) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, base, out)
    else if (e.isFile()) out.push(norm(path.relative(base, p)))
  }
  return out
}

/** Hash of the frontend source tree; line endings are normalized so autocrlf does not change it. */
function sourceHash(root = ROOT) {
  const lines = []
  const add = (rel) => {
    let buf = fs.readFileSync(path.join(root, rel))
    if (TEXT_EXT.has(path.extname(rel).toLowerCase())) buf = Buffer.from(buf.toString('utf8').replace(/\r\n/g, '\n'))
    lines.push(`${rel}:${sha(buf)}`)
  }
  for (const d of SOURCE_DIRS) for (const f of walk(path.join(root, d), root)) add(f)
  for (const f of SOURCE_FILES) if (fs.existsSync(path.join(root, f))) add(f)
  return { hash: sha(lines.join('\n')), files: lines.length }
}

/** Getter, mutation and action names declared in store/*.js. */
function storeKeys(root = ROOT) {
  const keys = []
  const dir = path.join(root, 'store')
  for (const f of fs.existsSync(dir) ? fs.readdirSync(dir).filter((n) => n.endsWith('.js')) : []) {
    const text = fs.readFileSync(path.join(dir, f), 'utf8').replace(/\r\n/g, '\n')
    const re = /^export const (getters|mutations|actions) = \{\n([\s\S]*?)\n\}/gm
    let m
    while ((m = re.exec(text))) {
      for (const line of m[2].split('\n')) {
        const k = /^ {2}(?:async\s+)?([A-Za-z_$][\w$]*)\s*(?:\(|:)/.exec(line)
        if (k) keys.push({ file: f, kind: m[1], name: k[1] })
      }
    }
  }
  return keys
}

function readBundle(dir) {
  const nuxt = path.join(dir, '_nuxt')
  if (!fs.existsSync(nuxt)) fail(`no _nuxt folder in ${dir}`)
  return fs.readdirSync(nuxt).filter((n) => n.endsWith('.js')).map((n) => fs.readFileSync(path.join(nuxt, n), 'utf8')).join('\n')
}

function missingKeys(bundle, keys) {
  return keys.filter((k) => !new RegExp(`(?:^|[,{\\s])${k.name.replace(/\$/g, '\\$')}\\s*[:(]`).test(bundle))
}

/** Relative imports in the generated entry files must stay inside the checkout (node_modules excepted). */
function escapingEntries(buildDir, root = ROOT) {
  const bad = []
  if (!fs.existsSync(buildDir)) return bad
  for (const f of fs.readdirSync(buildDir).filter((n) => n.endsWith('.js'))) {
    const text = fs.readFileSync(path.join(buildDir, f), 'utf8')
    const re = /(?:require\(|from\s+|import\()\s*['"](\.{1,2}[\\/][^'"]*)['"]/g
    let m
    while ((m = re.exec(text))) {
      const target = path.resolve(buildDir, m[1].replace(/\\\\/g, '/'))
      let real = target
      try { real = fs.realpathSync(target) } catch (_) { try { real = path.join(fs.realpathSync(path.dirname(target)), path.basename(target)) } catch (__) { /* generated, not present */ } }
      const inside = real === root || real.startsWith(root + path.sep)
      if (!inside && !real.split(path.sep).includes('node_modules')) bad.push(`${f}: ${m[1]} -> ${real}`)
    }
  }
  return bad
}

function gitCommit(root = ROOT) {
  if (process.env.SOURCE_COMMIT) return process.env.SOURCE_COMMIT.trim()
  try {
    const top = cp.execFileSync('git', ['-C', root, 'rev-parse', '--show-toplevel'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
    if (fs.realpathSync(top) !== root) return null
    return cp.execFileSync('git', ['-C', root, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
  } catch (_) {
    return null
  }
}

function preflight() {
  if (fs.realpathSync(process.cwd()) !== ROOT) fail(`run from the repository root ${ROOT} (current directory: ${process.cwd()})`)
  const cfg = fs.readFileSync(path.join(ROOT, 'nuxt.config.js'), 'utf8')
  if (!/cacheDir:\s*['"]\.nuxt['"]/.test(cfg)) fail("nuxt.config.js must set static: { cacheDir: '.nuxt' } (a private build directory inside the checkout; for static targets nuxt generate ignores buildDir)")
  const nm = path.join(ROOT, 'node_modules')
  if (fs.existsSync(nm) && fs.lstatSync(nm).isSymbolicLink()) console.log(`note: node_modules is a link to ${fs.realpathSync(nm)}; dependencies are shared, generated sources are not`)
  console.log(`preflight ok: root ${ROOT}`)
}

function files(dir) {
  const out = {}
  // Android asset packaging drops dot-files (e.g. .nojekyll), so they are not part of what is stamped or verified
  for (const f of walk(dir)) if (f !== PROVENANCE && !f.split('/').some((s) => s.startsWith('.'))) out[f] = sha(fs.readFileSync(path.join(dir, f)))
  return out
}

function stamp() {
  const dist = path.join(ROOT, 'dist')
  const bad = escapingEntries(path.join(ROOT, '.nuxt'))
  if (bad.length) fail(`generated entries resolve outside the checkout:\n  ${bad.join('\n  ')}`)
  if (!fs.existsSync(path.join(ROOT, '.nuxt'))) fail('no .nuxt build directory in the checkout (was the private buildDir used?)')
  const keys = storeKeys()
  const miss = missingKeys(readBundle(dist), keys)
  if (miss.length) fail(`store entries in the source are missing from the bundle: ${miss.map((k) => `${k.file}:${k.kind}.${k.name}`).join(', ')}`)
  const commit = gitCommit() || (process.env.ABS_ALLOW_UNKNOWN_SOURCE === '1' ? 'unknown' : null)
  if (!commit) fail('cannot establish the source commit (no git checkout at the root and no SOURCE_COMMIT); set SOURCE_COMMIT or ABS_ALLOW_UNKNOWN_SOURCE=1')
  const src = sourceHash()
  const f = files(dist)
  const doc = { schema: 1, commit, sourceHash: src.hash, sourceFiles: src.files, storeEntries: keys.length, bundleHash: sha(Object.entries(f).map(([k, v]) => `${k}:${v}`).join('\n')), files: f }
  fs.writeFileSync(path.join(dist, PROVENANCE), JSON.stringify(doc, null, 1))
  console.log(`stamped dist: commit ${commit}, source ${src.hash.slice(0, 12)} (${src.files} files), ${keys.length} store entries verified, ${Object.keys(f).length} output files`)
}

function verifyDir(dir, opts = {}) {
  const pf = path.join(dir, PROVENANCE)
  if (!fs.existsSync(pf)) fail(`${dir} has no ${PROVENANCE}: the frontend was not built through the checked generate step`)
  const doc = JSON.parse(fs.readFileSync(pf, 'utf8'))
  const have = files(dir)
  const wrong = Object.entries(doc.files).filter(([k, v]) => have[k] !== v).map(([k]) => k)
  if (wrong.length) fail(`packaged files differ from what was stamped (${wrong.length}): ${wrong.slice(0, 5).join(', ')}`)
  const miss = missingKeys(readBundle(dir), storeKeys())
  if (miss.length) fail(`store entries missing from the packaged bundle: ${miss.map((k) => k.name).join(', ')}`)
  if (opts.commit && doc.commit !== opts.commit) fail(`stamped commit ${doc.commit} is not ${opts.commit}`)
  if (opts.source) {
    const now = sourceHash()
    if (now.hash !== doc.sourceHash) fail(`the source tree changed since the frontend was built (${doc.sourceHash.slice(0, 12)} -> ${now.hash.slice(0, 12)})`)
  }
  console.log(`verified ${dir}: commit ${doc.commit}, ${Object.keys(doc.files).length} files match, store entries present${opts.source ? ', source tree unchanged' : ''}`)
}

function verifyApk(apk, opts) {
  const tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'abs-apk-'))
  try {
    try { cp.execFileSync('tar', ['-xf', apk, '-C', tmp, 'assets/public'], { stdio: 'ignore' }) } catch (_) { cp.execFileSync('unzip', ['-q', apk, 'assets/public/*', '-d', tmp], { stdio: 'ignore' }) }
    verifyDir(path.join(tmp, 'assets', 'public'), opts)
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true })
  }
}

module.exports = { sourceHash, storeKeys, missingKeys, escapingEntries }

if (require.main === module) {
  const [mode, arg, ...rest] = process.argv.slice(2)
  const opts = { source: rest.includes('--source') || arg === '--source', commit: rest.includes('--commit') ? rest[rest.indexOf('--commit') + 1] : null }
  if (mode === 'preflight') preflight()
  else if (mode === 'stamp') stamp()
  else if (mode === 'verify-dir' && arg) verifyDir(path.resolve(arg), opts)
  else if (mode === 'verify-apk' && arg) verifyApk(path.resolve(arg), opts)
  else fail('usage: verify-frontend-source.js preflight | stamp | verify-dir DIR [--source] [--commit SHA] | verify-apk FILE [--source] [--commit SHA]')
}
