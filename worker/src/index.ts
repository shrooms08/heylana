/**
 * Heylana's proxy.
 *
 * Every key Heylana needs lives here and nowhere else: the phone holds none of
 * them. The app sends what it wants done — a question, some text to speak, a
 * request for temporary ears — and this decides which model, which voice, and
 * how much any one phone may spend in a day.
 *
 * Shaped after Farza's Clicky worker (MIT), rewritten for Heylana; see README.
 *
 * Nothing here logs content. Not a prompt, not a transcript, not a word of
 * anyone's screen — only which route ran, which device asked, how long upstream
 * took, and how much it cost.
 */

import { isAddress } from './base58.ts'
import { challengeMessage, randomNonce, readSession, signSession, verifySignature } from './session.ts'
import { MARK_PATH, markResponse } from './mark.ts'
import { answerWithTools, proposeSend } from './brain.ts'
import { checkLines, checkShortAddresses, withAddressChecks } from './shortaddr.ts'
import { prepareSend } from './tools.ts'
import { checkSend, type PreparedSend } from './send.ts'
import { short } from './solana.ts'
import {
  type Account, type Standing, extendPro, grantWelcome, makeJudge, monthKey, newAccount, spendTalk, standing,
} from './plans.ts'
import {
  type Quote, QUOTE_TTL_MS, TOKEN_2022_PROGRAM, TOKEN_PROGRAM, USDC_DECIMALS, checkPayment, decimalToUnits,
  mintInfo, newReference, rpc, usdPrice, usdToTokenUnits,
} from './pay.ts'

/** The smallest slice of Workers KV this needs; keeps the types dependency-free. */
export interface CapStore {
  get(key: string): Promise<string | null>
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>
  delete(key: string): Promise<void>
}

/** The worker's clock, replaceable in tests so month boundaries can be crossed. */
export const clock = { now: (): number => Date.now() }

export interface Env {
  /** Secrets. Never in the repo, never in wrangler.toml, never in a response. */
  ANTHROPIC_API_KEY: string
  CARTESIA_API_KEY: string
  DEEPGRAM_API_KEY: string
  /** Seals session tokens. Long and random; changing it signs everyone out. */
  SESSION_SECRET: string
  /** The code that turns an account into a judge's. */
  JUDGE_CODE: string
  /** A Solana mainnet RPC endpoint. Its URL carries a token, so it is a secret. */
  RPC_URL: string
  /** Optional: raises Jupiter's rate limit above the keyless one. */
  JUPITER_API_KEY?: string
  /** Optional: a mainnet RPC for .skr names, which live on mainnet whatever CLUSTER is. */
  MAINNET_RPC_URL?: string

  /** Plain configuration. */
  DEEPGRAM_PROJECT_ID: string
  VOICE_SKYLAR: string
  VOICE_ARCHIE: string
  /** The last day of judging, as YYYY-MM-DD. Judge plans end with it. */
  JUDGE_UNTIL: string
  /** Where Pro payments go: the treasury wallet's address, not a token account. */
  TREASURY_ADDRESS: string
  USDC_MINT: string
  /** Filled in from Solscan before deploying; "replace-me" turns SKR off. */
  SKR_MINT: string
  /** The price of 30 days of Pro in US dollars, as text: "15", or "0.10" to test. */
  PRICE_USD: string
  PRO_DAYS: string
  /** "mainnet-beta", or "devnet" to test with play money. Anything else is mainnet. */
  CLUSTER?: string

  CAPS: CapStore
}

/** The app names what kind of work it is; the worker names the model. */
const MODELS: Record<string, string> = {
  quick: 'claude-haiku-4-5-20251001',
  task: 'claude-sonnet-5',
}

/**
 * What one device may spend in a day, per route. Budget and abuse protection —
 * a ceiling over the plans, never a plan in itself.
 */
const DAILY_CAPS: Record<string, number> = {
  chat: 150,
  tts: 150,
  'stt-token': 300,
  'wallet/challenge': 50,
  'wallet/verify': 50,
  judge: 20,
  me: 500,
  'pay/quote': 100,
  'pay/blockhash': 200,
  // Confirming polls for up to a minute, so it is allowed plenty.
  'pay/confirm': 300,
  profile: 200,
  'send/prepare': 50,
  // Confirming polls while the chain catches up.
  'send/confirm': 200,
}

/** Every route the worker answers, and the methods each takes. */
const ROUTES: Record<string, readonly string[]> = {
  chat: ['POST'],
  tts: ['POST'],
  'stt-token': ['POST'],
  'wallet/challenge': ['POST'],
  'wallet/verify': ['POST'],
  judge: ['POST'],
  me: ['GET'],
  'pay/quote': ['POST'],
  'pay/blockhash': ['POST'],
  'pay/confirm': ['POST'],
  profile: ['GET', 'PUT'],
  'send/prepare': ['POST'],
  'send/confirm': ['POST'],
}

/** Who is asking: always a device, and a wallet once one has been connected. */
export interface Who {
  device: string
  wallet: string | null
  /** What plans and talks are counted against: the wallet if there is one. */
  key: string
}

/** A sign-in challenge is good for five minutes. */
const CHALLENGE_TTL_SECONDS = 300

/** A month's talk count outlives its month by a little, then goes. */
const TALKS_TTL_SECONDS = 40 * 86_400

const ANTHROPIC_URL = 'https://api.anthropic.com/v1/messages'
const ANTHROPIC_VERSION = '2023-06-01'
const CARTESIA_URL = 'https://api.cartesia.ai/tts/bytes'
const CARTESIA_VERSION = '2024-11-13'
const CARTESIA_MODEL = 'sonic-2'
const DEEPGRAM_KEYS_URL = 'https://api.deepgram.com/v1/projects'

/**
 * Raw 16-bit audio: the one format that can be played as it arrives.
 *
 * Cartesia's raw container is single-channel, and the phone builds its AudioTrack
 * from the rate this header carries — the two must never drift apart, or every
 * word comes out at the wrong pitch.
 */
const TTS_SAMPLE_RATE = 24000

/** How long a borrowed pair of ears is good for. */
const STT_KEY_TTL_SECONDS = 120

/** Spoken answers are short by design; this is the ceiling, not the target. */
const MAX_TTS_CHARS = 400

const MAX_OUTPUT_TOKENS = 1024

/** Two days, so a cap key outlives the day it counts without piling up. */
const CAP_KEY_TTL_SECONDS = 172800

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const route = new URL(request.url).pathname.replace(/^\/+|\/+$/g, '')

    // The mark is public: Seed Vault fetches it for its approval screen with no
    // device header, so it is answered before anything is counted or checked.
    if (route === MARK_PATH) {
      if (request.method === 'GET') return markResponse()
      if (request.method === 'HEAD') return new Response(null, { headers: markResponse().headers })
      return fail(405, 'method', 'GET or HEAD to this.')
    }

    const methods = ROUTES[route]
    if (!methods) {
      return fail(404, 'unknown_route', 'No such route.')
    }
    if (!methods.includes(request.method)) {
      return fail(405, 'method', `${methods.join(' or ')} to this.`)
    }

    const device = deviceOf(request)
    if (!device) {
      return fail(400, 'no_device', 'Missing or malformed X-Heylana-Device.')
    }

    // A session is optional everywhere; a bad one is refused rather than
    // quietly ignored, so the app knows to connect the wallet again.
    const who = await whoIsAsking(request, env, device)
    if (!who) {
      return fail(401, 'bad_session', 'That wallet session has ended. Connect again.')
    }

    const overCap = await chargeOne(env, device, route)
    if (overCap) {
      return fail(429, 'daily_cap', 'That is all for today.')
    }

    const started = clock.now()
    try {
      if (route === 'chat') return await chat(request, env, who, started)
      if (route === 'tts') return await speak(request, env, device, started)
      if (route === 'stt-token') return await sttToken(env, device, started)
      if (route === 'wallet/challenge') return await walletChallenge(request, env, who)
      if (route === 'wallet/verify') return await walletVerify(request, env, who)
      if (route === 'judge') return await judge(request, env, who)
      if (route === 'pay/quote') return await payQuote(request, env, who)
      if (route === 'pay/blockhash') return await payBlockhash(request, env, who)
      if (route === 'pay/confirm') return await payConfirm(request, env, who)
      if (route === 'profile') return await profile(request, env, who)
      if (route === 'send/prepare') return await sendPrepare(request, env, who)
      if (route === 'send/confirm') return await sendConfirm(request, env, who)
      return await me(env, who)
    } catch (error) {
      // Whatever went wrong, the reply is a shape the app understands and
      // carries nothing that could have come from a secret.
      log({ route, device, ms: clock.now() - started, error: 'unhandled' })
      return fail(502, 'upstream', scrub(String(error), env))
    }
  },
}

// ------------------------------------------------------------------- routes

/** A question. The app says what kind of work it is; we choose the model. */
async function chat(request: Request, env: Env, who: Who, started: number): Promise<Response> {
  const device = who.device
  const body = await readJson(request)
  const model = MODELS[String(body.mode)]
  if (!model) return fail(400, 'bad_mode', 'mode must be quick or task.')
  if (!Array.isArray(body.messages) || body.messages.length === 0) {
    return fail(400, 'bad_messages', 'messages must be a non-empty array.')
  }

  // Every question is a talk. Checked before anything is spent upstream, and
  // only counted once the answer has actually come back.
  const now = new Date(clock.now())
  const account = await loadAccount(env, who.key)
  const used = await talksUsed(env, who.key, now)
  const spent = spendTalk(account, used, now)
  if (!spent.allowed) {
    const where = standing(account, used, now)
    log({ route: 'chat', device, wallet: who.wallet?.slice(0, 8), talks_cap: true })
    return json(429, {
      reason: 'talks_cap',
      plan: where.plan,
      used: where.used,
      limit: where.limit,
      resets_at: where.resets_at,
    })
  }

  const base = {
    model,
    max_tokens: clampTokens(body.max_tokens),
    system: typeof body.system === 'string' ? body.system : undefined,
    messages: body.messages,
  }
  const callModel = (payload: unknown) =>
    fetch(ANTHROPIC_URL, {
      method: 'POST',
      headers: {
        'x-api-key': env.ANTHROPIC_API_KEY,
        'anthropic-version': ANTHROPIC_VERSION,
        'content-type': 'application/json',
      },
      body: JSON.stringify(payload),
    })

  // Tools go only with the questions the app marked as Solana ones. Everything
  // else is sent exactly as it always was, at exactly the size it always was.
  const withTools = body.tools === true
  const sendIntent = withTools && body.intent === 'send'
  let status: number
  let text: string
  let tokensIn: number
  let tokensOut: number
  let rounds = 1
  let toolCalls: string[] = []
  let toolTimeout = false
  let toolMs = 0
  let toolTimings: string[] = []
  let signingMs: number | undefined
  let sendAction: { to: unknown; amount: unknown; token: unknown } | null = null
  if (sendIntent) {
    // A send is never left to prose: one call, the send written down and nothing
    // else. The app writes every word the user sees and hears about it.
    const result = await proposeSend({ callModel, base })
    status = result.status
    text = result.body
    tokensIn = result.input
    tokensOut = result.output
    sendAction = result.action
  } else if (withTools) {
    const context = toolContext(env, who)
    const signing = body.signing as { short?: unknown; typed?: unknown } | undefined
    if (signing && typeof signing === 'object') {
      const checkStarted = clock.now()
      const checks = await checkShortAddresses(signing.short, signing.typed, context, env.CAPS)
      if (checks.length > 0) {
        base.messages = withAddressChecks(base.messages, checkLines(checks))
        signingMs = clock.now() - checkStarted
      }
    }
    const result = await answerWithTools({ callModel, base, context, now: clock.now })
    status = result.status
    text = result.body
    tokensIn = result.input
    tokensOut = result.output
    rounds = result.rounds
    toolCalls = result.toolCalls
    toolTimeout = result.timedOut
    toolMs = result.toolMs
    toolTimings = result.timings
  } else {
    const upstream = await callModel(base)
    status = upstream.status
    text = await upstream.text()
    const usage = usageOf(text)
    tokensIn = usage.input
    tokensOut = usage.output
  }

  if (status >= 200 && status < 300) {
    await saveAccount(env, who.key, spent.account)
    await env.CAPS.put(talksKey(who.key, now), String(spent.used), { expirationTtl: TALKS_TTL_SECONDS })
  }
  log({
    route: 'chat',
    device,
    wallet: who.wallet?.slice(0, 8),
    ms: clock.now() - started,
    model,
    tools: withTools,
    rounds,
    tool_calls: toolCalls,
    tool_ms: toolMs,
    tool_timings: toolTimings,
    ...(signingMs !== undefined ? { signing_ms: signingMs } : {}),
    ...(sendIntent
      ? { send_action: sendAction ? { to: String(sendAction.to).slice(0, 4), amount: sendAction.amount, token: sendAction.token } : null }
      : {}),
    ...(toolTimeout ? { tool_timeout: true } : {}),
    status,
    tokens_in: tokensIn,
    tokens_out: tokensOut,
  })

  return new Response(scrub(text, env), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

/** Some text to say out loud, in one of Heylana's two voices. */
async function speak(request: Request, env: Env, device: string, started: number): Promise<Response> {
  const body = await readJson(request)
  const text = String(body.text ?? '').slice(0, MAX_TTS_CHARS)
  if (text.trim().length === 0) return fail(400, 'no_text', 'Nothing to say.')

  const voiceId = body.voice === 'archie' ? env.VOICE_ARCHIE : env.VOICE_SKYLAR

  const upstream = await fetch(CARTESIA_URL, {
    method: 'POST',
    headers: {
      'X-API-Key': env.CARTESIA_API_KEY,
      'Cartesia-Version': CARTESIA_VERSION,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model_id: CARTESIA_MODEL,
      transcript: text,
      voice: { mode: 'id', id: voiceId },
      language: 'en',
      output_format: {
        container: 'raw',
        encoding: 'pcm_s16le',
        sample_rate: TTS_SAMPLE_RATE,
      },
    }),
  })

  log({
    route: 'tts',
    device,
    ms: clock.now() - started,
    status: upstream.status,
    chars: text.length,
    voice: body.voice === 'archie' ? 'archie' : 'skylar',
  })

  if (!upstream.ok) {
    return fail(502, 'upstream', scrub(await upstream.text(), env))
  }

  // Straight through, so the phone can start playing before the sentence ends.
  return new Response(upstream.body, {
    status: 200,
    headers: {
      'content-type': 'audio/L16',
      'x-sample-rate': String(TTS_SAMPLE_RATE),
      'cache-control': 'no-store',
    },
  })
}

/**
 * A pair of ears for the next two minutes. The project key stays here; the
 * phone gets something that stops working almost immediately.
 */
async function sttToken(env: Env, device: string, started: number): Promise<Response> {
  const upstream = await fetch(`${DEEPGRAM_KEYS_URL}/${env.DEEPGRAM_PROJECT_ID}/keys`, {
    method: 'POST',
    headers: {
      authorization: `Token ${env.DEEPGRAM_API_KEY}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      comment: `heylana ${device.slice(0, 8)}`,
      scopes: ['usage:write'],
      time_to_live_in_seconds: STT_KEY_TTL_SECONDS,
    }),
  })

  const text = await upstream.text()
  const scopeProblem = text.includes('INSUFFICIENT_PERMISSIONS') || text.includes('keys:write')
  log({
    route: 'stt-token',
    device,
    ms: clock.now() - started,
    status: upstream.status,
    scope_problem: scopeProblem || undefined,
  })

  if (!upstream.ok) {
    // The one failure worth naming: a Deepgram key that is not allowed to mint
    // keys can never work, so the phone should stop waiting and say why.
    if (scopeProblem) {
      return fail(
        502,
        'deepgram_scope',
        "The Deepgram key cannot mint keys. It needs the 'keys:write' scope — " +
          'make an owner key in the Deepgram console and put it in again.',
      )
    }
    return fail(502, 'upstream', scrub(text, env))
  }

  const minted = JSON.parse(text)
  return new Response(
    JSON.stringify({ key: minted.key, expires_in: STT_KEY_TTL_SECONDS }),
    { status: 200, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } },
  )
}

// ----------------------------------------------------------------- wallets

/**
 * Step one of connecting a wallet: a message for it to sign. Nothing on-chain,
 * nothing that moves funds — only proof the wallet is the user's.
 */
async function walletChallenge(request: Request, env: Env, who: Who): Promise<Response> {
  if (!env.SESSION_SECRET) return fail(503, 'not_configured', 'Wallet sign-in is not set up.')
  const body = await readJson(request)
  if (!isAddress(body.pubkey)) return fail(400, 'bad_pubkey', 'pubkey must be a Solana address.')

  const nonce = randomNonce()
  const message = challengeMessage(body.pubkey, nonce, new Date(clock.now()).toISOString())
  await env.CAPS.put(`challenge:${body.pubkey}`, JSON.stringify({ nonce, message }), {
    expirationTtl: CHALLENGE_TTL_SECONDS,
  })
  log({ route: 'wallet/challenge', device: who.device, wallet: body.pubkey.slice(0, 8) })
  return json(200, { nonce, message })
}

/**
 * Step two: the signed message comes back. If the signature is the wallet's, the
 * phone gets a session for thirty days — and a wallet seen for the first time
 * gets its welcome talks.
 */
async function walletVerify(request: Request, env: Env, who: Who): Promise<Response> {
  if (!env.SESSION_SECRET) return fail(503, 'not_configured', 'Wallet sign-in is not set up.')
  const body = await readJson(request)
  const pubkey = body.pubkey
  if (!isAddress(pubkey)) return fail(400, 'bad_pubkey', 'pubkey must be a Solana address.')

  const stored = await env.CAPS.get(`challenge:${pubkey}`)
  const challenge = stored ? JSON.parse(stored) : null
  if (!challenge || challenge.nonce !== body.nonce) {
    return fail(400, 'bad_challenge', 'Ask for a new challenge and sign that.')
  }
  // One challenge, one attempt.
  await env.CAPS.delete(`challenge:${pubkey}`)

  if (!(await verifySignature(pubkey, challenge.message, String(body.signature ?? '')))) {
    log({ route: 'wallet/verify', device: who.device, wallet: pubkey.slice(0, 8), verified: false })
    return fail(401, 'bad_signature', 'That signature is not from this wallet.')
  }

  const key = walletKey(pubkey)
  const welcome = grantWelcome(await loadAccount(env, key))
  if (welcome.granted) await saveAccount(env, key, welcome.account)

  // First sign-in starts a profile. Its name would be the wallet's Seeker ID
  // (.skr), but Solana Mobile documents no public reverse-lookup API — .skr names
  // are AllDomains accounts read on mainnet — so it starts empty for the user.
  if (!(await loadProfile(env, pubkey))) await saveProfile(env, pubkey, { name: '', call_me: '' })

  const now = new Date(clock.now())
  const session = await signSession(pubkey, env.SESSION_SECRET, now.getTime())
  log({ route: 'wallet/verify', device: who.device, wallet: pubkey.slice(0, 8), welcome: welcome.granted })
  return json(200, {
    session,
    pubkey,
    welcome_granted: welcome.granted,
    me: { ...standing(welcome.account, await talksUsed(env, key, now), now), wallet: pubkey, cluster: clusterOf(env) },
  })
}

/** A judge's code: Pro until judging ends, on the wallet if there is one. */
async function judge(request: Request, env: Env, who: Who): Promise<Response> {
  if (!env.JUDGE_CODE) return fail(503, 'not_configured', 'Judge codes are not set up.')
  const body = await readJson(request)
  if (!sameText(String(body.code ?? '').trim(), env.JUDGE_CODE)) {
    log({ route: 'judge', device: who.device, accepted: false })
    return fail(403, 'bad_code', 'That code is not right.')
  }
  const account = makeJudge(await loadAccount(env, who.key), env.JUDGE_UNTIL)
  await saveAccount(env, who.key, account)
  log({ route: 'judge', device: who.device, wallet: who.wallet?.slice(0, 8), accepted: true })
  return me(env, who)
}

/** What a wallet is called, and what Heylana calls its owner. */
interface Profile {
  name: string
  call_me: string
}

const MAX_NAME = 40

/** One short line of plain text: the name goes into what the model is told. */
export function cleanName(raw: unknown): string {
  return String(raw ?? '')
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, MAX_NAME)
    .trim()
}

async function loadProfile(env: Env, pubkey: string): Promise<Profile | null> {
  const stored = await env.CAPS.get(`profile:${pubkey}`)
  return stored ? (JSON.parse(stored) as Profile) : null
}

async function saveProfile(env: Env, pubkey: string, value: Profile): Promise<void> {
  await env.CAPS.put(`profile:${pubkey}`, JSON.stringify(value))
}

/** GET what the wallet is called; PUT what to call them. Never logs the name. */
async function profile(request: Request, env: Env, who: Who): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  const current = (await loadProfile(env, who.wallet)) ?? { name: '', call_me: '' }
  if (request.method === 'GET') return json(200, current)

  const body = await readJson(request)
  const updated: Profile = { name: current.name, call_me: cleanName(body.call_me) }
  await saveProfile(env, who.wallet, updated)
  log({ route: 'profile', device: who.device, wallet: who.wallet.slice(0, 8), saved: true })
  return json(200, updated)
}

/** Where this wallet (or this bare device) stands right now. */
async function me(env: Env, who: Who): Promise<Response> {
  const now = new Date(clock.now())
  const account = await loadAccount(env, who.key)
  const used = await talksUsed(env, who.key, now)
  return json(200, { ...standing(account, used, now), wallet: who.wallet, cluster: clusterOf(env) })
}

// ------------------------------------------------------------------- paying

/**
 * What to send for 30 days of Pro, in USDC or SKR. The reference is a fresh
 * address the phone puts in the transaction so this exact payment can be found.
 */
async function payQuote(request: Request, env: Env, who: Who): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!payConfigured(env)) return fail(503, 'not_configured', 'Payments are not set up.')
  const body = await readJson(request)

  let mint: string
  let decimals: number
  let program: string
  let amount: bigint
  if (body.currency === 'usdc') {
    mint = env.USDC_MINT
    decimals = USDC_DECIMALS
    program = TOKEN_PROGRAM
    amount = decimalToUnits(env.PRICE_USD, decimals)
  } else if (body.currency === 'skr') {
    if (clusterOf(env) === 'devnet') return fail(503, 'not_on_devnet', 'There is no SKR on devnet.')
    if (!isAddress(env.SKR_MINT)) return fail(503, 'not_configured', 'SKR payments are not set up.')
    mint = env.SKR_MINT
    // Decimals and token program come from the chain, not from a guess.
    const info = await mintInfo(env.RPC_URL, mint)
    if (info.program !== TOKEN_PROGRAM && info.program !== TOKEN_2022_PROGRAM) {
      return fail(502, 'upstream', 'The SKR mint is not a token mint.')
    }
    decimals = info.decimals
    program = info.program
    amount = usdToTokenUnits(env.PRICE_USD, await usdPrice(mint, env.JUPITER_API_KEY), decimals)
  } else {
    return fail(400, 'bad_currency', 'currency must be usdc or skr.')
  }

  const now = clock.now()
  const quote: Quote = {
    currency: body.currency,
    mint,
    amount: amount.toString(),
    decimals,
    token_program: program,
    treasury: env.TREASURY_ADDRESS,
    reference: newReference(),
    expires_at: new Date(now + QUOTE_TTL_MS).toISOString(),
    pubkey: who.wallet,
  }
  // Kept for an hour: a payment sent just before the quote expired still confirms.
  await env.CAPS.put(`quote:${quote.reference}`, JSON.stringify(quote), { expirationTtl: 3600 })
  log({ route: 'pay/quote', device: who.device, wallet: who.wallet.slice(0, 8), currency: quote.currency })

  // The dollar price rides along so the app can say what a moving token is worth.
  const { pubkey: _owner, ...shown } = quote
  return json(200, { ...shown, price_usd: env.PRICE_USD })
}

/** A recent blockhash, fetched at the moment Pay is tapped so it is still fresh. */
async function payBlockhash(request: Request, env: Env, who: Who): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!env.RPC_URL) return fail(503, 'not_configured', 'Payments are not set up.')
  // A blockhash from one cluster makes a transaction the other cannot land.
  const body = await readJson(request)
  const cluster = clusterOf(env)
  if (body.cluster !== undefined && body.cluster !== cluster) {
    return fail(409, 'wrong_cluster', `Payments are on ${cluster}.`)
  }
  const result = await rpc(env.RPC_URL, 'getLatestBlockhash', [{ commitment: 'confirmed' }])
  return json(200, {
    blockhash: result?.value?.blockhash,
    last_valid_block_height: result?.value?.lastValidBlockHeight,
    cluster,
  })
}

/**
 * The phone says it paid; the chain has to agree. Answers 409 not_confirmed while
 * the transaction is not yet confirmed, so the phone keeps asking; 402 with a
 * reason if the transaction is not the payment quoted; and Pro once it is. A
 * reference is only ever paid for once.
 */
async function payConfirm(request: Request, env: Env, who: Who): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!payConfigured(env)) return fail(503, 'not_configured', 'Payments are not set up.')
  const body = await readJson(request)
  const reference = String(body.reference ?? '')
  const signature = String(body.signature ?? '')
  if (!isAddress(reference) || !/^[1-9A-HJ-NP-Za-km-z]{64,90}$/.test(signature)) {
    return fail(400, 'bad_request', 'reference and signature are required.')
  }

  const stored = await env.CAPS.get(`quote:${reference}`)
  if (!stored) return fail(404, 'unknown_quote', 'That quote has expired. Ask for a new one.')
  const quote = JSON.parse(stored) as Quote
  if (quote.pubkey !== who.wallet) return fail(403, 'not_yours', 'That quote is for another wallet.')

  const now = new Date(clock.now())
  if (await env.CAPS.get(`paid:${reference}`)) {
    const account = await loadAccount(env, who.key)
    return json(200, {
      ...standing(account, await talksUsed(env, who.key, now), now),
      wallet: who.wallet,
      cluster: clusterOf(env),
      confirmed: true,
      already_confirmed: true,
    })
  }
  const usedFor = await env.CAPS.get(`sig:${signature}`)
  if (usedFor && usedFor !== reference) {
    return fail(409, 'signature_used', 'That transaction already paid for something else.')
  }

  const tx = await rpc(env.RPC_URL, 'getTransaction', [
    signature,
    { encoding: 'jsonParsed', commitment: 'confirmed', maxSupportedTransactionVersion: 0 },
  ])
  const verdict = checkPayment(tx, quote)
  if (!verdict.ok) {
    log({ route: 'pay/confirm', device: who.device, wallet: who.wallet.slice(0, 8), accepted: false, reason: verdict.reason })
    if (verdict.reason === 'not_confirmed') return json(409, { reason: 'not_confirmed' })
    return json(402, { reason: verdict.reason })
  }

  // Marked paid before Pro is extended, so a repeated confirm cannot extend twice.
  await env.CAPS.put(`paid:${reference}`, JSON.stringify({ signature, pubkey: who.wallet, at: now.toISOString() }))
  await env.CAPS.put(`sig:${signature}`, reference)
  const account = extendPro(await loadAccount(env, who.key), now, Number(env.PRO_DAYS))
  await saveAccount(env, who.key, account)
  log({
    route: 'pay/confirm', device: who.device, wallet: who.wallet.slice(0, 8),
    accepted: true, currency: quote.currency, amount: verdict.amount,
  })
  return json(200, {
    ...standing(account, await talksUsed(env, who.key, now), now),
    wallet: who.wallet,
    cluster: clusterOf(env),
    confirmed: true,
  })
}

/** Which Solana payments are taken on. Mainnet unless the var says devnet. */
export function clusterOf(env: Env): 'mainnet-beta' | 'devnet' {
  return env.CLUSTER === 'devnet' ? 'devnet' : 'mainnet-beta'
}

// -------------------------------------------------------------------- sending

/** A prepared send is good for fifteen minutes: long enough to read, confirm and sign. */
const SEND_TTL_SECONDS = 900

/**
 * Checks a send the user asked for. Builds and signs nothing: the phone builds
 * the transfer and the user signs it in Seed Vault. Logs amounts, never more
 * than the first four characters of an address.
 */
async function sendPrepare(request: Request, env: Env, who: Who): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!env.RPC_URL) return fail(503, 'not_configured', 'Sending is not set up.')
  const body = await readJson(request)
  const token = String(body.token ?? '').toUpperCase()
  const quote = await prepareSend({ to: body.to, amount: body.amount, token: body.token }, toolContext(env, who))
  if ('error' in quote) {
    log({ route: 'send/prepare', device: who.device, wallet: who.wallet.slice(0, 4), token, amount: String(body.amount ?? ''), refused: quote.error })
    return json(422, { reason: quote.error, detail: quote.detail ?? '' })
  }
  const id = newReference()
  const prepared: PreparedSend = { ...quote, from: who.wallet }
  await env.CAPS.put(`send:${id}`, JSON.stringify(prepared), { expirationTtl: SEND_TTL_SECONDS })
  log({
    route: 'send/prepare', device: who.device, wallet: who.wallet.slice(0, 4), to: quote.to_address.slice(0, 4),
    token: quote.token, amount: quote.amount, new_account: quote.will_create_ata,
  })
  return json(200, { id, ...quote })
}

/** The user signed; did it land as prepared? 409 while it is not confirmed yet. */
async function sendConfirm(request: Request, env: Env, who: Who): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  const body = await readJson(request)
  const id = String(body.id ?? '')
  const signature = String(body.signature ?? '')
  if (!/^[1-9A-HJ-NP-Za-km-z]{64,88}$/.test(signature)) return fail(400, 'bad_signature', 'That is not a transaction signature.')

  const stored = await env.CAPS.get(`send:${id}`)
  if (!stored) return fail(404, 'unknown_send', 'That send has expired. Ask again.')
  const sent = JSON.parse(stored) as PreparedSend
  if (sent.from !== who.wallet) return fail(403, 'not_yours', 'That send belongs to another wallet.')
  if (await env.CAPS.get(`sent:${id}`)) return json(200, { confirmed: true, signature: short(signature), already_confirmed: true })

  const tx = await rpc(env.RPC_URL, 'getTransaction', [
    signature,
    { encoding: 'jsonParsed', commitment: 'confirmed', maxSupportedTransactionVersion: 0 },
  ])
  const verdict = checkSend(tx, sent)
  const logged = { route: 'send/confirm', device: who.device, wallet: who.wallet.slice(0, 4), to: sent.to_address.slice(0, 4), token: sent.token, amount: sent.amount }
  if (!verdict.ok) {
    log({ ...logged, confirmed: false, reason: verdict.reason })
    if (verdict.reason === 'not_confirmed') return json(409, { reason: 'not_confirmed' })
    return json(402, { reason: verdict.reason })
  }
  await env.CAPS.put(`sent:${id}`, signature, { expirationTtl: SEND_TTL_SECONDS })
  log({ ...logged, confirmed: true })
  return json(200, { confirmed: true, signature: short(signature) })
}

/** What the tools may use: the cluster's RPC, prices, the mints, and whose wallet. */
function toolContext(env: Env, who: Who) {
  return {
    rpcUrl: env.RPC_URL,
    mainnetRpcUrl: env.MAINNET_RPC_URL,
    jupiterKey: env.JUPITER_API_KEY,
    usdcMint: env.USDC_MINT,
    skrMint: env.SKR_MINT,
    cluster: clusterOf(env),
    wallet: who.wallet,
    treasury: env.TREASURY_ADDRESS,
    now: clock.now,
  }
}

function payConfigured(env: Env): boolean {
  return (
    isAddress(env.TREASURY_ADDRESS) &&
    isAddress(env.USDC_MINT) &&
    Boolean(env.RPC_URL) &&
    /^\d+(\.\d+)?$/.test(String(env.PRICE_USD ?? '')) &&
    Number(env.PRO_DAYS) > 0
  )
}

// -------------------------------------------------------------------- parts

/**
 * Reads the optional session. Null means a session was sent and is not good; no
 * session at all is fine, and counts against the device.
 */
export async function whoIsAsking(request: Request, env: Env, device: string): Promise<Who | null> {
  const header = request.headers.get('Authorization') ?? ''
  if (!header) return { device, wallet: null, key: deviceKey(device) }
  const token = header.replace(/^Bearer\s+/i, '')
  const wallet = await readSession(token, env.SESSION_SECRET ?? '', clock.now())
  if (!wallet) return null
  return { device, wallet, key: walletKey(wallet) }
}

export function walletKey(pubkey: string): string {
  return `w:${pubkey}`
}

export function deviceKey(device: string): string {
  return `d:${device}`
}

export async function loadAccount(env: Env, key: string): Promise<Account> {
  const raw = await env.CAPS.get(`acct:${key}`)
  if (!raw) return newAccount()
  try {
    return { ...newAccount(), ...JSON.parse(raw) }
  } catch {
    return newAccount()
  }
}

export async function saveAccount(env: Env, key: string, account: Account): Promise<void> {
  await env.CAPS.put(`acct:${key}`, JSON.stringify(account))
}

export function talksKey(key: string, now: Date): string {
  return `talks:${key}:${monthKey(now)}`
}

async function talksUsed(env: Env, key: string, now: Date): Promise<number> {
  return Number((await env.CAPS.get(talksKey(key, now))) ?? '0')
}

/** Compares without leaking how much matched through timing. */
function sameText(given: string, expected: string): boolean {
  const a = new TextEncoder().encode(given)
  const b = new TextEncoder().encode(expected)
  let diff = a.length ^ b.length
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0)
  return diff === 0
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  })
}

/** The device id the app generated once, at install. */
export function deviceOf(request: Request): string | null {
  const raw = request.headers.get('X-Heylana-Device')?.trim() ?? ''
  return /^[0-9a-fA-F-]{16,64}$/.test(raw) ? raw : null
}

/** Counts one request against today's allowance. True when it is over. */
export async function chargeOne(env: Env, device: string, route: string): Promise<boolean> {
  const cap = DAILY_CAPS[route]
  const key = `cap:${today()}:${device}:${route}`
  const used = Number((await env.CAPS.get(key)) ?? '0')
  if (used >= cap) return true
  await env.CAPS.put(key, String(used + 1), { expirationTtl: CAP_KEY_TTL_SECONDS })
  return false
}

function today(): string {
  return new Date(clock.now()).toISOString().slice(0, 10)
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  try {
    const parsed = await request.json()
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function clampTokens(value: unknown): number {
  const asked = Number(value)
  if (!Number.isFinite(asked) || asked <= 0) return 300
  return Math.min(Math.floor(asked), MAX_OUTPUT_TOKENS)
}

function usageOf(text: string): { input: number; output: number } {
  try {
    const usage = JSON.parse(text)?.usage
    return { input: Number(usage?.input_tokens ?? -1), output: Number(usage?.output_tokens ?? -1) }
  } catch {
    return { input: -1, output: -1 }
  }
}

/**
 * Last line of defence: no reply ever carries a key, however an upstream
 * service chose to word its error.
 */
export function scrub(text: string, env: Env): string {
  const secrets = [
    env.ANTHROPIC_API_KEY,
    env.CARTESIA_API_KEY,
    env.DEEPGRAM_API_KEY,
    env.SESSION_SECRET,
    env.JUDGE_CODE,
    env.RPC_URL,
    env.JUPITER_API_KEY,
    env.MAINNET_RPC_URL,
  ]
  let safe = text
  for (const secret of secrets) {
    if (secret && secret.length > 6) safe = safe.split(secret).join('***')
  }
  return safe
}

function fail(status: number, reason: string, detail: string): Response {
  return new Response(JSON.stringify({ reason, detail }), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

/** One line per request. Names and numbers only — never content. */
function log(fields: Record<string, unknown>): void {
  const shown = { ...fields, device: String(fields.device ?? '').slice(0, 8) }
  console.log(JSON.stringify(shown))
}
