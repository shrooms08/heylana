# Smoke test — Heylana's voice is Gemini

For Minos, on the Seeker.

**Live calls this test spends: 3 chat, 5 tts and 3 Deepgram listens** — one chat, one tts
and one listen for each of the three voice chats, and one tts for each voice picked in
Settings (it says a short sample line). One more chat for any reply that has to be asked for
again (`reply: unreadable … retry=once`).

## 0. Set up (once)

1. Get a key from Google AI Studio and put it in the worker:
   `cd worker && npx wrangler secret put GEMINI_API_KEY`.
2. Redeploy: `npx wrangler deploy`. (No `VOICE_PROVIDER` needed: unset means Gemini.)
3. Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**, go to the
   home screen. On the computer: `adb logcat -s HeylanaState`, and in `worker/`
   `npx wrangler tail` to see the worker's lines.

## 1. Three voice chats

For each, **hold the disc**, say the words, let go, and listen to the whole answer:

1. **"How's your day?"**
2. **"Tell me a joke."**
3. **"What's the capital of Nigeria?"**

Expected for each:
- Spoken **once**, in the Gemini voice (a warm woman's voice, Sulafat, unless Settings says
  otherwise), with no sentence repeated.
- Nothing that sounds like instructions.
- Under 60 words.
- Logcat: `voice=proxy voice=skylar`, then `speak: speaking=true` … `speak: speaking=false`,
  and no `voice_failed`.
- `wrangler tail`: a `"route":"tts"` line with `"provider":"gemini","voice":"Sulafat"`, and
  a `"route":"tts_end"` line with `bytes` above zero.

If an answer is **shown but not spoken**, Logcat's `voice_failed reason=` says why
(`429 daily_cap`, `quota`, `timeout` or `error`); that is the intended behaviour, with no
phone voice.

## 2. The voice picker

Heylana → Settings → **Voice**.

Expected: exactly two choices, **Sulafat** and **Achird** — no "Skylar", "Archie" or
"Phone voice". Tap **Achird**: a short line is said in a man's voice. Tap **Sulafat**: the
same line in a woman's voice. Go back and hold the disc for one more question if you want
to hear the switch in an answer (one more chat, tts and listen).

Settings → Debug has no "Force phone voice" switch any more.
