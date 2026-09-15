# Smoke test — phase 3a: wallet, plans and Pro payment

For Minos, on the Seeker, with Seed Vault Wallet holding a little SOL for fees,
at least 0.10 USDC, and some SKR.

**Live calls this test spends: 1 question (`/chat`) and 1 spoken answer (`/tts`).**
It also makes one real on-chain payment of **0.10 USDC** from your wallet to the
treasury. Everything else (connecting, the plan, quotes, the judge code) costs
no model calls.

## 1. Set the worker up (on the computer)

1. Open `worker/wrangler.toml`. Set `TREASURY_ADDRESS` to the wallet that receives
   payments, `SKR_MINT` to the SKR token's mint address, and `PRICE_USD` to `"0.10"`.
2. In the `worker` folder run, one at a time, pasting each value when asked:
   `npx wrangler secret put RPC_URL` (your Solana RPC address),
   `npx wrangler secret put SESSION_SECRET` (paste the output of `openssl rand -base64 48`),
   `npx wrangler secret put JUDGE_CODE` (any code you like; write it down).
3. Run `npx wrangler deploy`.

Expected: the deploy finishes and prints the worker address. **0 calls.**

## 2. Connect the wallet

1. Open Heylana → **Settings**.
2. On the **Wallet** card tap **Connect wallet**.
3. Seed Vault opens and asks to connect Heylana. Approve.
4. Seed Vault asks you to sign a message starting "Heylana wants you to sign in".
   Approve. (It is a message, not a transaction.)

Expected: back in Settings the Wallet card shows a short address like `9WzD…AWWM`
and **Disconnect**, with the line **20 welcome talks added** under it. The **Plan**
card shows **Free**, **0 of 50 talks this month**, **Up to 3 skills**, and a
**Go Pro, $15/month** button. **0 chat calls.**

## 3. One question

1. Open Chrome on any page. Tap the buddy, type `What is Solana?`, send.

Expected: an answer appears and is spoken. Back in Settings, the Plan card shows
**1 of 50 talks this month**. **1 chat call, 1 tts call.**

## 4. Go Pro with USDC

1. Settings → Plan → **Go Pro, $15/month**. A glass sheet slides up.
2. **USDC** is picked. The sheet shows **You'll send 0.10 USDC**.
3. Tap **Pay**. Seed Vault asks to connect, then shows a transfer of 0.10 USDC.
   Approve both.
4. The sheet says "Sent. Waiting for Solana to confirm it…".

Expected: within 60 seconds the sheet closes and the Plan card shows **Pro**,
**Unlimited talks**, **Up to 10 skills**, **Pro until <30 days from today>**. The
Go Pro button is gone. **0 calls.**

## 5. SKR quote, then cancel

This needs a wallet still on Free: tap **Disconnect**, connect a *different*
account in Seed Vault (or skip this step if you only have one).

1. Settings → **Go Pro, $15/month** → tap **SKR**.
2. The sheet shows **You'll send <some> SKR** and **About $0.10 at today's SKR price**.
3. Tap **Cancel**.

Expected: the sheet closes, nothing opens in Seed Vault, nothing is paid. **0 calls.**

## 6. Judge code

1. Settings → **Advanced** → **Judge code** card. Type the code from step 1, tap **Use code**.

Expected: the line **Judge until Nov 9, 2026** appears, and the Plan card at the
top shows **Judge**, **Unlimited talks**, **Up to 10 skills**.

2. Scroll up, tap **Disconnect**, then **Connect wallet** again with the same
   account and approve both Seed Vault screens.

Expected: the short address comes back **without** "20 welcome talks added", and
the Plan card still says **Judge**. **0 calls.**

## 7. Put the price back

1. In `worker/wrangler.toml` set `PRICE_USD` back to `"15"`. Run `npx wrangler deploy`.
2. On the phone, close Settings and open it again.

Expected: the Plan card still says **Judge until Nov 9, 2026**. **0 calls.**

## If something goes wrong

- "Cancelled in Seed Vault." — you declined; nothing was sent.
- "Not enough in this wallet to pay, including the network fee." — top up USDC or SOL.
- "That took too long…" — the payment may still land. Close and reopen Settings in
  a minute; Heylana checks it again and Pro appears if it went through.
