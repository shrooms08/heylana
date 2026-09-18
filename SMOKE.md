# Smoke test — teaching waits for you, one voice, mic pre-roll, message

For Minos, on the Seeker.

**Live calls this test spends: about 9 chat, 12 tts and 7 Deepgram listens** — the
walk-through is one chat and one tts per step (3 or 4), the five holds are three chats ("turn
it off" asks nothing) and five tts, and the message is one chat and one tts.

## 0. Set up

1. **Redeploy the worker**: `cd worker && npx wrangler deploy` (the action tool's wording
   changed).
2. Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**.
3. On the computer: `adb logcat -s HeylanaState`.

## 1. A teaching session that waits for you

Open the **Wallet**. Tap the disc, type **teach me how to swap**, tap **ask**.

Expected, on every step:
- The disc flies to the thing to tap and stays there, ring breathing. **Nothing moves on by
  itself**: wait 10 seconds without touching anything and it is still on the same step.
- The line is spoken **to the end** — never cut off — and there is **no screech** at the end
  of it or between steps.
- **Tap the thing it points at.** Only then does the next step come (after the line, if it is
  still being spoken).
- At the swap screen Heylana says so and the disc flies back to its edge.

Logcat per step: `step: armed`, then `advance reason=click` (or `content_changed` when the
screen really changed) — **no `advance` line before you tap**. Also `voice: queued length=…`
and never two lines playing at once.

## 2. Five short holds

From the home screen, hold the disc, say it straight away as you press, let go:

1. **"What's the time"**  2. **"Tell me a joke"**  3. **"Open the wallet"**
4. **"Turn on the flashlight"**  5. **"Turn it off"**

Expected: each understood first time, the first word included. Logcat per hold:
`deepgram: pre-roll kept_ms=… clipped_ms=0 …`. If `silenced_at_hold=true` appears, send me
that line: it would mean Android gave the microphone to the phone's own recogniser.

## 3. The message

Tap the disc, type **message 0800 123 4567 I'm on my way**, tap **ask**.

Expected: Messages opens on 0800 123 4567 with the words typed in, first time. Nothing is
sent; delete the draft. If it does not, send me the `action:` lines — they now say which
part failed.
