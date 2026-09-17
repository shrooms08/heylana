# Smoke test — spoken answers say only what they should, once

For Minos, on the Seeker.

**Live calls this test spends: 3 chat, 3 tts and 3 Deepgram listens.** One more chat for
any reply that has to be asked for again (logged as `reply: unreadable … retry=once`), and
one small shorten call for any answer over 60 words (`answer: over cap`).

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**, go to the home
screen. On the computer: `adb logcat -s HeylanaState`.

## 1. Three voice chats

For each, **hold the disc**, say the words, let go, and listen to the whole answer:

1. **"How's your day?"**
2. **"Tell me a joke."**
3. **"What's the capital of Nigeria?"**

Expected for each:
- The answer is spoken **once**, with no sentence repeated.
- Nothing that sounds like instructions: no "say", "point at", "JSON", "reply with", "user
  asks", no curly brackets read out.
- Under 60 words: a sentence or two.
- Logcat has `brain: mode=quick why=chat`, then `speak: line chars=` with a number well
  under 300 (a 60-word answer is roughly 350 characters at the very most).
- If Logcat shows `reply: unreadable`, the next `reply: raw` line shows what the model sent;
  what you heard should then be the retried answer, or "I didn't catch that, say it again."
