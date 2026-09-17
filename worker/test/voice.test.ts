import { test } from 'node:test'
import assert from 'node:assert/strict'
import { audioOfEvent, geminiPcmStream, geminiVoiceFor, providerOf, wavDataOffset, type StreamStats } from '../src/voice.ts'
import { DAILY_CAP_FREE_CHAT, PAID_DAILY_CEILING, dailyCapFor } from '../src/index.ts'

const b64 = (bytes: number[]) => Buffer.from(bytes).toString('base64')
const delta = (bytes: number[]) =>
  `event: step.delta\ndata: ${JSON.stringify({ event_type: 'step.delta', delta: { type: 'audio', data: b64(bytes) } })}\n\n`

async function pcmOf(chunks: string[]): Promise<{ bytes: number[]; stats: StreamStats }> {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      for (const chunk of chunks) c.enqueue(encoder.encode(chunk))
      c.close()
    },
  })
  let stats!: StreamStats
  const out = geminiPcmStream(body, (s) => (stats = s))
  const bytes = [...new Uint8Array(await new Response(out).arrayBuffer())]
  return { bytes, stats }
}

test('the provider is gemini unless it says cartesia', () => {
  assert.equal(providerOf(undefined), 'gemini')
  assert.equal(providerOf(''), 'gemini')
  assert.equal(providerOf('gemini'), 'gemini')
  assert.equal(providerOf(' Cartesia '), 'cartesia')
  assert.equal(providerOf('elevenlabs'), 'gemini')
  assert.equal(geminiVoiceFor('skylar'), 'Sulafat')
  assert.equal(geminiVoiceFor('archie'), 'Achird')
  assert.equal(geminiVoiceFor('phone'), 'Sulafat')
})

test('events split anywhere across network chunks come out as the same PCM, in order', async () => {
  const whole = delta([1, 2, 3]) + delta([4, 5]) + delta([6])
  const expected = [1, 2, 3, 4, 5, 6]
  for (const size of [1, 2, 7, 13, whole.length]) {
    const chunks: string[] = []
    for (let i = 0; i < whole.length; i += size) chunks.push(whole.slice(i, i + size))
    const { bytes, stats } = await pcmOf(chunks)
    assert.deepEqual(bytes, expected, `chunk size ${size}`)
    assert.equal(stats.audioEvents, 3)
    assert.equal(stats.bytes, 6)
  }
})

test('an odd byte at a chunk edge is passed on as is: the phone frames whole samples', async () => {
  const { bytes } = await pcmOf([delta([1, 2, 3]), delta([4])])
  assert.equal(bytes.length % 2, 0)
  assert.deepEqual(bytes, [1, 2, 3, 4])
})

test('only step deltas are audio: not the completed event, not text, not errors', async () => {
  const completed = `data: ${JSON.stringify({ event_type: 'interaction.completed', delta: { type: 'audio', data: b64([9, 9]) } })}\n\n`
  const text = `data: ${JSON.stringify({ event_type: 'step.delta', delta: { type: 'text', text: 'hi' } })}\n\n`
  const error = `data: ${JSON.stringify({ event_type: 'error', error: { code: 500, message: 'x' } })}\n\n`
  const { bytes, stats } = await pcmOf([delta([1, 2]), completed, text, 'data: not json\n\n', error])
  assert.deepEqual(bytes, [1, 2])
  assert.equal(stats.error, '500')
  assert.equal(audioOfEvent({ delta: { type: 'audio', data: '' } }), null)
})

test('a WAV header on a chunk is stripped to its samples', () => {
  const header = [...Buffer.from('RIFF'), 36, 0, 0, 0, ...Buffer.from('WAVE'), ...Buffer.from('fmt '), 16, 0, 0, 0,
    ...new Array(16).fill(0), ...Buffer.from('data'), 4, 0, 0, 0]
  const wav = new Uint8Array([...header, 7, 8, 9, 10])
  assert.equal(wavDataOffset(wav), 44)
  assert.deepEqual([...audioOfEvent({ event_type: 'step.delta', delta: { type: 'audio', data: Buffer.from(wav).toString('base64') } })!], [7, 8, 9, 10])
  assert.equal(wavDataOffset(new Uint8Array([1, 2, 3, 4])), 0)
})

test('questions and spoken answers are capped per day on Free only; Pro and Judge get the ceiling', () => {
  assert.equal(dailyCapFor('chat', 'free'), DAILY_CAP_FREE_CHAT)
  assert.equal(dailyCapFor('tts', 'free'), 150)
  for (const plan of ['pro', 'judge'] as const) {
    assert.equal(dailyCapFor('chat', plan), PAID_DAILY_CEILING)
    assert.equal(dailyCapFor('tts', plan), PAID_DAILY_CEILING)
  }
  assert.equal(PAID_DAILY_CEILING, 2000)
  // Everything else is the same whatever the plan.
  assert.equal(dailyCapFor('stt-token', 'judge'), dailyCapFor('stt-token', 'free'))
})
