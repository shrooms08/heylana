import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { deviceOf, scrub, type Env } from '../src/index.ts'
import { resetTally, tallyLimits } from '../src/tally.ts'

/** A device id the app would have generated at install. */
const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'

const SECRETS = {
  ANTHROPIC_API_KEY: 'sk-ant-test-000000000000',
  CARTESIA_API_KEY: 'sk_car_test_11111111111',
  DEEPGRAM_API_KEY: 'dg_test_2222222222222',
  GEMINI_API_KEY: 'AIza_test_3333333333333',
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
  // Every request writes its counters, as before batching: these tests read them back from KV.
  resetTally()
  tallyLimits.everyMs = 0
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

/** Gemini's streamed speech as it arrives: SSE, with base64 PCM in step.delta events. */
function sse(...events: unknown[]): string {
  return events.map((e: any) => `event: ${e.event_type ?? 'message'}\ndata: ${JSON.stringify(e)}\n\n`).join('')
}
const audioDelta = (bytes: number[]) => ({
  event_type: 'step.delta', index: 0,
  delta: { type: 'audio', data: Buffer.from(bytes).toString('base64'), mime_type: 'audio/l16', sample_rate: 24000, channels: 1 },
})

test('by default a spoken answer goes to Gemini TTS in Sulafat, streamed, and comes back as raw PCM', async () => {
  reply = () => new Response(sse(
    { event_type: 'interaction.start' }, audioDelta([1, 2, 3]), audioDelta([4]),
    // The closing event repeats the whole audio; it must not be played twice.
    { event_type: 'interaction.completed', interaction: { steps: [{ type: 'audio', data: Buffer.from([1, 2, 3, 4]).toString('base64') }] } },
  ), { status: 200, headers: { 'content-type': 'text/event-stream' } })
  const response = await worker.fetch(post('/tts', { text: 'The search bar is at the top.' }), env())
  assert.equal(calls[0].url, 'https://generativelanguage.googleapis.com/v1beta/interactions')
  const sent = sentBody(calls[0])
  assert.equal(sent.model, 'gemini-3.1-flash-tts-preview')
  assert.equal(sent.input, 'The search bar is at the top.')
  assert.equal(sent.stream, true)
  assert.deepEqual(sent.response_format, { type: 'audio' })
  assert.deepEqual(sent.generation_config.speech_config, [{ voice: 'Sulafat' }])
  assert.equal((calls[0].init.headers as any)['x-goog-api-key'], SECRETS.GEMINI_API_KEY)
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('content-type'), 'audio/L16')
  assert.equal(response.headers.get('x-sample-rate'), '24000')
  assert.deepEqual([...new Uint8Array(await response.arrayBuffer())], [1, 2, 3, 4])
})

test('Archie is Achird on Gemini, and each call logs its provider and voice', async () => {
  const lines: string[] = []
  const original = console.log
  console.log = (line: string) => lines.push(line)
  try {
    reply = () => new Response(sse(audioDelta([1, 2])), { status: 200 })
    const response = await worker.fetch(post('/tts', { text: 'hello', voice: 'archie' }), env())
    await response.arrayBuffer()
  } finally {
    console.log = original
  }
  assert.deepEqual(sentBody(calls[0]).generation_config.speech_config, [{ voice: 'Achird' }])
  const start = JSON.parse(lines.find((l) => l.includes('"route":"tts"'))!)
  assert.equal(start.provider, 'gemini')
  assert.equal(start.voice, 'Achird')
  assert.ok(!lines.join('\n').includes('hello'))
  const end = JSON.parse(lines.find((l) => l.includes('"route":"tts_end"'))!)
  assert.equal(end.bytes, 2)
})

test('VOICE_PROVIDER=deepgram speaks through Aura in Hera or Aries, with the Deepgram key, streamed as it comes', async () => {
  const lines: string[] = []
  const original = console.log
  console.log = (line: string) => lines.push(line)
  try {
    reply = () => new Response(new Uint8Array([1, 2, 3, 4, 5]), { status: 200, headers: { 'content-type': 'audio/l16;rate=24000' } })
    const response = await worker.fetch(post('/tts', { text: 'The search bar is at the top.' }), { ...env(), VOICE_PROVIDER: 'deepgram' })
    const url = new URL(calls[0].url)
    assert.equal(url.origin + url.pathname, 'https://api.deepgram.com/v1/speak')
    assert.equal(url.searchParams.get('model'), 'aura-2-hera-en')
    assert.equal((calls[0].init.headers as any).authorization, `Token ${SECRETS.DEEPGRAM_API_KEY}`)
    assert.deepEqual(sentBody(calls[0]), { text: 'The search bar is at the top.' })
    assert.equal(response.headers.get('content-type'), 'audio/L16')
    assert.equal(response.headers.get('x-sample-rate'), '24000')
    assert.deepEqual([...new Uint8Array(await response.arrayBuffer())], [1, 2, 3, 4, 5])
    await (await worker.fetch(post('/tts', { text: 'hello', voice: 'archie' }), { ...env(), VOICE_PROVIDER: 'deepgram' })).arrayBuffer()
    assert.equal(new URL(calls[1].url).searchParams.get('model'), 'aura-2-aries-en')
  } finally {
    console.log = original
  }
  const logged = lines.filter((l) => l.includes('"route":"tts"')).map((l) => JSON.parse(l))
  assert.equal(logged[0].provider, 'deepgram')
  assert.equal(logged[0].voice, 'aura-2-hera-en')
  assert.ok(!lines.join('\n').includes('search bar'))
})

test('Deepgram out of quota is a 429 the phone can name, and its key never comes back', async () => {
  reply = () => new Response(JSON.stringify({ err_msg: `limit for ${SECRETS.DEEPGRAM_API_KEY}` }), { status: 429 })
  const response = await worker.fetch(post('/tts', { text: 'hello' }), { ...env(), VOICE_PROVIDER: 'deepgram' })
  assert.equal(response.status, 429)
  const body = await response.text()
  assert.equal(JSON.parse(body).reason, 'quota')
  assert.ok(!body.includes(SECRETS.DEEPGRAM_API_KEY))
})

test('VOICE_PROVIDER=cartesia keeps Cartesia, with the format the phone is told about', async () => {
  reply = () => new Response(new Uint8Array([1, 2, 3]), { status: 200 })
  const response = await worker.fetch(post('/tts', { text: 'The search bar is at the top.' }), { ...env(), VOICE_PROVIDER: 'cartesia' })
  assert.equal(calls[0].url, 'https://api.cartesia.ai/tts/bytes')
  const asked = sentBody(calls[0])
  assert.equal(asked.voice.id, 'db6b0ed5-d5d3-463d-ae85-518a07d3c2b4')
  assert.equal(asked.output_format.encoding, 'pcm_s16le')
  assert.equal(String(asked.output_format.sample_rate), response.headers.get('x-sample-rate'))
  assert.equal((await response.arrayBuffer()).byteLength, 3)
  await worker.fetch(post('/tts', { text: 'hello', voice: 'archie' }), { ...env(), VOICE_PROVIDER: 'cartesia' })
  assert.equal(sentBody(calls[1]).voice.id, 'ef191366-f52f-447a-a398-ed8c0f2943a1')
})

test('a long answer is cut at 400 characters', async () => {
  reply = () => new Response(sse(audioDelta([1])), { status: 200 })
  await worker.fetch(post('/tts', { text: 'a'.repeat(900) }), env())
  assert.equal(sentBody(calls[0]).input.length, 400)
})

test('Gemini out of quota is a 429 the phone can name, and its key never comes back', async () => {
  reply = () => new Response(JSON.stringify({ error: { code: 429, message: `quota for ${SECRETS.GEMINI_API_KEY}` } }), { status: 429 })
  const response = await worker.fetch(post('/tts', { text: 'hello' }), env())
  assert.equal(response.status, 429)
  const body = await response.text()
  assert.equal(JSON.parse(body).reason, 'quota')
  assert.ok(!body.includes(SECRETS.GEMINI_API_KEY))
})

test('without a Gemini key the voice is refused, not attempted', async () => {
  const response = await worker.fetch(post('/tts', { text: 'hello' }), { ...env(), GEMINI_API_KEY: undefined })
  assert.equal(response.status, 503)
  assert.equal(calls.length, 0)
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

test('the third ear: a single-use AssemblyAI token, minted with the key in a header, the key never returned', async () => {
  const AAI = 'aai_test_4444444444444'
  reply = () => new Response(JSON.stringify({ token: 'aai-temp-xyz', expires_in_seconds: 60 }), { status: 200 })
  const response = await worker.fetch(post('/stt-token-aai', {}), { ...env(), ASSEMBLYAI_API_KEY: AAI })
  assert.equal(calls[0].url, 'https://streaming.assemblyai.com/v3/token?expires_in_seconds=60&max_session_duration_seconds=120')
  assert.equal(new Headers(calls[0].init?.headers).get('authorization'), AAI)
  const text = await response.text()
  assert.deepEqual(JSON.parse(text), { key: 'aai-temp-xyz', expires_in: 60 })
  assert.ok(!text.includes(AAI))
})

test('with no AssemblyAI key the third ear sits out, and its refusals are plain', async () => {
  const off = await worker.fetch(post('/stt-token-aai', {}), env())
  assert.deepEqual([off.status, (await off.json()).reason], [503, 'not_set_up'])
  reply = () => new Response('{"error":"Rate limited","code":429}', { status: 429 })
  const busy = await worker.fetch(post('/stt-token-aai', {}), { ...env(), ASSEMBLYAI_API_KEY: 'aai_x' })
  assert.deepEqual([busy.status, (await busy.json()).reason], [429, 'quota'])
  reply = () => new Response('{"error":"bad key aai_x"}', { status: 401 })
  const refused = await worker.fetch(post('/stt-token-aai', {}), { ...env(), ASSEMBLYAI_API_KEY: 'aai_x' })
  const body = await refused.text()
  assert.equal(refused.status, 502)
  assert.ok(!body.includes('aai_x'))
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
  // One record per device a day, a count per route in it.
  const record = JSON.parse(caps.values.get(`cap:${today}:${DEVICE}`)!)
  assert.equal(record.chat, 2)
  assert.equal(record.tts, undefined)
})

test('the 151st question of the day is refused', async () => {
  const today = new Date().toISOString().slice(0, 10)
  const caps = capStore({ [`cap:${today}:${DEVICE}`]: JSON.stringify({ 'chat': 150 }) })
  const response = await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }), env(caps))
  assert.equal(response.status, 429)
  assert.equal((await response.json()).reason, 'daily_cap')
  assert.equal(calls.length, 0)
})

test('speaking has its own allowance', async () => {
  const today = new Date().toISOString().slice(0, 10)
  const caps = capStore({ [`cap:${today}:${DEVICE}`]: JSON.stringify({ 'tts': 150 }) })
  const capped = await worker.fetch(post('/tts', { text: 'hello' }), env(caps))
  assert.equal(capped.status, 429)

  const stillFine = await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }), env(caps))
  assert.equal(stillFine.status, 200)
})

test('ears are allowed twice as often as questions', async () => {
  const today = new Date().toISOString().slice(0, 10)
  const caps = capStore({ [`cap:${today}:${DEVICE}`]: JSON.stringify({ 'stt-token': 299 }) })
  reply = () => new Response(JSON.stringify({ key: 'k' }), { status: 200 })
  assert.equal((await worker.fetch(post('/stt-token', {}), env(caps))).status, 200)
  assert.equal((await worker.fetch(post('/stt-token', {}), env(caps))).status, 429)
})

test('one device running out does not affect another', async () => {
  const today = new Date().toISOString().slice(0, 10)
  const other = '11111111-2222-3333-4444-555555555555'
  const caps = capStore({ [`cap:${today}:${DEVICE}`]: JSON.stringify({ 'chat': 150 }) })
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

test('an upstream error that quotes the key never reaches the phone: brain_unavailable and its status only', async () => {
  reply = () =>
    new Response(JSON.stringify({ error: { message: `bad key ${SECRETS.ANTHROPIC_API_KEY}` } }), { status: 401 })
  const response = await worker.fetch(post('/chat', { mode: 'quick', messages: [1] }), env())
  const text = await response.text()
  assert.equal(response.status, 502)
  assert.ok(!text.includes(SECRETS.ANTHROPIC_API_KEY))
  assert.ok(!text.includes('bad key'))
  assert.equal(JSON.parse(text).upstream_status, 401)
})

test('a thrown error carries no secret either', async () => {
  reply = () => {
    throw new Error(`socket died using ${SECRETS.CARTESIA_API_KEY} and ${SECRETS.GEMINI_API_KEY}`)
  }
  for (const provider of ['cartesia', 'gemini']) {
    const response = await worker.fetch(post('/tts', { text: 'hello' }), { ...env(), VOICE_PROVIDER: provider })
    const text = await response.text()
    assert.equal(response.status, 500)
    assert.equal(JSON.parse(text).reason, 'internal')
    assert.ok(!text.includes(SECRETS.CARTESIA_API_KEY))
    assert.ok(!text.includes(SECRETS.GEMINI_API_KEY))
  }
})

test('scrub leaves ordinary text alone', () => {
  assert.equal(scrub('nothing to hide', env()), 'nothing to hide')
})

test('two requests at once do not knock each other over', async () => {
  // The two ear passes are fetched together at every touch of the disc, and they land in
  // one isolate. The second to start used to null the first's counters out from under it,
  // which threw out of the finally and became a 500 — about a third of all holds.
  const e = { ...env(), ASSEMBLYAI_API_KEY: 'aai_test_key' }
  globalThis.fetch = (async (input: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('https://api.deepgram.com')) {
      // Slow enough that the other request certainly starts while this one is in flight.
      await new Promise((resolve) => setTimeout(resolve, 20))
      return new Response(JSON.stringify({ key: 'dg-temp-key' }), { status: 200 })
    }
    if (url.startsWith('https://streaming.assemblyai.com') || url.includes('assemblyai')) {
      return new Response(JSON.stringify({ token: 'aai-temp-token' }), { status: 200 })
    }
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch

  const touch = () => Promise.all([
    worker.fetch(post('/stt-token', {}), e),
    worker.fetch(post('/stt-token-aai', {}), e),
  ])
  for (let i = 0; i < 5; i++) {
    const [deepgram, assembly] = await touch()
    assert.equal(deepgram.status, 200, `deepgram, touch ${i}`)
    assert.equal(assembly.status, 200, `assemblyai, touch ${i}`)
  }
})
