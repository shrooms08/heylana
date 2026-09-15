/** Shared test fixtures. Imported by tests; registers no tests of its own. */

export const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v'
export const TOKEN_PROGRAM = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA'
export const TREASURY = '7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU'
export const TREASURY_ATA = 'C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH'
export const PAYER = '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'
export const PAYER_ATA = 'FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B'
export const STRANGER = '4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T'

export interface TxOptions {
  payer: string
  mint: string
  amount: string
  destOwner: string
  sender: string
  reference: string | null
  err: unknown
  type: 'transfer' | 'transferChecked'
  inner: boolean
}

/** A transaction as getTransaction with jsonParsed encoding returns it. */
export function paymentTx(over: Partial<TxOptions> = {}) {
  const o: TxOptions = {
    payer: PAYER, mint: USDC, amount: '100000', destOwner: TREASURY, sender: PAYER,
    reference: 'Ref1111111111111111111111111111111111111111', err: null, type: 'transferChecked', inner: false,
    ...over,
  }
  const keys = [o.payer, PAYER_ATA, TREASURY_ATA, o.mint, TOKEN_PROGRAM]
  if (o.reference) keys.push(o.reference)
  const info =
    o.type === 'transferChecked'
      ? { source: PAYER_ATA, destination: TREASURY_ATA, mint: o.mint, authority: o.sender, tokenAmount: { amount: o.amount, decimals: 6 } }
      : { source: PAYER_ATA, destination: TREASURY_ATA, authority: o.sender, amount: o.amount }
  const ix = { program: 'spl-token', programId: TOKEN_PROGRAM, parsed: { type: o.type, info } }
  const sourceOwner = o.sender === STRANGER ? STRANGER : o.payer
  const balance = (index: number, owner: string) => ({ accountIndex: index, mint: o.mint, owner, uiTokenAmount: {} })
  return {
    slot: 1,
    blockTime: 1,
    meta: {
      err: o.err,
      preTokenBalances: [balance(1, sourceOwner), balance(2, o.destOwner)],
      postTokenBalances: [balance(1, sourceOwner), balance(2, o.destOwner)],
      innerInstructions: o.inner ? [{ index: 0, instructions: [ix] }] : [],
    },
    transaction: {
      message: {
        accountKeys: keys.map((pubkey, i) => ({ pubkey, signer: i === 0, writable: i < 3, source: 'transaction' })),
        instructions: o.inner ? [] : [ix],
      },
    },
  }
}
