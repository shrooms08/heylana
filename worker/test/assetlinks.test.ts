import { test } from 'node:test'
import assert from 'node:assert/strict'
import worker, { type Env } from '../src/index.ts'
import { fingerprints } from '../src/assetlinks.ts'

const DEBUG = '51:E4:C2:7A:95:E1:70:B3:42:D8:42:C2:1E:6A:1C:44:ED:7B:B0:2C:D9:6E:E0:F9:40:C7:E2:55:B4:72:10:A3'
const RELEASE = 'AA'.repeat(32)
const URL_ = 'https://heylana-proxy.heylana.workers.dev/.well-known/assetlinks.json'

// No keys, no KV: serving the statement must touch neither.
const bare = (prints?: string) => ({ ASSETLINKS_SHA256: prints } as Env)

test('assetlinks.json vouches for xyz.heylana.app with every configured certificate, to anyone', async () => {
  const res = await worker.fetch(new Request(URL_), bare(`${DEBUG}, ${RELEASE.toLowerCase()}`))
  assert.equal(res.status, 200)
  assert.equal(res.headers.get('content-type'), 'application/json')
  const [statement] = await res.json()
  assert.ok(statement.relation.includes('delegate_permission/common.handle_all_urls'))
  assert.deepEqual(statement.target, {
    namespace: 'android_app',
    package_name: 'xyz.heylana.app',
    sha256_cert_fingerprints: [DEBUG, RELEASE.match(/../g)!.join(':')],
  })
})

test('fingerprints are written one way; duplicates and junk are dropped', () => {
  assert.deepEqual(fingerprints(`${DEBUG.replace(/:/g, '').toLowerCase()},${DEBUG}, not-a-fingerprint, 12:34`), [DEBUG])
  assert.deepEqual(fingerprints(undefined), [])
})

test('with no fingerprint set it says so instead of vouching for nothing', async () => {
  const res = await worker.fetch(new Request(URL_), bare())
  assert.equal(res.status, 404)
  assert.equal((await res.json()).reason, 'not_configured')
})

test('HEAD gets the headers; anything else is refused', async () => {
  const head = await worker.fetch(new Request(URL_, { method: 'HEAD' }), bare(DEBUG))
  assert.equal(head.status, 200)
  assert.equal((await head.arrayBuffer()).byteLength, 0)
  assert.equal((await worker.fetch(new Request(URL_, { method: 'POST' }), bare(DEBUG))).status, 405)
})
