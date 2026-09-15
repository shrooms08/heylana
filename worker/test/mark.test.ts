import { test } from 'node:test'
import assert from 'node:assert/strict'
import worker, { type Env } from '../src/index.ts'
import { markBytes } from '../src/mark.ts'

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]

// No keys, no KV: if serving the mark touched either, these would throw.
const bare = {} as Env

test('the mark is served as a PNG, to anyone, with no device header', async () => {
  const res = await worker.fetch(new Request('https://heylana-proxy.heylana.workers.dev/heylana-mark.png'), bare)
  assert.equal(res.status, 200)
  assert.equal(res.headers.get('content-type'), 'image/png')
  const bytes = new Uint8Array(await res.arrayBuffer())
  assert.deepEqual([...bytes.slice(0, 8)], PNG_SIGNATURE)
  assert.equal(bytes.length, markBytes().length)
})

test('the embedded mark is a real PNG, square', () => {
  const bytes = markBytes()
  assert.deepEqual([...bytes.slice(0, 8)], PNG_SIGNATURE)
  const view = new DataView(bytes.buffer, bytes.byteOffset)
  // IHDR: width then height, big-endian, right after the chunk header.
  assert.equal(view.getUint32(16), 256)
  assert.equal(view.getUint32(20), 256)
})

test('a HEAD for the mark gets its headers and no body', async () => {
  const res = await worker.fetch(new Request('https://heylana-proxy.heylana.workers.dev/heylana-mark.png', { method: 'HEAD' }), bare)
  assert.equal(res.status, 200)
  assert.equal(res.headers.get('content-type'), 'image/png')
  assert.equal((await res.arrayBuffer()).byteLength, 0)
})

test('the mark answers only GET and HEAD', async () => {
  const res = await worker.fetch(new Request('https://heylana-proxy.heylana.workers.dev/heylana-mark.png', { method: 'POST' }), bare)
  assert.equal(res.status, 405)
})
