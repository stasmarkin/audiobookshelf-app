// Reduced motion: the system "remove animations" setting (Android animator scale 0; Boox e-ink devices turn it on) stops
// CSS transitions/animations everywhere and the JavaScript title marquee in the player.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const esm = (source) => import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

const helperSource = await read('../utils/reducedMotion.js')
const { prefersReducedMotion, REDUCED_MOTION_QUERY } = await esm(helperSource)
// The marquee imports the helper relatively, which a data: module cannot resolve; inline it instead
const marqueeSource = (await read('../assets/WrappingMarquee.js')).replace(/^import \{ prefersReducedMotion \} from '\.\.\/utils\/reducedMotion\.js'\n/m, helperSource)
const { default: WrappingMarquee } = await esm(marqueeSource)

const fakeWindow = (reduce) => ({
  queries: [],
  matchMedia(query) {
    this.queries.push(query)
    return { matches: reduce && query === REDUCED_MOTION_QUERY }
  },
  requestAnimationFrame: () => 1,
  cancelAnimationFrame: () => {}
})

const fakeTitle = ({ scrollWidth, clientWidth }) => {
  const pEl = { style: {}, innerText: '', textContent: '' }
  return { el: { firstElementChild: pEl, style: {}, scrollWidth, clientWidth }, pEl }
}

const withWindow = (win, fn) => {
  const previous = globalThis.window
  globalThis.window = win
  try {
    return fn()
  } finally {
    if (previous === undefined) delete globalThis.window
    else globalThis.window = previous
  }
}

test('prefersReducedMotion: true only when the reduce query matches; false without a window or matchMedia', () => {
  const reduce = fakeWindow(true)
  assert.equal(prefersReducedMotion(reduce), true)
  assert.deepEqual(reduce.queries, ['(prefers-reduced-motion: reduce)'])
  assert.equal(prefersReducedMotion(fakeWindow(false)), false)
  assert.equal(prefersReducedMotion(null), false)
  assert.equal(prefersReducedMotion({}), false)
})

test('app.css: under prefers-reduced-motion every element gets near-zero transitions, and animations stop except spinners and the toast timer', async () => {
  const css = await read('../assets/app.css')
  const block = css.match(/@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/)
  assert.ok(block, 'reduced-motion block present')
  const rules = [...block[1].matchAll(/([^{}]+)\{([^}]*)\}/g)].map(([, selector, body]) => ({ selector: selector.trim(), body }))
  const transitions = rules.find((r) => r.body.includes('transition-duration'))
  const animations = rules.find((r) => r.body.includes('animation-duration') && r.selector.includes(':not('))
  // Pseudo-elements in a rule of their own, so a WebView that rejects the complex :not() still keeps it
  const pseudo = rules.find((r) => r.body.includes('animation-duration') && !r.selector.includes(':not('))
  assert.match(pseudo.selector, /^\*::before,\s*\*::after$/)
  assert.match(transitions.selector, /^\*,\s*\*::before,\s*\*::after$/)
  for (const declaration of ['transition-duration: 0.01ms !important', 'transition-delay: 0s !important', 'scroll-behavior: auto !important']) assert.ok(transitions.body.includes(declaration), declaration)
  for (const declaration of ['animation-duration: 0.01ms !important', 'animation-delay: 0s !important', 'animation-iteration-count: 1 !important']) assert.ok(animations.body.includes(declaration), declaration)
  // The toast closes on its timer bar's animationend; loading spinners keep turning so loading never reads as a hang
  const universal = animations.selector
  for (const kept of ['.Vue-Toastification__progress-bar', '.animate-spin', '.la-ball-spin-clockwise > div', '.loader-dots > div']) assert.ok(universal.includes(`:not(${kept})`), kept)
  // Not `none`: Vue transitions wait for transitionend, which a removed transition never fires
  assert.doesNotMatch(block[1], /transition: none|animation: none/)
})

test('app.css: the excluded classes are the ones the toast library and the spinners actually animate', async () => {
  const toast = await readFile(new URL('../node_modules/vue-toastification/dist/index.css', import.meta.url), 'utf8')
  assert.match(toast, /\.Vue-Toastification__progress-bar \{[^}]*animation:/)
  assert.match(await read('../components/widgets/LoadingSpinner.vue'), /\.la-ball-spin-clockwise > div \{[^}]*animation:/)
  assert.match(await read('../components/ui/LoadingIndicator.vue'), /class="loader-dots/)
})

test('WrappingMarquee: with reduced motion an overflowing title stays static with an ellipsis and never schedules a scroll', () => {
  withWindow(fakeWindow(true), () => {
    const { el, pEl } = fakeTitle({ scrollWidth: 500, clientWidth: 200 })
    const marquee = new WrappingMarquee(el)
    marquee.init('A very long chapter title that overflows the mini player')
    assert.equal(marquee.timer, null)
    assert.equal(marquee.isScrolling, false)
    assert.equal(pEl.innerText, 'A very long chapter title that overflows the mini player')
    assert.equal(pEl.style.overflow, 'hidden')
    assert.equal(pEl.style.textOverflow, 'ellipsis')
    assert.equal(el.style.maskImage, '')
  })
})

test('WrappingMarquee: without reduced motion an overflowing title still scrolls, and a reduced-motion ellipsis is cleared', () => {
  const { el, pEl } = fakeTitle({ scrollWidth: 500, clientWidth: 200 })
  pEl.style.overflow = 'hidden'
  pEl.style.textOverflow = 'ellipsis'
  withWindow(fakeWindow(false), () => {
    const marquee = new WrappingMarquee(el)
    marquee.init('A very long chapter title that overflows the mini player')
    try {
      assert.notEqual(marquee.timer, null)
      assert.equal(pEl.style.overflow, '')
      assert.equal(pEl.style.textOverflow, '')
      assert.match(el.style.maskImage, /linear-gradient/)
    } finally {
      marquee.reset()
    }
  })
})

test('WrappingMarquee: a title that fits is static either way', () => {
  for (const reduce of [true, false]) {
    withWindow(fakeWindow(reduce), () => {
      const { el } = fakeTitle({ scrollWidth: 100, clientWidth: 200 })
      const marquee = new WrappingMarquee(el)
      marquee.init('Short')
      assert.equal(marquee.timer, null)
      assert.equal(el.style.maskImage, '')
    })
  }
})
