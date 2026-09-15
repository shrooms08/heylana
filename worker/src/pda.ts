/**
 * Program-derived addresses, without an SDK.
 *
 * A PDA is sha256(seeds ‖ bump ‖ program id ‖ "ProgramDerivedAddress") for the
 * highest bump whose result is NOT a point on the ed25519 curve — so no private
 * key can exist for it. Web Crypto gives sha256; the curve check is here.
 */
import { decodeBase58, encodeBase58 } from './base58.ts'

const P = 2n ** 255n - 19n

function mod(value: bigint): bigint {
  const r = value % P
  return r >= 0n ? r : r + P
}

function power(base: bigint, exponent: bigint): bigint {
  let result = 1n
  let b = mod(base)
  let e = exponent
  while (e > 0n) {
    if (e & 1n) result = (result * b) % P
    b = (b * b) % P
    e >>= 1n
  }
  return result
}

/** d = -121665 / 121666 (mod p), the curve's constant. */
const D = mod(-121665n * power(121666n, P - 2n))

/**
 * Whether 32 bytes decompress to an ed25519 point, the way the Solana runtime
 * decides it: y is read little-endian with the sign bit dropped, and the point
 * exists when u/v = (y² − 1)/(d·y² + 1) has a square root.
 */
export function isOnCurve(bytes: Uint8Array): boolean {
  if (bytes.length !== 32) return false
  let y = 0n
  for (let i = 31; i >= 0; i--) y = (y << 8n) | BigInt(i === 31 ? bytes[i] & 0x7f : bytes[i])
  y = mod(y)
  const y2 = (y * y) % P
  const u = mod(y2 - 1n)
  const v = mod(D * y2 + 1n)
  const v3 = power(v, 3n)
  const v7 = power(v, 7n)
  const x = mod(u * v3 % P * power((u * v7) % P, (P - 5n) / 8n))
  const check = mod(v * x % P * x)
  return check === u || check === mod(-u)
}

export async function sha256(...parts: Uint8Array[]): Promise<Uint8Array> {
  const total = parts.reduce((sum, part) => sum + part.length, 0)
  const joined = new Uint8Array(total)
  let offset = 0
  for (const part of parts) {
    joined.set(part, offset)
    offset += part.length
  }
  return new Uint8Array(await crypto.subtle.digest('SHA-256', joined))
}

export const utf8 = (text: string) => new TextEncoder().encode(text)

const PDA_MARKER = utf8('ProgramDerivedAddress')

/** The address and bump for [seeds] under [programId]. Seeds are at most 32 bytes each. */
export async function findProgramAddress(seeds: Uint8Array[], programId: string): Promise<{ address: string; bump: number }> {
  const program = decodeBase58(programId)
  if (!program || program.length !== 32) throw new Error('bad program id')
  for (const seed of seeds) if (seed.length > 32) throw new Error('seed longer than 32 bytes')
  for (let bump = 255; bump >= 0; bump--) {
    const hash = await sha256(...seeds, Uint8Array.of(bump), program, PDA_MARKER)
    if (!isOnCurve(hash)) return { address: encodeBase58(hash), bump }
  }
  throw new Error('no viable bump')
}
