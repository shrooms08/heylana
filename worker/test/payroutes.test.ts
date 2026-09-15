import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { encodeBase58 } from '../src/base58.ts'
import { STRANGER, TOKEN_PROGRAM, TREASURY, USDC, paymentTx } from './fixtures.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const SKR = 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3'
const RPC = 'https://rpc.test/secret-token-abc'
const SEPT = Date.parse('2026-09-15T12:00:00Z')

function store() {
  const values = new Map<string, string>()
  return {
    values,
    async get(k: string) { return values.get(k) ?? null },
    async put(k: string, v: string) { values.set(k, v) },
    async delete(k: string) { values.delete(k) },
  }
}

function env(over: Partial<Env> = {}, kv = store()): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge-code-xyz',
    RPC_URL: RPC, DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: TREASURY, USDC_MINT: USDC, SKR_MINT: SKR, PRICE_USD: '15', PRO_DAYS: '30',
    CAPS: kv, ...over,
  }
}

/** What the mocked chain and price service say. */
let chain: { skrPrice: number; skrDecimals: number; skrProgram: string; tx: unknown }
let rpcCalls: string[] = []

beforeEach(() => {
  clock.now = () => SEPT
  rpcCalls = []
  chain = { skrPrice: 0.05, skrDecimals: 6, skrProgram: TOKEN_PROGRAM, tx: null }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('https://api.jup.ag/price/v3')) {
      return new Response(JSON.stringify({ [SKR]: { usdPrice: chain.skrPrice, decimals: chain.skrDecimals } }))
    }
    if (url === RPC) {
      const { method } = JSON.parse(init.body)
      rpcCalls.push(method)
      if (method === 'getAccountInfo') {
        return new Response(JSON.stringify({ result: { value: { owner: chain.skrProgram, data: { parsed: { info: { decimals: chain.skrDecimals } } } } } }))
      }
      if (method === 'getLatestBlockhash') {
        return new Response(JSON.stringify({ result: { value: { blockhash: 'EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k', lastValidBlockHeight: 42 } } }))
      }
      if (method === 'getTransaction') return new Response(JSON.stringify({ result: chain.tx }))
    }
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch
})

function req(path: string, body: unknown, session?: string) {
  const headers: Record<string, string> = { 'X-Heylana-Device': DEVICE }
  if (session) headers.Authorization = `Bearer ${session}`
  return new Request(`https://proxy.heylana.xyz${path}`, { method: 'POST', headers, body: JSON.stringify(body) })
}

async function connected(e: Env) {
  const pair = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify'])) as CryptoKeyPair
  const pubkey = encodeBase58(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)))
  const challenge = await (await worker.fetch(req('/wallet/challenge', { pubkey }), e)).json()
  const signature = encodeBase58(new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, pair.privateKey, new TextEncoder().encode(challenge.message))))
  const body = await (await worker.fetch(req('/wallet/verify', { pubkey, nonce: challenge.nonce, signature }), e)).json()
  return { pubkey, session: body.session as string }
}

const SIG = '5'.repeat(88)

test('a quote needs a connected wallet', async () => {
  const res = await worker.fetch(req('/pay/quote', { currency: 'usdc' }), env())
  assert.equal(res.status, 401)
  assert.equal((await res.json()).reason, 'session_required')
})

test('a USDC quote is the price in base units, to the treasury, with a fresh reference', async () => {
  const e = env()
  const { session } = await connected(e)
  const q = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()
  assert.equal(q.currency, 'usdc')
  assert.equal(q.mint, USDC)
  assert.equal(q.amount, '15000000')
  assert.equal(q.decimals, 6)
  assert.equal(q.token_program, TOKEN_PROGRAM)
  assert.equal(q.treasury, TREASURY)
  assert.equal(q.expires_at, '2026-09-15T12:10:00.000Z')
  assert.equal(q.pubkey, undefined, 'the wallet is not echoed')
  assert.equal(rpcCalls.length, 0, 'a USDC quote needs no chain call')
})

test('the smoke-test price of ten cents quotes 0.10 USDC', async () => {
  const e = env({ PRICE_USD: '0.10' })
  const { session } = await connected(e)
  const q = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()
  assert.equal(q.amount, '100000')
  assert.equal(q.price_usd, '0.10', 'the app shows what an SKR amount is worth')
  assert.equal(q.pubkey, undefined)
})

test('an SKR quote reads decimals from the chain and prices it through Jupiter, rounded up', async () => {
  const e = env()
  const { session } = await connected(e)
  const q = await (await worker.fetch(req('/pay/quote', { currency: 'skr' }, session), e)).json()
  assert.equal(q.mint, SKR)
  assert.equal(q.amount, '300000000') // $15 at $0.05, 6 decimals
  assert.equal(q.decimals, 6)
  chain.skrPrice = 0.03
  chain.skrDecimals = 9
  const e2 = env({ PRICE_USD: '0.10' })
  const s2 = (await connected(e2)).session
  const q2 = await (await worker.fetch(req('/pay/quote', { currency: 'skr' }, s2), e2)).json()
  assert.equal(q2.amount, '3333333334')
})

test('SKR is off until its mint is filled in, and says so', async () => {
  const e = env({ SKR_MINT: 'replace-me' })
  const { session } = await connected(e)
  const res = await worker.fetch(req('/pay/quote', { currency: 'skr' }, session), e)
  assert.equal(res.status, 503)
  assert.equal((await res.json()).reason, 'not_configured')
})

test('a blockhash is handed out fresh at pay time', async () => {
  const e = env()
  const { session } = await connected(e)
  const b = await (await worker.fetch(req('/pay/blockhash', {}, session), e)).json()
  assert.equal(b.blockhash, 'EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k')
  assert.equal(b.last_valid_block_height, 42)
})

async function quoted(e: Env, over: Record<string, unknown> = {}) {
  const who = await connected(e)
  const q = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, who.session), e)).json()
  chain.tx = paymentTx({ payer: who.pubkey, sender: who.pubkey, amount: q.amount, reference: q.reference, ...over })
  return { ...who, q }
}

test('a correct payment makes the wallet Pro for thirty days', async () => {
  const e = env()
  const { session, q } = await quoted(e)
  const res = await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, session), e)
  assert.equal(res.status, 200)
  const me = await res.json()
  assert.equal(me.plan, 'pro')
  assert.equal(me.limit, null)
  assert.equal(me.pro_until, '2026-10-15T12:00:00.000Z')
})

for (const [name, over, reason] of [
  ['the wrong mint', { mint: 'So11111111111111111111111111111111111111112' }, 'wrong_mint'],
  ['too little', { amount: '14999999' }, 'short_amount'],
  ['the wrong destination', { destOwner: STRANGER }, 'wrong_destination'],
  ['the wrong sender', { sender: STRANGER }, 'wrong_sender'],
  ['no reference', { reference: null }, 'no_reference'],
] as const) {
  test(`a payment with ${name} is refused and unlocks nothing`, async () => {
    const e = env()
    const { session, q } = await quoted(e, over)
    const res = await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, session), e)
    assert.equal(res.status, 402)
    assert.equal((await res.json()).reason, reason)
    const me = await (await worker.fetch(new Request('https://proxy.heylana.xyz/me', { headers: { 'X-Heylana-Device': DEVICE, Authorization: `Bearer ${session}` } }), e)).json()
    assert.equal(me.plan, 'free')
  })
}

test('an unconfirmed transaction is not an error, it is not yet', async () => {
  const e = env()
  const { session, q } = await quoted(e)
  chain.tx = null
  const res = await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, session), e)
  assert.equal(res.status, 409)
  assert.equal((await res.json()).reason, 'not_confirmed')
})

test('confirming twice extends Pro once', async () => {
  const e = env()
  const { session, q } = await quoted(e)
  const first = await (await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, session), e)).json()
  const second = await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, session), e)
  assert.equal(second.status, 200)
  const body = await second.json()
  assert.equal(body.already_confirmed, true)
  assert.equal(body.pro_until, first.pro_until)
  assert.equal(rpcCalls.filter((m) => m === 'getTransaction').length, 1)
})

test('one transaction cannot pay for two quotes', async () => {
  const e = env()
  const { session, q, pubkey } = await quoted(e)
  await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, session), e)
  const q2 = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()
  chain.tx = paymentTx({ payer: pubkey, sender: pubkey, amount: q2.amount, reference: q2.reference })
  const res = await worker.fetch(req('/pay/confirm', { reference: q2.reference, signature: SIG }, session), e)
  assert.equal(res.status, 409)
  assert.equal((await res.json()).reason, 'signature_used')
})

test('someone else’s quote cannot be confirmed', async () => {
  const e = env()
  const { q } = await quoted(e)
  const other = await connected(e)
  const res = await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, other.session), e)
  assert.equal(res.status, 403)
})

test('paying while already Pro extends from the end of it', async () => {
  const e = env()
  const { session, q, pubkey } = await quoted(e)
  await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, session), e)
  const q2 = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()
  chain.tx = paymentTx({ payer: pubkey, sender: pubkey, amount: q2.amount, reference: q2.reference })
  const me = await (await worker.fetch(req('/pay/confirm', { reference: q2.reference, signature: '6'.repeat(88) }, session), e)).json()
  assert.equal(me.pro_until, '2026-11-14T12:00:00.000Z')
})

test('the RPC token never appears in an error', async () => {
  const e = env()
  const { session } = await connected(e)
  globalThis.fetch = (async () => { throw new Error(`connect failed to ${RPC}`) }) as typeof fetch
  const res = await worker.fetch(req('/pay/blockhash', {}, session), e)
  assert.equal(res.status, 502)
  assert.ok(!(await res.text()).includes('secret-token-abc'))
})
