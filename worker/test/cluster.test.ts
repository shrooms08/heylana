import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { encodeBase58 } from '../src/base58.ts'
import { forgetRpcClusters } from '../src/cluster.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const RPC = 'https://rpc.test/secret-token-abc'
const MAINNET_GENESIS = '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d'
const DEVNET_GENESIS = 'EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG'
const USDC_DEVNET = '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU'
const TO = '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv'

function store() {
  const values = new Map<string, string>()
  return { values, async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } }
}

function env(): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: RPC,
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: TO, USDC_MINT: USDC_DEVNET, SKR_MINT: 'replace-me', PRICE_USD: '0.10', PRO_DAYS: '30', CLUSTER: 'devnet',
    CAPS: store(),
  } as Env
}

let genesis: string | null
let genesisAsked = 0

beforeEach(() => {
  clock.now = () => Date.parse('2026-09-16T12:00:00Z')
  forgetRpcClusters()
  genesis = DEVNET_GENESIS
  genesisAsked = 0
  console.log = () => {}
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url !== RPC) throw new Error(`unexpected fetch ${url}`)
    const { method } = JSON.parse(String(init.body))
    const result = (() => {
      if (method === 'getGenesisHash') {
        genesisAsked++
        if (!genesis) throw new Error('no answer')
        return genesis
      }
      if (method === 'getLatestBlockhash') return { value: { blockhash: 'EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k', lastValidBlockHeight: 1 } }
      if (method === 'getAccountInfo') return { value: { owner: 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA', data: { parsed: { info: { decimals: 6 } } } } }
      if (method === 'getTokenAccountsByOwner') return { value: [] }
      if (method === 'getMinimumBalanceForRentExemption') return 2_039_280
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
  return { pubkey, session: body.session as string }
}

test('a devnet worker on a devnet RPC hands out a devnet blockhash, asking the RPC only once', async () => {
  const e = env()
  const { session } = await connected(e)
  for (let i = 0; i < 2; i++) {
    const res = await worker.fetch(req('/pay/blockhash', { cluster: 'devnet' }, session), e)
    assert.equal(res.status, 200)
    assert.equal((await res.json()).cluster, 'devnet')
  }
  assert.equal(genesisAsked, 1)
})

test('a devnet worker on a mainnet RPC refuses the blockhash in plain words', async () => {
  genesis = MAINNET_GENESIS
  const e = env()
  const { session } = await connected(e)
  const res = await worker.fetch(req('/pay/blockhash', { cluster: 'devnet' }, session), e)
  assert.equal(res.status, 503)
  const body = await res.json()
  assert.equal(body.reason, 'rpc_wrong_cluster')
  assert.match(body.detail, /set up for devnet but connected to mainnet-beta/)
  assert.equal(body.detail.includes('secret-token'), false)
})

test('a devnet worker on a mainnet RPC will not prepare a send either', async () => {
  genesis = MAINNET_GENESIS
  const e = env()
  const { session } = await connected(e)
  const res = await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)
  assert.equal(res.status, 503)
  assert.equal((await res.json()).reason, 'rpc_wrong_cluster')
})

test('an RPC whose network cannot be told does not block anything', async () => {
  genesis = null
  const e = env()
  const { session } = await connected(e)
  const quote = await worker.fetch(req('/send/prepare', { to: TO, amount: '0.05', token: 'USDC' }, session), e)
  assert.equal(quote.status, 200)
  assert.equal((await quote.json()).cluster, 'devnet')
})
