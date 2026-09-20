/**
 * Heylana's Solana knowledge base: public docs, Solana Stack Exchange answers and release
 * notes, cut into chunks of about 400 tokens by scripts/kb/, embedded with Workers AI
 * (@cf/baai/bge-base-en-v1.5, 768 dimensions) and kept in Cloudflare Vectorize.
 *
 * search_solana_kb embeds the model's query the same way and returns the closest chunks
 * with their title, url, source and licence, so an answer can say where it came from.
 * Nothing about any user is ever written to the index: only scripts/kb/build.sh writes to
 * it, through /kb/ingest, which needs KB_ADMIN_SECRET.
 */

export const EMBEDDING_MODEL = '@cf/baai/bge-base-en-v1.5'
export const DIMENSIONS = 768

/** The most chunks one search returns, and the longest excerpt it hands the model. */
export const MAX_K = 5
export const EXCERPT_CHARS = 1200
/** Below this cosine similarity a chunk is not about the question. */
export const MIN_SCORE = 0.6
/** The most chunks one /kb/ingest call takes: Workers AI embeds up to 100 texts at once. */
export const INGEST_BATCH = 50
/** Vectorize keeps up to 10 KiB of metadata a vector: the chunk's text fits well inside it. */
const STORED_TEXT_CHARS = 2000

export interface Ai {
  run(model: string, input: { text: string[] }): Promise<{ data: number[][] }>
}

export interface VectorMatch {
  id: string
  score: number
  metadata?: Record<string, unknown>
}

export interface VectorIndex {
  query(vector: number[], options: { topK: number; returnMetadata?: 'all' | 'indexed' | 'none' }): Promise<{ matches: VectorMatch[] }>
  upsert(vectors: { id: string; values: number[]; metadata?: Record<string, unknown> }[]): Promise<unknown>
}

export interface Kb {
  ai: Ai
  index: VectorIndex
}

export interface Chunk {
  id: string
  title: string
  url: string
  source: string
  licence: string
  text: string
}

export interface KbResult {
  title: string
  url: string
  source: string
  licence: string
  excerpt: string
  score: number
}

/** What gets embedded for a chunk: its title leads, so a chunk far into a page still says what it is about. */
export function embeddingText(chunk: Pick<Chunk, 'title' | 'text'>): string {
  return `${chunk.title}\n\n${chunk.text}`
}

/** The closest chunks to [query], best first; chunks under [MIN_SCORE] are left out. */
export async function searchKb(kb: Kb, query: string, k: number): Promise<KbResult[]> {
  const wanted = Math.min(MAX_K, Math.max(1, Math.floor(k) || 3))
  const embedded = await kb.ai.run(EMBEDDING_MODEL, { text: [query.slice(0, 500)] })
  const vector = embedded?.data?.[0]
  if (!Array.isArray(vector) || vector.length !== DIMENSIONS) throw new Error('embedding failed')
  const found = await kb.index.query(vector, { topK: wanted, returnMetadata: 'all' })
  return (found?.matches ?? [])
    .filter((m) => m.score >= MIN_SCORE && m.metadata)
    .map((m) => ({
      title: String(m.metadata!.title ?? ''),
      url: String(m.metadata!.url ?? ''),
      source: String(m.metadata!.source ?? ''),
      licence: String(m.metadata!.licence ?? ''),
      excerpt: String(m.metadata!.text ?? '').slice(0, EXCERPT_CHARS),
      score: Math.round(m.score * 1000) / 1000,
    }))
}

/** The tool's answer: the results, or a plain reason there are none. */
export async function searchTool(kb: Kb | undefined, input: any): Promise<unknown> {
  if (!kb) return { error: 'not_configured', detail: 'The knowledge base is not set up.' }
  const query = typeof input?.query === 'string' ? input.query.trim() : ''
  if (!query) return { error: 'no_query' }
  const results = await searchKb(kb, query, Number(input?.k ?? 3))
  if (results.length === 0) return { results: [], note: 'Nothing in the knowledge base fits. Answer from what you know, and say it is not from a source.' }
  return {
    results,
    note: 'When you use a result, name its source in a few words in say ("the Solana Cookbook has an example"). ' +
      'Never write a url in say: add "cite": [the url of each result you used] to your reply, and the app shows it as a link.',
  }
}

/** The most source chips an answer carries. */
export const MAX_SOURCES = 2
/** A result this close is shown as the source when the answer cited nothing itself. */
export const TOP_SOURCE_SCORE = 0.7

/**
 * The reply with the model's "cite" swapped for "sources": each cited url that the knowledge
 * base really returned for this question, with its title and source, at most [MAX_SOURCES].
 * A url the model made up, or one it was never handed, is dropped. A body that isn't one of
 * our replies is left as it is.
 */
export function withSources(body: string, found: Map<string, KbResult>): { body: string; sources: number; from: 'cite' | 'top' | 'none' } {
  let parsed: any
  try {
    parsed = JSON.parse(body)
  } catch {
    return { body, sources: 0, from: 'none' as const }
  }
  const content = parsed?.content
  if (!Array.isArray(content)) return { body, sources: 0, from: 'none' as const }
  for (const block of content) {
    if (block?.type !== 'text' || typeof block.text !== 'string') continue
    // The reply object, wherever it sits: the model sometimes writes a sentence before it.
    const located = replyObject(block.text)
    if (!located) continue
    const reply = located.reply
    if (!('cite' in reply) && found.size === 0) return { body, sources: 0, from: 'none' as const }
    const cited = (Array.isArray(reply.cite) ? reply.cite : [reply.cite]).filter((u: unknown) => typeof u === 'string')
    let picked = [...new Set<string>(cited)].map((url) => found.get(url)).filter((r): r is KbResult => !!r)
    let from: 'cite' | 'top' | 'none' = picked.length > 0 ? 'cite' : 'none'
    // It searched and answered but cited nothing: the closest strong match is where it came from.
    if (picked.length === 0) {
      const top = [...found.values()].sort((a, b) => b.score - a.score)[0]
      if (top && top.score >= TOP_SOURCE_SCORE) {
        picked = [top]
        from = 'top'
      }
    }
    const sources = picked.slice(0, MAX_SOURCES).map((r) => ({ title: r.title, source: r.source, url: r.url }))
    delete reply.cite
    if (sources.length > 0) reply.sources = sources
    block.text = block.text.slice(0, located.start) + JSON.stringify(reply) + block.text.slice(located.end)
    return { body: JSON.stringify(parsed), sources: sources.length, from }
  }
  return { body, sources: 0, from: 'none' as const }
}

/** The first JSON object in [text] that has a say: where it starts and ends, and what it holds. */
export function replyObject(text: string): { reply: Record<string, unknown>; start: number; end: number } | null {
  for (let start = text.indexOf('{'); start >= 0; start = text.indexOf('{', start + 1)) {
    const end = closingBrace(text, start)
    if (end < 0) return null
    try {
      const reply = JSON.parse(text.slice(start, end + 1))
      if (reply && typeof reply === 'object' && !Array.isArray(reply) && 'say' in reply) return { reply, start, end: end + 1 }
    } catch {
      // Not an object after all: look further on.
    }
  }
  return null
}

/** Where the object opened at [start] closes, minding strings; -1 if it never does. */
function closingBrace(text: string, start: number): number {
  let depth = 0
  let inString = false
  for (let i = start; i < text.length; i++) {
    const c = text[i]
    if (inString) {
      if (c === '\\') i++
      else if (c === '"') inString = false
    } else if (c === '"') inString = true
    else if (c === '{') depth++
    else if (c === '}' && --depth === 0) return i
  }
  return -1
}

/** Checks, embeds and stores one batch from scripts/kb/build.sh. */
export async function ingest(kb: Kb, chunks: unknown): Promise<{ upserted: number } | { error: string }> {
  if (!Array.isArray(chunks) || chunks.length === 0 || chunks.length > INGEST_BATCH) return { error: 'bad_batch' }
  const clean: Chunk[] = []
  for (const c of chunks as any[]) {
    if (!c || typeof c.id !== 'string' || !/^[0-9a-f]{8,64}$/.test(c.id)) return { error: 'bad_id' }
    for (const key of ['title', 'url', 'source', 'licence', 'text']) {
      if (typeof c[key] !== 'string') return { error: `bad_${key}` }
    }
    if (!/^https:\/\//.test(c.url)) return { error: 'bad_url' }
    clean.push({ id: c.id, title: c.title.slice(0, 300), url: c.url.slice(0, 500), source: c.source.slice(0, 80),
      licence: c.licence.slice(0, 200), text: c.text.slice(0, STORED_TEXT_CHARS) })
  }
  const embedded = await kb.ai.run(EMBEDDING_MODEL, { text: clean.map(embeddingText) })
  if (!Array.isArray(embedded?.data) || embedded.data.length !== clean.length) return { error: 'embedding_failed' }
  await kb.index.upsert(clean.map((c, i) => ({
    id: c.id,
    values: embedded.data[i],
    metadata: { title: c.title, url: c.url, source: c.source, licence: c.licence, text: c.text },
  })))
  return { upserted: clean.length }
}

// ------------------------------------------------- the search that runs before the model

/** How many chunks are looked up before the model is asked, and how much of each it reads. */
export const CONTEXT_K = 3
export const CONTEXT_CHARS = 900

/** What the pre-search fills in: the same counters the tool fills in when the model calls it. */
export interface KbStats {
  kbHits: number
  found: Map<string, KbResult>
  kbError?: string
}

/**
 * The pages the question is about, put in front of the model instead of offered to it.
 *
 * The eval found the hole: on sixty developer questions the model called `search_solana_kb`
 * about a third of the time, answered the rest from memory, and cited nothing — so answers
 * that could have carried a source carried none, and facts that move with a release were
 * whatever the weights remembered. A developer's or a lesson's question now has the closest
 * three chunks read out of the index first and handed over with the question. The tool stays
 * on the table for a second, different query once it has read them.
 *
 * Every chunk handed over is registered in [stats.found], which is what turns the model's
 * `cite` into the answer's source chips — and what lets [withSources] fall back to the
 * closest strong match when it used one and cited nothing.
 */
export async function lookUpFirst(kb: Kb | undefined, query: unknown, stats: KbStats): Promise<string | null> {
  const asked = typeof query === 'string' ? query.trim() : ''
  if (!kb || asked.length === 0) return null
  let results: KbResult[]
  try {
    results = await searchKb(kb, asked, CONTEXT_K)
  } catch (err) {
    // A spent Workers AI allowance ("4006") or a Vectorize failure: the answer still comes,
    // from what the model knows, with no chunks and no chip.
    stats.kbError = String((err as any)?.message ?? err).slice(0, 80)
    return null
  }
  if (results.length === 0) return null
  stats.kbHits += results.length
  for (const result of results) stats.found.set(result.url, result)
  return contextBlock(results)
}

/** The chunks as the model reads them: each one titled, with the page it came from. */
export function contextBlock(results: KbResult[]): string {
  const pages = results.map((result, i) =>
    `[${i + 1}] ${result.title} (${result.source})\n${result.url}\n${result.excerpt.slice(0, CONTEXT_CHARS)}`)
  return (
    "From Heylana's Solana knowledge base, the pages closest to this question:\n\n" +
    pages.join('\n\n') +
    '\n\nAnswer from these where they fit, and prefer them to what you remember: they are ' +
    'current and your memory may not be. Name the one you used in a few words in say — never ' +
    'a url — and put its url in "cite". If none of them answers it, say so and answer from ' +
    'what you know; search_solana_kb is there if a different query would find it.'
  )
}

/** [block] appended to the question, where an address check would go. */
export function withKbContext(messages: unknown, block: string | null): unknown {
  if (!Array.isArray(messages) || messages.length === 0 || !block) return messages
  const copy = [...messages]
  const last: any = copy[copy.length - 1]
  if (last?.role !== 'user' || typeof last.content !== 'string') return messages
  copy[copy.length - 1] = { ...last, content: `${last.content}\n\n${block}` }
  return copy
}
