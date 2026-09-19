import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { addCacheUse, noCacheUse, systemText, withCache, withCacheTotals } from '../src/cache.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const ANTHROPIC = 'https://api.anthropic.com/v1/messages'

function env(): Env {
  const values = new Map<string, string>()
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: 'https://rpc.test/x',
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv', USDC_MINT: 'u', SKR_MINT: 'replace-me',
    PRICE_USD: '0.10', PRO_DAYS: '30', CLUSTER: 'devnet',
    CAPS: { async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } },
  } as Env
}

let bodies: any[] = []
let logs: string[] = []
let rounds: any[] = []

beforeEach(() => {
  clock.now = () => Date.parse('2026-09-19T12:00:00Z')
  bodies = []
  logs = []
  console.log = (line: string) => { logs.push(String(line)) }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url !== ANTHROPIC) throw new Error(`unexpected fetch ${url}`)
    bodies.push(JSON.parse(String(init.body)))
    return new Response(JSON.stringify(rounds.shift()))
  }) as typeof fetch
})

const reply = (usage: Record<string, number>) => ({
  stop_reason: 'end_turn', content: [{ type: 'text', text: '{"say":"ok","point_at":null,"task":null}' }], usage,
})

function ask(extra: Record<string, unknown> = {}) {
  return worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST',
    headers: { 'X-Heylana-Device': DEVICE },
    body: JSON.stringify({ mode: 'task', system: 'You are Heylana.', messages: [{ role: 'user', content: 'User asks: hi' }], ...extra }),
  }), env())
}

test('the system prompt and the last tool are cache breakpoints; the rest is untouched', () => {
  const tools = [{ name: 'a', input_schema: {} }, { name: 'b', input_schema: {} }]
  const out = withCache({ model: 'm', system: 'S', tools, messages: [] }) as any
  assert.deepEqual(out.system, [{ type: 'text', text: 'S', cache_control: { type: 'ephemeral' } }])
  assert.equal(out.tools[0].cache_control, undefined)
  assert.deepEqual(out.tools[1].cache_control, { type: 'ephemeral' })
  assert.equal((tools[1] as any).cache_control, undefined, 'the shared definitions are not changed')
  assert.equal(systemText(out.system), 'S')
  // No system prompt, no tools: nothing to mark.
  assert.deepEqual(withCache({ model: 'm', messages: [] }), { model: 'm', messages: [] })
})

test('cache reads and writes are added up and put on the reply', () => {
  const total = noCacheUse()
  addCacheUse(total, JSON.stringify({ usage: { cache_read_input_tokens: 1800, cache_creation_input_tokens: 0 } }))
  addCacheUse(total, JSON.stringify({ usage: { cache_creation_input_tokens: 300 } }))
  addCacheUse(total, 'not json')
  assert.deepEqual(total, { read: 1800, write: 300 })
  const body = JSON.parse(withCacheTotals(JSON.stringify({ usage: { input_tokens: 40 } }), total))
  assert.deepEqual(body.usage, { input_tokens: 40, cache_read_input_tokens: 1800, cache_creation_input_tokens: 300 })
})

test('a question goes with its system prompt cached, and the log and reply say what the cache did', async () => {
  rounds = [reply({ input_tokens: 40, output_tokens: 12, cache_read_input_tokens: 1850, cache_creation_input_tokens: 0 })]
  const res = await ask()
  assert.equal(res.status, 200)
  assert.deepEqual(bodies[0].system, [{ type: 'text', text: 'You are Heylana.', cache_control: { type: 'ephemeral' } }])
  const line = JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!)
  assert.equal(line.cache_read, 1850)
  assert.equal(line.cache_write, 0)
  assert.equal((await res.json()).usage.cache_read_input_tokens, 1850)
})

test('tool rounds are summed: the first writes the cache, the second reads it', async () => {
  rounds = [
    { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'get_price', input: { symbol_or_mint: 'SOL' } }],
      usage: { input_tokens: 30, output_tokens: 8, cache_creation_input_tokens: 2100 } },
    reply({ input_tokens: 60, output_tokens: 10, cache_read_input_tokens: 2100 }),
  ]
  const priceFetch = globalThis.fetch
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.includes('jup.ag')) return new Response(JSON.stringify({}))
    return priceFetch(input, init)
  }) as typeof fetch
  await ask({ tools: true, tool_names: ['get_price'] })
  assert.deepEqual(bodies[0].tools.at(-1).cache_control, { type: 'ephemeral' })
  const line = JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!)
  assert.equal(line.rounds, 2)
  assert.equal(line.cache_write, 2100)
  assert.equal(line.cache_read, 2100)
})
