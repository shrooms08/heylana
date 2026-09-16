# Smoke test — phase 3b fixes: deterministic send, short addresses

For Minos, on the Seeker, on **devnet**, with your wallet connected and the Judge plan.

**Live calls this test spends: 3 questions (`/chat`) and 4 spoken answers (`/tts`).**
The send question (e) is one model call with no lookups; the signing question (d) may
run up to five model calls inside the worker. Lookups also go to your devnet RPC.

Heylana prepares, you sign. Nothing is sent unless you approve it in Seed Vault (e).

## 0. Set up

1. In `worker`: `npx wrangler deploy`, then in a second terminal `npx wrangler tail`.
2. Install this build on the phone and run `./scripts/a11y.sh`. Start the buddy.
3. On the computer:
   `adb logcat -s HeylanaState | grep -E "brain:|sign-check:|send:|usage:"`

## d. What am I signing (shortened addresses)

1. Open **Wallet**. Start **Send**: 0.05 USDC to your treasury address. Continue to the
   confirm screen and stop there. **Do not approve.**
2. Tap the buddy, type `what am I signing`, send.

Expected: the answer names **0.05 USDC** and **your Heylana treasury**, says whether the
destination is known, and ends with fine / check the amount / do not sign. No name
greeting, never "safe", never "I can't read any addresses", and no address longer than
`7c2y…SxSv`. Logcat: `why=signing_screen` and `sign-check: … shortened=1` (or more);
the worker tail shows `signing_ms`. **1 chat, 1 tts.**

3. Cancel the send in the wallet.

## e. Send, signed by you

1. Open Chrome. Tap the buddy, type `send 0.05 USDC to ` and paste your treasury
   address, send.

Expected: the strip reads exactly **"Send 0.05 USDC to 7c2y…SxSv. Confirm?"** with
**cancel** and **confirm**, and Heylana reads it aloud. No other words from the model.
Logcat, in this order: `brain: mode=task why=send_question …`, `send: raw action
type=send to=7c2y kind=address amount=0.05 token=USDC`, `usage: … tools=none`,
`send: guard verdict=allowed amount=0.05 token=USDC`, `send: strip …`. The worker
tail shows `"send_action":{"to":"7c2y","amount":0.05,"token":"USDC"}` and
`"rounds":1`. **1 chat, 1 tts.** Write down the `usage:` line.

2. Tap **confirm**. Seed Vault opens with the transfer. Approve it.

Expected: within a minute Heylana says **"Sent. Signature …"**. The treasury's USDC is
up by 0.05. **1 tts.**

## g. Not about Solana

1. In Chrome, tap the buddy, type `what is the capital of Nigeria`, send.

Expected: **Abuja**. Logcat: `brain: mode=quick why=plain solana-core not loaded
tools=not sent`; the worker tail shows `"tools":false,"rounds":1`. **1 chat, 1 tts.**

Total: **3 chat, 4 tts.**

If any step fails, copy every `HeylanaState` line from logcat before closing it —
the send lines now say exactly what came back and what the guard decided.
