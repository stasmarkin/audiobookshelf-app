import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

// The opening tag of the first element whose opening tag contains `marker`, and the source of that whole element
const element = (source, marker) => {
  const at = source.indexOf(marker)
  assert.ok(at >= 0, marker)
  const open = source.lastIndexOf('<div', at)
  let depth = 0
  const tags = /<\/?div\b[^>]*>/g
  tags.lastIndex = open
  for (let m; (m = tags.exec(source)); ) {
    if (m[0].startsWith('</')) depth--
    else if (!m[0].endsWith('/>')) depth++
    if (depth === 0) return source.slice(open, m.index + m[0].length)
  }
  throw new Error(`unclosed ${marker}`)
}

// Library list rows: the downloaded tick sat at the row's top-right, over the top 13px of the vertically centered 40x40
// Play key, so a tap there opened the item instead of playing (S26). It now sits centered above the key in the key's
// column, as a fixed 16px SVG: the icon font has no weight axis and its glyph grows with Android font scaling.
const COLUMN = 'v-if="showPlayButton" class="absolute top-0 bottom-0 right-0 h-full flex items-center justify-center z-20 pr-1"'
const TICK_SVG = '<svg style="width: 16px; height: 16px" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 11.5l4 4 8-8M6 20h12" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" /></svg>'

test('list row: the downloaded tick is above the Play key, off the artwork', async () => {
  const list = await read('../components/cards/LazyListBookCard.vue')
  const cover = element(list, 'class="card-artwork list-card-cover relative"')
  const column = element(list, COLUMN)
  assert.doesNotMatch(list, /download_done/, 'no icon-font glyph')
  assert.doesNotMatch(cover, /downloaded-indicator|<svg/, 'the artwork stays clean')
  // After the key, across the key's width (right-1 matches the column's pr-1), lifted 3px into the row padding. It takes
  // no taps, so it can't steal the key's touches
  const tick = element(column, 'class="downloaded-indicator')
  assert.ok(tick.startsWith('<div v-if="localLibraryItem || isLocal" class="downloaded-indicator absolute left-0 right-1 flex justify-center text-success pointer-events-none" style="top: -3px">'))
  assert.ok(tick.includes(TICK_SVG))
  assert.ok(column.indexOf('downloaded-indicator') > column.indexOf('</button>'))
  // Without a Play key it keeps the row's top-right corner, also tap-through
  assert.ok(list.includes('<div v-else-if="localLibraryItem || isLocal" class="downloaded-indicator absolute top-0 right-1 text-success pointer-events-none">'))
  assert.equal((list.match(/downloaded-indicator/g) || []).length, 2)
})

test('list row: the Play key and its column are unchanged', async () => {
  const list = await read('../components/cards/LazyListBookCard.vue')
  const column = element(list, COLUMN)
  assert.match(column, /<button type="button" class="list-play-key relative rounded-full bg-fg-muted\/50" :class="\{ 'p-2': !playerIsStartingForThisMedia \}" @click\.stop\.prevent="play">/)
  assert.ok(column.includes('<span v-if="!playerIsStartingForThisMedia" class="material-symbols text-2xl fill text-white">'))
  assert.match(list, /<div class="flex-grow pl-2" :class="showPlayButton \? 'pr-12' : 'pr-2'">/)
  // The only other positioned element in the column is the tap-through tick
  const inner = column.slice(column.indexOf('>') + 1)
  const rest = inner.replace(element(inner, 'class="downloaded-indicator'), '')
  assert.doesNotMatch(rest, /absolute/)
})

// Switch Server/User footer: each link shows its destination owner's GitHub avatar from a bundled file, not a runtime URL
test('connect footer: bundled GitHub owner avatars, unchanged links and wording', async () => {
  const page = await read('../pages/connect.vue')
  assert.ok(page.includes("{ url: 'https://github.com/advplyr/audiobookshelf', label: this.$strings.LabelOfficialAudiobookshelf, caption: this.$strings.LabelOfficialAudiobookshelfCaption, avatar: '/avatars/github-advplyr.png' }"))
  assert.ok(page.includes("{ url: 'https://github.com/ADD-OCD/audiobookshelfplus-app', label: this.$strings.LabelAudiobookshelfPlus, caption: this.$strings.LabelAudiobookshelfPlusCaption, avatar: '/avatars/github-ADD-OCD.jpg' }"))
  assert.ok(page.includes('<img :src="link.avatar" alt="" aria-hidden="true" class="w-7 h-7 rounded-full shrink-0 mr-2" />'))
  assert.doesNotMatch(page, /githubusercontent|github\.com\/[^'"\s]+\.(png|jpe?g)/, 'no runtime avatar URL')
  for (const file of ['github-advplyr.png', 'github-ADD-OCD.jpg']) {
    const bytes = await readFile(new URL(`../static/avatars/${file}`, import.meta.url))
    assert.ok(bytes.length > 1000 && bytes.length < 40000, file)
  }
  const strings = JSON.parse(await read('../strings/en-us.json'))
  assert.equal(strings.LabelOfficialAudiobookshelf, 'Official Audiobookshelf')
  assert.equal(strings.LabelOfficialAudiobookshelfCaption, 'Upstream project')
  assert.equal(strings.LabelAudiobookshelfPlus, 'Audiobookshelf+')
  assert.equal(strings.LabelAudiobookshelfPlusCaption, 'Unofficial fork (this app)')
})

// Rescan Folder: activity is shown at once, progress comes from AbsFileSystem "onRescanProgress" scoped to this folder,
// and failures are never reported as "Matched 0 item(s)".
test('folder page: rescan status, folder-scoped progress and error handling', async () => {
  const page = await read('../pages/localMedia/folders/_id.vue')
  assert.match(page, /<div v-if="scan" class="flex items-start mb-2 text-sm" role="status" aria-live="polite">/)
  assert.match(page, /this\.scan = \{ folderId: this\.folderId, phase: 'loading'/)
  // The selected library scopes author/title matches; relinked items refresh the cached local progress
  assert.ok(page.includes('AbsFileSystem.rescanFolder({ folderId: this.folderId, libraryId: this.$store.state.libraries.currentLibraryId })'))
  assert.ok(page.includes("await this.$store.dispatch('globals/loadLocalMediaProgress')"))
  assert.match(page, /if \(!data \|\| data\.folderId !== this\.folderId\) return/)
  assert.match(page, /if \(localLibraryItem\) this\.newLocalLibraryItem\(localLibraryItem\)/)
  // Unsupported (podcast) folders and running scans don't offer Rescan
  assert.match(page, /if \(this\.mediaType === 'book' && !this\.isScanning\) \{/)
  // An error result is a failure with its own message, before any success toast
  const rescan = page.slice(page.indexOf('async rescanFolder()'), page.indexOf('rescanErrorMessage(error) {'))
  assert.ok(rescan.indexOf("phase: 'failed'") < rescan.indexOf('MessageRescanFolderResult'))
  // The listener is removed with the page, also when the page goes before it was registered
  assert.match(page, /const listener = await AbsFileSystem\.addListener\('onRescanProgress', this\.onRescanProgress\)/)
  assert.match(page, /if \(this\._isDestroyed\) \{\n\s*listener\?\.remove\(\)/)
  // A page reopened mid-scan shows the running scan before its next event
  assert.match(page, /await AbsFileSystem\.isRescanning\(\{ folderId: this\.folderId \}\)/)
  assert.match(page, /this\.rescanProgressListener\?\.remove\(\)/)
  const strings = JSON.parse(await read('../strings/en-us.json'))
  for (const key of ['LabelRescanChecked', 'LabelRescanComplete', 'LabelRescanFailed', 'LabelRescanFound', 'LabelRescanLoading', 'LabelRescanScanning', 'MessageRescanErrorCatalog', 'MessageRescanErrorFolder', 'MessageRescanErrorRunning', 'MessageRescanErrorServer', 'MessageRescanErrorUnexpected', 'MessageRescanFolderAmbiguous', 'MessageRescanFolderRelinked']) {
    assert.ok(strings[key], key)
    assert.ok(page.includes(key), `${key} is used`)
  }
  assert.doesNotMatch(strings.LabelRescanChecked, /%/, 'no percentage')
})

test('ui-btn: a disabled success button takes the neutral fill; enabled and loading keep the success fill', async () => {
  const { default: vm } = await import('node:vm')
  const script = (await read('../components/ui/Btn.vue'))
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace('export default', 'globalThis.component =')
  const sandbox = {}
  vm.runInNewContext(script, sandbox)
  const classes = (props) => sandbox.component.computed.classList.call({ color: 'success', small: false, ...props })
  const disabled = classes({ disabled: true })
  assert.ok(disabled.includes('bg-primary'))
  assert.ok(!disabled.includes('bg-success-action') && !disabled.includes('text-white'))
  const enabled = classes({ disabled: false })
  assert.ok(enabled.includes('bg-success-action') && enabled.includes('text-white'))
  assert.ok(!enabled.includes('bg-primary'))
  const loading = classes({ disabled: false, loading: true })
  assert.ok(loading.includes('bg-success-action') && loading.includes('text-white'), 'the spinner sits on the green button')
  assert.deepEqual([...sandbox.component.computed.classList.call({ color: 'primary', disabled: true })].filter((c) => c.startsWith('bg-')), ['bg-primary'])
})
