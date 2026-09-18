import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { encodeBase58 } from '../src/base58.ts'
import { checkSend, type PreparedSend } from '../src/send.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const RPC = 'https://rpc.test/secret-token-abc'
const USDC = '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU'
const TOKEN = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
const TO = '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv'
const STRANGER = 'DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263'
const FROM_ATA = 'FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B'
const TO_ATA = 'C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH'
const SIG = '5'.repeat(88)
const SEPT = Date.parse('2026-09-15T12:00:00Z')

function prepared(over: Partial<PreparedSend> = {}): PreparedSend {
  return {
    to_address: TO, resolved_from: null, amount: '0.05', token: 'USDC', mint: USDC, decimals: 6, token_program: TOKEN,
    fee_estimate: '0.000005', account_rent: '0', will_create_ata: false, balance: '5', cluster: 'devnet', from: 'FROM', ...over,
  }
}

function tokenTx(from: string, over: { amount?: string; mint?: string; destOwner?: string; signer?: string; err?: unknown } = {}) {
  return {
    meta: {
      err: over.err ?? null,
      preTokenBalances: [
        { accountIndex: 1, mint: over.mint ?? USDC, owner: from, uiTokenAmount: {} },
        { accountIndex: 2, mint: over.mint ?? USDC, owner: over.destOwner ?? TO, uiTokenAmount: {} },
      ],
      postTokenBalances: [],
    },
    transaction: {
      message: {
        accountKeys: [from, FROM_ATA, TO_ATA, USDC, TOKEN].map((pubkey) => ({ pubkey })),
        instructions: [{
          program: 'spl-token', programId: TOKEN,
          parsed: { type: 'transferChecked', info: { source: FROM_ATA, destination: TO_ATA, mint: over.mint ?? USDC, authority: over.signer ?? from, tokenAmount: { amount: over.amount ?? '50000', decimals: 6 } } },
        }],
      },
    },
  }
}

function solTx(from: string, lamports: number, destination = TO) {
  return {
    meta: { err: null, preTokenBalances: [], postTokenBalances: [] },
    transaction: { message: { accountKeys: [{ pubkey: from }, { pubkey: destination }], instructions: [{ program: 'system', parsed: { type: 'transfer', info: { source: from, destination, lamports } } }] } },
  }
}

// ---------------------------------------------------------------- the check

test('a token send that landed as prepared is confirmed', () => {
  assert.deepEqual(checkSend(tokenTx('FROM'), prepared()), { ok: true })
})

test('a token send to someone else, too little, or the wrong token is not', () => {
  for (const over of [{ destOwner: STRANGER }, { amount: '49999' }, { mint: STRANGER }]) {
    assert.deepEqual(checkSend(tokenTx('FROM', over), prepared()), { ok: false, reason: 'no_matching_transfer' })
  }
})

test("a transfer out of someone else's account is not this wallet's send", () => {
  assert.deepEqual(checkSend(tokenTx(STRANGER), prepared()), { ok: false, reason: 'no_matching_transfer' })
})

test("this wallet's own tokens moved by a delegate still came from this wallet", () => {
  assert.deepEqual(checkSend(tokenTx('FROM', { signer: STRANGER }), prepared()), { ok: true })
})

test('a SOL send is checked against the system transfer', () => {
  const sol = prepared({ token: 'SOL', mint: null, decimals: 9, token_program: null, amount: '0.25' })
  assert.deepEqual(checkSend(solTx('FROM', 250_000_000), sol), { ok: true })
  assert.equal((checkSend(solTx('FROM', 249_999_999), sol) as any).reason, 'no_matching_transfer')
  assert.equal((checkSend(solTx('FROM', 250_000_000, STRANGER), sol) as any).reason, 'no_matching_transfer')
})

test('not there yet, or failed on chain, says which', () => {
  assert.equal((checkSend(null, prepared()) as any).reason, 'not_confirmed')
  assert.equal((checkSend(tokenTx('FROM', { err: { InstructionError: [0, 'x'] } }), prepared()) as any).reason, 'failed_on_chain')
})

// --------------------------------------------------------------- the routes

function store() {
  const values = new Map<string, string>()
  return { values, async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } }
}

function env(kv = store()): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: RPC,
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: TO, USDC_MINT: USDC, SKR_MINT: 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3', PRICE_USD: '0.10', PRO_DAYS: '30', CLUSTER: 'devnet',
    CAPS: kv,
  } as Env
}

let chain: { tx: unknown; balances: Record<string, string>; signatures: unknown[]; byAddress: Record<string, unknown[]> }
let logs: string[]

beforeEach(() => {
  clock.now = () => SEPT
  chain = { tx: null, balances: {}, signatures: [], byAddress: {} }
  logs = []
  console.log = (line: string) => { logs.push(String(line)) }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url !== RPC) throw new Error(`unexpected fetch ${url}`)
    const { method, params } = JSON.parse(String(init.body))
    const result = (() => {
      if (method === 'getAccountInfo') return { value: { owner: TOKEN, data: { parsed: { info: { decimals: 6 } } } } }
      if (method === 'getTokenAccountsByOwner') {
        const amount = chain.balances[params[0]]
        return { value: amount ? [{ pubkey: FROM_ATA, account: { data: { parsed: { info: { mint: USDC, tokenAmount: { amount, decimals: 6 } } } } } }] : [] }
      }
      if (method === 'getBalance') return { value: 1_000_000_000 }
      if (method === 'getMinimumBalanceForRentExemption') return 2_039_280
      if (method === 'getTransaction') return chain.tx
      if (method === 'getSignaturesForAddress') return chain.byAddress[params[0]] ?? chain.signatures
      if (method === 'getSignatureStatuses') return { value: [chain.tx ? { confirmationStatus: 'confirmed', err: null } : null] }
      throw new Error(`unexpected rpc ${method}`)
    })()
    return new Response(JSON.stringify({ result }))
  }) as typeof fetch
})

function req(path: string, body: unknown, session?: string) {
  // These tests are about preparing and confirming: the user named the recipient themselves.
  // (Whether they did is test/redteam.test.ts's business.)
  if (path === '/send/prepare' && body && typeof body === 'object' && !('said' in body)) {
    const b = body as Record<string, unknown>
    body = { ...b, said: `send ${b.amount} ${b.token} to ${b.to}` }
  }
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

test('preparing a send needs a connected wallet', async () => {
  const res = await worker.fetch(req('/send/prepare', { to: TO, amount: 1, token: 'USDC' }), env())
  assert.equal(res.status, 401)
})

test('a prepared send comes back with an id, is kept, and is logged without addresses', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  logs = []
  const res = await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)
  assert.equal(res.status, 200)
  const quote = await res.json()
  assert.equal(quote.amount, '0.05')
  assert.equal(quote.to_address, TO)
  assert.equal(quote.balance, '5')
  assert.equal(quote.will_create_ata, true)
  assert.ok(await (e.CAPS as any).get(`send:${quote.id}`))
  const line = logs.find((l) => l.includes('send/prepare'))!
  assert.ok(line.includes('"amount":"0.05"'))
  assert.equal(line.includes(TO), false)
  assert.equal(line.includes(pubkey), false)
  assert.equal(line.includes(TO.slice(0, 5)), false, 'no more than four characters of an address')
})

test('"all" sends the whole token balance; SOL cannot be sent all', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '12000000'
  const all = await (await worker.fetch(req('/send/prepare', { to: TO, amount: 'all', token: 'USDC' }, session), e)).json()
  assert.equal(all.amount, '12')
  assert.equal(all.balance, '12')
  const sol = await worker.fetch(req('/send/prepare', { to: TO, amount: 'all', token: 'SOL' }, session), e)
  assert.equal(sol.status, 422)
  assert.equal((await sol.json()).reason, 'sol_all')
})

test('a send that cannot be prepared comes back with a plain reason', async () => {
  const e = env()
  const { session } = await connected(e)
  const res = await worker.fetch(req('/send/prepare', { to: 'bob', amount: 1, token: 'USDC' }, session), e)
  assert.equal(res.status, 422)
  const body = await res.json()
  assert.equal(body.reason, 'unknown_recipient')
  assert.ok(body.detail.length > 0)
})

test('confirming: not yet, then landed, then idempotent', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  const quote = await (await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)).json()

  const early = await worker.fetch(req('/send/confirm', { id: quote.id, signature: SIG }, session), e)
  assert.equal(early.status, 409)

  chain.tx = tokenTx(pubkey)
  const landed = await worker.fetch(req('/send/confirm', { id: quote.id, signature: SIG }, session), e)
  assert.equal(landed.status, 200)
  assert.deepEqual(await landed.json(), { confirmed: true, signature: '5555…5555' })

  chain.tx = null
  const again = await (await worker.fetch(req('/send/confirm', { id: quote.id, signature: SIG }, session), e)).json()
  assert.equal(again.already_confirmed, true)
})

test('a signed transaction that does not match is refused', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  const quote = await (await worker.fetch(req('/send/confirm'.replace('confirm', 'prepare'), { to: TO, amount: '0.05', token: 'USDC' }, session), e)).json()
  chain.tx = tokenTx(pubkey, { destOwner: STRANGER })
  const res = await worker.fetch(req('/send/confirm', { id: quote.id, signature: SIG }, session), e)
  assert.equal(res.status, 402)
  assert.equal((await res.json()).reason, 'no_matching_transfer')
})

test("someone else's send, an unknown one, or a bad signature is refused", async () => {
  const e = env()
  const a = await connected(e)
  const b = await connected(e)
  const quote = await (await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, a.session), e)).json()
  assert.equal((await worker.fetch(req('/send/confirm', { id: quote.id, signature: SIG }, b.session), e)).status, 403)
  assert.equal((await worker.fetch(req('/send/confirm', { id: 'nope', signature: SIG }, a.session), e)).status, 404)
  assert.equal((await worker.fetch(req('/send/confirm', { id: quote.id, signature: 'short' }, a.session), e)).status, 400)
})

// ------------------------------------------------- when the wallet gives none

const SIG2 = '4'.repeat(88)

test('with no signature from the wallet, the landed transfer is found on chain', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  const quote = await (await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)).json()
  chain.signatures = [{ signature: SIG2, blockTime: SEPT / 1000, err: null }]
  chain.tx = tokenTx(pubkey)
  const res = await worker.fetch(req('/send/confirm', { id: quote.id }, session), e)
  assert.equal(res.status, 200)
  assert.deepEqual(await res.json(), { confirmed: true, signature: '4444…4444' })
})

test('nothing new on chain is not confirmed, and an older transfer does not count', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  const quote = await (await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)).json()
  chain.signatures = [{ signature: SIG2, blockTime: SEPT / 1000 - 600, err: null }]
  chain.tx = tokenTx(pubkey)
  const res = await worker.fetch(req('/send/confirm', { id: quote.id }, session), e)
  assert.equal(res.status, 409)
})

test('a transfer already counted for one send is never counted for another', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  const first = await (await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)).json()
  const second = await (await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)).json()
  chain.tx = tokenTx(pubkey)
  assert.equal((await worker.fetch(req('/send/confirm', { id: first.id, signature: SIG }, session), e)).status, 200)
  chain.signatures = [{ signature: SIG, blockTime: SEPT / 1000, err: null }]
  assert.equal((await worker.fetch(req('/send/confirm', { id: second.id }, session), e)).status, 409)
  assert.equal((await worker.fetch(req('/send/confirm', { id: second.id, signature: SIG }, session), e)).status, 409)
})

test("a transfer seen only on the sender's token account is still found", async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const quote = await (await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)).json()
  chain.byAddress = { [pubkey]: [], [FROM_ATA]: [{ signature: SIG2, blockTime: SEPT / 1000, err: null }] }
  chain.tx = tokenTx(pubkey)
  const res = await worker.fetch(req('/send/confirm', { id: quote.id }, session), e)
  assert.equal(res.status, 200)
  assert.equal((await res.json()).signature, '4444…4444')
})
