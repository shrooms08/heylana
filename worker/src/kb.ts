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
  return { results, note: 'When you use a result, name its title in one short phrase. Give its url only if asked for a link.' }
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
