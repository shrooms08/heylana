# Smoke test — teaching sessions, ears, forgiving actions, voice cap

For Minos, on the Seeker.

**Live calls this test spends: about 12 chat, 14 tts and 9 Deepgram listens.** Step 1's
walk-through is one chat per step (3 or 4) plus one tts each; step 2 is five holds (three
chats — "turn it off" asks nothing, and "what's the time" may not either if it is answered
from the screen — five tts); step 3 is two chats and two tts. One more chat for any reply that
has to be asked for again.

## 0. Set up

1. **Redeploy the worker**: `cd worker && npx wrangler deploy`.
2. Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**.
3. On the computer: `adb logcat -s HeylanaState`.

## 1. Teaching session

Open the **Wallet** app. Tap the disc, type **teach me how to swap**, tap **ask**.

Expected:
- The disc flies to the first thing to tap and **stays beside it**, a purple ring breathing
  around it, the step's words in the strip beside the disc, starting with a short reason.
- There is **no Next** button; Done is there.
- **Tap the thing it is pointing at.** Without you touching Heylana, the ring flashes, and a
  moment later the disc flies to the next thing with the next step.
- When the swap screen is reached, Heylana says so, and **the disc flies back to its edge**.

Logcat: `teach: walk-through asked`, `teach: task started teaching=true`, a `teach: flight`
per step, `teach: step done by the user, advancing`, then `teach: session over, flying home`.

Run it again and part way through, hold the disc and say **"stop"** (or tap Done). Expected:
"Okay, stopping here." and the disc flies home.

## 2. Five short voice holds

From the home screen, hold the disc, say it, let go:

1. **"What's the time"**
2. **"Tell me a joke"**
3. **"Open the wallet"** — the Wallet opens.
4. **"Turn on the flashlight"** — the torch comes on.
5. **"Turn it off"** — the torch goes off.

Expected: each understood the first time. Logcat for each hold: `deepgram: finalize sent`, then
either `deepgram: final after_ms=… words=…` or `deepgram: no final reason=…` — if any says
`no final`, send me that line and the `ears=` line after it.

## 3. Forgiving actions

1. Tap the disc, type **message 0800 123 4567 I'm on my way**, tap **ask**. Messages opens on
   that number with the words typed in. Nothing is sent; delete the draft.
2. Tap the disc, type **remind me at 6 to call my dad**, tap **ask**. The reminder is saved
   ("Reminder saved for 6 PM today." — or, the first time, the calendar asks for permission
   and opens its own screen).

Both should work the first time. If one asks a question instead ("Who should I text?"),
answer it: that is the new behaviour for a missing part.

## If the voice goes quiet

If an answer is shown but not spoken, Logcat's `voice_failed reason=` now carries the
worker's own status, and over the day's limit the strip says "Voice is over its daily limit;
text only until tomorrow."
