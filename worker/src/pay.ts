/**
 * Paying for Pro, and checking on-chain that it was paid.
 *
 * A quote says exactly what to send: which token, how many base units, to whom,
 * and a fresh reference address to put in the transaction as a read-only account
 * so the payment can be tied to this quote. The phone builds the transfer, Seed
 * Vault signs it, and the wallet sends it. Then the worker reads the transaction
 * back from the chain and checks every part of it before anything is unlocked.
 *
 * Nothing here holds or moves funds, and nothing trusts the phone's word for it.
 */
import { encodeBase58 } from './base58.ts'
import type { Rpc } from './rpc.ts'

export const TOKEN_PROGRAM = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
export const TOKEN_2022_PROGRAM = 'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb'
export const USDC_DECIMALS = 6
export const QUOTE_TTL_MS = 10 * 60 * 1000
export const JUPITER_PRICE_URL = 'https://api.jup.ag/price/v3'

export type Currency = 'usdc' | 'skr'

export interface Quote {
  currency: Currency
  mint: string
  /** Base units, as a decimal string: token amounts outgrow JavaScript numbers. */
  amount: string
  decimals: number
  token_program: string
  treasury: string
  reference: string
  expires_at: string
  /** The wallet the quote was made for; only it can confirm the payment. */
  pubkey: string
}

/** "0.10" at 6 decimals is 100000. Exact: no floating point anywhere near money. */
export function decimalToUnits(amount: string, decimals: number): bigint {
  const text = String(amount).trim()
  if (!/^\d+(\.\d+)?$/.test(text)) throw new Error(`not an amount: ${text}`)
  const [whole, fraction = ''] = text.split('.')
  if (fraction.length > decimals && /[1-9]/.test(fraction.slice(decimals))) {
    throw new Error(`more precision than ${decimals} decimals: ${text}`)
  }
  return BigInt(whole) * 10n ** BigInt(decimals) + BigInt((fraction + '0'.repeat(decimals)).slice(0, decimals) || '0')
}

/**
 * How many base units of a token cover [priceUsd] at [usdPerToken], rounded up
 * so the treasury is never short. The price is fixed to 12 significant places,
 * which is far finer than a base unit of any token.
 */
export function usdToTokenUnits(priceUsd: string, usdPerToken: number, decimals: number): bigint {
  if (!(usdPerToken > 0) || !Number.isFinite(usdPerToken)) throw new Error('no usable price')
  const PRICE_SCALE = 12
  const price = decimalToUnits(usdPerToken.toFixed(PRICE_SCALE), PRICE_SCALE)
  const cents = decimalToUnits(priceUsd, PRICE_SCALE)
  const numerator = cents * 10n ** BigInt(decimals)
  return (numerator + price - 1n) / price
}

/** A fresh reference: 32 random bytes as an address. It never signs anything. */
export function newReference(): string {
  return encodeBase58(crypto.getRandomValues(new Uint8Array(32)))
}

/** A mint's decimals and which token program owns it, read from the chain. */
export async function mintInfo(rpc: Rpc, mint: string): Promise<{ decimals: number; program: string }> {
  const result = await rpc('getAccountInfo', [mint, { encoding: 'jsonParsed', commitment: 'confirmed' }])
  const value = result?.value
  const decimals = value?.data?.parsed?.info?.decimals
  if (!value || typeof decimals !== 'number') throw new Error('mint not found')
  return { decimals, program: value.owner }
}

/** A token's USD price from Jupiter. Keyless works at a low rate; a key raises it. */
export async function usdPrice(mint: string, apiKey?: string): Promise<number> {
  const headers: Record<string, string> = {}
  if (apiKey) headers['x-api-key'] = apiKey
  const res = await fetch(`${JUPITER_PRICE_URL}?ids=${encodeURIComponent(mint)}`, { headers })
  if (!res.ok) throw new Error(`price ${res.status}`)
  const body: any = await res.json()
  const price = Number(body?.[mint]?.usdPrice)
  if (!(price > 0)) throw new Error('no price for mint')
  return price
}

export type Verdict = { ok: true; amount: string } | { ok: false; reason: string }

const CHECK_ORDER = ['self_payment', 'wrong_mint', 'wrong_destination', 'short_amount', 'wrong_sender'] as const

/**
 * Whether a transaction, as the RPC returned it with jsonParsed encoding, is the
 * payment [quote] asked for. Every condition has to hold on one transfer:
 *
 *  - it succeeded;
 *  - the quote's reference address is one of its accounts;
 *  - it is an SPL transfer or transferChecked of the quoted mint;
 *  - into a token account owned by the treasury;
 *  - of at least the quoted amount;
 *  - out of the wallet the quote was made for.
 */
export function checkPayment(tx: any, quote: Quote): Verdict {
  if (!tx) return { ok: false, reason: 'not_confirmed' }
  if (tx.meta?.err) return { ok: false, reason: 'failed_on_chain' }

  const keys: string[] = (tx.transaction?.message?.accountKeys ?? []).map((k: any) =>
    typeof k === 'string' ? k : k?.pubkey,
  )
  if (!keys.includes(quote.reference)) return { ok: false, reason: 'no_reference' }

  const balances = [...(tx.meta?.preTokenBalances ?? []), ...(tx.meta?.postTokenBalances ?? [])]
  const accountOf = (address: string) =>
    balances.find((b: any) => keys[b.accountIndex] === address) as { mint?: string; owner?: string } | undefined

  const instructions = [
    ...(tx.transaction?.message?.instructions ?? []),
    ...(tx.meta?.innerInstructions ?? []).flatMap((inner: any) => inner.instructions ?? []),
  ]
  const transfers = instructions.filter(
    (ix: any) =>
      (ix?.program === 'spl-token' || ix?.program === 'spl-token-2022') &&
      (ix?.parsed?.type === 'transfer' || ix?.parsed?.type === 'transferChecked'),
  )
  if (transfers.length === 0) return { ok: false, reason: 'no_transfer' }

  let closest: string[] | null = null
  for (const ix of transfers) {
    const info = ix.parsed.info ?? {}
    const destination = accountOf(info.destination)
    const source = accountOf(info.source)
    const mint = info.mint ?? destination?.mint ?? source?.mint
    const amount = BigInt(info.tokenAmount?.amount ?? info.amount ?? '0')
    const sender = info.authority ?? info.multisigAuthority ?? source?.owner

    const failures: string[] = []
    if (mint !== quote.mint) failures.push('wrong_mint')
    if (destination?.owner !== quote.treasury) failures.push('wrong_destination')
    if (amount < BigInt(quote.amount)) failures.push('short_amount')
    if (sender !== quote.pubkey && source?.owner !== quote.pubkey) failures.push('wrong_sender')
    // The treasury paying itself moves nothing, so it buys nothing.
    if (sender === quote.treasury || source?.owner === quote.treasury) failures.push('self_payment')

    if (failures.length === 0) return { ok: true, amount: amount.toString() }
    if (!closest || failures.length < closest.length) closest = failures
  }
  const first = CHECK_ORDER.find((check) => closest!.includes(check))
  return { ok: false, reason: first ?? 'no_transfer' }
}
