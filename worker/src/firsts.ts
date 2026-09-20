/**
 * "First time you have sent to this address."
 *
 * The most useful thing Heylana can say about an address is often not what it is, but
 * whether the user has ever dealt with it before — a fresh destination on a send they did
 * not start themselves, or a program they have never used, is the shape most drains take.
 *
 * So the worker keeps two small sets per wallet: destinations sent to, and programs
 * interacted with. **Neither is kept in plain form.** Each one is a six-byte salted hash,
 * salted with the worker's own secret, so the record cannot be read back into addresses
 * even by whoever holds it, and cannot be compared across wallets. Nothing is written
 * while memory is off, and turning memory off throws it away with everything else.
 *
 * It answers one question only — have I seen this before — and never how often or when.
 */

export const FIRSTS_PREFIX = 'firsts:'
/** A year: long enough that "first time" means something, short enough to expire. */
export const FIRSTS_TTL_SECONDS = 365 * 24 * 60 * 60
/** The most hashes kept of each kind, oldest dropped first, so the record cannot grow. */
export const FIRSTS_CAP = 400

export type FirstKind = 'destination' | 'program'

export interface Firsts {
  /** Salted hashes of addresses this wallet has sent to. */
  destinations: string[]
  /** Salted hashes of programs this wallet's transactions have called. */
  programs: string[]
}

export const NO_FIRSTS: Firsts = { destinations: [], programs: [] }

export function firstsKey(wallet: string): string {
  return `${FIRSTS_PREFIX}${wallet}`
}

/** Six bytes of SHA-256 over the worker's secret and the address: enough to tell apart, never to read back. */
export async function hashFirst(value: string, secret: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${secret}:first:${value}`))
  return [...new Uint8Array(digest).slice(0, 6)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

export function parseFirsts(text: string | null): Firsts {
  if (!text) return { ...NO_FIRSTS }
  try {
    const parsed = JSON.parse(text)
    return {
      destinations: Array.isArray(parsed?.destinations) ? parsed.destinations.filter(isHash) : [],
      programs: Array.isArray(parsed?.programs) ? parsed.programs.filter(isHash) : [],
    }
  } catch {
    return { ...NO_FIRSTS }
  }
}

const isHash = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{12}$/.test(value)

/** [hashes] folded in, the oldest dropped past the cap. Pure: the caller stores what comes back. */
export function remember(firsts: Firsts, kind: FirstKind, hashes: string[]): Firsts {
  const into = kind === 'destination' ? 'destinations' : 'programs'
  const kept = [...firsts[into]]
  for (const hash of hashes) {
    if (!isHash(hash) || kept.includes(hash)) continue
    kept.push(hash)
  }
  if (kept.length > FIRSTS_CAP) kept.splice(0, kept.length - FIRSTS_CAP)
  return { ...firsts, [into]: kept }
}

/** Which of [hashes] this wallet has not seen before, in the order given. */
export function unseen(firsts: Firsts, kind: FirstKind, hashes: string[]): string[] {
  const kept = kind === 'destination' ? firsts.destinations : firsts.programs
  return hashes.filter((hash) => !kept.includes(hash))
}

// ------------------------------------------------------------------ the words

/** The one line a send's strip gets when its destination is new to this wallet. */
export const FIRST_DESTINATION = 'First time you have sent to this address.'

/** The one line a signing screen gets when it names a program this wallet has not used. */
export const FIRST_PROGRAM = 'You have not used this program before.'

/**
 * The lines for what was found, in the order they matter. Facts, never a verdict: the
 * line says what is true of this wallet's own history and stops there.
 */
export function firstLines(options: { newDestination?: boolean; newPrograms?: number }): string[] {
  const lines: string[] = []
  if (options.newDestination) lines.push(FIRST_DESTINATION)
  if (options.newPrograms && options.newPrograms > 0) lines.push(FIRST_PROGRAM)
  return lines
}
