# Smoke test — slow Seed Vault approval, on-chain check, full-width box

For Minos, on the Seeker, on **devnet**, wallet connected.

**Live calls this test spends: 1 question (`/chat`) and 2 spoken answers (`/tts`).**
The send check after approving uses your devnet RPC, not the API budget.

## 0. Set up

1. In `worker`: `npx wrangler deploy` (the on-chain check needs this worker), then
   `npx wrangler tail` in a second terminal.
2. Install this build, run `./scripts/a11y.sh`, start the buddy.
3. On the computer: `adb logcat -s HeylanaState | grep -E "send:|wallet:|mode:"`

## 1. A slow approval still ends in "Sent"

1. Open Chrome. Tap the buddy, type `send 0.05 USDC to ` and paste your treasury
   address, send.

Expected: the strip reads "Send 0.05 USDC to 7c2y…SxSv. Confirm?" and is spoken.
**1 chat, 1 tts.**

2. Tap **confirm**. When Seed Vault shows the transfer, **wait 20 seconds**, then
   approve.

Expected: Heylana says **"Sent. Signature …"** with a short signature. The treasury is
up 0.05 USDC. Logcat shows `wallet: raw result success` (or a raw failure line followed
by `send: check #…` lines ending in `result=ok` and `send: found on chain`). No
`send: stopped`. **1 tts.**

## 2. The box, from both dock sides

1. Drag the buddy to the **left** edge. Tap it.

Expected: the disc flies to the top centre and the box opens **full width** under it,
with the keyboard. Tap outside to close.

2. Drag the buddy to the **right** edge. Tap it.

Expected: exactly the same — full width under the disc, never a narrow column beside
it. **0 calls.**

3. Optional: Settings → Debug states → **box from left dock** and **box from right
   dock**. Both show the disc flying to the top and a full-width box.

Total: **1 chat, 2 tts.**
