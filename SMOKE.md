# SMOKE TEST — both ears, and a disc that always comes back

## Budget for this test: 2 stt, 2 chat, 2 tts

I sent nothing to your worker and nothing to Deepgram or Anthropic. Everything
below was checked against the stub on my machine, including a listening socket
that accepts and one that refuses.

## What changed, in plain words

- **Both ears listen from the moment you hold the buddy.** Your phone's own
  recogniser and Deepgram start together, every time. If Deepgram has your words
  within a second and a half of letting go, those are used; if not, your phone's
  are. So a Deepgram problem no longer means Heylana hears nothing.
- **The disc always comes back.** However a hold ends — words, nothing heard, an
  error, running out of time — the disc returns to its resting look. The
  thinking ring that turned for five minutes cannot happen any more.
- **Twenty seconds, at most.** If anything gets stuck, after twenty seconds the
  capsule melts, a short "That took too long, try again." appears, and the disc
  rests.
- **Failures say what they were.** Instead of `socket failed code=0` the log now
  names the error, the HTTP status if there was one, and the start of the reply.

**Before you start:** install from Android Studio, run `./scripts/a11y.sh`
(it should say screen reading is on and running), reopen Heylana, tap **Start
buddy**. From the Mac, keep this running to watch:

```
adb logcat -s HeylanaState
```

---

## 1. Wallet balance, by voice — 1 stt, 1 chat, 1 tts

Open your **wallet**. **Press and hold the buddy**, say **"what is my balance"**,
and let go.

You should see and hear:

- the purple listening ring and the capsule while you hold;
- the answer **spoken aloud**, about what is actually on screen, with no number
  made up;
- the disc **back to its resting look** afterwards — no ring still turning.

In the log, find the line that starts **`ears=`**. It will say either:

- `ears=deepgram won reason=deepgram_in_time …` — Deepgram heard you; or
- `ears=android won reason=…` — your phone's recogniser was used, and the reason
  says why Deepgram wasn't.

**Send me that whole line either way.** If it says android, the lines just above
it starting `deepgram:` now say exactly what went wrong — send those too.

---

## 2. The Kamino test — 1 stt, 1 chat, 1 tts

**Hold the buddy** and say **"what does the Kamino earn thing do"**.

Check two things:

- the **`ears=` line** again — deepgram or android, and the reason;
- **how Kamino is spelled** in the capsule while you speak and in the answer.
  Deepgram is told to expect "Kamino"; your phone's recogniser is not, and tends
  to write "come in oh".

If the ears line says **deepgram** but Kamino is still misspelled, tell me — that
would mean the hint isn't reaching Deepgram. If it says **android**, the spelling
is expected to be wrong and the `deepgram:` lines above it say why.

---

## If it goes wrong

- **The disc is still turning after the answer, or after you let go without
  speaking** — send me the last 30 lines of the log.
- **"That took too long, try again."** — send the log from the hold onwards; the
  line `exchange: timed out in …` says what was stuck.
- **Nothing spoken, nothing on screen** — send the `ears:` lines.
- **The app closes itself** — send `adb logcat -d -b crash | head -40`.

---

## Pass criteria

- Both questions are answered and spoken.
- The disc is at rest after each one.
- Each hold has an `ears=` line with a reason.
- Kamino is spelled properly when the ears line says deepgram.
- Nothing crashes, and the test costs 2 stt, 2 chat and 2 tts.
