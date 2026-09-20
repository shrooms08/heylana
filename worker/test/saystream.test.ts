import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import {
  FRAME_AUDIO, FRAME_HEADER_BYTES, FRAME_REPLY, FRAME_VOICE_FAILED, SAY_STREAM_TYPE, SayReader,
  frame, readModelStream, replyBody, sentencesIn,
} from '../src/saystream.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const SEPT = Date.parse('2026-09-20T12:00:00Z')

// ------------------------------------------------------------------ the pieces

test('a frame is a kind, four bytes of length, then the payload', () => {
  const out = frame(FRAME_AUDIO, new Uint8Array([9, 9, 9]))
  assert.equal(out[0], FRAME_AUDIO)
  assert.deepEqual([...out.slice(1, 5)], [0, 0, 0, 3])
  assert.deepEqual([...out.slice(FRAME_HEADER_BYTES)], [9, 9, 9])
  // Long enough to need more than one byte of length.
  const big = frame(FRAME_AUDIO, new Uint8Array(70_000))
  assert.deepEqual([...big.slice(1, 5)], [0, 1, 17, 112])
})

test('a sentence ends at a stop with a space after it, never inside a number', () => {
  const one = sentencesIn('Hello there. How are you')
  assert.deepEqual(one.sentences, ['Hello there.'])
  assert.equal(one.rest, ' How are you')

  // 0.05 is one number, not two sentences.
  assert.deepEqual(sentencesIn('Send 0.05 USDC now. Done.').sentences, ['Send 0.05 USDC now.', 'Done.'])
  // "?!" and "..." stay with their sentence.
  assert.deepEqual(sentencesIn('Really?! Yes... okay.').sentences, ['Really?!', 'Yes... okay.'])
  // An ellipsis is a pause, not an end: it stays inside its sentence.
  assert.deepEqual(sentencesIn('Well... maybe not.').sentences, ['Well... maybe not.'])
  // Nothing finished yet.
  assert.deepEqual(sentencesIn('Still writing').sentences, [])
})

test('say is read out of the json as it is written, however it is cut up', () => {
  const reader = new SayReader()
  const said: string[] = []
  for (const piece of ['{"sa', 'y": "Lagos is on the coa', 'st. It never sl', 'eeps.", "point_at": null}']) {
    said.push(...reader.take(piece))
  }
  assert.deepEqual(said, ['Lagos is on the coast.', 'It never sleeps.'])
  assert.equal(reader.flush(), '')
  assert.equal(reader.text, 'Lagos is on the coast. It never sleeps.')
  assert.ok(reader.streamable)
})

test('the last sentence comes out at the end, stop or no stop', () => {
  const reader = new SayReader()
  reader.take('{"say": "No full stop here"')
  assert.equal(reader.flush(), 'No full stop here')
  // And once flushed it is not said a second time.
  assert.equal(reader.flush(), '')
})

test('escapes are decoded, and a quote inside the answer does not end it', () => {
  const reader = new SayReader()
  const said = reader.take('{"say": "She said \\"hi\\" twice. Then \\u00e9 and a line\\nbreak.", "task": null}')
  assert.deepEqual(said, ['She said "hi" twice.', 'Then é and a line\nbreak.'])
})

test('a say split across deltas mid-escape still decodes', () => {
  const reader = new SayReader()
  const said = [...reader.take('{"say": "a \\'), ...reader.take('u00e9 b. ')]
  assert.deepEqual(said, ['a é b.'])
})

test('segments are not spoken early: the phone says them when the whole answer is there', () => {
  const reader = new SayReader()
  const said = reader.take('{"say": [{"text": "Tap Swap.", "point_at": 4}]}')
  assert.deepEqual(said, [])
  assert.equal(reader.streamable, false)
  assert.equal(reader.flush(), '')
})

test('the model stream hands over its text and its usage', async () => {
  const events = [
    'event: message_start',
    'data: {"type":"message_start","message":{"usage":{"input_tokens":120,"cache_read_input_tokens":40}}}',
    '',
    'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"{\\"say\\": \\"Hi."}}',
    'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":" There.\\"}"}}',
    'data: {"type":"message_delta","usage":{"output_tokens":18}}',
    '',
  ].join('\n')
  const body = new Response(events).body!
  const pieces: string[] = []
  const { usage, error } = await readModelStream(body, (text) => { pieces.push(text) })
  assert.equal(error, null)
  assert.equal(usage.input, 120)
  assert.equal(usage.output, 18)
  assert.equal(usage.cacheRead, 40)
  assert.equal(pieces.join(''), '{"say": "Hi. There."}')
})

test('the reply is handed over in the shape the app has always read', () => {
  const body = JSON.parse(replyBody('{"say": "Hi."}', { input: 5, output: 7, cacheRead: 0, cacheWrite: 0 }, 'a-model'))
  assert.equal(body.content[0].text, '{"say": "Hi."}')
  assert.equal(body.usage.input_tokens, 5)
  assert.equal(body.usage.output_tokens, 7)
  assert.equal(body.stop_reason, 'end_turn')
})

// ------------------------------------------------------------------ the route

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
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', DEEPGRAM_API_KEY: 'd-key', CARTESIA_API_KEY: 'c',
    SESSION_SECRET: 'test-session-secret-0123456789', VOICE_PROVIDER: 'deepgram',
    CAPS: kv, ...over,
  } as Env
}

/** What the model writes, one delta per piece. */
let deltas: string[]
let modelStatus: number
let ttsStatus: number
let spokenTexts: string[]

function sse(pieces: string[]): string {
  const lines = ['data: {"type":"message_start","message":{"usage":{"input_tokens":100}}}']
  for (const piece of pieces) {
    lines.push(`data: ${JSON.stringify({ type: 'content_block_delta', delta: { type: 'text_delta', text: piece } })}`)
  }
  lines.push('data: {"type":"message_delta","usage":{"output_tokens":20}}')
  return lines.join('\n') + '\n'
}

beforeEach(() => {
  clock.now = () => SEPT
  deltas = ['{"say": "Lagos is loud. ', 'It is also warm.", "point_at": null, "task": null}']
  modelStatus = 200
  ttsStatus = 200
  spokenTexts = []
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('https://api.anthropic.com')) {
      const asked = JSON.parse(String(init.body))
      assert.equal(asked.stream, true, 'the model is asked to stream')
      if (modelStatus !== 200) return new Response('{"error":{"type":"overloaded"}}', { status: modelStatus })
      return new Response(sse(deltas), { status: 200 })
    }
    if (url.startsWith('https://api.deepgram.com/v1/speak')) {
      spokenTexts.push(JSON.parse(String(init.body)).text)
      if (ttsStatus !== 200) return new Response('no', { status: ttsStatus })
      // Four bytes of "audio" per sentence, so the frames can be told apart.
      return new Response(new Uint8Array([1, 2, 3, 4]), { status: 200 })
    }
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch
})

/** Every frame in the streamed answer, in order. */
async function framesOf(response: Response): Promise<{ kind: number; bytes: Uint8Array }[]> {
  const all = new Uint8Array(await response.arrayBuffer())
  const out: { kind: number; bytes: Uint8Array }[] = []
  let at = 0
  while (at + FRAME_HEADER_BYTES <= all.length) {
    const kind = all[at]
    const length = (all[at + 1] << 24) | (all[at + 2] << 16) | (all[at + 3] << 8) | all[at + 4]
    out.push({ kind, bytes: all.slice(at + FRAME_HEADER_BYTES, at + FRAME_HEADER_BYTES + length) })
    at += FRAME_HEADER_BYTES + length
  }
  return out
}

function ask(body: Record<string, unknown> = {}): Request {
  return new Request('https://w/chat', {
    method: 'POST',
    headers: { 'X-Heylana-Device': DEVICE, 'content-type': 'application/json' },
    body: JSON.stringify({ mode: 'quick', messages: [{ role: 'user', content: 'what is Lagos like' }], speak: true, ...body }),
  })
}

test('a spoken answer streams the audio first and the reply after it', async () => {
  const response = await worker.fetch(ask(), env())
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('content-type'), SAY_STREAM_TYPE)
  const frames = await framesOf(response)

  // Each sentence went to the voice on its own, as soon as it was finished.
  assert.deepEqual(spokenTexts, ['Lagos is loud.', 'It is also warm.'])
  // Audio comes before the reply: that is the whole point of the trip.
  assert.equal(frames[0].kind, FRAME_AUDIO)
  const reply = frames.find((f) => f.kind === FRAME_REPLY)!
  assert.ok(frames.indexOf(reply) > 0)
  assert.equal(frames.filter((f) => f.kind === FRAME_AUDIO).length, 2)
  // Nothing failed, so nothing asks the phone to speak it again.
  assert.equal(frames.some((f) => f.kind === FRAME_VOICE_FAILED), false)

  const body = JSON.parse(new TextDecoder().decode(reply.bytes))
  assert.equal(JSON.parse(body.content[0].text).say, 'Lagos is loud. It is also warm.')
  assert.equal(body.usage.output_tokens, 20)
})

test('nothing spoken leaves the phone to say it the old way', async () => {
  // A segmented say is never spoken early.
  deltas = ['{"say": [{"text": "Tap Swap.", "point_at": 2}], "task": null}']
  const frames = await framesOf(await worker.fetch(ask(), env()))
  assert.deepEqual(spokenTexts, [])
  const failed = frames.find((f) => f.kind === FRAME_VOICE_FAILED)!
  assert.equal(new TextDecoder().decode(failed.bytes), 'not_spoken')
  assert.ok(frames.some((f) => f.kind === FRAME_REPLY))
})

test('a voice that refuses says so once, and the words still come back', async () => {
  ttsStatus = 429
  const frames = await framesOf(await worker.fetch(ask(), env()))
  assert.equal(frames.filter((f) => f.kind === FRAME_AUDIO).length, 0)
  const failed = frames.find((f) => f.kind === FRAME_VOICE_FAILED)!
  assert.equal(new TextDecoder().decode(failed.bytes), '429 quota')
  assert.ok(frames.some((f) => f.kind === FRAME_REPLY))
})

test('a model that refuses is answered in plain json, not a stream', async () => {
  modelStatus = 529
  const response = await worker.fetch(ask(), env())
  assert.equal(response.status, 502)
  assert.equal(response.headers.get('content-type'), 'application/json')
  const body = await response.json() as any
  assert.equal(body.reason, 'brain_unavailable')
  assert.equal(body.upstream_status, 529)
  // Nothing of the model's own message comes back.
  assert.equal(JSON.stringify(body).includes('overloaded'), false)
})

test('speak is ignored where the answer is not a plain one', async () => {
  // A quick action is a forced tool call: no prose, nothing to speak early.
  const response = await worker.fetch(ask({ intent: 'quick_action', tools: false }), env())
  assert.notEqual(response.headers.get('content-type'), SAY_STREAM_TYPE)
})

test('a provider whose audio does not stream is answered the old way', async () => {
  const response = await worker.fetch(ask(), env({ VOICE_PROVIDER: 'cartesia' }))
  assert.notEqual(response.headers.get('content-type'), SAY_STREAM_TYPE)
})
