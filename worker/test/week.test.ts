import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { encodeBase58 } from '../src/base58.ts'
import { EMPTY_COUNTS, SEEN_CAP, applyWeek, breakdown, cardLine, emptyWeek, nothingCaught, weekStart } from '../src/week.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const ANTHROPIC = 'https://api.anthropic.com/v1/messages'
const ADDRESS = '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv'
const OTHER = '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'
/** A Wednesday, so the week's Monday is two days back. */
const WEDNESDAY = Date.parse('2026-09-16T12:00:00Z')

function store(seed: Record<string, string> = {}) {
  const values = new Map(Object.entries(seed))
  return {
    values,
    async get(key: string) { return values.get(key) ?? null },
    async put(key: string, value: string) { values.set(key, value) },
    async delete(key: string) { values.delete(key) },
  }
}

let kv: ReturnType<typeof store>
let logs: string[]
let script: any[]

function env(): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', DEEPGRAM_API_KEY: 'd', DEEPGRAM_PROJECT_ID: 'p',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', JUDGE_UNTIL: '2026-11-09',
    RPC_URL: 'https://devnet.test/key', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', CLUSTER: 'devnet',
    TREASURY_ADDRESS: ADDRESS, USDC_MINT: 'u', SKR_MINT: 'replace-me', PRICE_USD: '5', PRO_DAYS: '30',
    CAPS: kv,
  } as unknown as Env
}

beforeEach(() => {
  kv = store()
  logs = []
  script = [{ stop_reason: 'end_turn', content: [{ type: 'text', text: '{"say":"ok","point_at":null,"task":null}' }], usage: { input_tokens: 5, output_tokens: 2 } }]
  clock.now = () => WEDNESDAY
  console.log = (line: string) => void logs.push(String(line))
  globalThis.fetch = (async (input: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url === ANTHROPIC) return new Response(JSON.stringify(script[Math.min(script.length - 1, 0)]))
    return new Response(JSON.stringify({ result: null }))
  }) as typeof fetch
})

function req(path: string, body: unknown, session?: string, method = 'POST') {
  const headers: Record<string, string> = { 'X-Heylana-Device': DEVICE }
  if (session) headers.Authorization = `Bearer ${session}`
  return new Request(`https://proxy.heylana.xyz${path}`, { method, headers, ...(method === 'GET' ? {} : { body: JSON.stringify(body) }) })
}

async function connected(e: Env) {
  const pair = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify'])) as CryptoKeyPair
  const pubkey = encodeBase58(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)))
  const challenge = await (await worker.fetch(req('/wallet/challenge', { pubkey }), e)).json()
  const signature = encodeBase58(new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, pair.privateKey, new TextEncoder().encode(challenge.message))))
  const body = await (await worker.fetch(req('/wallet/verify', { pubkey, nonce: challenge.nonce, signature }), e)).json()
  return { session: body.session as string, pubkey }
}

const ask = (session: string, caught?: string) =>
  req('/chat', { mode: 'quick', system: 'S', caught, messages: [{ role: 'user', content: 'User asks: what is this' }] }, session)

// ------------------------------------------------------------- arithmetic

test('the week runs Monday to Monday, in UTC', () => {
  assert.equal(weekStart(WEDNESDAY), '2026-09-14')
  assert.equal(weekStart(Date.parse('2026-09-14T00:00:00Z')), '2026-09-14', 'Monday is its own week')
  assert.equal(weekStart(Date.parse('2026-09-20T23:59:00Z')), '2026-09-14', 'Sunday is still that week')
  assert.equal(weekStart(Date.parse('2026-09-21T00:01:00Z')), '2026-09-21', 'the next Monday starts the next')
})

test('counts add up, and an address counts only the first time it is looked up', () => {
  let week = emptyWeek('2026-09-14')
  week = applyWeek(week, { questions: 1, screens: 1, seen: ['aaa', 'bbb'] })
  week = applyWeek(week, { questions: 1, transactions: 1, seen: ['aaa'] })
  week = applyWeek(week, { sends_prepared: 1, sends_stopped: 1 })
  assert.deepEqual(week.counts, {
    questions: 2, screens: 1, transactions: 1, sends_prepared: 1, sends_stopped: 1, new_addresses: 2, lessons: 0,
  })
  assert.deepEqual(week.seen, ['aaa', 'bbb'])
})

test('the hashes it keeps are bounded, and the count it left behind stands', () => {
  let week = emptyWeek('2026-09-14')
  for (let i = 0; i < SEEN_CAP + 20; i++) week = applyWeek(week, { seen: [`hash-${i}`] })
  assert.equal(week.counts.new_addresses, SEEN_CAP + 20)
  assert.equal(week.seen.length, SEEN_CAP)
})

test('nothing caught is not written', () => {
  assert.equal(nothingCaught({}), true)
  assert.equal(nothingCaught({ seen: [] }), true)
  assert.equal(nothingCaught({ questions: 1 }), false)
  assert.equal(nothingCaught({ seen: ['x'] }), false)
})

test('the card is one sentence of numbers and what they counted, singular where it should be', () => {
  assert.equal(
    cardLine({ ...EMPTY_COUNTS, screens: 14, sends_prepared: 2, sends_stopped: 1, questions: 30 }),
    'This week: 2 sends checked, 1 stopped before signing, 14 screens explained.',
  )
  assert.equal(cardLine({ ...EMPTY_COUNTS, screens: 1, lessons: 1 }), 'This week: 1 screen explained, 1 lesson finished.')
  assert.equal(cardLine(EMPTY_COUNTS), 'This week: nothing yet.')
})

test('the list behind it is labels and counts, and leaves out what did not happen', () => {
  const items = breakdown({ ...EMPTY_COUNTS, screens: 3, questions: 9 })
  assert.deepEqual(items, [
    { key: 'screens', label: 'screens explained', count: 3 },
    { key: 'questions', label: 'questions answered', count: 9 },
  ])
})

test('nothing in the card or the list can carry an address, an amount or a word that was said', () => {
  const counts = { questions: 9, screens: 14, transactions: 2, sends_prepared: 2, sends_stopped: 1, new_addresses: 3, lessons: 1 }
  const words = `${cardLine(counts)} ${JSON.stringify(breakdown(counts))}`
  // Only digits, the category words and punctuation: a base58 run could never fit through.
  assert.match(words, /^[0-9a-z ,.:_"{}\[\]-]+$/i)
  for (const secret of [ADDRESS, OTHER, '0.05', 'USDC', 'SOL']) assert.equal(words.includes(secret), false, secret)
})

// ----------------------------------------------------------- the route

test('the week needs a wallet, and is empty while memory is off', async () => {
  const e = env()
  const noWallet = await worker.fetch(req('/week', null, undefined, 'GET'), e)
  assert.equal(noWallet.status, 401)

  const { session } = await connected(e)
  await worker.fetch(ask(session, 'screen'), e)
  const off = await (await worker.fetch(req('/week', null, session, 'GET'), e)).json()
  assert.deepEqual([off.memory_on, off.line, off.items], [false, '', []])
  assert.deepEqual(off.counts, EMPTY_COUNTS, 'with memory off nothing was counted in the first place')
})

test('with memory on, questions, screens and sends are counted, and the card says so', async () => {
  const e = env()
  const { session } = await connected(e)
  await worker.fetch(req('/memory/consent', { on: true }, session), e)

  await worker.fetch(ask(session, 'screen'), e)
  await worker.fetch(ask(session, 'screen'), e)
  await worker.fetch(ask(session, 'transaction'), e)
  await worker.fetch(ask(session, 'lesson'), e)

  const body = await (await worker.fetch(req('/week', null, session, 'GET'), e)).json()
  assert.equal(body.memory_on, true)
  assert.equal(body.week_start, '2026-09-14')
  assert.deepEqual([body.counts.questions, body.counts.screens, body.counts.transactions, body.counts.lessons], [4, 2, 1, 1])
  assert.equal(body.line, 'This week: 1 transaction explained, 2 screens explained, 1 lesson finished.')
  assert.equal(JSON.stringify(body).includes('seen'), false, 'the hashes stay on the worker')
})

test("a lesson's kept progress is a lesson finished", async () => {
  const e = env()
  const { session } = await connected(e)
  await worker.fetch(req('/memory/consent', { on: true }, session), e)
  const kept = await worker.fetch(req('/memory', {
    category: 'skill_progress', content: 'knows PDAs, 2026-09-16', consent: 'inferred', source_turn: 'app',
  }, session), e)
  assert.equal(kept.status, 200)
  const body = await (await worker.fetch(req('/week', null, session, 'GET'), e)).json()
  assert.equal(body.counts.lessons, 1)
  assert.equal(body.line, 'This week: 1 lesson finished.')
})

test('turning memory off throws the week away with the rest', async () => {
  const e = env()
  const { session, pubkey } = await connected(e)
  await worker.fetch(req('/memory/consent', { on: true }, session), e)
  await worker.fetch(ask(session, 'screen'), e)
  assert.equal([...kv.values.keys()].some((k) => k.startsWith('week:')), true)

  await worker.fetch(req('/memory/consent', { on: false }, session), e)
  assert.equal([...kv.values.keys()].some((k) => k.startsWith(`week:${pubkey}`)), false, 'the week goes with memory')
  const body = await (await worker.fetch(req('/week', null, session, 'GET'), e)).json()
  assert.deepEqual([body.memory_on, body.counts.questions], [false, 0])
})

test('the week log says whether anything was caught, and never what', async () => {
  const e = env()
  const { session } = await connected(e)
  await worker.fetch(req('/memory/consent', { on: true }, session), e)
  await worker.fetch(ask(session, 'screen'), e)
  logs = []
  await worker.fetch(req('/week', null, session, 'GET'), e)
  const line = logs.find((l) => l.includes('"route":"week"'))!
  assert.match(line, /"caught":true/)
  assert.equal(line.includes(ADDRESS), false)
})
