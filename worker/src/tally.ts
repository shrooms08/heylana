/**
 * Counters, written in batches, and never in a request's way.
 *
 * Every request used to write its counters straight to KV: the day's cap for its route, the
 * month's talks, the day's usage record, the week's card — three to five writes for one
 * question. Cloudflare's free KV allows 1,000 writes a day, and on Sunday 20 September they
 * ran out mid-afternoon. Worse, the cap write came before the route's own error handling, so
 * once KV refused writes every request answered 500 — the product stopped because the
 * bookkeeping could not keep up.
 *
 * Two rules now:
 *
 * 1. **Accounting never breaks a request.** Every counter read and write goes through
 *    [safely]: a failure is logged as `{"route":"accounting",…}` and swallowed, and the request
 *    carries on. A cap that cannot be read is treated as not reached.
 *
 * 2. **Counters are kept in memory and written at most once a minute per isolate.** A request
 *    adds to this isolate's pending counts; at the end of a request, if the last flush was
 *    [tallyLimits.everyMs] ago or more, everything pending is written — one KV write per key
 *    touched, however many requests touched it — on `waitUntil`, after the answer has gone.
 *    A cap or a talk count is checked as what KV holds **plus** what is pending here, so
 *    within an isolate the limits stay exact.
 *
 * What this costs: counts pending in an isolate that goes cold before its next flush are lost
 * (at most the last minute of one isolate's traffic), and two isolates flushing the same key
 * together can lose an increment — near enough for budget protection and a cost estimate, which
 * is all these are. A count whose write fails is kept and tried again at the next flush, so
 * while KV refuses writes the caps are still enforced from memory.
 */

/** Mutable so tests can flush on every request; nothing else changes it. */
export const tallyLimits = { everyMs: 60_000 }

/** The one piece of KV the tally needs. */
export interface TallyStore {
  get(key: string): Promise<string | null>
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<unknown>
}

/** A request's log, handed in so this module never writes a line of its own format. */
export type Log = (fields: Record<string, unknown>) => void

/**
 * [work], with any failure logged and swallowed. What failed is named (`what`), never what
 * it was writing.
 */
export async function safely<T>(what: string, log: Log, work: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await work()
  } catch (error) {
    log({ route: 'accounting', what, error: String((error as Error)?.message ?? error).slice(0, 80) })
    return fallback
  }
}

/** Numbers added to a key: a plain number (`field` ''), or fields of a JSON object. */
interface Counts {
  ttl: number
  fields: Map<string, number>
}

/** Changes to a JSON record, applied in order to whatever KV holds at the flush. */
interface Updates {
  ttl: number
  load: (raw: string | null) => unknown
  steps: ((value: any) => any)[]
  /** Checked once at the flush; false writes nothing (the week's card while memory is off). */
  guard?: () => Promise<boolean>
}

/** The most changes one record keeps waiting; past it the oldest go (a cost estimate, not a ledger). */
const MAX_STEPS = 500

class Tally {
  counts = new Map<string, Counts>()
  updates = new Map<string, Updates>()
  /** Counts being written right now: still counted, so a check in the gap is not short. */
  inFlight = new Map<string, Counts>()
  lastFlush = Number.NEGATIVE_INFINITY

  /** Adds [by] to [field] of [key] ('' for a key holding a plain number). */
  add(key: string, field: string, by: number, ttl: number): void {
    const entry = this.counts.get(key) ?? { ttl, fields: new Map<string, number>() }
    entry.fields.set(field, (entry.fields.get(field) ?? 0) + by)
    this.counts.set(key, entry)
  }

  /** What is added to [field] of [key] here and not yet in KV. */
  pending(key: string, field: string): number {
    return (this.counts.get(key)?.fields.get(field) ?? 0) + (this.inFlight.get(key)?.fields.get(field) ?? 0)
  }

  update(key: string, ttl: number, load: Updates['load'], step: Updates['steps'][number], guard?: Updates['guard']): void {
    const entry = this.updates.get(key) ?? { ttl, load, steps: [], guard }
    entry.steps.push(step)
    if (entry.steps.length > MAX_STEPS) entry.steps.shift()
    this.updates.set(key, entry)
  }

  /** Drops anything pending for [key]: memory off or Wipe all must not be undone by a flush. */
  forget(key: string): void {
    this.counts.delete(key)
    this.updates.delete(key)
  }

  isEmpty(): boolean {
    return this.counts.size === 0 && this.updates.size === 0
  }

  /**
   * Everything pending, one write per key. The pending maps are swapped out before the first
   * await, so a request landing meanwhile adds to fresh ones.
   */
  async flush(store: TallyStore, log: Log): Promise<{ writes: number; failed: number }> {
    const counts = this.counts
    const updates = this.updates
    this.counts = new Map()
    this.updates = new Map()
    for (const [key, entry] of counts) this.inFlight.set(key, entry)
    let writes = 0
    let failed = 0

    for (const [key, entry] of counts) {
      const ok = await safely('flush_count', log, async () => {
        const raw = await store.get(key)
        let value: string
        if (entry.fields.size === 1 && entry.fields.has('')) {
          value = String(Number(raw ?? '0') + entry.fields.get('')!)
        } else {
          const record = parseRecord(raw)
          for (const [field, by] of entry.fields) record[field] = Number(record[field] ?? 0) + by
          value = JSON.stringify(record)
        }
        await store.put(key, value, { expirationTtl: entry.ttl })
        return true
      }, false)
      this.inFlight.delete(key)
      if (ok) {
        writes++
      } else {
        // Kept for the next flush: while KV refuses writes, the limits hold from memory.
        failed++
        for (const [field, by] of entry.fields) this.add(key, field, by, entry.ttl)
      }
    }

    for (const [key, entry] of updates) {
      const ok = await safely('flush_record', log, async () => {
        if (entry.guard && !(await entry.guard())) return true
        let value = entry.load(await store.get(key))
        for (const step of entry.steps) value = step(value)
        await store.put(key, JSON.stringify(value), { expirationTtl: entry.ttl })
        writes++
        return true
      }, false)
      // An estimate's changes are let go rather than piled up while KV is refusing them.
      if (!ok) failed++
    }
    return { writes, failed }
  }
}

function parseRecord(raw: string | null): Record<string, unknown> {
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

/** This isolate's pending counters. */
export let tally = new Tally()

/** A clean slate: for tests, which each bring their own KV. */
export function resetTally(): void {
  tally = new Tally()
}

/**
 * Called at the end of every request: writes everything pending if the last flush was a
 * minute ago or more. The write runs on [waitUntil] when there is one, after the answer.
 */
export function flushIfDue(
  store: TallyStore,
  now: number,
  log: Log,
  waitUntil?: (work: Promise<unknown>) => void,
): Promise<unknown> | null {
  if (tally.isEmpty() || now - tally.lastFlush < tallyLimits.everyMs) return null
  tally.lastFlush = now
  const work = tally.flush(store, log).then((done) => {
    if (done.failed > 0) log({ route: 'accounting', what: 'flush', writes: done.writes, failed: done.failed })
  })
  if (waitUntil) {
    waitUntil(work)
    return null
  }
  return work
}
