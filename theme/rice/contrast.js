/**
 * Contrast pair graph and deterministic repair for RICE snapshots (docs/rice-customization.md, "Accessibility").
 *
 * Thresholds follow WCAG 2.2: readable text 4.5:1, essential non-text (control boundaries, indicators, progress)
 * 3:1. A high-contrast request raises text to 7:1 and essential controls to 4.5:1. Ratios are never rounded before
 * comparison. Only RICE snapshots are evaluated; the built-in themes are never "corrected".
 */
const { contrast, blend } = require('./color')

const WHITE = [255, 255, 255]
const BLACK = [0, 0, 0]
const TEXT = 4.5
const NON_TEXT = 3

const thresholds = (highContrast) => ({ text: highContrast ? 7 : TEXT, control: highContrast ? 4.5 : NON_TEXT })

/** [a token or fixed color label, b, minimum kind] for every pair RICE requires. */
function pairGraph(snapshot) {
  const surfaces = ['surface.base', 'surface.content', 'surface.raised', 'surface.hover']
  const pairs = []
  for (const fg of ['text.default', 'text.primary']) for (const s of surfaces) pairs.push([fg, s, 'text'])
  for (const s of ['surface.base', 'surface.content', 'surface.raised']) pairs.push(['text.muted', s, 'text'])
  for (const overlay of ['overlay.item-header', 'overlay.player', 'overlay.mini-player']) pairs.push(['text.primary', overlay, 'text'])
  for (const s of ['surface.base', 'surface.content']) {
    pairs.push(['border.default', s, 'control'])
    pairs.push(['accent.primary', s, 'control'])
  }
  pairs.push(['progress.played', 'progress.track', 'control'])
  pairs.push(['progress.played', 'surface.base', 'control'])
  // Existing controls with fixed white labels (success buttons, destructive buttons)
  pairs.push(['#white', 'state.success-action', 'text'])
  pairs.push(['#white', 'state.error', 'text'])
  for (const state of ['state.success', 'state.warning', 'state.info']) pairs.push([state, 'surface.base', 'control'])
  // Player glyphs: the app draws white glyphs on dark schemes and black ones on light schemes (coverPresentation)
  const glyph = snapshot.colorScheme === 'light' ? '#black' : '#white'
  for (const s of ['surface.base', 'surface.raised']) pairs.push([glyph, s, 'control'])
  return pairs
}

const colorOf = (snapshot, name) => {
  if (name === '#white') return WHITE
  if (name === '#black') return BLACK
  const v = snapshot.tokens[name]
  return Array.isArray(v) ? v : v.color
}

/** @returns {{ ok: boolean, pairs: Array<{a,b,ratio,min,ok}>, failures: Array }} */
function evaluateContrast(snapshot, { highContrast = false } = {}) {
  const min = thresholds(highContrast)
  const pairs = pairGraph(snapshot).map(([a, b, kind]) => {
    const ratio = contrast(colorOf(snapshot, a), colorOf(snapshot, b))
    return { a, b, ratio, min: min[kind], ok: ratio >= min[kind] }
  })
  const failures = pairs.filter((p) => !p.ok)
  return { ok: failures.length === 0, pairs, failures }
}

const sqDistance = (a, b) => (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2
const lexLess = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2]

/**
 * Smallest change to `color` that meets every constraint: 256 evenly spaced blends from the color toward black and
 * toward white (linear RGB); the passing candidate with the least squared RGB change wins, ties by RGB order.
 * @param {number[]} color
 * @param {Array<{ against: number[], min: number }>} constraints
 * @returns {{ color: number[], changed: boolean, satisfied: boolean }}
 */
function repairColor(color, constraints) {
  const passes = (c) => constraints.every((k) => contrast(c, k.against) >= k.min)
  if (passes(color)) return { color: color.slice(), changed: false, satisfied: true }
  let best = null
  for (const target of [BLACK, WHITE]) {
    for (let step = 1; step <= 256; step++) {
      const candidate = blend(color, target, step / 256)
      if (!passes(candidate)) continue
      if (!best || sqDistance(candidate, color) < sqDistance(best, color) || (sqDistance(candidate, color) === sqDistance(best, color) && lexLess(candidate, best) < 0)) best = candidate
      break // further steps toward the same target only move further away
    }
  }
  return best ? { color: best, changed: true, satisfied: true } : { color: color.slice(), changed: false, satisfied: false }
}

module.exports = { evaluateContrast, repairColor, pairGraph, thresholds, TEXT, NON_TEXT, WHITE, BLACK }
