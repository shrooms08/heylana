import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { findProgramAddress, isOnCurve, utf8 } from '../src/pda.ts'
import { decodeBase58, encodeBase58 } from '../src/base58.ts'
import { NAME_HOUSE_PROGRAM, ROOT_ANS, SNS_PROXY_URL, TLD_HOUSE_PROGRAM, deriveNameAccount, resolveName } from '../src/names.ts'
import { runTool, type ToolContext } from '../src/tools.ts'

const ATA_PROGRAM = 'ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL'
const TOKEN = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v'
const MAINNET = 'https://mainnet.test/key-mainnet-123'
const DEVNET = 'https://devnet.test/key-devnet-456'
const OWNER = '7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv'
const HOLDER = '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'
const HOLDER_ACCOUNT = 'C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH'
const NOW = Date.parse('2026-09-15T12:00:00Z')

const bytes = (address: string) => decodeBase58(address)!

const context = (over: Partial<ToolContext> = {}): ToolContext => ({
  rpcUrl: DEVNET, mainnetRpcUrl: MAINNET, usdcMint: USDC, skrMint: 'replace-me', cluster: 'devnet', wallet: null, now: () => NOW, ...over,
})

/** A name-service record: owner at 40, expiry at 104, 200 bytes of header. */
function nameRecord(owner: string, expiresAt = 0n): Uint8Array {
  const data = new Uint8Array(216)
  data.set(bytes(owner), 40)
  new DataView(data.buffer).setBigUint64(104, expiresAt, true)
  return data
}

let accounts: Map<string, Uint8Array>
let rpcUrls: string[]
let solReply: (name: string) => Response
let extra: Record<string, (params: any) => unknown>

beforeEach(() => {
  accounts = new Map()
  rpcUrls = []
  extra = {}
  solReply = () => new Response(JSON.stringify({ s: 'error', result: 'Domain not found' }), { status: 404 })
  globalThis.fetch = (async (input: any, init: any) => {
    const url = typeof input === 'string' ? input : input.url
    if (url === MAINNET || url === DEVNET) {
      rpcUrls.push(url)
      const { method, params } = JSON.parse(String(init.body))
      if (method === 'getAccountInfo' && params[1]?.encoding === 'base64') {
        const data = accounts.get(params[0])
        return new Response(JSON.stringify({ result: { value: data ? { data: [Buffer.from(data).toString('base64'), 'base64'] } : null } }))
      }
      if (extra[method]) return new Response(JSON.stringify({ result: extra[method](params) }))
      return new Response(JSON.stringify({ error: { code: -32601 } }))
    }
    if (url.startsWith(`${SNS_PROXY_URL}/resolve/`)) return solReply(decodeURIComponent(url.split('/resolve/')[1]))
    throw new Error(`unexpected fetch ${url}`)
  }) as typeof fetch
})

async function aliceAccount(): Promise<string> {
  return deriveNameAccount('alice', await deriveNameAccount('.skr', ROOT_ANS))
}

// ----------------------------------------------------------------------- PDA

test('derived addresses match the ones checked independently for the app', async () => {
  const payer = await findProgramAddress([bytes('9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'), bytes(TOKEN), bytes(USDC)], ATA_PROGRAM)
  assert.equal(payer.address, 'FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B')
  const treasury = await findProgramAddress([bytes('7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU'), bytes(TOKEN), bytes(USDC)], ATA_PROGRAM)
  assert.equal(treasury.address, 'C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH')
})

test('a real key is on the curve; a derived address never is', async () => {
  for (let i = 0; i < 5; i++) {
    const pair = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify'])) as CryptoKeyPair
    assert.equal(isOnCurve(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey))), true)
  }
  assert.equal(isOnCurve(bytes('FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B')), false)
  assert.equal(isOnCurve(bytes('C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH')), false)
})

// ----------------------------------------------------------------------- .skr

test('alice.skr resolves to its owner, read from mainnet even while payments are on devnet', async () => {
  accounts.set(await aliceAccount(), nameRecord(OWNER))
  assert.deepEqual(await resolveName('alice.skr', context()), { name: 'alice.skr', address: OWNER })
  assert.deepEqual([...new Set(rpcUrls)], [MAINNET])
})

test('capitals and spaces around a name do not make it a different name', async () => {
  accounts.set(await aliceAccount(), nameRecord(OWNER))
  assert.deepEqual(await resolveName('  Alice.SKR ', context()), { name: 'alice.skr', address: OWNER })
})

test('a name nobody holds, or one that has expired, is not found', async () => {
  assert.equal(((await resolveName('nobody.skr', context())) as any).error, 'not_found')
  accounts.set(await aliceAccount(), nameRecord(OWNER, BigInt(NOW / 1000 - 60)))
  assert.equal(((await resolveName('alice.skr', context())) as any).error, 'not_found')
})

test('on devnet with no mainnet connection, .skr says why instead of guessing', async () => {
  const result: any = await resolveName('alice.skr', context({ mainnetRpcUrl: undefined }))
  assert.equal(result.error, 'names_unavailable')
  assert.deepEqual(rpcUrls, [])
})

test('on mainnet the cluster RPC is enough for .skr', async () => {
  accounts.set(await aliceAccount(), nameRecord(OWNER))
  const result = await resolveName('alice.skr', context({ cluster: 'mainnet-beta', rpcUrl: MAINNET, mainnetRpcUrl: undefined }))
  assert.deepEqual(result, { name: 'alice.skr', address: OWNER })
})

test('a name wrapped as an NFT resolves to whoever holds that token', async () => {
  const account = await aliceAccount()
  const tldHouse = await findProgramAddress([utf8('tld_house'), utf8('.skr')], TLD_HOUSE_PROGRAM)
  const nameHouse = await findProgramAddress([utf8('name_house'), bytes(tldHouse.address)], NAME_HOUSE_PROGRAM)
  const record = await findProgramAddress([utf8('nft_record'), bytes(nameHouse.address), bytes(account)], NAME_HOUSE_PROGRAM)
  accounts.set(account, nameRecord(record.address))
  const recordData = new Uint8Array(120)
  recordData[8] = 1
  recordData.set(bytes(USDC), 74)
  accounts.set(record.address, recordData)
  extra.getTokenSupply = () => ({ value: { decimals: 0, amount: '1' } })
  extra.getTokenLargestAccounts = () => ({ value: [{ address: HOLDER_ACCOUNT }] })
  extra.getAccountInfo = () => ({ value: { data: { parsed: { info: { owner: HOLDER } } } } })
  assert.deepEqual(await resolveName('alice.skr', context()), { name: 'alice.skr', address: HOLDER })
})

// ----------------------------------------------------------------------- .sol

test(".sol goes to Bonfida's proxy with the whole name", async () => {
  let asked = ''
  solReply = (name) => {
    asked = name
    return new Response(JSON.stringify({ s: 'ok', result: OWNER }))
  }
  assert.deepEqual(await resolveName('Bonfida.sol', context()), { name: 'bonfida.sol', address: OWNER })
  assert.equal(asked, 'bonfida.sol')
})

test('.sol not found, or not supported by the proxy any more, is said plainly', async () => {
  assert.equal(((await resolveName('nobody.sol', context())) as any).error, 'not_found')
  solReply = () => new Response(JSON.stringify({ s: 'error', result: 'Unsupported TLD' }), { status: 400 })
  assert.equal(((await resolveName('bonfida.sol', context())) as any).error, 'sol_unavailable')
})

test('what is not a name is refused before any lookup', async () => {
  assert.equal(((await resolveName('bob', context())) as any).error, 'bad_name')
  assert.equal(((await resolveName('a.b.skr', context())) as any).error, 'bad_name')
  assert.deepEqual(rpcUrls, [])
})

// ---------------------------------------------------------------------- send

test('preparing a send to alice.skr says the address came from the name', async () => {
  accounts.set(await aliceAccount(), nameRecord(OWNER))
  extra.getBalance = () => ({ value: 2_000_000_000 })
  const quote: any = await runTool('prepare_send', { to: 'alice.skr', amount: 1, token: 'SOL' }, context({ wallet: HOLDER }))
  assert.equal(quote.to_address, OWNER)
  assert.equal(quote.resolved_from, 'alice.skr')
  assert.equal(quote.balance, '2')
  assert.equal(encodeBase58(bytes(quote.to_address)), OWNER)
})
