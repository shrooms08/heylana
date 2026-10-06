import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import {
  PULSE_CAP, PULSE_KEY, PULSE_SOURCES, PULSE_TTL_SECONDS, buildPulse, feedItems, githubItems, needsRebuild,
  oneLine, pulseBody, sorted, superteamItems, type Pulse, type PulseSource,
} from '../src/pulse.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const NOW = Date.parse('2026-10-06T12:00:00Z')

const source = (id: string): PulseSource => PULSE_SOURCES.find((s) => s.id === id)!

// Shortened copies of what each feed really answered on Oct 6 2026.
const RSS = `<?xml version="1.0"?><rss version="2.0"><channel><title>Solana News</title>
  <item><title>Solana Mobile ships a new Seeker update</title>
    <link>https://solana.com/news/seeker-update</link>
    <description><![CDATA[<p>The update brings <b>faster</b> signing to the Seeker.</p>]]></description>
    <pubDate>Fri, 03 Oct 2026 09:00:00 GMT</pubDate></item>
  <item><title>Older piece</title><link>https://solana.com/news/older</link>
    <description>Something else.</description><pubDate>Mon, 01 Sep 2026 09:00:00 GMT</pubDate></item>
</channel></rss>`

const ATOM = `<?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom">
  <title>Helius Blog</title><updated>2026-10-05T20:40:52.469Z</updated>
  <entry><title>How priority fees work now</title>
    <link rel="alternate" href="https://www.helius.dev/blog/priority-fees"/>
    <summary>What changed with priority fees this year, and what to set.</summary>
    <updated>2026-09-21T10:00:00.000Z</updated></entry>
</feed>`

const GITHUB = JSON.stringify([
  {
    name: 'v3.1.0', tag_name: 'v3.1.0', draft: false, published_at: '2026-10-02T18:00:00Z',
    html_url: 'https://github.com/anza-xyz/agave/releases/tag/v3.1.0',
    body: '# Changes\n* Fixes a long-standing bug in the accounts database and speeds up snapshots.',
  },
  { name: 'draft', draft: true, html_url: 'https://github.com/anza-xyz/agave/releases/tag/x', published_at: '2026-10-03T00:00:00Z' },
])

const SUPERTEAM = JSON.stringify([
  {
    title: 'Crypto World\'s Fair Pakistan Track', slug: 'crypto-worlds-fair-pakistan-track', type: 'hackathon',
    rewardAmount: 5000, token: 'USDC', deadline: '2026-10-13T06:59:00.000Z', status: 'OPEN',
    sponsor: { name: 'Superteam Pakistan' },
  },
  { title: 'A closed bounty', slug: 'closed-bounty', type: 'bounty', deadline: '2026-09-01T00:00:00.000Z', status: 'CLOSED' },
])

test('an RSS feed becomes dated items, markup and all taken out', () => {
  const items = feedItems(RSS, source('solana-news'))
  assert.equal(items.length, 2)
  assert.equal(items[0].title, 'Solana Mobile ships a new Seeker update')
  assert.equal(items[0].url, 'https://solana.com/news/seeker-update')
  assert.equal(items[0].summary, 'The update brings faster signing to the Seeker.')
  assert.equal(items[0].published, '2026-10-03T09:00:00.000Z')
  assert.equal(items[0].category, 'news')
  assert.equal(items[0].source, 'Solana news')
})

test('an Atom feed takes its link from the href', () => {
  const items = feedItems(ATOM, source('helius'))
  assert.equal(items.length, 1)
  assert.equal(items[0].url, 'https://www.helius.dev/blog/priority-fees')
  assert.equal(items[0].published, '2026-09-21T10:00:00.000Z')
})

test('GitHub releases: the tag, the day and the first real line; drafts are not releases', () => {
  const items = githubItems(GITHUB, source('agave'))
  assert.equal(items.length, 1)
  assert.equal(items[0].title, 'v3.1.0')
  assert.equal(items[0].published, '2026-10-02T18:00:00.000Z')
  assert.match(items[0].summary, /accounts database/)
  assert.equal(items[0].category, 'release')
})

test('a listing keeps the deadline and whether it is open, exactly as the source says', () => {
  const items = superteamItems(SUPERTEAM, source('superteam'))
  assert.equal(items.length, 2)
  assert.equal(items[0].deadline, '2026-10-13T06:59:00.000Z')
  assert.equal(items[0].open, true)
  assert.equal(items[0].url, 'https://earn.superteam.fun/listing/crypto-worlds-fair-pakistan-track')
  assert.match(items[0].summary, /hackathon by Superteam Pakistan, 5,000 USDC/)
  assert.equal(items[0].category, 'hackathon')
  // Closed is closed because the source says so, and nothing here works one out.
  assert.equal(items[1].open, false)
  const noStatus = superteamItems(JSON.stringify([{ title: 'T', slug: 's', type: 'bounty' }]), source('superteam'))
  assert.equal(noStatus[0].open, undefined)
  assert.equal(noStatus[0].deadline, null)
})

test('a line is one plain sentence, cut on a word', () => {
  assert.equal(oneLine('<p>Hi &amp; hello</p>'), 'Hi & hello')
  const long = oneLine('a'.repeat(200))
  assert.ok(long.length <= 161 && long.endsWith('…'))
})

test('newest first; something with no date of its own goes after, soonest deadline first', () => {
  const items = sorted([
    { title: 'old', summary: '', url: 'u1', published: '2026-09-01T00:00:00Z', source: 's', category: 'news' },
    { title: 'later deadline', summary: '', url: 'u2', published: null, source: 's', category: 'hackathon', deadline: '2026-12-01T00:00:00Z' },
    { title: 'new', summary: '', url: 'u3', published: '2026-10-05T00:00:00Z', source: 's', category: 'news' },
    { title: 'soon', summary: '', url: 'u4', published: null, source: 's', category: 'hackathon', deadline: '2026-10-13T00:00:00Z' },
  ])
  assert.deepEqual(items.map((i) => i.title), ['new', 'old', 'soon', 'later deadline'])
})

test('a source that is down keeps its last good items, marked with their age', async () => {
  const first = await buildPulse(null, NOW, async (s) => (s.id === 'solana-news' ? RSS : '[]'))
  assert.deepEqual(first.sources.map((s) => s.id), ['solana-news'])

  const later = NOW + 7 * 60 * 60 * 1000
  const second = await buildPulse(first, later, async () => { throw new Error('down') })
  const kept = second.sources[0]
  assert.equal(kept.stale, true)
  assert.equal(kept.age_hours, 7)
  assert.equal(kept.items[0].title, 'Solana Mobile ships a new Seeker update')
  // And the answer still carries them, with how old the whole cache is.
  const body = pulseBody(second, later)
  assert.equal(body.items.length, 2)
  assert.equal(body.sources[0].stale, true)
})

test('an empty cache, and one more than a day old, say so rather than pretend', () => {
  const empty = pulseBody(null, NOW)
  assert.deepEqual(empty.items, [])
  assert.equal(empty.stale, true)
  assert.match(empty.note!, /could not refresh/)

  const old: Pulse = {
    fetched_at: new Date(NOW - 30 * 60 * 60 * 1000).toISOString(),
    sources: [{ id: 'solana-news', name: 'Solana news', fetched_at: new Date(NOW - 30 * 60 * 60 * 1000).toISOString(), items: feedItems(RSS, source('solana-news')) }],
  }
  const body = pulseBody(old, NOW)
  assert.equal(body.stale, true)
  assert.equal(body.age_hours, 30)
  assert.match(body.note!, /more than a day old/)
  // Fresh enough: no note at all.
  const fresh = pulseBody({ ...old, fetched_at: new Date(NOW - 60_000).toISOString() }, NOW)
  assert.equal(fresh.stale, false)
  assert.equal(fresh.note, undefined)
})

test('the cache is rebuilt every six hours and no more often, and is capped', () => {
  const pulse: Pulse = { fetched_at: new Date(NOW).toISOString(), sources: [] }
  assert.equal(needsRebuild(pulse, NOW + PULSE_TTL_SECONDS * 1000 - 1), false)
  assert.equal(needsRebuild(pulse, NOW + PULSE_TTL_SECONDS * 1000), true)
  assert.equal(needsRebuild(null, NOW), true)

  const many = Array.from({ length: 40 }, (_, i) => ({
    title: `item ${i}`, summary: '', url: `https://x.test/${i}`, published: new Date(NOW - i * 60_000).toISOString(),
    source: 's', category: 'news' as const,
  }))
  assert.equal(pulseBody({ fetched_at: new Date(NOW).toISOString(), sources: [{ id: 's', name: 's', fetched_at: new Date(NOW).toISOString(), items: many }] }, NOW).items.length, PULSE_CAP)
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

let kv: ReturnType<typeof store>
let env: Env
let asked: string[]

beforeEach(() => {
  clock.now = () => NOW
  console.log = () => {}
  kv = store()
  asked = []
  env = {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', DEEPGRAM_API_KEY: 'd', CARTESIA_API_KEY: 'c',
    SESSION_SECRET: 'test-session-secret-0123456789', CAPS: kv,
  } as Env
  globalThis.fetch = (async (input: any) => {
    const url = typeof input === 'string' ? input : input.url
    asked.push(url)
    if (url.includes('superteam')) return new Response(SUPERTEAM)
    if (url.includes('solana.com')) return new Response(RSS)
    if (url.includes('helius')) return new Response(ATOM)
    if (url.includes('api.github.com')) return new Response(GITHUB)
    return new Response('nope', { status: 500 })
  }) as typeof fetch
})

const ask = (query = '') => worker.fetch(
  new Request(`https://proxy.heylana.xyz/pulse${query}`, { method: 'GET', headers: { 'X-Heylana-Device': DEVICE } }),
  env,
)

test('the route builds once, answers from the cache after that, and filters by category', async () => {
  const body = await (await ask()).json() as any
  assert.equal(body.stale, false)
  assert.ok(body.items.length > 0)
  assert.ok(body.items.every((item: any) => item.url.startsWith('http') && 'published' in item))
  // Every source was asked once, and the lot is one KV record.
  assert.equal(asked.length, PULSE_SOURCES.length)
  assert.deepEqual([...kv.values.keys()].filter((k) => k.startsWith('pulse')), [PULSE_KEY])

  asked = []
  const again = await (await ask()).json() as any
  assert.equal(asked.length, 0, 'inside six hours nothing is fetched again')
  assert.deepEqual(again.items.map((i: any) => i.url), body.items.map((i: any) => i.url))

  const hackathons = await (await ask('?category=hackathon')).json() as any
  assert.ok(hackathons.items.length > 0)
  assert.ok(hackathons.items.every((item: any) => item.category === 'hackathon'))
})

test('one source down never empties the answer', async () => {
  globalThis.fetch = (async (input: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.includes('superteam')) return new Response('down', { status: 503 })
    return new Response(RSS)
  }) as typeof fetch
  const body = await (await ask()).json() as any
  assert.ok(body.items.length > 0)
  assert.equal(body.items.some((item: any) => item.category === 'hackathon'), false)
})

test('a cache that cannot be written still answers the question', async () => {
  env = { ...env, CAPS: { ...kv, async put() { throw new Error('kv down') } } } as Env
  const res = await ask()
  assert.equal(res.status, 200)
  assert.ok(((await res.json()) as any).items.length > 0)
})

test('a pulse answer writes nothing to memory: the cache is not a fact about anyone', async () => {
  // The model asks for the pulse and answers; what comes back is dated items, never a record.
  const rounds: any[] = [
    {
      stop_reason: 'tool_use', usage: { input_tokens: 10, output_tokens: 5 },
      content: [{ type: 'tool_use', id: 't1', name: 'solana_pulse', input: { category: 'hackathon' } }],
    },
    {
      stop_reason: 'end_turn', usage: { input_tokens: 12, output_tokens: 8 },
      content: [{ type: 'text', text: JSON.stringify({ say: 'One bounty closes on 13 October.', point_at: null, task: null }) }],
    },
  ]
  const feeds = globalThis.fetch
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('https://api.anthropic.com')) return new Response(JSON.stringify(rounds.shift()))
    return await (feeds as any)(input, init)
  }) as typeof fetch

  const res = await worker.fetch(new Request('https://proxy.heylana.xyz/chat', {
    method: 'POST', headers: { 'X-Heylana-Device': DEVICE },
    body: JSON.stringify({
      mode: 'quick', system: 'S', tools: true, tool_names: ['solana_pulse'],
      said: 'what hackathon is going on on Solana right now?',
      messages: [{ role: 'user', content: 'User asks: what hackathon is going on on Solana right now?' }],
    }),
  }), env)
  assert.equal(res.status, 200)
  assert.equal([...kv.values.keys()].some((key) => key.startsWith('memory')), false)
})
