# Smoke test — polish-10-skills, second pass (placement, pointer, chips, logs)

For Minos. Heylana now stands clear of whatever she points at, points at what her words tell
you to do, puts no developer pages under everyday questions, and notices your tap in apps that
used to hide it from her (the Wallet, the dApp Store, Jupiter's keypad). Install the app, run
`./scripts/a11y.sh` once, **wait five seconds**, then Menu → hold **Start buddy**.

**Live calls this test spends: about 9 chat, 9 spoken lines. Mainnet apps: never approve
anything. In Jupiter, the green Swap button goes straight to a confirm request — cancel it.**

---

## 1. dApp Store: a one-tap step waits for you (1–2 chat)

1. Open the **dApp Store** on its Home tab (the house icon at the bottom).
2. Hold the disc and say **where are the dApp Store settings**.
3. A blue ring goes round the **gear** at the top right. Heylana's card sits under it, never on
   it. The card says **step 1**.
4. Wait until she stops speaking. Nothing moves on by itself.
5. Tap the **gear**. Settings opens (your tap went to the dApp Store), and within about two
   seconds Heylana finishes and flies home.

If she answers without "step 1" and flies home at once, that is the model answering in one go.
Ask again once.

## 2. dApp Store: installing, and no developer chip (1 chat)

1. Back on the dApp Store's Home tab (the arrow at the top left, then the house icon).
2. Hold the disc and say **how do I install an app**.
3. The ring lands on a real **Install** button (the featured app's). Her card is clear of it.
4. **Under her answer there is no chip**, and in particular nothing like "Submit a New App".
5. Don't tap Install unless you want the app.

## 3. Wallet: Earn (1 chat)

1. Open the **Wallet**, home screen. If a "Receive crypto" sheet is half open at the bottom,
   press Back once to close it.
2. Hold the disc and say **how do I earn on my USDC**.
3. The ring goes round **Start** on the "Earn …% on your USDC" row. The rate she says matches
   the screen. Her card is above Start, not on it.
4. Tap **Start**. The Wallet opens its next page (Receive, if the Wallet has no USDC).
   **Stop there.**

## 4. Jupiter: the walk (3–4 chat)

1. Open **Jupiter**, unlock it, tap **Home** at the bottom. If the Trade screen still has an
   amount in it, go to Trade, tap **CLEAR**, then back to Home.
2. Hold the disc and say **teach me to swap 0.0009 SOL to USDC**.
3. Step 1: the ring is on **Trade** in the bottom bar. Tap it. She moves on by herself.
4. Step 2: the ring is on the **Sell amount**, not on Market. Tap it and type **0.0009** on
   Jupiter's keypad. About two and a half seconds after your last key she moves on.
5. Step 3: she tells you to tap the green **Swap** button, and the ring is on the **green
   button** (on its word "Swap"), never on the "Swap" tab at the top.
6. **Stop here.** Tapping green Swap goes straight to a confirm request. If you tap it, cancel.

## 5. Nothing on screen goes into the log

In Android Studio's Logcat, filter on `HeylanaState` while you do any of the above. Lines
about a new window say `glance: window pkg=… title_chars=…`: an app name and a number, never
the window's title. Nothing you can read on the phone's screen appears in the log.

## 6. Balances say their network (1 chat)

1. Open the **Wallet**, home screen.
2. Hold the disc and say **what's my USDC balance**.
3. Her answer says the amount **on devnet** and that the Wallet's own numbers are mainnet — once,
   not twice. No link chip under it.
