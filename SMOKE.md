# SMOKE TEST — the screech, the ears, and one capture for me

Three fixes. Two of them I could test properly; the third — whether Skylar still
screeches — needs your ears and a file back from you.

## Budget for this test: 3 chat, 3 tts, 3 stt

I sent nothing to anything. The playback fix is covered by tests over the awkward
chunk lengths a socket really delivers, and the rest against a stub on my machine.

| Step | chat | tts | stt |
|------|------|-----|-----|
| 1 capture a stream | 1 | 1 | 0 |
| 2 hold-to-talk, Kamino | 1 | 1 | 1 |
| 3 hold again within a minute | 1 | 1 | 1 |

**One thing to know about the count:** the listening key is now borrowed the
moment you *touch* the buddy, not when you hold it, so an isolated tap can also
mint one. It is kept for ninety seconds, so anything you do inside a minute and a
half is free. If the stt count comes out at four or five because you tapped
around between steps, that is expected.

**Before you start:** install, run `./scripts/a11y.sh`, close Heylana from
recents, reopen it, tap **Start buddy**. The second launcher icon (Heylana Glass)
is gone — that is fix 3, and there is nothing to check for it beyond noticing its
absence.

---

## 1. Capture a spoken answer — 1 chat, 1 tts

**Settings → Save last tts stream → ON.** It is in the debug section, near Force
phone ears.

Go back. Over **Chrome**, tap the buddy, type **tell me about seed vault** and
tap **ask**.

**Listen carefully to the whole answer.** What I am asking is whether the screech
is gone: a harsh buzzing tone under or instead of the voice, from the first
syllable to the last.

Then, from the Mac:

```
$HOME/Library/Android/sdk/platform-tools/adb pull \
  /sdcard/Android/data/xyz.heylana.app/files/tts_capture.pcm \
  design/refs/tts_capture.pcm
```

**Hand me that file** and tell me what you heard. It is raw 16-bit mono at
24000Hz — the exact bytes that came off the network, before the phone played
them — so if it is clean in the file and dirty out of the speaker, that tells us
which half is broken. It is gitignored, so it stays on your machine.

While you are there, the log is worth a look:

```
$HOME/Library/Android/sdk/platform-tools/adb logcat -s HeylanaState HeylanaTokens
```

- `voice: pcm rate=24000 channels=1 bits=16 buffer_ms=… pre_roll_ms=300` — what
  the phone built to play into.
- `voice: played …ms underruns=0 leftover_bytes=0` — **underruns above zero
  means the speaker ran dry and you would have heard gaps**. Tell me the number.

**Turn Save last tts stream back off afterwards**, or every answer overwrites the
file.

---

## 2. Hold-to-talk, and the Kamino test — 1 chat, 1 tts, 1 stt

Open your **wallet**. **Press and hold the buddy** and say:

**"what does the Kamino earn thing do"**

Three things to check, all in the log:

- **`ears=deepgram after=…ms keyterms=16`** — the good ears won, and how long
  after you first touched the disc they were ready. If instead you see
  `ears=android reason=… after=…ms`, **tell me the reason and the number**; that
  is exactly what the reason codes are for. `token_timeout` and `socket_timeout`
  mean different fixes.
- **The transcript in the capsule spells Kamino**, not "come in oh". That is the
  whole point of the keyterms.
- The words appear **as you speak**, not in one lump at the end.

---

## 3. Hold again, straight away — 1 chat, 1 tts, 1 stt

Within a minute of step 2, **hold the buddy again** and say **"where is the seed
vault"**.

You should see `proxy: stt-token from cache` in the log, and `ears=deepgram
after=…ms` with a **smaller number than step 2** — the key was already borrowed,
so only the socket had to open.

---

## What I could not check

- **Whether it still screeches.** My stub plays a tone, not a voice, and I cannot
  listen to the phone. The fix is real — an odd-length chunk was shifting every
  sample after it by one byte, which is exactly what a screech sounds like — but
  whether it was the only cause is your ears and that capture file.
- **Whether Deepgram engages now.** Exercising the socket means connecting to
  Deepgram, which I do not do. The URL it builds is covered by tests down to the
  repeated `keyterm=` parameters; the timing is not.

---

## What to do if something goes wrong

- **Still screeching** — send the file and say whether the file itself sounds
  clean on the Mac.
- **Gaps or stutters rather than a screech** — quote the `underruns=` number.
- **`ears=android` every time** — quote the reason and the `after=` number.
- **The transcript is empty** — say whether the log showed `deepgram: socket
  open` and what came after `listen: opening`.
- **Nothing is spoken at all** — quote the `voice=` line.

---

## Pass criteria

- A spoken answer plays cleanly from the first syllable to the last, with
  `underruns=0` and `leftover_bytes=0`.
- `ears=deepgram` on both holds, and the second is quicker than the first.
- Kamino comes back spelled properly.
- No second launcher icon, and nothing labelled or measured anywhere.
- Nothing crashes, and the test costs 3 chat, 3 tts and about 3 stt.
