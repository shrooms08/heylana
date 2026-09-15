/**
 * Small Solana facts the worker's tools share: well-known addresses, units, and
 * JSON-RPC and price calls that can be cancelled. Nothing here holds a key: the
 * RPC address arrives from the caller, and it is a secret.
 */
import { JUPITER_PRICE_URL, TOKEN_2022_PROGRAM, TOKEN_PROGRAM } from './pay.ts'

export { TOKEN_PROGRAM, TOKEN_2022_PROGRAM }

export const SYSTEM_PROGRAM = '11111111111111111111111111111111'
export const COMPUTE_BUDGET_PROGRAM = 'ComputeBudget111111111111111111111111111111'
export const BPF_UPGRADEABLE_LOADER = 'BPFLoaderUpgradeab1e11111111111111111111111'
export const WSOL_MINT = 'So11111111111111111111111111111111111111112'
export const USDC_MAINNET = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v'
export const USDC_DEVNET = '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU'
export const SKR_MAINNET = 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3'
export const SOL_DECIMALS = 9

export interface Known {
  label: string
  kind: 'program' | 'mint'
  symbol?: string
  /** A test token priced as its real one: devnet USDC is worth what USDC is. */
  priceMint?: string
}

/**
 * Addresses with a name everyone uses. Wallet apps such as Phantom have no
 * address of their own, so they are not here: a wallet is just an address.
 */
export const KNOWN: Record<string, Known> = {
  [SYSTEM_PROGRAM]: { label: 'System Program', kind: 'program' },
  [TOKEN_PROGRAM]: { label: 'SPL Token Program', kind: 'program' },
  [TOKEN_2022_PROGRAM]: { label: 'Token-2022 Program', kind: 'program' },
  ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL: { label: 'Associated Token Account Program', kind: 'program' },
  [COMPUTE_BUDGET_PROGRAM]: { label: 'Compute Budget Program', kind: 'program' },
  JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4: { label: 'Jupiter Aggregator v6', kind: 'program' },
  KLend2g3cP87fffoy8q1mQqGKjrxjC8boSyAYavgmjD: { label: 'Kamino Lending', kind: 'program' },
  MarBmsSgKXdrN1egZf5sqe1TMai9K1rChYNDJgjq7aD: { label: 'Marinade Finance', kind: 'program' },
  '675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8': { label: 'Raydium AMM v4', kind: 'program' },
  CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK: { label: 'Raydium Concentrated Liquidity', kind: 'program' },
  whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc: { label: 'Orca Whirlpools', kind: 'program' },
  [WSOL_MINT]: { label: 'Wrapped SOL', kind: 'mint', symbol: 'SOL' },
  [USDC_MAINNET]: { label: 'USD Coin (USDC)', kind: 'mint', symbol: 'USDC' },
  [USDC_DEVNET]: { label: 'USD Coin on devnet (test USDC)', kind: 'mint', symbol: 'USDC', priceMint: USDC_MAINNET },
  Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB: { label: 'Tether USD (USDT)', kind: 'mint', symbol: 'USDT' },
  [SKR_MAINNET]: { label: 'Seeker (SKR)', kind: 'mint', symbol: 'SKR' },
}

/** Symbol to mainnet mint, for prices. Test tokens are never looked up by symbol. */
export const SYMBOL_TO_MINT: Record<string, string> = Object.fromEntries(
  Object.entries(KNOWN)
    .filter(([, known]) => known.kind === 'mint' && known.symbol && !known.priceMint)
    .map(([mint, known]) => [known.symbol!.toUpperCase(), mint]),
)

/** "7c2y…nSxSv" is enough to recognise an address and never enough to misuse one. */
export function short(address: string): string {
  return address.length > 10 ? `${address.slice(0, 4)}…${address.slice(-4)}` : address
}

export function abs(value: bigint): bigint {
  return value < 0n ? -value : value
}

/** Base units to a plain decimal string, exactly: 50000 at 6 decimals is "0.05". */
export function unitsToDecimal(units: bigint, decimals: number): string {
  const negative = units < 0n
  const whole = abs(units) / 10n ** BigInt(decimals)
  const fraction = (abs(units) % 10n ** BigInt(decimals)).toString().padStart(decimals, '0').replace(/0+$/, '')
  return `${negative ? '-' : ''}${whole}${fraction ? `.${fraction}` : ''}`
}

export function isoDay(unixSeconds: number): string {
  return new Date(unixSeconds * 1000).toISOString().slice(0, 10)
}

/** A JSON-RPC call that stops when [signal] does. Errors never carry the URL. */
export async function rpcCall(url: string, method: string, params: unknown, signal?: AbortSignal): Promise<any> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
    signal,
  })
  const body: any = await res.json()
  if (body?.error) throw new Error(`rpc ${method}: ${body.error.code}`)
  return body?.result
}

/** USD prices for up to 50 mints in one call. A mint with no price is left out. */
export async function jupiterPrices(mints: string[], apiKey: string | undefined, signal?: AbortSignal): Promise<Record<string, number>> {
  const unique = [...new Set(mints)].slice(0, 50)
  if (unique.length === 0) return {}
  const headers: Record<string, string> = {}
  if (apiKey) headers['x-api-key'] = apiKey
  const res = await fetch(`${JUPITER_PRICE_URL}?ids=${unique.join(',')}`, { headers, signal })
  if (!res.ok) throw new Error(`price ${res.status}`)
  const body: any = await res.json()
  const prices: Record<string, number> = {}
  for (const mint of unique) {
    const price = Number(body?.[mint]?.usdPrice)
    if (price > 0) prices[mint] = price
  }
  return prices
}
