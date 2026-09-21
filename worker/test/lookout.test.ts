import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import worker, { clock, type Env } from '../src/index.ts'
import {
  ARCHIVE_LAG_DAYS, LOOKOUT_CAP, LOOKOUT_KEY, PHANTOM_SEED, archiveFor, cleanDomain, dayOf, domainsIn, fold, foldIn,
} from '../src/lookout.ts'

const DEVICE = '3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55'
const SEPT = Date.parse('2026-09-20T12:00:00Z')

// ------------------------------------------------------------------ reading a list

test('a domain is taken out of every shape these lists come in', () => {
  // Scam Sniffer: a JSON array.
  assert.deepEqual(domainsIn('["phanton.app", "claim-sol.xyz"]'), ['phanton.app', 'claim-sol.xyz'])
  // Their archive files: an object holding one.
  assert.deepEqual(domainsIn('{"domains": ["claim-sol.xyz"]}'), ['claim-sol.xyz'])
  // Phantom: a YAML list of "- url:" lines, with its stray version key.
  const phantom = '---\n  - url: blogpost-opensea.io\n  - url: phantomweb.app\n    versionTag: "0.13.0"\n'
  assert.deepEqual(domainsIn(phantom), ['blogpost-opensea.io', 'phantomweb.app'])
  // MetaMask: an object whose blacklist is the list, and whose other lists are not.
  const metamask = '{"version":2,"tolerance":1,"fuzzylist":["etherscan.io"],"whitelist":["phantom.app"],"blacklist":["phantom-mywallet.com"]}'
  assert.deepEqual(domainsIn(metamask), ['phantom-mywallet.com'])
  // One a line, with comments and a hosts file's address in front.
  assert.deepEqual(domainsIn('# a list\nbad-sol.io\n0.0.0.0 worse-sol.io\n'), ['bad-sol.io', 'worse-sol.io'])
})

test('what is not a domain never reaches the phone', () => {
  assert.equal(cleanDomain('https://claim-sol.xyz/path?a=1'), 'claim-sol.xyz')
  assert.equal(cleanDomain('WWW.Claim-Sol.XYZ.'), 'claim-sol.xyz')
  assert.equal(cleanDomain('192.168.0.1'), null)
  assert.equal(cleanDomain('not a domain'), null)
  assert.equal(cleanDomain('localhost'), null)
  assert.equal(cleanDomain(''), null)
  assert.equal(cleanDomain('#comment'), null)
  assert.equal(cleanDomain('a'.repeat(120) + '.com'), null)
})

test('every listed domain is kept: no Solana-words filter', () => {
  // Each is a known scam; a filter only cut coverage (24 of 77, 1,220 of 2,241).
  assert.deepEqual(foldIn([], ['dhl-parcel-redelivery.info', 'claim-sol.xyz']), ['dhl-parcel-redelivery.info', 'claim-sol.xyz'])
  // What is kept twice is kept once.
  assert.equal(foldIn(['claim-sol.xyz'], ['claim-sol.xyz']).length, 1)
})

test('past the cap the oldest go, the newest Scam Sniffer entries stay, and it is counted', () => {
  // A snapshot at the end, older archive days, then today's.
  const seed = Array.from({ length: 30 }, (_, i) => `seed-${i}.app`)
  const older = Array.from({ length: 30 }, (_, i) => `old-${i}.xyz`)
  const today = Array.from({ length: 30 }, (_, i) => `new-${i}.xyz`)
  let list = fold([], older, { cap: 50 }).domains
  const seeded = fold(list, seed, { cap: 50, last: true })
  assert.equal(seeded.dropped, 10)
  list = seeded.domains
  const folded = fold(list, today, { cap: 50 })
  assert.equal(folded.domains.length, 50)
  assert.equal(folded.dropped, 30)
  // Today's first, then the older archive day; the snapshot is what went.
  assert.deepEqual(folded.domains.slice(0, 30), today)
  assert.ok(folded.domains.slice(30).every((d) => d.startsWith('old-')))
  assert.equal(folded.domains.some((d) => d.startsWith('seed-')), false)
})

test('the day asked for is a week behind, because their open feed is', () => {
  assert.equal(dayOf(SEPT), '2026-09-20')
  assert.match(archiveFor(SEPT), /scamsniffer.*archive\/2026-09-12\.json$/)
  assert.equal(ARCHIVE_LAG_DAYS, 8)
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

function env(kv = store()): Env {
  return {
    ANTHROPIC_API_KEY: 'sk-ant-test-000000000000', DEEPGRAM_API_KEY: 'd', CARTESIA_API_KEY: 'c',
    SESSION_SECRET: 'test-session-secret-0123456789', CAPS: kv,
  } as Env
}

let fetched: string[]
let sources: Record<string, { status: number; body: string }>

beforeEach(() => {
  clock.now = () => SEPT
  console.log = () => {}
  fetched = []
  sources = {
    [PHANTOM_SEED]: { status: 200, body: '---\n  - url: phantomweb.app\n  - url: solvision.io\n' },
    [archiveFor(SEPT)]: { status: 200, body: '{"domains":["claim-sol-airdrop.xyz","dhl-parcel.info"]}' },
  }
  globalThis.fetch = (async (input: any) => {
    const url = typeof input === 'string' ? input : input.url
    fetched.push(url)
    const source = sources[url]
    if (!source) return new Response('not found', { status: 404 })
    return new Response(source.body, { status: source.status })
  }) as typeof fetch
})

function ask(have?: string) {
  const url = `https://proxy.heylana.xyz/lookout${have ? `?have=${have}` : ''}`
  return new Request(url, { method: 'GET', headers: { 'X-Heylana-Device': DEVICE } })
}

test('the phone is handed the whole list, and never asked what it is looking at', async () => {
  const e = env()
  const res = await worker.fetch(ask(), e)
  assert.equal(res.status, 200)
  const body = await res.json() as any
  assert.equal(body.version, '2026-09-20')
  // Both sources went in whole: the parcel scam is a scam too.
  assert.deepEqual(body.domains.sort(), ['claim-sol-airdrop.xyz', 'dhl-parcel.info', 'phantomweb.app', 'solvision.io'].sort())
  assert.ok(body.sources.includes('phantom/blocklist (snapshot)'))
  assert.ok(body.sources.includes('scamsniffer/scam-database'))
  // The request carries nothing about the user but the device header every route takes.
  assert.equal(new URL(ask().url).search, '')
})

test('a phone that already has today\'s list is told so in a few bytes', async () => {
  const e = env()
  await worker.fetch(ask(), e)
  const again = await worker.fetch(ask('2026-09-20'), e)
  const body = await again.json() as any
  assert.equal(body.unchanged, true)
  assert.equal(body.domains, undefined)
  // Yesterday's version gets the list itself.
  const stale = await (await worker.fetch(ask('2026-09-19'), e)).json() as any
  assert.ok(Array.isArray(stale.domains))
})

test('the seed is fetched once, ever, and the day\'s archive every day', async () => {
  const e = env()
  await worker.fetch(ask(), e)
  assert.equal(fetched.filter((url) => url === PHANTOM_SEED).length, 1)

  // A new day: the archive again, the seed never again.
  clock.now = () => SEPT + 24 * 60 * 60 * 1000
  sources[archiveFor(clock.now())] = { status: 200, body: '{"domains":["sol-drainer.io"]}' }
  const body = await (await worker.fetch(ask(), e)).json() as any
  assert.equal(fetched.filter((url) => url === PHANTOM_SEED).length, 1, 'the frozen seed is not refetched')
  assert.ok(body.domains.includes('sol-drainer.io'))
  assert.ok(body.domains.includes('phantomweb.app'), 'and what it already had is kept')
})

test('a source that is down leaves yesterday\'s list standing', async () => {
  const e = env()
  await worker.fetch(ask(), e)
  const had = ((await (await worker.fetch(ask(), e)).json()) as any).domains

  clock.now = () => SEPT + 24 * 60 * 60 * 1000
  sources = {} // every source down
  const body = await (await worker.fetch(ask(), e)).json() as any
  assert.deepEqual(body.domains, had, 'the list it had is still the list')
})

test('a source answering with rubbish adds nothing', async () => {
  const e = env()
  sources[archiveFor(SEPT)] = { status: 200, body: '<!doctype html><h1>404</h1>' }
  const body = await (await worker.fetch(ask(), e)).json() as any
  assert.deepEqual(body.domains, ['phantomweb.app', 'solvision.io'])
})

test('a list built with the old filter is rebuilt at once, whole, and the seed fetched again', async () => {
  const kv = store()
  // Yesterday's filtered list, stored today, with no format.
  kv.values.set(LOOKOUT_KEY, JSON.stringify({ version: '2026-09-20', domains: ['claim-sol-airdrop.xyz'], sources: ['scamsniffer/scam-database'] }))
  kv.values.set('lookout:seed', '2026-09-01')
  const body = await (await worker.fetch(ask(), env(kv))).json() as any
  assert.equal(fetched.filter((url) => url === PHANTOM_SEED).length, 1, 'the snapshot is folded in whole once more')
  assert.ok(body.domains.includes('dhl-parcel.info'))
  assert.ok(body.domains.includes('claim-sol-airdrop.xyz'), 'what it had is kept')
})

test('a cap that cuts the list says so in the log', async () => {
  const lines: string[] = []
  console.log = (line: string) => { lines.push(String(line)) }
  const huge = Array.from({ length: LOOKOUT_CAP + 5 }, (_, i) => `scam-${i}.xyz`)
  sources[archiveFor(SEPT)] = { status: 200, body: JSON.stringify(huge) }
  const body = await (await worker.fetch(ask(), env())).json() as any
  assert.equal(body.domains.length, LOOKOUT_CAP)
  // The newest (today's archive) are kept; the snapshot went first.
  assert.equal(body.domains.includes('phantomweb.app'), false)
  const warning = lines.map((l) => { try { return JSON.parse(l) } catch { return null } }).find((l) => l?.what === 'cap_truncated')
  assert.ok(warning, 'the truncation is logged')
  assert.equal(warning.dropped, 7)
})
