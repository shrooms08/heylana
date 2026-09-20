# Smoke test — polish-7-lookout

For Minos. This one is about the signing moment: what a transaction really does, whether
you have dealt with an address before, and the buddy noticing a scam screen on its own.
The server is deployed already. Install the app, then run `./scripts/a11y.sh` once.

**Live calls this test spends: 2 chat, 2 spoken answers, a handful of ear passes, and up
to about eight short spoken warnings the first time (they are then kept on the phone).**
Devnet. **Never tap Confirm, and never tap Pay.** Stop at "Simulation passed".

---

## 1. The buddy wakes at a signing screen (no live calls)

1. Menu → **Start buddy** (hold the round button). Press **Home**.
2. With the phone plugged in: `adb logcat -s HeylanaState | grep watch=` — it says
   `watch=signing on`, and a moment later `lookout: list updated domains=…`.
3. **The Wallet's confirm sheet.** Open the Seed Vault Wallet and start any send (devnet),
   far enough to reach the confirm screen — **do not approve it**. She should speak **as the
   screen appears**, before you could tap Approve: "Careful: something is asking for your
   signature." The strip follows a moment later with the amount, who it is for and what it
   does. She says a second sentence only if it is worse than a transfer — an approval, a
   handover, a close.
   - The spoken sentence is short on purpose ("Something here wants your signature.", or
     "Careful: this is an approval, not a transfer."). The amount and the address are on the
     strip, where you can read them.
   - It must **not** dim the screen or cover the Approve button — you can still tap
     anything on the wallet's screen.
   - It says it **once**. Leave the sheet and come back: the same request stays quiet.
   - Mute Heylana (the speaker icon on Home) and do it again: the line appears, nothing is
     said.
   - Back out of the send. The line goes.

   **How quickly.** With the phone plugged in:
   `adb logcat -s HeylanaState | grep -E "window_event_ms|first_audio_ms|look_done_ms"`.
   Three numbers per signing window, all from the moment the window appeared: when the voice
   was asked for, when the look finished, and when the first word was heard. Mine, over
   three windows: asked at **9–17ms**, look done at **20–27ms**, first word at **43–56ms**.
   If `first_audio_ms` is ever over 300, tell me.
4. Tap the buddy while that line is up: the box opens as usual and you can ask about the
   screen. That is where it talks.

**If you have no send to hand**, the same wake can be played without a wallet:

```
adb shell am broadcast -a xyz.heylana.app.debug.PANEL --es glance signing
adb shell am broadcast -a xyz.heylana.app.debug.PANEL --es glance secret
```

## 2. "No real Solana app asks for your recovery phrase" (no live calls)

1. Make a page that asks for one. Anything works — a note in a text editor is not enough,
   it has to be a page in a browser. The quickest:
   - on the Mac, `cd /tmp && printf '<h1>Wallet sync</h1><p>Enter your 12-word recovery phrase to restore access</p><input>' > seed.html && python3 -m http.server 8099`
   - on the phone, `adb reverse tcp:8099 tcp:8099`, then open Chrome and go to
     `http://127.0.0.1:8099/seed.html`.
2. As the page appears the buddy shows, unasked, with a **heads up** chip: *"No real Solana
   app asks for your recovery phrase. If you type it in, whoever is asking can take
   everything."* — **and says the first sentence of it out loud.**
3. It says it **once** for that page. Reload it: the line comes back, the voice does not.
4. Open Phantom or Solflare and go to their own "import recovery phrase" screen: **no
   warning**. A wallet asking for your phrase is what a wallet is for; only everything else
   is warned about.
5. Settings → **Watch signing screens** → off. Reload the page: nothing at all. Turn it
   back on.

## 3. Prepare a send to a brand new address (2 chat, devnet)

1. Menu → **Memory**: the switch must be **on** (the first-time check is memory's).
2. Make an address you have never sent to. Any devnet address will do — ask a friend, or
   use the one the eval script prints:
   `python3 scripts/firstcheck.py` (it spends no talks and signs nothing).
3. Hold the buddy and say, or type in the box: **"send 0.01 SOL to \<that address\>"**.
4. The strip goes up, says it is checking with the network, and then shows the confirmation
   with, underneath it:
   - **• First time you have sent to this address.**
   - **• Sends 0.01 SOL to \<short address\>.** — that second line is read back out of the
     bytes that were actually built, not out of what was asked for.
5. **Tap Cancel.** Do not tap Confirm.
6. Ask the same thing again for an address you *have* sent to before: no first-time line.

## 4. Pro is $5 or $40, and the Plan row opens (no live calls)

1. Menu → tap the **Plan** row itself. It opens the plan — what you are on, how much of it
   is left, and a Go Pro button. (It used to do nothing at all.)
2. Menu → Settings → scroll to **Debug** → **Simulate Free plan** → on.
3. Menu again: the Plan row now says **Free**, "30 talks a month · $5 a month, or $40 a
   year", with a talks bar and **Go Pro**.
4. Tap **Go Pro**: the sheet offers **Monthly $5** and **Yearly $40** with "Save $20", in
   USDC or SKR. **Close it — do not tap Pay.**
5. Turn **Simulate Free plan** back off; the Plan row says Judge again.

## 5. Home (no live calls)

- The six chips read: **What am I signing?**, **Check my balance**, **Explain this
  screen**, **Learn Solana**, **Set a timer**, **Play a song**. The first and third start
  the buddy (the app itself never reads a screen).
- With the buddy running, under "What do you need?" it says **"Watching for signing
  screens."**
- Menu → **Privacy** sits above **Advanced**, and the Privacy screen has two new lines:
  what watching signing screens means, and that the phishing list is checked on the phone.
- The week's card leads with the sends: "This week: 2 sends checked, 1 stopped before
  signing, …". (Settings → Debug → **Seed the week's card** if yours has none yet.)

## 6. Rotate the phone twice (no live calls)

1. With the buddy docked at the right, turn the phone to landscape and back.
2. The disc is still on screen, on the same edge, about as far down it as it was.
   `adb logcat -s HeylanaState | grep re-docked` says
   `overlay: re-docked after a turn side=right x=… y=…`.
   (It used to end up off the side of the screen and stay there.)

## 7. Ten holds, no ear failures (no chat calls if you say nothing)

1. Hold the buddy and let go without saying anything, ten times, a couple of seconds apart.
2. `adb logcat -s HeylanaState | grep -c token_refused` → **0**.
   It used to be about one hold in three, and the ear that lost its pass sat out the race.

---

### What to tell me

- Any moment the buddy woke up when it should not have — on a screen that is not a signing
  screen, or twice for the same one.
- Any warning that reads like a verdict. They should all say what was found and "check the
  address bar", and never the word safe.
- Anything it said aloud that was not one of the three warnings. Those are the only things
  it may say unasked; an answer still waits to be asked for.
- Any warning that arrived after you had already tapped, or that you did not hear at all.
