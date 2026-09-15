# Heylana's proxy

Every key Heylana needs lives in this Cloudflare Worker: Anthropic for the
answers, Cartesia for the voice, Deepgram for the ears. **The phone holds none
of them.** It sends what it wants done and this decides which model, which
voice, and how much any one phone may spend in a day.

Shaped after [Farza's Clicky worker](https://github.com/farzaa/clicky) (MIT) —
same idea of a thin key-holding proxy in front of the model, rewritten for
Heylana's routes.

## What it answers

Every route is a POST, and every route needs the header
`X-Heylana-Device: <the id the app made when it was installed>`.

| Route | The app sends | It does |
|---|---|---|
| `/chat` | `{mode, system, messages, max_tokens}` | Picks the model from `mode` — `quick` is Haiku, `task` is Sonnet — adds the Anthropic key, and returns the reply as it came. |
| `/tts` | `{text, voice}` | Says it in Skylar or Archie through Cartesia Sonic, and streams the audio straight back. Text is cut at 400 characters. |
| `/stt-token` | `{}` | Mints a Deepgram key that stops working after two minutes, so the phone can open the listening socket itself. |

Each phone gets 150 questions, 150 spoken answers and 300 pairs of ears a day.
Over that, the route answers `429 {"reason":"daily_cap"}`. That is budget
protection and nothing else — the real free and paid tiers arrive in phase 3.

Each request logs one line: which route, the first eight characters of the
device id, how long upstream took, tokens in and out for `/chat`, characters for
`/tts`. **No prompt, no transcript, no screen contents, ever.**

## Putting it up — the whole thing, in order

Everything below is run from this `worker/` directory.

**1. Sign in to Cloudflare.** A browser window opens; approve it there.

```
npx wrangler login
```

**2. Make the store the daily counts live in.**

```
npx wrangler kv namespace create CAPS
```

It prints a block ending in an `id = "…"`. Copy that id into `wrangler.toml`,
replacing the `id = "replace-me"` under `[[kv_namespaces]]`.

**3. Put in the three keys.** Each command asks for the key and does not echo
it. Paste, press enter.

```
npx wrangler secret put ANTHROPIC_API_KEY
npx wrangler secret put CARTESIA_API_KEY
npx wrangler secret put DEEPGRAM_API_KEY
```

> **The Deepgram key has to be allowed to make other keys.** `/stt-token` works
> by minting a short-lived key for the phone, and a plain "usage" key cannot do
> that — it comes back `INSUFFICIENT_PERMISSIONS ... keys:write`, the phone never
> gets ears, and it quietly uses the phone's own recogniser instead. In the
> Deepgram console, make a key with the **Owner** or **Administrator** role (or
> any role that includes `keys:write`) and use that one here.
>
> To check it without the phone:
>
> ```
> curl -s -X POST -H "X-Heylana-Device: 00000000-0000-4000-8000-000000000000" \
>   -d '{}' https://<your-worker>.workers.dev/stt-token
> ```
>
> A working key answers `{"key":"…","expires_in":120}`. A key without the scope
> answers `{"reason":"deepgram_scope", …}`.

**4. Put in the Deepgram project id.** It is not a secret. Open the Deepgram
console, copy the project id, and replace `DEEPGRAM_PROJECT_ID = "replace-me"`
in `wrangler.toml`.

**5. Set up wallets and payments.** Four things in `wrangler.toml`, then three
secrets.

In `wrangler.toml`, under `[vars]`:

- `TREASURY_ADDRESS` — the wallet address Pro payments go to. The wallet's own
  address, not a token account: the payer's transaction creates the treasury's
  USDC or SKR account if it does not exist yet.
- `SKR_MINT` — copy the SKR mint address from Solscan. **Do not guess it.** Until
  it is a real address, SKR quotes say "not set up" and USDC still works.
- `PRICE_USD` — the price of 30 days of Pro. `"15"` normally.
- `CLUSTER` — `"mainnet-beta"`. To test with play money instead, set it to
  `"devnet"`, put a **devnet** endpoint in `RPC_URL`, and set `USDC_MINT` to
  Circle's devnet USDC, `4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU`. The app
  follows the worker, so Seed Vault is asked about devnet too. There is no SKR on
  devnet: the Go Pro sheet greys SKR out and says so. Put all three back before
  submission.
- `USDC_MINT`, `PRO_DAYS` and `JUDGE_UNTIL` are already filled in; leave them.

Then the secrets. Each asks for the value and does not echo it.

```
npx wrangler secret put RPC_URL
npx wrangler secret put SESSION_SECRET
npx wrangler secret put JUDGE_CODE
```

- `RPC_URL` — your QuickNode **mainnet** endpoint, the whole `https://…` line.
  Its URL carries your token, which is why it is a secret and never a var.
- `SESSION_SECRET` — any long random string. This makes one:

  ```
  openssl rand -base64 48
  ```

  It seals wallet sign-ins. Changing it later signs every wallet out.
- `JUDGE_CODE` — the code you will give the judges. Anything you like.

Optionally, `npx wrangler secret put JUPITER_API_KEY` with a free key from
developers.jup.ag/portal. SKR pricing works without one, at Jupiter's keyless
rate limit, which is plenty for Heylana.

Optionally, `npx wrangler secret put MAINNET_RPC_URL` with a **mainnet** endpoint.
Seeker IDs (.skr names) live on mainnet whatever `CLUSTER` says, so while the worker
runs on devnet this is what lets Heylana resolve them; on mainnet `RPC_URL` is enough.
If `RPC_URL` is a Helius endpoint, token names come from its DAS API too; any other
RPC works without them.

**6. Send it up.**

```
npx wrangler deploy
```

The last line it prints is the address, and it looks like this:

```
Deployed heylana-proxy triggers (0.32 sec)
  https://heylana-proxy.<your-account>.workers.dev
```

**That address is what the app needs.** Copy the whole `https://…` line into
`local.properties` in the project root:

```
heylana.proxyUrl=https://heylana-proxy.<your-account>.workers.dev
```

Then rebuild and reinstall the app. `local.properties` is not in git, so the
address stays on your machine.

## Testing a real payment for ten cents

Before submission, test Pro with a real payment that costs almost nothing:

1. In `wrangler.toml`, set `PRICE_USD = "0.10"`.
2. `npx wrangler deploy`
3. Run the payment steps in `SMOKE.md`. A USDC payment is then 0.10 USDC.
4. **Set it back:** `PRICE_USD = "15"`, and `npx wrangler deploy` again.

A plan already paid for keeps its Pro days when the price changes back; only new
quotes use the new price.

## What the wallet routes do

| Route | Needs a wallet session | Does |
|---|---|---|
| `/wallet/challenge` | no | A message for the wallet to sign. Not a transaction; costs nothing. |
| `/wallet/verify` | no | Checks the signature, returns a 30-day session. The first time a wallet connects it gets 20 welcome talks. |
| `GET /me` | optional | The plan, talks used of the limit, the skills cap, and Pro or judge end dates. |
| `/judge` | optional | With the right code: Judge until `JUDGE_UNTIL`. |
| `/pay/quote` | yes | The exact USDC or SKR amount for Pro, and a reference to put in the payment. |
| `/pay/blockhash` | yes | A fresh blockhash for the payment transaction. |
| `/pay/confirm` | yes | Reads the payment back from the chain and checks it; Pro for `PRO_DAYS` if it is right. |

Plans: **Free** is 30 talks a calendar month (plus the one-off 20 welcome talks);
**Pro** and **Judge** are unlimited. Over the limit, `/chat` answers
`429 {"reason":"talks_cap", …}` and the app says so plainly. The daily per-device
caps stay on as a ceiling over every plan.

## Checking it without spending anything

```
npm test
```

Every test runs with no network: Anthropic, Cartesia, Deepgram, the Solana RPC
and Jupiter are all faked. They check that the
app cannot choose its own model, that the caps hold, that a request without a
device id is refused, and that no reply can carry a key even when the upstream
service puts one in its error message.

## Changing it later

```
npx wrangler tail        # watch the log lines live
npx wrangler deploy      # send up a change
```

Secrets are already up there and stay put; you only repeat `secret put` to
change a key.
