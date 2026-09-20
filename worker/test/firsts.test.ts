import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { encodeBase58 } from '../src/base58.ts'
import { forgetRpcClusters } from '../src/cluster.ts'
import {
  FIRSTS_CAP, FIRST_DESTINATION, FIRST_PROGRAM, firstLines, firstsKey, hashFirst, parseFirsts, remember, unseen,
} from '../src/firsts.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const RPC = 'https://rpc.test/secret-token-abc'
const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v'
const TOKEN = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
const TREASURY = '7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU'
const FRIEND = '4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T'
const KNOWN_FACE = '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'
const DEVNET_GENESIS = 'EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG'
const BLOCKHASH = 'EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N'
const SEPT = Date.parse('2026-09-15T12:00:00Z')
const SIG = '5'.repeat(88)

// ------------------------------------------------------------------ the record itself

test('what it keeps cannot be read back into an address', async () => {
  const secret = 'test-session-secret-0123456789'
  const hash = await hashFirst(FRIEND, secret)
  assert.match(hash, /^[0-9a-f]{12}$/)
  assert.equal(hash.includes(FRIEND.slice(0, 4)), false)
  // The salt is the worker's, so the same address under another secret is another hash.
  assert.notEqual(hash, await hashFirst(FRIEND, 'another-secret'))
  // And the same address is always the same hash, or nothing could be told apart.
  assert.equal(hash, await hashFirst(FRIEND, secret))

  const stored = JSON.stringify(remember(parseFirsts(null), 'destination', [hash]))
  assert.equal(stored.includes(FRIEND), false, 'no address in the record, whole or in part')
  assert.match(stored, /^[0-9a-z ,.:_"{}\[\]-]+$/)
})

test('a hash is kept once, and the oldest go first past the cap', () => {
  const hashes = Array.from({ length: FIRSTS_CAP + 10 }, (_, i) => i.toString(16).padStart(12, '0'))
  let firsts = remember(parseFirsts(null), 'destination', hashes)
  assert.equal(firsts.destinations.length, FIRSTS_CAP)
  assert.equal(firsts.destinations.includes(hashes[0]), false, 'the oldest went')
  assert.equal(firsts.destinations.at(-1), hashes.at(-1))

  // The same one twice does not take two places.
  firsts = remember(firsts, 'destination', [hashes.at(-1)!])
  assert.equal(firsts.destinations.length, FIRSTS_CAP)
  // Destinations and programs are kept apart.
  assert.equal(firsts.programs.length, 0)
  assert.deepEqual(unseen(firsts, 'program', [hashes.at(-1)!]), [hashes.at(-1)!])
})

test('rubbish in the record is dropped rather than trusted', () => {
  const parsed = parseFirsts('{"destinations":["nope", "0123456789ab", 7],"programs":"no"}')
  assert.deepEqual(parsed.destinations, ['0123456789ab'])
  assert.deepEqual(parsed.programs, [])
  assert.deepEqual(parseFirsts('not json').destinations, [])
})

test('the lines say what was found and nothing more', () => {
  assert.deepEqual(firstLines({}), [])
  assert.deepEqual(firstLines({ newDestination: true }), [FIRST_DESTINATION])
  assert.deepEqual(firstLines({ newDestination: true, newPrograms: 2 }), [FIRST_DESTINATION, FIRST_PROGRAM])
  // Never a verdict: no "unsafe", no "scam", no advice not to sign.
  for (const line of [FIRST_DESTINATION, FIRST_PROGRAM]) {
    assert.equal(/unsafe|scam|danger|do not sign|risky/i.test(line), false, line)
  }
})

// ------------------------------------------------------------------ through the routes

function store() {
  const values = new Map<string, string>()
  return {
    values,
    async get(k: string) { return values.get(k) ?? null },
    async put(k: string, v: string) { values.set(k, v) },
    async delete(k: string) { values.delete(k) },
  }
}

function env(kv = store()): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', DEEPGRAM_API_KEY: 'd', CARTESIA_API_KEY: 'c',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: RPC,
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: TREASURY, USDC_MINT: USDC, SKR_MINT: 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3',
    PRICE_USD: '5', PRO_DAYS: '30', CLUSTER: 'devnet', CAPS: kv,
  } as Env
}

/** Who the wallet has dealt with lately, as the chain would say. */
let recentFaces: string[]
let landed: boolean
/** The wallet the test is signing as; its sends are what the chain reports back. */
let sender: string

/** A transfer of exactly what was prepared, as the RPC returns it with jsonParsed. */
function landedTx() {
  const keys = [sender, 'FROM_ATA', 'TO_ATA', USDC, TOKEN, ...recentFaces]
  return {
    meta: {
      err: null,
      preTokenBalances: [
        { accountIndex: 1, mint: USDC, owner: sender, uiTokenAmount: {} },
        { accountIndex: 2, mint: USDC, owner: FRIEND, uiTokenAmount: {} },
      ],
      postTokenBalances: recentFaces.map((owner, i) => ({ accountIndex: 5 + i, mint: USDC, owner, uiTokenAmount: {} })),
    },
    transaction: {
      message: {
        accountKeys: keys.map((pubkey) => ({ pubkey })),
        instructions: [{
          program: 'spl-token', programId: TOKEN,
          parsed: {
            type: 'transferChecked',
            info: { source: 'FROM_ATA', destination: 'TO_ATA', mint: USDC, authority: sender, tokenAmount: { amount: '50000', decimals: 6 } },
          },
        }],
      },
    },
  }
}

beforeEach(() => {
  forgetRpcClusters()
  clock.now = () => SEPT
  console.log = () => {}
  recentFaces = []
  landed = true
  sender = ''
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url !== RPC) throw new Error(`unexpected fetch ${url}`)
    const { method, params } = JSON.parse(String(init.body))
    const result = (() => {
      if (method === 'getGenesisHash') return DEVNET_GENESIS
      if (method === 'getAccountInfo') return { value: { owner: TOKEN, data: { parsed: { info: { decimals: 6 } } } } }
      if (method === 'getTokenAccountsByOwner') {
        return { value: [{ pubkey: 'x', account: { data: { parsed: { info: { mint: USDC, tokenAmount: { amount: '5000000', decimals: 6 } } } } } }] }
      }
      if (method === 'getBalance') return { value: 1_000_000_000 }
      if (method === 'getMinimumBalanceForRentExemption') return 2_039_280
      if (method === 'getLatestBlockhash') return { value: { blockhash: BLOCKHASH, lastValidBlockHeight: 150 } }
      if (method === 'simulateTransaction') return { value: { err: null, logs: [], unitsConsumed: 6200, accounts: [{ lamports: 999_995_000 }] } }
      if (method === 'getFeeForMessage') return { value: 5000 }
      if (method === 'getBlockHeight') return 100
      if (method === 'getSignaturesForAddress') return recentFaces.length ? [{ signature: SIG }] : []
      if (method === 'getSignatureStatuses') return { value: [landed ? { confirmationStatus: 'confirmed', err: null } : null] }
      if (method === 'getTransaction') return landedTx()
      throw new Error(`unexpected rpc ${method}`)
    })()
    return new Response(JSON.stringify({ result }))
  }) as typeof fetch
})

function req(path: string, body: unknown, session?: string, method = 'POST') {
  const headers: Record<string, string> = { 'X-Heylana-Device': DEVICE }
  if (session) headers.Authorization = `Bearer ${session}`
  return new Request(`https://proxy.heylana.xyz${path}`, { method, headers, body: method === 'GET' ? undefined : JSON.stringify(body) })
}

async function connected(e: Env) {
  const pair = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify'])) as CryptoKeyPair
  const pubkey = encodeBase58(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)))
  const challenge = await (await worker.fetch(req('/wallet/challenge', { pubkey }), e)).json() as any
  const signature = encodeBase58(new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, pair.privateKey, new TextEncoder().encode(challenge.message))))
  const body = await (await worker.fetch(req('/wallet/verify', { pubkey, nonce: challenge.nonce, signature }), e)).json() as any
  sender = pubkey
  return { pubkey, session: body.session as string }
}

async function memoryOn(e: Env, session: string) {
  const res = await worker.fetch(req('/memory/consent', { on: true }, session), e)
  assert.equal(res.status, 200)
}

async function prepared(e: Env, session: string, to = FRIEND) {
  const res = await worker.fetch(req('/send/prepare', { to, amount: '0.05', token: 'USDC', said: `send 0.05 USDC to ${to}` }, session), e)
  assert.equal(res.status, 200)
  return ((await res.json()) as any).id as string
}

async function build(e: Env, session: string, id: string) {
  const res = await worker.fetch(req('/send/build', { id }, session), e)
  assert.equal(res.status, 200)
  return (await res.json()) as any
}

test('a brand new destination says so, and stops saying so once a send has landed there', async () => {
  const e = env()
  const { session } = await connected(e)
  await memoryOn(e, session)

  const first = await build(e, session, await prepared(e, session))
  assert.equal(first.preview.first_destination, true)

  // It lands, so the address is one this wallet has sent to now.
  const id = await prepared(e, session)
  await build(e, session, id)
  const confirmed = await worker.fetch(req('/send/confirm', { id, signature: SIG }, session), e)
  assert.equal(confirmed.status, 200)

  const again = await build(e, session, await prepared(e, session))
  assert.equal(again.preview.first_destination, false, 'not the first time any more')
})

test('an address already in the wallet\'s recent history is not a first time', async () => {
  const e = env()
  const { session } = await connected(e)
  await memoryOn(e, session)
  // The chain says they have dealt with this address lately, even though Heylana never has.
  recentFaces = [FRIEND, KNOWN_FACE]

  const built = await build(e, session, await prepared(e, session))
  assert.equal(built.preview.first_destination, false)
})

test('with memory off nothing is asked and nothing is claimed', async () => {
  const e = env()
  const { session } = await connected(e)
  // Memory starts off: the question is the wallet's own history, so it is memory's.
  const built = await build(e, session, await prepared(e, session))
  assert.equal(built.preview.first_destination, false)
  assert.equal([...(e.CAPS as any).values.keys()].some((k: string) => k.startsWith('firsts:')), false)
})

test('turning memory off, or wiping it, throws away what it had seen before', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  await memoryOn(e, session)
  const id = await prepared(e, session)
  await build(e, session, id)
  const done = await worker.fetch(req('/send/confirm', { id, signature: SIG }, session), e)
  assert.equal(done.status, 200, JSON.stringify(await done.clone().json()))
  const kv = (e.CAPS as any).values as Map<string, string>
  assert.ok(kv.has(firstsKey(pubkey)), 'it kept something')

  await worker.fetch(req('/memory/wipe', {}, session), e)
  assert.equal(kv.has(firstsKey(pubkey)), false, 'wipe all means all')

  // And again, for the switch itself.
  const id2 = await prepared(e, session)
  await build(e, session, id2)
  // A second send needs a signature of its own; one signature pays for one send.
  await worker.fetch(req('/send/confirm', { id: id2, signature: '6'.repeat(88) }, session), e)
  assert.ok(kv.has(firstsKey(pubkey)))
  await worker.fetch(req('/memory/consent', { on: false }, session), e)
  assert.equal(kv.has(firstsKey(pubkey)), false)
})

test('the record holds nothing but hashes, whatever has happened to it', async () => {
  const e = env()
  const { pubkey, session } = await connected(e)
  await memoryOn(e, session)
  const id = await prepared(e, session)
  await build(e, session, id)
  await worker.fetch(req('/send/confirm', { id, signature: SIG }, session), e)

  const stored = (e.CAPS as any).values.get(firstsKey(pubkey)) as string
  const kept = parseFirsts(stored)
  assert.equal(kept.destinations.length, 1)
  assert.ok(kept.programs.length >= 1, 'the programs it called are kept too')
  assert.equal(stored.includes(FRIEND), false)
  assert.equal(stored.includes(pubkey), false)
  for (const hash of [...kept.destinations, ...kept.programs]) assert.match(hash, /^[0-9a-f]{12}$/)
})
