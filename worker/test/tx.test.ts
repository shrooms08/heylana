import { test } from 'node:test'
import assert from 'node:assert/strict'
import { associatedTokenAddress, buildTransfer, compactU16, fromBase64, toBase64 } from '../src/tx.ts'
import { PAYER, PAYER_ATA, TOKEN_PROGRAM, TREASURY, TREASURY_ATA, USDC } from './fixtures.ts'

const FRIEND = '4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T'
const REFERENCE = 'Ref1111111111111111111111111111111111111111'
const BLOCKHASH = 'EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N'

/**
 * Made by the app's own builder (sol4k 0.7.0, PaymentTransaction and SendTransaction)
 * for exactly these inputs, before building moved here. The worker's transfers are held
 * to them byte for byte: the same transaction the phone used to hand Seed Vault.
 */
const SOL4K = {
  pay: 'AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAYJfowIh2C/3h3dzzLBfyCbgkLuUqrxMfrNiNDqLG0LBvKkTqbyus+/B3iFE3O1Go6HtCYUWNf9k4ccnNp5Dw4bJtPqjPWsrKjNBSB1EhdcQ871Sl3Znt4goWtVJTc485fcZ1IFXCCz6dh0Zlbd9zhVUH+Hq22HUj5Mdqf6NglqmevG+nrzvtutOj1l82qryXQxsbvkwtL24OR8pgIDRS9dYQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABlCEKWr5G3+Ic7N4w43zx6WcdkRe7fhiZKjoAAAAAACMlyWPTiSJ8bs9ECkUjg2DC1oTmdr/EIQEjnvY2+n4WQbd9uHXZaGT2cvhRs7reawctIXtX1s3kTqM9YV+/wCpzEkOkozS44c7s0P8ldozF5ymD02/RsLDbpEpnVXU5rkCBwYAAQMEBQgBAQgFAgQBAAYKDKCGAQAAAAAABg==',
  sol: 'AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAEDfowIh2C/3h3dzzLBfyCbgkLuUqrxMfrNiNDqLG0LBvIyHPpa3RheiJOl/YgBPsTX4SLe1GNUyt/1DZVjledbYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAzEkOkozS44c7s0P8ldozF5ymD02/RsLDbpEpnVXU5rkBAgIAAQwCAAAAgPD6AgAAAAA=',
  token: 'AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAUIfowIh2C/3h3dzzLBfyCbgkLuUqrxMfrNiNDqLG0LBvLR9fE19GbyQgzJH3P+O5JTxo2ZMOJst1OXI8hEXWMv09PqjPWsrKjNBSB1EhdcQ871Sl3Znt4goWtVJTc485fcMhz6Wt0YXoiTpf2IAT7E1+Ei3tRjVMrf9Q2VY5XnW2DG+nrzvtutOj1l82qryXQxsbvkwtL24OR8pgIDRS9dYQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAjJclj04kifG7PRApFI4NgwtaE5na/xCEBI572Nvp+FkG3fbh12Whk9nL4UbO63msHLSF7V9bN5E6jPWFfv8AqcxJDpKM0uOHO7ND/JXaMxecpg9Nv0bCw26RKZ1V1Oa5AgYGAAEDBAUHAQEHBAIEAQAKDFDDAAAAAAAABg==',
}

test('a Pro payment is byte for byte what the app used to build', async () => {
  const built = await buildTransfer(
    { from: PAYER, to: TREASURY, mint: USDC, tokenProgram: TOKEN_PROGRAM, units: 100000n, decimals: 6, reference: REFERENCE },
    BLOCKHASH,
  )
  assert.equal(toBase64(built.transaction), SOL4K.pay)
})

test('a SOL send is byte for byte what the app used to build', async () => {
  const built = await buildTransfer(
    { from: PAYER, to: FRIEND, mint: null, tokenProgram: null, units: 50_000_000n, decimals: 9, reference: null },
    BLOCKHASH,
  )
  assert.equal(toBase64(built.transaction), SOL4K.sol)
})

test('a token send is byte for byte what the app used to build', async () => {
  const built = await buildTransfer(
    { from: PAYER, to: FRIEND, mint: USDC, tokenProgram: TOKEN_PROGRAM, units: 50000n, decimals: 6, reference: null },
    BLOCKHASH,
  )
  assert.equal(toBase64(built.transaction), SOL4K.token)
})

test('token accounts derive to the independently computed addresses', async () => {
  assert.equal(await associatedTokenAddress(PAYER, USDC, TOKEN_PROGRAM), PAYER_ATA)
  assert.equal(await associatedTokenAddress(TREASURY, USDC, TOKEN_PROGRAM), TREASURY_ATA)
})

test('the transaction is unsigned: one empty slot for the fee payer, who is the first account', async () => {
  const built = await buildTransfer(
    { from: PAYER, to: FRIEND, mint: USDC, tokenProgram: TOKEN_PROGRAM, units: 1n, decimals: 6, reference: REFERENCE },
    BLOCKHASH,
  )
  const bytes = built.transaction
  assert.equal(bytes[0], 1)
  assert.ok(bytes.slice(1, 65).every((b) => b === 0))
  assert.deepEqual(bytes.slice(65), built.message)
  assert.equal(built.message[0], 1, 'one signer: the payer')
})

test('a send carries its reference read-only, SOL or token', async () => {
  for (const mint of [null, USDC]) {
    const built = await buildTransfer(
      { from: PAYER, to: FRIEND, mint, tokenProgram: mint ? TOKEN_PROGRAM : null, units: 5n, decimals: 6, reference: REFERENCE },
      BLOCKHASH,
    )
    assert.ok(toBase64(built.message).length > 0)
    const text = Buffer.from(built.message).toString('hex')
    const ref = Buffer.from((await import('../src/base58.ts')).decodeBase58(REFERENCE)!).toString('hex')
    assert.ok(text.includes(ref), 'the reference is one of the accounts')
  }
})

test('compact-u16 and base64 round trips', () => {
  assert.deepEqual(compactU16(0), [0])
  assert.deepEqual(compactU16(127), [0x7f])
  assert.deepEqual(compactU16(128), [0x80, 0x01])
  assert.deepEqual(compactU16(16384), [0x80, 0x80, 0x01])
  const bytes = Uint8Array.from([0, 1, 2, 250, 255])
  assert.deepEqual(fromBase64(toBase64(bytes)), bytes)
})

test('a zero amount is refused before anything is built', async () => {
  await assert.rejects(buildTransfer({ from: PAYER, to: FRIEND, mint: null, tokenProgram: null, units: 0n, decimals: 9, reference: null }, BLOCKHASH))
})
