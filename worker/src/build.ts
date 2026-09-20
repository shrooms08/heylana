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
import { buildTransfer, toBase64, type TransferPlan } from './tx.ts'
import type { Rpc } from './rpc.ts'
import { KNOWN, SOL_DECIMALS, TOKEN_2022_PROGRAM, short, unitsToDecimal } from './solana.ts'

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
}

export type Simulation =
  | { ok: true; units_consumed: number | null }
  | { ok: false; reason: SimReason; words: string }

export type SimReason =
  | 'not_enough_token'
  | 'not_enough_sol'
  | 'no_sol_for_fee'
  | 'recipient_account_needs_sol'
  | 'below_rent'
  | 'blockhash_expired'
  | 'program_error'
  | 'simulation_unavailable'

export interface Built {
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

    const [simulated, fee] = await Promise.all([
      rpc('simulateTransaction', [
        encoded,
        { encoding: 'base64', sigVerify: false, replaceRecentBlockhash: false, commitment: 'confirmed' },
      ]).catch(() => null),
      rpc('getFeeForMessage', [toBase64(message), { commitment: 'confirmed' }]).catch(() => null),
    ])
    const simulation = simulated
      ? explainSimulation(simulated.value, plan, facts)
      : ({ ok: false, reason: 'simulation_unavailable', words: SIM_UNAVAILABLE } as Simulation)
    if (!simulation.ok && simulation.reason === 'blockhash_expired' && attempt < 2) continue

    const feeLamports = BigInt(fee?.value ?? BASE_FEE_LAMPORTS)
    return {
      preview: previewOf(plan, facts, feeLamports),
      simulation,
      transaction: encoded,
      blockhash,
      last_valid_block_height: lastValid,
    }
  }
}

export function previewOf(plan: TransferPlan, facts: BuildFacts, feeLamports: bigint): Preview {
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
