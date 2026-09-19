/**
 * The lookups the model may ask for. All of them run here in the worker, never
 * on the phone, and none of them can move anything: prepare_send only checks a
 * send the user asked for, and the user signs it in Seed Vault.
 *
 * Results are small, plain JSON meant to be read by the model. They never carry
 * an RPC address or a key, and wallets are shortened to their ends.
 */
import { isAddress } from './base58.ts'
import { decimalToUnits, mintInfo } from './pay.ts'
import { resolveName } from './names.ts'
import { LOOKUP_TOOLS } from './registry.ts'
import { searchTool, type Kb, type KbResult } from './kb.ts'
import {
  BPF_UPGRADEABLE_LOADER,
  COMPUTE_BUDGET_PROGRAM,
  KNOWN,
  SOL_DECIMALS,
  SYMBOL_TO_MINT,
  SYSTEM_PROGRAM,
  TOKEN_2022_PROGRAM,
  TOKEN_PROGRAM,
  WSOL_MINT,
  abs,
  isoDay,
  jupiterPrices,
  rpcCall,
  short,
  unitsToDecimal,
} from './solana.ts'

export interface ToolContext {
  /** The cluster's RPC. A secret: it never appears in a result. */
  rpcUrl: string
  /** Names live on mainnet whatever CLUSTER says. */
  mainnetRpcUrl?: string
  jupiterKey?: string
  usdcMint: string
  skrMint: string
  cluster: 'mainnet-beta' | 'devnet'
  /** The connected wallet, if the question came with a session. */
  wallet: string | null
  /** Heylana's own treasury, so it can be named when it turns up. */
  treasury?: string
  signal?: AbortSignal
  now: () => number
  /** The knowledge base, when this worker has one bound. */
  kb?: Kb
  /** Counts for the log: how many knowledge-base chunks this question was handed. */
  stats?: { kbHits: number; found?: Map<string, KbResult>; kbError?: string }
}

/**
 * Sent to the model only when Solana knowledge is loaded. Kept short: every word is paid
 * for. The definitions, their schemas and their risk classes live in the registry.
 */
export const TOOL_DEFINITIONS = LOOKUP_TOOLS

type Failure = { error: string; detail?: string }

export async function runTool(name: string, input: any, context: ToolContext): Promise<unknown> {
  try {
    switch (name) {
      case 'get_balances':
        return await getBalances(input?.wallet, context)
      case 'get_price':
        return await getPrice(input?.symbol_or_mint, context)
      case 'explain_address':
        return await explainAddress(input?.address, context)
      case 'recent_activity':
        return await recentActivity(input?.wallet, input?.n, context)
      case 'resolve_name':
        return await resolveName(String(input?.name ?? ''), context)
      case 'prepare_send':
        return await prepareSend(input, context)
      case 'search_solana_kb': {
        let found: unknown
        try {
          found = await searchTool(context.kb, input)
        } catch (error) {
          // Workers AI or Vectorize failed (a spent daily allowance is "4006"): said in the log, never to the user.
          if (context.stats) context.stats.kbError = String((error as Error)?.message ?? error).match(/^\d{4}/)?.[0] ?? (error as Error)?.name ?? 'error'
          throw error
        }
        const results: KbResult[] = Array.isArray((found as any)?.results) ? (found as any).results : []
        if (context.stats) {
          context.stats.kbHits += results.length
          // What the reply may cite: only what was really handed over.
          for (const r of results) context.stats.found?.set(r.url, r)
        }
        return found
      }
      default:
        return { error: 'unknown_tool' }
    }
  } catch (error) {
    const aborted = (error as Error)?.name === 'AbortError'
    return { error: aborted ? 'timed_out' : 'lookup_failed', detail: aborted ? 'The lookup took too long.' : 'The lookup service did not answer.' }
  }
}

// ------------------------------------------------------------------ balances

function walletOf(given: unknown, context: ToolContext): string | Failure {
  const wallet = typeof given === 'string' && given.trim() ? given.trim() : context.wallet
  if (!wallet) return { error: 'no_wallet', detail: 'No wallet is connected. Connect one in Heylana Settings, or ask about an address.' }
  if (!isAddress(wallet)) return { error: 'bad_address', detail: 'That is not a Solana address.' }
  return wallet
}

interface Holding {
  units: bigint
  decimals: number
}

async function tokenHoldings(owner: string, context: ToolContext, filter?: { mint: string }): Promise<Map<string, Holding>> {
  const queries = filter
    ? [rpcCall(context.rpcUrl, 'getTokenAccountsByOwner', [owner, filter, { encoding: 'jsonParsed' }], context.signal)]
    : [TOKEN_PROGRAM, TOKEN_2022_PROGRAM].map((programId) =>
        rpcCall(context.rpcUrl, 'getTokenAccountsByOwner', [owner, { programId }, { encoding: 'jsonParsed' }], context.signal),
      )
  const holdings = new Map<string, Holding>()
  for (const result of await Promise.all(queries)) {
    for (const account of result?.value ?? []) {
      const info = account?.account?.data?.parsed?.info
      if (!info?.mint) continue
      const units = BigInt(info.tokenAmount?.amount ?? '0')
      const previous = holdings.get(info.mint)
      holdings.set(info.mint, { units: (previous?.units ?? 0n) + units, decimals: Number(info.tokenAmount?.decimals ?? 0) })
    }
  }
  return holdings
}

async function getBalances(given: unknown, context: ToolContext) {
  const wallet = walletOf(given, context)
  if (typeof wallet !== 'string') return wallet

  const [lamports, holdings] = await Promise.all([
    rpcCall(context.rpcUrl, 'getBalance', [wallet], context.signal),
    tokenHoldings(wallet, context),
  ])
  for (const [mint, holding] of holdings) if (holding.units === 0n) holdings.delete(mint)

  // Devnet tokens are play money: amounts are real, dollar values would not be.
  const priced = context.cluster === 'mainnet-beta'
  let prices: Record<string, number> = {}
  let priceNote: string | undefined
  if (priced) {
    const mints = [WSOL_MINT, ...[...holdings.keys()].map((mint) => KNOWN[mint]?.priceMint ?? mint)]
    try {
      prices = await jupiterPrices(mints, context.jupiterKey, context.signal)
    } catch {
      priceNote = 'Prices are unavailable right now; amounts are exact.'
    }
  }

  const line = (mint: string, units: bigint, decimals: number, symbol: string) => {
    const amount = unitsToDecimal(units, decimals)
    const price = prices[KNOWN[mint]?.priceMint ?? mint]
    return { symbol, amount, usd: priced && price ? Math.round(Number(amount) * price * 100) / 100 : null }
  }
  const named = (mint: string, symbol: string) => {
    const holding = holdings.get(mint)
    return line(mint, holding?.units ?? 0n, holding?.decimals ?? 0, symbol)
  }

  const others = [...holdings.entries()]
    .filter(([mint]) => mint !== context.usdcMint && mint !== context.skrMint)
    .map(([mint, holding]) => ({ ...line(mint, holding.units, holding.decimals, KNOWN[mint]?.symbol ?? short(mint)), mint: short(mint) }))
    .sort((a, b) => (b.usd ?? -1) - (a.usd ?? -1) || Number(b.amount) - Number(a.amount))

  const sol = line(WSOL_MINT, BigInt(lamports?.value ?? 0), SOL_DECIMALS, 'SOL')
  const usdc = named(context.usdcMint, 'USDC')
  const skr = isAddress(context.skrMint) && context.cluster === 'mainnet-beta' ? named(context.skrMint, 'SKR') : null
  const all = [sol, usdc, ...(skr ? [skr] : []), ...others]

  return {
    wallet: short(wallet),
    cluster: context.cluster,
    sol,
    usdc,
    skr,
    others: others.slice(0, 5),
    more_tokens: Math.max(0, others.length - 5),
    total_usd: priced ? Math.round(all.reduce((sum, item) => sum + (item.usd ?? 0), 0) * 100) / 100 : null,
    prices: priced ? 'Jupiter Price API' : 'none: devnet tokens have no market value',
    ...(priceNote ? { note: priceNote } : {}),
  }
}

// --------------------------------------------------------------------- price

/** Jupiter's token search, for a symbol not in the table. */
export const JUPITER_SEARCH_URL = 'https://api.jup.ag/tokens/v2/search'

async function searchToken(query: string, context: ToolContext): Promise<{ mint: string; symbol: string } | null> {
  const headers: Record<string, string> = {}
  if (context.jupiterKey) headers['x-api-key'] = context.jupiterKey
  const res = await fetch(`${JUPITER_SEARCH_URL}?query=${encodeURIComponent(query)}`, { headers, signal: context.signal })
  if (!res.ok) return null
  const list: any = await res.json()
  const tokens = Array.isArray(list) ? list : []
  const wanted = query.toUpperCase()
  const match =
    tokens.find((token: any) => String(token?.symbol).toUpperCase() === wanted && token?.isVerified) ??
    tokens.find((token: any) => String(token?.symbol).toUpperCase() === wanted) ??
    null
  return match && isAddress(match.id) ? { mint: match.id, symbol: String(match.symbol) } : null
}

async function getPrice(given: unknown, context: ToolContext) {
  const query = String(given ?? '').trim()
  if (!query) return { error: 'no_token', detail: 'Name a token or give its mint.' }

  let mint = isAddress(query) ? query : SYMBOL_TO_MINT[query.toUpperCase()]
  let symbol = isAddress(query) ? KNOWN[query]?.symbol ?? short(query) : query.toUpperCase()
  if (!mint) {
    const found = await searchToken(query, context)
    if (!found) return { error: 'unknown_token', detail: `No token called ${query} was found.` }
    mint = found.mint
    symbol = found.symbol
  }
  const prices = await jupiterPrices([KNOWN[mint]?.priceMint ?? mint], context.jupiterKey, context.signal)
  const usd = prices[KNOWN[mint]?.priceMint ?? mint]
  if (!usd) return { error: 'no_price', detail: `Jupiter has no price for ${symbol}.` }
  return { symbol, mint: short(mint), usd, source: 'Jupiter Price API', as_of: new Date(context.now()).toISOString() }
}

// ------------------------------------------------------------------- address

function kindOf(account: any): string {
  if (!account) return 'unused address: it holds no SOL and has never been set up'
  if (account.executable) return 'program'
  const owner = account.owner
  const type = account.data?.parsed?.type
  if (owner === SYSTEM_PROGRAM) return 'wallet'
  if (owner === TOKEN_PROGRAM || owner === TOKEN_2022_PROGRAM) {
    if (type === 'mint') return 'token mint'
    if (type === 'account') return 'token account'
    return 'token program account'
  }
  if (owner === BPF_UPGRADEABLE_LOADER) return 'program data'
  return `account owned by ${KNOWN[owner]?.label ?? short(owner)}`
}

/** A token's name from Helius DAS, when the RPC is Helius. Anything else: null. */
async function dasName(mint: string, context: ToolContext): Promise<string | null> {
  try {
    const asset = await rpcCall(context.rpcUrl, 'getAsset', { id: mint }, context.signal)
    const name = asset?.content?.metadata?.name
    const symbol = asset?.content?.metadata?.symbol ?? asset?.token_info?.symbol
    return name ? (symbol ? `${name} (${symbol})` : String(name)) : null
  } catch {
    return null
  }
}

const SIGNATURE_PAGE = 1000

async function explainAddress(given: unknown, context: ToolContext) {
  const address = String(given ?? '').trim()
  if (!isAddress(address)) return { error: 'bad_address', detail: 'That is not a Solana address.' }

  const known = KNOWN[address]
  const [info, signatures] = await Promise.all([
    rpcCall(context.rpcUrl, 'getAccountInfo', [address, { encoding: 'jsonParsed' }], context.signal),
    rpcCall(context.rpcUrl, 'getSignaturesForAddress', [address, { limit: SIGNATURE_PAGE }], context.signal),
  ])
  const account = info?.value ?? null
  const kind = kindOf(account)
  const list: any[] = Array.isArray(signatures) ? signatures : []
  const complete = list.length < SIGNATURE_PAGE
  const oldest = list.at(-1)?.blockTime

  const isTreasury = Boolean(context.treasury) && address === context.treasury
  const label = known?.label ??
    (isTreasury ? 'your Heylana treasury' : null) ??
    (kind === 'token mint' ? await dasName(address, context) : null)
  const parsed = account?.data?.parsed?.info

  // Only what an answer about this address uses: every field is paid for again in
  // the next round, and a sign explanation reads up to three of these.
  return {
    address: short(address),
    kind,
    label,
    well_known: Boolean(known) || isTreasury,
    transactions: complete ? list.length : `${SIGNATURE_PAGE} or more`,
    first_seen: oldest ? (complete ? isoDay(oldest) : `before ${isoDay(oldest)}`) : null,
    age_days: oldest && complete ? Math.floor((context.now() / 1000 - oldest) / 86400) : null,
    ...(kind === 'token account' ? { owner: parsed?.owner ? short(parsed.owner) : null, mint: parsed?.mint ? short(parsed.mint) : null } : {}),
  }
}

// ------------------------------------------------------------------ activity

interface Change {
  asset: string
  mint: string | null
  units: bigint
  decimals: number
}

function labelOf(address: string): string {
  return KNOWN[address]?.label ?? short(address)
}

/** One transaction, from the wallet's side, in one line. */
export function summarize(tx: any, wallet: string): string {
  const keys: string[] = (tx?.transaction?.message?.accountKeys ?? []).map((key: any) => (typeof key === 'string' ? key : key?.pubkey))
  const meta = tx?.meta ?? {}
  const index = keys.indexOf(wallet)
  const changes: Change[] = []

  if (index >= 0) {
    let lamports = BigInt(meta.postBalances?.[index] ?? 0) - BigInt(meta.preBalances?.[index] ?? 0)
    if (index === 0) lamports += BigInt(meta.fee ?? 0) // the fee is not what was sent
    if (lamports !== 0n) changes.push({ asset: 'SOL', mint: null, units: lamports, decimals: SOL_DECIMALS })
  }
  const byMint = new Map<string, { pre: bigint; post: bigint; decimals: number }>()
  for (const [field, side] of [['preTokenBalances', 'pre'], ['postTokenBalances', 'post']] as const) {
    for (const balance of meta[field] ?? []) {
      if (balance?.owner !== wallet) continue
      const entry = byMint.get(balance.mint) ?? { pre: 0n, post: 0n, decimals: Number(balance.uiTokenAmount?.decimals ?? 0) }
      entry[side] += BigInt(balance.uiTokenAmount?.amount ?? '0')
      byMint.set(balance.mint, entry)
    }
  }
  for (const [mint, entry] of byMint) {
    const units = entry.post - entry.pre
    if (units !== 0n) changes.push({ asset: KNOWN[mint]?.symbol ?? short(mint), mint, units, decimals: entry.decimals })
  }

  // Next to a token moving, a sliver of SOL is account rent, not the point.
  const RENT_SIZED = 10_000_000n
  const moved = changes.filter((change) => !(change.mint === null && changes.length > 1 && abs(change.units) < RENT_SIZED))
  const out = moved.filter((change) => change.units < 0n)
  const into = moved.filter((change) => change.units > 0n)
  const amount = (change: Change) => `${unitsToDecimal(abs(change.units), change.decimals)} ${change.asset}`

  if (out.length && into.length) return `swapped ${out.map(amount).join(' + ')} for ${into.map(amount).join(' + ')}`
  if (out.length) {
    const other = counterparty(tx, keys, wallet, out[0])
    return `sent ${out.map(amount).join(' + ')}${other ? ` to ${other}` : ''}`
  }
  if (into.length) {
    const other = counterparty(tx, keys, wallet, into[0])
    return `received ${into.map(amount).join(' + ')}${other ? ` from ${other}` : ''}`
  }
  const program = (tx?.transaction?.message?.instructions ?? [])
    .map((instruction: any) => instruction?.programId)
    .find((id: string) => id && id !== SYSTEM_PROGRAM && id !== COMPUTE_BUDGET_PROGRAM)
  return program ? `used ${labelOf(program)}, no balance change` : 'a transaction with no balance change'
}

function counterparty(tx: any, keys: string[], wallet: string, change: Change): string | null {
  const meta = tx?.meta ?? {}
  if (change.mint) {
    const deltas = new Map<string, bigint>()
    for (const [field, sign] of [['preTokenBalances', -1n], ['postTokenBalances', 1n]] as const) {
      for (const balance of meta[field] ?? []) {
        if (balance?.mint !== change.mint || !balance?.owner || balance.owner === wallet) continue
        deltas.set(balance.owner, (deltas.get(balance.owner) ?? 0n) + sign * BigInt(balance.uiTokenAmount?.amount ?? '0'))
      }
    }
    const opposite = [...deltas.entries()].filter(([, delta]) => (change.units < 0n ? delta > 0n : delta < 0n))
    opposite.sort((a, b) => (abs(b[1]) > abs(a[1]) ? 1 : -1))
    return opposite[0] ? labelOf(opposite[0][0]) : null
  }
  let best: { key: string; delta: bigint } | null = null
  keys.forEach((key, i) => {
    if (key === wallet) return
    const delta = BigInt(meta.postBalances?.[i] ?? 0) - BigInt(meta.preBalances?.[i] ?? 0)
    const opposite = change.units < 0n ? delta > 0n : delta < 0n
    if (opposite && (!best || abs(delta) > abs(best.delta))) best = { key, delta }
  })
  return best ? labelOf((best as { key: string }).key) : null
}

async function recentActivity(given: unknown, count: unknown, context: ToolContext) {
  const wallet = walletOf(given, context)
  if (typeof wallet !== 'string') return wallet
  const n = Math.max(1, Math.min(5, Math.floor(Number(count) || 5)))

  const signatures = await rpcCall(context.rpcUrl, 'getSignaturesForAddress', [wallet, { limit: n }], context.signal)
  const list: any[] = Array.isArray(signatures) ? signatures.slice(0, n) : []
  const transactions = await Promise.all(
    list.map((entry) =>
      rpcCall(
        context.rpcUrl,
        'getTransaction',
        [entry.signature, { encoding: 'jsonParsed', maxSupportedTransactionVersion: 0, commitment: 'confirmed' }],
        context.signal,
      ).catch(() => null),
    ),
  )
  return {
    wallet: short(wallet),
    cluster: context.cluster,
    items: list.map((entry, i) => ({
      when: entry.blockTime ? isoDay(entry.blockTime) : null,
      summary: transactions[i] ? summarize(transactions[i], wallet) : 'details unavailable',
      failed: Boolean(entry.err),
    })),
  }
}

// ---------------------------------------------------------------------- send

export interface SendQuote {
  to_address: string
  resolved_from: string | null
  amount: string
  token: 'SOL' | 'USDC' | 'SKR'
  mint: string | null
  decimals: number
  token_program: string | null
  fee_estimate: string
  account_rent: string
  will_create_ata: boolean
  /** The sender's balance of this token, when a wallet is connected. */
  balance: string | null
  cluster: string
}

const BASE_FEE_LAMPORTS = 5000n
const TOKEN_ACCOUNT_SIZE = 165
const TOKEN_2022_ACCOUNT_SIZE = 170

async function balanceOf(owner: string, mint: string | null, decimals: number, context: ToolContext): Promise<string> {
  if (!mint) {
    const lamports = await rpcCall(context.rpcUrl, 'getBalance', [owner], context.signal)
    return unitsToDecimal(BigInt(lamports?.value ?? 0), SOL_DECIMALS)
  }
  const holdings = await tokenHoldings(owner, context, { mint })
  return unitsToDecimal(holdings.get(mint)?.units ?? 0n, decimals)
}

/**
 * Everything the confirmation strip needs, and nothing built: no transaction,
 * no signature. The phone builds the transfer and Seed Vault signs it.
 */
export async function prepareSend(input: any, context: ToolContext): Promise<SendQuote | Failure> {
  const token = String(input?.token ?? '').toUpperCase()
  if (token !== 'SOL' && token !== 'USDC' && token !== 'SKR') {
    return { error: 'bad_token', detail: 'Heylana can send SOL, USDC or SKR.' }
  }
  // "all" comes only from the app's send route, when the user said everything.
  const all = input?.amount === 'all'
  const asked = all ? 0 : Number(input?.amount)
  if (!all && (!Number.isFinite(asked) || asked <= 0)) return { error: 'bad_amount', detail: 'The amount has to be more than zero.' }
  if (all && token === 'SOL') {
    return { error: 'sol_all', detail: 'Some SOL has to stay behind to pay the fee, so say an amount.' }
  }

  const target = String(input?.to ?? '').trim()
  let toAddress: string
  let resolvedFrom: string | null = null
  if (isAddress(target)) {
    toAddress = target
  } else if (/\.(skr|sol)$/i.test(target)) {
    const resolved = await resolveName(target, context)
    if ('error' in resolved) return resolved
    toAddress = resolved.address
    resolvedFrom = resolved.name
  } else {
    return { error: 'unknown_recipient', detail: 'Send to a Solana address, a .skr name or a .sol name.' }
  }

  let mint: string | null = null
  let decimals = SOL_DECIMALS
  let program: string | null = null
  if (token === 'USDC') mint = context.usdcMint
  if (token === 'SKR') {
    if (context.cluster === 'devnet') return { error: 'not_on_devnet', detail: 'There is no SKR on devnet.' }
    if (!isAddress(context.skrMint)) return { error: 'not_configured', detail: 'SKR sends are not set up.' }
    mint = context.skrMint
  }
  if (mint) {
    const info = await mintInfo(context.rpcUrl, mint)
    decimals = info.decimals
    program = info.program
  }

  let known: string | null = null
  let units: bigint
  if (all) {
    if (!context.wallet) return { error: 'no_wallet', detail: 'No wallet is connected. Connect one in Heylana Settings.' }
    known = await balanceOf(context.wallet, mint, decimals, context)
    units = decimalToUnits(known, decimals)
    if (units <= 0n) return { error: 'bad_amount', detail: `There is no ${token} to send.` }
  } else {
    units = decimalToUnits(asked.toFixed(decimals), decimals)
    if (units <= 0n) return { error: 'bad_amount', detail: 'That amount is smaller than the token allows.' }
  }

  const [recipientAccounts, balance, rent] = await Promise.all([
    mint ? rpcCall(context.rpcUrl, 'getTokenAccountsByOwner', [toAddress, { mint }, { encoding: 'jsonParsed' }], context.signal) : null,
    known ?? (context.wallet ? balanceOf(context.wallet, mint, decimals, context) : null),
    mint
      ? rpcCall(context.rpcUrl, 'getMinimumBalanceForRentExemption', [program === TOKEN_2022_PROGRAM ? TOKEN_2022_ACCOUNT_SIZE : TOKEN_ACCOUNT_SIZE], context.signal)
      : null,
  ])
  const willCreateAta = mint ? (recipientAccounts?.value ?? []).length === 0 : false

  return {
    to_address: toAddress,
    resolved_from: resolvedFrom,
    amount: unitsToDecimal(units, decimals),
    token,
    mint,
    decimals,
    token_program: program,
    fee_estimate: unitsToDecimal(BASE_FEE_LAMPORTS, SOL_DECIMALS),
    account_rent: willCreateAta ? unitsToDecimal(BigInt(rent ?? 0), SOL_DECIMALS) : '0',
    will_create_ata: willCreateAta,
    balance,
    cluster: context.cluster,
  }
}
