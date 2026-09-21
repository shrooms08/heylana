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
import {
  challengeMessage, randomNonce, readConfirmation, readSession, signConfirmation, signSession, verifySignature,
} from './session.ts'
import { R3_INTENTS, actionRisk, entry, type Decision } from './registry.ts'
import { inUserWords, withoutActions } from './policy.ts'
import { aboutBlock, newRecord, parseMemory, refusal, relevant, withRecord, type Memory } from './memory.ts'
import { MARK_PATH, markResponse } from './mark.ts'
import { ASSETLINKS_PATH, assetLinksResponse } from './assetlinks.ts'
import { rpcClusterMismatch } from './cluster.ts'
import { makeRpc, type Rpc } from './rpc.ts'
import { SYSTEM_PROGRAM } from './instructions.ts'
import {
  LOOKOUT_FORMAT, LOOKOUT_KEEP_SECONDS, LOOKOUT_KEY, LOOKOUT_SEED_KEY, PHANTOM_SEED, archiveFor, dayOf as lookoutDay, fold,
  domainsIn, type Lookout,
} from './lookout.ts'
import {
  FIRSTS_TTL_SECONDS, FIRST_DESTINATION, FIRST_PROGRAM, firstsKey, hashFirst, parseFirsts, remember, unseen,
  type Firsts,
} from './firsts.ts'
import {
  WEEK_PREFIX, WEEK_TTL_SECONDS, anythingCaught, applyWeek, breakdown, cardLine, emptyWeek, nothingCaught, weekStart,
  type Week, type WeekPatch,
} from './week.ts'
import {
  USAGE_PREFIX, USAGE_TTL_SECONDS, apply as applyUsage, daysBack, dayOf, emptyDay, isEmpty as usageIsEmpty,
  priceSheet, summarise, type DayUsage, type UsagePatch,
} from './usage.ts'
import { ANSWER_MAX_TOKENS, answerWithTools, forcedAnswer, proposeAction, proposeSend, toolsNamed } from './brain.ts'
import { sentryFor, type WaitUntil } from './sentry.ts'
import { SHORTEN_MAX_TOKENS, shortenRequest, shortenSystem } from './shorten.ts'
import { checkLines, checkShortAddresses, recentCounterparties, withAddressChecks } from './shortaddr.ts'
import { prepareSend } from './tools.ts'
import { checkSend, type PreparedSend } from './send.ts'
import { buildAndSimulate, labelFor, needsAccount, tokenAccountRent, type Built } from './build.ts'
import { ASSOCIATED_TOKEN_PROGRAM, type TransferPlan } from './tx.ts'
import { short, unitsToDecimal } from './solana.ts'
import { checkedBody, wrappedProse } from './say.ts'
import { flushIfDue, safely, tally } from './tally.ts'
import {
  FRAME_AUDIO, FRAME_REPLY, FRAME_VOICE_FAILED, SAY_STREAM_TYPE, SayReader, frame, readModelStream, replyBody, textFrame,
} from './saystream.ts'
import { addCacheUse, noCacheUse, withCache, withCacheTotals } from './cache.ts'
import { ingest, isUserHowTo, lookUpFirst, searchKb, withKbContext, withSources, type Ai, type Kb, type KbResult, type VectorIndex } from './kb.ts'
import {
  GEMINI_API_REVISION, GEMINI_TTS_MODEL, GEMINI_TTS_URL, deepgramSpeakUrl, deepgramVoiceFor, geminiPcmStream, geminiRequest,
  geminiVoiceFor, providerOf, rawPcmStream, voiceInfo,
} from './voice.ts'
import {
  type Account, type PlanName, type Standing, extendPro, grantWelcome, makeJudge, monthKey, newAccount, planOf, spendTalk, standing,
} from './plans.ts'
import {
  type Quote, QUOTE_TTL_MS, TOKEN_2022_PROGRAM, TOKEN_PROGRAM, USDC_DECIMALS, checkPayment, decimalToUnits,
  PERIOD_DAYS, isPeriod, mintInfo, newReference, usdPrice, usdToTokenUnits, type Period,
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
  /** Only needed when VOICE_PROVIDER is "cartesia". */
  CARTESIA_API_KEY?: string
  /** Gemini TTS, the default voice. */
  GEMINI_API_KEY?: string
  DEEPGRAM_API_KEY: string
  /** AssemblyAI's key, for minting the third ear's single-use streaming tokens. Optional: without it that ear sits out. */
  ASSEMBLYAI_API_KEY?: string
  /** Seals session tokens. Long and random; changing it signs everyone out. */
  SESSION_SECRET: string
  /** The code that turns an account into a judge's. */
  JUDGE_CODE: string
  /** A Solana mainnet RPC endpoint. Its URL carries a token, so it is a secret. */
  /** Devnet's RPC. On devnet this is the only provider; on mainnet the two below are used instead. */
  RPC_URL: string
  /** RPC Fast, mainnet. */
  RPCFAST_URL?: string
  /** Which mainnet provider is tried first: "rpcfast" (the default) or "helius". */
  RPC_PRIMARY?: string
  /** Reads /admin/usage. Without it the route does not exist. */
  ADMIN_SECRET?: string
  /** What each thing costs, as JSON ([priceSheet]); the defaults stand in when it is unset. */
  PRICES?: string
  /** Optional: raises Jupiter's rate limit above the keyless one. */
  JUPITER_API_KEY?: string
  /** Optional: a mainnet RPC for .skr names, which live on mainnet whatever CLUSTER is. */
  MAINNET_RPC_URL?: string
  /** Optional: where unhandled errors are reported, scrubbed. Unset means nowhere. */
  SENTRY_DSN?: string
  /** Workers AI and the Vectorize index behind search_solana_kb. Unbound means no knowledge base. */
  AI?: Ai
  KB?: VectorIndex
  /** Lets scripts/kb/build.sh write to the knowledge base. Unset means nobody can. */
  KB_ADMIN_SECRET?: string
  /** Signing certificate SHA-256 fingerprints for assetlinks.json, comma-separated. */
  ASSETLINKS_SHA256?: string

  /** Plain configuration. */
  DEEPGRAM_PROJECT_ID: string
  /** "deepgram" (Aura), "gemini" (the default when unset) or "cartesia". */
  VOICE_PROVIDER?: string
  /** Optional: a newer Gemini TTS model id than the built-in one. */
  GEMINI_TTS_MODEL?: string
  /** Cartesia voice ids, used only when VOICE_PROVIDER is "cartesia". */
  VOICE_SKYLAR?: string
  VOICE_ARCHIE?: string
  /** The last day of judging, as YYYY-MM-DD. Judge plans end with it. */
  JUDGE_UNTIL: string
  /** Where Pro payments go: the treasury wallet's address, not a token account. */
  TREASURY_ADDRESS: string
  USDC_MINT: string
  /** Filled in from Solscan before deploying; "replace-me" turns SKR off. */
  SKR_MINT: string
  /** The price of 30 days of Pro in US dollars, as text: "15", or "0.10" to test. */
  /** 30 days of Pro, in dollars. */
  PRICE_USD: string
  /** A year of it, in dollars. Unset means a year cannot be bought. */
  PRICE_YEAR_USD?: string
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
 * a ceiling over the plans, never a plan in itself. Questions and spoken answers are
 * capped like this on Free only; Pro and Judge get [PAID_DAILY_CEILING].
 */
export const DAILY_CAP_FREE_CHAT = 150

const DAILY_CAPS: Record<string, number> = {
  chat: DAILY_CAP_FREE_CHAT,
  tts: 150,
  week: 200,
  'stt-token': 300,
  // AssemblyAI's tokens are single-use, so one is minted at every touch of the disc.
  'stt-token-aai': 600,
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
  // A preview, the final build at Confirm, and a rebuild if it went stale.
  'send/build': 150,
  // One per confirmed send, payment, message or reminder.
  confirm: 200,
  memory: 300,
  'memory/delete': 200,
  lookout: 20,
  'memory/wipe': 20,
  'memory/consent': 20,
}

/** Pro and Judge questions and spoken answers: unlimited in practice, with an abuse ceiling. */
export const PAID_DAILY_CEILING = 2000

/** The routes whose daily cap depends on the plan. */
const PLAN_CAPPED_ROUTES = new Set(['chat', 'tts'])

/** Today's cap for a route on a plan: Free keeps the device cap; Pro and Judge get the ceiling. */
export function dailyCapFor(route: string, plan: PlanName): number {
  if (PLAN_CAPPED_ROUTES.has(route) && plan !== 'free') return PAID_DAILY_CEILING
  return DAILY_CAPS[route]
}

/** Every route the worker answers, and the methods each takes. */
const ROUTES: Record<string, readonly string[]> = {
  chat: ['POST'],
  tts: ['POST'],
  'stt-token': ['POST'],
  'stt-token-aai': ['POST'],
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
  'send/build': ['POST'],
  confirm: ['POST'],
  week: ['GET'],
  memory: ['GET', 'POST'],
  'memory/delete': ['POST'],
  lookout: ['GET'],
  'memory/wipe': ['POST'],
  'memory/consent': ['POST'],
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
/** AssemblyAI Universal-Streaming: a temporary token for one session, opened within this many seconds. */
export const AAI_TOKEN_URL = 'https://streaming.assemblyai.com/v3/token'
export const AAI_TOKEN_TTL_SECONDS = 60
/** A hold is never longer than the phone's 20 seconds; a session well past that is not a hold. */
export const AAI_MAX_SESSION_SECONDS = 120

/** Spoken answers are short by design; this is the ceiling, not the target. */
const MAX_TTS_CHARS = 400

const MAX_OUTPUT_TOKENS = 1024

/** Two days, so a cap key outlives the day it counts without piling up. */
const CAP_KEY_TTL_SECONDS = 172800

export default {
  async fetch(request: Request, env: Env, context?: WaitUntil): Promise<Response> {
    const route = new URL(request.url).pathname.replace(/^\/+|\/+$/g, '')

    // The mark is public: Seed Vault fetches it for its approval screen with no
    // device header, so it is answered before anything is counted or checked.
    if (route === MARK_PATH) {
      if (request.method === 'GET') return markResponse()
      if (request.method === 'HEAD') return new Response(null, { headers: markResponse().headers })
      return fail(405, 'method', 'GET or HEAD to this.')
    }

    // Seed Vault checks the app's identity here, with no device header either.
    if (route === ASSETLINKS_PATH) {
      if (request.method === 'GET' || request.method === 'HEAD') {
        return assetLinksResponse(env.ASSETLINKS_SHA256, request.method === 'HEAD')
      }
      return fail(405, 'method', 'GET or HEAD to this.')
    }

    // The operator's own door: counts and an estimated cost. A secret, no device, no caps.
    if (route === 'admin/usage') return await adminUsage(request, env)

    // The knowledge base's own door, for scripts/kb/build.sh: a secret, no device, no caps.
    if (route === 'kb/ingest' || route === 'kb/search') {
      try {
        return await kbAdmin(route, request, env)
      } catch (error) {
        // Workers AI or Vectorize failed (a spent daily allowance is "4006"): said plainly, with its code.
        const code = String((error as Error)?.message ?? '').match(/^\d{4}/)?.[0] ?? 'error'
        log({ route, error: 'kb_unavailable', code })
        return fail(503, 'kb_unavailable', `The knowledge base could not search (${code}).`)
      }
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

    // Only chat and tts depend on the plan, so only they pay for reading the account.
    const plan: PlanName = PLAN_CAPPED_ROUTES.has(route) ? planOf(await loadAccount(env, who.key), new Date(clock.now())) : 'free'
    const overCap = await chargeOne(env, device, route, dailyCapFor(route, plan))
    if (overCap) {
      return fail(429, 'daily_cap', 'That is all for today.')
    }

    const started = clock.now()
    // One RPC caller for this request: every chain call goes through it, and it keeps what
    // each call cost so the log below can carry them.
    const rpc = makeRpc(env, {
      now: clock.now,
      // One line per chain call: which method, which provider, how long, how it went.
      onCall: (call) => log({ route: 'rpc', method: call.method, provider: call.provider, ms: call.ms, outcome: call.outcome }),
    })
    // This request's own counters. It is also put in `current` so log(), count() and
    // caught() can reach it without being handed it everywhere — but the finally below
    // reads **this** object, never the global one: two requests overlap in one isolate
    // all the time (the two ear passes are fetched together at every touch), and the
    // second one to start used to null the first one's state out from under it. That
    // threw out of the finally, which Cloudflare answers as a 500 — the intermittent
    // "token_refused_500" an ear saw about a third of the time.
    const scope = { rpc, logged: false, usage: {} as UsagePatch, week: {} as WeekPatch }
    current = scope
    try {
      if (route === 'chat') return await chat(request, env, who, started, rpc, context)
      if (route === 'tts') return await speak(request, env, device, started)
      if (route === 'stt-token') return await sttToken(env, device, started)
      if (route === 'stt-token-aai') return await sttTokenAai(env, device, started)
      if (route === 'wallet/challenge') return await walletChallenge(request, env, who)
      if (route === 'wallet/verify') return await walletVerify(request, env, who)
      if (route === 'judge') return await judge(request, env, who)
      if (route === 'pay/quote') return await payQuote(request, env, who, rpc)
      if (route === 'pay/blockhash') return await payBlockhash(request, env, who, rpc)
      if (route === 'pay/confirm') return await payConfirm(request, env, who, rpc)
      if (route === 'profile') return await profile(request, env, who)
      if (route === 'send/prepare') return await sendPrepare(request, env, who, rpc)
      if (route === 'send/confirm') return await sendConfirm(request, env, who, rpc)
      if (route === 'send/build') return await sendBuild(request, env, who, rpc)
      if (route === 'confirm') return await confirm(request, env, who)
      if (route === 'week') return await week(env, who)
      if (route === 'lookout') return await lookout(request, env, started, context)
      if (route === 'memory' || route.startsWith('memory/')) return await memory(route, request, env, who)
      return await me(env, who)
    } catch (error) {
      // Whatever went wrong, the reply is a shape the app understands and
      // carries nothing that could have come from a secret.
      log({ route, device, ms: clock.now() - started, error: 'unhandled' })
      sentryFor(request, env.SENTRY_DSN, context, (text) => scrub(text, env))?.captureException(error)
      // The phone says "Something went wrong on my side."; what happened is in the log and Sentry.
      return fail(500, 'internal', 'Something went wrong on Heylana\'s side.')
    } finally {
      // A route that logs nothing of its own still says what its chain calls cost.
      if (rpc.calls.length > 0 && !scope.logged) log({ route, device, ms: clock.now() - started })
      // The day's counters, written once per request and never in its way.
      const patch: UsagePatch = {
        ...scope.usage,
        wallet: who.wallet,
        rpc: rpc.calls.map((call) => ({ provider: call.provider, method: call.method, ms: call.ms })),
      }
      const week = scope.week
      // Only clear the global if this request still owns it: another may have started.
      if (current === scope) current = null
      // Added to this isolate's tally, and written at most once a minute: never in the
      // answer's way, and never able to fail it (tally.ts).
      const accounted = (async () => {
        if (!usageIsEmpty(patch)) await safely('usage', log, () => recordUsage(env, patch), undefined)
        // The week's counts are the user's own, and follow memory: nothing without a wallet.
        if (who.wallet && !nothingCaught(week)) await safely('week', log, () => recordWeek(env, who.wallet!, week), undefined)
        await flushIfDue(env.CAPS, clock.now(), log)
      })().catch(() => {})
      if (context?.waitUntil) context.waitUntil(accounted)
      else await accounted
    }
  },
}

// ------------------------------------------------------------------- routes

/** A question. The app says what kind of work it is; we choose the model. */
async function chat(request: Request, env: Env, who: Who, started: number, rpc: Rpc, context?: WaitUntil): Promise<Response> {
  const device = who.device
  const body = await readJson(request)
  const model = MODELS[String(body.mode)]
  if (!model) return fail(400, 'bad_mode', 'mode must be quick or task.')
  if (!Array.isArray(body.messages) || body.messages.length === 0) {
    return fail(400, 'bad_messages', 'messages must be a non-empty array.')
  }

  // "Use my own key": the user's Anthropic key for this request's model calls, and nothing else.
  const own = ownKeyOf(request)
  if (own === 'malformed') return fail(400, 'bad_key', "That doesn't look like an Anthropic key.")
  const userKey = own

  if (body.shorten === true) return await shorten(body, env, who, started, userKey)

  // Every question is a talk. Checked before anything is spent upstream, and
  // only counted once the answer has actually come back.
  const now = new Date(clock.now())
  const account = await loadAccount(env, who.key)
  const used = await talksUsed(env, who.key, now)
  const spent = spendTalk(account, used, now)
  // On the user's own key they pay for the model, so it is not one of their plan's talks.
  if (!spent.allowed && !userKey) {
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
  // What the user chose to have remembered, on every question but a quick action.
  let memoryRecords = 0
  if (who.wallet && body.intent !== 'quick_action') {
    const kept = await loadMemory(env, who.wallet)
    const block = kept.on ? aboutBlock(kept.records) : null
    if (block) {
      base.system = base.system ? `${base.system}\n\n${block}` : block
      memoryRecords = relevant(kept.records).length
    }
  }
  // How many knowledge-base chunks this question was handed, for the log.
  const kbStats: { kbHits: number; found: Map<string, KbResult>; kbError?: string; addresses: string[] } = {
    kbHits: 0, found: new Map<string, KbResult>(), addresses: [],
  }
  // Every round of this question: the system prompt and tools marked cacheable, the cache use added up.
  const cacheUse = noCacheUse()
  const callModel = async (payload: unknown) => {
    const upstream = await fetch(ANTHROPIC_URL, {
      method: 'POST',
      headers: {
        'x-api-key': userKey ?? env.ANTHROPIC_API_KEY,
        'anthropic-version': ANTHROPIC_VERSION,
        'content-type': 'application/json',
      },
      body: JSON.stringify(withCache(payload as Record<string, unknown>)),
    })
    addCacheUse(cacheUse, await upstream.clone().text())
    return upstream
  }

  // Tools go only with the questions the app marked as Solana ones. Everything
  // else is sent exactly as it always was, at exactly the size it always was.
  const withTools = body.tools === true
  const sendIntent = withTools && body.intent === 'send'
  // An alarm, a timer, an app, a page, a place, a number: no lookups, so no tools flag.
  const actionIntent = !sendIntent && body.intent === 'quick_action'
  let status: number
  let text: string
  let tokensIn: number
  let tokensOut: number
  let rounds = 1
  // A walk-through's steps come back in the answer tool's shape: fields, never prose, with
  // room to spare. On the Seeker two steps ran into the phone's 300 tokens with no say at all.
  const forceAnswer = body.shape === 'answer'
  const appSkill = typeof body.app_skill === 'string' && body.app_skill.length > 0
  let limitUsed = base.max_tokens
  let toolCalls: string[] = []
  let toolTimeout = false
  let toolMs = 0
  let toolTimings: string[] = []
  let signingMs: number | undefined
  let sendAction: { to: unknown; amount: unknown; token: unknown } | null = null
  let quickAction: Record<string, unknown> | null = null
  let decisions: Decision[] = []
  // The user's own words, sent apart from the screen: what a recipient must come from.
  const said = typeof body.said === 'string' ? body.said : null
  let actionsRemoved = 0
  let sourcesSent = 0
  let sourcesFrom = 'none'
  let sourcesDropped = 0
  let userHowTo = false
  let proseWrapped = false
  if (actionIntent) {
    // Never left to prose either: the action written down, and the app says the words.
    const result = await proposeAction({ callModel, base })
    status = result.status
    text = result.body
    tokensIn = result.input
    tokensOut = result.output
    quickAction = result.action
    if (result.decision) decisions.push(result.decision)
    // A message or a reminder is R3: it gets an id the app must confirm before firing it.
    if (quickAction && R3_INTENTS.has(String(quickAction.intent))) {
      const id = newReference()
      await env.CAPS.put(`proposed:${id}`, JSON.stringify({ device, intent: quickAction.intent }), { expirationTtl: PROPOSAL_TTL_SECONDS })
      quickAction = { ...quickAction, action_id: id }
      text = forcedAnswer(quickAction, tokensIn, tokensOut)
    }
  } else if (sendIntent) {
    // A send is never left to prose: one call, the send written down and nothing
    // else. The app writes every word the user sees and hears about it.
    const result = await proposeSend({ callModel, base })
    status = result.status
    text = result.body
    tokensIn = result.input
    tokensOut = result.output
    sendAction = result.action
    if (result.decision) decisions.push(result.decision)
    // A recipient only on the screen — a page, a token's name, a memo, a planted prompt — is never proposed.
    if (sendAction && !inUserWords(sendAction.to, said)) {
      decisions.push({ decision: 'rejected', tool: 'propose_send', class: 'R2', reason: 'recipient_not_in_user_words' })
      sendAction = null
      text = forcedAnswer(null, tokensIn, tokensOut)
    }
  } else if (withTools) {
    const context = { ...toolContext(env, who, rpc), stats: kbStats, seenBefore: seenBeforeFor(env, who, rpc) }
    const signing = body.signing as { short?: unknown; typed?: unknown } | undefined
    if (signing && typeof signing === 'object') {
      const checkStarted = clock.now()
      const checks = await checkShortAddresses(signing.short, signing.typed, context, env.CAPS)
      if (checks.length > 0) {
        base.messages = withAddressChecks(base.messages, checkLines(checks))
        signingMs = clock.now() - checkStarted
      }
    }
    const offered = toolsNamed(body.tool_names)
    // The knowledge base is read, not offered. A question that may be answered from it —
    // a developer's, an infrastructure one, a lesson turn — has its closest three chunks
    // looked up here and handed over with the question, because left to itself the model
    // answered two out of three from memory and cited nothing. The tool stays offered for
    // a follow-up query once it has read them.
    const knowledge = offered.some((tool) => tool.name === 'search_solana_kb')
    // A user asking how to install, swap or earn is not asking the developer docs: the app's
    // skill has the steps, and the knowledge base is not read first for them.
    const kbQuery = typeof body.kb_query === 'string' && body.kb_query.trim() ? body.kb_query : null
    // Inside a consumer app (a built-in app skill is carried) the developer docs are never a
    // chip, however the question is worded; the word rule stays as the second line.
    userHowTo = !kbQuery && (appSkill || isUserHowTo(said))
    if (knowledge && !userHowTo) {
      base.messages = withKbContext(base.messages, await lookUpFirst(kbOf(env), kbQuery ?? said, kbStats))
    }
    if (offered.length === 0 && !forceAnswer) {
      // Nothing to look up (a signing screen with only shortened addresses, already
      // checked above): one round, no tool definitions to pay for.
      const upstream = await callModel(base)
      status = upstream.status
      text = await upstream.text()
      const usage = usageOf(text)
      tokensIn = usage.input
      tokensOut = usage.output
    } else {
      const result = await answerWithTools({
        callModel, base, context, now: clock.now, tools: offered, said,
        // The last call after a lookup has to write the answer as a tool, so it comes back
        // in the shape the phone reads rather than as markdown prose.
        forceShape: knowledge || forceAnswer,
      })
      if (knowledge || forceAnswer) limitUsed = Math.max(base.max_tokens, ANSWER_MAX_TOKENS)
      status = result.status
      text = result.body
      tokensIn = result.input
      tokensOut = result.output
      rounds = result.rounds
      toolCalls = result.toolCalls
      toolTimeout = result.timedOut
      toolMs = result.toolMs
      toolTimings = result.timings
      decisions = result.decisions
    }
  } else if (forceAnswer) {
    // No lookups, only the answer's shape: one forced round with the answer tool alone.
    const context = { ...toolContext(env, who, rpc), stats: kbStats }
    const result = await answerWithTools({ callModel, base, context, now: clock.now, tools: [], said, forceShape: true })
    status = result.status
    text = result.body
    tokensIn = result.input
    tokensOut = result.output
    rounds = result.rounds
    limitUsed = Math.max(base.max_tokens, ANSWER_MAX_TOKENS)
  } else if (speakable(body, env)) {
    // One trip: the answer is streamed and each finished sentence goes straight to the
    // voice, so the first word is heard while the rest is still being written.
    return await spokenAnswer({
      env, who, started, model, base, userKey, spent, now, context,
      voice: body.voice === 'archie' ? 'archie' : 'skylar',
      account,
      caughtKind: typeof body.caught === 'string' ? body.caught : '',
    })
  } else {
    const upstream = await callModel(base)
    status = upstream.status
    text = await upstream.text()
    const usage = usageOf(text)
    tokensIn = usage.input
    tokensOut = usage.output
  }

  // The reply's say is checked here: segments the phone cannot use never reach it.
  let saySegments = 0
  if (status >= 200 && status < 300 && !actionIntent && !sendIntent) {
    const checked = checkedBody(text)
    text = checked.body
    saySegments = checked.segments
    // Prose where a reply was due: put into the contract here rather than asked for again.
    const wrapped = wrappedProse(text)
    text = wrapped.body
    proseWrapped = wrapped.wrapped
    // An action rides only on the routes made for it: never on an ordinary answer.
    const stripped = withoutActions(text)
    text = stripped.body
    actionsRemoved = stripped.removed
    // The pages the answer cited, checked against what the knowledge base returned.
    const cited = withSources(text, kbStats.found, body.kb_query ? null : said)
    text = cited.body
    sourcesSent = cited.sources
    sourcesFrom = cited.from
    sourcesDropped = cited.dropped ?? 0
  }

  // The phone logs whether the answer fit: the limit it was given and why it stopped.
  const stopped = stopOf(text)
  if (status >= 200 && status < 300) text = withLimit(text, limitUsed)

  if (status >= 200 && status < 300) {
    if (!userKey) await recordTalk(env, who.key, account, spent, now)
  }
  // For the day's counters: which model answered, and what it read and wrote.
  if (status >= 200 && status < 300) count({ chatModel: model, tokensIn: tokensIn, tokensOut: tokensOut })
  // And for the week's card: a question answered, and what kind it was. The kind is a word
  // the app sends ("screen", "transaction", "lesson"); never a word of the question itself.
  if (status >= 200 && status < 300) {
    const kind = typeof body.caught === 'string' ? body.caught : ''
    caught({
      questions: 1,
      screens: kind === 'screen' ? 1 : 0,
      transactions: kind === 'transaction' || signingMs !== undefined ? 1 : 0,
      lessons: kind === 'lesson' ? 1 : 0,
      // Which addresses were looked up is kept only as salted hashes, to tell a first look from a second.
      seen: who.wallet
        ? await Promise.all([...new Set(kbStats.addresses)].map((address) => hashSeen(address, env.SESSION_SECRET)))
        : [],
    })
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
    // The kind of action and its times only: names, numbers, places and pages stay out of the log.
    ...(actionIntent
      ? {
        quick_action: quickAction
          ? {
              intent: quickAction.intent, hour: quickAction.hour, minutes: quickAction.minutes, seconds: quickAction.seconds,
              command: quickAction.command, state: quickAction.state, page: quickAction.page,
            }
          : null,
      }
      : {}),
    ...(toolTimeout ? { tool_timeout: true } : {}),
    // Every tool call the model made: the tool, its class, and what the registry decided.
    ...(decisions.length > 0
      ? { tool_decisions: decisions.map((d) => ({ tool: d.tool, class: d.class, decision: d.decision, ...(d.decision === 'rejected' ? { reason: d.reason } : {}) })) }
      : {}),
    ...(quickAction ? { action_class: actionRisk(quickAction.intent) } : {}),
    ...(actionsRemoved > 0 ? { actions_removed: actionsRemoved } : {}),
    // Prompt caching: tokens read from the cache and written to it, over every round.
    kb_hits: kbStats.kbHits,
    kb_error: kbStats.kbError,
    prose_wrapped: proseWrapped || undefined,
    sources: sourcesSent,
    sources_from: sourcesFrom,
    ...(userHowTo ? { user_how_to: true } : {}),
    ...(sourcesDropped > 0 ? { sources_dropped_developer: sourcesDropped } : {}),
    // Whose key paid for the model: never the key itself.
    key: userKey ? 'user' : 'heylana',
    cache_read: cacheUse.read,
    cache_write: cacheUse.write,
    stop_reason: stopped,
    output_tokens: tokensOut,
    max_tokens: limitUsed,
    ...(forceAnswer ? { shape: 'answer' } : {}),
    ...(appSkill ? { app_skill: true } : {}),
    ...(memoryRecords > 0 ? { memory_records: memoryRecords } : {}),
    ...(saySegments > 1 ? { say_segments: saySegments } : {}),
    status,
    tokens_in: tokensIn,
    tokens_out: tokensOut,
  })

  // The phone's usage line (and the eval) see the cache too: tokens read and written over every round.
  if (status >= 200 && status < 300) text = withCacheTotals(text, cacheUse)
  if (userKey && status === 401) return fail(401, 'own_key_refused', 'Anthropic refused your own key. Check it in Advanced.')
  // The model failed (over its limit, overloaded, refused): its words never go to the phone.
  if (status < 200 || status >= 300) return brainUnavailable(status)

  return new Response(scrub(text, env, [userKey]), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

/**
 * Whether this question can be answered and spoken in one trip: an ordinary answer, no
 * tools, no forced action, and a provider whose voice streams raw audio.
 */
/**
 * The phishing blocklist, whole, for the phone to check against on its own.
 *
 * The phone says which version it already has; if that is today's, it gets a few bytes
 * back instead of the list. The list itself is public, so nothing here is secret — what
 * matters is the direction it travels: out to the phone, never a domain back.
 */
async function lookout(request: Request, env: Env, started: number, context?: WaitUntil): Promise<Response> {
  const today = lookoutDay(clock.now())
  let list = await readLookout(env)
  // A day old or missing: rebuild it, but never keep the phone waiting on a source.
  // A list built before the filter was dropped is rebuilt at once, not tomorrow.
  if (list === null || list.version !== today || list.format !== LOOKOUT_FORMAT) {
    const rebuilt = await buildLookout(env, list, today)
    if (rebuilt) {
      list = rebuilt
      // Yesterday's list keeps answering if today's cannot be kept.
      const write = env.CAPS.put(LOOKOUT_KEY, JSON.stringify(rebuilt), { expirationTtl: LOOKOUT_KEEP_SECONDS }).catch(() => {})
      if (context?.waitUntil) context.waitUntil(write)
      else await write
    }
  }
  if (!list) return json(200, { version: '', domains: [], sources: [] })
  const have = new URL(request.url).searchParams.get('have')
  log({
    route: 'lookout', ms: clock.now() - started, version: list.version,
    domains: list.domains.length, unchanged: have === list.version,
  })
  if (have && have === list.version) return json(200, { version: list.version, unchanged: true })
  return json(200, list)
}

async function readLookout(env: Env): Promise<Lookout | null> {
  try {
    const stored = await env.CAPS.get(LOOKOUT_KEY)
    if (!stored) return null
    const parsed = JSON.parse(stored)
    if (!Array.isArray(parsed?.domains)) return null
    return {
      version: String(parsed.version ?? ''), domains: parsed.domains, sources: parsed.sources ?? [],
      format: typeof parsed.format === 'number' ? parsed.format : undefined,
    }
  } catch {
    return null
  }
}

/**
 * Today's list: whatever it had, plus Phantom's frozen seed the first time, plus the day's
 * archive from Scam Sniffer. A source that will not answer is skipped, never waited on:
 * yesterday's list is a fine answer, and no list at all is an honest one.
 */
async function buildLookout(env: Env, current: Lookout | null, today: string): Promise<Lookout | null> {
  const sources = new Set(current?.sources ?? [])
  let domains = current?.domains ?? []

  const get = async (url: string) => {
    const res = await fetch(url, { headers: { 'user-agent': 'heylana-lookout/1' }, signal: AbortSignal.timeout(4000) })
    if (!res.ok) throw new Error(String(res.status))
    return await res.text()
  }

  let dropped = 0
  if (!(await env.CAPS.get(LOOKOUT_SEED_KEY))) {
    try {
      // The frozen snapshot goes in at the end: past the cap, it is what goes first.
      const seeded = fold(domains, domainsIn(await get(PHANTOM_SEED)), { last: true })
      domains = seeded.domains
      dropped += seeded.dropped
      sources.add('phantom/blocklist (snapshot)')
      await env.CAPS.put(LOOKOUT_SEED_KEY, today, { expirationTtl: LOOKOUT_KEEP_SECONDS })
    } catch {
      // The seed is a one-off; missing it costs the list its oldest entries and nothing else.
    }
  }
  try {
    const archived = fold(domains, domainsIn(await get(archiveFor(clock.now()))))
    domains = archived.domains
    dropped += archived.dropped
    sources.add('scamsniffer/scam-database')
  } catch {
    // No archive for that day, or the source is down: the list stands as it was.
  }
  // Never silently: a cap that cut the list is worth knowing about.
  if (dropped > 0) log({ route: 'lookout', what: 'cap_truncated', dropped, kept: domains.length, level: 'warn' })
  if (domains.length === 0) return null
  return { version: today, domains, sources: [...sources], format: LOOKOUT_FORMAT }
}

function speakable(body: any, env: Env): boolean {
  if (body.speak !== true) return false
  const provider = providerOf(env.VOICE_PROVIDER)
  return provider === 'deepgram' || provider === 'gemini'
}

/**
 * The answer, streamed, speaking as it is written.
 *
 * The model is asked to stream; [SayReader] takes `say` apart as it arrives, and each
 * finished sentence is sent to the voice at once — the next sentence's audio is already
 * being made while the one before it is still being sent. The phone gets one stream of
 * frames: the audio, then the finished reply as soon as the model has written it.
 *
 * The plain path's accounting is all here too, since the request is long over by the time
 * the last word is said: the talk is counted, the day's counters written, the week's
 * counts kept, and one log line carries what it cost.
 */
async function spokenAnswer(options: {
  env: Env
  who: Who
  started: number
  model: string
  base: { model: string; max_tokens: number; system?: string; messages: unknown[] }
  userKey: string | null
  spent: { allowed: boolean; account: Account; used: number }
  /** The account as it was before this talk, to tell whether spending it changed anything. */
  account: Account
  now: Date
  context?: WaitUntil
  voice: 'skylar' | 'archie'
  caughtKind: string
}): Promise<Response> {
  const { env, who, started, model, base, userKey, spent, now, context, voice } = options
  const device = who.device

  const upstream = await fetch(ANTHROPIC_URL, {
    method: 'POST',
    headers: {
      'x-api-key': userKey ?? env.ANTHROPIC_API_KEY,
      'anthropic-version': ANTHROPIC_VERSION,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ ...withCache(base as Record<string, unknown>), stream: true }),
  })

  // A refusal is answered exactly as it always was: plain JSON, nothing streamed.
  if (!upstream.ok || !upstream.body) {
    await upstream.text().catch(() => '')
    log({ route: 'chat', device, wallet: who.wallet?.slice(0, 8), ms: clock.now() - started, model, spoken: true, status: upstream.status })
    if (userKey && upstream.status === 401) {
      return fail(401, 'own_key_refused', 'Anthropic refused your own key. Check it in Advanced.')
    }
    return brainUnavailable(upstream.status)
  }

  const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>()
  const writer = writable.getWriter()
  const pump = speakWhileWriting({ ...options, body: upstream.body, writer, voice })
  if (context?.waitUntil) context.waitUntil(pump)
  return new Response(readable, { status: 200, headers: { 'content-type': SAY_STREAM_TYPE, 'x-sample-rate': String(SPOKEN_RATE) } })
}

/** 24 kHz mono 16-bit, what both providers make and what the phone plays. */
const SPOKEN_RATE = 24000

async function speakWhileWriting(options: {
  env: Env
  who: Who
  started: number
  model: string
  userKey: string | null
  spent: { allowed: boolean; account: Account; used: number }
  now: Date
  voice: 'skylar' | 'archie'
  caughtKind: string
  body: ReadableStream<Uint8Array>
  writer: WritableStreamDefaultWriter<Uint8Array>
}): Promise<void> {
  const { env, who, started, model, userKey, spent, now, voice, body, writer } = options
  const device = who.device
  const reader = new SayReader()

  let spokenChars = 0
  let sentences = 0
  let firstSentenceMs = -1
  let firstAudioMs = -1
  let voiceFailure: string | null = null
  // Every write goes through this one chain, so no frame is ever cut in half by another.
  let writes: Promise<void> = Promise.resolve()

  const say = (text: string) => {
    if (voiceFailure) return
    if (sentences === 0) firstSentenceMs = clock.now() - started
    sentences++
    // The voice is asked for now; its audio joins the stream when the sentence before it is done.
    const asked = voiceStream(env, text, voice)
    writes = writes.then(async () => {
      const audio = await asked.catch(() => ({ stream: null, failure: '0 error' }) as VoiceStream)
      if (!audio.stream) {
        voiceFailure = voiceFailure ?? audio.failure ?? '0 error'
        return
      }
      const pcm = audio.stream.getReader()
      for (;;) {
        const { done, value } = await pcm.read()
        if (done) break
        if (value.length === 0) continue
        if (firstAudioMs < 0) firstAudioMs = clock.now() - started
        await writer.write(frame(FRAME_AUDIO, value))
      }
      // Only what was really heard is counted against the voice's day.
      spokenChars += text.length
    })
  }

  let streamError: string | null = null
  let usage = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
  try {
    const read = await readModelStream(body, (delta) => {
      for (const sentence of reader.take(delta)) say(sentence)
    })
    usage = read.usage
    streamError = read.error
    const last = reader.flush()
    if (last) say(last)
  } catch {
    streamError = streamError ?? 'stream_broken'
  }

  // The finished reply, in the shape the app has always read, checked the same way.
  let text = replyBody(reader.body, usage, model)
  const checked = checkedBody(text)
  text = withoutActions(checked.body).body

  writes = writes.then(async () => {
    await writer.write(textFrame(FRAME_REPLY, scrub(text, env, [userKey])))
    // Nothing was heard: the phone says it the old way, so an answer is never silent.
    const heard = firstAudioMs >= 0
    const note = !heard ? (voiceFailure ?? NOT_SPOKEN) : voiceFailure ? PARTLY_SPOKEN : null
    if (note) await writer.write(textFrame(FRAME_VOICE_FAILED, note))
  })

  try {
    await writes
  } catch {
    // The phone hung up: nothing left to say, and the counting below still happens.
  }
  await writer.close().catch(() => {})

  const ok = !streamError && reader.body.length > 0
  if (ok && !userKey) {
    await recordTalk(env, who.key, options.account, spent, now)
  }
  log({
    route: 'chat',
    device,
    wallet: who.wallet?.slice(0, 8),
    ms: clock.now() - started,
    model,
    spoken: true,
    sentences,
    // Where the first word came from in the answer's life, which is what this is all for.
    first_sentence_ms: firstSentenceMs,
    first_audio_ms: firstAudioMs,
    chars: spokenChars,
    voice_failed: voiceFailure ?? undefined,
    stream_error: streamError ?? undefined,
    key: userKey ? 'user' : 'heylana',
    tokens_in: usage.input,
    tokens_out: usage.output,
    cache_read: usage.cacheRead,
    cache_write: usage.cacheWrite,
    say_segments: checked.segments,
  })

  if (!ok) return
  const patch: UsagePatch = {
    wallet: who.wallet,
    chatModel: model,
    tokensIn: usage.input,
    tokensOut: usage.output,
    ...(spokenChars > 0 ? { tts: { provider: providerOf(env.VOICE_PROVIDER), chars: spokenChars } } : {}),
  }
  await safely('usage', log, () => recordUsage(env, patch), undefined)
  if (who.wallet) {
    const kind = options.caughtKind
    await recordWeek(env, who.wallet, {
      questions: 1,
      screens: kind === 'screen' ? 1 : 0,
      transactions: kind === 'transaction' ? 1 : 0,
      lessons: kind === 'lesson' ? 1 : 0,
    }).catch(() => {})
  }
  // A spoken answer finishes after its request's own flush has run, so it offers one of its own.
  await flushIfDue(env.CAPS, clock.now(), log)
}

/** Nothing was spoken at all, so the phone speaks the answer itself, the old way. */
const NOT_SPOKEN = 'not_spoken'
/** Part of it was heard: the rest is let go rather than said twice. */
const PARTLY_SPOKEN = 'partly_spoken'

interface VoiceStream {
  stream: ReadableStream<Uint8Array> | null
  failure?: string
}

/** One sentence as raw 16-bit audio, from whichever provider is configured. */
async function voiceStream(env: Env, text: string, slot: 'skylar' | 'archie'): Promise<VoiceStream> {
  const provider = providerOf(env.VOICE_PROVIDER)
  try {
    if (provider === 'gemini') {
      if (!env.GEMINI_API_KEY) return { stream: null, failure: '503 not_set_up' }
      const model = env.GEMINI_TTS_MODEL || GEMINI_TTS_MODEL
      const upstream = await fetch(`${GEMINI_TTS_URL}?key=${env.GEMINI_API_KEY}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-goog-api-revision': GEMINI_API_REVISION },
        body: JSON.stringify(geminiRequest(text, geminiVoiceFor(slot), model)),
      })
      if (!upstream.ok || !upstream.body) return { stream: null, failure: `${upstream.status} upstream` }
      return { stream: geminiPcmStream(upstream.body, {}) }
    }
    const upstream = await fetch(deepgramSpeakUrl(deepgramVoiceFor(slot)), {
      method: 'POST',
      headers: { authorization: `Token ${env.DEEPGRAM_API_KEY}`, 'content-type': 'application/json' },
      body: JSON.stringify({ text }),
    })
    if (!upstream.ok || !upstream.body) {
      return { stream: null, failure: upstream.status === 429 ? '429 quota' : `${upstream.status} upstream` }
    }
    return { stream: rawPcmStream(upstream.body) }
  } catch {
    return { stream: null, failure: '0 error' }
  }
}

/** Which cloud ears the phone may borrow a pass for: the privacy line names each one that listens. */
export function earsOf(env: Pick<Env, 'ASSEMBLYAI_API_KEY'>): string[] {
  return env.ASSEMBLYAI_API_KEY ? ['deepgram', 'assemblyai'] : ['deepgram']
}

function brainUnavailable(upstreamStatus: number): Response {
  return new Response(JSON.stringify({ reason: 'brain_unavailable', detail: 'The model did not answer.', upstream_status: upstreamStatus }), {
    status: 502,
    headers: { 'content-type': 'application/json' },
  })
}

/**
 * An answer that ran long, said again in fewer words. The worker writes the whole
 * request, so it cannot be used as a free question; see shorten.ts.
 */
async function shorten(body: any, env: Env, who: Who, started: number, userKey: string | null = null): Promise<Response> {
  const asked = shortenRequest(body)
  if (!asked) return fail(400, 'bad_shorten', 'shorten needs one short text.')
  const model = MODELS.quick
  const upstream = await fetch(ANTHROPIC_URL, {
    method: 'POST',
    headers: {
      'x-api-key': userKey ?? env.ANTHROPIC_API_KEY,
      'anthropic-version': ANTHROPIC_VERSION,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model,
      max_tokens: SHORTEN_MAX_TOKENS,
      system: shortenSystem(asked.maxWords),
      messages: [{ role: 'user', content: asked.text }],
    }),
  })
  const text = await upstream.text()
  const usage = usageOf(text)
  log({
    route: 'chat',
    device: who.device,
    wallet: who.wallet?.slice(0, 8),
    ms: clock.now() - started,
    model,
    shorten: true,
    max_words: asked.maxWords,
    key: userKey ? 'user' : 'heylana',
    status: upstream.status,
    tokens_in: usage.input,
    tokens_out: usage.output,
  })
  if (userKey && upstream.status === 401) return fail(401, 'own_key_refused', 'Anthropic refused your own key. Check it in Advanced.')
  if (!upstream.ok) return brainUnavailable(upstream.status)
  return new Response(scrub(text, env, [userKey]), { status: upstream.status, headers: { 'content-type': 'application/json' } })
}

/** Some text to say out loud, in one of Heylana's two voices, by the configured provider. */
async function speak(request: Request, env: Env, device: string, started: number): Promise<Response> {
  const body = await readJson(request)
  const text = String(body.text ?? '').slice(0, MAX_TTS_CHARS)
  if (text.trim().length === 0) return fail(400, 'no_text', 'Nothing to say.')
  const slot = body.voice === 'archie' ? 'archie' : 'skylar'
  const provider = providerOf(env.VOICE_PROVIDER)
  if (provider === 'deepgram') return speakDeepgram(env, device, started, text, slot)
  if (provider === 'cartesia') return speakCartesia(env, device, started, text, slot)
  return speakGemini(env, device, started, text, slot)
}

async function speakDeepgram(env: Env, device: string, started: number, text: string, slot: string): Promise<Response> {
  const voice = deepgramVoiceFor(slot)
  const upstream = await fetch(deepgramSpeakUrl(voice), {
    method: 'POST',
    headers: {
      authorization: `Token ${env.DEEPGRAM_API_KEY}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({ text }),
  })

  if (upstream.ok) count({ tts: { provider: 'deepgram', chars: text.length } })
  log({ route: 'tts', device, provider: 'deepgram', voice, ms: clock.now() - started, status: upstream.status, chars: text.length })

  if (!upstream.ok || !upstream.body) {
    const detail = scrub(await upstream.text().catch(() => ''), env)
    if (upstream.status === 429) return fail(429, 'quota', 'The voice is out of quota for now.')
    return fail(502, 'upstream', detail)
  }

  // Straight through as it is made, so the phone starts playing before the sentence ends.
  return new Response(rawPcmStream(upstream.body), { status: 200, headers: AUDIO_HEADERS })
}

const AUDIO_HEADERS = {
  'content-type': 'audio/L16',
  'x-sample-rate': String(TTS_SAMPLE_RATE),
  'cache-control': 'no-store',
}

async function speakGemini(env: Env, device: string, started: number, text: string, slot: string): Promise<Response> {
  const voice = geminiVoiceFor(slot)
  const model = env.GEMINI_TTS_MODEL?.trim() || GEMINI_TTS_MODEL
  if (!env.GEMINI_API_KEY) {
    log({ route: 'tts', device, provider: 'gemini', voice, status: 503, error: 'no_key' })
    return fail(503, 'voice_not_configured', 'The voice is not set up.')
  }
  const upstream = await fetch(GEMINI_TTS_URL, {
    method: 'POST',
    headers: {
      'x-goog-api-key': env.GEMINI_API_KEY,
      'Api-Revision': GEMINI_API_REVISION,
      'content-type': 'application/json',
      accept: 'text/event-stream',
    },
    body: JSON.stringify(geminiRequest(text, voice, model)),
  })

  if (upstream.ok) count({ tts: { provider: 'gemini', chars: text.length } })
  log({ route: 'tts', device, provider: 'gemini', voice, model, ms: clock.now() - started, status: upstream.status, chars: text.length })

  if (!upstream.ok || !upstream.body) {
    const detail = scrub(await upstream.text().catch(() => ''), env)
    // Google's quota or rate limit: the phone says nothing and shows the words.
    if (upstream.status === 429) return fail(429, 'quota', 'The voice is out of quota for now.')
    return fail(502, 'upstream', detail)
  }

  // Decoded as each event lands, so the phone starts playing before the sentence is done.
  const pcm = geminiPcmStream(upstream.body, (stats) => {
    log({
      route: 'tts_end', device, provider: 'gemini', voice, ms: clock.now() - started,
      events: stats.events, audio_events: stats.audioEvents, bytes: stats.bytes,
      ...(stats.error ? { error: stats.error } : {}),
    })
  })
  return new Response(pcm, { status: 200, headers: AUDIO_HEADERS })
}

async function speakCartesia(env: Env, device: string, started: number, text: string, slot: string): Promise<Response> {
  const voiceId = slot === 'archie' ? env.VOICE_ARCHIE : env.VOICE_SKYLAR

  const upstream = await fetch(CARTESIA_URL, {
    method: 'POST',
    headers: {
      'X-API-Key': env.CARTESIA_API_KEY ?? '',
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

  if (upstream.ok) count({ tts: { provider: 'cartesia', chars: text.length } })
  log({ route: 'tts', device, provider: 'cartesia', voice: slot, ms: clock.now() - started, status: upstream.status, chars: text.length })

  if (!upstream.ok) {
    if (upstream.status === 429) return fail(429, 'quota', 'The voice is out of quota for now.')
    return fail(502, 'upstream', scrub(await upstream.text(), env))
  }

  // Straight through, so the phone can start playing before the sentence ends.
  return new Response(upstream.body, { status: 200, headers: AUDIO_HEADERS })
}

/**
 * A pair of ears for the next two minutes. The project key stays here; the
 * phone gets something that stops working almost immediately.
 */
/**
 * The third ear: a single-use AssemblyAI streaming token, good for [AAI_TOKEN_TTL_SECONDS] to
 * open one session of at most [AAI_MAX_SESSION_SECONDS]. The real key stays here. The
 * phone opens the socket itself, as it does Deepgram's.
 */
async function sttTokenAai(env: Env, device: string, started: number): Promise<Response> {
  if (!env.ASSEMBLYAI_API_KEY) return fail(503, 'not_set_up', 'AssemblyAI is not set up on this worker.')
  const url = `${AAI_TOKEN_URL}?expires_in_seconds=${AAI_TOKEN_TTL_SECONDS}&max_session_duration_seconds=${AAI_MAX_SESSION_SECONDS}`
  const upstream = await fetch(url, { headers: { authorization: env.ASSEMBLYAI_API_KEY } })
  const text = await upstream.text()
  if (upstream.ok) count({ ears: { provider: 'assemblyai' } })
  log({ route: 'stt-token-aai', device, ms: clock.now() - started, status: upstream.status })
  if (!upstream.ok) {
    if (upstream.status === 429) return fail(429, 'quota', 'The ears are out of quota for now.')
    return fail(502, 'upstream', 'AssemblyAI did not give a token.')
  }
  const token = (() => {
    try {
      return JSON.parse(text)?.token
    } catch {
      return undefined
    }
  })()
  if (typeof token !== 'string' || !token) return fail(502, 'upstream', 'AssemblyAI did not give a token.')
  return new Response(
    JSON.stringify({ key: token, expires_in: AAI_TOKEN_TTL_SECONDS }),
    { status: 200, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } },
  )
}

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
  count({ ears: { provider: 'deepgram' } })
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
    me: {
      ...standing(welcome.account, await talksUsed(env, key, now), now), wallet: pubkey, cluster: clusterOf(env),
      voice: { ...voiceInfo(env.VOICE_PROVIDER), ears: earsOf(env) },
    },
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

// -------------------------------------------------------------------- memory

const memoryKey = (wallet: string) => `memory:${wallet}`

async function loadMemory(env: Env, wallet: string): Promise<Memory> {
  return parseMemory(await env.CAPS.get(memoryKey(wallet)))
}

/**
 * The user's memory, per wallet: read it, add to it, delete a line, wipe it, or turn it
 * on or off (off wipes it). Nothing is kept until it is on. Logged by counts and
 * categories only, never a word of what is kept.
 */
async function memory(route: string, request: Request, env: Env, who: Who): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet to use memory.')
  const current = await loadMemory(env, who.wallet)
  const save = (next: Memory) => env.CAPS.put(memoryKey(who.wallet!), JSON.stringify(next))
  const wallet = who.wallet.slice(0, 4)

  if (route === 'memory' && request.method === 'GET') return json(200, current)
  const body = await readJson(request)

  if (route === 'memory/consent') {
    const on = body.on === true
    // Turning it off keeps nothing — the week's counts and what it has seen before are memory's too.
    await save({ on, records: on ? current.records : [] })
    if (!on) {
      const thisWeek = weekKey(who.wallet, weekStart(clock.now()))
      // Nothing pending may write it back after it is gone.
      tally.forget(thisWeek)
      await env.CAPS.delete(thisWeek)
      await env.CAPS.delete(firstsKey(who.wallet))
    }
    log({ route, device: who.device, wallet, on })
    return json(200, { on, records: on ? current.records : [] })
  }
  if (route === 'memory/wipe') {
    await save({ on: current.on, records: [] })
    // Wipe all means all: the addresses and programs it had seen before go too.
    await env.CAPS.delete(firstsKey(who.wallet))
    log({ route, device: who.device, wallet, wiped: current.records.length })
    return json(200, { on: current.on, records: [] })
  }
  if (route === 'memory/delete') {
    const records = current.records.filter((r) => r.id !== String(body.id ?? ''))
    await save({ on: current.on, records })
    log({ route, device: who.device, wallet, deleted: current.records.length - records.length })
    return json(200, { on: current.on, records })
  }
  if (route === 'memory' && request.method === 'POST') {
    if (!current.on) return json(403, { reason: 'memory_off', detail: 'Memory is off. Turn it on in Menu, Memory.' })
    const why = refusal(body)
    if (why) {
      log({ route, device: who.device, wallet, category: String(body.category ?? ''), refused: why })
      return json(422, { reason: why, detail: "That isn't something I keep." })
    }
    const record = newRecord(body, newReference().slice(0, 12), clock.now())
    const records = withRecord(current.records, record)
    await save({ on: true, records })
    // A lesson's progress is how a finished lesson reaches the worker: the week counts it.
    if (record.category === 'skill_progress') caught({ lessons: 1 })
    log({ route, device: who.device, wallet, category: record.category, consent: record.consent, count: records.length })
    return json(200, { record, count: records.length })
  }
  return fail(404, 'unknown_route', 'No such memory route.')
}

/** Where this wallet (or this bare device) stands right now. */
async function me(env: Env, who: Who): Promise<Response> {
  const now = new Date(clock.now())
  const account = await loadAccount(env, who.key)
  const used = await talksUsed(env, who.key, now)
  return json(200, {
    ...standing(account, used, now), wallet: who.wallet, cluster: clusterOf(env),
    // Which provider speaks and what its two voices are called: the phone's privacy line and picker follow it.
    voice: { ...voiceInfo(env.VOICE_PROVIDER), ears: earsOf(env) },
  })
}

// ------------------------------------------------------------------- paying

/**
 * What to send for 30 days of Pro, in USDC or SKR. The reference is a fresh
 * address the phone puts in the transaction so this exact payment can be found.
 */
async function payQuote(request: Request, env: Env, who: Who, rpc: Rpc): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!payConfigured(env)) return fail(503, 'not_configured', 'Payments are not set up.')
  const body = await readJson(request)

  // A month or a year. An older app sends no period at all, and means a month.
  const period: Period = body.period === undefined ? 'month' : isPeriod(body.period) ? body.period : 'invalid' as Period
  if (!isPeriod(period)) return fail(400, 'bad_period', 'period must be month or year.')
  const priceUsd = period === 'year' ? env.PRICE_YEAR_USD : env.PRICE_USD
  if (!priceUsd || !/^\d+(\.\d+)?$/.test(priceUsd)) {
    return fail(503, 'not_configured', period === 'year' ? 'A year of Pro is not set up.' : 'Payments are not set up.')
  }
  const days = period === 'year' ? PERIOD_DAYS.year : Number(env.PRO_DAYS) || PERIOD_DAYS.month

  let mint: string
  let decimals: number
  let program: string
  let amount: bigint
  if (body.currency === 'usdc') {
    mint = env.USDC_MINT
    decimals = USDC_DECIMALS
    program = TOKEN_PROGRAM
    amount = decimalToUnits(priceUsd, decimals)
  } else if (body.currency === 'skr') {
    if (clusterOf(env) === 'devnet') return fail(503, 'not_on_devnet', 'There is no SKR on devnet.')
    if (!isAddress(env.SKR_MINT)) return fail(503, 'not_configured', 'SKR payments are not set up.')
    mint = env.SKR_MINT
    // Decimals and token program come from the chain, not from a guess.
    const info = await mintInfo(rpc, mint)
    if (info.program !== TOKEN_PROGRAM && info.program !== TOKEN_2022_PROGRAM) {
      return fail(502, 'upstream', 'The SKR mint is not a token mint.')
    }
    decimals = info.decimals
    program = info.program
    amount = usdToTokenUnits(priceUsd, await usdPrice(mint, env.JUPITER_API_KEY), decimals)
  } else {
    return fail(400, 'bad_currency', 'currency must be usdc or skr.')
  }

  const now = clock.now()
  const quote: Quote = {
    currency: body.currency,
    period,
    price_usd: priceUsd,
    days,
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
  log({ route: 'pay/quote', device: who.device, wallet: who.wallet.slice(0, 8), currency: quote.currency, period, price_usd: priceUsd })

  // The dollar price rides along so the app can say what a moving token is worth.
  const { pubkey: _owner, ...shown } = quote
  return json(200, shown)
}

/** A recent blockhash, fetched at the moment Pay is tapped so it is still fresh. */
async function payBlockhash(request: Request, env: Env, who: Who, rpc: Rpc): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!env.RPC_URL) return fail(503, 'not_configured', 'Payments are not set up.')
  // A blockhash from one cluster makes a transaction the other cannot land.
  const body = await readJson(request)
  const cluster = clusterOf(env)
  if (body.cluster !== undefined && body.cluster !== cluster) {
    return fail(409, 'wrong_cluster', `Payments are on ${cluster}.`)
  }
  // A blockhash from the wrong network makes a transaction the wallet will refuse.
  const mismatch = await rpcClusterMismatch(rpc, cluster)
  if (mismatch) {
    log({ route: 'pay/blockhash', device: who.device, rpc_wrong_cluster: true, cluster })
    return fail(503, 'rpc_wrong_cluster', mismatch)
  }
  const result = await rpc('getLatestBlockhash', [{ commitment: 'confirmed' }])
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
async function payConfirm(request: Request, env: Env, who: Who, rpc: Rpc): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!payConfigured(env)) return fail(503, 'not_configured', 'Payments are not set up.')
  const body = await readJson(request)
  const reference = String(body.reference ?? '')
  // No signature means the wallet ended without one; the reference finds the payment.
  const given = body.signature === undefined || body.signature === null || body.signature === '' ? null : String(body.signature)
  if (!isAddress(reference) || (given !== null && !/^[1-9A-HJ-NP-Za-km-z]{64,90}$/.test(given))) {
    return fail(400, 'bad_request', 'A reference is required, and a signature must be one.')
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
  const signature = given ?? (await paymentByReference(env, rpc, reference, quote))
  if (!signature) {
    const gone = await expired(env, rpc, reference)
    log({ route: 'pay/confirm', device: who.device, wallet: who.wallet.slice(0, 8), looked_up: true, found: false, expired: gone })
    return gone ? json(410, { reason: 'expired' }) : json(409, { reason: 'not_confirmed' })
  }
  const usedFor = await env.CAPS.get(`sig:${signature}`)
  if (usedFor && usedFor !== reference) {
    return fail(409, 'signature_used', 'That transaction already paid for something else.')
  }

  const tx = await rpc('getTransaction', [
    signature,
    { encoding: 'jsonParsed', commitment: 'confirmed', maxSupportedTransactionVersion: 0 },
  ])
  const verdict = checkPayment(tx, quote)
  if (!verdict.ok) {
    log({ route: 'pay/confirm', device: who.device, wallet: who.wallet.slice(0, 8), accepted: false, reason: verdict.reason })
    if (verdict.reason === 'not_confirmed') {
      return (await expired(env, rpc, reference)) ? json(410, { reason: 'expired' }) : json(409, { reason: 'not_confirmed' })
    }
    return json(402, { reason: verdict.reason })
  }

  // Marked paid before Pro is extended, so a repeated confirm cannot extend twice.
  await env.CAPS.put(`paid:${reference}`, JSON.stringify({ signature, pubkey: who.wallet, at: now.toISOString() }))
  await env.CAPS.put(`sig:${signature}`, reference)
  // A quote from before periods existed has no days of its own: it was a month.
  const account = extendPro(await loadAccount(env, who.key), now, Number(quote.days) || Number(env.PRO_DAYS))
  await saveAccount(env, who.key, account)
  count({ proPayments: 1, proUsd: Number(quote.price_usd ?? env.PRICE_USD) || 0 })
  log({
    route: 'pay/confirm', device: who.device, wallet: who.wallet.slice(0, 8),
    accepted: true, currency: quote.currency, period: quote.period ?? 'month', amount: verdict.amount,
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
 * Checks a send the user asked for and keeps it fifteen minutes. /send/build then
 * builds and simulates it; the user signs it in Seed Vault. Logs amounts, never
 * more than the first four characters of an address.
 */
async function sendPrepare(request: Request, env: Env, who: Who, rpc: Rpc): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!env.RPC_URL) return fail(503, 'not_configured', 'Sending is not set up.')
  const mismatch = await rpcClusterMismatch(rpc, clusterOf(env))
  if (mismatch) {
    log({ route: 'send/prepare', device: who.device, rpc_wrong_cluster: true, cluster: clusterOf(env) })
    return json(503, { reason: 'rpc_wrong_cluster', detail: mismatch })
  }
  const body = await readJson(request)
  const token = String(body.token ?? '').toUpperCase()
  // The second check, after the app's SendGuard: the recipient is in the user's own words.
  if (!inUserWords(body.to, body.said)) {
    logDecision(who, 'send', 'rejected', 'recipient_not_in_user_words')
    return json(422, { reason: 'not_in_user_words', detail: 'I can only send to someone you named yourself.' })
  }
  const quote = await prepareSend({ to: body.to, amount: body.amount, token: body.token }, toolContext(env, who, rpc))
  if ('error' in quote) {
    log({ route: 'send/prepare', device: who.device, wallet: who.wallet.slice(0, 4), token, amount: String(body.amount ?? ''), refused: quote.error })
    return json(422, { reason: quote.error, detail: quote.detail ?? '' })
  }
  const id = newReference()
  const prepared: PreparedSend = { ...quote, from: who.wallet, prepared_at: clock.now() }
  await env.CAPS.put(`send:${id}`, JSON.stringify(prepared), { expirationTtl: SEND_TTL_SECONDS })
  count({ sendsPrepared: 1 })
  caught({ sends_prepared: 1 })
  log({
    route: 'send/prepare', device: who.device, wallet: who.wallet.slice(0, 4), to: quote.to_address.slice(0, 4),
    token: quote.token, amount: quote.amount, new_account: quote.will_create_ata,
  })
  return json(200, { id, ...quote })
}

/** What a build is remembered as: enough to tell later whether it could still land. */
interface BuildRecord {
  sim_ok: boolean
  last_valid_block_height: number
  at: number
}

/**
 * Builds the exact transfer for a prepared send (`id`) or a Pro quote (`reference`),
 * simulates it on CLUSTER, and answers with a preview and the simulation's verdict in
 * plain words. The unsigned transaction itself comes back only when `final` is asked
 * for and the simulation passed: a transfer that fails simulation never reaches a
 * wallet. Nothing is signed or submitted here.
 */
async function sendBuild(request: Request, env: Env, who: Who, rpc: Rpc): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  if (!env.RPC_URL) return fail(503, 'not_configured', 'Sending is not set up.')
  const body = await readJson(request)
  const cluster = clusterOf(env)
  if (body.cluster !== undefined && body.cluster !== cluster) {
    log({ route: 'send/build', device: who.device, wrong_cluster: true, asked: String(body.cluster), cluster })
    return json(409, {
      reason: 'wrong_cluster',
      detail: `This is for ${String(body.cluster)}, but Heylana is on ${cluster}. I stopped before building it.`,
    })
  }
  const mismatch = await rpcClusterMismatch(rpc, cluster)
  if (mismatch) {
    log({ route: 'send/build', device: who.device, rpc_wrong_cluster: true, cluster })
    return json(503, { reason: 'rpc_wrong_cluster', detail: mismatch })
  }

  const target = await buildTarget(body, env, who, rpc)
  if ('error' in target) return target.error
  const final = body.final === true
  // R3: the bytes for Seed Vault only with the user's confirmation of exactly this send or payment.
  if (final) {
    const confirmed = await readConfirmation(body.confirmation, env.SESSION_SECRET, clock.now())
    const holds = confirmed !== null && confirmed.kind === target.kind && confirmed.subject === target.subject && confirmed.holder === who.key
    logDecision(who, target.kind, holds ? 'allowed' : 'rejected', holds ? undefined : 'confirmation_required')
    if (!holds) return json(403, { reason: 'confirmation_required', detail: 'Tap Confirm first.' })
  }
  const built = await buildAndSimulate(rpc, target.plan, target.facts)
  // A simulation that failed is a send that never reached the wallet: the week counts it.
  if (!built.simulation.ok) caught({ sends_stopped: 1 })
  // Has this wallet ever sent here before? Memory's question, so memory's switch decides.
  const firstTime = await firstDestination(env, who, rpc, target.plan.to)
  const record: BuildRecord = { sim_ok: built.simulation.ok, last_valid_block_height: built.last_valid_block_height, at: clock.now() }
  await env.CAPS.put(`built:${target.subject}`, JSON.stringify(record), { expirationTtl: SEND_TTL_SECONDS })
  log({
    route: 'send/build', device: who.device, wallet: who.wallet.slice(0, 4), kind: target.kind, final,
    to: target.plan.to.slice(0, 4), token: built.preview.token, amount: built.preview.amount,
    fee: built.preview.fee_sol, new_account: built.preview.creates_account, cluster,
    simulation: built.simulation.ok ? 'passed' : built.simulation.reason,
  })
  return json(200, buildReply(target.kind, built, final, firstTime))
}

function buildReply(kind: 'send' | 'pay', built: Built, final: boolean, firstTime = false) {
  const { transaction, blockhash: _blockhash, does: _does, ...shown } = built
  // Only a transfer that passed simulation, and only when it is about to go to the wallet.
  return {
    kind,
    ...shown,
    preview: { ...built.preview, first_destination: firstTime },
    ...(final && built.simulation.ok ? { transaction } : {}),
  }
}

// ------------------------------------------------------------ first time, per wallet

async function loadFirsts(env: Env, wallet: string): Promise<Firsts> {
  return parseFirsts(await env.CAPS.get(firstsKey(wallet)))
}

async function saveFirsts(env: Env, wallet: string, firsts: Firsts): Promise<void> {
  // Written when a send lands, so kept immediate — but a refused write never fails the confirm.
  await safely('firsts', log, () => env.CAPS.put(firstsKey(wallet), JSON.stringify(firsts), { expirationTtl: FIRSTS_TTL_SECONDS }), undefined)
}

/**
 * Whether this wallet has dealt with [to] before: not among the addresses Heylana has sent
 * to for it, and not among the counterparties of its recent transactions (the same twenty
 * the address check already fetches, cached). Two sources, so the line is not a claim about
 * the wallet's whole history — PRODUCT.md says exactly what it means.
 *
 * Only asked while memory is on: it is the wallet's own history, so it is memory's.
 */
async function firstDestination(env: Env, who: Who, rpc: Rpc, to: string): Promise<boolean> {
  if (!who.wallet) return false
  try {
    const kept = await loadMemory(env, who.wallet)
    if (!kept.on) return false
    const firsts = await loadFirsts(env, who.wallet)
    const hash = await hashFirst(to, env.SESSION_SECRET)
    if (unseen(firsts, 'destination', [hash]).length === 0) return false
    const context = toolContext(env, who, rpc)
    const recent = await recentCounterparties(who.wallet, { ...context, signal: undefined as never }, env.CAPS).catch(() => [])
    return !recent.includes(to)
  } catch {
    // A check that could not be run says nothing, and never says "first time" by default.
    return false
  }
}

/**
 * The question `explain_address` asks of the record: has this wallet dealt with [address]
 * before? Null while there is no wallet or memory is off — then nothing is claimed at all.
 * The answer is the same two sources as a send's: what Heylana has seen, and the
 * counterparties of the wallet's recent transactions.
 */
function seenBeforeFor(env: Env, who: Who, rpc: Rpc): (address: string) => Promise<boolean | null> {
  return async (address: string) => {
    if (!who.wallet) return null
    try {
      const kept = await loadMemory(env, who.wallet)
      if (!kept.on) return null
      if (address === who.wallet || address === env.TREASURY_ADDRESS) return true
      const firsts = await loadFirsts(env, who.wallet)
      const hash = await hashFirst(address, env.SESSION_SECRET)
      if (unseen(firsts, 'destination', [hash]).length === 0) return true
      if (unseen(firsts, 'program', [hash]).length === 0) return true
      const context = toolContext(env, who, rpc)
      const recent = await recentCounterparties(who.wallet, { ...context, signal: undefined as never }, env.CAPS)
      return recent.includes(address)
    } catch {
      return null
    }
  }
}

/** What a landed send teaches the record: where it went, and which programs it called. */
async function rememberSend(env: Env, wallet: string, to: string, programs: string[]): Promise<void> {
  try {
    const kept = await loadMemory(env, wallet)
    if (!kept.on) return
    const [destination, ...rest] = await Promise.all([
      hashFirst(to, env.SESSION_SECRET),
      ...programs.map((program) => hashFirst(program, env.SESSION_SECRET)),
    ])
    let firsts = await loadFirsts(env, wallet)
    firsts = remember(firsts, 'destination', [destination])
    firsts = remember(firsts, 'program', rest)
    await saveFirsts(env, wallet, firsts)
  } catch {
    // The record is a convenience; failing to write it never fails a send.
  }
}

type BuildTarget =
  | { kind: 'send' | 'pay'; subject: string; plan: TransferPlan; facts: Parameters<typeof buildAndSimulate>[2] }
  | { error: Response }

/** The transfer a prepared send or a Pro quote describes, and the facts its words need. */
async function buildTarget(body: any, env: Env, who: Who, rpc: Rpc): Promise<BuildTarget> {
  const cluster = clusterOf(env)
  if (typeof body.id === 'string' && body.id) {
    const stored = await env.CAPS.get(`send:${body.id}`)
    if (!stored) return { error: fail(404, 'unknown_send', 'That send has expired. Ask again.') }
    const sent = JSON.parse(stored) as PreparedSend
    if (sent.from !== who.wallet) return { error: fail(403, 'not_yours', 'That send belongs to another wallet.') }
    const [createsAccount, rent] = sent.mint
      ? await Promise.all([needsAccount(rpc, sent.to_address, sent.mint), tokenAccountRent(rpc, sent.token_program)])
      : [false, 0n]
    return {
      kind: 'send',
      subject: body.id,
      plan: {
        from: sent.from,
        to: sent.to_address,
        mint: sent.mint,
        tokenProgram: sent.token_program,
        units: decimalToUnits(sent.amount, sent.decimals),
        decimals: sent.decimals,
        // The send's id is a fresh random address: carried read-only, it finds this exact send on chain.
        reference: body.id,
      },
      facts: {
        token: sent.token,
        toLabel: labelFor(sent.to_address, { resolvedFrom: sent.resolved_from, treasury: env.TREASURY_ADDRESS, wallet: who.wallet }),
        rentLamports: rent,
        createsAccount,
        balance: sent.balance,
        cluster,
      },
    }
  }
  if (typeof body.reference === 'string' && body.reference) {
    const stored = await env.CAPS.get(`quote:${body.reference}`)
    if (!stored) return { error: fail(404, 'unknown_quote', 'That quote has expired. Ask for a new one.') }
    const quote = JSON.parse(stored) as Quote
    if (quote.pubkey !== who.wallet) return { error: fail(403, 'not_yours', 'That quote is for another wallet.') }
    const [createsAccount, rent, holdings] = await Promise.all([
      needsAccount(rpc, quote.treasury, quote.mint),
      tokenAccountRent(rpc, quote.token_program),
      rpc('getTokenAccountsByOwner', [quote.pubkey, { mint: quote.mint }, { encoding: 'jsonParsed' }]).catch(() => null),
    ])
    const units = (holdings?.value ?? []).reduce(
      (sum: bigint, account: any) => sum + BigInt(account?.account?.data?.parsed?.info?.tokenAmount?.amount ?? '0'),
      0n,
    )
    return {
      kind: 'pay',
      subject: body.reference,
      plan: {
        from: quote.pubkey,
        to: quote.treasury,
        mint: quote.mint,
        tokenProgram: quote.token_program,
        units: BigInt(quote.amount),
        decimals: quote.decimals,
        reference: quote.reference,
      },
      facts: {
        token: quote.currency.toUpperCase(),
        toLabel: 'Heylana, for 30 days of Pro',
        rentLamports: rent,
        createsAccount,
        balance: holdings ? unitsToDecimal(units, quote.decimals) : null,
        cluster,
      },
    }
  }
  return { error: fail(400, 'bad_request', 'Say which send (id) or which quote (reference) to build.') }
}

/**
 * Whether a built transfer can no longer land: the chain has passed the last block
 * its blockhash was good for. Then it was never submitted, and never will be.
 */
async function expired(env: Env, rpc: Rpc, subject: string): Promise<boolean> {
  const stored = await env.CAPS.get(`built:${subject}`)
  if (!stored) return false
  const record = JSON.parse(stored) as BuildRecord
  if (!record.last_valid_block_height) return false
  const height = await rpc('getBlockHeight', [{ commitment: 'confirmed' }]).catch(() => null)
  return typeof height === 'number' && height > record.last_valid_block_height
}

/** One line per R3 decision: the action, its class, allowed or rejected, and why. */
function logDecision(who: Who, action: string, decision: 'allowed' | 'rejected', reason?: string) {
  log({ route: 'policy', device: who.device, tool: action, class: entry(action)?.risk ?? 'unknown', decision, ...(reason ? { reason } : {}) })
}

/** A proposed R3 phone action, kept until the app's guard fires it or ten minutes pass. */
const PROPOSAL_TTL_SECONDS = 600

/**
 * The user confirmed an R3 action: a confirmation token for it, if it is one this
 * worker prepared for this user and it is ready. A send or a payment must have been
 * built with a passing simulation; a message or a reminder must be one the model
 * proposed to this device, and the app's guard must have found it in the user's own
 * words ("guard": "allowed"). A proposal is confirmed once.
 */
async function confirm(request: Request, env: Env, who: Who): Promise<Response> {
  const body = await readJson(request)
  const kind = String(body.kind ?? '')
  const subject = String(body.subject ?? '')
  const refuse = (reason: string) => {
    logDecision(who, kind || 'unknown', 'rejected', reason)
    return json(403, { reason: 'not_confirmable', detail: reason })
  }
  if (!subject) return refuse('no_subject')

  if (kind === 'send' || kind === 'pay') {
    if (!who.wallet) return refuse('session_required')
    const stored = await env.CAPS.get(kind === 'send' ? `send:${subject}` : `quote:${subject}`)
    if (!stored) return refuse('unknown')
    const owner = kind === 'send' ? (JSON.parse(stored) as PreparedSend).from : (JSON.parse(stored) as Quote).pubkey
    if (owner !== who.wallet) return refuse('not_yours')
    const built = await env.CAPS.get(`built:${subject}`)
    if (!built || !(JSON.parse(built) as BuildRecord).sim_ok) return refuse('not_simulated')
  } else if (kind === 'message' || kind === 'reminder') {
    const stored = await env.CAPS.get(`proposed:${subject}`)
    if (!stored) return refuse('not_proposed')
    const proposal = JSON.parse(stored) as { device: string; intent: string }
    if (proposal.device !== who.device || proposal.intent !== kind) return refuse('not_proposed')
    if (body.guard !== 'allowed') return refuse('guard_not_passed')
    await env.CAPS.delete(`proposed:${subject}`)
  } else {
    return refuse('not_r3')
  }
  const token = await signConfirmation({ kind, subject, holder: who.key }, env.SESSION_SECRET, clock.now())
  logDecision(who, kind, 'allowed')
  return json(200, { confirmation: token })
}

/** The user signed; did it land as prepared? 409 while it is not confirmed yet. */
async function sendConfirm(request: Request, env: Env, who: Who, rpc: Rpc): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  const body = await readJson(request)
  const id = String(body.id ?? '')
  // No signature means the wallet ended without one; the transfer is then looked for.
  const given = body.signature === undefined || body.signature === null ? null : String(body.signature)
  if (given !== null && !SIGNATURE.test(given)) return fail(400, 'bad_signature', 'That is not a transaction signature.')

  const stored = await env.CAPS.get(`send:${id}`)
  if (!stored) return fail(404, 'unknown_send', 'That send has expired. Ask again.')
  const sent = JSON.parse(stored) as PreparedSend
  if (sent.from !== who.wallet) return fail(403, 'not_yours', 'That send belongs to another wallet.')
  const already = await env.CAPS.get(`sent:${id}`)
  // The whole signature too, for the Explorer link under "Done": a signature is public on chain,
  // and it goes back only to the wallet that sent it. It is never logged.
  if (already) return json(200, { confirmed: true, signature: short(already), full_signature: already, already_confirmed: true })

  let signature = given
  let verdict: { ok: true } | { ok: false; reason: string }
  if (signature) {
    const used = await env.CAPS.get(`sentsig:${signature}`)
    if (used && used !== id) return fail(409, 'signature_used', 'That transaction already counted for another send.')
    // The status is cheap: only a confirmed transaction is worth reading in full.
    const statuses = await rpc('getSignatureStatuses', [[signature], { searchTransactionHistory: true }])
    const status = statuses?.value?.[0]
    const landed = status && (status.confirmationStatus === 'confirmed' || status.confirmationStatus === 'finalized')
    if (!landed) {
      verdict = { ok: false, reason: 'not_confirmed' }
    } else if (status.err) {
      verdict = { ok: false, reason: 'failed_on_chain' }
    } else {
      const tx = await rpc('getTransaction', [
        signature,
        { encoding: 'jsonParsed', commitment: 'confirmed', maxSupportedTransactionVersion: 0 },
      ])
      verdict = checkSend(tx, sent)
    }
  } else {
    signature = await findLandedSend(env, rpc, sent, id)
    verdict = signature ? { ok: true } : { ok: false, reason: 'not_confirmed' }
  }
  // Not on chain, and its blockhash has run out: it was never submitted and never will be.
  if (!verdict.ok && verdict.reason === 'not_confirmed' && (await expired(env, rpc, id))) {
    verdict = { ok: false, reason: 'expired' }
  }
  const logged = {
    route: 'send/confirm', device: who.device, wallet: who.wallet.slice(0, 4), to: sent.to_address.slice(0, 4),
    token: sent.token, amount: sent.amount, looked_up: given === null,
  }
  if (!verdict.ok) {
    log({ ...logged, confirmed: false, reason: verdict.reason })
    if (verdict.reason === 'not_confirmed') return json(409, { reason: 'not_confirmed' })
    if (verdict.reason === 'expired') return json(410, { reason: 'expired' })
    return json(402, { reason: verdict.reason })
  }
  await env.CAPS.put(`sent:${id}`, signature!, { expirationTtl: SEND_TTL_SECONDS })
  await env.CAPS.put(`sentsig:${signature}`, id, { expirationTtl: SEND_TTL_SECONDS })
  // It landed, so this address is one the wallet has sent to now, and the programs it
  // called are ones it has used. Hashes only, and only while memory is on.
  await rememberSend(
    env, who.wallet, sent.to_address,
    sent.mint ? [sent.token_program ?? TOKEN_PROGRAM, ASSOCIATED_TOKEN_PROGRAM] : [SYSTEM_PROGRAM],
  )
  count({ sendsConfirmed: 1 })
  log({ ...logged, confirmed: true })
  return json(200, { confirmed: true, signature: short(signature!), full_signature: signature! })
}

const SIGNATURE = /^[1-9A-HJ-NP-Za-km-z]{64,88}$/

/** Whether a transaction lists [address] among its accounts. */
function carries(tx: any, address: string): boolean {
  const keys: unknown[] = tx?.transaction?.message?.accountKeys ?? []
  return keys.some((k: any) => (typeof k === 'string' ? k : k?.pubkey) === address)
}
const LANDED_LOOKBACK = 10
const LANDED_SLACK_MS = 120_000

/**
 * The wallet ended without a signature, so look for the send among the sender's
 * latest transactions: newer than when it was prepared, succeeded, doing exactly
 * what was prepared, and not already counted for another send.
 */
async function findLandedSend(env: Env, rpc: Rpc, sent: PreparedSend, id: string): Promise<string | null> {
  // A send built here carries its id as a read-only reference: that finds it directly.
  const byReference = await rpc('getSignaturesForAddress', [id, { limit: 5 }]).catch(() => [])
  const newerThan = (sent.prepared_at ?? 0) - LANDED_SLACK_MS
  for (const entry of Array.isArray(byReference) ? byReference : []) {
    const signature = String(entry?.signature ?? '')
    if (entry?.err || !SIGNATURE.test(signature)) continue
    if (entry?.blockTime && entry.blockTime * 1000 < newerThan) continue
    const used = await env.CAPS.get(`sentsig:${signature}`)
    if (used && used !== id) continue
    const tx = await rpc('getTransaction', [
      signature,
      { encoding: 'jsonParsed', commitment: 'confirmed', maxSupportedTransactionVersion: 0 },
    ])
    if (carries(tx, id) && checkSend(tx, sent).ok) return signature
  }
  // The sender's wallet, and for a token its token account for that mint: a
  // transfer shows up on both, but an RPC may index one before the other.
  const addresses = [sent.from]
  if (sent.mint) {
    const accounts = await rpc('getTokenAccountsByOwner', [sent.from, { mint: sent.mint }, { encoding: 'jsonParsed' }]).catch(() => null)
    for (const account of accounts?.value ?? []) if (isAddress(account?.pubkey)) addresses.push(account.pubkey)
  }
  const lists = await Promise.all(
    addresses.map((address) => rpc('getSignaturesForAddress', [address, { limit: LANDED_LOOKBACK }]).catch(() => [])),
  )
  const seen = new Set<string>()
  const recent = lists
    .flatMap((list: unknown) => (Array.isArray(list) ? list : []))
    .filter((entry: any) => entry?.signature && !seen.has(entry.signature) && Boolean(seen.add(entry.signature)))
    .sort((a: any, b: any) => (b?.blockTime ?? 0) - (a?.blockTime ?? 0))
  const since = (sent.prepared_at ?? 0) - LANDED_SLACK_MS
  for (const entry of Array.isArray(recent) ? recent : []) {
    if (entry?.err) continue
    if (entry?.blockTime && entry.blockTime * 1000 < since) break
    const signature = String(entry?.signature ?? '')
    if (!SIGNATURE.test(signature)) continue
    const used = await env.CAPS.get(`sentsig:${signature}`)
    if (used && used !== id) continue
    const tx = await rpc('getTransaction', [
      signature,
      { encoding: 'jsonParsed', commitment: 'confirmed', maxSupportedTransactionVersion: 0 },
    ])
    if (checkSend(tx, sent).ok) return signature
  }
  return null
}

/** What the tools may use: the cluster's RPC, prices, the mints, and whose wallet. */
/** The knowledge base, when both its bindings are there. */
function kbOf(env: Env): Kb | undefined {
  return env.AI && env.KB ? { ai: env.AI, index: env.KB } : undefined
}

/**
 * /kb/ingest (a batch of chunks to embed and store) and /kb/search (what a query finds,
 * with no model involved), for scripts/kb/build.sh. Both need KB_ADMIN_SECRET in
 * X-Heylana-KB-Admin; without the secret set, or with a wrong one, the route does not exist.
 */
async function kbAdmin(route: string, request: Request, env: Env): Promise<Response> {
  const given = request.headers.get('X-Heylana-KB-Admin') ?? ''
  if (!env.KB_ADMIN_SECRET || !sameText(given, env.KB_ADMIN_SECRET)) return fail(404, 'unknown_route', 'No such route.')
  if (request.method !== 'POST') return fail(405, 'method', 'POST to this.')
  const kb = kbOf(env)
  if (!kb) return fail(503, 'not_configured', 'The knowledge base is not bound.')
  const body = await readJson(request)
  if (route === 'kb/ingest') {
    const result = await ingest(kb, body.chunks)
    log({ route, ...('error' in result ? { error: result.error } : { upserted: result.upserted }) })
    return 'error' in result ? fail(400, result.error, 'Bad batch.') : json(200, result)
  }
  const query = typeof body.query === 'string' ? body.query.trim().slice(0, 200) : ''
  if (!query) return fail(400, 'no_query', 'query is required.')
  const results = await searchKb(kb, query, Number(body.k ?? 3))
  log({ route, kb_hits: results.length })
  return json(200, { results })
}

function toolContext(env: Env, who: Who, rpc: Rpc) {
  return {
    kb: kbOf(env),
    rpc,
    jupiterKey: env.JUPITER_API_KEY,
    usdcMint: env.USDC_MINT,
    skrMint: env.SKR_MINT,
    cluster: clusterOf(env),
    wallet: who.wallet,
    treasury: env.TREASURY_ADDRESS,
    now: clock.now,
  }
}

/** The payment carrying [reference] as an account: the Solana Pay way of finding one without its signature. */
async function paymentByReference(env: Env, rpc: Rpc, reference: string, quote: Quote): Promise<string | null> {
  const found = await rpc('getSignaturesForAddress', [reference, { limit: 5 }])
  for (const entry of Array.isArray(found) ? found : []) {
    if (entry?.err) continue
    const signature = String(entry?.signature ?? '')
    if (!/^[1-9A-HJ-NP-Za-km-z]{64,90}$/.test(signature)) continue
    const tx = await rpc('getTransaction', [
      signature,
      { encoding: 'jsonParsed', commitment: 'confirmed', maxSupportedTransactionVersion: 0 },
    ])
    if (checkPayment(tx, quote).ok) return signature
  }
  return null
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
  const at = talksKey(key, now)
  const stored = await safely('talks_read', log, async () => Number((await env.CAPS.get(at)) ?? '0'), 0)
  return stored + tally.pending(at, '')
}

/**
 * A talk spent. The account is written only when spending it changed the account (a welcome
 * talk used up), which is at most twenty times a wallet; the month's count joins the tally.
 */
async function recordTalk(env: Env, key: string, before: Account, spent: { account: Account }, now: Date): Promise<void> {
  if (spent.account !== before) await safely('account', log, () => saveAccount(env, key, spent.account), undefined)
  tally.add(talksKey(key, now), '', 1, TALKS_TTL_SECONDS)
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

/** One record per device a day, `{route: count}`: one write per device per flush, not one per route. */
export function capKey(device: string): string {
  return `cap:${today()}:${device}`
}

/**
 * Counts one request against today's allowance. True when it is over.
 *
 * The count is what KV holds plus what this isolate has not written yet, and the request is
 * added to the pending tally rather than written: see tally.ts. A cap that cannot be read is
 * treated as not reached — budget protection must never be the thing that takes Heylana down.
 */
export async function chargeOne(env: Env, device: string, route: string, cap: number = DAILY_CAPS[route]): Promise<boolean> {
  const key = capKey(device)
  const stored = await safely('cap_read', log, async () => {
    const raw = await env.CAPS.get(key)
    const record = raw ? JSON.parse(raw) : {}
    return Number(record?.[route] ?? 0)
  }, 0)
  if (stored + tally.pending(key, route) >= cap) return true
  tally.add(key, route, 1, CAP_KEY_TTL_SECONDS)
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

/** Why the model stopped, off a reply body: end_turn, tool_use, max_tokens… or none. */
function stopOf(text: string): string {
  try {
    const reason = JSON.parse(text)?.stop_reason
    return typeof reason === 'string' ? reason : 'none'
  } catch {
    return 'none'
  }
}

/** The reply's usage carries the output limit it had, so the phone can say how close it came. */
function withLimit(text: string, limit: number): string {
  try {
    const parsed = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object' || !parsed.usage || typeof parsed.usage !== 'object') return text
    parsed.usage.max_tokens = limit
    return JSON.stringify(parsed)
  } catch {
    return text
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
export function scrub(text: string, env: Env, extra: (string | null | undefined)[] = []): string {
  const secrets = [
    env.ANTHROPIC_API_KEY,
    env.CARTESIA_API_KEY,
    env.GEMINI_API_KEY,
    env.DEEPGRAM_API_KEY,
    env.SESSION_SECRET,
    env.JUDGE_CODE,
    env.RPC_URL,
    env.RPCFAST_URL,
    env.JUPITER_API_KEY,
    env.MAINNET_RPC_URL,
    env.SENTRY_DSN,
    ...extra,
  ]
  let safe = text
  for (const secret of secrets) {
    if (secret && secret.length > 6) safe = safe.split(secret).join('***')
  }
  // Any Anthropic key at all, whoever's: an error that quotes one never carries it out.
  return safe.replace(ANTHROPIC_KEY_SHAPE, 'sk-ant-***')
}

const ANTHROPIC_KEY_SHAPE = /sk-ant-[A-Za-z0-9_-]{6,}/g

/** The header "Use my own key" sends. */
export const OWN_KEY_HEADER = 'X-Heylana-Key'

/**
 * The user's own Anthropic key from [OWN_KEY_HEADER], null when there is none, or
 * 'malformed'. Used for one request's model calls; never stored, never logged.
 */
function ownKeyOf(request: Request): string | null | 'malformed' {
  const given = request.headers.get(OWN_KEY_HEADER)
  if (given === null) return null
  const key = given.trim()
  return /^sk-ant-[A-Za-z0-9_-]{20,200}$/.test(key) ? key : 'malformed'
}

function fail(status: number, reason: string, detail: string): Response {
  return new Response(JSON.stringify({ reason, detail }), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

/** A request's chain calls for its log line: method, provider and milliseconds, never an address. */
function rpcSummary(rpc: Rpc): { method: string; provider: string; ms: number }[] {
  return rpc.calls.map((call) => ({ method: call.method, provider: call.provider, ms: call.ms }))
}

const weekKey = (wallet: string, start: string) => `${WEEK_PREFIX}${wallet}:${start}`

/**
 * This week's counts for [wallet], written once per request and only while memory is on —
 * the card is part of memory, and follows its switch.
 */
async function recordWeek(env: Env, wallet: string, patch: WeekPatch): Promise<void> {
  const start = weekStart(clock.now())
  tally.update(
    weekKey(wallet, start),
    WEEK_TTL_SECONDS,
    (raw) => (raw ? { ...emptyWeek(start), ...JSON.parse(raw) } : emptyWeek(start)),
    (week: Week) => applyWeek(week, patch),
    // Asked at the flush, so memory switched off in the meantime keeps nothing.
    async () => (await loadMemory(env, wallet)).on,
  )
}

/** An address as a salted hash: enough to tell a first look from a second, never enough to name it. */
async function hashSeen(address: string, secret: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${secret}:seen:${address}`))
  return [...new Uint8Array(digest).slice(0, 6)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * What Heylana caught this week, for the card on Home: counts and the words for them, and
 * nothing else. Needs a wallet; empty while memory is off, because the card is memory's.
 */
async function week(env: Env, who: Who): Promise<Response> {
  if (!who.wallet) return fail(401, 'session_required', 'Connect a wallet first.')
  const kept = await loadMemory(env, who.wallet)
  const start = weekStart(clock.now())
  if (!kept.on) {
    log({ route: 'week', device: who.device, wallet: who.wallet.slice(0, 8), memory: false })
    return json(200, { week_start: start, memory_on: false, counts: emptyWeek(start).counts, line: '', items: [] })
  }
  const stored = await env.CAPS.get(weekKey(who.wallet, start))
  const found: Week = stored ? { ...emptyWeek(start), ...JSON.parse(stored) } : emptyWeek(start)
  log({ route: 'week', device: who.device, wallet: who.wallet.slice(0, 8), memory: true, caught: anythingCaught(found.counts) })
  // The hashes stay here: the phone gets numbers and the words for them.
  return json(200, {
    week_start: start,
    memory_on: true,
    counts: found.counts,
    line: cardLine(found.counts),
    items: breakdown(found.counts),
  })
}

/**
 * The day's counters, read and written once per request. Last write wins, so two requests
 * landing together can lose an increment — near enough to watch a budget by, as the talk
 * counts are.
 */
async function recordUsage(env: Env, patch: UsagePatch): Promise<void> {
  const date = dayOf(clock.now())
  const hashed = patch.wallet ? await hashWallet(patch.wallet, env.SESSION_SECRET) : null
  const minute = Math.floor(clock.now() / 60_000)
  tally.update(
    USAGE_PREFIX + date,
    USAGE_TTL_SECONDS,
    (raw) => (raw ? { ...emptyDay(date), ...JSON.parse(raw) } : emptyDay(date)),
    (day: DayUsage) => applyUsage(day, patch, hashed, minute),
  )
}

/** A wallet as a short salted hash: enough to count how many were active, never enough to name one. */
async function hashWallet(wallet: string, secret: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${secret}:${wallet}`))
  return [...new Uint8Array(digest).slice(0, 8)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * What Heylana has been costing: the day's counters and an estimate from the price sheet,
 * with the median and p95 of each RPC provider's methods over the last 24 hours, so RPC Fast
 * and Helius can be compared from where the phone is. Behind ADMIN_SECRET
 * in X-Heylana-Admin; without the secret set the route does not exist. Counts only: no
 * wallet, no device, no words.
 */
async function adminUsage(request: Request, env: Env): Promise<Response> {
  const given = request.headers.get('X-Heylana-Admin') ?? ''
  if (!env.ADMIN_SECRET || !sameText(given, env.ADMIN_SECRET)) return fail(404, 'unknown_route', 'No such route.')
  if (request.method !== 'GET') return fail(405, 'method', 'GET to this.')
  const asked = Number(new URL(request.url).searchParams.get('days') ?? 30)
  const days = Math.min(90, Math.max(1, Number.isFinite(asked) ? Math.floor(asked) : 30))
  const dates = daysBack(clock.now(), days)
  const stored = await Promise.all(dates.map((date) => env.CAPS.get(USAGE_PREFIX + date)))
  const found: DayUsage[] = stored.flatMap((raw, i) => {
    if (!raw) return []
    try {
      return [{ ...emptyDay(dates[i]), ...JSON.parse(raw) }]
    } catch {
      return []
    }
  })
  const report = summarise(found, priceSheet(env.PRICES), clock.now())
  log({ route: 'admin/usage', days, found: found.length })
  return json(200, report)
}

/** One line per request. Names and numbers only — never content. */
/**
 * The request being served, so its line can carry what its chain calls cost. Set for each
 * request and cleared after it; a Worker handles one request per invocation.
 */
let current: { rpc: Rpc; logged: boolean; usage: UsagePatch; week: WeekPatch } | null = null

/** Adds what this request caught to the week's counts. Counts only: never what it was about. */
function caught(patch: WeekPatch): void {
  if (!current) return
  const into = current.week
  for (const [key, value] of Object.entries(patch)) {
    if (key === 'seen') into.seen = [...(into.seen ?? []), ...(value as string[])]
    else (into as any)[key] = ((into as any)[key] ?? 0) + (value as number)
  }
}

/** Adds what this request did to what will be counted for the day. */
function count(patch: UsagePatch): void {
  if (!current) return
  const into = current.usage
  for (const [key, value] of Object.entries(patch)) {
    if (key === 'rpc') continue
    if (typeof value === 'number') (into as any)[key] = ((into as any)[key] ?? 0) + value
    else (into as any)[key] = value
  }
}

function log(fields: Record<string, unknown>): void {
  const shown: Record<string, unknown> = { ...fields, device: String(fields.device ?? '').slice(0, 8) }
  // Every line about the request itself carries the chain calls it made. The per-call
  // lines (route "rpc") are what those calls were, and never carry themselves.
  if (fields.route !== 'rpc' && current && current.rpc.calls.length > 0) {
    shown.rpc = rpcSummary(current.rpc)
    shown.rpc_ms = current.rpc.totalMs
    current.logged = true
  }
  console.log(JSON.stringify(shown))
}
