import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import { flushIfDue, resetTally, tally, tallyLimits } from '../src/tally.ts'
import { replay } from './kvwrites.bench.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const T0 = Date.parse('2026-09-20T13:00:00Z')
let t = T0
let logs: any[] = []

/** KV that works, or refuses reads or writes the way it did on Sunday afternoon. */
function kv(seed: Record<string, string> = {}, fail: { put?: boolean; get?: string } = {}) {
  const values = new Map(Object.entries(seed))
  const counted = { writes: 0 }
  return {
    values,
    counted,
    async get(key: string) {
      if (fail.get && key.startsWith(fail.get)) throw new Error('KV GET failed: 429 Too Many Requests')
      return values.get(key) ?? null
    },
    async put(key: string, value: string) {
      if (fail.put) throw new Error('KV PUT failed: 429 Too Many Requests')
      counted.writes++
      values.set(key, value)
    },
    async delete(key: string) {
      values.delete(key)
    },
  }
}

function env(caps: ReturnType<typeof kv>): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000',
    DEEPGRAM_API_KEY: 'dg_test_2222222222222',
    DEEPGRAM_PROJECT_ID: 'project-abc',
    VOICE_PROVIDER: 'deepgram',
    SESSION_SECRET: 'test-session-secret-0123456789',
    CAPS: caps,
  } as unknown as Env
}

const ask = () => new Request('https://proxy.heylana.xyz/chat', {
  method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
  body: JSON.stringify({ mode: 'quick', messages: [{ role: 'user', content: 'What is a slot?' }] }),
})
const post = (path: string, body: unknown) => new Request(`https://proxy.heylana.xyz/${path}`, {
  method: 'POST', headers: { 'X-Heylana-Device': DEVICE }, body: JSON.stringify(body),
})

beforeEach(() => {
  resetTally()
  tallyLimits.everyMs = 60_000
  t = T0
  clock.now = () => t
  logs = []
  console.log = (line: string) => { try { logs.push(JSON.parse(String(line))) } catch { /* not ours */ } }
  globalThis.fetch = (async (input: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('https://api.anthropic.com/')) {
      return new Response(JSON.stringify({
        type: 'message', role: 'assistant', stop_reason: 'end_turn',
        content: [{ type: 'text', text: '{"say":"About 300 milliseconds.","point_at":null,"task":null}' }],
        usage: { input_tokens: 900, output_tokens: 30 },
      }))
    }
    if (url.startsWith('https://api.deepgram.com/v1/speak')) return new Response(new Uint8Array(4800))
    return new Response(JSON.stringify({ key: 'dg-key', access_token: 'dg-key', expires_in: 120 }))
  }) as typeof fetch
})

// ------------------------------------------------------- accounting never breaks a request

test('a KV that refuses every write still gets the answer back, with a 200', async () => {
  const refusing = kv({}, { put: true })
  const res = await worker.fetch(ask(), env(refusing))
  assert.equal(res.status, 200)
  assert.equal(JSON.parse((await res.json()).content[0].text).say, 'About 300 milliseconds.')
  // It tried, it was refused, it said so — and nothing else happened.
  assert.ok(logs.some((l) => l.route === 'accounting' && l.what === 'flush'), 'the refused flush is logged')
  assert.ok(!logs.some((l) => l.error === 'unhandled'), 'nothing reached the error handler')
})

test('so do the voice and the ears', async () => {
  const refusing = kv({}, { put: true })
  assert.equal((await worker.fetch(post('tts', { text: 'hello' }), env(refusing))).status, 200)
  assert.equal((await worker.fetch(post('stt-token', {}), env(refusing))).status, 200)
})

test('a cap that cannot be read is not reached: the request goes ahead', async () => {
  // Only the counters: a session or an account that cannot be read is another matter.
  const unreadable = kv({}, { get: 'cap:' })
  const res = await worker.fetch(ask(), env(unreadable))
  assert.equal(res.status, 200)
  assert.ok(logs.some((l) => l.route === 'accounting' && l.what === 'cap_read'))
})

test('while KV refuses writes, the caps still hold from memory', async () => {
  const today = new Date(T0).toISOString().slice(0, 10)
  const refusing = kv({ [`cap:${today}:${DEVICE}`]: JSON.stringify({ chat: 149 }) }, { put: true })
  assert.equal((await worker.fetch(ask(), env(refusing))).status, 200)
  // Even a minute later, after a flush that failed, the 150th is remembered.
  t += 61_000
  const res = await worker.fetch(ask(), env(refusing))
  assert.equal(res.status, 429)
  assert.equal((await res.json()).reason, 'daily_cap')
})

// ------------------------------------------------------------------ batched writes

test('inside a minute the count is KV plus what is pending here, so the cap is exact', async () => {
  const today = new Date(T0).toISOString().slice(0, 10)
  const store = kv({ [`cap:${today}:${DEVICE}`]: JSON.stringify({ chat: 148 }) })
  // The first request of an isolate flushes; the next ones wait for the minute.
  assert.equal((await worker.fetch(ask(), env(store))).status, 200)
  t += 5_000
  assert.equal((await worker.fetch(ask(), env(store))).status, 200)
  t += 5_000
  assert.equal((await worker.fetch(ask(), env(store))).status, 429, 'the 151st is refused before any write')
  assert.equal(JSON.parse(store.values.get(`cap:${today}:${DEVICE}`)!).chat, 149, 'KV has not heard of the 150th yet')
  // A minute on, it has.
  t += 60_000
  await worker.fetch(post('stt-token', {}), env(store))
  assert.equal(JSON.parse(store.values.get(`cap:${today}:${DEVICE}`)!).chat, 150)
})

test('many requests in a minute are one write per key', async () => {
  const store = kv()
  tallyLimits.everyMs = 60_000
  // The first request flushes (nothing has yet); ten more inside the minute write nothing.
  await worker.fetch(ask(), env(store))
  const afterFirst = store.counted.writes
  for (let i = 0; i < 10; i++) {
    t += 2_000
    await worker.fetch(ask(), env(store))
  }
  assert.equal(store.counted.writes, afterFirst)
  // Then one flush: the cap record, the talks and the day's usage — three writes for ten questions.
  t += 60_000
  await flushIfDue(store, t, () => {})
  assert.equal(store.counted.writes - afterFirst, 3)
  const month = new Date(T0).toISOString().slice(0, 7)
  assert.equal(store.values.get(`talks:d:${DEVICE}:${month}`), '11', 'every talk counted, none twice')
})

test('a hundred requests of real use cost under a third of what they did', async () => {
  console.log = () => {}
  // Measured before this change with the same replay: 250 writes per 100 requests.
  const busy = await replay(25, 20)
  assert.equal(busy.statuses[200], 100)
  assert.ok(busy.writes <= 35, `busy: ${busy.writes} writes per 100 requests`)
  const sparse = await replay(25, 600)
  assert.ok(sparse.writes <= 80, `sparse: ${sparse.writes} writes per 100 requests`)
})

test('memory switched off drops the week pending for it: nothing writes it back', async () => {
  const store = kv()
  tally.update('week:W:2026-09-14', 60, () => ({ n: 0 }), (w: any) => ({ n: w.n + 1 }))
  tally.forget('week:W:2026-09-14')
  await tally.flush(store, () => {})
  assert.equal(store.values.has('week:W:2026-09-14'), false)
})
