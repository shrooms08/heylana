/**
 * Heylana's voice: which provider speaks, in which voice, and how Gemini's streamed
 * speech becomes the raw 16-bit audio the phone plays as it arrives.
 *
 * Gemini TTS (the Interactions API, `stream: true`) sends Server-Sent Events whose
 * `step.delta` events carry base64 audio: PCM, 24 kHz, mono, 16-bit little-endian.
 * Only those deltas are passed on, decoded, in order — never the closing
 * `interaction.completed` event, which repeats the whole audio. Cartesia stays as an
 * option behind VOICE_PROVIDER.
 */

export type Provider = 'gemini' | 'cartesia' | 'deepgram'

/** "deepgram" or "cartesia" when VOICE_PROVIDER says so; otherwise "gemini". */
export function providerOf(setting: string | undefined): Provider {
  const wanted = String(setting ?? '').trim().toLowerCase()
  if (wanted === 'deepgram') return 'deepgram'
  if (wanted === 'cartesia') return 'cartesia'
  return 'gemini'
}

// ------------------------------------------------------------- Deepgram Aura

/** Deepgram's REST text-to-speech: the audio streams back as it is made. */
export const DEEPGRAM_SPEAK_URL = 'https://api.deepgram.com/v1/speak'

/**
 * The picker's two slots, as Aura-2 English voices. Deepgram describes Callista (American,
 * feminine) as "Clear, Energetic, Professional, Smooth" and Aries (American, masculine) as
 * "Warm, Energetic, Caring". Callista was picked by ear against Harmonia, Vesta and Helena:
 * there is no Deepgram voice called Sienna, which is what was asked for first.
 *
 * The name below matters as much as the model: the phone keys its kept audio on the slot
 * **and** this name, so changing one without the other would leave every warning it has
 * already fetched playing in the old voice.
 */
export const DEEPGRAM_VOICES = { skylar: 'aura-2-callista-en', archie: 'aura-2-aries-en' } as const

export function deepgramVoiceFor(slot: unknown): string {
  return slot === 'archie' ? DEEPGRAM_VOICES.archie : DEEPGRAM_VOICES.skylar
}

/** The same 16-bit, 24 kHz, single-channel raw audio every provider hands the phone. */
export function deepgramSpeakUrl(model: string): string {
  const query = new URLSearchParams({ model, encoding: 'linear16', sample_rate: '24000', container: 'none' })
  return `${DEEPGRAM_SPEAK_URL}?${query}`
}

/** What each slot is called, per provider — what the picker shows, sent on /me. */
export const VOICE_NAMES: Record<Provider, { skylar: string; archie: string }> = {
  deepgram: { skylar: 'Callista', archie: 'Aries' },
  gemini: { skylar: 'Sulafat', archie: 'Achird' },
  cartesia: { skylar: 'Skylar', archie: 'Archie' },
}

/** The voice as /me tells the phone: which provider speaks, and the two names. */
export function voiceInfo(setting: string | undefined): { provider: Provider; skylar: string; archie: string } {
  const provider = providerOf(setting)
  return { provider, ...VOICE_NAMES[provider] }
}

/**
 * Raw PCM passed straight through, except that a WAV header, if one ever comes first
 * despite `container=none`, is taken off — so a header is never played as a click. The
 * first bytes are held only until it is clear whether they are one.
 */
export function rawPcmStream(body: ReadableStream<Uint8Array>): ReadableStream<Uint8Array> {
  let head: Uint8Array | null = new Uint8Array(0)
  const HEAD_BYTES = 64
  return body.pipeThrough(new TransformStream<Uint8Array, Uint8Array>({
    transform(chunk, out) {
      if (head === null) {
        out.enqueue(chunk)
        return
      }
      const joined = new Uint8Array(head.length + chunk.length)
      joined.set(head)
      joined.set(chunk, head.length)
      const looksLikeWav = joined.length >= 4 && String.fromCharCode(joined[0], joined[1], joined[2], joined[3]) === 'RIFF'
      if (looksLikeWav && joined.length < HEAD_BYTES) {
        head = joined
        return
      }
      head = null
      const offset = looksLikeWav ? wavDataOffset(joined) : 0
      out.enqueue(offset > 0 ? joined.subarray(offset) : joined)
    },
    flush(out) {
      if (head && head.length > 0) {
        const offset = wavDataOffset(head)
        out.enqueue(offset > 0 ? head.subarray(offset) : head)
      }
    },
  }))
}

export const GEMINI_TTS_URL = 'https://generativelanguage.googleapis.com/v1beta/interactions'
/** Gemini 3.1 Flash TTS: the first Gemini TTS model that streams. */
export const GEMINI_TTS_MODEL = 'gemini-3.1-flash-tts-preview'
/** The revision the streaming example in Google's speech-generation guide is written against. */
export const GEMINI_API_REVISION = '2026-05-20'

/**
 * The picker's two slots, as Gemini prebuilt voices. Sulafat is the voice Google
 * describes as warm (female); Achird, friendly (male) — no male voice is described as
 * warm, and friendly is the nearest.
 */
export const GEMINI_VOICES = { skylar: 'Sulafat', archie: 'Achird' } as const

export function geminiVoiceFor(slot: unknown): string {
  return slot === 'archie' ? GEMINI_VOICES.archie : GEMINI_VOICES.skylar
}

/** Plain text only: a style prompt in front ("Say warmly:") risks being read out. */
export function geminiRequest(text: string, voice: string, model: string = GEMINI_TTS_MODEL): Record<string, unknown> {
  return {
    model,
    input: text,
    response_format: { type: 'audio' },
    generation_config: { speech_config: [{ voice }] },
    stream: true,
  }
}

export interface StreamStats {
  events: number
  audioEvents: number
  bytes: number
  error: string | null
}

/** Base64 to bytes, with the Web API both Workers and Node have. */
export function base64Bytes(data: string): Uint8Array {
  const binary = atob(data)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}

/** A WAV container's header, if a chunk starts with one: the offset where its samples start. */
export function wavDataOffset(bytes: Uint8Array): number {
  const tag = (at: number) => String.fromCharCode(bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3])
  if (bytes.length < 12 || tag(0) !== 'RIFF' || tag(8) !== 'WAVE') return 0
  let at = 12
  while (at + 8 <= bytes.length) {
    const size = bytes[at + 4] | (bytes[at + 5] << 8) | (bytes[at + 6] << 16) | (bytes[at + 7] << 24)
    if (tag(at) === 'data') return at + 8
    at += 8 + size
  }
  return 0
}

/** The audio in one SSE event's JSON, or null. Only deltas count. */
export function audioOfEvent(event: any): Uint8Array | null {
  if (!event || typeof event !== 'object') return null
  if (event.event_type && event.event_type !== 'step.delta') return null
  const delta = event.delta
  if (!delta || delta.type !== 'audio' || typeof delta.data !== 'string' || delta.data.length === 0) return null
  const bytes = base64Bytes(delta.data)
  const offset = wavDataOffset(bytes)
  return offset > 0 ? bytes.subarray(offset) : bytes
}

/**
 * Gemini's SSE body in, raw PCM out, chunk by chunk as events complete. Events may be
 * split anywhere across network chunks; a partial one waits for the rest.
 */
export function geminiPcmStream(
  body: ReadableStream<Uint8Array>,
  onDone: (stats: StreamStats) => void = () => {},
): ReadableStream<Uint8Array> {
  const decoder = new TextDecoder()
  const stats: StreamStats = { events: 0, audioEvents: 0, bytes: 0, error: null }
  let pending = ''

  const handle = (block: string, out: TransformStreamDefaultController<Uint8Array>) => {
    const data = block
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).replace(/^ /, ''))
      .join('\n')
    if (!data) return
    stats.events++
    let event: any
    try {
      event = JSON.parse(data)
    } catch {
      return
    }
    if (event?.event_type === 'error' || (event?.error && !event?.delta)) {
      const code = event?.error?.code ?? event?.code ?? 'unknown'
      stats.error = String(code)
      return
    }
    const audio = audioOfEvent(event)
    if (!audio || audio.length === 0) return
    stats.audioEvents++
    stats.bytes += audio.length
    out.enqueue(audio)
  }

  const events = new TransformStream<Uint8Array, Uint8Array>({
    transform(chunk, out) {
      pending += decoder.decode(chunk, { stream: true }).replace(/\r\n/g, '\n')
      let end = pending.indexOf('\n\n')
      while (end >= 0) {
        handle(pending.slice(0, end), out)
        pending = pending.slice(end + 2)
        end = pending.indexOf('\n\n')
      }
    },
    flush(out) {
      pending += decoder.decode()
      if (pending.trim()) handle(pending, out)
      onDone(stats)
    },
  })
  return body.pipeThrough(events)
}
