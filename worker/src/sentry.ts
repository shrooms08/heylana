/**
 * Error reports to Sentry, only when a SENTRY_DSN secret is set.
 *
 * Only unhandled errors are reported. What goes is scrubbed first, the same way
 * every error the app sees is: no key or RPC address, and no Solana address or
 * signature at all (a base58 run of 32 or more characters becomes [address]). No
 * user, no IP, no headers, cookies, query string or body, and no breadcrumbs — the
 * worker's own log lines stay in Cloudflare's log and nowhere else.
 */
import { Toucan } from 'toucan-js'

/** Addresses are 32 to 44 base58 characters; signatures 87 or 88. */
const BASE58_RUN = /(?<![1-9A-HJ-NP-Za-km-z])[1-9A-HJ-NP-Za-km-z]{32,88}(?![1-9A-HJ-NP-Za-km-z])/g

export function scrubAddresses(text: string): string {
  return text.replace(BASE58_RUN, '[address]')
}

/** Every string anywhere in the event, scrubbed; the parts about the person, gone. */
export function scrubEvent<T>(event: T, scrubSecrets: (text: string) => string): T {
  const walk = (value: unknown): unknown => {
    if (typeof value === 'string') return scrubAddresses(scrubSecrets(value))
    if (Array.isArray(value)) return value.map(walk)
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, inner]) => [key, walk(inner)]))
    }
    return value
  }
  const clean = walk(event) as any
  delete clean.user
  delete clean.server_name
  delete clean.breadcrumbs
  if (clean.request) clean.request = { url: clean.request.url, method: clean.request.method }
  return clean as T
}

export interface WaitUntil {
  waitUntil(promise: Promise<unknown>): void
}

export function sentryFor(
  request: Request,
  dsn: string | undefined,
  context: WaitUntil | undefined,
  scrubSecrets: (text: string) => string,
): Toucan | null {
  if (!dsn) return null
  return new Toucan({
    dsn,
    context: context as any,
    request,
    // Nothing from the request but its method and path.
    requestDataOptions: { allowedHeaders: [], allowedCookies: [], allowedSearchParams: [], allowedIps: false },
    sendDefaultPii: false,
    beforeBreadcrumb: () => null,
    beforeSend: (event) => scrubEvent(event, scrubSecrets),
  })
}
