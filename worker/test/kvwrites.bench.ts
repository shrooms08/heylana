/**
 * How many KV writes a hundred requests cost.
 *
 * Cloudflare's free KV allows 1,000 writes a day, and on Sunday 20 September the worker ran
 * out of them mid-afternoon. This replays the requests a phone actually makes — for every
 * question: an ear pass from Deepgram, one from AssemblyAI, the question, and its spoken
 * answer — against the real worker with every upstream faked, and counts what reaches KV.
 *
 * It uses nothing but the worker's front door, so it runs unchanged against an older copy
 * of the worker: that is how the "before" figure was taken.
 *
 *     node test/kvwrites.bench.ts
 */
import worker, { clock, type Env } from '../src/index.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'

export interface Counted {
  requests: number
  writes: number
  reads: number
  statuses: Record<number, number>
  span_s: number
}

function countingKv() {
  const values = new Map<string, string>()
  const counted = { writes: 0, reads: 0 }
  return {
    counted,
    values,
    async get(key: string) {
      counted.reads++
      return values.get(key) ?? null
    },
    async put(key: string, value: string) {
      counted.writes++
      values.set(key, value)
    },
    async delete(key: string) {
      values.delete(key)
    },
  }
}

/** Every upstream the four routes reach, answering the way a working one would. */
function fakeUpstreams() {
  globalThis.fetch = (async (input: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('https://api.anthropic.com/')) {
      return new Response(JSON.stringify({
        type: 'message', role: 'assistant', stop_reason: 'end_turn',
        content: [{ type: 'text', text: '{"say":"Here you go.","point_at":null,"task":null}' }],
        usage: { input_tokens: 900, output_tokens: 30 },
      }))
    }
    if (url.startsWith('https://api.deepgram.com/v1/speak')) return new Response(new Uint8Array(4800))
    if (url.includes('assemblyai')) return new Response(JSON.stringify({ token: 'aai-token' }))
    if (url.includes('deepgram')) return new Response(JSON.stringify({ key: 'dg-key', access_token: 'dg-key', expires_in: 120 }))
    return new Response('{}', { status: 404 })
  }) as typeof fetch
}

function env(caps: ReturnType<typeof countingKv>): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000',
    DEEPGRAM_API_KEY: 'dg_test_2222222222222',
    DEEPGRAM_PROJECT_ID: 'project-abc',
    ASSEMBLYAI_API_KEY: 'aai_test_4444444444444',
    VOICE_PROVIDER: 'deepgram',
    SESSION_SECRET: 'test-session-secret-0123456789',
    CAPS: caps,
  } as unknown as Env
}

const post = (path: string, body: unknown) => new Request(`https://proxy.heylana.xyz/${path}`, {
  method: 'POST', headers: { 'X-Heylana-Device': DEVICE }, body: JSON.stringify(body),
})

/**
 * [questions] questions, one every [everySeconds], each four requests a second or two apart:
 * the two ear passes at the touch of the disc, the question at the release, the voice after.
 */
export async function replay(questions: number, everySeconds: number): Promise<Counted> {
  // Batched counters (when this copy of the worker has them) start empty for each replay.
  const batched = await import('../src/tally.ts').catch(() => null)
  batched?.resetTally()
  fakeUpstreams()
  const kv = countingKv()
  const statuses: Record<number, number> = {}
  const start = Date.parse('2026-09-20T13:00:00Z')
  let t = start
  clock.now = () => t
  let requests = 0
  for (let q = 0; q < questions; q++) {
    t = start + q * everySeconds * 1000
    const steps: [number, Request][] = [
      [0, post('stt-token', {})],
      [0, post('stt-token-aai', {})],
      [3, post('chat', { mode: 'quick', max_tokens: 300, messages: [{ role: 'user', content: 'What is a slot?' }] })],
      [6, post('tts', { text: 'Here you go.' })],
    ]
    for (const [after, request] of steps) {
      t = start + q * everySeconds * 1000 + after * 1000
      const response = await worker.fetch(request, env(kv))
      await response.arrayBuffer().catch(() => {})
      statuses[response.status] = (statuses[response.status] ?? 0) + 1
      requests++
    }
  }
  // Whatever is still pending is written in the end — counted here, so the tail is not free.
  if (batched) await batched.tally.flush(kv, () => {})
  return { requests, writes: kv.counted.writes, reads: kv.counted.reads, statuses, span_s: (t - start) / 1000 }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  // Quiet: the worker logs a line per request.
  console.log = () => {}
  const out = (label: string, c: Counted) =>
    process.stdout.write(`${label.padEnd(34)} requests=${c.requests} writes=${c.writes} per100=${Math.round((c.writes * 100) / c.requests)} reads=${c.reads} span=${c.span_s}s statuses=${JSON.stringify(c.statuses)}\n`)
  out('busy: a question every 20s', await replay(25, 20))
  out('steady: a question every 2 min', await replay(25, 120))
  out('sparse: a question every 10 min', await replay(25, 600))
}
