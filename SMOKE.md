# Smoke test — Deepgram Aura voice

For Minos, on the Seeker.

**Live calls this test spends: 1 chat, 3 tts and 1 Deepgram listen** — the question and its
spoken answer, plus one short sample line for each voice you tap in the picker.

## 0. Set up

1. **Redeploy the worker**: `cd worker && npx wrangler deploy`. `wrangler.toml` now has
   `VOICE_PROVIDER = "deepgram"`; the Deepgram key is the one the ears already use.
2. Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**.
3. On the computer: `adb logcat -s HeylanaState`, and in `worker/` `npx wrangler tail`.

## 1. One voice question

From the home screen, hold the disc, say **"tell me a joke"**, let go.

Expected: the answer is spoken once, in a warm woman's voice (Aura's Hera). `wrangler tail`
shows a `"route":"tts"` line with `"provider":"deepgram","voice":"aura-2-hera-en"`. Logcat
shows `speak: speaking=true` then `false`, and no `voice_failed`.

## 2. The picker

Heylana → Settings → **Voice**. Expected: two choices, **Hera** and **Aries**. Tap
**Aries**: a short line in a man's voice. Tap **Hera**: the same line in a woman's voice.

Scroll to **What leaves the phone**: it says "The spoken answer text goes to Deepgram to
become speech."

To go back to Gemini: set `VOICE_PROVIDER = "gemini"` in `wrangler.toml` and redeploy; the
picker and the privacy line follow the next time Settings opens.
