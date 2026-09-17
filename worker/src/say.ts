/**
 * The reply's `say`, checked before it leaves the worker.
 *
 * `say` is either a string (as it always was) or up to [MAX_SEGMENTS] pieces —
 * `[{text, point_at}]` — which the phone speaks one after another, flying the disc to
 * each named element as its sentence is spoken. A malformed piece is dropped rather
 * than sent on; a `say` that ends up with nothing left is an empty string, which the
 * phone treats as unreadable and asks for once more.
 */

export const MAX_SEGMENTS = 4

export interface Segment {
  text: string
  point_at: number | null
}

/** The pieces that survive checking, or null when `say` is not an array. */
export function validSegments(say: unknown): Segment[] | null {
  if (!Array.isArray(say)) return null
  const out: Segment[] = []
  for (const piece of say) {
    if (out.length >= MAX_SEGMENTS) break
    if (!piece || typeof piece !== 'object') continue
    const text = (piece as any).text
    if (typeof text !== 'string' || text.trim().length === 0) continue
    const raw = (piece as any).point_at
    const id = typeof raw === 'number' && Number.isInteger(raw) && raw >= 0 ? raw : null
    out.push({ text: text.trim(), point_at: id })
  }
  return out
}

/** One reply object, checked. Returns the object to send on, and how many pieces it says. */
export function checkedReply(reply: Record<string, unknown>): { reply: Record<string, unknown>; segments: number } {
  const segments = validSegments(reply.say)
  if (segments === null) return { reply, segments: typeof reply.say === 'string' ? 1 : 0 }
  if (segments.length === 0) return { reply: { ...reply, say: '' }, segments: 0 }
  // One piece needs no walking: it is the shape every other answer has.
  if (segments.length === 1 && segments[0].point_at === null) {
    return { reply: { ...reply, say: segments[0].text }, segments: 1 }
  }
  return { reply: { ...reply, say: segments }, segments: segments.length }
}

/**
 * An upstream body, with the reply in its first text block checked. Anything that is
 * not one of our replies — a tool-use body, prose, half a sentence — is left exactly
 * as it came, since the phone's own parser is what decides about those.
 */
export function checkedBody(body: string): { body: string; segments: number } {
  let parsed: any
  try {
    parsed = JSON.parse(body)
  } catch {
    return { body, segments: 0 }
  }
  const content = parsed?.content
  if (!Array.isArray(content)) return { body, segments: 0 }
  let segments = 0
  let changed = false
  for (const block of content) {
    if (block?.type !== 'text' || typeof block.text !== 'string') continue
    const text = block.text.trim()
    if (!text.startsWith('{')) continue
    let reply: any
    try {
      reply = JSON.parse(text)
    } catch {
      continue
    }
    if (!reply || typeof reply !== 'object' || !('say' in reply)) continue
    const checked = checkedReply(reply)
    segments = checked.segments
    const rewritten = JSON.stringify(checked.reply)
    if (rewritten !== text) {
      block.text = rewritten
      changed = true
    }
    break
  }
  return { body: changed ? JSON.stringify(parsed) : body, segments }
}
