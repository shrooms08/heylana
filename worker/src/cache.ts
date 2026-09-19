/**
 * Prompt caching. Every request to the model marks two places as cacheable: the end of the
 * tool definitions and the end of the system prompt (tools come first in the cached prefix,
 * then the system prompt, then the messages). The same system prompt and tools within five
 * minutes are then read from Anthropic's cache instead of processed again.
 *
 * Caching only happens above each model's minimum prompt length; a shorter prefix is simply
 * not cached, with no error. The log says what happened: `cache_read` and `cache_write`
 * (tokens), summed over every round of a question.
 */

export const EPHEMERAL = { type: 'ephemeral' } as const

/** [payload] with the system prompt and the last tool marked as cache breakpoints. */
export function withCache(payload: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = { ...payload }
  if (typeof out.system === 'string' && out.system.length > 0) {
    out.system = [{ type: 'text', text: out.system, cache_control: EPHEMERAL }]
  }
  if (Array.isArray(out.tools) && out.tools.length > 0) {
    const tools = [...out.tools]
    tools[tools.length - 1] = { ...(tools[tools.length - 1] as Record<string, unknown>), cache_control: EPHEMERAL }
    out.tools = tools
  }
  return out
}

export interface CacheUse {
  read: number
  write: number
}

export function noCacheUse(): CacheUse {
  return { read: 0, write: 0 }
}

/** Adds one upstream reply's cache tokens to [total]. A body that is not JSON adds nothing. */
export function addCacheUse(total: CacheUse, body: string): void {
  try {
    const usage = JSON.parse(body)?.usage
    total.read += Number(usage?.cache_read_input_tokens ?? 0) || 0
    total.write += Number(usage?.cache_creation_input_tokens ?? 0) || 0
  } catch {
    // Not a reply: nothing cached to count.
  }
}

/** The system prompt as text, whether it is still a string or already a cached block list. */
export function systemText(system: unknown): string {
  if (typeof system === 'string') return system
  if (Array.isArray(system)) return system.map((block) => (block as { text?: unknown })?.text ?? '').join('')
  return ''
}

/** [body]'s usage with the question's cache totals set on it; anything unreadable is left alone. */
export function withCacheTotals(body: string, total: CacheUse): string {
  try {
    const parsed = JSON.parse(body)
    if (!parsed || typeof parsed !== 'object') return body
    parsed.usage = { ...(parsed.usage ?? {}), cache_read_input_tokens: total.read, cache_creation_input_tokens: total.write }
    return JSON.stringify(parsed)
  } catch {
    return body
  }
}
