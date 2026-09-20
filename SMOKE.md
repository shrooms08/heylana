# Smoke test — polish-5-rpc

For Minos. Nothing changed in the app: this is all the server. It is deployed already.
Devnet stays on `RPC_URL`, so the phone's sends and payments are exactly as they were.

**Live calls this test spends: 1 chat, about 1 tts.** Never tap Confirm or Pay.

The secret for step 2 is in `worker/.admin_secret` (git-ignored, as the knowledge base's is):

```
cd worker && SEC=$(cat .admin_secret)
```

## 1. Ask something that reads the chain (1 chat)

Open **Heylana**, type **what's my SOL balance** and tap the arrow. You get your balance as
usual.

## 2. Read the table

```
curl -s "https://heylana-proxy.heylana.workers.dev/admin/usage?days=7" \
  -H "X-Heylana-Admin: $SEC" | python3 -m json.tool
```

Expected, for today:

- `wallets: 1` — a count, never an address.
- `chat` naming the model that answered, with `tokens_in` and `tokens_out`.
- `rpc` with `devnet` and how many calls it served, and `rpc_ms` with their total.
- `tts` with the characters Heylana spoke.
- `cost` — an estimate in dollars from the `prices` sheet printed above it.
- `latency_24h` — one row per provider and method with `calls`, `p50` and `p95`. Your balance
  question shows `devnet / getBalance` and `devnet / getTokenAccountsByOwner`.

Without the header it is a 404: `curl -s -o /dev/null -w "%{http_code}\n" ".../admin/usage"`.

## 3. See the per-call lines (optional)

In one terminal `cd worker && npx wrangler tail --format pretty`, then ask another question.
Each chain call prints `"route":"rpc"` with its method, provider, ms and `ok`; the request's
own line carries the same calls under `rpc` with `rpc_ms`.

## 4. Confirm RPC Fast is the mainnet primary

Two ways — the second is the one in the brief:

**Already proved live.** Ask **who owns toly.skr** in the app. A .skr name is always looked
up on mainnet, so that call goes to the mainnet primary; `/admin/usage` then shows
`rpc: { devnet: …, rpcfast: 1 }`. (Done on Sept 20: one `rpcfast` `getAccountInfo`, 67ms.)

**Locally, without deploying.** In `worker/`, put the addresses in a `.dev.vars` file (it is
git-ignored; `wrangler secret list` names the ones in use):

```
RPC_URL=<devnet address>
RPCFAST_URL=<RPC Fast mainnet address>
MAINNET_RPC_URL=<Helius mainnet address>
SESSION_SECRET=anything-for-local
ADMIN_SECRET=local
```

Then edit `wrangler.toml` and set `CLUSTER = "mainnet-beta"`, run `npx wrangler dev`, and in
another terminal:

```
curl -s localhost:8787/admin/usage -H "X-Heylana-Admin: local" | python3 -m json.tool | head
```

Ask the local worker anything that reads the chain (or just watch its output): the per-call
lines say `"provider":"rpcfast"`. **Put `CLUSTER` back to `"devnet"`** when you are done —
`git diff wrangler.toml` should come back empty — and stop `wrangler dev`.

## If a provider is down

Nothing to do. The other is tried once, and the log line says `"outcome":"fallback"`. If both
fail, the phone says "I can't reach my brain right now" or the plain line for that route.
