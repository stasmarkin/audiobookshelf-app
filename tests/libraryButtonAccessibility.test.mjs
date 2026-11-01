import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const toModule = (source) => import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const { otherLibraryId } = await toModule(await read('../utils/libraryViewState.js'))

// ---- accessibility (components/app/Appbar.vue)

const appbarSource = await read('../components/app/Appbar.vue')
const appbarScript = appbarSource.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'globalThis.component =')

function makeAppbar(libraryCount, currentIndex = 0) {
  const timers = []
  const sandbox = {
    otherLibraryId,
    AbsAudioPlayer: { addListener: async () => ({ remove() {} }) },
    setTimeout: (fn) => (timers.push(fn), timers.length),
    clearTimeout: (id) => {
      if (id) timers[id - 1] = null
    }
  }
  vm.runInNewContext(appbarScript, sandbox)
  const component = sandbox.component
  const libraries = Array.from({ length: libraryCount }, (_, i) => ({ id: `L${i + 1}`, name: `Library ${i + 1}`, icon: 'database' }))
  const calls = { dispatch: [], commit: [], haptics: 0 }
  const ctx = {
    ...component.data.call({}),
    $store: {
      state: { libraries: { libraries, currentLibraryId: libraries[currentIndex]?.id } },
      dispatch: (...args) => calls.dispatch.push(args),
      commit: (...args) => calls.commit.push(args)
    },
    $hapticsImpact: async () => calls.haptics++
  }
  for (const [name, method] of Object.entries(component.methods)) ctx[name] = method.bind(ctx)
  for (const [name, getter] of Object.entries(component.computed)) {
    if (typeof getter === 'function' && ['quickSwitchTargetId', 'libraryButtonDescription'].includes(name)) Object.defineProperty(ctx, name, { get: () => getter.call(ctx) })
  }
  const touch = (x = 5, y = 5) => ({ touches: [{ clientX: x, clientY: y }] })
  return { ctx, calls, timers, touch }
}

const opensMenu = (calls) => calls.commit.some(([name, value]) => name === 'libraries/setShowModal' && value === true)
const switches = (calls) => calls.dispatch.filter(([name]) => name === 'libraries/switchLibrary')

test('Appbar, two libraries: a tap switches to the other one and does not open the menu', async () => {
  const { ctx, calls } = makeAppbar(2, 0)
  await ctx.clickLibraryButton()
  assert.deepEqual(switches(calls), [['libraries/switchLibrary', 'L2']])
  assert.equal(opensMenu(calls), false)
  const second = makeAppbar(2, 1)
  await second.ctx.clickLibraryButton()
  assert.deepEqual(switches(second.calls), [['libraries/switchLibrary', 'L1']])
})

test('Appbar, one or three libraries: a tap opens the menu and never switches', async () => {
  for (const count of [1, 3, 4]) {
    const { ctx, calls, timers, touch } = makeAppbar(count)
    ctx.libraryButtonTouchStart({ ...touch() })
    assert.equal(timers.length, 0, `${count}: no long-press timer`)
    await ctx.clickLibraryButton()
    assert.equal(opensMenu(calls), true, `${count}: menu`)
    assert.equal(switches(calls).length, 0, `${count}: no switch`)
    ctx.libraryButtonKeydown({ key: 'ContextMenu', preventDefault() {} })
    assert.equal(calls.commit.filter(([name]) => name === 'libraries/setShowModal').length, 1, `${count}: the keyboard shortcut is not needed`)
  }
})

test('Appbar, two libraries: a long press opens the menu once and its click does not also switch', async () => {
  const { ctx, calls, timers, touch } = makeAppbar(2)
  ctx.libraryButtonTouchStart(touch())
  assert.equal(timers.length, 1)
  await timers[0]()
  assert.equal(opensMenu(calls), true)
  assert.equal(calls.haptics, 1, 'a haptic tick for the long press')
  await ctx.clickLibraryButton()
  assert.equal(switches(calls).length, 0, 'the click after a long press is swallowed')
  // the next, ordinary tap switches again
  await ctx.clickLibraryButton()
  assert.equal(switches(calls).length, 1)
  // moving the finger or lifting it early cancels the long press
  const moved = makeAppbar(2)
  moved.ctx.libraryButtonTouchStart(moved.touch(5, 5))
  moved.ctx.libraryButtonTouchMove(moved.touch(5, 40))
  assert.equal(moved.timers[0], null)
})

test('Appbar, two libraries: the accessible menu control and the keyboard open the menu without switching', async () => {
  // TalkBack, switch access and a keyboard reach a real button; its click only opens the menu
  assert.match(appbarSource, /<button v-if="quickSwitchTargetId" type="button" aria-label="Show library menu"[^>]*@click="clickShowLibraryModal"/)
  assert.match(appbarSource, /class="sr-only focus:not-sr-only/, 'visually hidden until it has the keyboard focus')
  const { ctx, calls } = makeAppbar(2)
  ctx.clickShowLibraryModal()
  assert.equal(opensMenu(calls), true)
  assert.equal(switches(calls).length, 0)
  assert.equal(calls.haptics, 0, 'no dependence on haptics')

  for (const event of [{ key: 'ContextMenu' }, { key: 'F10', shiftKey: true }]) {
    const k = makeAppbar(2)
    let prevented = false
    k.ctx.libraryButtonKeydown({ ...event, preventDefault: () => (prevented = true) })
    assert.equal(opensMenu(k.calls), true, JSON.stringify(event))
    assert.equal(prevented, true)
    assert.equal(switches(k.calls).length, 0)
  }
  for (const event of [{ key: 'Enter' }, { key: ' ' }, { key: 'F10' }, { key: 'ArrowDown' }]) {
    const k = makeAppbar(2)
    k.ctx.libraryButtonKeydown({ ...event, preventDefault() {} })
    assert.equal(opensMenu(k.calls), false, JSON.stringify(event))
  }
})

test('Appbar: the button keeps its name (the LLAMA hook pins it) and says in a description what a tap does', () => {
  assert.match(appbarSource, /<button type="button" aria-label="Show library modal" class="library-selector /)
  assert.equal(makeAppbar(2, 0).ctx.libraryButtonDescription, 'Switches to library Library 2. Use Show library menu to choose a library.')
  assert.equal(makeAppbar(1).ctx.libraryButtonDescription, undefined)
  assert.equal(makeAppbar(3).ctx.libraryButtonDescription, undefined)
})
