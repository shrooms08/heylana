# Smoke test — polish-1

For Minos, on the Seeker, with a wallet connected, the worker on **devnet** (deployed with
prompt caching). Claude Code ran every item on the Seeker already (see the report); this is
the final confirmation.

**Live calls this test spends: about 28 chat, about 12 tts, and ears on every hold** —
1 for step 1, 1 for step 2, about 4 for step 4, 1 for step 5's text, 1 for step 6, and 20
for step 7's eval. Holds for "flashlight on/off", "turn it off" and "stop" cost no chat at
all: the phone does them itself. Never tap **Swap**, **confirm** or **Continue** on a
review or deposit screen, and never approve anything in Seed Vault.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open **Heylana**. Watch along with
`adb logcat -s HeylanaState` if you like.

## 1. Who made you (1 chat)

On Home, type **who made you** and tap the arrow.

Expected: one line naming **Minos, an independent developer in Lagos**. Menu → **Settings**,
scroll to the bottom: an **About** row with the same facts.

## 2. Memory keeps what you say (1 chat)

1. Menu → **Memory**. If it says "Hold to turn on", hold the brain button until its ring fills.
2. Back to Home. Type **I'm new to solana** and tap the arrow.

Expected: Heylana answers, and a small **Remembered** chip sits above the answer for about
two seconds, then goes.

3. Menu → **Memory**. Expected: **I'm new to solana**, "You said · <today>".
4. Back to Home. Type **forget that** and tap the arrow. Expected: **"Okay, forgotten."** at
   once, with no "Thinking…". Menu → **Memory**: the line is gone.

## 3. Hold to start, stop and remember (no chat)

1. Menu. Next to **Start buddy** is a round power button. Press it and let go straight
   away. Expected: the ring starts filling clockwise, then unwinds; nothing starts.
2. Press and **hold** it. Expected: the ring fills all the way round in just over a second,
   the phone ticks, and the disc pops out from the right edge of the screen. The line under
   Start buddy says "On. Hold to stop."
3. Look at the docked disc: it sits a finger-nail's width (6dp) from the edge, not a
   thumb's width in.
4. Menu → **Settings** → **Stop buddy**: hold its power button. Expected: the ring fills, a
   tick, the disc disappears, and the row says "The buddy isn't running."
5. Menu → **Memory**: hold the brain button. Expected: memory turns off ("Memory is off.
   Nothing is kept."). Hold it again to turn it back on.

## 4. Teach me how to swap, to the review screen (about 4 chat)

1. Menu → hold **Start buddy**. Open the **Wallet** (on Mainnet if you want the real swap
   screens; it says "Switch to Mainnet" on devnet).
2. Hold the disc and say **teach me how to swap**, or tap it and type that.

Expected: the disc flies next to **Swap** with a ring round it and a short reason, then
"Tap Swap to start." Wait twenty seconds without touching anything: **nothing moves on** —
the Wallet's prices refreshing no longer count as you doing the step.

3. Tap **Swap**. Expected: the next step, with the box clear of whatever it points at.
4. Type an amount on the keypad (for example 0.00001), then tap the **Swap** button at the
   bottom. Expected: the review sheet opens and Heylana explains it ("Check the amounts and
   network fee, then tap Swap to confirm").
5. **Do not tap Swap.** Say or type **stop**. Expected: the session ends and the disc flies
   home. Close the review with its **X**.

## 5. Short phrases, and texting by name (1 chat)

Five short holds on the disc, saying each as your finger lands:
**flashlight on**, **turn it off**, **stop**, **flashlight on**, **flashlight off**.

Expected: each is done first time — the torch goes on and off within a moment of letting
go, and "stop" puts the box away — with no "Thinking…" for any of them.

Then hold and say **text Ada I'm on my way** (use a real contact's first name).
Expected the first time: Heylana asks for access to contacts; allow it. Then Messages opens
on that person with "I'm on my way" written, and Heylana says "Your message to … is ready.
Check it and tap send." **Don't send it**; delete the draft. "message my brother I'm
outside" works the same way if a contact is saved as your brother (by name, nickname, or
on your own contact card).

## 6. The Kamino earn thing (1 chat)

Open the **Wallet**, tap the disc, type **what does the Kamino earn thing do**, tap ask.

Expected: a short answer from the real screens — your USDC goes into Kamino's lending
market, the rate moves, you can withdraw any time, and to start it's **Start** under Earn.

## 7. The eval (20 chat)

On the Mac, in the repo: `python3 scripts/eval.py`

Expected: a table of 20 questions, every row **PASS**, ending "20 of 20 passed, 20 /chat
calls". The **cache r/w** column shows numbers on the screen and send rows (the task model
reading its cached prompt) and 0/0 on the chat and lesson rows (too short to cache). The
eval signs in as its own throwaway wallet; its key is in `scripts/eval/.state.json`.

## Put it back

Menu → hold **Start buddy** to stop it, if you want it off. Switch the Wallet back to
devnet if you moved it (Wallet → the wallet icon → gear → Network → Devnet → Continue).
