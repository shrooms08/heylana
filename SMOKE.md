# Smoke test — polish-11-lookout (the scam watchlist and a Solana app's own confirm)

For Minos. The lookout now catches a page under a listed scam domain, keeps every domain both
sources list, and speaks when Jupiter asks you to confirm a swap with its own fingerprint prompt.
Install the app, run `./scripts/a11y.sh`, wait five seconds, then Menu → hold **Start buddy**.

**Live calls this test spends: 1 chat (the send in section 3). A few spoken lines. Mainnet apps: never approve or
confirm anything; never touch the fingerprint sensor at a swap prompt.**

---

## 1. A page under a listed domain (0 chat)

1. On the Mac: `adb shell am broadcast -a xyz.heylana.app.debug.PANEL --es test_scam heylana-test-scam.example`
   (a made-up domain, on this phone only).
2. On the phone, open Chrome at `claim.heylana-test-scam.example`. It will not load — that is fine.
3. Within a second a card beside the disc says **"This site is on a scam watchlist built from
   ScamSniffer's feed and Phantom's blocklist. Check the address bar."**, marked **heads up**, and
   she says the first part out loud. She never says "safe".
4. Clear it: the same command with `--es test_scam "''"`.

## 2. Jupiter's own confirm (0 chat) — needs at least 0.01 SOL in Jupiter's wallet

1. Open Jupiter, unlock it, go to **Trade**, Sell SOL, Buy USDC, type **0.0009**.
2. Tap the green **Swap**. The fingerprint prompt comes up. **Do not touch the sensor.**
3. Before you do anything she says **"Jupiter wants you to confirm: 0.0009 SOL for about … USDC."**
   (If she says "Jupiter wants you to confirm a swap. Check the amounts before you touch the
   sensor." instead, the form was hidden under the prompt: note it.)
4. Cancel the prompt.
5. Unlocking Jupiter (its own fingerprint prompt on opening) says nothing.

## 3. Heylana's own send, rejected (1 chat)

1. In any app, hold the disc and say **send 0.01 USDC to** the treasury address (devnet).
2. When the card shows ✓ Simulation passed, tap **Confirm**. Seed Vault opens.
3. Tap **Reject** in Seed Vault.
4. The card says **Not sent** and she says **"Cancelled. Nothing left your wallet."** Nothing else
   from the lookout pops up over the card (Logcat: `lookout: skipped why=busy_own_send`).

## 3b. Seed Vault for someone else's request (0 chat)

1. In the Wallet app start a small Send and go through to Seed Vault's approval.
2. She says "Careful: something is asking for your signature." at once. **Reject** in Seed Vault.

## 4. The watchlist's size

The phone picks up the full list at its next daily check (Logcat: `lookout: list updated
domains=2335` or more).
