/**
 * Did the send the user signed land the way Heylana prepared it?
 *
 * Heylana prepares a send and the user signs it in Seed Vault; the worker never
 * builds or signs anything. This only reads the confirmed transaction back and
 * checks it did what the confirmation strip said: this token, to this
 * recipient, at least this much, from this wallet.
 */
import { decimalToUnits } from './pay.ts'
import type { SendQuote } from './tools.ts'

export interface PreparedSend extends SendQuote {
  /** The wallet that asked, from its session. */
  from: string
}

export type SendVerdict = { ok: true } | { ok: false; reason: string }

export function checkSend(tx: any, sent: PreparedSend): SendVerdict {
  if (!tx) return { ok: false, reason: 'not_confirmed' }
  if (tx.meta?.err) return { ok: false, reason: 'failed_on_chain' }

  const units = decimalToUnits(sent.amount, sent.decimals)
  const keys: string[] = (tx.transaction?.message?.accountKeys ?? []).map((k: any) => (typeof k === 'string' ? k : k?.pubkey))
  const instructions = [
    ...(tx.transaction?.message?.instructions ?? []),
    ...(tx.meta?.innerInstructions ?? []).flatMap((inner: any) => inner.instructions ?? []),
  ]

  if (!sent.mint) {
    const landed = instructions.some((ix: any) => {
      const info = ix?.parsed?.info
      return (
        ix?.program === 'system' &&
        ix?.parsed?.type === 'transfer' &&
        info?.source === sent.from &&
        info?.destination === sent.to_address &&
        BigInt(info?.lamports ?? 0) >= units
      )
    })
    return landed ? { ok: true } : { ok: false, reason: 'no_matching_transfer' }
  }

  const balances = [...(tx.meta?.preTokenBalances ?? []), ...(tx.meta?.postTokenBalances ?? [])]
  const accountOf = (address: string) =>
    balances.find((b: any) => keys[b.accountIndex] === address) as { mint?: string; owner?: string } | undefined

  const landed = instructions.some((ix: any) => {
    if (ix?.program !== 'spl-token' && ix?.program !== 'spl-token-2022') return false
    if (ix?.parsed?.type !== 'transfer' && ix?.parsed?.type !== 'transferChecked') return false
    const info = ix.parsed.info ?? {}
    const destination = accountOf(info.destination)
    const source = accountOf(info.source)
    const mint = info.mint ?? destination?.mint ?? source?.mint
    const amount = BigInt(info.tokenAmount?.amount ?? info.amount ?? '0')
    const signer = info.authority ?? info.multisigAuthority ?? source?.owner
    return (
      mint === sent.mint &&
      destination?.owner === sent.to_address &&
      amount >= units &&
      (signer === sent.from || source?.owner === sent.from)
    )
  })
  return landed ? { ok: true } : { ok: false, reason: 'no_matching_transfer' }
}
