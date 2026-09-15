/** Bitcoin-alphabet base58, which is how Solana writes keys and signatures. */

const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
const INDEX: Record<string, number> = Object.fromEntries([...ALPHABET].map((c, i) => [c, i]))

export function encodeBase58(bytes: Uint8Array): string {
  let zeros = 0
  while (zeros < bytes.length && bytes[zeros] === 0) zeros++
  let value = 0n
  for (const byte of bytes) value = (value << 8n) | BigInt(byte)
  let out = ''
  while (value > 0n) {
    out = ALPHABET[Number(value % 58n)] + out
    value /= 58n
  }
  return '1'.repeat(zeros) + out
}

/** Null when the text is not base58 at all. */
export function decodeBase58(text: string): Uint8Array | null {
  if (typeof text !== 'string' || text.length === 0) return null
  let zeros = 0
  while (zeros < text.length && text[zeros] === '1') zeros++
  let value = 0n
  for (const char of text) {
    const digit = INDEX[char]
    if (digit === undefined) return null
    value = value * 58n + BigInt(digit)
  }
  const body: number[] = []
  while (value > 0n) {
    body.unshift(Number(value & 0xffn))
    value >>= 8n
  }
  return new Uint8Array([...new Array(zeros).fill(0), ...body])
}

/** A Solana address: base58 that decodes to exactly 32 bytes. */
export function isAddress(text: unknown): text is string {
  return typeof text === 'string' && decodeBase58(text)?.length === 32
}
