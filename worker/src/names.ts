/**
 * Names to addresses.
 *
 * .skr names (Seeker IDs) are AllDomains name-service records on **mainnet**,
 * whatever cluster payments use. They are read the way Solana Mobile's
 * seeker-domains resolver reads them, with no SDK: derive the name's account and
 * read its owner — one RPC call for an ordinary name.
 *
 * .sol names go to Bonfida's public SNS proxy.
 *
 * Nothing is cached: names change hands, and a stale owner is a wrong recipient.
 */
import { decodeBase58, encodeBase58, isAddress } from './base58.ts'
import { findProgramAddress, sha256, utf8 } from './pda.ts'
import type { RpcOptions } from './rpc.ts'
import type { ToolContext } from './tools.ts'

/** Names live on mainnet whatever the worker's own cluster is. */
const MAINNET: RpcOptions = { cluster: 'mainnet-beta' }

export const ANS_PROGRAM = 'ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK'
export const TLD_HOUSE_PROGRAM = 'TLDHkysf5pCnKsVA4gXpNvmy7psXLPEu4LAdDJthT9S'
export const NAME_HOUSE_PROGRAM = 'NH3uX6FtVE2fNREAioP7hm5RaozotZxeL6khU1EHx51'
export const ROOT_ANS = '3mX9b4AZaQehNoQGfckVcmgmA6bkBoFcbLj9RMmMyNcU'
export const SNS_PROXY_URL = 'https://sdk-proxy-v2.sns.id'

const HASH_PREFIX = 'ALT Name Service'
const HEADER_SIZE = 200
const OWNER_OFFSET = 40
const EXPIRES_AT_OFFSET = 104
const ACTIVE_RECORD = 1
const NFT_MINT_OFFSET = 74
const ZERO_32 = new Uint8Array(32)

export type Resolved = { name: string; address: string }
type Failure = { error: string; detail: string }

const notFound = (name: string): Failure => ({ error: 'not_found', detail: `No one owns ${name}.` })

function bytesOf(base64: unknown): Uint8Array | null {
  if (typeof base64 !== 'string') return null
  try {
    return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
  } catch {
    return null
  }
}

/** The name account for [name] under [parent] (the root when absent). */
export async function deriveNameAccount(name: string, parent?: string): Promise<string> {
  const hashed = await sha256(utf8(HASH_PREFIX + name))
  const parentBytes = parent ? decodeBase58(parent)! : ZERO_32
  return (await findProgramAddress([hashed, ZERO_32, parentBytes], ANS_PROGRAM)).address
}

async function accountBytes(address: string, context: ToolContext): Promise<Uint8Array | null> {
  const info = await context.rpc('getAccountInfo', [address, { encoding: 'base64' }], MAINNET)
  return bytesOf(info?.value?.data?.[0])
}

/** A name wrapped as an NFT: its owner is whoever holds that one token. */
async function tokenizedOwner(record: string, context: ToolContext): Promise<string | null> {
  const data = await accountBytes(record, context)
  if (!data || data.length < NFT_MINT_OFFSET + 32 || data[8] !== ACTIVE_RECORD) return null
  const mint = encodeBase58(data.slice(NFT_MINT_OFFSET, NFT_MINT_OFFSET + 32))
  const supply = await context.rpc('getTokenSupply', [mint], MAINNET)
  if (supply?.value?.decimals !== 0 || supply?.value?.amount !== '1') return null
  const largest = await context.rpc('getTokenLargestAccounts', [mint], MAINNET)
  const holder = largest?.value?.[0]?.address
  if (!holder) return null
  const account = await context.rpc('getAccountInfo', [holder, { encoding: 'jsonParsed' }], MAINNET)
  const owner = account?.value?.data?.parsed?.info?.owner
  return isAddress(owner) ? owner : null
}

async function resolveSkr(label: string, context: ToolContext): Promise<Resolved | Failure> {
  const name = `${label}.skr`
  if (!context.rpc.has('mainnet-beta')) {
    return { error: 'names_unavailable', detail: '.skr names live on mainnet, and no mainnet connection is set up.' }
  }

  const parent = await deriveNameAccount('.skr', ROOT_ANS)
  const account = await deriveNameAccount(label, parent)
  const data = await accountBytes(account, context)
  if (!data || data.length < HEADER_SIZE) return notFound(name)

  const expiresAt = new DataView(data.buffer, data.byteOffset, data.byteLength).getBigUint64(EXPIRES_AT_OFFSET, true)
  if (expiresAt > 0n && Number(expiresAt) * 1000 < context.now()) return notFound(name)

  let owner = encodeBase58(data.slice(OWNER_OFFSET, OWNER_OFFSET + 32))
  const tldHouse = await findProgramAddress([utf8('tld_house'), utf8('.skr')], TLD_HOUSE_PROGRAM)
  const nameHouse = await findProgramAddress([utf8('name_house'), decodeBase58(tldHouse.address)!], NAME_HOUSE_PROGRAM)
  const record = await findProgramAddress(
    [utf8('nft_record'), decodeBase58(nameHouse.address)!, decodeBase58(account)!],
    NAME_HOUSE_PROGRAM,
  )
  if (owner === record.address) {
    const holder = await tokenizedOwner(record.address, context)
    if (!holder) return notFound(name)
    owner = holder
  }
  return { name, address: owner }
}

async function resolveSol(name: string, context: ToolContext): Promise<Resolved | Failure> {
  const res = await fetch(`${SNS_PROXY_URL}/resolve/${encodeURIComponent(name)}`, { signal: context.signal })
  const body: any = await res.json().catch(() => null)
  if (res.ok && body?.s === 'ok' && isAddress(body.result)) return { name, address: body.result }
  if (res.status === 404) return notFound(name)
  if (res.status === 400 && /unsupported/i.test(String(body?.result))) {
    return { error: 'sol_unavailable', detail: "Bonfida's resolver is not answering for .sol names right now." }
  }
  return { error: 'lookup_failed', detail: 'The name service did not answer.' }
}

export async function resolveName(given: string, context: ToolContext): Promise<Resolved | Failure> {
  const name = given.trim().toLowerCase()
  if (name.endsWith('.skr')) {
    const label = name.slice(0, -'.skr'.length)
    if (!/^[a-z0-9-]{1,63}$/.test(label)) return { error: 'bad_name', detail: `${given} is not a .skr name.` }
    return resolveSkr(label, context)
  }
  if (name.endsWith('.sol')) {
    const label = name.slice(0, -'.sol'.length)
    if (!/^[^\s./]{1,63}$/u.test(label)) return { error: 'bad_name', detail: `${given} is not a .sol name.` }
    return resolveSol(name, context)
  }
  return { error: 'bad_name', detail: 'Names end in .skr or .sol.' }
}
