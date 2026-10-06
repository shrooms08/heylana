# Smoke test — the release build on mainnet

This is the signed release APK, version **1.0.3**, with the worker on **mainnet**. Real
money moves if you approve a send, so read every screen before you touch anything.

The app talks to **api.heylana.xyz** and tells the wallet it is **heylana.xyz**. Both are
the same worker. It must not go back to a workers.dev address: the Wallet blocks every
mainnet transaction from one with "Scam site detected".

**Expected live API calls: 8 `/chat`** (one a question, plus the send's own), the voice
on each spoken answer, and the ears on each hold. No `/pay/*` anywhere.

**Before you start**

- The Wallet app on the phone is on **mainnet** (not devnet).
- The wallet you sign in with holds at least **0.02 USDC** and about **0.01 SOL** for fees.
- The debug build is gone. Install the release one with
  `adb install -r dist/heylana-1.0.3.apk`, or let Claude do it.
- After installing, run `./scripts/a11y.sh` so screen reading is on.

---

## 1. It says Mainnet, and never devnet

1. Open **Heylana**. Sign in with your wallet if it asks, and let it reach Home.
2. Look at the top of Home, beside your name.
   - **Expect:** a small grey **Mainnet** badge. Not amber, and never the word Devnet.
3. Open the menu (top left) → **Plan**.
   - **Expect:** your plan, and nothing anywhere saying devnet.

## 2. She answers about a screen, and points

1. Menu → **Start buddy**: hold the round power button until the ring fills and the phone
   ticks. The disc pops out from the edge.
2. Open the **Wallet** app.
3. Tap the disc once. The box opens with a question field.
4. Type **what is this screen for** and tap Send.
   - **Expect:** one or two spoken sentences about the Wallet, and a box or arrow drawn
     around one thing on the screen. Nothing says devnet.

## 3. "What am I signing" speaks straight away

1. Still in the Wallet, start a **swap or a send of your own** — any amount — and go as far
   as the screen that asks you to approve. **Do not approve it.**
   - **Expect:** Heylana speaks within about a second of that screen appearing: a short
     warning, with the amount and the address on the strip beside the disc.
2. Tap **Cancel** or **Reject** on that screen. Nothing was signed.

## 4. A real send of 0.01 USDC to the treasury — you approve this one

1. Tap the disc. Type **send 0.01 USDC to 7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv**
   and send it.
2. **Expect:** "checking with the network…" with Confirm greyed out, then a card:
   - the first line **Prepared, not signed · Mainnet**
   - 0.01 USDC leaving, the treasury as the recipient, a fee of about 0.000005 SOL
   - a green **✓ Simulation passed**
   - and she says she has prepared it, with **no** "on devnet".
3. Tap **Confirm**. The card goes small and stays out of the way.
   - **Expect:** **Waiting for your wallet**, then Seed Vault opens.
4. Seed Vault may ask you to connect first (the app now identifies itself as
   heylana.xyz). Then check the amount and the address, leave **trust this app**
   unticked, and approve. When the Wallet shows its own **Success** screen, tap
   **Close** — the wallet does not hand back until you do.
   - **Expect:** **Signed, confirming**, then **Sent** — "Done. 0.01 USDC went to
     7c2y…SxSv." with a small signature chip.
5. Tap the signature chip.
   - **Expect:** Explorer opens on **mainnet** (the address bar has no `?cluster=devnet`)
     and shows that transaction as succeeded.

## 5. A send you reject

1. Tap the disc. Send the same message again: **send 0.01 USDC to
   7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv**.
2. Wait for **Prepared, not signed · Mainnet** and tap **Confirm**.
3. When Seed Vault opens, tap **Reject** (or back out of it).
   - **Expect:** the card reads **Not sent**, and she says either "Cancelled. Nothing left
     your wallet." or — when the wallet does not say plainly that you declined — "Not sent.
     Nothing left your wallet. If you meant to send it, try again." Either way nothing left
     your wallet, nothing is retried, and no second wallet screen appears. She must never
     say it expired: you rejected it.

## 6. A wallet that goes quiet

1. Tap the disc. Send **send 0.01 USDC to 7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv**
   again and wait for **Prepared, not signed · Mainnet**.
2. Tap **Confirm**, and when Seed Vault opens, **leave it without deciding** — press Back,
   or go Home and stay there.
   - **Expect:** within a few seconds of leaving, the card reads **Not sent** and she says
     "Cancelled. Nothing left your wallet."
3. If instead the wallet ever puts up a screen of its own that you cannot answer (a block,
   a warning with only a Close button), leave it and watch Heylana.
   - **Expect:** after about two minutes she looks for it on the network, and then the card
     ends by itself. If it did go, **Sent** with the signature chip. If it did not,
     **Not sent** — "Your wallet didn't come back, and I couldn't find it on the network,
     so it most likely didn't leave your wallet. Check your wallet app before asking
     again." She never waits for ever, never says nothing went without looking, never
     blames expiry for a wallet that went quiet, never leaves you waiting on a signature
     that was never given, and never sends anything again.

## 7. What is happening on Solana now

1. Tap the disc. Type **what hackathon is going on on Solana right now** and send it.
   - **Expect:** one to three sentences naming real things with dates — a hackathon or a
     deadline — and one or two source chips under the answer. Not "I don't know", and not
     a sentence that trails off mid-word.
2. Tap a chip.
   - **Expect:** the page opens in the browser.

## 8. A timer

1. Tap the disc. Type **set a timer for 5 minutes** and send it.
   - **Expect:** the Clock app comes to the front with a 5-minute timer, Heylana says
     "Timer set for 5 minutes.", the box melts away and the disc flies home.
2. Cancel the timer in the Clock.

---

## If something is wrong

- **The badge says nothing at all:** she has not heard from the server yet. Open the menu
  and come back to Home.
- **Screen reading is off** (she says so, or Permissions shows "Allow"): run
  `./scripts/a11y.sh`, then close and reopen Heylana.
- **"Something went wrong on my side."**: that is the server, not the phone. Say what you
  asked and when.
- **"Scam site detected", only a Close button:** the build is pointing at a workers.dev
  address again. Check `heylana.identityUrl` in local.properties and rebuild.
- **A send stops at "Not confirmed yet":** leave it. It may still have left the wallet;
  check the Wallet app in a minute. Heylana never sends a second copy.
