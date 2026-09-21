import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { runTool, summarize, type ToolContext } from '../src/tools.ts'
import { makeRpc } from '../src/rpc.ts'

const RPC = 'https://rpc.test/secret-token-abc'
const WALLET = '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'
const OTHER = '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv'
const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v'
const SKR = 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3'
const WSOL = 'So11111111111111111111111111111111111111112'
const TOKEN = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
const TOKEN_2022 = 'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb'
const SYSTEM = '11111111111111111111111111111111'
// A real mint that is not in the table, so it is 'some other token'.
const MYSTERY = 'DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263'
const JUPITER_PROGRAM = 'JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4'
const NOW = Date.parse('2026-09-15T12:00:00Z')
const DAY = 86400

const context = (over: Partial<ToolContext> = {}): ToolContext => ({
  rpc: makeRpc({ RPCFAST_URL: RPC, CLUSTER: 'mainnet-beta' }), usdcMint: USDC, skrMint: SKR, cluster: 'mainnet-beta', wallet: WALLET, now: () => NOW, ...over,
})

const tokenAccount = (owner: string, mint: string, amount: string, decimals = 6) => ({
  pubkey: 'acct', account: { data: { parsed: { info: { owner, mint, tokenAmount: { amount, decimals } } } } },
})

/** The chain these tests believe in. */
let chain: Record<string, (params: any) => unknown>
let prices: Record<string, number>
let searches: string[]
let seen: string[]

beforeEach(() => {
  seen = []
  searches = []
  prices = { [WSOL]: 150, [USDC]: 1, [SKR]: 0.05, [MYSTERY]: 2 }
  chain = {
    getBalance: () => ({ value: 1_500_000_000 }),
    getTokenAccountsByOwner: ([owner, filter]: any) => {
      const all = owner === WALLET
        ? [tokenAccount(WALLET, USDC, '5000000'), tokenAccount(WALLET, SKR, '100000000'), tokenAccount(WALLET, MYSTERY, '3000000'), tokenAccount(WALLET, WSOL, '0', 9)]
        : []
      if (filter.programId === TOKEN_2022) return { value: [] }
      if (filter.mint) return { value: all.filter((a) => a.account.data.parsed.info.mint === filter.mint) }
      return { value: all }
    },
    getAccountInfo: ([address]: any) => {
      if (address === USDC) return { value: { owner: TOKEN, executable: false, lamports: 1, data: { parsed: { type: 'mint', info: { decimals: 6, supply: '1' } } } } }
      if (address === WALLET) return { value: { owner: SYSTEM, executable: false, lamports: 10_000_000, data: ['', 'base64'] } }
      return { value: null }
    },
    getSignaturesForAddress: ([, options]: any) =>
      // Newest first, as the RPC returns them.
      [1, 2, 3].map((daysAgo, i) => ({ signature: `sig${i}`, blockTime: NOW / 1000 - daysAgo * DAY, err: null })).slice(0, options.limit),
    getMinimumBalanceForRentExemption: () => 2_039_280,
    getTransaction: () => sendTx(),
  }
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url === RPC) {
      const { method, params } = JSON.parse(String(init.body))
      seen.push(method)
      if (!chain[method]) return new Response(JSON.stringify({ error: { code: -32601 } }))
      return new Response(JSON.stringify({ result: chain[method](params) }))
    }
    if (url.startsWith('https://api.jup.ag/price/v3')) {
      seen.push('price')
      const ids = new URL(url).searchParams.get('ids')!.split(',')
      return new Response(JSON.stringify(Object.fromEntries(ids.filter((id) => prices[id]).map((id) => [id, { usdPrice: prices[id] }]))))
    }
    if (url.startsWith('https://api.jup.ag/tokens/v2/search')) {
      const query = new URL(url).searchParams.get('query')!
      searches.push(query)
      return new Response(JSON.stringify(query === 'MYST' ? [{ id: MYSTERY, symbol: 'MYST', isVerified: true }] : []))
    }
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch
})

/** The connected wallet sent 0.05 USDC to OTHER. */
function sendTx() {
  return {
    meta: {
      err: null, fee: 5000,
      preBalances: [1_000_000_000, 0], postBalances: [999_995_000, 0],
      preTokenBalances: [
        { accountIndex: 2, mint: USDC, owner: WALLET, uiTokenAmount: { amount: '5000000', decimals: 6 } },
        { accountIndex: 3, mint: USDC, owner: OTHER, uiTokenAmount: { amount: '0', decimals: 6 } },
      ],
      postTokenBalances: [
        { accountIndex: 2, mint: USDC, owner: WALLET, uiTokenAmount: { amount: '4950000', decimals: 6 } },
        { accountIndex: 3, mint: USDC, owner: OTHER, uiTokenAmount: { amount: '50000', decimals: 6 } },
      ],
    },
    transaction: { message: { accountKeys: [{ pubkey: WALLET }, { pubkey: OTHER }], instructions: [{ programId: TOKEN }] } },
  }
}

// ------------------------------------------------------------------ balances

test('balances: SOL, USDC and SKR by name, others by value, with dollars from Jupiter', async () => {
  const result: any = await runTool('get_balances', {}, context())
  assert.deepEqual(result.sol, { symbol: 'SOL', amount: '1.5', usd: 225 })
  assert.deepEqual(result.usdc, { symbol: 'USDC', amount: '5', usd: 5 })
  assert.deepEqual(result.skr, { symbol: 'SKR', amount: '100', usd: 5 })
  assert.equal(result.others.length, 1, 'the empty wrapped-SOL account is not a holding')
  assert.equal(result.others[0].amount, '3')
  assert.equal(result.others[0].usd, 6)
  assert.equal(result.total_usd, 241)
  assert.equal(result.prices, 'Jupiter Price API')
  assert.equal(result.wallet, '9WzD…AWWM')
})

test('balances on devnet are exact amounts with no dollar value, and no price is asked', async () => {
  const result: any = await runTool('get_balances', {}, context({ cluster: 'devnet' }))
  assert.equal(result.sol.amount, '1.5')
  assert.equal(result.sol.usd, null)
  assert.equal(result.total_usd, null)
  assert.equal(result.skr, null)
  assert.equal(seen.includes('price'), false)
  // Said with its network, and never as the wallet app's mainnet number.
  assert.match(result.say_network, /17\.65 USDC on devnet/)
  assert.match(result.say_network, /never that they match/)
})

test('balances on mainnet carry no network line', async () => {
  const result: any = await runTool('get_balances', {}, context())
  assert.equal(result.say_network, undefined)
})

test('balances with no wallet connected and none given say how to fix it', async () => {
  const result: any = await runTool('get_balances', {}, context({ wallet: null }))
  assert.equal(result.error, 'no_wallet')
  assert.deepEqual(seen, [])
})

// --------------------------------------------------------------------- price

test('a price by symbol comes from Jupiter, with its source and time', async () => {
  const result: any = await runTool('get_price', { symbol_or_mint: 'sol' }, context())
  assert.equal(result.usd, 150)
  assert.equal(result.symbol, 'SOL')
  assert.equal(result.source, 'Jupiter Price API')
  assert.equal(result.as_of, '2026-09-15T12:00:00.000Z')
  assert.deepEqual(searches, [], 'a known symbol needs no search')
})

test('a symbol not in the table is searched for; an unknown one says so', async () => {
  const found: any = await runTool('get_price', { symbol_or_mint: 'MYST' }, context())
  assert.equal(found.usd, 2)
  const missing: any = await runTool('get_price', { symbol_or_mint: 'NOPE' }, context())
  assert.equal(missing.error, 'unknown_token')
})

// ------------------------------------------------------------------- address

test('a wallet: kind, age and transactions, and nothing an answer does not use', async () => {
  const result: any = await runTool('explain_address', { address: WALLET }, context())
  assert.equal(result.kind, 'wallet')
  assert.equal(result.label, null)
  assert.equal(result.transactions, 3)
  assert.equal(result.first_seen, '2026-09-12')
  assert.equal(result.age_days, 3)
  assert.deepEqual(Object.keys(result).sort(), ['address', 'age_days', 'first_seen', 'kind', 'label', 'transactions', 'well_known'])
  assert.ok(JSON.stringify(result).length < 200, 'a result stays small')
})

test('a well-known mint is named from the table', async () => {
  const result: any = await runTool('explain_address', { address: USDC }, context())
  assert.equal(result.kind, 'token mint')
  assert.equal(result.label, 'USD Coin (USDC)')
  assert.equal(result.well_known, true)
  assert.equal(result.decimals, undefined)
})

test("Heylana's own treasury is named as such", async () => {
  const result: any = await runTool('explain_address', { address: WALLET }, context({ treasury: WALLET }))
  assert.equal(result.label, 'your Heylana treasury')
  assert.equal(result.well_known, true)
  const other: any = await runTool('explain_address', { address: WALLET }, context({ treasury: OTHER }))
  assert.equal(other.label, null)
})

test('an address never used says so plainly', async () => {
  chain.getSignaturesForAddress = () => []
  const result: any = await runTool('explain_address', { address: OTHER }, context())
  assert.match(result.kind, /^unused address/)
  assert.equal(result.transactions, 0)
  assert.equal(result.first_seen, null)
})

test('a busy address counts a full page as a floor, not a total', async () => {
  chain.getSignaturesForAddress = () => Array.from({ length: 1000 }, (_, i) => ({ signature: `s${i}`, blockTime: NOW / 1000 - i * 60 }))
  const result: any = await runTool('explain_address', { address: WALLET }, context())
  assert.equal(result.transactions, '1000 or more')
  assert.match(result.first_seen, /^before /)
  assert.equal(result.age_days, null)
})

test('a non-address is refused before any lookup', async () => {
  const result: any = await runTool('explain_address', { address: 'bob' }, context())
  assert.equal(result.error, 'bad_address')
  assert.deepEqual(seen, [])
})

// ------------------------------------------------------------------ activity

test('a send is summarised from the wallet side, fee left out, to the counterparty', () => {
  assert.equal(summarize(sendTx(), WALLET), 'sent 0.05 USDC to 7c2y…nSxSv'.replace('nSxSv', 'SxSv'))
})

test('receiving SOL names the sender', () => {
  const tx = {
    meta: { fee: 5000, preBalances: [2_000_000_000, 0], postBalances: [999_995_000, 1_000_000_000], preTokenBalances: [], postTokenBalances: [] },
    transaction: { message: { accountKeys: [OTHER, WALLET], instructions: [{ programId: SYSTEM }] } },
  }
  assert.equal(summarize(tx, WALLET), 'received 1 SOL from 7c2y…SxSv')
})

test('one token out and another in is a swap', () => {
  const tx = {
    meta: {
      fee: 5000, preBalances: [3_000_000_000], postBalances: [1_999_995_000],
      preTokenBalances: [{ mint: USDC, owner: WALLET, uiTokenAmount: { amount: '0', decimals: 6 } }],
      postTokenBalances: [{ mint: USDC, owner: WALLET, uiTokenAmount: { amount: '150000000', decimals: 6 } }],
    },
    transaction: { message: { accountKeys: [WALLET], instructions: [{ programId: JUPITER_PROGRAM }] } },
  }
  assert.equal(summarize(tx, WALLET), 'swapped 1 SOL for 150 USDC')
})

test('nothing moving still says which program was used', () => {
  const tx = {
    meta: { fee: 5000, preBalances: [1_000_005_000], postBalances: [1_000_000_000], preTokenBalances: [], postTokenBalances: [] },
    transaction: { message: { accountKeys: [WALLET], instructions: [{ programId: JUPITER_PROGRAM }] } },
  }
  assert.equal(summarize(tx, WALLET), 'used Jupiter Aggregator v6, no balance change')
})

test('recent activity asks for five at most', async () => {
  let limit = 0
  chain.getSignaturesForAddress = ([, options]: any) => {
    limit = options.limit
    return [{ signature: 's', blockTime: NOW / 1000, err: null }]
  }
  const result: any = await runTool('recent_activity', { n: 50 }, context())
  assert.equal(limit, 5)
  assert.equal(result.items[0].summary, 'sent 0.05 USDC to 7c2y…SxSv')
  assert.equal(result.items[0].when, '2026-09-15')
})

// ---------------------------------------------------------------------- send

test('preparing a USDC send to a fresh address: amount, fee, new token account, balance', async () => {
  const quote: any = await runTool('prepare_send', { to: OTHER, amount: 0.05, token: 'usdc' }, context())
  assert.equal(quote.to_address, OTHER)
  assert.equal(quote.resolved_from, null)
  assert.equal(quote.amount, '0.05')
  assert.equal(quote.token, 'USDC')
  assert.equal(quote.mint, USDC)
  assert.equal(quote.decimals, 6)
  assert.equal(quote.will_create_ata, true)
  assert.equal(quote.account_rent, '0.00203928')
  assert.equal(quote.fee_estimate, '0.000005')
  assert.equal(quote.balance, '5')
  assert.equal(seen.includes('sendTransaction'), false, 'nothing is ever sent')
})

test('preparing a SOL send needs no token account and reads the SOL balance', async () => {
  const quote: any = await runTool('prepare_send', { to: OTHER, amount: 0.25, token: 'SOL' }, context())
  assert.equal(quote.mint, null)
  assert.equal(quote.will_create_ata, false)
  assert.equal(quote.amount, '0.25')
  assert.equal(quote.balance, '1.5')
})

test('a send that cannot be prepared says why in plain words', async () => {
  assert.equal(((await runTool('prepare_send', { to: OTHER, amount: 1, token: 'SKR' }, context({ cluster: 'devnet' }))) as any).error, 'not_on_devnet')
  assert.equal(((await runTool('prepare_send', { to: OTHER, amount: 0, token: 'SOL' }, context())) as any).error, 'bad_amount')
  assert.equal(((await runTool('prepare_send', { to: OTHER, amount: 1, token: 'BONK' }, context())) as any).error, 'bad_token')
  assert.equal(((await runTool('prepare_send', { to: 'bob', amount: 1, token: 'SOL' }, context())) as any).error, 'unknown_recipient')
})
