import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { EMBEDDING_MODEL, MIN_SCORE, embeddingText, ingest, isDeveloperPage, isUserHowTo, searchKb, withSources, type Ai, type KbResult, type VectorIndex } from '../src/kb.ts'
import { LOOKUP_TOOLS } from '../src/registry.ts'
import { ANSWER_MAX_TOKENS } from '../src/brain.ts'

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

test('a cited result comes back as a source chip; a url the search never returned is dropped', async () => {
  rounds = [
    { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'search_solana_kb', input: { query: 'what is a PDA' } }], usage: { input_tokens: 10, output_tokens: 5 } },
    { stop_reason: 'end_turn', content: [{ type: 'text', text: JSON.stringify({ say: 'The Solana docs cover it.', point_at: null, task: null,
      cite: ['https://solana.com/docs/core/pda', 'https://evil.test/phish'] }) }], usage: { input_tokens: 20, output_tokens: 10 } },
  ]
  const res = await worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
    body: JSON.stringify({ mode: 'task', system: 'S', tools: true, messages: [{ role: 'user', content: 'User asks: what is a PDA' }] }),
  }), env)
  const reply = JSON.parse((await res.json()).content[0].text)
  assert.deepEqual(reply.sources, [{ title: 'Program Derived Address', source: 'solana.com docs', url: 'https://solana.com/docs/core/pda' }])
  assert.equal(reply.cite, undefined)
  assert.equal(JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!).sources, 1)
  const toolResult = JSON.parse(bodies[1].messages.at(-1).content[0].content)
  assert.match(toolResult.note, /Never write a url in say/)
})

test('withSources keeps at most two, each once, and leaves a reply alone when nothing was searched', () => {
  const r = (url: string): KbResult => ({ title: url, url, source: 's', licence: 'l', excerpt: '', score: 0.9 })
  const found = new Map(['https://a.test/1', 'https://a.test/2', 'https://a.test/3'].map((u) => [u, r(u)]))
  const body = (reply: object) => JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(reply) }] })
  const out = withSources(body({ say: 'x', cite: ['https://a.test/1', 'https://a.test/1', 'https://a.test/2', 'https://a.test/3'] }), found)
  assert.equal(out.sources, 2)
  assert.deepEqual(JSON.parse(JSON.parse(out.body).content[0].text).sources.map((s: any) => s.url), ['https://a.test/1', 'https://a.test/2'])
  // No search this question: the reply is left exactly as it came.
  const plain = body({ say: 'x', point_at: null })
  assert.equal(withSources(plain, new Map()).body, plain)
  // A sentence before the object, as the model sometimes writes: still found, the sentence kept.
  const prose = JSON.stringify({ content: [{ type: 'text', text: 'Here is the answer. {"say":"x {curly}","cite":["https://a.test/2"]}' }] })
  const withProse = withSources(prose, found)
  assert.equal(withProse.sources, 1)
  const text = JSON.parse(withProse.body).content[0].text
  assert.ok(text.startsWith('Here is the answer. {'))
  assert.equal(JSON.parse(text.slice(text.indexOf('{'))).sources[0].url, 'https://a.test/2')
  const none = withSources(body({ say: 'x', cite: 'https://made.up' }), new Map([['https://weak.test', { ...r('https://weak.test'), score: 0.65 }]]))
  assert.equal(none.sources, 0)
  // Searched, answered, cited nothing: the strongest match stands in.
  const top = withSources(body({ say: 'x' }), found)
  assert.equal(top.from, 'top')
  assert.equal(top.sources, 1)
  assert.equal(JSON.parse(JSON.parse(none.body).content[0].text).sources, undefined)
})

test('a search that fails (the daily Workers AI allowance spent) is named in the log, and the answer still comes', async () => {
  env.AI = { async run() { throw new Error('4006: you have used up your daily free allocation of 10,000 neurons') } }
  rounds = [
    { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'search_solana_kb', input: { query: 'priority fees' } }], usage: { input_tokens: 10, output_tokens: 5 } },
    { stop_reason: 'end_turn', content: [{ type: 'text', text: '{"say":"Priority fees are tips.","point_at":null,"task":null}' }], usage: { input_tokens: 20, output_tokens: 10 } },
  ]
  const res = await worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
    body: JSON.stringify({ mode: 'quick', system: 'S', tools: true, messages: [{ role: 'user', content: 'User asks: how do priority fees work' }] }),
  }), env)
  assert.equal(res.status, 200)
  const line = JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!)
  assert.equal(line.kb_error, '4006')
  assert.equal(line.sources, 0)
  assert.equal(JSON.parse(bodies[1].messages.at(-1).content[0].content).error, 'lookup_failed')
})

test('chat and quick actions are never offered the knowledge base', async () => {
  rounds = [{ stop_reason: 'end_turn', content: [{ type: 'text', text: '{"say":"hi","point_at":null,"task":null}' }], usage: { input_tokens: 5, output_tokens: 2 } }]
  await worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
    body: JSON.stringify({ mode: 'quick', system: 'S', messages: [{ role: 'user', content: 'User asks: tell me a joke' }] }),
  }), env)
  assert.equal(bodies[0].tools, undefined)
})

// ------------------------------------------------- the search that runs before the model

/** A knowledge question as the phone sends one: the user's own words, and the lookups on. */
const asksAbout = (said: string, extra: Record<string, unknown> = {}) => worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
  method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
  body: JSON.stringify({ mode: 'quick', system: 'S', tools: true, said, messages: [{ role: 'user', content: `User asks: ${said}` }], ...extra }),
}), env)

/** The answer written as the forced tool, which is what the model does once it is forced. */
const answersWith = (input: Record<string, unknown>) => ({
  stop_reason: 'tool_use',
  content: [{ type: 'tool_use', id: 'ans', name: 'answer', input }],
  usage: { input_tokens: 20, output_tokens: 10 },
})

test('a developer question is looked up before the model is asked, not left to it', async () => {
  rounds = [answersWith({ say: 'It is derived from seeds.', cite: ['https://solana.com/docs/core/pda'] })]
  const res = await asksAbout('How do I derive a PDA in Anchor?')
  assert.equal(res.status, 200)

  // The chunks are in the very first call, with their titles and their urls.
  const first = bodies[0].messages.at(-1).content
  assert.match(first, /How do I derive a PDA in Anchor\?/)
  assert.match(first, /Program Derived Address \(solana\.com docs\)/)
  assert.match(first, /https:\/\/solana\.com\/docs\/core\/pda/)
  assert.match(first, /A PDA is an address derived from seeds/)
  // And the tool is still on the table, for a second query once it has read them.
  assert.ok(bodies[0].tools.some((t: any) => t.name === 'search_solana_kb'))

  // One model call, no tool round, and the page it used is the answer's chip.
  assert.equal(bodies.length, 1)
  const reply = JSON.parse((await res.json()).content[0].text)
  assert.equal(reply.say, 'It is derived from seeds.')
  assert.deepEqual(reply.sources, [{ title: 'Program Derived Address', source: 'solana.com docs', url: 'https://solana.com/docs/core/pda' }])
  assert.equal(JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!).kb_hits, 2)
})

test('a lesson turn says what to look up, since the words of the turn are not the topic', async () => {
  rounds = [answersWith({ say: 'A PDA has no private key.' })]
  await asksAbout('yes', { kb_query: 'program derived addresses', tool_names: ['search_solana_kb'] })
  assert.match(bodies[0].messages.at(-1).content, /Program Derived Address/)
})

test('a question with nothing to look up is sent exactly as it was', async () => {
  const fake = fakeIndex([])
  env = { ...env, KB: fake.index }
  rounds = [answersWith({ say: 'Nothing found, so from what I know.' })]
  await asksAbout('How do I derive a PDA in Anchor?')
  assert.equal(bodies[0].messages.at(-1).content, 'User asks: How do I derive a PDA in Anchor?')
})

test('an answer that used a chunk but cited nothing still carries the page it came from', async () => {
  rounds = [answersWith({ say: 'It is derived from seeds and a program id.' })]
  const res = await asksAbout('How do I derive a PDA in Anchor?')
  const reply = JSON.parse((await res.json()).content[0].text)
  assert.equal(reply.sources.length, 1)
  assert.equal(JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!).sources_from, 'top')
})

test('the dApp Store install question gets no developer page, looked up or cited', async () => {
  // What the Seeker got: "Solana Mobile: Submit a New App" under "how do I install an app".
  const submit = { id: 'c9', score: 0.78, metadata: { title: 'Submit a New App', url: 'https://docs.solanamobile.com/dapp-publishing/submit-new-app', source: 'Solana Mobile docs', licence: 'none stated', text: 'To submit a new app to the dApp Store, open the publisher portal.' } }
  const fake = fakeIndex([submit])
  env = { ...env, KB: fake.index }
  rounds = [answersWith({ say: 'Tap the magnifier, find the app and tap Install.', cite: ['https://docs.solanamobile.com/dapp-publishing/submit-new-app'] })]
  const res = await asksAbout('how do I install an app')
  // Not read first: the question goes as the user asked it.
  assert.equal(bodies[0].messages.at(-1).content, 'User asks: how do I install an app')
  const reply = JSON.parse((await res.json()).content[0].text)
  assert.equal(reply.sources, undefined)
  const line = JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!)
  assert.equal(line.user_how_to, true)
  assert.equal(line.sources, 0)
})

test('withSources: a user how-to drops developer pages, cited or top; a developer question keeps them', () => {
  const submit: KbResult = { title: 'Submit a New App', url: 'https://docs.solanamobile.com/dapp-publishing/submit-new-app', source: 'Solana Mobile docs', licence: 'l', excerpt: '', score: 0.9 }
  const found = new Map([[submit.url, submit]])
  const body = (reply: object) => JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(reply) }] })
  const cited = withSources(body({ say: 'x', cite: [submit.url] }), found, 'how do I install an app')
  assert.equal(cited.sources, 0)
  assert.equal(cited.dropped, 1)
  const top = withSources(body({ say: 'x' }), found, 'How can I install Jupiter?')
  assert.equal(top.sources, 0)
  // Publishing is a developer's question, and the same page is the right chip.
  assert.equal(withSources(body({ say: 'x', cite: [submit.url] }), found, 'how do I publish my app on the dApp Store').sources, 1)
  // No question given (a lesson turn): as before.
  assert.equal(withSources(body({ say: 'x', cite: [submit.url] }), found).sources, 1)
})

test('who is a user asking how, and which pages are for developers', () => {
  for (const q of ['how do I install an app', 'How do I swap SOL to USDC?', 'how can I earn on my USDC', 'teach me to swap', 'how do I send 1 SOL to my friend', 'where do I stake SOL', 'help me deposit into Earn']) {
    assert.ok(isUserHowTo(q), q)
  }
  for (const q of ['how much SOL is in this Jupiter account? say the exact number shown', 'what is my balance', 'how much USDC do I have']) {
    assert.ok(isUserHowTo(q), q)
  }
  for (const q of ['how do I publish my app', 'how do I send a transaction with web3.js', 'How do I derive a PDA in Anchor?', 'how do I deploy a program', 'what is an epoch', 'install the Solana CLI', 'how do I install the Anchor CLI', 'why did my swap fail with error 0x1']) {
    assert.ok(!isUserHowTo(q), q)
  }
  assert.ok(isDeveloperPage({ title: 'Submit a New App', url: 'https://docs.solanamobile.com/dapp-publishing/submit-new-app', source: 'Solana Mobile docs' }))
  assert.ok(isDeveloperPage({ title: 'Transfer SOL', url: 'https://solana.com/developers/cookbook/transactions/send-sol', source: 'Solana Cookbook' }))
  assert.ok(isDeveloperPage({ title: 'How to swap', url: 'https://example.test/x', source: 'Solana Stack Exchange' }))
  assert.ok(!isDeveloperPage({ title: 'How to swap on Jupiter', url: 'https://support.jup.ag/swap', source: 'Jupiter help' }))
})

// ------------------------------------------------- the answer's shape, forced and repaired

test('the last call after a lookup has to write the answer as a tool', async () => {
  rounds = [
    { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't1', name: 'search_solana_kb', input: { query: 'pda' } }], usage: { input_tokens: 10, output_tokens: 5 } },
    answersWith({ say: 'Seeds and a program id.', code: 'const [pda] = PublicKey.findProgramAddressSync(seeds, id)', cite: ['https://solana.com/docs/core/pda'] }),
  ]
  const res = await asksAbout('How do I derive a PDA in Anchor?')
  // Every round has to use a tool: a lookup while there are any, the answer after that.
  assert.deepEqual(bodies[0].tool_choice, { type: 'any' })
  // The same two sentences cost more as JSON with a snippet in them than as plain text.
  assert.equal(bodies[0].max_tokens, ANSWER_MAX_TOKENS)
  assert.ok(bodies[0].tools.some((t: any) => t.name === 'answer'))
  const reply = JSON.parse((await res.json()).content[0].text)
  assert.equal(reply.say, 'Seeds and a program id.')
  assert.match(reply.code, /findProgramAddressSync/)
  assert.equal(reply.point_at, null)
  assert.equal(reply.task, null)
  assert.equal(reply.sources.length, 1)
})

test('prose where a reply was due is put into the contract, not asked for again', async () => {
  rounds = [{
    stop_reason: 'end_turn',
    content: [{ type: 'text', text: '## Deriving a PDA\n\nYou use **findProgramAddressSync**. See [the docs](https://solana.com/docs/core/pda).\n\n```ts\nconst [pda] = PublicKey.findProgramAddressSync([Buffer.from("x")], id)\n```\n\nThe bump matters.' }],
    usage: { input_tokens: 20, output_tokens: 10 },
  }]
  const res = await asksAbout('How do I derive a PDA in Anchor?')
  // One call: the essay is taken as it is rather than bought a second time.
  assert.equal(bodies.length, 1)
  const reply = JSON.parse((await res.json()).content[0].text)
  assert.equal(reply.say, 'Deriving a PDA You use findProgramAddressSync. See the docs. The bump matters.')
  assert.match(reply.code, /findProgramAddressSync/)
  assert.ok(!reply.say.includes('https://'), 'no url in the words')
  assert.ok(!reply.say.includes('**'), 'no markdown in the words')
  assert.equal(JSON.parse(logs.find((l) => l.includes('"route":"chat"'))!).prose_wrapped, true)
  // It used the chunks it was given, so it still carries the page.
  assert.equal(reply.sources.length, 1)
})
