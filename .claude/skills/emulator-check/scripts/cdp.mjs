#!/usr/bin/env node
// Drives the app's WebView over the Chrome DevTools Protocol. Works on debug builds only:
// Capacitor enables WebView debugging when the APK is debuggable.
//
//   cdp.mjs eval '<js expression>'          result as JSON (promises are awaited)
//   cdp.mjs text                            visible text of the page
//   cdp.mjs click '<css selector>'
//   cdp.mjs click-text '<visible text>'     exact match first, then substring
//   cdp.mjs fill '<css selector>' '<value>' sets the value and fires input/change for v-model
//   cdp.mjs wait-text '<text>' [seconds]    waits until the page text contains it (default 20 s)
//   cdp.mjs wait-for '<css>' [seconds]      waits until the selector matches
import { execFileSync } from 'node:child_process'

const ADB = process.env.ADB || 'adb'
const APP_ID = process.env.APP_ID || 'app.absplus.android.debug'
const PORT = Number(process.env.CDP_PORT || 9223)

const fail = (msg) => {
  console.error(msg)
  process.exit(1)
}
const adb = (...args) => execFileSync(ADB, args, { encoding: 'utf8', timeout: 15000 }).trim()

if (typeof WebSocket === 'undefined') fail(`Node 22+ is required for the global WebSocket (running ${process.version})`)

async function pageWebSocketUrl() {
  let pid = ''
  try {
    pid = adb('shell', 'pidof', APP_ID)
  } catch {
    fail(`${APP_ID} is not running`)
  }
  const socket = `webview_devtools_remote_${pid.split(/\s+/)[0]}`
  if (!adb('shell', 'cat', '/proc/net/unix').includes(`@${socket}`)) {
    fail(`No DevTools socket ${socket}; is this a debug build and has the WebView loaded?`)
  }
  adb('forward', `tcp:${PORT}`, `localabstract:${socket}`)
  const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()
  const page = targets.find((t) => t.type === 'page')
  if (!page) fail('No page target in the WebView')
  return page.webSocketDebuggerUrl
}

async function evaluate(expression) {
  const ws = new WebSocket(await pageWebSocketUrl())
  await new Promise((resolve, reject) => {
    ws.onopen = resolve
    ws.onerror = () => reject(new Error('DevTools WebSocket failed to open'))
  })
  const reply = await new Promise((resolve, reject) => {
    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data)
      if (msg.id === 1) resolve(msg)
    }
    ws.onclose = () => reject(new Error('DevTools WebSocket closed before replying (app restarted or crashed?)'))
    ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }))
  })
  ws.close()
  if (reply.error) fail(reply.error.message)
  if (reply.result.exceptionDetails) fail(reply.result.exceptionDetails.exception?.description || reply.result.exceptionDetails.text)
  return reply.result.result.value
}

// Runs in the page; returns an error string or undefined.
const inPage = (body) => `(async () => { ${body} })()`
const visible = `const visible = (el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);`

const [command, ...args] = process.argv.slice(2)
const arg = (i, name) => args[i] ?? fail(`Missing ${name}`)
let result

// A hung renderer can keep the socket open without replying; bound the whole command instead.
const limitSeconds = command?.startsWith('wait-') ? Number(args[1] || 20) + 10 : Number(process.env.CDP_TIMEOUT || 30)
setTimeout(() => fail(`cdp ${command} timed out after ${limitSeconds} s`), limitSeconds * 1000).unref()

switch (command) {
  case 'eval':
    result = await evaluate(arg(0, 'expression'))
    break
  case 'text':
    result = await evaluate('document.body.innerText')
    break
  case 'click':
    result = await evaluate(
      inPage(`const el = document.querySelector(${JSON.stringify(arg(0, 'selector'))});
        if (!el) return 'not found';
        el.scrollIntoView({ block: 'center' }); el.click();`)
    )
    break
  case 'click-text': {
    const text = JSON.stringify(arg(0, 'text'))
    result = await evaluate(
      inPage(`${visible}
        const all = [...document.querySelectorAll('body *')].filter(visible);
        const norm = (el) => el.innerText?.trim().replace(/\\s+/g, ' ') ?? '';
        // Deepest element wins, so the click lands on the control rather than a wrapper.
        const pick = (match) => all.filter(match).filter((el) => ![...el.children].some((c) => visible(c) && match(c))).pop();
        const el = pick((e) => norm(e) === ${text}) || pick((e) => norm(e).includes(${text}));
        if (!el) return 'not found';
        el.scrollIntoView({ block: 'center' }); el.click();`)
    )
    break
  }
  case 'fill':
    result = await evaluate(
      inPage(`const el = document.querySelector(${JSON.stringify(arg(0, 'selector'))});
        if (!el) return 'not found';
        const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set;
        setter.call(el, ${JSON.stringify(arg(1, 'value'))});
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));`)
    )
    break
  case 'wait-text':
  case 'wait-for': {
    const deadline = Date.now() + Number(args[1] || 20) * 1000
    const target = arg(0, command === 'wait-text' ? 'text' : 'selector')
    const check =
      command === 'wait-text' ? `document.body.innerText.includes(${JSON.stringify(target)})` : `!!document.querySelector(${JSON.stringify(target)})`
    while (!(await evaluate(check))) {
      if (Date.now() > deadline) fail(`Timed out waiting for ${target}`)
      await new Promise((r) => setTimeout(r, 500))
    }
    break
  }
  default:
    fail('Usage: cdp.mjs eval|text|click|click-text|fill|wait-text|wait-for ...')
}

if (result === 'not found') fail('Element not found')
if (result !== undefined) console.log(typeof result === 'string' ? result : JSON.stringify(result, null, 2))
