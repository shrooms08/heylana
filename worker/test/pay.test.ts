import { test } from 'node:test'
import assert from 'node:assert/strict'
import { checkPayment, decimalToUnits, newReference, usdToTokenUnits, type Quote } from '../src/pay.ts'
import { isAddress } from '../src/base58.ts'

import { PAYER, STRANGER, TREASURY, USDC, paymentTx } from './fixtures.ts'

const OTHER_MINT = 'So11111111111111111111111111111111111111112'
const REFERENCE = 'Ref1111111111111111111111111111111111111111'

const quote: Quote = {
  currency: 'usdc', mint: USDC, amount: '100000', decimals: 6,
  token_program: 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA', treasury: TREASURY,
  reference: REFERENCE, expires_at: '2026-09-15T12:10:00.000Z', pubkey: PAYER,
}

test('dollars become USDC base units exactly', () => {
  assert.equal(decimalToUnits('15', 6), 15_000_000n)
  assert.equal(decimalToUnits('0.10', 6), 100_000n)
  assert.equal(decimalToUnits('0.1', 6), 100_000n)
  assert.throws(() => decimalToUnits('abc', 6))
})

test('an SKR quote covers the price, rounded up to the next base unit', () => {
  // $15 at $0.05 is exactly 300 SKR.
  assert.equal(usdToTokenUnits('15', 0.05, 6), 300_000_000n)
  // $0.10 at $0.03 is 3.333… SKR: rounded up, never down.
  assert.equal(usdToTokenUnits('0.10', 0.03, 6), 3_333_334n)
  assert.equal(usdToTokenUnits('0.10', 0.03, 9), 3_333_333_334n)
  assert.throws(() => usdToTokenUnits('15', 0, 6))
})

test('a reference is a fresh address every time', () => {
  const a = newReference()
  assert.equal(isAddress(a), true)
  assert.notEqual(a, newReference())
})

test('the treasury paying itself is refused as a self-payment', () => {
  const own = { ...quote, pubkey: TREASURY }
  assert.deepEqual(checkPayment(paymentTx({ payer: TREASURY, sender: TREASURY }), own), { ok: false, reason: 'self_payment' })
})

test('the payment asked for is accepted', () => {
  assert.deepEqual(checkPayment(paymentTx(), quote), { ok: true, amount: '100000' })
})

test('a plain transfer is accepted too, the mint read from the balances', () => {
  assert.equal(checkPayment(paymentTx({ type: 'transfer' }), quote).ok, true)
})

test('a transfer made inside another program is found', () => {
  assert.equal(checkPayment(paymentTx({ inner: true }), quote).ok, true)
})

test('paying more than asked is accepted', () => {
  assert.equal(checkPayment(paymentTx({ amount: '250000' }), quote).ok, true)
})

test('the wrong token is refused', () => {
  assert.deepEqual(checkPayment(paymentTx({ mint: OTHER_MINT }), quote), { ok: false, reason: 'wrong_mint' })
})

test('too little is refused', () => {
  assert.deepEqual(checkPayment(paymentTx({ amount: '99999' }), quote), { ok: false, reason: 'short_amount' })
})

test('paying someone other than the treasury is refused', () => {
  assert.deepEqual(checkPayment(paymentTx({ destOwner: STRANGER }), quote), { ok: false, reason: 'wrong_destination' })
})

test('a payment out of another wallet is refused', () => {
  assert.deepEqual(checkPayment(paymentTx({ sender: STRANGER }), quote), { ok: false, reason: 'wrong_sender' })
})

test('a payment without the quote’s reference is refused', () => {
  assert.deepEqual(checkPayment(paymentTx({ reference: null }), quote), { ok: false, reason: 'no_reference' })
})

test('a transaction the chain has not confirmed is not accepted yet', () => {
  assert.deepEqual(checkPayment(null, quote), { ok: false, reason: 'not_confirmed' })
})

test('a transaction that failed on chain is refused', () => {
  assert.deepEqual(checkPayment(paymentTx({ err: { InstructionError: [1, 'Custom'] } }), quote), {
    ok: false, reason: 'failed_on_chain',
  })
})

test('a transaction with no token transfer at all is refused', () => {
  const tx: any = paymentTx()
  tx.transaction.message.instructions = []
  assert.deepEqual(checkPayment(tx, quote), { ok: false, reason: 'no_transfer' })
})
