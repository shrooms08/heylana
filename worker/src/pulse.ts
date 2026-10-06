/**
 * What is happening on Solana right now, from dated sources rather than the model's memory.
 *
 * Heylana could not answer "what hackathon is running on Solana right now?": a model's memory
 * has no dates and no idea what closed last week. So the worker keeps a small cache of real
 * feeds — every item with the day it was published, its source and its link — and the model
 * reads that. Nothing here is a claim of its own: a hackathon is open only where the source
 * says so, and a deadline is never worked out, only copied.
 *
 * Only sources with a real feed are used. Checked on Oct 6 2026: hackathons.solana.com,
 * colosseum.com's own site, solanamobile.com and the Anza blog publish no feed (they would
 * need scraping), so they are left out rather than guessed at; Colosseum's blog does publish
 * one and is here.
 *
 * One KV record holds the lot, rebuilt at most every [PULSE_TTL_SECONDS] (4 writes a day,
 * whatever the traffic), and every write goes through the accounting wrapper, so a cache that
 * cannot be written never fails the question. A source that will not answer keeps its last
 * good items, marked with their age; one source being down never empties the answer.
 */

export const PULSE_KEY = 'pulse:v1'
/** Rebuilt every six hours: four writes a day, inside the free KV allowance. */
export const PULSE_TTL_SECONDS = 6 * 60 * 60
/** Kept far longer, so a source being down for a day is not an empty answer. */
export const PULSE_KEEP_SECONDS = 30 * 24 * 60 * 60
/** The most items the phone is ever handed. */
export const PULSE_CAP = 25
/** Past this, the whole cache is old enough that Heylana says she could not refresh. */
export const PULSE_STALE_MS = 24 * 60 * 60 * 1000
/** Past this, an item is old enough that its age is said out loud. */
export const PULSE_OLD_DAYS = 7

export type PulseCategory = 'hackathon' | 'release' | 'news'

export interface PulseItem {
  title: string
  /** One plain line: what it is, no markup, no marketing. */
  summary: string
  url: string
  /** ISO day the source published it, or null when the source gives none. */
  published: string | null
  source: string
  category: PulseCategory
  /** Hackathons and bounties only, and only where the source states them. */
  deadline?: string | null
  open?: boolean
}

export interface PulseSourceState {
  id: string
  name: string
  /** When this source last answered, ISO. */
  fetched_at: string | null
  items: PulseItem[]
  /** Set when the last fetch failed and these items are the last good copy. */
  stale?: boolean
  age_hours?: number
}

export interface Pulse {
  fetched_at: string
  sources: PulseSourceState[]
}

type Kind = 'rss' | 'atom' | 'github' | 'superteam'

export interface PulseSource {
  id: string
  name: string
  url: string
  kind: Kind
  category: PulseCategory
  /** How many of its newest items are kept. */
  take: number
  /** Only the first bytes are read, for a feed too big to hold whole (Helius is 5.7MB). */
  bytes?: number
}

/** Every source with a feed, checked on Oct 6 2026. */
export const PULSE_SOURCES: PulseSource[] = [
  { id: 'superteam', name: 'Superteam Earn', url: 'https://earn.superteam.fun/api/listings/?take=20', kind: 'superteam', category: 'hackathon', take: 8 },
  { id: 'solana-news', name: 'Solana news', url: 'https://solana.com/news/rss.xml', kind: 'rss', category: 'news', take: 6 },
  { id: 'colosseum', name: 'Colosseum blog', url: 'https://blog.colosseum.com/rss/', kind: 'rss', category: 'news', take: 4, bytes: 200_000 },
  { id: 'helius', name: 'Helius blog', url: 'https://www.helius.dev/blog/rss.xml', kind: 'atom', category: 'news', take: 4, bytes: 150_000 },
  { id: 'agave', name: 'Agave releases', url: 'https://api.github.com/repos/anza-xyz/agave/releases?per_page=4', kind: 'github', category: 'release', take: 3 },
  { id: 'web3js', name: 'solana-web3.js releases', url: 'https://api.github.com/repos/solana-foundation/solana-web3.js/releases?per_page=3', kind: 'github', category: 'release', take: 2 },
]

// ------------------------------------------------------------------ reading a feed

const TAGS = /<(item|entry)\b[\s\S]*?<\/\1>/g

/** The text of the first [tag] in [block], tags and entities taken out. */
function tagText(block: string, ...tags: string[]): string | null {
  for (const tag of tags) {
    const match = block.match(new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${tag}>`, 'i'))
    if (match) {
      const text = plain(match[1])
      if (text) return text
    }
  }
  return null
}

/** Markup, CDATA and entities out; whitespace to single spaces. */
export function plain(value: string): string {
  return value
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/\s+/g, ' ')
    .trim()
}

/** A line short enough to be read aloud: whole words, nothing cut mid-sentence where it can help it. */
export function oneLine(text: string | null, limit = 160): string {
  if (!text) return ''
  const clean = plain(text)
  if (clean.length <= limit) return clean
  const cut = clean.slice(0, limit)
  const stop = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('! '), cut.lastIndexOf('? '))
  if (stop > 60) return cut.slice(0, stop + 1).trim()
  return cut.slice(0, cut.lastIndexOf(' ')).trim() + '…'
}

/** The day an item says it was published, as an ISO day, or null. */
export function dayOf(value: string | null | undefined): string | null {
  if (!value) return null
  const ms = Date.parse(value)
  if (Number.isNaN(ms)) return null
  return new Date(ms).toISOString()
}

/** An RSS or Atom feed's newest items. */
export function feedItems(text: string, source: PulseSource): PulseItem[] {
  const items: PulseItem[] = []
  for (const block of text.match(TAGS) ?? []) {
    const title = tagText(block, 'title')
    const url = linkIn(block)
    if (!title || !url) continue
    items.push({
      title,
      summary: oneLine(tagText(block, 'description', 'summary', 'content')),
      url,
      published: dayOf(tagText(block, 'pubDate', 'published', 'updated', 'dc:date')),
      source: source.name,
      category: source.category,
    })
    if (items.length >= source.take) break
  }
  return items
}

/** RSS puts the link in the tag's text; Atom in a href. */
function linkIn(block: string): string | null {
  const href = block.match(/<link[^>]*\bhref=["']([^"']+)["']/i)
  if (href && href[1].startsWith('http')) return href[1]
  const text = tagText(block, 'link', 'guid')
  return text && text.startsWith('http') ? text : null
}

/** GitHub's releases API: the tag, the day it was published, and the first line of its notes. */
export function githubItems(text: string, source: PulseSource): PulseItem[] {
  const parsed = JSON.parse(text)
  if (!Array.isArray(parsed)) return []
  return parsed
    .filter((release: any) => release && !release.draft)
    .slice(0, source.take)
    .map((release: any) => ({
      title: String(release.name || release.tag_name || '').trim() || 'release',
      summary: oneLine(String(release.body ?? '').split('\n').find((line: string) => plain(line).length > 20) ?? ''),
      url: String(release.html_url ?? ''),
      published: dayOf(release.published_at ?? release.created_at),
      source: source.name,
      category: source.category,
    }))
    .filter((item: PulseItem) => item.url.startsWith('http'))
}

/**
 * Superteam Earn's listings: the one source that states a deadline and whether it is open, so
 * they are copied as they come and never worked out here.
 */
export function superteamItems(text: string, source: PulseSource): PulseItem[] {
  const parsed = JSON.parse(text)
  if (!Array.isArray(parsed)) return []
  return parsed
    .filter((listing: any) => listing?.title && listing?.slug)
    .slice(0, source.take)
    .map((listing: any) => {
      const reward = typeof listing.rewardAmount === 'number' && listing.token
        ? `${listing.rewardAmount.toLocaleString('en-US')} ${listing.token}`
        : null
      const kind = String(listing.type ?? 'listing')
      const by = listing.sponsor?.name ? ` by ${listing.sponsor.name}` : ''
      return {
        title: String(listing.title),
        summary: oneLine(`A ${kind}${by}${reward ? `, ${reward}` : ''}.`),
        url: `https://earn.superteam.fun/listing/${listing.slug}`,
        published: dayOf(listing.publishedAt ?? listing.createdAt ?? null),
        source: source.name,
        category: 'hackathon' as const,
        deadline: dayOf(listing.deadline),
        // Only what the source says: nothing here decides whether something is still open.
        open: typeof listing.status === 'string' ? listing.status.toUpperCase() === 'OPEN' : undefined,
      }
    })
}

export function itemsOf(text: string, source: PulseSource): PulseItem[] {
  switch (source.kind) {
    case 'github': return githubItems(text, source)
    case 'superteam': return superteamItems(text, source)
    default: return feedItems(text, source)
  }
}

// ------------------------------------------------------------------ what the phone is handed

/** Newest first; an item with no date of its own sits after the dated ones, soonest deadline first. */
export function sorted(items: PulseItem[]): PulseItem[] {
  const dated = items.filter((item) => item.published)
  const undated = items.filter((item) => !item.published)
  dated.sort((a, b) => Date.parse(b.published!) - Date.parse(a.published!))
  undated.sort((a, b) => (Date.parse(a.deadline ?? '') || Infinity) - (Date.parse(b.deadline ?? '') || Infinity))
  return [...dated, ...undated]
}

/** Whole hours since [iso], or null. */
export function hoursSince(iso: string | null, now: number): number | null {
  if (!iso) return null
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return null
  return Math.max(0, Math.floor((now - ms) / 3_600_000))
}

/**
 * The answer: every source's items, newest first, capped, with how old each source's copy is
 * and whether the whole cache is stale. A cache older than a day is still handed over — with
 * its age, so Heylana can say she could not refresh rather than pretend it is today's.
 */
export function pulseBody(pulse: Pulse | null, now: number): {
  items: PulseItem[]
  sources: { name: string; fetched_at: string | null; stale?: boolean; age_hours?: number | null }[]
  fetched_at: string | null
  age_hours: number | null
  stale: boolean
  note?: string
} {
  if (!pulse) {
    return {
      items: [], sources: [], fetched_at: null, age_hours: null, stale: true,
      note: 'Nothing cached. Say you could not refresh, and offer to open the page.',
    }
  }
  const items = sorted(pulse.sources.flatMap((source) => source.items)).slice(0, PULSE_CAP)
  const age = hoursSince(pulse.fetched_at, now)
  const stale = age !== null && age * 3_600_000 >= PULSE_STALE_MS
  return {
    items,
    sources: pulse.sources.map((source) => ({
      name: source.name,
      fetched_at: source.fetched_at,
      ...(source.stale ? { stale: true, age_hours: hoursSince(source.fetched_at, now) } : {}),
    })),
    fetched_at: pulse.fetched_at,
    age_hours: age,
    stale,
    ...(stale ? { note: 'This is more than a day old: say so, give what is here with its age, and offer to open the page.' } : {}),
  }
}

/** Whether a fresh build is due: nothing cached, or the cache has passed its six hours. */
export function needsRebuild(pulse: Pulse | null, now: number): boolean {
  if (!pulse) return true
  const ms = Date.parse(pulse.fetched_at)
  return Number.isNaN(ms) || now - ms >= PULSE_TTL_SECONDS * 1000
}

/**
 * Today's cache: every source fetched, and a source that fails keeping the copy it had, marked
 * stale with its age. Pure of KV — the caller stores what comes back.
 */
export async function buildPulse(
  previous: Pulse | null,
  now: number,
  get: (source: PulseSource) => Promise<string>,
): Promise<Pulse> {
  const before = new Map((previous?.sources ?? []).map((source) => [source.id, source]))
  const sources: PulseSourceState[] = []
  const fetched = await Promise.all(PULSE_SOURCES.map(async (source) => {
    try {
      return { source, items: itemsOf(await get(source), source) }
    } catch {
      return { source, items: null }
    }
  }))
  for (const { source, items } of fetched) {
    const had = before.get(source.id)
    if (items && items.length > 0) {
      sources.push({ id: source.id, name: source.name, fetched_at: new Date(now).toISOString(), items })
    } else if (had && had.items.length > 0) {
      // Down, or answering with nothing usable: what it last said still stands, with its age.
      sources.push({ ...had, stale: true, age_hours: hoursSince(had.fetched_at, now) ?? undefined })
    }
  }
  return { fetched_at: new Date(now).toISOString(), sources }
}
