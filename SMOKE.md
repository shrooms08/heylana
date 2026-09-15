# Smoke test — phase 3b: Heylana understands Solana

For Minos, on the Seeker, on **devnet**, with your wallet connected in Heylana and
the **Judge** plan active.

**Live calls this test spends: 7 questions (`/chat`) and 8 spoken answers (`/tts`).**
A question that looks things up (balances, prices, addresses) runs up to five model
calls inside the worker for that one question; step 7 runs exactly one. Lookups also
go to your devnet RPC and to Jupiter, which cost no API budget.

Heylana prepares, you sign. Nothing in this test is sent unless you approve it in
Seed Vault yourself (step 5).

## 0. Set up (on the computer)

1. In `worker`, check `wrangler.toml`: `CLUSTER = "devnet"`, devnet `USDC_MINT`.
2. Optional, for .skr names: `npx wrangler secret put MAINNET_RPC_URL` (a **mainnet**
   endpoint). Without it, .skr names answer "no mainnet connection is set up".
3. `npx wrangler deploy`. In a second terminal, `npx wrangler tail` to watch the worker.
4. Install this build on the phone and run `./scripts/a11y.sh`.
5. On the computer: `adb logcat -s HeylanaState | grep -E "brain:|sign-check:|send:|usage:"`

## 1. Your balance, from the chain

1. Open **Wallet** (Seed Vault Wallet) on its home screen.
2. Tap the buddy, type `what is my balance`, send.

Expected: the answer gives your devnet **SOL** and **USDC** amounts, matching what the
wallet shows, and does not give dollar values (devnet money has none). Logcat shows
`brain: mode=task why=wallet_screen solana-core loaded reason=app tools=sent`; the
worker tail shows `"tool_calls":["get_balances"]`. **1 chat, 1 tts.**

## 2. A price, with where it came from

1. Open Chrome. Tap the buddy, type `what is SOL worth right now`, send.

Expected: a dollar price for SOL that says it came from **Jupiter**. Logcat:
`solana-core loaded reason=words`. **1 chat, 1 tts.**

## 3. Who is this address

1. Still in Chrome, tap the buddy and type `who is ` then paste your **treasury
   address** (`7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv`), send.

Expected: it says this is a **wallet**, that it is Heylana's **treasury**, roughly how
new it is, and that it holds a small balance. **1 chat, 1 tts.**

## 4. What am I signing

1. In **Wallet**, start **Send**: 0.05 USDC to your treasury address. Continue until
   the confirm screen, and stop there. **Do not approve.**
2. Tap the buddy. Type `what am I signing`, send.

Expected: plain words saying this sends **0.05 USDC** to **your treasury**, that the
destination is known, and one line of advice (fine / check the amount / do not sign).
It never says "safe". Logcat: `why=signing_screen` and `sign-check: addresses=1
amounts=…`. **1 chat, 1 tts.**

3. Cancel the send in the wallet.

If the buddy is **not visible** over Seed Vault's own screen, write that down: Seed
Vault hides other apps on top of it. Ask on the Wallet's confirm screen instead.

## 5. Send, signed by you

1. Open Chrome. Tap the buddy, type `send 0.05 USDC to ` and paste your treasury
   address, send.

Expected: a strip reads **"Send 0.05 USDC to 7c2y…SxSv. Fee ~0.000005 SOL."** with
**cancel** and **confirm**, and Heylana reads it aloud. **1 chat, 1 tts.**

2. Tap **confirm**. Seed Vault opens and shows the transfer. Approve it.

Expected: within a minute Heylana says **"Sent. Signature …"**. The treasury's USDC
is up by 0.05. **1 tts.**

## 6. The 25% rule

1. Tap the buddy, type `send all my USDC to ` and paste your treasury address, send.

Expected: no strip. Heylana says **"That's more than a quarter of your USDC. If you mean
it, say "yes send it all", or say the amount again."** Do not say it. **1 chat, 1 tts.**

## 7. Not about Solana

1. In Chrome, tap the buddy, type `what is the capital of Nigeria`, send.

Expected: **Abuja**. Logcat: `brain: mode=quick why=plain solana-core not loaded
tools=not sent`; the worker tail shows `"tools":false,"rounds":1`. **1 chat, 1 tts.**

Total: **7 chat, 8 tts.**
