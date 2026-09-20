/**
 * What a transaction actually does, instruction by instruction.
 *
 * A transfer is not the only thing a signature can authorise, and the dangerous ones are
 * the quiet ones: an **approval** hands someone the right to move your tokens later, a
 * **set authority** hands over an account for good, a **close** empties one. None of those
 * moves anything while you are looking at it, so none of them shows up as an amount
 * leaving your wallet — which is exactly why they have to be read out loud.
 *
 * This reads the bytes of a legacy message and says, in plain words, what each instruction
 * would do. It never decides anything: it reports what it found, and says so plainly when
 * it does not recognise a program. Nothing here signs, sends or approves.
 */
import { encodeBase58 } from './base58.ts'
import { TOKEN_PROGRAM, TOKEN_2022_PROGRAM } from './pay.ts'
import { ASSOCIATED_TOKEN_PROGRAM } from './tx.ts'

export const SYSTEM_PROGRAM = '11111111111111111111111111111111'
export const MEMO_PROGRAM = 'MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr'
export const COMPUTE_BUDGET_PROGRAM = 'ComputeBudget111111111111111111111111111111'

/** Programs whose instructions this can read. Anything else is named, never guessed at. */
export const KNOWN_PROGRAMS: Record<string, string> = {
  [SYSTEM_PROGRAM]: 'System Program',
  [TOKEN_PROGRAM]: 'SPL Token Program',
  [TOKEN_2022_PROGRAM]: 'Token-2022 Program',
  [ASSOCIATED_TOKEN_PROGRAM]: 'Associated Token Account Program',
  [MEMO_PROGRAM]: 'Memo Program',
  [COMPUTE_BUDGET_PROGRAM]: 'Compute Budget Program',
}

export type FindingKind =
  | 'sol_transfer'
  | 'token_transfer'
  | 'approve'
  | 'revoke'
  | 'set_authority'
  | 'close_account'
  | 'burn'
  | 'mint_to'
  | 'freeze'
  | 'create_account'
  | 'open_token_account'
  | 'note'
  | 'unreadable'
  | 'unknown_program'

/** One instruction, in words a person can act on. */
export interface Finding {
  kind: FindingKind
  /** One short sentence. Addresses already shortened; never a log line, never a URL. */
  words: string
  /** The program it calls, by name where known, else its shortened address. */
  program: string
  /** The program's full address, for the first-time check. */
  programId: string
  /** Who would receive, be approved, or take over — shortened. Absent where there is none. */
  counterparty?: string
  /** The counterparty in full, for the first-time check. */
  counterpartyId?: string
  /** Base units, as a decimal string, when the instruction carries an amount. */
  units?: string
  /** True for anything that hands someone else power over an account, now or later. */
  grantsPower?: boolean
}

// ------------------------------------------------------------------ reading the message

export interface ParsedMessage {
  /** Every account the transaction names, in order. */
  keys: string[]
  /** How many of them must sign. */
  signers: number
  instructions: { programId: string; accounts: string[]; data: Uint8Array }[]
}

/** A legacy message's accounts and instructions. Throws on anything it cannot read. */
export function parseMessage(bytes: Uint8Array): ParsedMessage {
  let at = 0
  const byte = () => {
    if (at >= bytes.length) throw new Error('short message')
    return bytes[at++]
  }
  const take = (n: number) => {
    if (at + n > bytes.length) throw new Error('short message')
    const out = bytes.slice(at, at + n)
    at += n
    return out
  }
  const compact = () => {
    let value = 0
    let shift = 0
    for (;;) {
      const piece = byte()
      value |= (piece & 0x7f) << shift
      if ((piece & 0x80) === 0) return value
      shift += 7
      if (shift > 21) throw new Error('bad length')
    }
  }

  const signers = byte()
  if (signers & 0x80) throw new Error('versioned message')
  byte() // readonly signed
  byte() // readonly unsigned
  const keyCount = compact()
  if (keyCount > 64) throw new Error('too many accounts')
  const keys: string[] = []
  for (let i = 0; i < keyCount; i++) keys.push(encodeBase58(take(32)))
  take(32) // recent blockhash

  const instructions: ParsedMessage['instructions'] = []
  const count = compact()
  for (let i = 0; i < count; i++) {
    const programIndex = byte()
    const accountCount = compact()
    const accounts: string[] = []
    for (let a = 0; a < accountCount; a++) {
      const index = byte()
      accounts.push(keys[index] ?? '')
    }
    const dataLength = compact()
    instructions.push({ programId: keys[programIndex] ?? '', accounts, data: take(dataLength) })
  }
  return { keys, signers, instructions }
}

// ------------------------------------------------------------------ the words

const short = (address: string) =>
  address.length > 9 ? `${address.slice(0, 4)}…${address.slice(-4)}` : address

/** Base units as a decimal string: 50000 at 6 decimals is "0.05". */
export function amountWords(units: bigint, decimals: number | null): string | null {
  if (decimals === null || decimals < 0 || decimals > 18) return null
  const text = units.toString().padStart(decimals + 1, '0')
  const whole = text.slice(0, text.length - decimals)
  const fraction = decimals === 0 ? '' : text.slice(text.length - decimals).replace(/0+$/, '')
  return fraction ? `${whole}.${fraction}` : whole
}

const u64 = (data: Uint8Array, offset: number): bigint => {
  let value = 0n
  for (let i = 7; i >= 0; i--) value = (value << 8n) | BigInt(data[offset + i] ?? 0)
  return value
}

/** How an amount reads when the decimals are not in the instruction. */
const rawAmount = (units: bigint) => `${units.toString()} base units`

/**
 * What this transaction would do, one line per instruction, in the order they run.
 * [token] names the mint's symbol where the caller knows it. [owners] renames an account
 * the caller knows the owner of — a token account is the recipient's, but it is not the
 * address they know themselves by, so saying the wallet is the honest thing.
 */
export function describe(
  message: ParsedMessage,
  token?: string | null,
  owners?: Record<string, string>,
): Finding[] {
  return message.instructions.map((instruction) => one(instruction, token ?? null, owners ?? {}))
}

function one(
  instruction: ParsedMessage['instructions'][number],
  token: string | null,
  owners: Record<string, string>,
): Finding {
  const programId = instruction.programId
  const program = KNOWN_PROGRAMS[programId] ?? short(programId)
  const base = { program, programId }
  const data = instruction.data
  try {
    if (programId === SYSTEM_PROGRAM) return system(instruction, base)
    if (programId === TOKEN_PROGRAM || programId === TOKEN_2022_PROGRAM) return spl(instruction, base, token, owners)
    if (programId === ASSOCIATED_TOKEN_PROGRAM) {
      return {
        ...base,
        kind: 'open_token_account',
        words: 'Opens a token account so the tokens have somewhere to land.',
      }
    }
    if (programId === MEMO_PROGRAM) {
      // The note itself is the user's or the sender's words; its length is all that is said.
      return { ...base, kind: 'note', words: `Attaches a note of ${data.length} characters.` }
    }
    if (programId === COMPUTE_BUDGET_PROGRAM) {
      return { ...base, kind: 'note', words: 'Sets the transaction\'s compute budget.' }
    }
  } catch {
    return { ...base, kind: 'unreadable', words: `Calls ${program}, and I could not read what it asks for.` }
  }
  return {
    ...base,
    kind: 'unknown_program',
    words: `Calls a program I don't recognise (${short(programId)}).`,
  }
}

function system(
  instruction: ParsedMessage['instructions'][number],
  base: { program: string; programId: string },
): Finding {
  const data = instruction.data
  const code = data.length >= 4 ? data[0] | (data[1] << 8) | (data[2] << 16) | (data[3] << 24) : -1
  if (code === 2 && data.length >= 12) {
    const units = u64(data, 4)
    const to = instruction.accounts[1] ?? ''
    return {
      ...base,
      kind: 'sol_transfer',
      words: `Sends ${amountWords(units, 9)} SOL to ${short(to)}.`,
      counterparty: short(to),
      counterpartyId: to,
      units: units.toString(),
    }
  }
  if (code === 0) {
    return { ...base, kind: 'create_account', words: 'Creates a new account and funds its rent.' }
  }
  if (code === 1 || code === 10) {
    const to = instruction.accounts[0] ?? ''
    return {
      ...base,
      kind: 'set_authority',
      words: `Hands an account over to another program (${short(to)}).`,
      counterparty: short(to),
      counterpartyId: to,
      grantsPower: true,
    }
  }
  return { ...base, kind: 'unreadable', words: 'Calls the System Program in a way I could not read.' }
}

/** The SPL Token instructions worth saying out loud, checked and unchecked alike. */
function spl(
  instruction: ParsedMessage['instructions'][number],
  base: { program: string; programId: string },
  token: string | null,
  owners: Record<string, string> = {},
): Finding {
  const data = instruction.data
  const code = data[0]
  const name = token ?? 'tokens'
  // A token account is whoever owns it, where the caller knows: that is the address the
  // user recognises, and the one the confirmation strip already shows them.
  const accounts = instruction.accounts.map((account) => owners[account] ?? account)

  // transfer (3) and transferChecked (12): the checked one carries the mint and decimals.
  if (code === 3 || code === 12) {
    const checked = code === 12
    const units = u64(data, 1)
    const decimals = checked && data.length >= 10 ? data[9] : null
    const to = (checked ? accounts[2] : accounts[1]) ?? ''
    const amount = amountWords(units, decimals) ?? rawAmount(units)
    return {
      ...base,
      kind: 'token_transfer',
      words: `Sends ${amount} ${name} to ${short(to)}.`,
      counterparty: short(to),
      counterpartyId: to,
      units: units.toString(),
    }
  }

  // approve (4) and approveChecked (13): the quiet one.
  if (code === 4 || code === 13) {
    const checked = code === 13
    const units = u64(data, 1)
    const decimals = checked && data.length >= 10 ? data[9] : null
    const who = (checked ? accounts[2] : accounts[1]) ?? ''
    const amount = amountWords(units, decimals) ?? rawAmount(units)
    return {
      ...base,
      kind: 'approve',
      words:
        `Lets ${short(who)} move up to ${amount} ${name} out of your account whenever they choose. ` +
        'This is an approval, not a transfer: nothing moves now.',
      counterparty: short(who),
      counterpartyId: who,
      units: units.toString(),
      grantsPower: true,
    }
  }

  if (code === 5) {
    return { ...base, kind: 'revoke', words: 'Cancels an approval you gave earlier.' }
  }

  if (code === 6) {
    const to = accounts[0] ?? ''
    return {
      ...base,
      kind: 'set_authority',
      words: `Changes who controls a token account or mint (${short(to)}). Whoever holds it can move or freeze the tokens.`,
      counterparty: short(to),
      counterpartyId: to,
      grantsPower: true,
    }
  }

  if (code === 7) {
    const units = u64(data, 1)
    return { ...base, kind: 'mint_to', words: `Creates ${rawAmount(units)} of a token.` }
  }

  if (code === 8 || code === 15) {
    const units = u64(data, 1)
    const amount = code === 15 && data.length >= 10 ? amountWords(units, data[9]) : null
    return { ...base, kind: 'burn', words: `Destroys ${amount ? `${amount} ${name}` : rawAmount(units)} for good.` }
  }

  if (code === 9) {
    const to = accounts[1] ?? ''
    return {
      ...base,
      kind: 'close_account',
      words: `Closes a token account and sends what it holds to ${short(to)}.`,
      counterparty: short(to),
      counterpartyId: to,
      grantsPower: true,
    }
  }

  if (code === 10 || code === 11) {
    return {
      ...base,
      kind: 'freeze',
      words: code === 10 ? 'Freezes a token account: nothing can leave it.' : 'Unfreezes a token account.',
      grantsPower: true,
    }
  }

  return { ...base, kind: 'unreadable', words: `Calls ${base.program} in a way I could not read.` }
}

// ------------------------------------------------------------------ what it adds up to

/** Everything the findings hand power to someone over, in the order found. */
export function powersGranted(findings: Finding[]): Finding[] {
  return findings.filter((finding) => finding.grantsPower)
}

/** Programs the transaction calls, without repeats, in the order they appear. */
export function programsIn(findings: Finding[]): string[] {
  return [...new Set(findings.map((finding) => finding.programId))].filter(Boolean)
}

/** Everyone who would receive something or be handed power, without repeats. */
export function counterpartiesIn(findings: Finding[]): string[] {
  return [...new Set(findings.map((finding) => finding.counterpartyId).filter(Boolean) as string[])]
}

/**
 * The lines the strip shows under a preview: every instruction that does something worth
 * knowing, the quiet ones first, and never more than [most].
 */
export function doesLines(findings: Finding[], most = 4): string[] {
  const quiet = findings.filter((finding) => finding.grantsPower)
  const rest = findings.filter(
    (finding) => !finding.grantsPower && finding.kind !== 'note' && finding.kind !== 'open_token_account',
  )
  return [...quiet, ...rest].slice(0, most).map((finding) => finding.words)
}
