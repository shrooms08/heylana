# SMOKE TEST — questions that don't need the screen

## Budget for this test: 2 chat, 2 tts

No stt: nothing here holds the buddy. I sent nothing to your worker's chat or
voice routes, and nothing to Anthropic. Everything below was checked against the
stub on my machine.

## What was wrong, in one paragraph

Heylana wasn't refusing because of a stale build or an empty screen. **The app was
crashing** — the new ears reported back from a network thread and started an
animation there, which Android punishes by killing the app. The screen reader
lives in the same app, so it died too, and Android then **stops starting it again
while still showing it as switched on**. Onboarding looked only at the "switched
on" part, so it ticked the row, and every question after that got "I can't read
this screen yet". The crash is fixed, the tick now means running, and a plain
question no longer needs the screen reader at all.

---

**Before you start:**

1. Install from Android Studio as usual, then from the project folder run:

```
./scripts/a11y.sh
```

It now **waits until screen reading is really running** and says so. If it prints
*"switched on, but Android has not started it"*, go to **Settings → Accessibility
→ Heylana**, turn it off and on, and run the script again.

2. Close Heylana from recents and reopen it. The **Screen reading** row should be
ticked. It is now only ticked when the reader is actually running — so if it ever
shows a dot while the switch is on, that is the new check being honest; its row
says to turn it off and on again.

3. Read the grey paragraph at the top. The last sentences should now read exactly:
*"Your voice goes to Deepgram to be transcribed while you hold the buddy. The
spoken answer text goes to Cartesia to become speech. The screen never goes to
either."* Same wording under **Settings → What leaves the phone**. No mention of
button names anywhere.

4. Tap **Start buddy**.

---

## 1. A general question over Heylana's own screen — 1 chat, 1 tts

Stay on the Heylana onboarding screen. **Tap the buddy**, type **what is the
capital of Nigeria** and tap **ask**.

You should see **Abuja** in the strip, and hear it read out.

This is the exact question that was being refused. Heylana's own screen often
gives the reader nothing to read, and that no longer matters for a question like
this.

---

## 2. A screen question over the wallet — 1 chat, 1 tts

Open your **wallet**. **Tap the buddy**, type **what is my balance** and tap
**ask**.

You should hear an answer that **names what is actually on the screen** — the
balance label, the token, where to look — and **does not make a number up**. If
the balance is visible it may read it; if it isn't, it must say so rather than
guess.

---

## If it goes wrong

- **"I can't read this screen yet" on a typed question** — that line no longer
  exists for typed questions. If you see it, the phone has an old build: reinstall.
- **The answer says to switch on screen reading, over the wallet** — screen
  reading isn't running. Run `./scripts/a11y.sh` and send me what it printed.
- **The app closes itself** — send me the output of
  `adb logcat -d -b crash | head -40`.
- **The row shows a dot though the switch is on** — turn Heylana off and on in
  Accessibility, then reopen the app.

---

## Pass criteria

- Abuja is answered and spoken over Heylana's own screen.
- The wallet answer describes the screen and invents no number.
- Onboarding and Settings carry the clean privacy sentence.
- `./scripts/a11y.sh` reports screen reading as running.
- Nothing crashes, and the test costs 2 chat and 2 tts.
