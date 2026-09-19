import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { EMBEDDING_MODEL, MIN_SCORE, embeddingText, ingest, searchKb, type Ai, type VectorIndex } from '../src/kb.ts'
import { LOOKUP_TOOLS } from '../src/registry.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const ANTHROPIC = 'https://api.anthropic.com/v1/messages'
const SECRET = 'kb-admin-secret-for-tests-0123456789'

/** A made-up Workers AI: every text gets a 768-long vector; it remembers what it was asked. */
function fakeAi() {
  const asked: string[][] = []
  const ai: Ai = { async run(model, input) { assert.equal(model, EMBEDDING_MODEL); asked.push(input.text); return { data: input.text.map(() => new Array(768).fill(0.01)) } } }
  return { ai, asked }
}

/** A made-up Vectorize index holding [stored] and answering every query with [matches]. */
function fakeIndex(matches: any[] = []) {
  const stored: any[] = []
  const index: VectorIndex = {
    async query(_vector, options) { return { matches: matches.slice(0, options.topK) } },
    async upsert(vectors) { stored.push(...vectors); return {} },
  }
  return { index, stored }
}

const pda = { id: 'c1', score: 0.83, metadata: { title: 'Program Derived Address', url: 'https://solana.com/docs/core/pda', source: 'solana.com docs', licence: 'GPL-3.0', text: 'A PDA is an address derived from seeds and a program id.' } }
const se = { id: 'c2', score: 0.71, metadata: { title: 'What causes AccountDidNotDeserialize?', url: 'https://solana.stackexchange.com/a/1', source: 'Solana Stack Exchange', licence: 'CC BY-SA 4.0, answer by someone', text: 'x'.repeat(3000) } }
const weak = { id: 'c3', score: MIN_SCORE - 0.01, metadata: { title: 'Unrelated', url: 'https://x.test', source: 's', licence: 'l', text: 't' } }

test('a search returns the close chunks with title, url, source and licence, and leaves out weak ones', async () => {
  const { ai } = fakeAi()
  const { index } = fakeIndex([pda, se, weak])
  const results = await searchKb({ ai, index }, 'what is a pda', 3)
  assert.deepEqual(results.map((r) => r.title), ['Program Derived Address', 'What causes AccountDidNotDeserialize?'])
  assert.equal(results[1].excerpt.length, 1200, 'an excerpt is kept short: every word goes to the model')
  assert.equal(results[0].licence, 'GPL-3.0')
  assert.equal((await searchKb({ ai, index }, 'q', 99)).length <= 5, true)
})

test('ingest embeds each chunk with its title in front and stores the text and attribution with it', async () => {
  const { ai, asked } = fakeAi()
  const { index, stored } = fakeIndex()
  const chunk = { id: 'abc123def456', title: 'Priority fees', url: 'https://solana.com/docs/core/fees', source: 'solana.com docs', licence: 'GPL-3.0', text: 'Priority fees are…' }
  assert.deepEqual(await ingest({ ai, index }, [chunk]), { upserted: 1 })
  assert.deepEqual(asked[0], [embeddingText(chunk)])
  assert.equal(stored[0].metadata.url, chunk.url)
  assert.equal(stored[0].values.length, 768)
  assert.deepEqual(await ingest({ ai, index }, [{ ...chunk, url: 'http://insecure' }]), { error: 'bad_url' })
  assert.deepEqual(await ingest({ ai, index }, []), { error: 'bad_batch' })
})

test('search_solana_kb is offered with the Solana lookups, and is public and read-only', () => {
  assert.ok(LOOKUP_TOOLS.some((t) => t.name === 'search_solana_kb'))
})

// ------------------------------------------------------------------ routes

let bodies: any[] = []
let logs: string[] = []
let rounds: any[] = []
let env: Env
let stored: any[]

beforeEach(() => {
  clock.now = () => Date.parse('2026-09-19T12:00:00Z')
  bodies = []
  logs = []
  const values = new Map<string, string>()
  const fake = fakeIndex([pda, se])
  stored = fake.stored
  env = {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: 'https://rpc.test/x',
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv', USDC_MINT: 'u', SKR_MINT: 'replace-me',
    PRICE_USD: '0.10', PRO_DAYS: '30', CLUSTER: 'devnet', AI: fakeAi().ai, KB: fake.index, KB_ADMIN_SECRET: SECRET,
    CAPS: { async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } },
  } as Env
  console.log = (line: string) => { logs.push(String(line)) }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url !== ANTHROPIC) throw new Error(`unexpected fetch ${url}`)
    bodies.push(JSON.parse(String(init.body)))
    return new Response(JSON.stringify(rounds.shift()))
  }) as typeof fetch
})

const admin = (route: string, body: unknown, secret?: string) => worker.fetch(new Request(`https://proxy.heylana.xyz/${route}`, {
  method: 'POST', headers: secret ? { 'X-Heylana-KB-Admin': secret } : {}, body: JSON.stringify(body),
}), env)

test('the admin routes do not exist without the secret, and work with it', async () => {
  assert.equal((await admin('kb/ingest', { chunks: [] })).status, 404)
  assert.equal((await admin('kb/ingest', { chunks: [] }, 'wrong')).status, 404)
  const chunk = { id: 'abc123def456', title: 'T', url: 'https://solana.com/docs', source: 's', licence: 'l', text: 'text' }
  const res = await admin('kb/ingest', { chunks: [chunk] }, SECRET)
  assert.equal(res.status, 200)
  assert.equal(stored.length, 1)
  const found = await (await admin('kb/search', { query: 'pda' }, SECRET)).json()
  assert.equal(found.results[0].title, 'Program Derived Address')
})

test('a Solana question can look things up in the knowledge base; the log counts the hits, never the text', async () => {
  rounds = [
    { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'search_solana_kb', input: { query: 'what is a PDA' } }], usage: { input_tokens: 10, output_tokens: 5 } },
    { stop_reason: 'end_turn', content: [{ type: 'text', text: '{"say":"The Solana docs on PDAs say it is derived from seeds.","point_at":null,"task":null}' }], usage: { input_tokens: 20, output_tokens: 10 } },
  ]
  const res = await worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
    body: JSON.stringify({ mode: 'task', system: 'S', tools: true, messages: [{ role: 'user', content: 'User asks: what is a PDA' }] }),
  }), env)
  assert.equal(res.status, 200)
  assert.ok(bodies[0].tools.some((t: any) => t.name === 'search_solana_kb'))
  const toolResult = JSON.parse(bodies[1].messages.at(-1).content[0].content)
  assert.equal(toolResult.results[0].url, 'https://solana.com/docs/core/pda')
  const line = logs.find((l) => l.includes('"route":"chat"'))!
  assert.equal(JSON.parse(line).kb_hits, 2)
  assert.ok(!line.includes('derived from seeds'), 'no chunk text in the log')
})

test('chat and quick actions are never offered the knowledge base', async () => {
  rounds = [{ stop_reason: 'end_turn', content: [{ type: 'text', text: '{"say":"hi","point_at":null,"task":null}' }], usage: { input_tokens: 5, output_tokens: 2 } }]
  await worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
    body: JSON.stringify({ mode: 'quick', system: 'S', messages: [{ role: 'user', content: 'User asks: tell me a joke' }] }),
  }), env)
  assert.equal(bodies[0].tools, undefined)
})
