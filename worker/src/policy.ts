/**
 * What the model says is never an instruction, and what is on the screen is never the
 * user's. Two checks the worker makes on every reply, after the registry's:
 *
 *  - A send's recipient must be in the user's own words ([inUserWords]): an address
 *    exactly, a .skr or .sol name case-free ("dot" allowed). A recipient that is only
 *    on the screen — in a page, a token's name, a memo, a prompt someone planted — is
 *    never proposed, prepared or built.
 *  - An action rides only on the routes made for it (a send's forced proposal, a quick
 *    action's). Anywhere else — an ordinary answer, a signing explanation, a recap —
 *    any `action` in the reply is removed before it reaches the phone
 *    ([withoutActions]), wherever in the text the JSON sits, as the phone's own parser
 *    would find it.
 *
 * The app checks the same things first (SendGuard, QuickGuard); this is the second.
 */
import { isAddress } from './base58.ts'

/** Whether [recipient] is in [said], the words the user typed or spoke. */
export function inUserWords(recipient: unknown, said: unknown): boolean {
  if (typeof recipient !== 'string' || typeof said !== 'string') return false
  const wanted = recipient.trim()
  if (!wanted) return false
  if (isAddress(wanted)) return said.includes(wanted)
  const plain = (text: string) => text.toLowerCase().replace(/\s+dot\s+/g, '.').replace(/\s*\.\s*/g, '.')
  return plain(said).includes(plain(wanted))
}

/**
 * [body] (an Anthropic reply) with every `action` removed from every JSON object in its
 * text blocks. Returns the body and how many were removed.
 */
export function withoutActions(body: string): { body: string; removed: number } {
  let parsed: any
  try {
    parsed = JSON.parse(body)
  } catch {
    return stripText(body)
  }
  const content = parsed?.content
  if (!Array.isArray(content)) return { body, removed: 0 }
  let removed = 0
  for (const block of content) {
    if (block?.type !== 'text' || typeof block.text !== 'string') continue
    const stripped = stripText(block.text)
    if (stripped.removed > 0) {
      block.text = stripped.body
      removed += stripped.removed
    }
  }
  return { body: removed > 0 ? JSON.stringify(parsed) : body, removed }
}

/** Every top-level JSON object in [text] that carries `action`, rewritten without it. */
function stripText(text: string): { body: string; removed: number } {
  let out = ''
  let removed = 0
  let at = 0
  while (at < text.length) {
    const start = text.indexOf('{', at)
    if (start < 0) break
    const end = matchingBrace(text, start)
    if (end < 0) break
    const piece = text.slice(start, end + 1)
    let object: any = null
    try {
      object = JSON.parse(piece)
    } catch {
      object = null
    }
    out += text.slice(at, start)
    if (object && typeof object === 'object' && !Array.isArray(object) && 'action' in object) {
      const { action: _dropped, ...rest } = object
      out += JSON.stringify(rest)
      removed++
    } else {
      out += piece
    }
    at = end + 1
  }
  out += text.slice(at)
  return { body: removed > 0 ? out : text, removed }
}

/** The index of the brace closing the one at [start], minding strings and escapes; -1 if none. */
function matchingBrace(text: string, start: number): number {
  let depth = 0
  let inString = false
  for (let i = start; i < text.length; i++) {
    const c = text[i]
    if (inString) {
      if (c === '\\') i++
      else if (c === '"') inString = false
      continue
    }
    if (c === '"') inString = true
    else if (c === '{') depth++
    else if (c === '}') {
      depth--
      if (depth === 0) return i
    }
  }
  return -1
}
