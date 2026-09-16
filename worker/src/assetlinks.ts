/**
 * Digital Asset Links for Heylana's app.
 *
 * The app names this worker as its identity when it asks a wallet to connect or
 * sign. Seed Vault checks that claim by fetching /.well-known/assetlinks.json from
 * that address: the statement says the site vouches for the Android app
 * xyz.heylana.app signed with these certificates. The fingerprints come from the
 * ASSETLINKS_SHA256 var, comma-separated, so a release certificate can be added
 * without touching code.
 */
export const ASSETLINKS_PATH = '.well-known/assetlinks.json'
export const APP_PACKAGE = 'xyz.heylana.app'

/** SHA-256 fingerprints in the colon-separated uppercase form the statement uses; anything else is dropped. */
export function fingerprints(raw: string | undefined): string[] {
  const cleaned = String(raw ?? '')
    .split(',')
    .map((entry) => entry.trim().replace(/:/g, '').toUpperCase())
    .filter((hex) => /^[0-9A-F]{64}$/.test(hex))
    .map((hex) => hex.match(/../g)!.join(':'))
  return [...new Set(cleaned)]
}

export function assetLinksResponse(raw: string | undefined, head = false): Response {
  const prints = fingerprints(raw)
  if (prints.length === 0) {
    return new Response(JSON.stringify({ reason: 'not_configured', detail: 'ASSETLINKS_SHA256 is not set.' }), {
      status: 404,
      headers: { 'content-type': 'application/json' },
    })
  }
  const statement = [
    {
      relation: ['delegate_permission/common.handle_all_urls', 'delegate_permission/common.get_login_creds'],
      target: { namespace: 'android_app', package_name: APP_PACKAGE, sha256_cert_fingerprints: prints },
    },
  ]
  return new Response(head ? null : JSON.stringify(statement), {
    headers: { 'content-type': 'application/json', 'cache-control': 'public, max-age=3600' },
  })
}
