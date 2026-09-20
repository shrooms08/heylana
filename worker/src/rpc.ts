/**
 * Every Solana call the worker makes goes through one function.
 *
 * Mainnet has two providers — RPC Fast and Helius — and `RPC_PRIMARY` says which is tried
 * first ("rpcfast" by default). A call that fails, answers with a retryable JSON-RPC error,
 * or takes longer than [RPC_TIMEOUT_MS] is tried once on the other; a second failure is a
 * plain error. Devnet has one address (`RPC_URL`) and no fallback, whatever `RPC_PRIMARY`
 * says. Each call is recorded — method, provider, milliseconds, and whether it was `ok`,
 * came from the `fallback` or `failed` — so the request log can carry them. **No URL is ever
 * logged or returned**: they are secrets, and only the provider's name leaves this file.
 */

/** How long one provider gets before the other is tried. */
export const RPC_TIMEOUT_MS = 1200

export type Cluster = 'mainnet-beta' | 'devnet'

/** What a provider is called in a log. Never its address. */
export type ProviderName = 'rpcfast' | 'helius' | 'devnet'

export interface RpcSample {
  method: string
  provider: ProviderName
  ms: number
  outcome: 'ok' | 'fallback' | 'failed'
}

export interface RpcOptions {
  /** Which network this call is for; the worker's own cluster by default. Names are always mainnet. */
  cluster?: Cluster
  signal?: AbortSignal
}

export interface Rpc {
  (method: string, params?: unknown, options?: RpcOptions): Promise<any>
  /** Every call made through this caller, in order. */
  readonly calls: RpcSample[]
  /** The milliseconds they took, added up. */
  readonly totalMs: number
  /** Whether any provider serves [cluster] at all (names need mainnet; a devnet worker may have none). */
  readonly has: (cluster: Cluster) => boolean
}

export interface RpcEnv {
  RPC_URL?: string
  RPCFAST_URL?: string
  MAINNET_RPC_URL?: string
  RPC_PRIMARY?: string
  CLUSTER?: string
}

interface Provider {
  name: ProviderName
  url: string
}

/**
 * Which providers serve [cluster], best first. Mainnet is RPC Fast and Helius in the order
 * `RPC_PRIMARY` asks for, leaving out whichever has no address; devnet is `RPC_URL` alone.
 */
export function providersFor(env: RpcEnv, cluster: Cluster): Provider[] {
  if (cluster === 'devnet') {
    return env.RPC_URL ? [{ name: 'devnet', url: env.RPC_URL }] : []
  }
  const rpcfast = env.RPCFAST_URL ? [{ name: 'rpcfast' as const, url: env.RPCFAST_URL }] : []
  const helius = env.MAINNET_RPC_URL ? [{ name: 'helius' as const, url: env.MAINNET_RPC_URL }] : []
  const order = env.RPC_PRIMARY === 'helius' ? [...helius, ...rpcfast] : [...rpcfast, ...helius]
  // A mainnet worker with neither mainnet address set, but whose RPC_URL is its mainnet one:
  // better one provider than none. Never for a devnet worker, whose RPC_URL is devnet's.
  if (order.length === 0 && env.RPC_URL && env.CLUSTER === 'mainnet-beta') return [{ name: 'rpcfast', url: env.RPC_URL }]
  return order
}

/**
 * JSON-RPC errors worth trying on the other provider: the node is behind, unhealthy, busy or
 * broke on its side. Anything about the request itself (bad params, an unknown method, a
 * transaction that simply fails) means the same on both, so it is returned as it came.
 */
const RETRYABLE_CODES = new Set([-32004, -32005, -32014, -32603])
const RETRYABLE_WORDS = /rate.?limit|too many requests|unhealthy|behind|timed? ?out|unavailable|capacity|try again/i

export function isRetryableRpcError(error: { code?: number; message?: string } | null | undefined): boolean {
  if (!error) return false
  if (typeof error.code === 'number' && RETRYABLE_CODES.has(error.code)) return true
  return RETRYABLE_WORDS.test(String(error.message ?? ''))
}

/** A JSON-RPC error the provider answered with, kept so callers can still see its code. */
export class RpcError extends Error {
  method: string
  code: number | undefined
  retryable: boolean

  constructor(method: string, code: number | undefined, message: string, retryable: boolean) {
    super(message)
    this.name = 'RpcError'
    this.method = method
    this.code = code
    this.retryable = retryable
  }
}

interface Attempt {
  ok: boolean
  result?: any
  error?: RpcError
  retryable: boolean
}

/** One request to one provider, with its own clock. */
async function attempt(
  provider: Provider,
  method: string,
  params: unknown,
  signal: AbortSignal | undefined,
  timeoutMs: number,
): Promise<Attempt> {
  const clock = new AbortController()
  const timer = setTimeout(() => clock.abort(), timeoutMs)
  const onAbort = () => clock.abort()
  signal?.addEventListener('abort', onAbort)
  try {
    const res = await fetch(provider.url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
      signal: clock.signal,
    })
    if (!res.ok) {
      return { ok: false, retryable: true, error: new RpcError(method, undefined, `rpc ${method}: http ${res.status}`, true) }
    }
    const body: any = await res.json()
    if (body?.error) {
      const retryable = isRetryableRpcError(body.error)
      return { ok: false, retryable, error: new RpcError(method, body.error.code, `rpc ${method}: ${body.error.code}`, retryable) }
    }
    return { ok: true, result: body?.result, retryable: false }
  } catch (thrown) {
    // The caller gave up (its own signal): not the provider's fault, and not worth a second try.
    if (signal?.aborted) return { ok: false, retryable: false, error: new RpcError(method, undefined, `rpc ${method}: cancelled`, false) }
    const timedOut = clock.signal.aborted
    return {
      ok: false,
      retryable: true,
      error: new RpcError(method, undefined, `rpc ${method}: ${timedOut ? 'timed out' : 'unreachable'}`, true),
    }
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', onAbort)
  }
}

/**
 * The caller every chain call uses. One per request, so its [Rpc.calls] are that request's.
 * [now] and [timeoutMs] are for the tests.
 */
export function makeRpc(
  env: RpcEnv,
  options: { now?: () => number; timeoutMs?: number; onCall?: (sample: RpcSample) => void } = {},
): Rpc {
  const now = options.now ?? (() => Date.now())
  const timeoutMs = options.timeoutMs ?? RPC_TIMEOUT_MS
  const calls: RpcSample[] = []
  const record = (sample: RpcSample) => {
    calls.push(sample)
    options.onCall?.(sample)
  }
  const worker: Cluster = env.CLUSTER === 'mainnet-beta' ? 'mainnet-beta' : 'devnet'

  const call = async (method: string, params: unknown = [], opts: RpcOptions = {}): Promise<any> => {
    const cluster = opts.cluster ?? worker
    const providers = providersFor(env, cluster)
    if (providers.length === 0) throw new RpcError(method, undefined, `rpc ${method}: no provider`, false)

    const started = now()
    const first = await attempt(providers[0], method, params, opts.signal, timeoutMs)
    const firstMs = now() - started
    const second = providers[1]

    if (first.ok) {
      record({ method, provider: providers[0].name, ms: firstMs, outcome: 'ok' })
      return first.result
    }
    if (!first.retryable || !second) {
      record({ method, provider: providers[0].name, ms: firstMs, outcome: 'failed' })
      throw first.error
    }

    const secondStarted = now()
    const again = await attempt(second, method, params, opts.signal, timeoutMs)
    const secondMs = now() - secondStarted
    record({ method, provider: providers[0].name, ms: firstMs, outcome: 'failed' })
    record({ method, provider: second.name, ms: secondMs, outcome: again.ok ? 'fallback' : 'failed' })
    if (again.ok) return again.result
    throw again.error
  }

  return Object.defineProperties(call as Rpc, {
    calls: { get: () => calls },
    totalMs: { get: () => calls.reduce((sum, c) => sum + c.ms, 0) },
    has: { value: (cluster: Cluster) => providersFor(env, cluster).length > 0 },
  })
}
