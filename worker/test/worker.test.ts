import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { deviceOf, scrub, type Env } from '../src/index.ts'

/** A device id the app would have generated at install. */
const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'

const SECRETS = {
  ANTHROPIC_API_KEY: 'sk-ant-test-000000000000',
  CARTESIA_API_KEY: 'sk_car_test_11111111111',
  DEEPGRAM_API_KEY: 'dg_test_2222222222222',
}

/** Workers KV, small enough to keep in a Map. */
function capStore(seed: Record<string, string> = {}) {
  const values = new Map(Object.entries(seed))
  return {
    values,
    async get(key: string) {
      return values.get(key) ?? null
    },
    async put(key: string, value: string) {
      values.set(key, value)
    },
    async delete(key: string) {
      values.delete(key)
    },
  }
}

function env(caps = capStore()): Env {
  return {
    ...SECRETS,
    DEEPGRAM_PROJECT_ID: 'project-abc',
    VOICE_SKYLAR: 'db6b0ed5-d5d3-463d-ae85-518a07d3c2b4',
    VOICE_ARCHIE: 'ef191366-f52f-447a-a398-ed8c0f2943a1',
    CAPS: caps,
  }
}

type Call = { url: string; init: RequestInit }
let calls: Call[] = []
let reply: (url: string) => Response

beforeEach(() => {
  calls = []
  reply = () => new Response(JSON.stringify({ ok: true }), { status: 200 })
  // Nothing in these tests ever reaches a real service.
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    calls.push({ url, init: init ?? {} })
    return reply(url)
  }) as typeof fetch
})

function post(path: string, body: unknown, headers: Record<string, string> = { 'X-Heylana-Device': DEVICE }) {
  return new Request(`https://proxy.heylana.xyz${path}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  })
}

function sentBody(call: Call): any {
  return JSON.parse(String(call.init.body))
}

// ------------------------------------------------------------------ routing

test('a quick question goes to Haiku', async () => {
  await worker.fetch(post('/chat', { mode: 'quick', messages: [{ role: 'user', content: 'hi' }] }), env())
  assert.equal(calls[0].url, 'https://api.anthropic.com/v1/messages')
  assert.equal(sentBody(calls[0]).model, 'claude-haiku-4-5-20251001')
})

test('a task step goes to Sonnet', async () => {
  await worker.fetch(post('/chat', { mode: 'task', messages: [{ role: 'user', content: 'hi' }] }), env())
  assert.equal(sentBody(calls[0]).model, 'claude-sonnet-5')
})

test('the app cannot name a model of its own', async () => {
  await worker.fetch(
    post('/chat', { mode: 'quick', model: 'claude-opus-5', messages: [{ role: 'user', content: 'hi' }] }),
    env(),
  )
  assert.equal(sentBody(calls[0]).model, 'claude-haiku-4-5-20251001')
})

test('an unknown mode is refused before anything is spent', async () => {
  const response = await worker.fetch(post('/chat', { mode: 'cheap', messages: [{ role: 'user', content: 'hi' }] }), env())
  assert.equal(response.status, 400)
  assert.equal((await response.json()).reason, 'bad_mode')
  assert.equal(calls.length, 0)
})

test('the key is added by the worker, and the app never sees it', async () => {
  await worker.fetch(post('/chat', { mode: 'quick', messages: [{ role: 'user', content: 'hi' }] }), env())
  const headers = calls[0].init.headers as Record<string, string>
  assert.equal(headers['x-api-key'], SECRETS.ANTHROPIC_API_KEY)
  assert.equal(headers['anthropic-version'], '2023-06-01')
})

test('max_tokens is clamped, whatever the app asks for', async () => {
  await worker.fetch(post('/chat', { mode: 'quick', max_tokens: 99999, messages: [{ role: 'user', content: 'hi' }] }), env())
  assert.equal(sentBody(calls[0]).max_tokens, 1024)
})

// ---------------------------------------------------------------- the voice

test('the audio format the phone is told about is the one that was asked for', async () => {
  reply = () => new Response(new Uint8Array([1]), { status: 200 })
  const response = await worker.fetch(post('/tts', { text: 'hello' }), env())
  const asked = sentBody(calls[0]).output_format
  assert.equal(asked.container, 'raw')
  assert.equal(asked.encoding, 'pcm_s16le')
  assert.equal(String(asked.sample_rate), response.headers.get('x-sample-rate'))
})

test('a spoken answer goes to Cartesia in Skylar by default', async () => {
  reply = () => new Response(new Uint8Array([1, 2, 3]), { status: 200 })
  const response = await worker.fetch(post('/tts', { text: 'The search bar is at the top.' }), env())
  assert.equal(calls[0].url, 'https://api.cartesia.ai/tts/bytes')
  assert.equal(sentBody(calls[0]).voice.id, 'db6b0ed5-d5d3-463d-ae85-518a07d3c2b4')
  assert.equal(response.headers.get('x-sample-rate'), '24000')
  assert.equal((await response.arrayBuffer()).byteLength, 3)
})

test('Archie is a different voice id', async () => {
  reply = () => new Response(new Uint8Array([1]), { status: 200 })
  await worker.fetch(post('/tts', { text: 'hello', voice: 'archie' }), env())
  assert.equal(sentBody(calls[0]).voice.id, 'ef191366-f52f-447a-a398-ed8c0f2943a1')
})

test('a long answer is cut at 400 characters', async () => {
  reply = () => new Response(new Uint8Array([1]), { status: 200 })
  await worker.fetch(post('/tts', { text: 'a'.repeat(900) }), env())
  assert.equal(sentBody(calls[0]).transcript.length, 400)
})

test('nothing to say is not a request', async () => {
  const response = await worker.fetch(post('/tts', { text: '   ' }), env())
  assert.equal(response.status, 400)
  assert.equal(calls.length, 0)
})

// ----------------------------------------------------------------- the ears

test('borrowed ears last two minutes and are minted from the project key', async () => {
  reply = () => new Response(JSON.stringify({ key: 'temp-key-abc', api_key_id: 'x' }), { status: 200 })
  const response = await worker.fetch(post('/stt-token', {}), env())
  assert.equal(calls[0].url, 'https://api.deepgram.com/v1/projects/project-abc/keys')
  assert.equal(sentBody(calls[0]).time_to_live_in_seconds, 120)
  assert.deepEqual(sentBody(calls[0]).scopes, ['usage:write'])

  const body = await response.json()
  assert.equal(body.key, 'temp-key-abc')
  assert.equal(body.expires_in, 120)
})

test('the project key itself never leaves', async () => {
  reply = () => new Response(JSON.stringify({ key: 'temp-key-abc' }), { status: 200 })
  const response = await worker.fetch(post('/stt-token', {}), env())
  const text = await response.text()
  assert.ok(!text.includes(SECRETS.DEEPGRAM_API_KEY))
})

// ---------------------------------------------------------------- the rules

test('a request with no device is refused', async () => {
  const response = await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }, {}), env())
  assert.equal(response.status, 400)
  assert.equal((await response.json()).reason, 'no_device')
  assert.equal(calls.length, 0)
})

test('a device id that is not one is refused', async () => {
  const response = await worker.fetch(
    post('/chat', { mode: 'quick', messages: [1] }, { 'X-Heylana-Device': 'not a device' }),
    env(),
  )
  assert.equal(response.status, 400)
  assert.equal(calls.length, 0)
})

test('deviceOf accepts a uuid and nothing else', () => {
  assert.equal(deviceOf(post('/chat', {}, { 'X-Heylana-Device': DEVICE })), DEVICE)
  assert.equal(deviceOf(post('/chat', {}, { 'X-Heylana-Device': 'short' })), null)
  assert.equal(deviceOf(post('/chat', {})), DEVICE)
})

test('only POST is answered', async () => {
  const response = await worker.fetch(
    new Request('https://proxy.heylana.xyz/chat', { method: 'GET' }),
    env(),
  )
  assert.equal(response.status, 405)
})

test('an unknown route is a 404, not a proxy', async () => {
  const response = await worker.fetch(post('/anything', {}), env())
  assert.equal(response.status, 404)
  assert.equal(calls.length, 0)
})

// ----------------------------------------------------------------- the caps

test('the day is counted per device and per route', async () => {
  const caps = capStore()
  const today = new Date().toISOString().slice(0, 10)
  await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }), env(caps))
  await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }), env(caps))
  assert.equal(caps.values.get(`cap:${today}:${DEVICE}:chat`), '2')
  assert.equal(caps.values.get(`cap:${today}:${DEVICE}:tts`), undefined)
})

test('the 151st question of the day is refused', async () => {
  const today = new Date().toISOString().slice(0, 10)
  const caps = capStore({ [`cap:${today}:${DEVICE}:chat`]: '150' })
  const response = await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }), env(caps))
  assert.equal(response.status, 429)
  assert.equal((await response.json()).reason, 'daily_cap')
  assert.equal(calls.length, 0)
})

test('speaking has its own allowance', async () => {
  const today = new Date().toISOString().slice(0, 10)
  const caps = capStore({ [`cap:${today}:${DEVICE}:tts`]: '150' })
  const capped = await worker.fetch(post('/tts', { text: 'hello' }), env(caps))
  assert.equal(capped.status, 429)

  const stillFine = await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }), env(caps))
  assert.equal(stillFine.status, 200)
})

test('ears are allowed twice as often as questions', async () => {
  const today = new Date().toISOString().slice(0, 10)
  const caps = capStore({ [`cap:${today}:${DEVICE}:stt-token`]: '299' })
  reply = () => new Response(JSON.stringify({ key: 'k' }), { status: 200 })
  assert.equal((await worker.fetch(post('/stt-token', {}), env(caps))).status, 200)
  assert.equal((await worker.fetch(post('/stt-token', {}), env(caps))).status, 429)
})

test('one device running out does not affect another', async () => {
  const today = new Date().toISOString().slice(0, 10)
  const other = '11111111-2222-3333-4444-555555555555'
  const caps = capStore({ [`cap:${today}:${DEVICE}:chat`]: '150' })
  const response = await worker.fetch(
    post('/chat', { mode: 'quick', messages: [1] }, { 'X-Heylana-Device': other }),
    env(caps),
  )
  assert.equal(response.status, 200)
})

test('a Deepgram key that cannot mint keys is named, not passed through as noise', async () => {
  reply = () =>
    new Response(
      JSON.stringify({
        category: 'INSUFFICIENT_PERMISSIONS',
        message: 'Your account does not have the required scope to perform that action.',
        details: "Check that your account has the 'keys:write' scope for this project.",
      }),
      { status: 403 },
    )
  const response = await worker.fetch(post('/stt-token', {}), env())
  const body = await response.json()
  assert.equal(response.status, 502)
  assert.equal(body.reason, 'deepgram_scope')
  assert.ok(body.detail.includes('keys:write'))
})

// --------------------------------------------------------------- the errors

test('an upstream error that quotes the key comes back with it removed', async () => {
  reply = () =>
    new Response(JSON.stringify({ error: { message: `bad key ${SECRETS.ANTHROPIC_API_KEY}` } }), { status: 401 })
  const response = await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }), env())
  const text = await response.text()
  assert.equal(response.status, 401)
  assert.ok(!text.includes(SECRETS.ANTHROPIC_API_KEY))
  assert.ok(text.includes('***'))
})

test('a thrown error carries no secret either', async () => {
  reply = () => {
    throw new Error(`socket died using ${SECRETS.CARTESIA_API_KEY}`)
  }
  const response = await worker.fetch(post('/tts', { text: 'hello' }), env())
  const text = await response.text()
  assert.equal(response.status, 502)
  assert.ok(!text.includes(SECRETS.CARTESIA_API_KEY))
})

test('scrub leaves ordinary text alone', () => {
  assert.equal(scrub('nothing to hide', env()), 'nothing to hide')
})
