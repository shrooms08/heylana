/**
 * "What I caught this week", per wallet.
 *
 * Counts only. Six things are counted — questions answered, screens explained, transactions
 * explained, sends prepared, sends stopped before signing (the simulation failed), and
 * addresses looked up for the first time — and **nothing about any of them is kept**: no
 * address, no amount, no word of a screen or an answer. The record holds seven numbers and,
 * so a first-time address can be told from a repeat, salted hashes of the addresses looked
 * up that week, which never leave the worker and go with the record when the week does.
 *
 * It follows memory: with memory off nothing is counted, and turning memory off throws the
 * week away with the rest.
 */

export const WEEK_PREFIX = 'week:'
/** A week's record outlives the week itself, so Monday's card still has Sunday in it. */
export const WEEK_TTL_SECONDS = 21 * 24 * 60 * 60
/** The most addresses remembered (as hashes) in one week, so the record cannot grow. */
export const SEEN_CAP = 500

export interface WeekCounts {
  /** Questions answered, whatever they were about. */
  questions: number
  /** Screens read and explained. */
  screens: number
  /** Signing screens explained before anything was signed. */
  transactions: number
  sends_prepared: number
  /** Sends whose simulation failed, so the wallet never opened. */
  sends_stopped: number
  /** Addresses looked up for the first time this week. */
  new_addresses: number
  lessons: number
}

export interface Week {
  /** The Monday the week starts on, in UTC: "2026-09-14". */
  start: string
  counts: WeekCounts
  /** Salted hashes of the addresses looked up this week. Never returned, never logged. */
  seen: string[]
}

export const EMPTY_COUNTS: WeekCounts = {
  questions: 0,
  screens: 0,
  transactions: 0,
  sends_prepared: 0,
  sends_stopped: 0,
  new_addresses: 0,
  lessons: 0,
}

export function emptyWeek(start: string): Week {
  return { start, counts: { ...EMPTY_COUNTS }, seen: [] }
}

/** The Monday of the week [ms] falls in, in UTC. */
export function weekStart(ms: number): string {
  const day = new Date(ms)
  const weekday = (day.getUTCDay() + 6) % 7 // Monday is 0
  return new Date(Date.UTC(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate() - weekday)).toISOString().slice(0, 10)
}

/** What one request caught. Every field optional: a request counts only what it did. */
export interface WeekPatch extends Partial<WeekCounts> {
  /** Hashed addresses this request looked up; the new ones are counted, the rest ignored. */
  seen?: string[]
}

/** [patch] folded into [week]. Pure: the caller stores what comes back. */
export function applyWeek(week: Week, patch: WeekPatch): Week {
  const counts = { ...week.counts }
  for (const key of Object.keys(EMPTY_COUNTS) as (keyof WeekCounts)[]) counts[key] += patch[key] ?? 0
  const seen = [...week.seen]
  for (const hash of patch.seen ?? []) {
    if (seen.includes(hash)) continue
    seen.push(hash)
    counts.new_addresses += 1
  }
  // The oldest hashes go first; only the count they left behind stays.
  if (seen.length > SEEN_CAP) seen.splice(0, seen.length - SEEN_CAP)
  return { start: week.start, counts, seen }
}

/** True when there is nothing to write. */
export function nothingCaught(patch: WeekPatch): boolean {
  const counted = (Object.keys(EMPTY_COUNTS) as (keyof WeekCounts)[]).some((key) => (patch[key] ?? 0) > 0)
  return !counted && (patch.seen ?? []).length === 0
}

/** Whether anything at all happened this week: an empty week gets no card. */
export function anythingCaught(counts: WeekCounts): boolean {
  return Object.values(counts).some((n) => n > 0)
}

// ------------------------------------------------------------------ words

/** One thing counted, in the words the card uses. [one] is the singular. */
interface Line {
  key: keyof WeekCounts
  one: string
  many: string
}

/**
 * The order the card and the list read in. The sends lead: what Heylana checked before
 * anyone signed, and what it stopped, are what it is for — a count of questions answered
 * is the least of it, so it comes last.
 */
export const LINES: Line[] = [
  { key: 'sends_prepared', one: 'send checked', many: 'sends checked' },
  { key: 'sends_stopped', one: 'stopped before signing', many: 'stopped before signing' },
  { key: 'transactions', one: 'transaction explained', many: 'transactions explained' },
  { key: 'screens', one: 'screen explained', many: 'screens explained' },
  { key: 'new_addresses', one: 'new address looked up', many: 'new addresses looked up' },
  { key: 'lessons', one: 'lesson finished', many: 'lessons finished' },
  { key: 'questions', one: 'question answered', many: 'questions answered' },
]

/** "14 screens explained" — a number and what it counts, and never anything else. */
export function phrase(line: Line, count: number): string {
  return `${count} ${count === 1 ? line.one : line.many}`
}

/**
 * The card's one sentence: the three biggest things, in the order above.
 * "This week: 14 screens explained, 2 sends checked, 1 stopped before signing."
 */
export function cardLine(counts: WeekCounts, most = 3): string {
  const shown = LINES.filter((line) => counts[line.key] > 0).slice(0, most)
  if (shown.length === 0) return 'This week: nothing yet.'
  return `This week: ${shown.map((line) => phrase(line, counts[line.key])).join(', ')}.`
}

/** Everything counted, for the list behind the card: a label and a number, nothing else. */
export function breakdown(counts: WeekCounts): { key: string; label: string; count: number }[] {
  return LINES.filter((line) => counts[line.key] > 0).map((line) => ({
    key: line.key,
    label: count1(line, counts[line.key]),
    count: counts[line.key],
  }))
}

const count1 = (line: Line, count: number) => (count === 1 ? line.one : line.many)
