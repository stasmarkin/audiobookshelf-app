// The system "remove animations" setting (Android animator duration scale 0, which e-ink devices such as Boox turn on),
// as the WebView reports it. CSS honors it in assets/app.css; this is for motion driven from JavaScript.
export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)'

export function prefersReducedMotion(win = typeof window !== 'undefined' ? window : null) {
  if (!win || typeof win.matchMedia !== 'function') return false
  return !!win.matchMedia(REDUCED_MOTION_QUERY).matches
}
