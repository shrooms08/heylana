# Smoke test — simulate before signing, the registry, the mode chip

For Minos, on the Seeker, on the **Judge** plan, with the worker on **devnet** (it is:
`CLUSTER = "devnet"`, deployed). Claude Code ran steps 1–3 on the Seeker already (see the
report); this is the final confirmation.

**Live calls this test spends: 3 chat, about 4 tts** — two sends and one question — and
**one real devnet transfer of 0.05 USDC** to the Heylana treasury, which you approve in
Seed Vault yourself in step 1.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open **Heylana**, Menu → **Start buddy** on.
Go to the home screen. Watch along with `adb logcat -s HeylanaState` if you like.

## 1. A send that passes (1 chat, 1–2 tts)

Tap the disc, type **send 0.05 USDC to 7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv**,
tap **ask**.

Expected, in order:
1. A small chip at the top of the box says **reading**, then **thinking**, then
   **preparing**.
2. The strip says "Send 0.05 USDC to 7c2y…SxSv." with the chip **simulating**, the line
   "checking with the network…" and a **greyed confirm** that does nothing if tapped.
3. Then the strip says **"Send 0.05 USDC from your wallet (EFj9…5L1S) to your Heylana
   treasury (7c2y…SxSv). Fee 0.000005 SOL, on devnet. Nothing has been signed.
   Confirm?"**, a green **✓ Simulation passed** appears, and **confirm** lights up. The
   preview is read aloud.
4. Tap **confirm**. The chip says **simulating** for a moment, then **approve in wallet**,
   and Seed Vault opens on the transfer. **Approve it.**
5. The chip says **working**, then **sent**, and Heylana says **"Sent. Signature …"**.

## 2. A send that fails simulation (1 chat, 1 tts)

Tap the disc, type **send 500 USDC to 7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv**,
tap **ask**.

Expected: the strip shows **simulating**, then goes, and Heylana says **"I did not open
the wallet because the simulation failed. Not enough USDC. You have …"**. Seed Vault never
opens. (It no longer asks "that's more than a quarter, say it again" first: 500 is more
than the whole balance, so there is nothing to ask twice.)

## 3. Touch the disc mid-sentence (1 chat, 1 tts)

Tap the disc, type **tell me a short story about a lighthouse keeper in three
sentences**, tap **ask**. While Heylana is speaking, **tap the disc once**.

Expected: she stops at once, mid-sentence. The box does not open or close from that tap.

## 4. Nothing to check by hand

The registry and the red-team tests run in CI (`.github/workflows/worker.yml`) and with
`cd worker && npm test`: 262 tests, the red-team file among them.
