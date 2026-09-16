import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { scrubAddresses, scrubEvent } from '../src/sentry.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const RPC = 'https://rpc.test/secret-token-abc'
const ANTHROPIC = 'https://api.anthropic.com/v1/messages'
const ADDRESS = '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv'
const SIGNATURE = '5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW'
const DSN = 'https://publickey@o1.ingest.sentry.io/123'

function store() {
  const values = new Map<string, string>()
  return { values, async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } }
}

function env(over: Partial<Env> = {}): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: RPC,
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: ADDRESS, USDC_MINT: 'm', SKR_MINT: 'replace-me', PRICE_USD: '15', PRO_DAYS: '30', CLUSTER: 'mainnet-beta',
    CAPS: store(), ...over,
  } as Env
}

let sentryBodies: string[] = []

beforeEach(() => {
  clock.now = () => Date.parse('2026-09-16T12:00:00Z')
  sentryBodies = []
  console.log = () => {}
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url === ANTHROPIC) throw new Error(`upstream broke sending to ${ADDRESS} (${SIGNATURE}) via ${RPC}`)
    if (url.startsWith('https://o1.ingest.sentry.io/api/123/envelope')) {
      sentryBodies.push(String(init.body))
      return new Response('{}')
    }
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch
})

function ask() {
  return new Request('https://proxy.heylana.xyz/chat?wallet=' + ADDRESS, {
    method: 'POST',
    headers: { 'X-Heylana-Device': DEVICE, 'x-secret-header': 'hunter2' },
    body: JSON.stringify({ mode: 'quick', messages: [{ role: 'user', content: 'what is ' + ADDRESS }] }),
  })
}

test('addresses and signatures become [address]; ordinary words and short ids do not', () => {
  assert.equal(scrubAddresses(`to ${ADDRESS} sig ${SIGNATURE}`), 'to [address] sig [address]')
  assert.equal(scrubAddresses(`device ${DEVICE} status 502`), `device ${DEVICE} status 502`)
})

test('an event loses the person and every secret or address in any string', () => {
  const event = scrubEvent({
    message: `failed for ${ADDRESS}`,
    user: { id: 'x', ip_address: '1.2.3.4' },
    server_name: 'edge',
    request: { url: '/chat', method: 'POST', headers: { a: 'b' }, data: ADDRESS },
    exception: { values: [{ value: `rpc ${RPC} said no` }] },
  }, (text) => text.split(RPC).join('***'))
  assert.equal(event.message, 'failed for [address]')
  assert.equal('user' in event, false)
  assert.equal('server_name' in event, false)
  assert.deepEqual(event.request, { url: '/chat', method: 'POST' })
  assert.equal(event.exception.values[0].value, 'rpc *** said no')
})

test('with no SENTRY_DSN nothing is reported', async () => {
  const res = await worker.fetch(ask(), env())
  assert.equal(res.status, 502)
  assert.deepEqual(sentryBodies, [])
})

test('with a SENTRY_DSN an unhandled error is reported, scrubbed', async () => {
  const pending: Promise<unknown>[] = []
  const res = await worker.fetch(ask(), env({ SENTRY_DSN: DSN }), { waitUntil: (p) => { pending.push(p) } })
  assert.equal(res.status, 502)
  await Promise.all(pending)
  assert.equal(sentryBodies.length, 1)
  const body = sentryBodies[0]
  assert.ok(body.includes('upstream broke sending to [address] ([address]) via ***'))
  for (const secret of [ADDRESS, SIGNATURE, RPC, 'secret-token', 'hunter2', DEVICE, 'publickey@']) {
    assert.equal(body.includes(secret), false, `report carries ${secret}`)
  }
})
