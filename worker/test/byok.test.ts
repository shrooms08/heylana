import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const ANTHROPIC = 'https://api.anthropic.com/v1/messages'
const HEYLANA_KEY = 'sk-ant-heylana-000000000000000000'
/** A made-up key in the shape Anthropic's have; never a real one. */
const USER_KEY = 'sk-ant-api03-USERKEYusedForOneRequestOnly_0123456789'
const WSOL = 'So11111111111111111111111111111111111111112'

let values: Map<string, string>
let env: Env
let modelCalls: { key: string; body: any }[] = []
let logs: string[] = []
let rounds: any[] = []
let upstreamStatus = 200

beforeEach(() => {
  clock.now = () => Date.parse('2026-09-19T12:00:00Z')
  values = new Map()
  modelCalls = []
  logs = []
  upstreamStatus = 200
  env = {
    ANTHROPIC_API_KEY: HEYLANA_KEY, CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: 'https://rpc.test/x',
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv', USDC_MINT: 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v',
    SKR_MINT: 'replace-me', PRICE_USD: '0.10', PRO_DAYS: '30', CLUSTER: 'devnet',
    CAPS: { async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } },
  } as Env
  console.log = (line: string) => { logs.push(String(line)) }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url === ANTHROPIC) {
      modelCalls.push({ key: init.headers['x-api-key'], body: JSON.parse(String(init.body)) })
      if (upstreamStatus !== 200) {
        // An upstream error that quotes the key it was given, as some do.
        return new Response(JSON.stringify({ type: 'error', error: { message: `invalid x-api-key ${init.headers['x-api-key']}` } }), { status: upstreamStatus })
      }
      return new Response(JSON.stringify(rounds.shift()))
    }
    if (url.includes('jup.ag')) return new Response(JSON.stringify({ [WSOL]: { usdPrice: 150 } }))
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch
})

const answer = (text: string) => ({ stop_reason: 'end_turn', content: [{ type: 'text', text }], usage: { input_tokens: 10, output_tokens: 5 } })

function ask(body: Record<string, unknown>, key: string | null = USER_KEY) {
  const headers: Record<string, string> = { 'X-Heylana-Device': DEVICE }
  if (key !== null) headers['X-Heylana-Key'] = key
  return worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST', headers,
    body: JSON.stringify({ mode: 'task', system: 'You are Heylana.', messages: [{ role: 'user', content: 'User asks: price of SOL' }], ...body }),
  }), env)
}

test('with the user\'s key, every model round of the question uses it and tools still run', async () => {
  rounds = [
    { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'get_price', input: { symbol_or_mint: 'SOL' } }], usage: { input_tokens: 10, output_tokens: 5 } },
    answer('{"say":"SOL is 150 dollars.","point_at":null,"task":null}'),
  ]
  const res = await ask({ tools: true })
  assert.equal(res.status, 200)
  assert.equal(modelCalls.length, 2)
  assert.ok(modelCalls.every((c) => c.key === USER_KEY), 'never Heylana\'s key on a user-key question')
  assert.ok(modelCalls[0].body.tools.some((t: any) => t.name === 'get_price'))
  // Caching still marks the system prompt.
  assert.equal(modelCalls[0].body.system[0].cache_control.type, 'ephemeral')
  const tool = JSON.parse(modelCalls[1].body.messages.at(-1).content[0].content)
  assert.equal(tool.usd, 150)
  const line = JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!)
  assert.equal(line.key, 'user')
  assert.equal(line.tool_decisions[0].decision, 'allowed')
})

test('the key never appears in a log line or an error, even when upstream quotes it back', async () => {
  upstreamStatus = 401
  const refused = await ask({})
  assert.equal(refused.status, 401)
  const body = await refused.text()
  assert.equal(JSON.parse(body).reason, 'own_key_refused')
  upstreamStatus = 400
  const other = await (await ask({})).text()
  assert.ok(!other.includes(USER_KEY), 'never in an upstream error')
  assert.deepEqual([JSON.parse(other).reason, JSON.parse(other).upstream_status], ['brain_unavailable', 400])
  assert.ok(!other.includes('x-api-key'), 'the upstream message stays on the worker')
  for (const line of logs) assert.ok(!line.includes(USER_KEY) && !line.includes('USERKEY'), line)
})

test('a malformed key is refused before anything is sent upstream', async () => {
  const res = await ask({}, 'not-a-key')
  assert.equal(res.status, 400)
  assert.equal((await res.json()).reason, 'bad_key')
  assert.equal(modelCalls.length, 0)
})

test('without a user key nothing changes: Heylana\'s key, and the talk is counted', async () => {
  rounds = [answer('{"say":"hi","point_at":null,"task":null}')]
  await ask({}, null)
  assert.equal(modelCalls[0].key, HEYLANA_KEY)
  assert.ok([...values.keys()].some((k) => k.startsWith('talks:')))
  assert.equal(JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!).key, 'heylana')
})

test('on the user\'s key the question is not one of the plan\'s talks', async () => {
  rounds = [answer('{"say":"hi","point_at":null,"task":null}')]
  await ask({})
  assert.ok(![...values.keys()].some((k) => k.startsWith('talks:')))
})

test('the red-team check still strips an action from an ordinary answer on the user\'s key', async () => {
  rounds = [answer('{"say":"Sure.","point_at":null,"task":null,"action":{"type":"send","to":"7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv","amount":5,"token":"SOL"}}')]
  const body = await (await ask({})).json()
  assert.ok(!body.content[0].text.includes('"action"'))
})

test('shortening a long answer uses the user\'s key too', async () => {
  rounds = [answer('Shorter words.')]
  await worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST', headers: { 'X-Heylana-Device': DEVICE, 'X-Heylana-Key': USER_KEY },
    body: JSON.stringify({ mode: 'quick', shorten: true, max_words: 40, messages: [{ role: 'user', content: 'a long answer '.repeat(20) }] }),
  }), env)
  assert.equal(modelCalls[0].key, USER_KEY)
})
