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
let chain: { skrPrice: number; skrDecimals: number; skrProgram: string; tx: unknown; referenced: unknown[] }
let rpcCalls: string[] = []

beforeEach(() => {
  clock.now = () => SEPT
  rpcCalls = []
  chain = { skrPrice: 0.05, skrDecimals: 6, skrProgram: TOKEN_PROGRAM, tx: null, referenced: [] }
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
      if (method === 'getSignaturesForAddress') return new Response(JSON.stringify({ result: chain.referenced }))
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

test('on devnet SKR is refused in plain words, with no chain call, and USDC still quotes', async () => {
  const e = env({ CLUSTER: 'devnet' })
  const { session } = await connected(e)
  const skr = await worker.fetch(req('/pay/quote', { currency: 'skr' }, session), e)
  assert.equal(skr.status, 503)
  assert.equal((await skr.json()).reason, 'not_on_devnet')
  assert.equal(rpcCalls.length, 0)
  const usdc = await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)
  assert.equal(usdc.status, 200)
})

test('a blockhash is only handed out for the cluster the worker takes payments on', async () => {
  const e = env({ CLUSTER: 'devnet' })
  const { session } = await connected(e)
  const wrong = await worker.fetch(req('/pay/blockhash', { cluster: 'mainnet-beta' }, session), e)
  assert.equal(wrong.status, 409)
  assert.equal((await wrong.json()).reason, 'wrong_cluster')
  assert.equal(rpcCalls.length, 0, 'refused before asking the chain')
  const right = await (await worker.fetch(req('/pay/blockhash', { cluster: 'devnet' }, session), e)).json()
  assert.equal(right.cluster, 'devnet')
  assert.equal(right.blockhash, 'EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k')
})

async function quoted(e: Env, over: Record<string, unknown> = {}, asked: Record<string, unknown> = {}) {
  const who = await connected(e)
  const q = await (await worker.fetch(req('/pay/quote', { currency: 'usdc', ...asked }, who.session), e)).json()
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

test('a month and a year are quoted at their own prices, in USDC and in SKR', async () => {
  const e = env({ PRICE_USD: '5', PRICE_YEAR_USD: '40' } as any)
  const { session } = await connected(e)

  const month = await (await worker.fetch(req('/pay/quote', { currency: 'usdc', period: 'month' }, session), e)).json()
  assert.deepEqual([month.period, month.price_usd, month.days, month.amount], ['month', '5', 30, '5000000'])

  const year = await (await worker.fetch(req('/pay/quote', { currency: 'usdc', period: 'year' }, session), e)).json()
  assert.deepEqual([year.period, year.price_usd, year.days, year.amount], ['year', '40', 365, '40000000'])

  // SKR follows the same dollars through Jupiter: $40 at $0.05 is 800 SKR.
  const skr = await (await worker.fetch(req('/pay/quote', { currency: 'skr', period: 'year' }, session), e)).json()
  assert.deepEqual([skr.period, skr.days, skr.amount], ['year', 365, '800000000'])

  // An app that says nothing still gets a month, as it always did.
  const old = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()
  assert.deepEqual([old.period, old.days, old.amount], ['month', 30, '5000000'])

  const nonsense = await worker.fetch(req('/pay/quote', { currency: 'usdc', period: 'decade' }, session), e)
  assert.deepEqual([nonsense.status, (await nonsense.json()).reason], [400, 'bad_period'])
})

test('with no year price a year cannot be bought, and a month still can', async () => {
  const e = env({ PRICE_USD: '5', PRICE_YEAR_USD: undefined } as any)
  const { session } = await connected(e)
  const refused = await worker.fetch(req('/pay/quote', { currency: 'usdc', period: 'year' }, session), e)
  assert.deepEqual([refused.status, (await refused.json()).reason], [503, 'not_configured'])
  assert.equal((await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()).amount, '5000000')
})

test('a year\'s payment extends Pro by 365 days, a month\'s by 30', async () => {
  const e = env({ PRICE_USD: '5', PRICE_YEAR_USD: '40' } as any)
  const year = await quoted(e, {}, { period: 'year' })
  const first = await (await worker.fetch(req('/pay/confirm', { reference: year.q.reference, signature: SIG }, year.session), e)).json()
  assert.equal(first.plan, 'pro')
  assert.equal(first.pro_until, '2027-09-15T12:00:00.000Z', 'a year from today')

  const month = await quoted(e)
  const second = await (await worker.fetch(
    req('/pay/confirm', { reference: month.q.reference, signature: '7'.repeat(88) }, month.session), e,
  )).json()
  assert.equal(second.pro_until, '2026-10-15T12:00:00.000Z')
})

test('a treasury wallet paying itself is refused and unlocks nothing', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  e.TREASURY_ADDRESS = pubkey
  const q = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()
  assert.equal(q.treasury, pubkey)
  chain.tx = paymentTx({ payer: pubkey, sender: pubkey, destOwner: pubkey, amount: q.amount, reference: q.reference })
  const res = await worker.fetch(req('/pay/confirm', { reference: q.reference, signature: SIG }, session), e)
  assert.equal(res.status, 402)
  assert.equal((await res.json()).reason, 'self_payment')
  const me = await (await worker.fetch(new Request('https://proxy.heylana.xyz/me', { headers: { 'X-Heylana-Device': DEVICE, Authorization: `Bearer ${session}` } }), e)).json()
  assert.equal(me.plan, 'free')
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
  assert.equal(res.status, 500)
  const text = await res.text()
  assert.ok(!text.includes('secret-token-abc'))
  assert.equal(JSON.parse(text).reason, 'internal')
})

test('a payment the wallet gave no signature for is found by its reference', async () => {
  const e = env()
  const { session, q } = await quoted(e)
  chain.referenced = [{ signature: SIG, err: null }]
  const res = await worker.fetch(req('/pay/confirm', { reference: q.reference }, session), e)
  assert.equal(res.status, 200)
  assert.equal((await res.json()).plan, 'pro')
})

test('with no signature and nothing on the reference yet, the payment is not confirmed', async () => {
  const e = env()
  const { session, q } = await quoted(e)
  chain.referenced = []
  const res = await worker.fetch(req('/pay/confirm', { reference: q.reference }, session), e)
  assert.equal(res.status, 409)
  assert.equal((await res.json()).reason, 'not_confirmed')
})
