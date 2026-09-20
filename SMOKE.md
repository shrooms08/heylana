# Smoke test — polish-6-value

For Minos. Three things changed: the price of Pro, a weekly card on Home, and how quickly
Heylana starts speaking. The server is deployed already; install the app as usual.

**Live calls this test spends: 4 chat, 4 spoken answers (they now come back together), 4
pairs of ears.** Nothing here signs anything. **Never tap Confirm or Pay.**

After installing from Android Studio, run `./scripts/a11y.sh` once.

---

## 1. Pro is $5 a month, or $40 a year (no live calls)

1. Open **Heylana**. Tap the **three lines** at the top left.
2. The **Plan** row: on Free it now reads **"30 talks a month · $5 a month, or $40 a
   year"**. (On your Judge account it still says "Unlimited until Nov 9" — that is right.)
3. Tap **Plan**, then **Go Pro**.
4. The sheet shows two buttons above the price: **Monthly** and **Yearly**.
   - **Monthly** selected: **$5**.
   - Tap **Yearly**: **$40**, and under it **"Save $20"**.
5. Tap **USDC** and **SKR** in turn. Each shows the amount to send for whichever of the two
   periods is selected, and the amount changes when you switch Monthly ↔ Yearly.
6. **Close the sheet.** Do not tap Pay.

## 2. The weekly card on Home (no live calls)

**The seeded card**

1. Menu → **Settings**, scroll to the bottom (debug section) → **Seed the week's card** →
   tap **Seed**. It takes you back to Home.
2. Home shows a card above the message bar: **"What I caught"** and one line —
   *"This week: 14 screens explained, 2 sends checked, 1 stopped before signing."*
3. Tap **See it all**. Every kind is listed with its count: screens explained,
   transactions explained, sends checked, stopped before signing, new addresses looked up,
   lessons finished, questions answered. **Check there is no address, no amount and
   nothing from any screen anywhere on it.**
4. Tap **Share**. The phone's share sheet opens with a picture of the card. Share it to
   yourself (or just look at the preview) and check the picture, too, has only numbers and
   what they count. Then come back.
5. Tap **Dismiss**. The card goes, and it does not come back on Home for the rest of the
   week.

**The real card**

6. Menu → **Memory**. If the switch is **off**, turn it on (hold the round button).
7. Ask Heylana two or three things (see part 3 below — those count).
8. Force-stop Heylana (or just leave and come back tomorrow) and open it again: the card is
   there with **your own counts** — mine read *"This week: 6 screens explained, 10 questions
   answered."* after a morning of testing.
9. Menu → **Memory** → turn the switch **off**. Go back to Home and re-open the app: **no
   card at all.** Nothing is counted while memory is off, and turning it off throws the
   week away. Turn it back on if you want it.

## 3. How quickly she starts speaking (4 chat, 4 spoken answers, 4 pairs of ears)

The change: she no longer waits for the whole answer before starting to talk. As soon as
she has finished a **sentence**, that sentence is on its way to your ear while the rest is
still being written.

1. Menu → **Start buddy** (hold the round button until it ticks). Press **Home**.
2. **Hold the disc** and ask **"tell me a short joke"**. Let go.
3. Listen: she should start talking about a second sooner than you are used to, and the
   first sentence should arrive before the whole answer is on the strip.
4. Do the same three more times — **"how are you today"**, **"what is the capital of
   Ghana"**, **"what should I have for lunch"**.
5. Each answer should be **one continuous line of speech** — no gap in the middle, nothing
   said twice, and nothing cut off. That is the thing to listen for; if a sentence is ever
   repeated or clipped, tell me.

**What I measured (Sept 20, on the Seeker, ten questions the old way and seven the new):**
from letting go of the disc to the first spoken word, **3.5s before, 3.0s after** at the middle.
The model's share of the wait went from about 1.8s to about 0.7s. It is **not** under the
1.5s the brief asked for — what is left is about 1s of ears (Deepgram deciding you have
stopped talking), half a second of making the audio, and a third of a second of Lagos to
Cloudflare and back. Cutting the ears further would cost accuracy, so I stopped and left
that decision to you.

To see the numbers yourself, with the phone plugged in:

```
adb logcat -s HeylanaState | grep speed:
```

One line per spoken answer, e.g.
`speed: ears_ms=969 brain_ms=2565 tts_first_byte_ms=1698 play_ms=7 rest_ms=0 total_ms=2674 trips=one`.
`total_ms` is what you feel. `trips=one` means the new way; `trips=two` means it fell back
to the old way (a send, a quick action, or a walk-through — all of those still speak the old
way on purpose).

## 4. Nothing else changed (1 of the chats above is enough)

- Ask **"what's my SOL balance"**: answers as before.
- Say **"set a timer for two minutes"**: the Clock opens as before, and she says one line.
- Both of those still speak the old way, and should sound exactly as they did.

---

### If something is wrong

- **An answer is said twice, or in two halves with a gap:** tell me which question. The log
  line `speak: already said as it was written` should appear exactly once per spoken answer.
- **She goes silent but the words are on the strip:** that is the voice failing, and it is
  meant to fall back like that. `adb logcat -s HeylanaState | grep voice_failed` says why.
- **No card on Home:** memory must be on and you must have a wallet connected; with memory
  off there is deliberately no card.
