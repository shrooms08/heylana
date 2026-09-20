/**
 * Every tool the model may be offered and every action Heylana can take, in one
 * table: name, version, JSON schema, and risk class.
 *
 *   R0  read-only, public: prices, what an address is, whose a name is.
 *   R1  reads the user's own data: their balances, their recent activity.
 *   R2  prepares, with no side effect: checks a send, builds and simulates it,
 *       writes down what the user asked for; and the phone actions that the
 *       phone's own app does in view (an alarm, a timer, an app, a page).
 *   R3  a side effect that needs the user's confirmation first: a send, a Pro
 *       payment, a text message, a calendar reminder. The worker issues a
 *       confirmation token only when the app reports the user confirmed it
 *       (Confirm on the strip, or the app's own guard firing a message or a
 *       reminder the model proposed), and nothing R3 happens without one.
 *   R4  never: signing, submitting a raw transaction, seed phrases, private keys.
 *       Never offered; a call is rejected and logged.
 *
 * The model proposes; only this registry lets a tool run. Every call the model
 * makes is checked here — unknown tools, tools not offered for this question,
 * R3 and R4 tools and arguments that do not fit the schema (extra fields
 * included) are rejected — and each decision is logged with the tool and class.
 * The app's own guards stay the first check; this is the second.
 */

export type Risk = 'R0' | 'R1' | 'R2' | 'R3' | 'R4'

/** What confirms an R3 action: the kind a confirmation token is issued for. */
export type ConfirmKind = 'send' | 'pay' | 'message' | 'reminder'

export interface Schema {
  type?: string | string[]
  properties?: Record<string, Schema>
  required?: string[]
  additionalProperties?: boolean
  enum?: unknown[]
  pattern?: string
  maxLength?: number
  minimum?: number
  maximum?: number
  items?: Schema
  description?: string
}

export interface Entry {
  name: string
  version: string
  risk: Risk
  /** Offered to the model as a tool (R0–R2), or an action/route the worker gates (R2–R4). */
  kind: 'tool' | 'action'
  description: string
  input_schema: Schema
  confirm?: ConfirmKind
}

const ADDRESS_PATTERN = '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
const ADDRESS: Schema = { type: 'string', description: 'A Solana address', pattern: ADDRESS_PATTERN }
const NULLABLE_STRING: Schema = { type: ['string', 'null'], maxLength: 400 }
const NULLABLE_INTEGER: Schema = { type: ['integer', 'null'] }

/** The phone actions propose_action may write down. message and reminder are R3. */
export const ACTION_INTENTS = [
  'alarm', 'timer', 'open_app', 'open_url', 'navigate', 'dial', 'youtube_search', 'spotify_play',
  'media_control', 'message', 'reminder', 'flashlight', 'camera', 'selfie', 'web_search', 'settings',
] as const

/** Actions with a side effect beyond an app opening in view: they need the user's confirmation. */
export const R3_INTENTS = new Set<string>(['message', 'reminder'])

export const REGISTRY: Entry[] = [
  // ------------------------------------------------------------ R0: public
  {
    name: 'get_price', version: '1', risk: 'R0', kind: 'tool',
    description: 'The current USD price of a token, by symbol (SOL, USDC, SKR, JUP) or mint address.',
    input_schema: {
      type: 'object',
      properties: { symbol_or_mint: { type: 'string', maxLength: 64 } },
      required: ['symbol_or_mint'],
      additionalProperties: false,
    },
  },
  {
    name: 'explain_address', version: '1', risk: 'R0', kind: 'tool',
    description:
      'What a Solana address is (wallet, token mint, program, token account), its known name, how old it is and ' +
      'how many transactions it has. dealt_with_before, when present, says whether this user has dealt with this ' +
      'address before: if it is false, say so plainly — "first time you have sent to this address" for a wallet, ' +
      '"you have not used this program before" for a program. Never call an address safe because it is not new.',
    input_schema: { type: 'object', properties: { address: ADDRESS }, required: ['address'], additionalProperties: false },
  },
  {
    name: 'resolve_name', version: '1', risk: 'R0', kind: 'tool',
    description: 'The address a .skr or .sol name belongs to.',
    input_schema: {
      type: 'object',
      properties: { name: { type: 'string', maxLength: 64 } },
      required: ['name'],
      additionalProperties: false,
    },
  },
  {
    name: 'search_solana_kb', version: '1', risk: 'R0', kind: 'tool',
    description: "Search Heylana's Solana knowledge base: the Solana, Anchor and Solana Mobile docs, the Solana Cookbook, " +
      'top Solana Stack Exchange answers, and Agave and Anchor release notes. Use it for how things work, how to build ' +
      'something, and what an error means. When you use a result, name its title in one short phrase.',
    input_schema: {
      type: 'object',
      properties: {
        query: { type: 'string', maxLength: 200, description: 'What to look up, in a few words: "priority fees", "AccountDidNotDeserialize"' },
        k: { type: 'integer', minimum: 1, maximum: 5 },
      },
      required: ['query'],
      additionalProperties: false,
    },
  },
  // --------------------------------------------------------- R1: user data
  {
    name: 'get_balances', version: '1', risk: 'R1', kind: 'tool',
    description: "SOL, USDC, SKR and the top 5 other tokens a wallet holds, with USD values. Omit wallet for the user's connected wallet.",
    input_schema: { type: 'object', properties: { wallet: ADDRESS }, additionalProperties: false },
  },
  {
    name: 'recent_activity', version: '1', risk: 'R1', kind: 'tool',
    description: "A wallet's latest transactions as one-line summaries. Omit wallet for the user's.",
    input_schema: {
      type: 'object',
      properties: { wallet: ADDRESS, n: { type: 'integer', minimum: 1, maximum: 5 } },
      additionalProperties: false,
    },
  },
  // ----------------------------------------------------------- R2: prepare
  {
    name: 'prepare_send', version: '1', risk: 'R2', kind: 'tool',
    description: 'Checks a send the user asked for: resolves the recipient, the fee, and whether a token account must be created. Builds and signs nothing.',
    input_schema: {
      type: 'object',
      properties: {
        to: { type: 'string', maxLength: 64, description: 'Address, .skr or .sol name, exactly as the user said it' },
        amount: { type: 'number' },
        token: { type: 'string', enum: ['SOL', 'USDC', 'SKR'] },
      },
      required: ['to', 'amount', 'token'],
      additionalProperties: false,
    },
  },
  {
    name: 'propose_send', version: '1', risk: 'R2', kind: 'tool',
    description: 'Write down the send the user asked for, exactly as they said it. Heylana checks it and the user confirms and signs it.',
    input_schema: {
      type: 'object',
      properties: {
        to: { type: 'string', maxLength: 64, description: 'The recipient exactly as the user wrote or said it: an address, or a .skr or .sol name' },
        amount: { type: ['number', 'null'], description: 'The amount; null only if the user said everything or all' },
        token: { type: 'string', enum: ['SOL', 'USDC', 'SKR'] },
      },
      required: ['to', 'amount', 'token'],
      additionalProperties: false,
    },
  },
  {
    name: 'propose_action', version: '1', risk: 'R2', kind: 'tool',
    description:
      "Write down the phone action the user asked for, exactly as they said it. The phone's own app does it; " +
      'fill only the fields the action uses and leave the rest null.',
    input_schema: {
      type: 'object',
      properties: {
        intent: { type: 'string', enum: [...ACTION_INTENTS] },
        hour: {
          ...NULLABLE_INTEGER,
          description: 'alarm, reminder: 0-23. A bare hour with no pm, evening, afternoon or tonight is the morning: "7 tomorrow" is 7.',
        },
        minutes: { ...NULLABLE_INTEGER, description: 'alarm, reminder: 0-59, 0 if not said' },
        message: { ...NULLABLE_STRING, description: 'alarm only: a label, if the user gave one. For a text message the words go in text.' },
        seconds: { ...NULLABLE_INTEGER, description: 'timer: the whole length in seconds' },
        app: { ...NULLABLE_STRING, description: 'open_app: the app name as the user said it' },
        url: { ...NULLABLE_STRING, description: 'open_url: the web address as the user said it' },
        query: {
          ...NULLABLE_STRING,
          description: 'navigate: the place; youtube_search, web_search: what to search for; spotify_play: the song, artist, playlist or genre. As the user said it.',
        },
        number: { ...NULLABLE_STRING, description: 'dial, message: the digits exactly as the user said them, no country code added' },
        name: { ...NULLABLE_STRING, description: 'dial, message: the contact name as the user said it, if no number' },
        text: { ...NULLABLE_STRING, description: 'message: the words to send; reminder: what to be reminded of. As the user said it.' },
        command: { type: ['string', 'null'], enum: ['play', 'pause', 'next', 'previous', null], description: 'media_control' },
        state: { type: ['string', 'null'], enum: ['on', 'off', null], description: 'flashlight' },
        page: {
          type: ['string', 'null'],
          enum: ['wifi', 'bluetooth', 'display', 'sound', 'battery', 'accessibility', null],
          description: 'settings: which page',
        },
      },
      required: ['intent'],
      additionalProperties: false,
    },
  },
  {
    name: 'build_transfer', version: '1', risk: 'R2', kind: 'action',
    description: 'The /send/build preview: builds the exact transfer and simulates it. No bytes to sign come back.',
    input_schema: { type: 'object' },
  },
  // --------------------------------------------- R3: needs the user's confirmation
  {
    name: 'send', version: '1', risk: 'R3', kind: 'action', confirm: 'send',
    description: 'The bytes of a send, handed out for Seed Vault after the user tapped Confirm on the strip.',
    input_schema: { type: 'object' },
  },
  {
    name: 'pay', version: '1', risk: 'R3', kind: 'action', confirm: 'pay',
    description: 'The bytes of a Pro payment, handed out for Seed Vault after the user tapped Pay.',
    input_schema: { type: 'object' },
  },
  {
    name: 'message', version: '1', risk: 'R3', kind: 'action', confirm: 'message',
    description: "A text message, filled in on the phone's Messages app after the app's guard found every part in the user's words.",
    input_schema: { type: 'object' },
  },
  {
    name: 'reminder', version: '1', risk: 'R3', kind: 'action', confirm: 'reminder',
    description: "A calendar reminder, saved after the app's guard found every part in the user's words.",
    input_schema: { type: 'object' },
  },
  // -------------------------------------------------------------- R4: never
  ...['sign_transaction', 'sign_message', 'submit_transaction', 'export_seed_phrase', 'reveal_private_key'].map(
    (name): Entry => ({
      name, version: '1', risk: 'R4', kind: 'action',
      description: 'Never: Heylana does not sign, submit, or touch keys or seed phrases.',
      input_schema: { type: 'object' },
    }),
  ),
]

const BY_NAME = new Map(REGISTRY.map((entry) => [entry.name, entry]))

export function entry(name: string): Entry | undefined {
  return BY_NAME.get(name)
}

/** A registry tool as the model is offered it: name, description and schema, nothing else. */
export function definition(name: string): { name: string; description: string; input_schema: Schema } {
  const found = BY_NAME.get(name)
  if (!found || found.kind !== 'tool' || found.risk === 'R3' || found.risk === 'R4') throw new Error(`not an offerable tool: ${name}`)
  return { name: found.name, description: found.description, input_schema: found.input_schema }
}

/** The lookups offered with Solana questions, in the order they have always been offered. */
export const LOOKUP_TOOLS = ['get_balances', 'get_price', 'explain_address', 'recent_activity', 'resolve_name', 'prepare_send', 'search_solana_kb'].map(definition)

export type Decision =
  | { decision: 'allowed'; tool: string; class: Risk }
  | { decision: 'rejected'; tool: string; class: Risk | 'unknown'; reason: string }

/**
 * Whether a tool call the model made may run: known, offered for this question,
 * below R3, and its arguments exactly as the schema says.
 */
export function authorize(name: unknown, input: unknown, offered: readonly string[]): Decision {
  const tool = typeof name === 'string' ? name : String(name)
  const found = BY_NAME.get(tool)
  if (!found) return { decision: 'rejected', tool, class: 'unknown', reason: 'unknown_tool' }
  if (found.risk === 'R4') return { decision: 'rejected', tool, class: 'R4', reason: 'never' }
  if (found.risk === 'R3' || found.kind !== 'tool') return { decision: 'rejected', tool, class: found.risk, reason: 'not_a_model_tool' }
  if (!offered.includes(tool)) return { decision: 'rejected', tool, class: found.risk, reason: 'not_offered' }
  const errors = validate(found.input_schema, input)
  if (errors.length > 0) return { decision: 'rejected', tool, class: found.risk, reason: `bad_arguments:${errors[0]}` }
  return { decision: 'allowed', tool, class: found.risk }
}

/** The risk class of a phone action the model wrote down. */
export function actionRisk(intent: unknown): Risk {
  return typeof intent === 'string' && R3_INTENTS.has(intent) ? 'R3' : 'R2'
}

/**
 * A small JSON Schema check: types (with null), enums, required fields, no extra
 * fields where additionalProperties is false, string patterns and lengths, number
 * bounds, and array items. Returns what is wrong, by path; empty when it fits.
 */
export function validate(schema: Schema, value: unknown, path = '$'): string[] {
  const errors: string[] = []
  const types = schema.type === undefined ? null : Array.isArray(schema.type) ? schema.type : [schema.type]
  if (types && !types.some((type) => isType(type, value))) {
    return [`${path} is not ${types.join(' or ')}`]
  }
  if (schema.enum && !schema.enum.some((allowed) => allowed === value)) errors.push(`${path} is not one of the allowed values`)
  if (typeof value === 'string') {
    if (schema.maxLength !== undefined && value.length > schema.maxLength) errors.push(`${path} is too long`)
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) errors.push(`${path} does not match`)
  }
  if (typeof value === 'number') {
    if (schema.minimum !== undefined && value < schema.minimum) errors.push(`${path} is below ${schema.minimum}`)
    if (schema.maximum !== undefined && value > schema.maximum) errors.push(`${path} is above ${schema.maximum}`)
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const object = value as Record<string, unknown>
    for (const field of schema.required ?? []) {
      if (!(field in object) || object[field] === undefined) errors.push(`${path}.${field} is missing`)
    }
    for (const [field, fieldValue] of Object.entries(object)) {
      const inner = schema.properties?.[field]
      if (inner) errors.push(...validate(inner, fieldValue, `${path}.${field}`))
      else if (schema.additionalProperties === false) errors.push(`${path}.${field} is not allowed`)
    }
  }
  if (Array.isArray(value) && schema.items) {
    value.forEach((item, index) => errors.push(...validate(schema.items!, item, `${path}[${index}]`)))
  }
  return errors
}

function isType(type: string, value: unknown): boolean {
  switch (type) {
    case 'null': return value === null
    case 'string': return typeof value === 'string'
    case 'number': return typeof value === 'number' && Number.isFinite(value)
    case 'integer': return typeof value === 'number' && Number.isInteger(value)
    case 'boolean': return typeof value === 'boolean'
    case 'array': return Array.isArray(value)
    case 'object': return value !== null && typeof value === 'object' && !Array.isArray(value)
    default: return false
  }
}
