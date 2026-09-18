/**
 * Solana transactions, built here and nowhere else: a SOL transfer, or a token
 * `transferChecked` into the recipient's associated token account (created first,
 * idempotently, when they may not have one). Built unsigned, with one empty
 * signature slot for the fee payer — the shape a wallet fills in. The worker never
 * signs and never submits: Seed Vault does both, after the user approves there.
 *
 * Legacy messages only, and no dependency: base58, the account ordering, compact-u16
 * lengths and the instruction data are written out here and held to transactions the
 * app's own builder (sol4k) made, byte for byte, in test/tx.test.ts.
 */
import { decodeBase58, encodeBase58 } from './base58.ts'
import { findProgramAddress } from './pda.ts'
import { SYSTEM_PROGRAM, TOKEN_PROGRAM } from './solana.ts'

export const ASSOCIATED_TOKEN_PROGRAM = 'ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL'

export interface AccountMeta {
  pubkey: string
  signer: boolean
  writable: boolean
}

export interface Instruction {
  programId: string
  keys: AccountMeta[]
  data: Uint8Array
}

/** Where [owner] holds [mint], under whichever token program owns the mint. */
export async function associatedTokenAddress(owner: string, mint: string, tokenProgram: string = TOKEN_PROGRAM): Promise<string> {
  const seeds = [owner, tokenProgram, mint].map((key) => bytesOf(key))
  return (await findProgramAddress(seeds, ASSOCIATED_TOKEN_PROGRAM)).address
}

/** System Program transfer: instruction 2, then the lamports as a little-endian u64. */
export function systemTransfer(from: string, to: string, lamports: bigint): Instruction {
  if (lamports <= 0n) throw new Error('lamports must be positive')
  const data = new Uint8Array(12)
  const view = new DataView(data.buffer)
  view.setUint32(0, 2, true)
  view.setBigUint64(4, lamports, true)
  return {
    programId: SYSTEM_PROGRAM,
    keys: [
      { pubkey: from, signer: true, writable: true },
      { pubkey: to, signer: false, writable: true },
    ],
    data,
  }
}

/** Creates [owner]'s token account for [mint] if it is missing; a no-op if it is there. */
export function createIdempotent(payer: string, account: string, owner: string, mint: string, tokenProgram: string): Instruction {
  return {
    programId: ASSOCIATED_TOKEN_PROGRAM,
    keys: [
      { pubkey: payer, signer: true, writable: true },
      { pubkey: account, signer: false, writable: true },
      { pubkey: owner, signer: false, writable: false },
      { pubkey: mint, signer: false, writable: false },
      { pubkey: SYSTEM_PROGRAM, signer: false, writable: false },
      { pubkey: tokenProgram, signer: false, writable: false },
    ],
    data: new Uint8Array([1]),
  }
}

/**
 * `transferChecked`: instruction 12, the amount as a little-endian u64, then the
 * decimals. A reference goes last, read-only and not a signer — the token program
 * ignores accounts past the ones it needs, and the reference is how this exact
 * transfer is found on chain without its signature (the Solana Pay way).
 */
export function transferChecked(options: {
  source: string
  mint: string
  destination: string
  owner: string
  amount: bigint
  decimals: number
  reference: string | null
  tokenProgram: string
}): Instruction {
  if (options.amount <= 0n) throw new Error('amount must be positive')
  if (options.decimals < 0 || options.decimals > 255) throw new Error('decimals must fit in a byte')
  const data = new Uint8Array(10)
  const view = new DataView(data.buffer)
  data[0] = 12
  view.setBigUint64(1, options.amount, true)
  data[9] = options.decimals
  const keys: AccountMeta[] = [
    { pubkey: options.source, signer: false, writable: true },
    { pubkey: options.mint, signer: false, writable: false },
    { pubkey: options.destination, signer: false, writable: true },
    { pubkey: options.owner, signer: true, writable: false },
  ]
  if (options.reference) keys.push({ pubkey: options.reference, signer: false, writable: false })
  return { programId: options.tokenProgram, keys, data }
}

/** What a transfer has to do. [mint] null is SOL. */
export interface TransferPlan {
  from: string
  to: string
  mint: string | null
  tokenProgram: string | null
  /** Lamports for SOL, base units for a token. */
  units: bigint
  decimals: number
  /** A fresh address carried read-only, so the transfer can be found without its signature. */
  reference: string | null
}

export async function transferInstructions(plan: TransferPlan): Promise<Instruction[]> {
  if (!plan.mint) {
    const transfer = systemTransfer(plan.from, plan.to, plan.units)
    if (plan.reference) transfer.keys.push({ pubkey: plan.reference, signer: false, writable: false })
    return [transfer]
  }
  const program = plan.tokenProgram ?? TOKEN_PROGRAM
  const toAccount = await associatedTokenAddress(plan.to, plan.mint, program)
  const fromAccount = await associatedTokenAddress(plan.from, plan.mint, program)
  return [
    createIdempotent(plan.from, toAccount, plan.to, plan.mint, program),
    transferChecked({
      source: fromAccount,
      mint: plan.mint,
      destination: toAccount,
      owner: plan.from,
      amount: plan.units,
      decimals: plan.decimals,
      reference: plan.reference,
      tokenProgram: program,
    }),
  ]
}

/**
 * A legacy message: the fee payer first, then signers that write, signers that only
 * read, non-signers that write, non-signers that only read, each group in the order
 * the accounts first appear, with program ids after every instruction account.
 */
export function compileMessage(feePayer: string, instructions: Instruction[], recentBlockhash: string): Uint8Array {
  const metas = new Map<string, { signer: boolean; writable: boolean; order: number }>()
  const note = (pubkey: string, signer: boolean, writable: boolean) => {
    const seen = metas.get(pubkey)
    if (seen) {
      seen.signer ||= signer
      seen.writable ||= writable
    } else {
      metas.set(pubkey, { signer, writable, order: metas.size })
    }
  }
  // Instruction accounts first, then the program ids; an account that is also a
  // program id takes the program's place (how sol4k, the app's old builder, orders them).
  const programs = new Set(instructions.map((ix) => ix.programId))
  note(feePayer, true, true)
  for (const ix of instructions) {
    for (const key of ix.keys) if (!programs.has(key.pubkey)) note(key.pubkey, key.signer, key.writable)
  }
  for (const ix of instructions) {
    note(ix.programId, false, false)
    for (const key of ix.keys) if (key.pubkey === ix.programId || programs.has(key.pubkey)) note(key.pubkey, key.signer, key.writable)
  }
  const rank = (m: { signer: boolean; writable: boolean }) => (m.signer ? (m.writable ? 0 : 1) : m.writable ? 2 : 3)
  const ordered = [...metas.entries()].sort(([a, ma], [b, mb]) => {
    if (a === feePayer) return -1
    if (b === feePayer) return 1
    return rank(ma) - rank(mb) || ma.order - mb.order
  })
  const index = new Map(ordered.map(([key], i) => [key, i]))
  const signers = ordered.filter(([, m]) => m.signer)
  const readonlySigned = signers.filter(([, m]) => !m.writable).length
  const readonlyUnsigned = ordered.filter(([, m]) => !m.signer && !m.writable).length

  const out: number[] = [signers.length, readonlySigned, readonlyUnsigned]
  out.push(...compactU16(ordered.length))
  for (const [key] of ordered) out.push(...bytesOf(key))
  out.push(...bytesOf(recentBlockhash))
  out.push(...compactU16(instructions.length))
  for (const ix of instructions) {
    out.push(index.get(ix.programId)!)
    out.push(...compactU16(ix.keys.length))
    for (const key of ix.keys) out.push(index.get(key.pubkey)!)
    out.push(...compactU16(ix.data.length))
    out.push(...ix.data)
  }
  return new Uint8Array(out)
}

/** The whole transaction, unsigned: one empty slot per required signature, then the message. */
export function unsignedTransaction(message: Uint8Array): Uint8Array {
  const signatures = message[0]
  const out = new Uint8Array(compactU16(signatures).length + signatures * 64 + message.length)
  out.set(compactU16(signatures), 0)
  out.set(message, out.length - message.length)
  return out
}

export async function buildTransfer(plan: TransferPlan, recentBlockhash: string): Promise<{ message: Uint8Array; transaction: Uint8Array }> {
  const message = compileMessage(plan.from, await transferInstructions(plan), recentBlockhash)
  return { message, transaction: unsignedTransaction(message) }
}

/** Solana's compact-u16: seven bits a byte, low first, the high bit meaning "more". */
export function compactU16(value: number): number[] {
  const out: number[] = []
  let rest = value
  for (;;) {
    const byte = rest & 0x7f
    rest >>= 7
    if (rest === 0) {
      out.push(byte)
      return out
    }
    out.push(byte | 0x80)
  }
}

export function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

export function fromBase64(text: string): Uint8Array {
  const binary = atob(text)
  return Uint8Array.from(binary, (c) => c.charCodeAt(0))
}

function bytesOf(key: string): Uint8Array {
  const bytes = decodeBase58(key)
  if (!bytes || bytes.length !== 32) throw new Error('not a 32-byte key')
  return bytes
}

export { encodeBase58 }
