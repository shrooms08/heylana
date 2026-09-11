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

**5. Send it up.**

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

## Checking it without spending anything

```
npm test
```

Twenty-five tests, no network: every upstream call is faked. They check that the
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
