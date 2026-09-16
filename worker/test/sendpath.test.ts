import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { checkShortAddresses, matchesShort } from '../src/shortaddr.ts'
import type { ToolContext } from '../src/tools.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const RPC = 'https://rpc.test/secret-token-abc'
const ANTHROPIC = 'https://api.anthropic.com/v1/messages'
const SEPT = Date.parse('2026-09-15T12:00:00Z')
const TREASURY = '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv'
const WALLET = '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'
const BONK = 'DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263'
const FRIEND = 'C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH'
const WSOL = 'So11111111111111111111111111111111111111112'

function store() {
  const values = new Map<string, string>()
  return { values, async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } }
}

function env(): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: 'test-session-secret-0123456789', JUDGE_CODE: 'judge', RPC_URL: RPC,
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: TREASURY, USDC_MINT: '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU',
    SKR_MINT: 'replace-me', PRICE_USD: '0.10', PRO_DAYS: '30', CLUSTER: 'devnet',
    CAPS: store(),
  } as Env
}

let script: any[] = []
let modelBodies: any[] = []
let logs: string[] = []
let chain: Record<string, (params: any) => unknown> = {}

beforeEach(() => {
  clock.now = () => SEPT
  script = []
  modelBodies = []
  logs = []
  chain = {}
  console.log = (line: string) => { logs.push(String(line)) }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url === ANTHROPIC) {
      modelBodies.push(JSON.parse(String(init.body)))
      const next = script[Math.min(modelBodies.length - 1, script.length - 1)]
      return new Response(JSON.stringify(next))
    }
    if (url === RPC) {
      const { method, params } = JSON.parse(String(init.body))
      if (!chain[method]) throw new Error(`unexpected rpc ${method}`)
      return new Response(JSON.stringify({ result: chain[method](params) }))
    }
    if (url.startsWith('https://api.jup.ag/price/v3')) return new Response(JSON.stringify({ [WSOL]: { usdPrice: 150 } }))
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch
})

function ask(body: Record<string, unknown>) {
  return new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST',
    headers: { 'X-Heylana-Device': DEVICE },
    body: JSON.stringify({ mode: 'task', system: 'You are Heylana.', messages: [{ role: 'user', content: 'User asks: send 0.05 USDC' }], ...body }),
  })
}

const proposal = (input: unknown) => ({
  stop_reason: 'tool_use',
  content: [{ type: 'tool_use', id: 's1', name: 'propose_send', input }],
  usage: { input_tokens: 400, output_tokens: 30 },
})

// ---------------------------------------------------------------------- send

test('a send question gets one call, the send tool forced, and no words back', async () => {
  script = [proposal({ to: TREASURY, amount: 0.05, token: 'USDC' })]
  const res = await worker.fetch(ask({ tools: true, intent: 'send' }), env())
  assert.equal(res.status, 200)
  assert.equal(modelBodies.length, 1)
  assert.deepEqual(modelBodies[0].tool_choice, { type: 'tool', name: 'propose_send' })
  assert.deepEqual(modelBodies[0].tools.map((t: any) => t.name), ['propose_send'])

  const body = await res.json()
  const answer = JSON.parse(body.content[0].text)
  assert.equal(answer.say, '')
  assert.deepEqual(answer.action, { type: 'send', to: TREASURY, amount: 0.05, token: 'USDC' })
  assert.equal(body.usage.input_tokens, 400)
})

test('the worker log names the send by four characters and its amount', async () => {
  script = [proposal({ to: TREASURY, amount: 0.05, token: 'USDC' })]
  await worker.fetch(ask({ tools: true, intent: 'send' }), env())
  const line = logs.find((l) => l.includes('"route":"chat"'))!
  assert.ok(line.includes('"send_action":{"to":"7c2y","amount":0.05,"token":"USDC"}'))
  assert.equal(line.includes(TREASURY), false)
})

test('everything is an amount of null, and a model that writes nothing down leaves no action', async () => {
  script = [proposal({ to: 'bob.skr', amount: null, token: 'USDC' })]
  const all = JSON.parse((await (await worker.fetch(ask({ tools: true, intent: 'send' }), env())).json()).content[0].text)
  assert.deepEqual(all.action, { type: 'send', to: 'bob.skr', amount: null, token: 'USDC' })

  script = [{ stop_reason: 'end_turn', content: [{ type: 'text', text: 'I will prepare that.' }], usage: { input_tokens: 1, output_tokens: 1 } }]
  modelBodies = []
  const none = JSON.parse((await (await worker.fetch(ask({ tools: true, intent: 'send' }), env())).json()).content[0].text)
  assert.equal(none.action, null)
  assert.equal(none.say, '')
})

// ------------------------------------------------------- shortened addresses

test('shortened addresses are matched by their ends, in either spelling', () => {
  assert.equal(matchesShort('7c2y…SxSv', TREASURY), true)
  assert.equal(matchesShort('7c2y...SxSv', TREASURY), true)
  assert.equal(matchesShort('7c2y…SxSw', TREASURY), false)
  assert.equal(matchesShort('bob.skr', TREASURY), false)
})

test('the treasury on a signing screen is named before the model sees the question', async () => {
  script = [{ stop_reason: 'end_turn', content: [{ type: 'text', text: '{"say":"ok","point_at":null,"task":null}' }], usage: { input_tokens: 1, output_tokens: 1 } }]
  const res = await worker.fetch(ask({ tools: true, signing: { short: ['7c2y…SxSv', 'AAAA…BBBB'], typed: [BONK] } }), env())
  assert.equal(res.status, 200)
  const content = modelBodies[0].messages.at(-1).content as string
  assert.ok(content.endsWith(
    'Address check: 7c2y…SxSv is your Heylana treasury.\nAddress check: AAAA…BBBB cannot be verified from here.',
  ))
})

const context = (over: Partial<ToolContext> = {}): ToolContext => ({
  rpcUrl: RPC, usdcMint: 'mint', skrMint: 'replace-me', cluster: 'devnet', wallet: null, treasury: TREASURY, now: () => SEPT, ...over,
})

test('your own wallet, its token accounts and what you typed are recognised', async () => {
  chain.getTokenAccountsByOwner = ([, filter]: any) =>
    filter.programId === 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
      ? { value: [{ pubkey: FRIEND, account: { data: { parsed: { info: { mint: 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v' } } } } }] }
      : { value: [] }
  const checks = await checkShortAddresses(['9WzD…AWWM', 'C4PR…TUJH', 'DezX…B263'], [BONK], context({ wallet: WALLET }))
  assert.deepEqual(checks.map((c) => c.label), ['your own wallet', 'your own USDC account', 'the address you typed earlier'])
})

test('a recent counterparty is found when nothing closer matches, and remembered for ten minutes', async () => {
  chain.getTokenAccountsByOwner = () => ({ value: [] })
  let transactionsRead = 0
  chain.getSignaturesForAddress = ([, options]: any) => {
    assert.equal(options.limit, 20)
    return [{ signature: 'sig1' }]
  }
  chain.getTransaction = () => {
    transactionsRead++
    return { transaction: { message: { accountKeys: [{ pubkey: WALLET }, { pubkey: BONK }] } }, meta: { preTokenBalances: [], postTokenBalances: [] } }
  }
  const kv = store()
  const first = await checkShortAddresses(['DezX…B263'], [], context({ wallet: WALLET }), kv)
  assert.equal(first[0].label, 'someone you sent to or received from recently')
  await checkShortAddresses(['DezX…B263'], [], context({ wallet: WALLET }), kv)
  assert.equal(transactionsRead, 1, 'the second look used the remembered list')
})

test('two known addresses with the same ends are not a match', async () => {
  const lookalike = '7c2yZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZSxSv'
  const checks = await checkShortAddresses(['7c2y…SxSv'], [lookalike], context())
  assert.equal(checks[0].label, null)
  assert.equal(checks[0].candidates, 2)
})

// -------------------------------------------------------------------- timing

test('each lookup and the total time are on the usage line and in the log', async () => {
  script = [
    { stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't', name: 'get_price', input: { symbol_or_mint: 'SOL' } }], usage: { input_tokens: 1, output_tokens: 1 } },
    { stop_reason: 'end_turn', content: [{ type: 'text', text: '{"say":"150","point_at":null,"task":null}' }], usage: { input_tokens: 1, output_tokens: 1 } },
  ]
  const body = await (await worker.fetch(ask({ tools: true }), env())).json()
  assert.equal(typeof body.usage.tool_ms, 'number')
  assert.match(body.usage.tools, /^get_price:\d+ms$/)
  const line = logs.find((l) => l.includes('"route":"chat"'))!
  assert.ok(line.includes('"tool_timings":["get_price:0ms"]'))
})
