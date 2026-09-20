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
 * everyone. No hashing here: the phone holds the list as it is, and hashing a public list
 * would buy nothing but bytes.
 */

export const LOOKOUT_KEY = 'lookout:list'
export const LOOKOUT_SEED_KEY = 'lookout:seed'
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
/** Their open feed lags their own by a week, so that is how far back the day is taken. */
export const ARCHIVE_LAG_DAYS = 8

export interface Lookout {
  /** The day it was last built, so the phone can tell whether it already has this one. */
  version: string
  /** The domains themselves, lower case, no www., sorted. */
  domains: string[]
  /** Where they came from, in the words PRODUCT.md uses. */
  sources: string[]
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
 * The words that make a domain worth one of the phone's places. The list is capped, and a
 * Solana buddy's list should be a Solana one: a parcel-delivery scam is someone else's job
 * and the browser's own warning already covers a great many of them.
 */
const WORTH_KEEPING = [
  'sol', 'phantom', 'solflare', 'backpack', 'jup', 'raydium', 'orca', 'meteora', 'kamino',
  'marginfi', 'drift', 'marinade', 'jito', 'sanctum', 'magiceden', 'tensor', 'pump', 'bonk',
  'seeker', 'saga', 'wallet', 'airdrop', 'claim', 'mint', 'nft', 'swap', 'dapp', 'token',
  'crypto', 'web3', 'ledger', 'connect', 'metamask', 'trezor', 'seed', 'staking',
]

/** Whether a domain is one this list is for. */
export function worthKeeping(domain: string): boolean {
  return WORTH_KEEPING.some((word) => domain.includes(word))
}

/**
 * The new list: what it had, plus what the day's sources add, the ones worth keeping
 * first and the oldest dropped past the cap. Pure — the caller stores what comes back.
 */
export function foldIn(current: string[], adding: string[], cap = LOOKOUT_CAP): string[] {
  const kept = new Set(current)
  for (const domain of adding) {
    if (!worthKeeping(domain)) continue
    kept.add(domain)
  }
  const all = [...kept]
  // Past the cap the oldest go: a domain that has been on the list for months and has
  // never been seen is worth less than one added today.
  return (all.length > cap ? all.slice(all.length - cap) : all).sort()
}
