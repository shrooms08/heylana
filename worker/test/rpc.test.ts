import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { isRetryableRpcError, makeRpc, providersFor, RPC_TIMEOUT_MS, type RpcEnv } from '../src/rpc.ts'

const FAST = 'https://rpcfast.test/key-fast-111'
const HELIUS = 'https://helius.test/key-helius-222'
const DEVNET = 'https://devnet.test/key-devnet-333'

const MAINNET: RpcEnv = { RPCFAST_URL: FAST, MAINNET_RPC_URL: HELIUS, RPC_URL: DEVNET, CLUSTER: 'mainnet-beta' }
const DEVNET_ENV: RpcEnv = { ...MAINNET, CLUSTER: 'devnet' }

/** What each address answers: a result, a JSON-RPC error, an HTTP status, or a hang. */
let answers: Record<string, (method: string, signal?: AbortSignal) => Promise<Response> | Response>
let asked: string[]

/** A clock the tests move by hand, so a "slow" provider costs no real time. */
let ms: number
const now = () => ms

beforeEach(() => {
  asked = []
  ms = 0
  answers = {}
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    const { method } = JSON.parse(String(init.body))
    asked.push(url)
    const reply = answers[url]
    if (!reply) throw new Error('nothing listening')
    return reply(method, init?.signal)
  }) as typeof fetch
})

const ok = (result: unknown) => () => new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, result }))
const rpcError = (code: number, message = 'no') => () => new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code, message } }))
const httpError = (status: number) => () => new Response('nope', { status })
/** Never answers: the caller's own timeout aborts it, exactly as a real fetch would end. */
const hangs = () => (_method: string, signal?: AbortSignal) =>
  new Promise<Response>((_resolve, reject) => {
    // The clock moves as the caller waits, so the recorded milliseconds are the wait.
    ms += RPC_TIMEOUT_MS + 1
    signal?.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })))
  })

test('the primary answers: one call, named by provider, no fallback', async () => {
  answers[FAST] = ok(7)
  const rpc = makeRpc(MAINNET, { now })
  assert.equal(await rpc('getBalance', ['w']), 7)
  assert.deepEqual(asked, [FAST])
  assert.deepEqual(rpc.calls, [{ method: 'getBalance', provider: 'rpcfast', ms: 0, outcome: 'ok' }])
})

test('RPC_PRIMARY picks which mainnet provider goes first', async () => {
  assert.deepEqual(providersFor(MAINNET, 'mainnet-beta').map((p) => p.name), ['rpcfast', 'helius'])
  assert.deepEqual(providersFor({ ...MAINNET, RPC_PRIMARY: 'helius' }, 'mainnet-beta').map((p) => p.name), ['helius', 'rpcfast'])
  answers[HELIUS] = ok('first')
  const rpc = makeRpc({ ...MAINNET, RPC_PRIMARY: 'helius' }, { now })
  assert.equal(await rpc('getSlot'), 'first')
  assert.deepEqual(asked, [HELIUS])
})

test('a primary that hangs past the limit falls back, and both calls are recorded', async () => {
  answers[FAST] = hangs()
  answers[HELIUS] = ok('from helius')
  const rpc = makeRpc(MAINNET, { now, timeoutMs: 20 })
  assert.equal(await rpc('getLatestBlockhash'), 'from helius')
  assert.deepEqual(asked, [FAST, HELIUS])
  assert.deepEqual(rpc.calls.map((c) => [c.provider, c.outcome]), [['rpcfast', 'failed'], ['helius', 'fallback']])
  assert.equal(rpc.totalMs, rpc.calls[0].ms + rpc.calls[1].ms)
})

test('an HTTP error falls back too; both failing is a plain error', async () => {
  answers[FAST] = httpError(502)
  answers[HELIUS] = ok('second')
  const rpc = makeRpc(MAINNET, { now })
  assert.equal(await rpc('getBalance'), 'second')

  asked = []
  answers[HELIUS] = httpError(500)
  const both = makeRpc(MAINNET, { now })
  await assert.rejects(both('getBalance'), /rpc getBalance: http 500/)
  assert.deepEqual(asked, [FAST, HELIUS])
  assert.deepEqual(both.calls.map((c) => c.outcome), ['failed', 'failed'])
})

test('a retryable JSON-RPC error is tried on the other; one about the request itself is not', async () => {
  answers[FAST] = rpcError(-32005, 'node is behind by 40 slots')
  answers[HELIUS] = ok('caught up')
  const behind = makeRpc(MAINNET, { now })
  assert.equal(await behind('getSignatureStatuses'), 'caught up')

  asked = []
  answers[FAST] = rpcError(-32602, 'invalid params')
  const bad = makeRpc(MAINNET, { now })
  await assert.rejects(bad('getAccountInfo'), /rpc getAccountInfo: -32602/)
  assert.deepEqual(asked, [FAST], 'the same request fails the same way on both, so it is asked once')

  assert.equal(isRetryableRpcError({ code: -32005 }), true)
  assert.equal(isRetryableRpcError({ code: -32603 }), true)
  assert.equal(isRetryableRpcError({ code: -32000, message: 'Too Many Requests' }), true)
  assert.equal(isRetryableRpcError({ code: -32602, message: 'invalid params' }), false)
  assert.equal(isRetryableRpcError({ code: -32002, message: 'Transaction simulation failed' }), false)
})

test('devnet uses RPC_URL alone, whatever RPC_PRIMARY says, and never falls back', async () => {
  assert.deepEqual(providersFor(DEVNET_ENV, 'devnet').map((p) => p.name), ['devnet'])
  assert.deepEqual(providersFor({ ...DEVNET_ENV, RPC_PRIMARY: 'helius' }, 'devnet').map((p) => p.name), ['devnet'])
  answers[DEVNET] = httpError(503)
  const rpc = makeRpc(DEVNET_ENV, { now })
  await assert.rejects(rpc('getBalance'), /http 503/)
  assert.deepEqual(asked, [DEVNET], 'a devnet worker never reaches for a mainnet provider')
  assert.deepEqual(rpc.calls.map((c) => [c.provider, c.outcome]), [['devnet', 'failed']])
})

test('names ask mainnet even while the worker is on devnet; without one it says so', async () => {
  answers[FAST] = ok('mainnet answer')
  const rpc = makeRpc(DEVNET_ENV, { now })
  assert.equal(await rpc('getAccountInfo', ['x'], { cluster: 'mainnet-beta' }), 'mainnet answer')
  assert.deepEqual(asked, [FAST])
  assert.equal(rpc.has('mainnet-beta'), true)

  const alone = makeRpc({ RPC_URL: DEVNET, CLUSTER: 'devnet' }, { now })
  assert.equal(alone.has('mainnet-beta'), false)
  await assert.rejects(alone('getAccountInfo', ['x'], { cluster: 'mainnet-beta' }), /no provider/)
})

test('every call is handed to onCall as it finishes, for the log', async () => {
  const seen: string[] = []
  answers[FAST] = rpcError(-32005)
  answers[HELIUS] = ok(1)
  const rpc = makeRpc(MAINNET, { now, onCall: (c) => seen.push(`${c.method}/${c.provider}/${c.outcome}`) })
  await rpc('getBalance')
  assert.deepEqual(seen, ['getBalance/rpcfast/failed', 'getBalance/helius/fallback'])
})
