/**
 * Build, then simulate, before anyone is asked to sign.
 *
 * Every send and every Pro payment goes through here: the worker builds the exact
 * transfer (tx.ts), runs it through the RPC's `simulateTransaction` against the
 * cluster it will land on, and turns the outcome into plain words. A transfer that
 * fails simulation is never handed out: the app shows the reason, and the wallet
 * never opens. One that passes comes with a preview — from, to (and who that is),
 * amount, token, fee, any account it opens, the programs it touches, the cluster —
 * that the confirmation strip shows before Confirm can be tapped.
 *
 * Nothing here signs, and nothing here submits. Seed Vault does both.
 */
import { associatedTokenAddress, buildTransfer, toBase64, type TransferPlan } from './tx.ts'
import type { Rpc } from './rpc.ts'
import { KNOWN, SOL_DECIMALS, TOKEN_2022_PROGRAM, short, unitsToDecimal } from './solana.ts'
import { counterpartiesIn, describe, doesLines, parseMessage, powersGranted, programsIn } from './instructions.ts'

/** What the strip shows, in its own words; addresses only ever shortened. */
export interface Preview {
  from: string
  from_label: string
  to: string
  to_label: string
  amount: string
  token: string
  fee_sol: string
  /** SOL the sender pays to open the recipient's token account; "0" when it is already there. */
  account_rent_sol: string
  creates_account: boolean
  programs: string[]
  cluster: string
  /**
   * What the transaction's instructions actually do, read back out of the bytes that were
   * just built — not from what was asked for. A transfer is one line; anything that hands
   * someone power over an account says so in its own.
   */
  does: string[]
  /** True when any instruction would let someone else move or take over an account. */
  grants_power: boolean
}

export type Simulation =
  | { ok: true; units_consumed: number | null }
  | { ok: false; reason: SimReason; words: string }

export type SimReason =
  | 'unexpected_drain'
  | 'grants_power'
  | 'not_enough_token'
  | 'not_enough_sol'
  | 'no_sol_for_fee'
  | 'recipient_account_needs_sol'
  | 'below_rent'
  | 'blockhash_expired'
  | 'program_error'
  | 'simulation_unavailable'

/** What reading the built bytes back found: the words, and who and what they name. */
export interface WhatItDoes {
  lines: string[]
  grantsPower: boolean
  programs: string[]
  counterparties: string[]
}

export interface Built {
  /** What reading the built bytes back found. */
  does: WhatItDoes
  preview: Preview
  simulation: Simulation
  /** The unsigned transaction, base64; handed out only after a passing simulation. */
  transaction: string
  blockhash: string
  last_valid_block_height: number
}

/** Everything the words need that the transfer itself does not carry. */
export interface BuildFacts {
  token: string
  /** The recipient, as the strip names them: "bob.skr", "your Heylana treasury", or a known program. */
  toLabel: string
  /** Rent for a new token account, in lamports, if one may be opened. */
  rentLamports: bigint
  /** Whether the recipient has no account for this token yet. */
  createsAccount: boolean
  /** The sender's balance of the token, as a decimal, when known. */
  balance: string | null
  cluster: string
}

const BASE_FEE_LAMPORTS = 5000n
const TOKEN_ACCOUNT_SIZE = 165
const TOKEN_2022_ACCOUNT_SIZE = 170

/** A fresh blockhash, the transfer built on it, then simulated. One rebuild if the blockhash went stale. */
export async function buildAndSimulate(rpc: Rpc, plan: TransferPlan, facts: BuildFacts): Promise<Built> {
  let attempt = 0
  for (;;) {
    attempt++
    const latest = await rpc('getLatestBlockhash', [{ commitment: 'confirmed' }])
    const blockhash = String(latest?.value?.blockhash ?? '')
    const lastValid = Number(latest?.value?.lastValidBlockHeight ?? 0)
    const { message, transaction } = await buildTransfer(plan, blockhash)
    const encoded = toBase64(transaction)

    const [simulated, fee, payer] = await Promise.all([
      rpc('simulateTransaction', [
        encoded,
        {
          encoding: 'base64', sigVerify: false, replaceRecentBlockhash: false, commitment: 'confirmed',
          // The payer's account after it would run, so what actually leaves can be checked
          // against what was confirmed rather than taken on trust.
          accounts: { encoding: 'base64', addresses: [plan.from] },
        },
      ]).catch(() => null),
      rpc('getFeeForMessage', [toBase64(message), { commitment: 'confirmed' }]).catch(() => null),
      // What the payer holds now, to set against what the simulation leaves them with.
      rpc('getBalance', [plan.from, { commitment: 'confirmed' }]).catch(() => null),
    ])
    let simulation = simulated
      ? explainSimulation(simulated.value, plan, facts)
      : ({ ok: false, reason: 'simulation_unavailable', words: SIM_UNAVAILABLE } as Simulation)
    if (!simulation.ok && simulation.reason === 'blockhash_expired' && attempt < 2) continue

    const feeLamports = BigInt(fee?.value ?? BASE_FEE_LAMPORTS)
    // Two last looks before any of this can be signed, both on the built bytes rather than
    // on what was asked for: does it hand anyone power, and does more leave than was said.
    const does = whatItDoes(message, facts.token, await ownerNames(plan))
    if (simulation.ok && does.grantsPower) {
      simulation = { ok: false, reason: 'grants_power', words: GRANTS_POWER }
    }
    if (simulation.ok) {
      const drain = drainCheck(payer?.value, simulated?.value, plan, facts, feeLamports)
      if (drain) simulation = drain
    }
    // Read the bytes back rather than trusting what was asked for: the preview says what
    // the transaction does, and a build that grew an instruction nobody asked for is caught.
    return {
      does,
      preview: previewOf(plan, facts, feeLamports, does),
      simulation,
      transaction: encoded,
      blockhash,
      last_valid_block_height: lastValid,
    }
  }
}

/**
 * The recipient's token account, said as the recipient: the strip shows the wallet, so the
 * line under it has to name the same address rather than an account derived from it.
 */
async function ownerNames(plan: TransferPlan): Promise<Record<string, string>> {
  if (!plan.mint || !plan.tokenProgram) return {}
  const [theirs, mine] = await Promise.all([
    associatedTokenAddress(plan.to, plan.mint, plan.tokenProgram),
    associatedTokenAddress(plan.from, plan.mint, plan.tokenProgram),
  ])
  return { [theirs]: plan.to, [mine]: plan.from }
}

/** The built message, read back: its lines, whether it hands over power, and who it names. */
export function whatItDoes(message: Uint8Array, token: string, owners: Record<string, string> = {}): WhatItDoes {
  try {
    const findings = describe(parseMessage(message), token, owners)
    return {
      lines: doesLines(findings),
      grantsPower: powersGranted(findings).length > 0,
      programs: programsIn(findings),
      counterparties: counterpartiesIn(findings),
    }
  } catch {
    // Unreadable bytes are never called harmless: the caller refuses them.
    return { lines: ['I could not read the prepared transaction back.'], grantsPower: true, programs: [], counterparties: [] }
  }
}

export function previewOf(plan: TransferPlan, facts: BuildFacts, feeLamports: bigint, does?: WhatItDoes): Preview {
  const programs = plan.mint
    ? ['Associated Token Account Program', plan.tokenProgram === TOKEN_2022_PROGRAM ? 'Token-2022 Program' : 'SPL Token Program']
    : ['System Program']
  return {
    from: short(plan.from),
    from_label: 'your wallet',
    to: short(plan.to),
    to_label: facts.toLabel,
    amount: unitsToDecimal(plan.units, plan.decimals),
    token: facts.token,
    fee_sol: unitsToDecimal(feeLamports, SOL_DECIMALS),
    account_rent_sol: facts.createsAccount ? unitsToDecimal(facts.rentLamports, SOL_DECIMALS) : '0',
    creates_account: facts.createsAccount,
    programs,
    cluster: facts.cluster,
    does: does?.lines ?? [],
    grants_power: does?.grantsPower ?? false,
  }
}

export const GRANTS_POWER =
  'The prepared transaction would give someone else power over one of your accounts, which is not what a send does. I stopped and did not open the wallet.'

/** How much more than the confirmed amount may leave before it counts as a drain. */
export const DRAIN_SLACK_LAMPORTS = 10_000_000n

/**
 * What the simulation says would leave the payer's own account, against what the
 * confirmation promised: the amount (for a SOL send), the fee, and the rent of any account
 * it opens. More than that — by more than the slack a priority fee could account for — is
 * never signed, whatever the transaction claims to be.
 */
export function drainCheck(
  payerLamports: unknown,
  value: any,
  plan: TransferPlan,
  facts: BuildFacts,
  feeLamports: bigint,
): Simulation | null {
  const before = payerLamports
  const after = value?.accounts?.[0]?.lamports
  // The RPC does not have to answer this, and a missing answer proves nothing either way.
  if (typeof after !== 'number' || typeof before !== 'number') return null
  const left = BigInt(before) - BigInt(after)
  if (left <= 0n) return null
  const promised =
    feeLamports + (facts.createsAccount ? facts.rentLamports : 0n) + (plan.mint ? 0n : plan.units)
  if (left <= promised + DRAIN_SLACK_LAMPORTS) return null
  return {
    ok: false,
    reason: 'unexpected_drain',
    words:
      `The check showed ${unitsToDecimal(left, SOL_DECIMALS)} SOL leaving your wallet, ` +
      'more than the amount you confirmed and its fee. I stopped and did not open the wallet.',
  }
}

/** Rent for a new token account under [tokenProgram], in lamports. */
export async function tokenAccountRent(rpc: Rpc, tokenProgram: string | null): Promise<bigint> {
  const size = tokenProgram === TOKEN_2022_PROGRAM ? TOKEN_2022_ACCOUNT_SIZE : TOKEN_ACCOUNT_SIZE
  return BigInt((await rpc('getMinimumBalanceForRentExemption', [size])) ?? 0)
}

/** Whether [owner] has no account for [mint] yet, so the transfer would open one. */
export async function needsAccount(rpc: Rpc, owner: string, mint: string): Promise<boolean> {
  const found = await rpc('getTokenAccountsByOwner', [owner, { mint }, { encoding: 'jsonParsed' }])
  return (found?.value ?? []).length === 0
}

/** Who a recipient is, as the strip says it. */
export function labelFor(address: string, options: { resolvedFrom?: string | null; treasury?: string; wallet?: string | null }): string {
  if (options.resolvedFrom) return options.resolvedFrom
  if (options.treasury && address === options.treasury) return 'your Heylana treasury'
  if (options.wallet && address === options.wallet) return 'your own wallet'
  const known = KNOWN[address]
  if (known) return known.label
  return 'a wallet'
}

// ------------------------------------------------------------------ outcomes

export const SIM_UNAVAILABLE = "I couldn't check it with the network just now, so I didn't open the wallet. Try again in a moment."

/**
 * The simulation's verdict in plain words. Which instruction failed says what it
 * was about: for a token, 0 opens the recipient's account and 1 is the transfer;
 * for SOL, 0 is the transfer.
 */
export function explainSimulation(value: any, plan: TransferPlan, facts: BuildFacts): Simulation {
  const err = value?.err
  const logs: string[] = Array.isArray(value?.logs) ? value.logs : []
  if (err === null || err === undefined) {
    return { ok: true, units_consumed: typeof value?.unitsConsumed === 'number' ? value.unitsConsumed : null }
  }
  const token = facts.token
  const have = facts.balance !== null ? ` You have ${facts.balance}.` : ''
  const rent = unitsToDecimal(facts.rentLamports, SOL_DECIMALS)
  const fail = (reason: SimReason, words: string): Simulation => ({ ok: false, reason, words })

  if (err === 'AccountNotFound') {
    return fail('no_sol_for_fee', 'Your wallet has no SOL to pay the network fee.')
  }
  if (err === 'InsufficientFundsForFee') {
    return fail('no_sol_for_fee', 'Not enough SOL to pay the network fee.')
  }
  if (err === 'BlockhashNotFound') {
    return fail('blockhash_expired', 'The network moved on before it could be checked. Ask again.')
  }
  if (typeof err === 'object' && err && 'InsufficientFundsForRent' in err) {
    return fail('below_rent', 'That would leave an account below the minimum SOL Solana requires to keep it open.')
  }
  const failed = Array.isArray(err?.InstructionError) ? err.InstructionError : null
  if (failed) {
    const [index, detail] = failed
    const custom = typeof detail === 'object' && detail !== null ? detail.Custom : undefined
    const lamportsShort = logs.some((line) => /insufficient lamports/i.test(line))
    if (plan.mint) {
      if (index === 0) {
        // Opening the recipient's account: only fails for want of the SOL it costs.
        return fail(
          'recipient_account_needs_sol',
          `The recipient's ${token} account needs creating, fee ${rent} SOL, and there isn't enough SOL for it.`,
        )
      }
      const below = facts.balance !== null && Number(facts.balance) < Number(unitsToDecimal(plan.units, plan.decimals))
      if (index === 1 && (custom === 1 || /insufficient funds/i.test(logs.join(' ')) || below)) {
        return fail('not_enough_token', `Not enough ${token}.${have}`)
      }
    } else if (index === 0 && (custom === 1 || lamportsShort)) {
      return fail('not_enough_sol', `Not enough SOL.${have}`)
    }
    return fail('program_error', `The network refused it: ${errorWords(detail)}.`)
  }
  return fail('program_error', `The network refused it: ${errorWords(err)}.`)
}

/** A program error's name, as words, and never a log line or an address. */
function errorWords(detail: unknown): string {
  if (typeof detail === 'string') return detail.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()
  if (detail && typeof detail === 'object' && 'Custom' in (detail as any)) return `program error ${(detail as any).Custom}`
  return 'an unknown error'
}
