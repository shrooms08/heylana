/**
 * Speaking while the answer is still being written.
 *
 * The old way was two trips: the phone asked `/chat`, waited for the whole answer, then
 * asked `/tts` and waited again for the first audio. Measured on the Seeker on Sept 20,
 * that was 1.8s of model and 0.7s of voice, one after the other, before a word was heard.
 *
 * This is the other way round. The model is asked to stream, [SayReader] pulls `say` out
 * of the JSON as it is written, and the moment a **sentence** is finished it goes to the
 * voice and its audio starts coming back — while the model is still writing the rest.
 * The phone gets one stream of [frame]s: audio as it is made, and the finished reply
 * itself as soon as the model has written it, so the strip and everything after it are
 * exactly as they were.
 *
 * Nothing here is about a particular model or provider: it is text in, sentences out.
 */

// ------------------------------------------------------------------ frames

/** Raw 16-bit audio, in order. */
export const FRAME_AUDIO = 1
/** The finished reply, as the app has always read it. */
export const FRAME_REPLY = 2
/** Nothing could be spoken, and [VoiceFailure]'s reason: the app shows the words instead. */
export const FRAME_VOICE_FAILED = 3

/** What the one-trip answer is served as. */
export const SAY_STREAM_TYPE = 'application/x-heylana-say'

export const FRAME_HEADER_BYTES = 5

/**
 * One frame: a kind, four bytes of length (big-endian), then the payload. Framed rather
 * than concatenated because audio and words share the one connection and the phone has
 * to know which it is holding.
 */
export function frame(kind: number, payload: Uint8Array): Uint8Array {
  const out = new Uint8Array(FRAME_HEADER_BYTES + payload.length)
  out[0] = kind
  out[1] = (payload.length >>> 24) & 0xff
  out[2] = (payload.length >>> 16) & 0xff
  out[3] = (payload.length >>> 8) & 0xff
  out[4] = payload.length & 0xff
  out.set(payload, FRAME_HEADER_BYTES)
  return out
}

export function textFrame(kind: number, text: string): Uint8Array {
  return frame(kind, new TextEncoder().encode(text))
}

// ------------------------------------------------------------------ sentences

/** A sentence ends here, if what follows is a space or the end of the answer. */
const ENDS = new Set(['.', '!', '?', '…'])

/**
 * The sentences in [text], and whatever is left over unfinished. A full stop only ends a
 * sentence when a space or the end of the text follows it, so "0.05 USDC" stays whole.
 */
export function sentencesIn(text: string): { sentences: string[]; rest: string } {
  const sentences: string[] = []
  let start = 0
  for (let i = 0; i < text.length; i++) {
    if (!ENDS.has(text[i])) continue
    // Run on through "?!" and "..." so one sentence does not become three.
    let end = i
    while (end + 1 < text.length && ENDS.has(text[end + 1])) end++
    // "..." is a pause in the middle of a thought, not the end of one: speaking it as
    // its own sentence puts a gap where the voice should carry on.
    const run = text.slice(i, end + 1)
    if (run.length > 1 && /^\.+$/.test(run)) {
      i = end
      continue
    }
    const next = text[end + 1]
    if (next !== undefined && next !== ' ' && next !== '\n') {
      i = end
      continue
    }
    const sentence = text.slice(start, end + 1).trim()
    if (sentence.length > 0) sentences.push(sentence)
    start = end + 1
    i = end
  }
  return { sentences, rest: text.slice(start) }
}

// ------------------------------------------------------------------ the reply, as it is written

/**
 * Pulls `say` out of a JSON reply as the model writes it.
 *
 * The contract puts `say` first, so its text is known long before the reply is finished.
 * Fed the deltas in order, [take] hands back every sentence that is now complete; [rest]
 * is whatever is written but unfinished, said at the end by [flush].
 *
 * A `say` that is not a plain string — the segmented form, `[{text, point_at}]` — is left
 * alone: [streamable] goes false and nothing is spoken early, so the phone falls back to
 * saying it when the whole answer is there.
 */
export class SayReader {
  /** False once the reply turns out not to be a plain `say` string. */
  streamable = true
  /** The text of `say` decoded so far. */
  private said = ''
  /** Written but not yet part of a finished sentence. */
  private pending = ''
  /** True once the closing quote of `say` has been read. */
  private closed = false

  private raw = ''
  private inside = false
  private escape = ''
  private searched = 0

  /** [delta] as the model wrote it; the sentences it finished. */
  take(delta: string): string[] {
    if (!this.streamable || this.closed) {
      this.raw += delta
      return []
    }
    this.raw += delta
    if (!this.inside) this.findStart()
    if (!this.inside) return []
    this.readString()
    const { sentences, rest } = sentencesIn(this.pending)
    this.pending = rest
    return sentences
  }

  /** Whatever was left when the answer ended: the last sentence, usually. */
  flush(): string {
    const rest = this.pending.trim()
    this.pending = ''
    return rest
  }

  /** Everything `say` has said so far, finished or not. */
  get text(): string {
    return this.said
  }

  /** The whole reply as written, for parsing once it is complete. */
  get body(): string {
    return this.raw
  }

  /** Finds `"say":` and the opening quote of its value. */
  private findStart() {
    const at = this.raw.indexOf('"say"', this.searched)
    if (at < 0) {
      // Keep a few characters back in case the key itself is split across deltas.
      this.searched = Math.max(0, this.raw.length - 5)
      return
    }
    let i = at + 5
    while (i < this.raw.length && (this.raw[i] === ' ' || this.raw[i] === ':' || this.raw[i] === '\n')) i++
    if (i >= this.raw.length) return
    if (this.raw[i] !== '"') {
      // An array of segments, or something else: nothing to speak early.
      this.streamable = false
      return
    }
    this.inside = true
    this.searched = i + 1
  }

  /** Decodes the string's characters, escape by escape, stopping at its closing quote. */
  private readString() {
    while (this.searched < this.raw.length) {
      const char = this.raw[this.searched]
      this.searched++
      if (this.escape) {
        this.escape += char
        const done = decodeEscape(this.escape)
        if (done === null) continue // \u needs its four digits
        this.escape = ''
        this.said += done
        this.pending += done
        continue
      }
      if (char === '\\') {
        this.escape = '\\'
        continue
      }
      if (char === '"') {
        this.closed = true
        return
      }
      this.said += char
      this.pending += char
    }
  }
}

/** One JSON escape, or null while it is still incomplete. */
function decodeEscape(escape: string): string | null {
  const simple: Record<string, string> = {
    '\\"': '"', '\\\\': '\\', '\\/': '/', '\\b': '\b',
    '\\f': '\f', '\\n': '\n', '\\r': '\r', '\\t': '\t',
  }
  if (escape in simple) return simple[escape]
  if (escape.startsWith('\\u')) {
    if (escape.length < 6) return null
    const code = Number.parseInt(escape.slice(2, 6), 16)
    return Number.isNaN(code) ? '' : String.fromCharCode(code)
  }
  // Not an escape the JSON grammar has: keep the character itself.
  return escape.slice(1)
}

// ------------------------------------------------------------------ the model, as it streams

export interface StreamedUsage {
  input: number
  output: number
  cacheRead: number
  cacheWrite: number
}

/**
 * The text deltas of one Anthropic stream, and its usage. [onDelta] is called with each
 * piece as it lands; nothing is buffered here beyond one line.
 */
export async function readModelStream(
  body: ReadableStream<Uint8Array>,
  onDelta: (text: string) => void | Promise<void>,
): Promise<{ usage: StreamedUsage; error: string | null }> {
  const usage: StreamedUsage = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
  let error: string | null = null
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let cut: number
    while ((cut = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, cut).trim()
      buffer = buffer.slice(cut + 1)
      if (!line.startsWith('data:')) continue
      const event = parseEvent(line.slice(5).trim())
      if (!event) continue
      if (event.type === 'message_start') {
        const from = event.message?.usage ?? {}
        usage.input += Number(from.input_tokens ?? 0)
        usage.cacheRead += Number(from.cache_read_input_tokens ?? 0)
        usage.cacheWrite += Number(from.cache_creation_input_tokens ?? 0)
      } else if (event.type === 'content_block_delta' && event.delta?.type === 'text_delta') {
        await onDelta(String(event.delta.text ?? ''))
      } else if (event.type === 'message_delta') {
        usage.output += Number(event.usage?.output_tokens ?? 0)
      } else if (event.type === 'error') {
        error = String(event.error?.type ?? 'stream_error')
      }
    }
  }
  return { usage, error }
}

function parseEvent(data: string): any {
  if (!data || data === '[DONE]') return null
  try {
    return JSON.parse(data)
  } catch {
    return null
  }
}

/**
 * The finished reply in the shape the app has always been given, built from the streamed
 * text and usage — so everything downstream (the say check, the sources, the phone's own
 * parser) works on exactly what it worked on before.
 */
export function replyBody(text: string, usage: StreamedUsage, model: string): string {
  return JSON.stringify({
    id: 'msg_stream',
    type: 'message',
    role: 'assistant',
    model,
    content: [{ type: 'text', text }],
    stop_reason: 'end_turn',
    usage: {
      input_tokens: usage.input,
      output_tokens: usage.output,
      cache_read_input_tokens: usage.cacheRead,
      cache_creation_input_tokens: usage.cacheWrite,
    },
  })
}
