// Appearance polish (Claude): the sort-direction arrow and multi-select check are readable on light-based themes.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { contrast } = require('../theme/rice/color')
const { BUILTIN_THEMES } = require('../theme/builtins')
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))

const YELLOW_300 = [253, 224, 71]
const theme = (id) => BUILTIN_THEMES.find((t) => t.id === id)

test('selection marks carry the hook class next to their yellow', async () => {
  assert.match(await read('../components/modals/OrderModal.vue'), /class="selection-mark text-yellow-300 absolute/)
  const multi = await read('../components/ui/MultiSelect.vue')
  assert.match(multi, /class="selection-mark text-yellow-400 absolute/)
  // its options use the theme text color (they were fixed near-white, unreadable on a light menu)
  assert.match(multi, /<li :key="item" class="text-fg select-none/)
})

test('light-based themes draw them in the text color, readable on every light surface', async () => {
  const css = await read('../assets/app.css')
  assert.match(css, /html\[data-theme='light'\] \.selection-mark \{\n  color: rgb\(var\(--color-fg\)\);\n\}/)
  const light = theme('light').tokens
  // the yellow fails the 3:1 non-text minimum on the light dialog surface; the text color passes on all of them
  assert.ok(contrast(YELLOW_300, light['surface.base']) < 3)
  for (const surface of ['surface.base', 'surface.raised', 'surface.content']) assert.ok(contrast(light['text.default'], light[surface]) >= 4.5, surface)
  // dark-based themes keep the yellow, which stands out there
  for (const id of ['dark', 'black']) assert.ok(contrast(YELLOW_300, theme(id).tokens['surface.base']) >= 3, id)
})
