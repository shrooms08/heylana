import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { TOKEN_PROGRAM } from '../src/pay.ts'
import { GRANTS_POWER, drainCheck, whatItDoes } from '../src/build.ts'
import worker, { clock, type Env } from '../src/index.ts'
import { decodeBase58, encodeBase58 } from '../src/base58.ts'
import { forgetRpcClusters } from '../src/cluster.ts'
import { compileMessage, fromBase64 } from '../src/tx.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const RPC = 'https://rpc.test/secret-token-abc'
const USDC = '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU'
const TOKEN = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
const TREASURY = '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv'
const FRIEND = '4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T'
const DEVNET_GENESIS = 'EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG'
const MAINNET_GENESIS = '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d'
const BLOCKHASH = 'EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N'
const SEPT = Date.parse('2026-09-15T12:00:00Z')
const SIG = '5'.repeat(88)

function store() {
  const values = new Map<string, string>()
  return { values, async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } }
}

function env(): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: RPC,
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: TREASURY, USDC_MINT: USDC, SKR_MINT: 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3',
    PRICE_USD: '0.10', PRO_DAYS: '30', CLUSTER: 'devnet', CAPS: store(),
  } as Env
}

/** The mocked chain: what each RPC method answers, changed per test. */
let chain: {
  genesis: string
  balances: Record<string, string>
  recipientHasAccount: boolean
  simulations: unknown[]
  simulateThrows: boolean
  fee: number | null
  blockHeight: number
  signatures: Record<string, unknown[]>
  tx: unknown
  calls: string[]
  simulated: string[]
  /** The connected wallet: its token accounts are the balances; anyone else is a recipient. */
  balancesOwner?: string
}
let logs: string[]

beforeEach(() => {
  forgetRpcClusters()
  clock.now = () => SEPT
  logs = []
  console.log = (line: string) => { logs.push(String(line)) }
  chain = {
    genesis: DEVNET_GENESIS, balances: {}, recipientHasAccount: true, simulations: [{ err: null, logs: [], unitsConsumed: 6200 }],
    simulateThrows: false, fee: 5000, blockHeight: 100, signatures: {}, tx: null, calls: [], simulated: [],
  }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url !== RPC) throw new Error(`unexpected fetch ${url}`)
    const { method, params } = JSON.parse(String(init.body))
    chain.calls.push(method)
    if (method === 'simulateTransaction' && chain.simulateThrows) {
      return new Response(JSON.stringify({ error: { code: -32005, message: 'busy' } }))
    }
    const result = (() => {
      if (method === 'getGenesisHash') return chain.genesis
      if (method === 'getAccountInfo') return { value: { owner: TOKEN, data: { parsed: { info: { decimals: 6 } } } } }
      if (method === 'getTokenAccountsByOwner') {
        const owner = params[0]
        const amount = chain.balances[owner]
        if (amount) return { value: [{ pubkey: 'x', account: { data: { parsed: { info: { mint: USDC, tokenAmount: { amount, decimals: 6 } } } } } }] }
        if (owner !== chain.balancesOwner && chain.recipientHasAccount) {
          return { value: [{ pubkey: 'y', account: { data: { parsed: { info: { mint: USDC, tokenAmount: { amount: '0', decimals: 6 } } } } } }] }
        }
        return { value: [] }
      }
      if (method === 'getBalance') return { value: 1_000_000_000 }
      if (method === 'getMinimumBalanceForRentExemption') return 2_039_280
      if (method === 'getLatestBlockhash') return { value: { blockhash: BLOCKHASH, lastValidBlockHeight: 150 } }
      if (method === 'simulateTransaction') {
        chain.simulated.push(params[0])
        const next = chain.simulations.length > 1 ? chain.simulations.shift() : chain.simulations[0]
        return { value: next }
      }
      if (method === 'getFeeForMessage') return { value: chain.fee }
      if (method === 'getBlockHeight') return chain.blockHeight
      if (method === 'getSignaturesForAddress') return chain.signatures[params[0]] ?? []
      if (method === 'getSignatureStatuses') return { value: [chain.tx ? { confirmationStatus: 'confirmed', err: null } : null] }
      if (method === 'getTransaction') return chain.tx
      throw new Error(`unexpected rpc ${method}`)
    })()
    return new Response(JSON.stringify({ result }))
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
  chain.balancesOwner = pubkey
  return { pubkey, session: body.session as string }
}

async function preparedSend(e: Env, session: string, to = FRIEND, amount = '0.05', token = 'USDC') {
  const res = await worker.fetch(req('/send/prepare', { to, amount, token, said: `send ${amount} ${token} to ${to}` }, session), e)
  assert.equal(res.status, 200)
  return (await res.json()).id as string
}

async function build(e: Env, session: string, body: Record<string, unknown>) {
  // A final build is R3: it takes the confirmation the app gets when the user taps Confirm.
  let confirmation: string | undefined
  if (body.final === true) {
    const kind = body.id ? 'send' : 'pay'
    const confirmed = await worker.fetch(req('/confirm', { kind, subject: body.id ?? body.reference }, session), e)
    confirmation = confirmed.ok ? (await confirmed.json()).confirmation : undefined
  }
  const res = await worker.fetch(req('/send/build', { cluster: 'devnet', ...body, ...(confirmation ? { confirmation } : {}) }, session), e)
  return { status: res.status, body: await res.json() }
}

/** A preview first, as the strip does; then the final build with its confirmation. */
async function previewThenFinal(e: Env, session: string, body: Record<string, unknown>) {
  const preview = await build(e, session, body)
  const final = await build(e, session, { ...body, final: true })
  return { preview, final }
}

const keysOf = (b64: string) => {
  const bytes = fromBase64(b64)
  let o = 1 + 64 * bytes[0] + 3
  const n = bytes[o++]
  const keys: string[] = []
  for (let i = 0; i < n; i++, o += 32) keys.push(encodeBase58(bytes.slice(o, o + 32)))
  return keys
}

// ------------------------------------------------------------------- passing

test('a passing simulation comes back with the preview and no transaction until final', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)

  const preview = await build(e, session, { id })
  assert.equal(preview.status, 200)
  assert.equal(preview.body.kind, 'send')
  assert.deepEqual(preview.body.simulation, { ok: true, units_consumed: 6200 })
  assert.equal(preview.body.transaction, undefined, 'no bytes to sign before the user confirms')
  assert.deepEqual(preview.body.preview, {
    from: `${pubkey.slice(0, 4)}…${pubkey.slice(-4)}`, from_label: 'your wallet',
    to: '4Nd1…DB4T', to_label: 'a wallet', amount: '0.05', token: 'USDC', fee_sol: '0.000005',
    account_rent_sol: '0', creates_account: false,
    programs: ['Associated Token Account Program', 'SPL Token Program'], cluster: 'devnet',
    // Read back out of the bytes that were built, not from what was asked for.
    does: ['Sends 0.05 USDC to 4Nd1…DB4T.'], grants_power: false,
  })
  assert.equal(chain.simulated.length, 1, 'simulated once')

  const final = await build(e, session, { id, final: true })
  assert.equal(final.body.simulation.ok, true)
  const keys = keysOf(final.body.transaction)
  assert.equal(keys[0], pubkey, 'the user pays the fee')
  assert.ok(keys.includes(id), "the send's id rides along as its reference")
  assert.equal(final.body.transaction, chain.simulated[1], 'the bytes handed out are exactly the ones simulated')
})

test('the treasury is named, and a new account and its rent are in the preview', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  chain.recipientHasAccount = false
  const id = await preparedSend(e, session, TREASURY)
  const { body } = await build(e, session, { id })
  assert.equal(body.preview.to_label, 'your Heylana treasury')
  assert.equal(body.preview.creates_account, true)
  assert.equal(body.preview.account_rent_sol, '0.00203928')
})

test('a stale blockhash is rebuilt once and simulated again', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  chain.simulations = [{ err: 'BlockhashNotFound', logs: [] }, { err: null, logs: [], unitsConsumed: 1 }]
  const { body } = await build(e, session, { id })
  assert.equal(body.simulation.ok, true)
  assert.equal(chain.calls.filter((m) => m === 'getLatestBlockhash').length, 2)
})

// ------------------------------------------------------------------- failing

test('not enough USDC: plain words, and no transaction even when final is asked', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '30000'
  const id = await preparedSend(e, session)
  chain.simulations = [{ err: { InstructionError: [1, { Custom: 1 }] }, logs: ['Program log: Error: insufficient funds'] }]
  const { preview, final } = await previewThenFinal(e, session, { id })
  assert.deepEqual(preview.body.simulation, { ok: false, reason: 'not_enough_token', words: 'Not enough USDC. You have 0.03.' })
  assert.equal(final.status, 403, 'a send that failed simulation cannot be confirmed')
  assert.equal(final.body.transaction, undefined)
})

test("the recipient's account needs creating and there is no SOL for it", async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  chain.recipientHasAccount = false
  const id = await preparedSend(e, session)
  chain.simulations = [{ err: { InstructionError: [0, { Custom: 1 }] }, logs: ['Transfer: insufficient lamports 1000, need 2039280'] }]
  const { preview, final } = await previewThenFinal(e, session, { id })
  assert.equal(preview.body.simulation.reason, 'recipient_account_needs_sol')
  assert.equal(preview.body.simulation.words, "The recipient's USDC account needs creating, fee 0.00203928 SOL, and there isn't enough SOL for it.")
  assert.equal(final.body.transaction, undefined)
})

test('not enough SOL for a SOL send', async () => {
  const e = env()
  const { session } = await connected(e)
  const id = await preparedSend(e, session, FRIEND, '5', 'SOL')
  chain.simulations = [{ err: { InstructionError: [0, { Custom: 1 }] }, logs: ['Transfer: insufficient lamports 100, need 5000000000'] }]
  const { body } = await build(e, session, { id })
  assert.equal(body.simulation.reason, 'not_enough_sol')
  assert.ok(body.simulation.words.startsWith('Not enough SOL.'))
})

test('no SOL at all for the fee', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  chain.simulations = [{ err: 'AccountNotFound', logs: [] }]
  const { body } = await build(e, session, { id })
  assert.deepEqual(body.simulation, { ok: false, reason: 'no_sol_for_fee', words: 'Your wallet has no SOL to pay the network fee.' })
})

test('a simulation that cannot be run is a failure, never a pass', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  chain.simulateThrows = true
  const { preview, final } = await previewThenFinal(e, session, { id })
  assert.equal(preview.body.simulation.ok, false)
  assert.equal(preview.body.simulation.reason, 'simulation_unavailable')
  assert.equal(final.body.transaction, undefined)
})

test('an unexplained program error is named, with no log line or address in it', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  chain.simulations = [{ err: { InstructionError: [1, 'InvalidAccountData'] }, logs: [`Program ${TOKEN} failed`] }]
  const { body } = await build(e, session, { id })
  assert.equal(body.simulation.reason, 'program_error')
  assert.equal(body.simulation.words, 'The network refused it: invalid account data.')
})

// ------------------------------------------------------------- wrong network

test('the wrong network stops before anything is built', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  chain.calls = []
  const res = await build(e, session, { id, cluster: 'mainnet-beta' })
  assert.equal(res.status, 409)
  assert.equal(res.body.reason, 'wrong_cluster')
  assert.equal(res.body.detail, 'This is for mainnet-beta, but Heylana is on devnet. I stopped before building it.')
  assert.equal(chain.calls.includes('simulateTransaction'), false)
})

test("an RPC on another network than the worker's stops it too", async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  forgetRpcClusters()
  chain.genesis = MAINNET_GENESIS
  const res = await build(e, session, { id })
  assert.equal(res.status, 503)
  assert.equal(res.body.reason, 'rpc_wrong_cluster')
})

// ---------------------------------------------------------------- Pro payment

test('a Pro payment is built and simulated from its quote', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '50000'
  const quote = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()
  const { final: { body } } = await previewThenFinal(e, session, { reference: quote.reference })
  assert.equal(body.kind, 'pay')
  assert.equal(body.preview.to_label, 'Heylana, for 30 days of Pro')
  assert.equal(body.preview.amount, '0.1')
  assert.ok(keysOf(body.transaction).includes(quote.reference))

  chain.simulations = [{ err: { InstructionError: [1, { Custom: 1 }] }, logs: [] }]
  const short = await previewThenFinal(e, session, { reference: quote.reference })
  assert.deepEqual(short.preview.body.simulation, { ok: false, reason: 'not_enough_token', words: 'Not enough USDC. You have 0.05.' })
  assert.equal(short.final.status, 403)
  assert.equal(short.final.body.transaction, undefined)
})

test("someone else's send or quote is not built", async () => {
  const e = env()
  const a = await connected(e)
  chain.balances[a.pubkey] = '5000000'
  const id = await preparedSend(e, a.session)
  const b = await connected(e)
  assert.equal((await build(e, b.session, { id })).status, 403)
  assert.equal((await build(e, b.session, {})).status, 400)
})

// ------------------------------------------------------------------- status

test('a send whose blockhash ran out without landing is expired, not pending', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  await previewThenFinal(e, session, { id })
  chain.blockHeight = 120
  assert.equal((await worker.fetch(req('/send/confirm', { id }, session), e)).status, 409, 'still good: pending')
  chain.blockHeight = 151
  const res = await worker.fetch(req('/send/confirm', { id }, session), e)
  assert.equal(res.status, 410)
  assert.equal((await res.json()).reason, 'expired')
})

test('a send the wallet gave no signature for is found by its reference, and counted once', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  await previewThenFinal(e, session, { id })
  chain.tx = landedTokenTx(pubkey, id)
  chain.signatures[id] = [{ signature: SIG, blockTime: SEPT / 1000, err: null }]
  const found = await worker.fetch(req('/send/confirm', { id }, session), e)
  assert.equal(found.status, 200)
  assert.equal((await found.json()).signature, '5555…5555')
  const again = await (await worker.fetch(req('/send/confirm', { id, signature: SIG }, session), e)).json()
  assert.equal(again.already_confirmed, true)
})

test('a Pro payment whose blockhash ran out is expired', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '500000'
  const quote = await (await worker.fetch(req('/pay/quote', { currency: 'usdc' }, session), e)).json()
  await previewThenFinal(e, session, { reference: quote.reference })
  chain.blockHeight = 151
  const res = await worker.fetch(req('/pay/confirm', { reference: quote.reference }, session), e)
  assert.equal(res.status, 410)
})

test('the build log carries amounts and four characters of an address, never more', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  logs = []
  await build(e, session, { id })
  const line = logs.find((l) => l.includes('send/build'))!
  assert.ok(line.includes('"simulation":"passed"'))
  assert.ok(line.includes('"amount":"0.05"'))
  for (const address of [pubkey, FRIEND, id]) assert.equal(line.includes(address.slice(0, 5)), false)
})

test('every chain call is logged, and rolled into the request line by provider and ms', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  chain.balances[pubkey] = '5000000'
  const id = await preparedSend(e, session)
  logs = []
  await build(e, session, { id })

  const perCall = logs.map((l) => JSON.parse(l)).filter((l) => l.route === 'rpc')
  assert.ok(perCall.length > 0)
  for (const call of perCall) {
    assert.equal(call.provider, 'devnet', 'a devnet worker names its one provider')
    assert.equal(call.outcome, 'ok')
    assert.equal(typeof call.ms, 'number')
    assert.ok(typeof call.method === 'string' && call.method.startsWith('get') || call.method === 'simulateTransaction')
  }
  const line = JSON.parse(logs.find((l) => l.includes('"route":"send/build"'))!)
  assert.equal(line.rpc.length, perCall.length)
  assert.deepEqual([...new Set(line.rpc.map((c: any) => c.provider))], ['devnet'])
  assert.equal(line.rpc_ms, line.rpc.reduce((sum: number, c: any) => sum + c.ms, 0))
  // The addresses stay out of it: only the provider's name.
  assert.equal(JSON.stringify(line.rpc).includes('http'), false)
})

/** A landed transferChecked from [from] to FRIEND carrying [reference], as getTransaction returns it. */
function landedTokenTx(from: string, reference: string) {
  const fromAta = 'FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B'
  const toAta = 'C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH'
  assert.ok(decodeBase58(reference))
  return {
    meta: {
      err: null,
      preTokenBalances: [
        { accountIndex: 1, mint: USDC, owner: from, uiTokenAmount: {} },
        { accountIndex: 2, mint: USDC, owner: FRIEND, uiTokenAmount: {} },
      ],
      postTokenBalances: [],
    },
    transaction: {
      message: {
        accountKeys: [from, fromAta, toAta, USDC, TOKEN, reference].map((pubkey) => ({ pubkey })),
        instructions: [{
          program: 'spl-token', programId: TOKEN,
          parsed: { type: 'transferChecked', info: { source: fromAta, destination: toAta, mint: USDC, authority: from, tokenAmount: { amount: '50000', decimals: 6 } } },
        }],
      },
    },
  }
}

// ------------------------------------------------------------------ what it does, and what leaves

test('an instruction that hands over power is never handed out to be signed', () => {
  // The worker only ever builds transfers, so this is the net beneath that: bytes that
  // grant an approval are read back as such and refused before the wallet can open.
  const plan = {
    from: '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM', to: '4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T',
    mint: null, tokenProgram: null, units: 1n, decimals: 9, reference: null,
  }
  const approval = compileMessage(plan.from, [{
    programId: TOKEN_PROGRAM,
    keys: [plan.from, plan.to, plan.from].map((pubkey, i) => ({ pubkey, isSigner: i === 2, isWritable: true })),
    data: new Uint8Array([4, 1, 0, 0, 0, 0, 0, 0, 0]),
  }], '11111111111111111111111111111111')
  const read = whatItDoes(approval, 'USDC')
  assert.equal(read.grantsPower, true)
  assert.match(read.lines[0], /^Lets /)
  assert.match(GRANTS_POWER, /did not open the wallet/)
})

test('more leaving the wallet than was confirmed stops the send', () => {
  const plan = {
    from: '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM', to: '4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T',
    mint: null, tokenProgram: null, units: 50_000_000n, decimals: 9, reference: null,
  }
  const facts = { token: 'SOL', toLabel: 'a wallet', rentLamports: 0n, createsAccount: false, balance: null, cluster: 'devnet' }
  const fee = 5_000n

  // Exactly what was promised: the amount and its fee.
  assert.equal(drainCheck(1_000_000_000, { accounts: [{ lamports: 949_995_000 }] }, plan, facts, fee), null)
  // A priority fee's worth more is still fine.
  assert.equal(drainCheck(1_000_000_000, { accounts: [{ lamports: 945_000_000 }] }, plan, facts, fee), null)
  // A whole SOL more is not.
  const drained = drainCheck(2_000_000_000, { accounts: [{ lamports: 900_000_000 }] }, plan, facts, fee)
  assert.equal(drained?.ok, false)
  assert.equal(drained && !drained.ok && drained.reason, 'unexpected_drain')
  assert.match(drained && !drained.ok ? drained.words : '', /1\.1 SOL leaving your wallet/)
  // An RPC that will not say proves nothing, and never fails a send on its own.
  assert.equal(drainCheck(undefined, { accounts: [] }, plan, facts, fee), null)
  assert.equal(drainCheck(1_000_000_000, null, plan, facts, fee), null)
})
