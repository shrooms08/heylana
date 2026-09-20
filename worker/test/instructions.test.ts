import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  KNOWN_PROGRAMS, SYSTEM_PROGRAM, amountWords, counterpartiesIn, describe, doesLines, parseMessage,
  powersGranted, programsIn,
} from '../src/instructions.ts'
import { buildTransfer, compileMessage, type Instruction } from '../src/tx.ts'
import { TOKEN_PROGRAM } from '../src/pay.ts'
import { decodeBase58, encodeBase58 } from '../src/base58.ts'

const WALLET = 'EFj9oJ8JcbW9WcnpmEbTJjvBKrBBnEKNWVGCTCyN5L1S'
const TREASURY = '7c2yUJGTRhHhqXKGPqVmVKZwqHaEDpKzENbDNhK4SxSv'
const STRANGER = '9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM'
const USDC = '4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU'
/** A token account of the user's: a real 32-byte address, so the builder accepts it. */
const SOURCE = encodeBase58(new Uint8Array(32).fill(9))

/** A message with one instruction, so a decoder can be checked on its own. */
function messageOf(instructions: Instruction[]) {
  const message = compileMessage(WALLET, instructions, '11111111111111111111111111111111')
  return parseMessage(message)
}

function ix(programId: string, keys: string[], data: number[]): Instruction {
  return {
    programId,
    keys: keys.map((pubkey, index) => ({ pubkey, isSigner: index === keys.length - 1, isWritable: true })),
    data: new Uint8Array(data),
  }
}

/** [code][u64 amount LE][decimals] — the checked token instructions' shape. */
function tokenData(code: number, amount: bigint, decimals?: number): number[] {
  const out = [code]
  let left = amount
  for (let i = 0; i < 8; i++) {
    out.push(Number(left & 0xffn))
    left >>= 8n
  }
  if (decimals !== undefined) out.push(decimals)
  return out
}

test('base units read back as an amount', () => {
  assert.equal(amountWords(50_000n, 6), '0.05')
  assert.equal(amountWords(1_000_000n, 6), '1')
  assert.equal(amountWords(1n, 9), '0.000000001')
  assert.equal(amountWords(0n, 6), '0')
  assert.equal(amountWords(7n, 0), '7')
  // Nothing is invented when the instruction does not say.
  assert.equal(amountWords(5n, null), null)
})

test('a real SOL transfer, as the worker builds it, reads back in plain words', async () => {
  const { message } = await buildTransfer(
    { from: WALLET, to: TREASURY, mint: null, tokenProgram: null, units: 20_000_000n, decimals: 9, reference: null },
    '11111111111111111111111111111111',
  )
  const findings = describe(parseMessage(message))
  assert.equal(findings.length, 1)
  assert.equal(findings[0].kind, 'sol_transfer')
  assert.equal(findings[0].words, 'Sends 0.02 SOL to 7c2y…SxSv.')
  assert.equal(findings[0].counterpartyId, TREASURY)
  assert.equal(findings[0].program, 'System Program')
  assert.equal(findings[0].grantsPower, undefined)
})

test('a real token transfer names the token and the account it lands in', async () => {
  const { message } = await buildTransfer(
    { from: WALLET, to: TREASURY, mint: USDC, tokenProgram: TOKEN_PROGRAM, units: 50_000n, decimals: 6, reference: null },
    '11111111111111111111111111111111',
  )
  const findings = describe(parseMessage(message), 'USDC')
  // Opening the account, then the transfer itself.
  assert.deepEqual(findings.map((f) => f.kind), ['open_token_account', 'token_transfer'])
  assert.match(findings[1].words, /^Sends 0\.05 USDC to /)
  assert.equal(findings[1].units, '50000')
  // The strip leaves out the housekeeping and keeps what moves.
  assert.deepEqual(doesLines(findings), ['Sends 0.05 USDC to ' + findings[1].counterparty + '.'])
})

test('an approval says it is not a transfer, and that it lasts', () => {
  const findings = describe(
    messageOf([ix(TOKEN_PROGRAM, [SOURCE, USDC, STRANGER, WALLET], tokenData(13, 1_000_000_000n, 6))]),
    'USDC',
  )
  assert.equal(findings[0].kind, 'approve')
  assert.equal(findings[0].grantsPower, true)
  assert.match(findings[0].words, /Lets 9WzD…AWWM move up to 1000 USDC out of your account whenever they choose\./)
  assert.match(findings[0].words, /approval, not a transfer: nothing moves now\./)
  assert.equal(findings[0].counterpartyId, STRANGER)
  assert.deepEqual(powersGranted(findings).length, 1)
})

test('an unchecked approval still says how much, without inventing decimals', () => {
  const findings = describe(
    messageOf([ix(TOKEN_PROGRAM, [SOURCE, STRANGER, WALLET], tokenData(4, 42n))]),
  )
  assert.equal(findings[0].kind, 'approve')
  assert.match(findings[0].words, /up to 42 base units tokens/)
})

test('set authority and close account are read out as the handovers they are', () => {
  const authority = describe(messageOf([ix(TOKEN_PROGRAM, [STRANGER, WALLET], [6, 2, 1])]))
  assert.equal(authority[0].kind, 'set_authority')
  assert.equal(authority[0].grantsPower, true)
  assert.match(authority[0].words, /Changes who controls/)

  const closing = describe(messageOf([ix(TOKEN_PROGRAM, [SOURCE, STRANGER, WALLET], [9])]))
  assert.equal(closing[0].kind, 'close_account')
  assert.equal(closing[0].grantsPower, true)
  assert.match(closing[0].words, /Closes a token account and sends what it holds to 9WzD…AWWM\./)
})

test('a revoke and a burn are named for what they are', () => {
  assert.equal(describe(messageOf([ix(TOKEN_PROGRAM, [STRANGER, WALLET], [5])]))[0].kind, 'revoke')
  const burn = describe(messageOf([ix(TOKEN_PROGRAM, [STRANGER, USDC, WALLET], tokenData(15, 1_000n, 6))]), 'USDC')
  assert.equal(burn[0].kind, 'burn')
  assert.match(burn[0].words, /Destroys 0\.001 USDC for good\./)
})

test('a program it has never heard of is named, never guessed at', () => {
  const stranger = 'Stake11111111111111111111111111111111111111'
  const findings = describe(messageOf([ix(stranger, [WALLET], [1, 2, 3, 4])]))
  assert.equal(findings[0].kind, 'unknown_program')
  assert.equal(findings[0].words, "Calls a program I don't recognise (Stak…1111).")
  assert.equal(findings[0].program, 'Stak…1111')
  assert.deepEqual(programsIn(findings), [stranger])
})

test('the quiet instructions come first in the lines the strip shows', () => {
  const findings = describe(
    messageOf([
      ix(SYSTEM_PROGRAM, [WALLET, TREASURY], [2, 0, 0, 0, ...tokenData(0, 1_000_000n).slice(1)]),
      ix(TOKEN_PROGRAM, [SOURCE, USDC, STRANGER, WALLET], tokenData(13, 5_000_000n, 6)),
    ]),
    'USDC',
  )
  const lines = doesLines(findings)
  assert.match(lines[0], /^Lets /, 'the approval is what the user needs to hear first')
  assert.match(lines[1], /^Sends /)
  assert.deepEqual(counterpartiesIn(findings).sort(), [STRANGER, TREASURY].sort())
})

test('a message it cannot read throws rather than guessing', () => {
  assert.throws(() => parseMessage(new Uint8Array([0x80, 1, 1])), /versioned/)
  assert.throws(() => parseMessage(new Uint8Array([1, 0, 0])), /short message/)
})

test('every program it claims to know is named', () => {
  for (const [id, name] of Object.entries(KNOWN_PROGRAMS)) {
    assert.equal(decodeBase58(id).length, 32, `${name} is an address`)
    assert.ok(name.length > 3)
  }
})
