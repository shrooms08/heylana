# Smoke test — phase 3a.1: your name, the greeting, and the mark in Seed Vault

For Minos, on the Seeker, on **devnet** (the worker's `CLUSTER = "devnet"`, a devnet
`RPC_URL`, and devnet `USDC_MINT`).

**Live calls this test spends: 2 questions (`/chat`) and 2 spoken answers (`/tts`).**
Connecting the wallet, the name sheet and the mark cost no model calls.

## 0. Send the new worker up (on the computer)

1. In the `worker` folder run `npx wrangler deploy`.
2. Open `https://heylana-proxy.heylana.workers.dev/heylana-mark.png` in a browser.

Expected: the deploy finishes, and the browser shows Heylana's white mark on black.
**0 calls.**

Then install this build on the phone and run `./scripts/a11y.sh`.

## 1. Your name

1. Open Heylana → **Settings**. On the **Wallet** card tap **Disconnect**.
2. Tap **Connect wallet**. Approve both screens in Seed Vault.
3. A glass sheet slides up: **What should I call you?** with one field and **Save**.

Expected: the field is **empty**. (Heylana cannot look up your .skr name yet — there
is no public way to — so it never guesses.) Type `Minos`, tap **Save**.

Expected: the sheet closes, and the Wallet card shows **Minos** with the short
address under it. **0 calls.**

## 2. The greeting, once

1. Back on the main screen tap **Stop buddy**, then **Start buddy**.
2. Open Chrome on any page. Tap the buddy, type `what is on this screen`, send.

Expected: the answer **starts with "Minos"** and is spoken. **1 chat, 1 tts.**

3. Tap the buddy again and send `what is on this screen` again.

Expected: an answer with **no name** in it. **1 chat, 1 tts.**

## 3. The mark in Seed Vault

1. Settings → Wallet → **Disconnect**, then **Connect wallet**.

Expected: Seed Vault's approval screen shows **Heylana** with the white-on-black
mark, not a blank icon. Approve, and **Save** the name sheet again (it is
prefilled with `Minos` this time). **0 calls.**

Total: **2 chat, 2 tts.**
