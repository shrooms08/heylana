/**
 * The phishing blocklist the phone checks against.
 *
 * A wallet assistant that watches for scam sites has an obvious way to go wrong: sending
 * every address anyone visits to a server to be checked. Heylana does not do that. The
 * worker keeps a list of known phishing domains and hands the **whole list** to the phone
 * once a day; the phone checks what it sees against it on its own, so no address bar —
 * and no page anyone visited — is ever sent anywhere, to Heylana or to anyone else.
 *
 * Two public sources, both checked on Sept 20 (PRODUCT.md names them and why):
 *
 *  - **Scam Sniffer** (`scamsniffer/scam-database`, GPL-3.0) is the live one: appended to
 *    every day, crypto-native, and the feed Phantom's own product uses. Its whole file is
 *    nine megabytes, so the worker takes the **daily archive** files instead — a kilobyte
 *    or two each — and keeps what they add. The open feed runs seven days behind their
 *    commercial one, which is a limit worth knowing rather than hiding.
 *  - **Phantom's blocklist** (`phantom/blocklist`) is the seed: about 2,300 hand-picked
 *    Solana phishing domains, the densest Solana list there is. It has not been updated
 *    since January 2025, so it is fetched once and treated as a frozen snapshot, never as
 *    a live source.
 *
 * What the worker does to them is small on purpose — split lines, keep what looks like a
 * domain, drop the rest — because a Worker's CPU budget is small and this runs for
 * everyone. Every domain either source lists is kept: each is a known scam. No hashing here: the phone holds the list as it is, and hashing a public list
 * would buy nothing but bytes.
 */

export const LOOKOUT_KEY = 'lookout:list'
/** v2: the seed folded in whole, once more, after the Solana-words filter was dropped. */
export const LOOKOUT_SEED_KEY = 'lookout:seed:v2'
/** A stored list without this format was built with the filter, and is rebuilt at once. */
export const LOOKOUT_FORMAT = 2
/** Rebuilt a day after it was last built. */
export const LOOKOUT_TTL_SECONDS = 24 * 60 * 60
/** Kept far longer than that, so a source being down is not an outage here. */
export const LOOKOUT_KEEP_SECONDS = 30 * 24 * 60 * 60
/** The most domains the phone is ever asked to download, so it cannot grow unwatched. */
export const LOOKOUT_CAP = 8_000

/** Appended to every day; `<date>` is the day it covers, seven days behind today. */
export const SCAMSNIFFER_ARCHIVE = 'https://raw.githubusercontent.com/scamsniffer/scam-database/main/blacklist/archive/'
/** Frozen since January 2025: fetched once as a seed, never as a live source. */
export const PHANTOM_SEED = 'https://raw.githubusercontent.com/phantom/blocklist/master/blocklist.yaml'
/**
 * Upstream's open feed is 7 days delayed; 8 gives a one-day margin for archives that land
 * late, so the day asked for has always been published.
 */
export const ARCHIVE_LAG_DAYS = 8

export interface Lookout {
  /** The day it was last built, so the phone can tell whether it already has this one. */
  version: string
  /** The domains themselves, lower case, no www., sorted. */
  domains: string[]
  /** Where they came from, in the words PRODUCT.md uses. */
  sources: string[]
  /** [LOOKOUT_FORMAT] once built without the Solana-words filter. */
  format?: number
}

/** The day [ms] falls in, in UTC, as "2026-09-20". */
export function dayOf(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

/** The archive file to ask for today: their open feed runs a week behind. */
export function archiveFor(ms: number): string {
  return `${SCAMSNIFFER_ARCHIVE}${dayOf(ms - ARCHIVE_LAG_DAYS * 24 * 60 * 60 * 1000)}.json`
}

/**
 * The domains in a list, whatever shape it arrives in: a JSON array of strings, a JSON
 * object holding one, a YAML list of `- url: domain` lines, or one domain a line with `#`
 * comments. Anything that is not a domain is dropped rather than guessed at.
 */
export function domainsIn(text: string): string[] {
  const found = new Set<string>()
  const keep = (value: unknown) => {
    if (typeof value !== 'string') return
    const domain = cleanDomain(value)
    if (domain) found.add(domain)
  }

  const trimmed = text.trim()
  if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
    try {
      walk(JSON.parse(trimmed), keep)
      if (found.size > 0) return [...found]
    } catch {
      // Not the JSON it looked like; read it as lines instead.
    }
  }
  for (const line of trimmed.split('\n')) {
    // "- url: bad.example" (Phantom), "- bad.example", '"bad.example",' or a hosts file.
    const bare = line.trim().replace(/^[-*]\s*/, '').replace(/^url:\s*/i, '').replace(/^["']|["'],?$/g, '')
    if (!bare || bare.startsWith('#') || bare.startsWith('---')) continue
    const parts = bare.split(/\s+/)
    keep(parts.length > 1 && /^\d/.test(parts[0]) ? parts[1] : parts[0])
  }
  return [...found]
}

function walk(value: unknown, keep: (value: unknown) => void, depth = 0): void {
  if (depth > 4) return
  if (Array.isArray(value)) {
    for (const item of value) {
      if (typeof item === 'string') keep(item)
      else walk(item, keep, depth + 1)
    }
    return
  }
  if (value && typeof value === 'object') {
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      // A config file's other fields — the domains it says are fine — are not the blocklist.
      if (key === 'fuzzylist' || key === 'whitelist' || key === 'allowlist' || key === 'version') continue
      walk(item, keep, depth + 1)
    }
  }
}

const DOMAIN = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/

/** A domain, or null: no scheme, no path, no port, no bare address, nothing with a space. */
export function cleanDomain(value: string): string | null {
  let text = value.trim().toLowerCase()
  if (!text || text.startsWith('#')) return null
  text = text.replace(/^[a-z]+:\/\//, '').split('/')[0].split('?')[0].split(':')[0].replace(/^www\./, '').replace(/\.$/, '')
  if (!DOMAIN.test(text)) return null
  if (/^\d+(\.\d+)+$/.test(text)) return null
  if (text.length > 100) return null
  return text
}

/**
 * The new list, newest first: what the day's source adds that the list did not have, then
 * everything it had. **Every listed domain is kept** — each one is a known scam, so a
 * "Solana words only" filter (there was one, until Sept 21) only cut coverage: it kept 24
 * of a day's 77 Scam Sniffer domains and 1,220 of Phantom's 2,241. [last] folds a source in
 * at the end instead, which is where Phantom's frozen snapshot goes: past the cap the end is
 * what is dropped, so the newest Scam Sniffer entries are the last to go. Pure — the caller
 * stores what comes back and logs [dropped].
 */
export function fold(
  current: string[],
  adding: string[],
  options: { cap?: number; last?: boolean } = {},
): { domains: string[]; dropped: number } {
  const cap = options.cap ?? LOOKOUT_CAP
  const had = new Set(current)
  const fresh: string[] = []
  for (const domain of adding) {
    if (had.has(domain)) continue
    had.add(domain)
    fresh.push(domain)
  }
  const all = options.last ? [...current, ...fresh] : [...fresh, ...current]
  if (all.length <= cap) return { domains: all, dropped: 0 }
  return { domains: all.slice(0, cap), dropped: all.length - cap }
}

/** [fold] without the count, for the callers that only want the list. */
export function foldIn(current: string[], adding: string[], cap = LOOKOUT_CAP): string[] {
  return fold(current, adding, { cap }).domains
}
