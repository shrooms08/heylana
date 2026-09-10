# SMOKE TEST — the proxy, the ears and the voice

The big one. Every key has moved off the phone and into a small server of your
own, Heylana has proper ears that can hear "Kamino", and a voice of her own
instead of the phone's.

**You have to put the server up first.** That is step 1, and nothing else works
until it is done.

## Budget for this test: 4 chat, 4 tts, 3 stt

I sent nothing to anything. Everything I could check, I checked against a stub
running on my machine that answers the same three routes with fixed replies.

| Step | chat | tts | stt |
|------|------|-----|-----|
| 1 put the server up | 0 | 0 | 0 |
| 2 a typed question | 1 | 1 | 0 |
| 3 hold-to-talk in the wallet | 1 | 1 | 1 |
| 4 Archie | 1 | 1 | 1 |
| 5 both fallbacks on | 1 | 1 | 1 |
| 6 aeroplane mode | 0 | 0 | 0 |

Steps 4 and 5 each also play a **three-word sample** when you pick a voice —
that is one more tiny tts call each, and it is included above.

---

## 1. Put the server up — 0 calls

Open **worker/README.md** and follow it top to bottom. It is six steps: sign in,
make the counter store, put in the three keys, put in the Deepgram project id,
deploy, and copy the address it prints into `local.properties`.

Then rebuild and reinstall the app.

You should see, opening Heylana: **three rows, not four.** The API key row is
gone — the key is in your server now, not on the phone. **Start buddy** works
once the first three are ticked.

If **Start buddy** works but every question comes back saying Heylana is not set
up, the address did not make it into `local.properties`. Check for a typo, then
rebuild.

---

## 2. A typed question — 1 chat, 1 tts

Open **Chrome** on any page. **Tap the buddy**, type **what is the capital of
japan** and tap **ask**.

You should see **Tokyo** in the strip, and hear it **read out in Skylar's voice**
— a real voice, not the phone's.

**To prove it went through your server and not straight to Anthropic**, from the
Mac:

```
$HOME/Library/Android/sdk/platform-tools/adb logcat -s HeylanaTokens HeylanaState
```

You should see `mode=quick`, `first_byte_ms=…`, and `voice=cartesia`. You should
**not** see `api.anthropic.com` anywhere. In another terminal, `npx wrangler
tail` from `worker/` shows the same request arriving at your server.

---

## 3. Hold-to-talk in the wallet — 1 chat, 1 tts, 1 stt

Open your **wallet**. **Press and hold the buddy** and say:

**"what does the Kamino earn thing do"**

While you are speaking: the words should **appear in the capsule as you say
them**, and the ring around the disc should **breathe with your voice**.

The important part: the transcript should say **Kamino**, spelled properly. That
is the whole point of the new ears — the phone's own recogniser writes it "come
in oh". Heylana tells Deepgram to expect it, along with the names of the buttons
actually on your screen.

Then the answer is **spoken in Skylar's voice** and nothing is left on screen but
the purple box, if it pointed at something.

In the log: `ears=deepgram` and `voice=cartesia`.

---

## 4. Archie — 1 chat, 1 tts, 1 stt (+ a sample)

**Settings → Voice → Archie.** You should hear **three words in Archie's voice**
the moment you tap it.

Go back, **hold the buddy** and say **"where is the seed vault"**.

The answer should come back in **Archie's voice**, not Skylar's.

---

## 5. Both fallbacks on purpose — 1 chat, 1 tts, 1 stt

**Settings → Force phone ears ON, Force phone voice ON.** Both are near the
bottom, in the debug section.

**Hold the buddy** and say **"how do I swap"**.

It should **still work**, start to finish — the words still appear as you speak,
the answer still arrives and is still read out. It will sound like your phone
rather than like Heylana, and the transcript may spell names worse. That is the
point: when Deepgram or Cartesia cannot be reached, the user gets an answer
anyway and is never told why.

In the log: `ears=android` and `voice=android`. Those two are **free** — no stt
or tts call reaches your server. The chat call still does.

**Turn both switches back off afterwards.**

---

## 6. Aeroplane mode — 0 calls

**Turn aeroplane mode on.** Tap the buddy, type anything, tap **ask**.

You should see a **clear line saying Heylana could not be reached**, and the disc
should go back to resting. No spinning forever, no crash.

**Turn aeroplane mode off again.**

---

## Two things to watch for, because I could not check them

- **Whether Deepgram hears the names.** I can drive a hold with a cable but I
  cannot speak into the phone, so every word of step 3 and 4 is unproven. If the
  capsule stays empty while you talk, say so — and say whether the log said
  `ears=deepgram` or `ears=android`.
- **Whether Skylar and Archie sound right.** I have never heard either: my stub
  plays a tone. If a voice is wrong, or the audio stutters or cuts off early,
  say which voice and roughly where it broke.

---

## What to do if something goes wrong

- **"Heylana is not set up yet"** — the address is missing from
  `local.properties`; step 1 again.
- **"That is all Heylana can do today"** — the day's cap. It resets at midnight
  UTC, and `npx wrangler tail` shows which route ran out.
- **A question works but nothing is ever spoken** — say whether the log said
  `voice=cartesia` or `voice=android`, and whether the mute speaker icon on the
  pane is on.
- **The words never appear while you hold** — say what the log said after
  `listen: opening`.
- **It answers in the phone's voice when the switches are off** — that is the
  1.5 second fallback firing, which means the server was slow. Say what
  `first_byte_ms` said.

---

## Pass criteria

- Onboarding has three rows and no API key.
- A typed question is answered and spoken, through your server, with no sign of
  api.anthropic.com on the phone.
- Holding and speaking fills the capsule live and spells Kamino properly.
- Skylar and Archie are different voices, and picking one plays a sample.
- With both debug switches on it still works, in the phone's own ears and voice,
  and says so in the log.
- Aeroplane mode says so plainly and returns to idle.
- Nothing crashes, and the test costs 4 chat, 4 tts and 3 stt calls and no more.
