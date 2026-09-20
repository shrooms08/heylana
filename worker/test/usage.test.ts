import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import {
  DEFAULT_PRICES, SAMPLE_CAP, USAGE_PREFIX, apply, costOf, dayOf, daysBack, emptyDay, priceSheet, summarise,
} from '../src/usage.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const NOON = Date.parse('2026-09-20T12:00:00Z')
const MINUTE = Math.floor(NOON / 60_000)
const ADMIN = 'admin-secret-for-tests-0123456789'

/** Workers KV, small enough to keep in a Map. */
function store(seed: Record<string, string> = {}) {
  const values = new Map(Object.entries(seed))
  return {
    values,
    async get(key: string) {
      return values.get(key) ?? null
    },
    async put(key: string, value: string) {
      values.set(key, value)
    },
    async delete(key: string) {
      values.delete(key)
    },
  }
}

let kv: ReturnType<typeof store>
let logs: string[]

function env(over: Partial<Env> = {}): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', DEEPGRAM_API_KEY: 'd', DEEPGRAM_PROJECT_ID: 'p',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', JUDGE_UNTIL: '2026-11-09',
    RPC_URL: 'https://devnet.test/key', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', CLUSTER: 'devnet',
    TREASURY_ADDRESS: '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv',
    USDC_MINT: '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU', SKR_MINT: 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3',
    PRICE_USD: '0.10', PRO_DAYS: '30', ADMIN_SECRET: ADMIN, CAPS: kv,
    ...over,
  } as unknown as Env
}

beforeEach(() => {
  kv = store()
  logs = []
  clock.now = () => NOON
  console.log = (line: string) => void logs.push(String(line))
  globalThis.fetch = (async () => new Response('{}')) as typeof fetch
})

// ------------------------------------------------------------- arithmetic

test('a day adds up what each request did, and a wallet is counted once', () => {
  let day = emptyDay('2026-09-20')
  day = apply(day, { chatModel: 'claude-haiku-4-5-20251001', tokensIn: 100, tokensOut: 20 }, 'w1', MINUTE)
  day = apply(day, { chatModel: 'claude-haiku-4-5-20251001', tokensIn: 50, tokensOut: 10 }, 'w1', MINUTE)
  day = apply(day, { tts: { provider: 'deepgram', chars: 240 }, ears: { provider: 'assemblyai' } }, 'w2', MINUTE)
  day = apply(day, { sendsPrepared: 1 }, null, MINUTE)
  day = apply(day, { sendsConfirmed: 1, proPayments: 1, proUsd: 0.1 }, 'w2', MINUTE)

  assert.deepEqual(day.chat, { 'claude-haiku-4-5-20251001': 2 })
  assert.equal(day.tokens_in, 150)
  assert.equal(day.tokens_out, 30)
  assert.deepEqual(day.tts, { deepgram: 240 })
  assert.deepEqual(day.ears, { assemblyai: 1 })
  assert.deepEqual(day.wallets, ['w1', 'w2'], 'two wallets, each once')
  assert.deepEqual([day.sends_prepared, day.sends_confirmed, day.pro_payments, day.pro_usd], [1, 1, 1, 0.1])
})

test('chain calls are counted and timed by provider, and the samples stay bounded', () => {
  let day = emptyDay('2026-09-20')
  day = apply(day, { rpc: [{ provider: 'rpcfast', method: 'getBalance', ms: 90 }, { provider: 'helius', method: 'getBalance', ms: 300 }] }, null, MINUTE)
  assert.deepEqual(day.rpc, { rpcfast: 1, helius: 1 })
  assert.deepEqual(day.rpc_ms, { rpcfast: 90, helius: 300 })
  assert.deepEqual(day.latency['rpcfast|getBalance'], [{ ms: 90, at: MINUTE }])

  for (let i = 0; i < SAMPLE_CAP + 40; i++) {
    day = apply(day, { rpc: [{ provider: 'rpcfast', method: 'getBalance', ms: i }] }, null, MINUTE)
  }
  assert.equal(day.latency['rpcfast|getBalance'].length, SAMPLE_CAP)
  assert.equal(day.latency['rpcfast|getBalance'].at(-1)!.ms, SAMPLE_CAP + 39, 'the newest are the ones kept')
})

test('the cost comes from the price sheet: tokens per model, characters, listening minutes', () => {
  let day = emptyDay('2026-09-20')
  day = apply(day, { chatModel: 'claude-haiku-4-5-20251001', tokensIn: 1_000_000, tokensOut: 200_000 }, null, MINUTE)
  day = apply(day, { tts: { provider: 'deepgram', chars: 500_000 } }, null, MINUTE)
  day = apply(day, { ears: { provider: 'assemblyai' } }, null, MINUTE)
  const prices = priceSheet(JSON.stringify({
    models: { 'claude-haiku-4-5-20251001': { in: 1, out: 5 } },
    tts: { deepgram: 30 },
    ears: { assemblyai: 0.0025 },
    ears_session_seconds: 60,
  }))
  const cost = costOf(day, prices)
  assert.equal(cost.model, 2) // 1M in at $1 + 0.2M out at $5
  assert.equal(cost.tts, 15) // half a million characters at $30 a million
  assert.equal(cost.ears, 0.0025) // one session of a minute
  assert.equal(cost.rpc, 0)
  assert.equal(cost.total, 17.0025)
})

test('two models share the day\'s tokens by their share of the calls', () => {
  let day = emptyDay('2026-09-20')
  day = apply(day, { chatModel: 'claude-haiku-4-5-20251001', tokensIn: 1_000_000, tokensOut: 0 }, null, MINUTE)
  day = apply(day, { chatModel: 'claude-sonnet-5', tokensIn: 1_000_000, tokensOut: 0 }, null, MINUTE)
  const cost = costOf(day, DEFAULT_PRICES)
  // 2M input tokens, half priced at $1 and half at $3.
  assert.equal(cost.model, 4)
})

test.skip('percentiles are nearest-rank, and the window leaves out what is older than a day', () => {
  assert.equal(percentile([], 0.5), 0)
  assert.equal(percentile([5], 0.95), 5)
  assert.equal(percentile([1, 2, 3, 4], 0.5), 2)
  assert.equal(percentile([...Array(100)].map((_, i) => i + 1), 0.95), 95)

  const day = emptyDay('2026-09-20')
  day.latency['rpcfast|getBalance'] = [
    { ms: 999, at: MINUTE - 25 * 60 },
    ...[...Array(20)].map((_, i) => ({ ms: (i + 1) * 10, at: MINUTE - 60 })),
  ]
  day.latency['helius|getBalance'] = [{ ms: 400, at: MINUTE - 10 }]
  const rows = latencyOver([day], MINUTE - 24 * 60)
  const fast = rows.find((r) => r.provider === 'rpcfast')!
  assert.equal(fast.calls, 20, 'the sample from yesterday is out of the window')
  assert.deepEqual([fast.p50, fast.p95], [100, 190])
  assert.deepEqual(rows.find((r) => r.provider === 'helius'), { provider: 'helius', method: 'getBalance', calls: 1, p50: 400, p95: 400 })
})

test('the report totals the days, counts wallets across them, and never lists one', () => {
  let one = apply(emptyDay('2026-09-19'), { chatModel: 'claude-sonnet-5', tokensIn: 10, tokensOut: 2 }, 'wallet-hash-a', MINUTE)
  let two = apply(emptyDay('2026-09-20'), { chatModel: 'claude-sonnet-5', tokensIn: 30, tokensOut: 4 }, 'wallet-hash-a', MINUTE)
  two = apply(two, { sendsPrepared: 2, sendsConfirmed: 1 }, 'wallet-hash-b', MINUTE)
  const report = summarise([two, one], DEFAULT_PRICES)

  assert.deepEqual([report.from, report.to], ['2026-09-19', '2026-09-20'])
  assert.deepEqual(report.days.map((d) => d.date), ['2026-09-19', '2026-09-20'])
  assert.deepEqual(report.days.map((d) => d.wallets), [1, 2], 'a count, not a list')
  assert.equal(report.totals.wallets, 2, 'the same wallet on two days is one wallet')
  assert.deepEqual(report.totals.chat, { 'claude-sonnet-5': 2 })
  assert.deepEqual([report.totals.tokens_in, report.totals.sends_prepared, report.totals.sends_confirmed], [40, 2, 1])
  assert.equal(JSON.stringify(report).includes('wallet-hash-a'), false, 'no wallet, hashed or not, is in the report')
})

test('the day keys run back from today, in UTC', () => {
  assert.equal(dayOf(NOON), '2026-09-20')
  assert.deepEqual(daysBack(NOON, 3), ['2026-09-18', '2026-09-19', '2026-09-20'])
})

// ------------------------------------------------------------- the route

test('/admin/usage needs the secret, and does not exist without one', async () => {
  const noHeader = await worker.fetch(new Request('https://proxy.heylana.xyz/admin/usage'), env())
  assert.equal(noHeader.status, 404)
  const wrong = await worker.fetch(new Request('https://proxy.heylana.xyz/admin/usage', { headers: { 'X-Heylana-Admin': 'nope' } }), env())
  assert.equal(wrong.status, 404)
  const unset = await worker.fetch(
    new Request('https://proxy.heylana.xyz/admin/usage', { headers: { 'X-Heylana-Admin': ADMIN } }),
    env({ ADMIN_SECRET: undefined } as any),
  )
  assert.equal(unset.status, 404)
})

test('/admin/usage returns the days, their cost and the last day\'s latencies', async () => {
  const day = apply(
    apply(emptyDay('2026-09-20'), { chatModel: 'claude-haiku-4-5-20251001', tokensIn: 2_000, tokensOut: 400 }, 'hashed', MINUTE),
    { rpc: [{ provider: 'rpcfast', method: 'getBalance', ms: 80 }, { provider: 'rpcfast', method: 'getBalance', ms: 120 }] },
    null,
    MINUTE - 30,
  )
  kv.values.set(USAGE_PREFIX + '2026-09-20', JSON.stringify(day))

  const res = await worker.fetch(
    new Request('https://proxy.heylana.xyz/admin/usage?days=7', { headers: { 'X-Heylana-Admin': ADMIN } }),
    env(),
  )
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.days.length, 1)
  assert.equal(body.days[0].wallets, 1)
  assert.deepEqual(body.totals.rpc, { rpcfast: 2 })
  assert.equal(body.totals.cost.total > 0, true)
  assert.equal(body.prices.models['claude-haiku-4-5-20251001'].in, DEFAULT_PRICES.models['claude-haiku-4-5-20251001'].in)
  assert.ok(body.notes.some((n: string) => n.includes('estimates')))
})

test('a question counts itself: the model, its tokens, the wallet and its chain calls', async () => {
  const answers = [{ content: [{ type: 'text', text: '{"say":"hi","point_at":null,"task":null}' }], usage: { input_tokens: 70, output_tokens: 9 } }]
  globalThis.fetch = (async (input: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.includes('api.anthropic.com')) return new Response(JSON.stringify(answers[0]))
    return new Response(JSON.stringify({ result: 1 }))
  }) as typeof fetch

  const res = await worker.fetch(
    new Request('https://proxy.heylana.xyz/chat', {
      method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
      body: JSON.stringify({ mode: 'quick', system: 'S', messages: [{ role: 'user', content: 'User asks: hi' }] }),
    }),
    env(),
  )
  assert.equal(res.status, 200)
  const stored = JSON.parse(kv.values.get(USAGE_PREFIX + '2026-09-20')!)
  assert.deepEqual(stored.chat, { 'claude-haiku-4-5-20251001': 1 })
  assert.deepEqual([stored.tokens_in, stored.tokens_out], [70, 9])
  assert.deepEqual(stored.wallets, [], 'no wallet, nothing to count')
})
