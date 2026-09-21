# Smoke test — polish-9-states (what a send is doing, which network, readable anywhere)

For Minos. Every send now says where it stands on its card — **Prepared, not signed**, then
**Waiting for your wallet**, then **Sent** or **Not sent** — and which network it is on. The
server is deployed already. Install the app, then run `./scripts/a11y.sh` once.

**Live calls this test spends: 2 chat, about 4 spoken lines. Devnet only. One real devnet
transfer of 0.01 USDC to the Heylana treasury (play money).** Before you start: in Seed
Vault, make sure Heylana is **not** marked as trusted, or it will sign without asking you.

---

## 1. Home shows the network (no live calls)

1. Open Heylana. You are on Home.
2. Beside "Heylana / your buddy" at the top there is a small **Devnet** badge in **amber**.
   (On a mainnet server it would say **Mainnet**, in grey.)

---

## 2. A send you approve (1 chat)

1. Menu → hold **Start buddy** until it ticks. Press the phone's **Home** button.
2. Hold the disc and say: `send 0.01 USDC to` and the treasury address (or type it in the box).
3. A dark, solid card comes up — the home-screen icons do **not** show through the words.
   At the top, **Prepared, not signed** in bold with an amber **Devnet** pill beside it and the
   speaker on the right. Then, as label and value: **Leaves your wallet** / **0.01 USDC**
   (the biggest thing on the card), **Arrives** / **0.01 USDC at your Heylana treasury
   (7c2y…SxSv)**, **Fee** / **0.000005 SOL**. Then, in grey, **Seed Vault will ask you to
   approve. Leave 'trust' unticked.** — nothing cut off. At the bottom, a green **✓ Simulation
   passed** on the left and **cancel** / **confirm** on the right. The numbers look like the rest
   of the words, not typewriter spacing ("0.01", not "0 . 01").
   She says: *"I've prepared it on devnet. Nothing moves until you approve in your wallet."*
4. Tap **confirm**. The card goes small and moves to the top of the screen: **Waiting for your
   wallet** with the Devnet pill — "Approve it in Seed Vault, or reject it there." It never sits
   over Seed Vault's buttons, and your **first** tap in Seed Vault works.
5. In Seed Vault tap your wallet card, then **approve**.
6. The card says **Signed, confirming** for a moment, then **Sent · Devnet** — *"Done. 0.01
   USDC went to 7c2y…SxSv."* (spoken), with a **Signature …** chip under it.
7. Tap the chip: Explorer opens on the transaction, on devnet.

---

## 3. A send you reject (1 chat)

1. Open the **Wallet** app. Ask for the same send again.
2. The same solid card comes up over the Wallet — full width, readable over its white buttons.
3. Tap **confirm**, then **Waiting for your wallet**, as before.
4. In Seed Vault, **reject** it (or close the sheet).
5. The card says **Not sent · Devnet** — *"Cancelled. Nothing left your wallet."* (spoken).
   Nothing is tried again.

---

## What I would want to hear about

- Heylana saying "Careful…" about Seed Vault's window during her own send — she should keep
  quiet then.
- Anything of Heylana's covering Seed Vault's buttons while it is open.
- "Sent" when nothing arrived, or "Not sent" when something did.
- The Devnet badge missing, or saying Mainnet on this server.
