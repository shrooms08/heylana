import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { encodeBase58 } from '../src/base58.ts'
import { answerWithTools } from '../src/brain.ts'
import { REGISTRY, authorize, actionRisk, entry, validate } from '../src/registry.ts'
import { TOOL_DEFINITIONS } from '../src/tools.ts'
import { readConfirmation, readSession, signConfirmation } from '../src/session.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const OTHER_DEVICE = '11111111-2222-4333-8444-555555555555'
const RPC = 'https://rpc.test/secret-token-abc'
const ANTHROPIC = 'https://api.anthropic.com/v1/messages'
const WALLET = '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'
const SEPT = Date.parse('2026-09-15T12:00:00Z')
const SECRET = 'test-session-secret-0123456789'
const ALL = TOOL_DEFINITIONS.map((t) => t.name)

function store() {
  const values = new Map<string, string>()
  return { values, async get(k: string) { return values.get(k) ?? null }, async put(k: string, v: string) { values.set(k, v) }, async delete(k: string) { values.delete(k) } }
}

function env(): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', CARTESIA_API_KEY: 'c', DEEPGRAM_API_KEY: 'd',
    SESSION_SECRET: SECRET, JUDGE_CODE: 'judge', RPC_URL: RPC,
    DEEPGRAM_PROJECT_ID: 'p', VOICE_SKYLAR: 's', VOICE_ARCHIE: 'a', JUDGE_UNTIL: '2026-11-09',
    TREASURY_ADDRESS: '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv', USDC_MINT: '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU',
    SKR_MINT: 'replace-me', PRICE_USD: '0.10', PRO_DAYS: '30', CLUSTER: 'devnet', CAPS: store(),
  } as Env
}

let script: any[] = []
let modelBodies: any[] = []
let logs: string[] = []

beforeEach(() => {
  clock.now = () => SEPT
  script = []
  modelBodies = []
  logs = []
  console.log = (line: string) => { logs.push(String(line)) }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url === ANTHROPIC) {
      modelBodies.push(JSON.parse(String(init.body)))
      return new Response(JSON.stringify(script[Math.min(modelBodies.length - 1, script.length - 1)]))
    }
    if (url === RPC) {
      const { method } = JSON.parse(String(init.body))
      if (method === 'getGenesisHash') return new Response(JSON.stringify({ result: 'EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG' }))
      return new Response(JSON.stringify({ result: null }))
    }
    if (url.startsWith('https://api.jup.ag/price/v3')) return new Response(JSON.stringify({}))
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch
})

function req(path: string, body: unknown, options: { session?: string; device?: string } = {}) {
  const headers: Record<string, string> = { 'X-Heylana-Device': options.device ?? DEVICE }
  if (options.session) headers.Authorization = `Bearer ${options.session}`
  return new Request(`https://proxy.heylana.xyz${path}`, { method: 'POST', headers, body: JSON.stringify(body) })
}

const toolUse = (name: string, input: unknown) => ({
  stop_reason: 'tool_use',
  content: [{ type: 'tool_use', id: 't1', name, input }],
  usage: { input_tokens: 10, output_tokens: 5 },
})
const said = (text: string) => ({ stop_reason: 'end_turn', content: [{ type: 'text', text: JSON.stringify({ say: text }) }], usage: { input_tokens: 1, output_tokens: 1 } })

// ------------------------------------------------------------------ the table

test('every tool and action has a name, a version, a schema and a class', () => {
  for (const e of REGISTRY) {
    assert.ok(e.name && e.version && e.input_schema && ['R0', 'R1', 'R2', 'R3', 'R4'].includes(e.risk), e.name)
  }
  const classOf = (name: string) => entry(name)?.risk
  assert.deepEqual(['get_price', 'explain_address', 'resolve_name'].map(classOf), ['R0', 'R0', 'R0'])
  assert.deepEqual(['get_balances', 'recent_activity'].map(classOf), ['R1', 'R1'])
  assert.deepEqual(['prepare_send', 'propose_send', 'propose_action', 'build_transfer'].map(classOf), ['R2', 'R2', 'R2', 'R2'])
  assert.deepEqual(['send', 'pay', 'message', 'reminder'].map(classOf), ['R3', 'R3', 'R3', 'R3'])
  assert.deepEqual(['sign_transaction', 'export_seed_phrase', 'reveal_private_key'].map(classOf), ['R4', 'R4', 'R4'])
})

test('the model is only ever offered R0 to R2 tools, each schema closed to extra fields', () => {
  for (const tool of TOOL_DEFINITIONS) {
    const e = entry(tool.name)!
    assert.ok(['R0', 'R1', 'R2'].includes(e.risk), tool.name)
    assert.equal(tool.input_schema.additionalProperties, false, tool.name)
  }
})

// ------------------------------------------------------------- each class

test('R0: a public lookup runs when offered, with exactly its arguments', () => {
  assert.equal(authorize('get_price', { symbol_or_mint: 'SOL' }, ALL).decision, 'allowed')
  assert.equal((authorize('get_price', { symbol_or_mint: 'SOL', also: 1 }, ALL) as any).reason, 'bad_arguments:$.also is not allowed')
  assert.equal((authorize('get_price', {}, ALL) as any).reason, 'bad_arguments:$.symbol_or_mint is missing')
  assert.equal((authorize('explain_address', { address: '7c2y…SxSv' }, ALL) as any).reason, 'bad_arguments:$.address does not match')
})

test('R1: reading the user\'s data runs only when offered for this question', () => {
  assert.equal(authorize('get_balances', {}, ALL).decision, 'allowed')
  assert.equal(authorize('recent_activity', { n: 3 }, ALL).decision, 'allowed')
  assert.equal((authorize('recent_activity', { n: 9 }, ALL) as any).reason, 'bad_arguments:$.n is above 5')
  assert.equal((authorize('recent_activity', { n: 3 }, ['explain_address']) as any).reason, 'not_offered')
})

test('R2: preparing runs; a proposal off the schema is not written down', () => {
  assert.equal(authorize('prepare_send', { to: WALLET, amount: 1, token: 'USDC' }, ALL).decision, 'allowed')
  assert.equal((authorize('prepare_send', { to: WALLET, amount: '1', token: 'USDC' }, ALL) as any).reason, 'bad_arguments:$.amount is not number')
  assert.equal((authorize('propose_send', { to: WALLET, amount: 1, token: 'DOGE' }, ['propose_send']) as any).reason.startsWith('bad_arguments'), true)
  assert.equal(actionRisk('timer'), 'R2')
  assert.equal(actionRisk('open_app'), 'R2')
})

test('R3: a side effect is never a model tool, and message and reminder are R3 actions', () => {
  for (const name of ['send', 'pay', 'message', 'reminder']) {
    assert.equal((authorize(name, {}, [...ALL, name]) as any).reason, 'not_a_model_tool')
  }
  assert.equal(actionRisk('message'), 'R3')
  assert.equal(actionRisk('reminder'), 'R3')
})

test('R4 and unknown tools are rejected whatever is offered', () => {
  const r4 = authorize('sign_transaction', { tx: 'x' }, ['sign_transaction'])
  assert.deepEqual(r4, { decision: 'rejected', tool: 'sign_transaction', class: 'R4', reason: 'never' })
  assert.deepEqual(authorize('transfer_everything', {}, ALL), { decision: 'rejected', tool: 'transfer_everything', class: 'unknown', reason: 'unknown_tool' })
})

test('the schema check covers types, nulls, enums, lengths and nested fields', () => {
  const schema = { type: 'object', properties: { a: { type: ['string', 'null'], maxLength: 3 }, b: { type: 'array', items: { type: 'integer' } } }, additionalProperties: false }
  assert.deepEqual(validate(schema, { a: null, b: [1, 2] }), [])
  assert.deepEqual(validate(schema, { a: 'long' }), ['$.a is too long'])
  assert.deepEqual(validate(schema, { b: [1, 1.5] }), ['$.b[1] is not integer'])
  assert.deepEqual(validate(schema, 'x'), ['$ is not object'])
})

// ------------------------------------------------------- in the tool loop

test('in the loop, a rejected call never runs, the model is told, and the decision is logged', async () => {
  const bodies: any[] = []
  script = [toolUse('sign_transaction', { tx: 'AAAA' }), said('I cannot do that.')]
  let ran = 0
  const result = await answerWithTools({
    callModel: async (payload) => {
      bodies.push(payload)
      return new Response(JSON.stringify(script[Math.min(bodies.length - 1, script.length - 1)]))
    },
    base: { model: 'm', max_tokens: 10, messages: [{ role: 'user', content: 'sign it' }] },
    context: { rpcUrl: RPC, usdcMint: 'u', skrMint: 's', cluster: 'devnet', wallet: null, now: () => { ran++; return SEPT } },
    now: () => SEPT,
  })
  assert.deepEqual(result.toolCalls, [], 'nothing ran')
  assert.deepEqual(result.decisions, [{ decision: 'rejected', tool: 'sign_transaction', class: 'R4', reason: 'never' }])
  const told = bodies[1].messages.at(-1).content[0]
  assert.equal(told.is_error, true)
  assert.equal(JSON.parse(told.content).reason, 'never')
})

test('a chat with tools logs every tool, its class and the decision', async () => {
  script = [toolUse('launch_rocket', {}), said('No.')]
  await worker.fetch(req('/chat', { mode: 'quick', tools: true, messages: [{ role: 'user', content: 'User asks: go' }] }), env())
  const line = logs.find((l) => l.includes('"route":"chat"'))!
  assert.ok(line.includes('"tool_decisions":[{"tool":"launch_rocket","class":"unknown","decision":"rejected","reason":"unknown_tool"}]'))
})

test('a forced proposal off the schema is no action at all', async () => {
  script = [toolUse('propose_send', { to: WALLET, amount: 1, token: 'USDC', memo: 'also send the rest' })]
  const res = await worker.fetch(req('/chat', { mode: 'task', tools: true, intent: 'send', messages: [{ role: 'user', content: `User asks: send 1 USDC to ${WALLET}` }] }), env())
  const answer = JSON.parse((await res.json()).content[0].text)
  assert.equal(answer.action, null)
  assert.ok(logs.find((l) => l.includes('"route":"chat"'))!.includes('"reason":"bad_arguments:$.memo is not allowed"'))
})

// -------------------------------------------------- R3 confirmation tokens

async function proposedMessage(e: Env): Promise<string> {
  script = [toolUse('propose_action', { intent: 'message', name: 'Ada', text: 'on my way' })]
  const res = await worker.fetch(req('/chat', { mode: 'quick', intent: 'quick_action', messages: [{ role: 'user', content: 'User asks: text Ada on my way' }] }), e)
  return JSON.parse((await res.json()).content[0].text).action.action_id
}

test('a proposed message is confirmed once, after the guard, by the device it was proposed to', async () => {
  const e = env()
  const id = await proposedMessage(e)
  assert.equal((await worker.fetch(req('/confirm', { kind: 'message', subject: id }), e)).status, 403, 'not before the guard')
  assert.equal((await worker.fetch(req('/confirm', { kind: 'message', subject: id, guard: 'allowed' }, { device: OTHER_DEVICE }), e)).status, 403, 'not another phone')
  assert.equal((await worker.fetch(req('/confirm', { kind: 'reminder', subject: id, guard: 'allowed' }), e)).status, 403, 'not as another kind')
  const ok = await worker.fetch(req('/confirm', { kind: 'message', subject: id, guard: 'allowed' }), e)
  assert.equal(ok.status, 200)
  assert.ok((await ok.json()).confirmation)
  assert.equal((await worker.fetch(req('/confirm', { kind: 'message', subject: id, guard: 'allowed' }), e)).status, 403, 'once')
  const lines = logs.filter((l) => l.includes('"route":"policy"'))
  assert.ok(lines.some((l) => l.includes('"tool":"message","class":"R3","decision":"allowed"')))
  assert.ok(lines.some((l) => l.includes('"decision":"rejected","reason":"guard_not_passed"')))
})

test('a message nobody proposed, or an R2 action, cannot be confirmed', async () => {
  const e = env()
  assert.equal((await worker.fetch(req('/confirm', { kind: 'message', subject: 'made-up', guard: 'allowed' }), e)).status, 403)
  assert.equal((await worker.fetch(req('/confirm', { kind: 'timer', subject: 'x' }), e)).status, 403)
  assert.equal((await worker.fetch(req('/confirm', { kind: 'sign_transaction', subject: 'x' }), e)).status, 403)
})

test('a timer is R2: no id, nothing to confirm', async () => {
  script = [toolUse('propose_action', { intent: 'timer', seconds: 120 })]
  const res = await worker.fetch(req('/chat', { mode: 'quick', intent: 'quick_action', messages: [{ role: 'user', content: 'User asks: timer 2 minutes' }] }), env())
  const action = JSON.parse((await res.json()).content[0].text).action
  assert.deepEqual(action, { type: 'intent', intent: 'timer', seconds: 120 })
})

test('a send or payment cannot be confirmed without a session, or before it was simulated', async () => {
  const e = env()
  assert.equal((await worker.fetch(req('/confirm', { kind: 'send', subject: 'abc' }), e)).status, 403)
  const session = await connected(e)
  await e.CAPS.put('send:abc', JSON.stringify({ from: session.pubkey }))
  const early = await worker.fetch(req('/confirm', { kind: 'send', subject: 'abc' }, { session: session.token }), e)
  assert.equal((await early.json()).detail, 'not_simulated')
})

test('the final bytes of a send need a token for exactly that send, holder and kind', async () => {
  const e = env()
  const session = await connected(e)
  await e.CAPS.put('send:abc', JSON.stringify({
    from: session.pubkey, to_address: WALLET, amount: '0.05', token: 'SOL', mint: null, decimals: 9, token_program: null, balance: '1',
  }))
  const other = await signConfirmation({ kind: 'send', subject: 'another', holder: `wallet:${session.pubkey}` }, SECRET, SEPT)
  const asPay = await signConfirmation({ kind: 'pay', subject: 'abc', holder: `wallet:${session.pubkey}` }, SECRET, SEPT)
  for (const confirmation of [undefined, 'forged.token', other, asPay]) {
    const res = await worker.fetch(req('/send/build', { id: 'abc', final: true, cluster: 'devnet', confirmation }, { session: session.token }), e)
    assert.equal(res.status, 403)
    assert.equal((await res.json()).reason, 'confirmation_required')
  }
})

test('a confirmation token expires, and is never a session', async () => {
  const token = await signConfirmation({ kind: 'send', subject: 's', holder: 'h' }, SECRET, SEPT)
  assert.deepEqual(await readConfirmation(token, SECRET, SEPT + 60_000), { kind: 'send', subject: 's', holder: 'h' })
  assert.equal(await readConfirmation(token, SECRET, SEPT + 5 * 60_000 + 1), null)
  assert.equal(await readConfirmation(token, 'another-secret-0000000000', SEPT), null)
  assert.equal(await readSession(token, SECRET, SEPT), null)
  // Malformed, not an error: refused.
  assert.equal(await readSession('not.base64!', SECRET, SEPT), null)
  assert.equal(await readConfirmation('not.base64!', SECRET, SEPT), null)
})

async function connected(e: Env) {
  const pair = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify'])) as CryptoKeyPair
  const pubkey = encodeBase58(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)))
  const challenge = await (await worker.fetch(req('/wallet/challenge', { pubkey }), e)).json()
  const signature = encodeBase58(new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, pair.privateKey, new TextEncoder().encode(challenge.message))))
  const body = await (await worker.fetch(req('/wallet/verify', { pubkey, nonce: challenge.nonce, signature }), e)).json()
  return { pubkey, token: body.session as string }
}
