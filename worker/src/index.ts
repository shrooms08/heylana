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

/** The smallest slice of Workers KV this needs; keeps the types dependency-free. */
export interface CapStore {
  get(key: string): Promise<string | null>
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>
}

export interface Env {
  /** Secrets. Never in the repo, never in wrangler.toml, never in a response. */
  ANTHROPIC_API_KEY: string
  CARTESIA_API_KEY: string
  DEEPGRAM_API_KEY: string

  /** Plain configuration. */
  DEEPGRAM_PROJECT_ID: string
  VOICE_SKYLAR: string
  VOICE_ARCHIE: string

  CAPS: CapStore
}

/** The app names what kind of work it is; the worker names the model. */
const MODELS: Record<string, string> = {
  quick: 'claude-haiku-4-5-20251001',
  task: 'claude-sonnet-5',
}

/** What one device may spend in a day. Budget protection, not a product tier. */
const DAILY_CAPS: Record<string, number> = {
  chat: 150,
  tts: 150,
  'stt-token': 300,
}

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

    if (request.method !== 'POST') {
      return fail(405, 'method', 'Post to this.')
    }
    if (!(route in DAILY_CAPS)) {
      return fail(404, 'unknown_route', 'No such route.')
    }

    const device = deviceOf(request)
    if (!device) {
      return fail(400, 'no_device', 'Missing or malformed X-Heylana-Device.')
    }

    const overCap = await chargeOne(env, device, route)
    if (overCap) {
      return fail(429, 'daily_cap', 'That is all for today.')
    }

    const started = Date.now()
    try {
      if (route === 'chat') return await chat(request, env, device, started)
      if (route === 'tts') return await speak(request, env, device, started)
      return await sttToken(env, device, started)
    } catch (error) {
      // Whatever went wrong, the reply is a shape the app understands and
      // carries nothing that could have come from a secret.
      log({ route, device, ms: Date.now() - started, error: 'unhandled' })
      return fail(502, 'upstream', scrub(String(error), env))
    }
  },
}

// ------------------------------------------------------------------- routes

/** A question. The app says what kind of work it is; we choose the model. */
async function chat(request: Request, env: Env, device: string, started: number): Promise<Response> {
  const body = await readJson(request)
  const model = MODELS[String(body.mode)]
  if (!model) return fail(400, 'bad_mode', 'mode must be quick or task.')
  if (!Array.isArray(body.messages) || body.messages.length === 0) {
    return fail(400, 'bad_messages', 'messages must be a non-empty array.')
  }

  const upstream = await fetch(ANTHROPIC_URL, {
    method: 'POST',
    headers: {
      'x-api-key': env.ANTHROPIC_API_KEY,
      'anthropic-version': ANTHROPIC_VERSION,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model,
      max_tokens: clampTokens(body.max_tokens),
      system: typeof body.system === 'string' ? body.system : undefined,
      messages: body.messages,
    }),
  })

  const text = await upstream.text()
  const usage = usageOf(text)
  log({
    route: 'chat',
    device,
    ms: Date.now() - started,
    model,
    status: upstream.status,
    tokens_in: usage.input,
    tokens_out: usage.output,
  })

  return new Response(scrub(text, env), {
    status: upstream.status,
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
    ms: Date.now() - started,
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
  log({ route: 'stt-token', device, ms: Date.now() - started, status: upstream.status })

  if (!upstream.ok) return fail(502, 'upstream', scrub(text, env))

  const minted = JSON.parse(text)
  return new Response(
    JSON.stringify({ key: minted.key, expires_in: STT_KEY_TTL_SECONDS }),
    { status: 200, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } },
  )
}

// -------------------------------------------------------------------- parts

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
  return new Date().toISOString().slice(0, 10)
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
  const secrets = [env.ANTHROPIC_API_KEY, env.CARTESIA_API_KEY, env.DEEPGRAM_API_KEY]
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
