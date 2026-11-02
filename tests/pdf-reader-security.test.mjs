// P01 (PDF.js hardening, 2026-10-13): every PDF.js document load turns off eval (isEvalSupported: false, the upstream
// workaround for CVE-2024-4367 in pdfjs-dist 2.7.570), on the initial open and on the reload after a token renewal.
// Exercises the reader's real methods, the installed @teckel/vue-pdf loader and the installed PDF.js.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8').then((s) => s.replace(/\r\n/g, '\n'))
const dataUrl = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`

const helperUrl = dataUrl(await read('../utils/pdfDocumentParams.js'))
const { pdfDocumentParams } = await import(helperUrl)

// The reader's script with its imports resolved: the PDF component is not needed for its methods
const readerSource = await read('../components/readers/PdfReader.vue')
const readerScript = readerSource
  .slice(readerSource.indexOf('<script>') + 8, readerSource.indexOf('</script>'))
  .replace("import pdf from '@teckel/vue-pdf'", 'const pdf = {}')
  .replace("from '@/utils/pdfDocumentParams'", `from '${helperUrl}'`)
  .replace("from '@/utils/progressTarget'", `from '${dataUrl(await read('../utils/progressTarget.js'))}'`)
const { default: reader } = await import(dataUrl(readerScript))

// A reader instance: its data and methods, the computed URL and token, and a store whose renewal answers `renewed`
const makeReader = ({ token = 'initial-token', renewed = 'renewed-token', isLocal = false } = {}) => {
  const dispatched = []
  const vm = {
    ...reader.data(),
    isLocal,
    url: isLocal ? 'http://localhost/_capacitor_file_/data/user/0/app/files/book.pdf' : '/api/items/li_1/ebook',
    $store: {
      getters: { 'user/getToken': token, 'user/getServerAddress': 'http://server.test:13378' },
      dispatch: async (action, payload) => (dispatched.push([action, payload]), renewed)
    }
  }
  Object.defineProperty(vm, 'userToken', { get: () => reader.computed.userToken.call(vm) })
  Object.defineProperty(vm, 'ebookUrl', { get: () => reader.computed.ebookUrl.call(vm) })
  for (const [name, fn] of Object.entries(reader.methods)) vm[name] = fn.bind(vm)
  return { vm, dispatched }
}

test('pdfDocumentParams: URL, bearer header and eval turned off, in a new object every time', () => {
  const params = pdfDocumentParams('http://server.test/api/items/li_1/ebook', 'tok')
  assert.deepEqual(params, { url: 'http://server.test/api/items/li_1/ebook', httpHeaders: { Authorization: 'Bearer tok' }, isEvalSupported: false })
  assert.notEqual(pdfDocumentParams('u', 't'), pdfDocumentParams('u', 't'), 'a new object, so Vue reloads the document')
})

test('Initial open: server and local books load with eval off and the stored token', () => {
  const { vm } = makeReader()
  vm.init()
  assert.deepEqual(vm.pdfDocInitParams, { url: 'http://server.test:13378/api/items/li_1/ebook', httpHeaders: { Authorization: 'Bearer initial-token' }, isEvalSupported: false })
  const local = makeReader({ isLocal: true }).vm
  local.init()
  assert.equal(local.pdfDocInitParams.url, 'http://localhost/_capacitor_file_/data/user/0/app/files/book.pdf')
  assert.equal(local.pdfDocInitParams.isEvalSupported, false)
})

test('401: the shared renewal runs once with the refused token, and the reload keeps eval off with the new token', async () => {
  const { vm, dispatched } = makeReader()
  vm.init()
  const before = vm.pdfDocInitParams
  await vm.error({ status: 401 })
  assert.deepEqual(dispatched, [['user/refreshToken', 'initial-token']])
  assert.notEqual(vm.pdfDocInitParams, before)
  assert.deepEqual(vm.pdfDocInitParams, { url: 'http://server.test:13378/api/items/li_1/ebook', httpHeaders: { Authorization: 'Bearer renewed-token' }, isEvalSupported: false })
  assert.equal(vm.renewedToken, 'renewed-token')
  assert.equal(vm.isRefreshing, false)
})

test('Existing recovery is unchanged: no loop after a refused renewal, no reload without a token, other errors ignored', async () => {
  // The renewed token is refused too: no second renewal, the parameters stay as they were
  const { vm, dispatched } = makeReader()
  vm.init()
  await vm.error({ status: 401 })
  const afterRenewal = vm.pdfDocInitParams
  await vm.error({ status: 401 })
  assert.equal(dispatched.length, 1)
  assert.equal(vm.pdfDocInitParams, afterRenewal)
  // Renewal failed (signed out through $authRenewal): the reader keeps its parameters
  const failed = makeReader({ renewed: null })
  failed.vm.init()
  const initial = failed.vm.pdfDocInitParams
  await failed.vm.error({ status: 401 })
  assert.equal(failed.vm.pdfDocInitParams, initial)
  // Other errors never renew
  const other = makeReader()
  other.vm.init()
  await other.vm.error({ status: 500 })
  await other.vm.error(new Error('Invalid PDF structure'))
  assert.equal(other.dispatched.length, 0)
})

test('Both loads go through pdfDocumentParams; no hand-built parameter object can drop the setting', () => {
  const script = readerSource.slice(readerSource.indexOf('<script>'), readerSource.indexOf('</script>'))
  const assignments = script.match(/this\.pdfDocInitParams = [^\n]+/g)
  assert.deepEqual(assignments, ['this.pdfDocInitParams = pdfDocumentParams(this.ebookUrl, newAccessToken)', 'this.pdfDocInitParams = pdfDocumentParams(this.ebookUrl, this.userToken)'])
  assert.doesNotMatch(script, /httpHeaders\s*:/, 'parameters are only built by the helper')
  assert.match(readerSource, /<pdf v-if="pdfDocInitParams" ref="pdf"[^>]*:src="pdfDocInitParams"/)
})

// The installed wrapper's own createLoadingTask (its pdf_viewer import is only used for link handling)
const wrapperSource = (await read('../node_modules/@teckel/vue-pdf/src/pdfjsWrapper.js')).replace("import { PDFLinkService } from 'pdfjs-dist/es5/web/pdf_viewer';", 'const PDFLinkService = function () {};')
const { default: pdfjsWrapper } = await import(dataUrl(wrapperSource))

test('@teckel/vue-pdf createLoadingTask hands every parameter, eval off included, to getDocument', () => {
  const sources = []
  const { createLoadingTask } = pdfjsWrapper({ getDocument: (source) => (sources.push(source), {}) })
  const params = pdfDocumentParams('http://server.test/api/items/li_1/ebook', 'tok')
  createLoadingTask(params)
  assert.deepEqual(sources, [params])
  assert.notEqual(sources[0], params, 'the wrapper passes a copy')
})

// A minimal one-page PDF built here (no fonts, scripts or external content)
const minimalPdf = () => {
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>']
  let pdf = '%PDF-1.4\n'
  const offsets = objects.map((body, i) => {
    const offset = pdf.length
    pdf += `${i + 1} 0 obj\n${body}\nendobj\n`
    return offset
  })
  const xref = pdf.length
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return new TextEncoder().encode(pdf)
}

test('Installed pdfjs-dist 2.7.570: the setting reaches the document transport and the worker request', async () => {
  const PDFJS = require('pdfjs-dist/es5/build/pdf.js')
  assert.equal(PDFJS.version, '2.7.570')
  const { createLoadingTask } = pdfjsWrapper(PDFJS)
  const load = async (isEvalSupported) => {
    const worker = new PDFJS.PDFWorker()
    await worker.promise
    const requests = []
    const post = worker.port.postMessage.bind(worker.port)
    worker.port.postMessage = (message, transfer) => {
      if (message && message.action === 'GetDocRequest') requests.push(message.data)
      return post(message, transfer)
    }
    // The reader's parameters with the URL swapped for inline data (no network in tests)
    const { url, ...params } = pdfDocumentParams('unused', 'tok')
    const source = { ...params, data: minimalPdf(), worker }
    if (isEvalSupported === undefined) delete source.isEvalSupported
    const task = createLoadingTask(source)
    const doc = await task.promise
    const result = { pages: doc.numPages, transport: doc._transport._params.isEvalSupported, worker: requests.map((r) => r.isEvalSupported) }
    await task.destroy()
    worker.destroy()
    return result
  }
  assert.deepEqual(await load(false), { pages: 1, transport: false, worker: [false] })
  // Control: without the setting PDF.js 2.7.570 defaults to eval on, which is what P01 prevents
  assert.deepEqual(await load(undefined), { pages: 1, transport: true, worker: [true] })
})
