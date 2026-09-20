/**
 * What Heylana costs to run, counted by day.
 *
 * One record a day in KV (`usage:2026-09-20`), holding counts only: how many wallets were
 * active (hashed, so they are counted and never listed), how many questions went to which
 * model and the tokens they used, how many characters were spoken by which voice, how many
 * listening sessions each ear opened, how many chain calls each RPC provider served and how
 * long they took, how many sends were prepared and confirmed, and how many Pro payments
 * landed and for how much. A price sheet turns those into an estimated cost.
 *
 * Nothing here is personal: no wallet address, no device id, no words. The endpoint that
 * reads it ([summarise]) returns the same counts and never the hashes.
 */

export const USAGE_PREFIX = 'usage:'
/** Kept a little longer than the longest window the endpoint offers. */
export const USAGE_TTL_SECONDS = 120 * 24 * 60 * 60
/** The most latency samples kept for one provider and method in a day. */
export const SAMPLE_CAP = 200

export interface LatencySample {
  /** Milliseconds the call took. */
  ms: number
  /** When it happened, in whole minutes since the epoch, so a window can be cut by time. */
  at: number
}

export interface DayUsage {
  date: string
  /** Hashed wallet ids: counted, never shown. */
  wallets: string[]
  /** Questions that reached the model, by the model that answered. */
  chat: Record<string, number>
  tokens_in: number
  tokens_out: number
  /** Characters spoken, by voice provider. */
  tts: Record<string, number>
  /** Listening sessions opened, by ear. The worker mints the pass; the phone holds the socket. */
  ears: Record<string, number>
  /** Chain calls served, by provider. */
  rpc: Record<string, number>
  /** Their milliseconds, by provider. */
  rpc_ms: Record<string, number>
  /** Samples for the percentiles, keyed `provider|method`. */
  latency: Record<string, LatencySample[]>
  sends_prepared: number
  sends_confirmed: number
  pro_payments: number
  /** What those payments came to, in US dollars. */
  pro_usd: number
}

export function emptyDay(date: string): DayUsage {
  return {
    date,
    wallets: [],
    chat: {},
    tokens_in: 0,
    tokens_out: 0,
    tts: {},
    ears: {},
    rpc: {},
    rpc_ms: {},
    latency: {},
    sends_prepared: 0,
    sends_confirmed: 0,
    pro_payments: 0,
    pro_usd: 0,
  }
}

/** What one request added. Every field is optional: a request counts only what it did. */
export interface UsagePatch {
  wallet?: string | null
  chatModel?: string
  tokensIn?: number
  tokensOut?: number
  tts?: { provider: string; chars: number }
  ears?: { provider: string }
  rpc?: { provider: string; method: string; ms: number }[]
  sendsPrepared?: number
  sendsConfirmed?: number
  proPayments?: number
  proUsd?: number
}

const add = (into: Record<string, number>, key: string, by: number) => {
  into[key] = (into[key] ?? 0) + by
}

/** [patch] folded into [day]. Pure: the caller stores what comes back. */
export function apply(day: DayUsage, patch: UsagePatch, hashedWallet?: string | null, atMinute = 0): DayUsage {
  const next: DayUsage = {
    ...day,
    wallets: [...day.wallets],
    chat: { ...day.chat },
    tts: { ...day.tts },
    ears: { ...day.ears },
    rpc: { ...day.rpc },
    rpc_ms: { ...day.rpc_ms },
    latency: Object.fromEntries(Object.entries(day.latency).map(([k, v]) => [k, [...v]])),
  }
  if (hashedWallet && !next.wallets.includes(hashedWallet)) next.wallets.push(hashedWallet)
  if (patch.chatModel) add(next.chat, patch.chatModel, 1)
  next.tokens_in += patch.tokensIn ?? 0
  next.tokens_out += patch.tokensOut ?? 0
  if (patch.tts) add(next.tts, patch.tts.provider, patch.tts.chars)
  if (patch.ears) add(next.ears, patch.ears.provider, 1)
  for (const call of patch.rpc ?? []) {
    add(next.rpc, call.provider, 1)
    add(next.rpc_ms, call.provider, call.ms)
    const key = `${call.provider}|${call.method}`
    const samples = next.latency[key] ?? (next.latency[key] = [])
    samples.push({ ms: call.ms, at: atMinute })
    // A bounded reservoir: the newest are kept, so a busy day cannot grow the record.
    if (samples.length > SAMPLE_CAP) samples.splice(0, samples.length - SAMPLE_CAP)
  }
  next.sends_prepared += patch.sendsPrepared ?? 0
  next.sends_confirmed += patch.sendsConfirmed ?? 0
  next.pro_payments += patch.proPayments ?? 0
  next.pro_usd = Math.round((next.pro_usd + (patch.proUsd ?? 0)) * 100) / 100
  return next
}

/** True when the patch has anything worth a write. */
export function isEmpty(patch: UsagePatch): boolean {
  return !(
    patch.wallet ||
    patch.chatModel ||
    patch.tokensIn ||
    patch.tokensOut ||
    patch.tts ||
    patch.ears ||
    (patch.rpc && patch.rpc.length > 0) ||
    patch.sendsPrepared ||
    patch.sendsConfirmed ||
    patch.proPayments
  )
}

// ------------------------------------------------------------------ prices

/**
 * What each thing costs, as the operator entered it in `PRICES` (a JSON var). Models are
 * dollars per million tokens, the voice per million characters, the ears per minute, and
 * RPC is free on both plans in use. These are figures to keep up to date by hand — the
 * endpoint echoes the sheet back with the estimate so nobody reads it as a bill.
 */
export interface PriceSheet {
  models: Record<string, { in: number; out: number }>
  /** Dollars per million characters spoken. */
  tts: Record<string, number>
  /** Dollars per minute of listening. */
  ears: Record<string, number>
  /** Dollars per million chain calls. */
  rpc: Record<string, number>
  /** How long one listening session is taken to be, in seconds: the worker mints passes, it does not hear. */
  ears_session_seconds: number
}

export const DEFAULT_PRICES: PriceSheet = {
  models: {
    'claude-haiku-4-5-20251001': { in: 1, out: 5 },
    'claude-sonnet-5': { in: 3, out: 15 },
  },
  tts: { deepgram: 30, gemini: 30, cartesia: 65 },
  ears: { deepgram: 0.0077, assemblyai: 0.0025 },
  rpc: { rpcfast: 0, helius: 0, devnet: 0 },
  ears_session_seconds: 8,
}

export function priceSheet(raw?: string): PriceSheet {
  if (!raw) return DEFAULT_PRICES
  try {
    const given = JSON.parse(raw)
    return {
      models: { ...DEFAULT_PRICES.models, ...(given.models ?? {}) },
      tts: { ...DEFAULT_PRICES.tts, ...(given.tts ?? {}) },
      ears: { ...DEFAULT_PRICES.ears, ...(given.ears ?? {}) },
      rpc: { ...DEFAULT_PRICES.rpc, ...(given.rpc ?? {}) },
      ears_session_seconds: given.ears_session_seconds ?? DEFAULT_PRICES.ears_session_seconds,
    }
  } catch {
    return DEFAULT_PRICES
  }
}

export interface DayCost {
  model: number
  tts: number
  ears: number
  rpc: number
  total: number
}

const money = (n: number) => Math.round(n * 10_000) / 10_000

/**
 * What a day cost, near enough to watch a budget by. Model tokens are priced per model;
 * the voice per character; the ears per minute, from sessions at the sheet's assumed
 * length (the worker never hears the audio, so that part is an estimate, not a count).
 */
export function costOf(day: DayUsage, prices: PriceSheet): DayCost {
  let model = 0
  const calls = Object.values(day.chat).reduce((sum, n) => sum + n, 0)
  for (const [name, count] of Object.entries(day.chat)) {
    const price = prices.models[name] ?? { in: 0, out: 0 }
    // Tokens are counted for the day, not per model: they are split by each model's share of the calls.
    const share = calls > 0 ? count / calls : 0
    model += ((day.tokens_in * share) / 1_000_000) * price.in + ((day.tokens_out * share) / 1_000_000) * price.out
  }
  let tts = 0
  for (const [provider, chars] of Object.entries(day.tts)) tts += (chars / 1_000_000) * (prices.tts[provider] ?? 0)
  let ears = 0
  for (const [provider, sessions] of Object.entries(day.ears)) {
    ears += ((sessions * prices.ears_session_seconds) / 60) * (prices.ears[provider] ?? 0)
  }
  let rpc = 0
  for (const [provider, count] of Object.entries(day.rpc)) rpc += (count / 1_000_000) * (prices.rpc[provider] ?? 0)
  const total = model + tts + ears + rpc
  return { model: money(model), tts: money(tts), ears: money(ears), rpc: money(rpc), total: money(total) }
}

// ---------------------------------------------------------------- reading

export interface UsageReport {
  from: string
  to: string
  prices: PriceSheet
  days: (Omit<DayUsage, 'wallets' | 'latency'> & { wallets: number; cost: DayCost })[]
  totals: {
    wallets: number
    chat: Record<string, number>
    tokens_in: number
    tokens_out: number
    tts: Record<string, number>
    ears: Record<string, number>
    rpc: Record<string, number>
    sends_prepared: number
    sends_confirmed: number
    pro_payments: number
    pro_usd: number
    cost: DayCost
  }
  notes: string[]
}

/** The days, their costs, the totals and the last day's latencies. Wallets are counted, never listed. */
export function summarise(days: DayUsage[], prices: PriceSheet): UsageReport {
  const ordered = [...days].sort((a, b) => a.date.localeCompare(b.date))
  const wallets = new Set<string>()
  const totals = {
    wallets: 0,
    chat: {} as Record<string, number>,
    tokens_in: 0,
    tokens_out: 0,
    tts: {} as Record<string, number>,
    ears: {} as Record<string, number>,
    rpc: {} as Record<string, number>,
    sends_prepared: 0,
    sends_confirmed: 0,
    pro_payments: 0,
    pro_usd: 0,
    cost: { model: 0, tts: 0, ears: 0, rpc: 0, total: 0 } as DayCost,
  }
  const shown = ordered.map((day) => {
    const cost = costOf(day, prices)
    for (const w of day.wallets) wallets.add(w)
    for (const [k, v] of Object.entries(day.chat)) add(totals.chat, k, v)
    for (const [k, v] of Object.entries(day.tts)) add(totals.tts, k, v)
    for (const [k, v] of Object.entries(day.ears)) add(totals.ears, k, v)
    for (const [k, v] of Object.entries(day.rpc)) add(totals.rpc, k, v)
    totals.tokens_in += day.tokens_in
    totals.tokens_out += day.tokens_out
    totals.sends_prepared += day.sends_prepared
    totals.sends_confirmed += day.sends_confirmed
    totals.pro_payments += day.pro_payments
    totals.pro_usd = money(totals.pro_usd + day.pro_usd)
    for (const part of ['model', 'tts', 'ears', 'rpc', 'total'] as const) totals.cost[part] = money(totals.cost[part] + cost[part])
    const { wallets: theirs, latency, ...rest } = day
    return { ...rest, wallets: theirs.length, cost }
  })
  totals.wallets = wallets.size
  return {
    from: ordered[0]?.date ?? '',
    to: ordered[ordered.length - 1]?.date ?? '',
    prices,
    days: shown,
    totals,
    notes: [
      'Costs are estimates from the PRICES sheet above, not a bill.',
      'Ears are counted as sessions: the worker mints the pass and never hears the audio, so seconds are sessions × ears_session_seconds.',
      'Wallets are counted by a salted hash and never stored or returned as addresses.',
      'Counters are written once per request; two requests landing together can lose one increment.',
    ],
  }
}

/** The day a moment belongs to, in UTC. */
export function dayOf(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

/** The [days] day keys ending today, newest last. */
export function daysBack(nowMs: number, days: number): string[] {
  const out: string[] = []
  for (let i = days - 1; i >= 0; i--) out.push(dayOf(nowMs - i * 86_400_000))
  return out
}
