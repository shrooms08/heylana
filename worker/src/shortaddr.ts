/**
 * Shortened addresses on a signing screen, matched against addresses this user
 * already knows.
 *
 * The Wallet and Seed Vault print addresses as 7c2y…SxSv. That cannot be looked
 * up, but it can be recognised: the worker compares it with the treasury, the
 * user's own wallet and its token accounts, the addresses they typed since the
 * buddy started, and — only if nothing else matched — the counterparties of their
 * last 20 transactions. Exactly one match gives a name; anything else is said to
 * be unverifiable, never guessed.
 */
import { isAddress } from './base58.ts'
import { KNOWN, TOKEN_2022_PROGRAM, TOKEN_PROGRAM } from './solana.ts'
import type { ToolContext } from './tools.ts'

const SHORT = /^([1-9A-HJ-NP-Za-km-z]{4,8})(?:…|\.\.\.)([1-9A-HJ-NP-Za-km-z]{4,8})$/
const MAX_SHORT = 3
const MAX_TYPED = 20
const RECENT = 20
const COUNTERPARTY_TTL_SECONDS = 600

export interface ShortCheck {
  short: string
  label: string | null
  /** How many known addresses it could be: 1 is a match, 0 or more is not. */
  candidates: number
}

interface Store {
  get(key: string): Promise<string | null>
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>
}

export function matchesShort(short: string, full: string): boolean {
  const parts = SHORT.exec(short)
  if (!parts) return false
  return full.length >= parts[1].length + parts[2].length && full.startsWith(parts[1]) && full.endsWith(parts[2])
}

export async function checkShortAddresses(
  shorts: unknown,
  typed: unknown,
  context: ToolContext,
  store?: Store,
): Promise<ShortCheck[]> {
  const wanted = (Array.isArray(shorts) ? shorts : []).map(String).filter((s) => SHORT.test(s)).slice(0, MAX_SHORT)
  if (wanted.length === 0) return []

  // First source wins: the treasury is named as the treasury even if it was typed too.
  const known = new Map<string, string>()
  const add = (address: string, label: string) => {
    if (isAddress(address) && !known.has(address)) known.set(address, label)
  }
  if (context.treasury) add(context.treasury, 'your Heylana treasury')
  if (context.wallet) {
    add(context.wallet, 'your own wallet')
    for (const account of await ownTokenAccounts(context.wallet, context)) add(account.address, account.label)
  }
  for (const address of (Array.isArray(typed) ? typed : []).map(String).slice(0, MAX_TYPED)) {
    add(address, 'the address you typed earlier')
  }

  let checks = wanted.map((short) => check(short, known))
  if (context.wallet && checks.some((c) => c.candidates === 0)) {
    for (const address of await recentCounterparties(context.wallet, context, store)) {
      add(address, KNOWN[address]?.label ?? 'someone you sent to or received from recently')
    }
    checks = wanted.map((short) => check(short, known))
  }
  return checks
}

function check(short: string, known: Map<string, string>): ShortCheck {
  const hits = [...known.entries()].filter(([address]) => matchesShort(short, address))
  return { short, label: hits.length === 1 ? hits[0][1] : null, candidates: hits.length }
}

/** What goes to the model, one line per shortened address. */
export function checkLines(checks: ShortCheck[]): string {
  return checks
    .map((c) =>
      c.label
        ? `Address check: ${c.short} is ${c.label}.`
        : c.candidates > 1
          ? `Address check: ${c.short} could be more than one address, so it cannot be verified from here.`
          : `Address check: ${c.short} cannot be verified from here.`,
    )
    .join('\n')
}

/** The checks, appended to the user's message, so the model reads them with the question. */
export function withAddressChecks(messages: unknown, lines: string): unknown {
  if (!Array.isArray(messages) || messages.length === 0 || !lines) return messages
  const copy = [...messages]
  const last = copy[copy.length - 1]
  if (last?.role !== 'user' || typeof last.content !== 'string') return messages
  copy[copy.length - 1] = { ...last, content: `${last.content}\n\n${lines}` }
  return copy
}

async function ownTokenAccounts(wallet: string, context: ToolContext): Promise<{ address: string; label: string }[]> {
  const results = await Promise.all(
    [TOKEN_PROGRAM, TOKEN_2022_PROGRAM].map((programId) =>
      context.rpc('getTokenAccountsByOwner', [wallet, { programId }, { encoding: 'jsonParsed' }], { signal: context.signal }).catch(() => null),
    ),
  )
  return results.flatMap((result) =>
    (result?.value ?? []).map((account: any) => ({
      address: String(account?.pubkey ?? ''),
      label: `your own ${KNOWN[account?.account?.data?.parsed?.info?.mint]?.symbol ?? 'token'} account`,
    })),
  )
}

async function recentCounterparties(wallet: string, context: ToolContext, store?: Store): Promise<string[]> {
  const key = `counterparties:${wallet}`
  try {
    const cached = store ? await store.get(key) : null
    if (cached) return JSON.parse(cached)
  } catch {
    // A cache that cannot be read is just a cache miss.
  }

  const signatures = await context.rpc('getSignaturesForAddress', [wallet, { limit: RECENT }], { signal: context.signal }).catch(() => [])
  const transactions = await Promise.all(
    (Array.isArray(signatures) ? signatures : []).slice(0, RECENT).map((entry: any) =>
      context
        .rpc(
          'getTransaction',
          [entry.signature, { encoding: 'jsonParsed', maxSupportedTransactionVersion: 0, commitment: 'confirmed' }],
          { signal: context.signal },
        )
        .catch(() => null),
    ),
  )
  const found = new Set<string>()
  for (const tx of transactions) {
    if (!tx) continue
    for (const k of tx.transaction?.message?.accountKeys ?? []) {
      const address = typeof k === 'string' ? k : k?.pubkey
      if (address && address !== wallet && !KNOWN[address]) found.add(address)
    }
    for (const balance of [...(tx.meta?.preTokenBalances ?? []), ...(tx.meta?.postTokenBalances ?? [])]) {
      if (balance?.owner && balance.owner !== wallet) found.add(balance.owner)
    }
  }
  const list = [...found]
  try {
    if (store) await store.put(key, JSON.stringify(list), { expirationTtl: COUNTERPARTY_TTL_SECONDS })
  } catch {
    // Not cached this time; nothing else changes.
  }
  return list
}
