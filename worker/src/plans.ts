/**
 * Plans, and the talks each one allows.
 *
 * Free is 30 talks a calendar month (UTC), plus a one-off welcome bonus of 20 the
 * first time a wallet is connected; bonus talks are only spent once the month's 30
 * are gone, and whatever is left carries over. Pro and Judge are unlimited. Each
 * plan also carries a skills cap — nothing enforces it yet; it is here so the app
 * can show it and phase 3b can enforce it.
 */

export type PlanName = 'free' | 'pro' | 'judge'

export const FREE_TALKS = 30
export const WELCOME_BONUS = 20
export const SKILLS_CAP: Record<PlanName, number> = { free: 3, pro: 10, judge: 10 }

/** What is stored for one wallet (or one bare device). */
export interface Account {
  bonus_left: number
  bonus_granted: boolean
  pro_until?: string
  judge_until?: string
}

export function newAccount(): Account {
  return { bonus_left: 0, bonus_granted: false }
}

/** The first connection of a wallet, and only that, adds the welcome bonus. */
export function grantWelcome(account: Account): { account: Account; granted: boolean } {
  if (account.bonus_granted) return { account, granted: false }
  return { account: { ...account, bonus_granted: true, bonus_left: account.bonus_left + WELCOME_BONUS }, granted: true }
}

export function planOf(account: Account, now: Date): PlanName {
  if (account.judge_until && Date.parse(account.judge_until) > now.getTime()) return 'judge'
  if (account.pro_until && Date.parse(account.pro_until) > now.getTime()) return 'pro'
  return 'free'
}

/** "2026-09" — the key a month's talks are counted under. */
export function monthKey(now: Date): string {
  return now.toISOString().slice(0, 7)
}

/** Midnight UTC on the first of next month, when the monthly talks come back. */
export function resetsAt(now: Date): string {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1)).toISOString()
}

export interface Standing {
  plan: PlanName
  used: number
  /** Null means unlimited. */
  limit: number | null
  skills_cap: number
  pro_until: string | null
  judge_until: string | null
  resets_at: string
}

/** Where an account stands this month, given how many talks it has used. */
export function standing(account: Account, used: number, now: Date): Standing {
  const plan = planOf(account, now)
  return {
    plan,
    used,
    // Bonus already spent this month still counts toward this month's limit.
    limit: plan === 'free' ? FREE_TALKS + account.bonus_left + Math.max(0, used - FREE_TALKS) : null,
    skills_cap: SKILLS_CAP[plan],
    pro_until: account.pro_until ?? null,
    judge_until: account.judge_until ?? null,
    resets_at: resetsAt(now),
  }
}

/** Spends one talk, if there is one to spend. */
export function spendTalk(
  account: Account,
  used: number,
  now: Date,
): { allowed: boolean; account: Account; used: number } {
  if (planOf(account, now) !== 'free') return { allowed: true, account, used: used + 1 }
  if (used < FREE_TALKS) return { allowed: true, account, used: used + 1 }
  if (account.bonus_left > 0) {
    return { allowed: true, account: { ...account, bonus_left: account.bonus_left - 1 }, used: used + 1 }
  }
  return { allowed: false, account, used }
}

/** Pro for [days] more, from now or from the end of the Pro already paid for. */
export function extendPro(account: Account, now: Date, days: number): Account {
  const current = account.pro_until ? Date.parse(account.pro_until) : 0
  const from = Math.max(now.getTime(), current)
  return { ...account, pro_until: new Date(from + days * 86_400_000).toISOString() }
}

/** Judge until the end of the judging day given, in UTC. */
export function makeJudge(account: Account, until: string): Account {
  const end = /^\d{4}-\d{2}-\d{2}$/.test(until) ? `${until}T23:59:59.000Z` : until
  return { ...account, judge_until: new Date(end).toISOString() }
}
