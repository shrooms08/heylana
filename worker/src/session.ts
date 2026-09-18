/**
 * Proving a wallet is yours, and remembering that you did.
 *
 * Sign-in is a message the wallet signs — nothing on-chain, nothing that moves
 * funds. The worker checks the ed25519 signature against the public key, then
 * hands back a session: the wallet address and an expiry, sealed with an HMAC so
 * it cannot be edited. No key of the user's is ever seen or stored.
 */
import { decodeBase58 } from './base58.ts'

export const SESSION_DAYS = 30
const DAY_MS = 86_400_000

/** What the wallet is asked to sign. Plain words, so Seed Vault shows something readable. */
export function challengeMessage(pubkey: string, nonce: string, issuedAt: string): string {
  return (
    'Heylana wants you to sign in with your Solana account:\n' +
    `${pubkey}\n\n` +
    'This only proves the wallet is yours. It is not a transaction and costs nothing.\n\n' +
    `Nonce: ${nonce}\n` +
    `Issued At: ${issuedAt}`
  )
}

export function randomNonce(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** True only when [signature] is [pubkey]'s ed25519 signature over [message]. */
export async function verifySignature(pubkey: string, message: string, signature: string): Promise<boolean> {
  const key = decodeBase58(pubkey)
  const sig = decodeBase58(signature)
  if (!key || key.length !== 32 || !sig || sig.length !== 64) return false
  try {
    const imported = await crypto.subtle.importKey('raw', key, { name: 'Ed25519' }, false, ['verify'])
    return await crypto.subtle.verify({ name: 'Ed25519' }, imported, sig, new TextEncoder().encode(message))
  } catch {
    return false
  }
}

/** A session for [pubkey], good for [SESSION_DAYS] from [now]. */
export async function signSession(pubkey: string, secret: string, now: number): Promise<string> {
  const payload = base64url(new TextEncoder().encode(JSON.stringify({ sub: pubkey, exp: now + SESSION_DAYS * DAY_MS })))
  const mac = await hmac(secret, payload)
  return `${payload}.${base64url(mac)}`
}

/** The wallet a session belongs to, or null if it is forged, edited or expired. */
export async function readSession(token: string, secret: string, now: number): Promise<string | null> {
  if (!secret || typeof token !== 'string') return null
  const [payload, mac] = token.split('.')
  if (!payload || !mac) return null
  try {
    const key = await hmacKey(secret)
    const valid = await crypto.subtle.verify('HMAC', key, fromBase64url(mac), new TextEncoder().encode(payload))
    if (!valid) return null
    const claims = JSON.parse(new TextDecoder().decode(fromBase64url(payload)))
    if (typeof claims.sub !== 'string' || typeof claims.exp !== 'number') return null
    if (claims.exp <= now) return null
    return claims.sub
  } catch {
    return null
  }
}

/** How long a confirmation stays good: long enough to open the wallet, not long enough to keep. */
export const CONFIRMATION_MS = 5 * 60 * 1000

export interface Confirmation {
  kind: string
  subject: string
  holder: string
}

/**
 * A confirmation token: this [kind] of R3 action, for this [subject] (a send's id, a
 * quote's reference, a proposed action's id), confirmed by this [holder] (the account
 * key), good for [CONFIRMATION_MS]. Sealed like a session, and never mistaken for one:
 * it has no subject wallet, and a session has no kind.
 */
export async function signConfirmation(confirmation: Confirmation, secret: string, now: number): Promise<string> {
  const claims = { t: 'confirm', k: confirmation.kind, s: confirmation.subject, h: confirmation.holder, exp: now + CONFIRMATION_MS }
  const payload = base64url(new TextEncoder().encode(JSON.stringify(claims)))
  return `${payload}.${base64url(await hmac(secret, payload))}`
}

/** What a confirmation token confirms, or null if it is forged, edited, expired or not one. */
export async function readConfirmation(token: unknown, secret: string, now: number): Promise<Confirmation | null> {
  if (!secret || typeof token !== 'string') return null
  const [payload, mac] = token.split('.')
  if (!payload || !mac) return null
  try {
    const key = await hmacKey(secret)
    const valid = await crypto.subtle.verify('HMAC', key, fromBase64url(mac), new TextEncoder().encode(payload))
    if (!valid) return null
    const claims = JSON.parse(new TextDecoder().decode(fromBase64url(payload)))
    if (claims.t !== 'confirm' || typeof claims.exp !== 'number' || claims.exp <= now) return null
    if (typeof claims.k !== 'string' || typeof claims.s !== 'string' || typeof claims.h !== 'string') return null
    return { kind: claims.k, subject: claims.s, holder: claims.h }
  } catch {
    return null
  }
}

async function hmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, [
    'sign',
    'verify',
  ])
}

async function hmac(secret: string, text: string): Promise<Uint8Array> {
  const key = await hmacKey(secret)
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(text)))
}

function base64url(bytes: Uint8Array): string {
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64url(text: string): Uint8Array {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((text.length + 3) % 4)
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
}
