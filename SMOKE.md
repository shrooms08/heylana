# Smoke test — teaching that moves, and four fixes

For Minos, on the Seeker.

**Live calls this test spends: 6 chat, about 10 tts and 6 Deepgram listens** if every step
is asked by voice. Step 1 speaks one sentence per segment (up to 4 tts), the rest are one
each. One more chat for any reply that has to be asked for again.

## 0. Set up

1. **Redeploy the worker**: `cd worker && npx wrangler deploy`. The model is only offered
   the segmented answer and the message action after this.
2. **Sign in to Spotify** on the phone (it is installed but sitting on its login screen).
3. Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**.
4. On the computer: `adb logcat -s HeylanaState`.

## 1. Teaching that moves

Open the **Wallet** app, on its home screen. Tap the disc, type **teach me how to swap**,
tap **ask**.

Expected: Heylana explains in two to four short sentences, and **as each sentence is
spoken the disc flies over to the thing it names**, in an arc, and stands beside it. A
purple ring breathes around that element, and the words sit beside the disc. When the last
sentence ends the disc flies back to the edge and the strip melts away.

Logcat: `reply: segments=…`, then `teach: run segments=…`, a `teach: flight …ms` per hop,
and `teach: run done, flying home`.

If the answer comes back as one sentence with no flight, that is the model choosing not to
use segments — ask again with "teach me how to swap, step by step".

## 2. YouTube

Tap the disc, type **search cooking videos on youtube**, tap **ask**.

Expected: YouTube opens **on the results for cooking videos**, not on its home screen.

## 3. Spotify

Tap the disc, type **play Burna Boy on Spotify**, tap **ask**.

Expected: Spotify opens on Burna Boy, and Heylana says "Opened Spotify for Burna Boy. Tap
play." Tap play yourself. (No Android intent can make Spotify start playing; see the note
in PRODUCT.md.)

## 4. A text

Tap the disc, type **text 0800 123 4567 I'm on my way**, tap **ask**.

Expected: Messages opens on 0800 123 4567 with the message typed in. Nothing is sent.
Delete the draft.

If it fails, **send me the Logcat lines that start with `action:`** — they now say which
check refused it and by how much, without the words themselves.

## 5. A reminder, saved

Tap the disc, type **remind me to call mum at 6**, tap **ask**.

Expected the first time: Android asks whether Heylana may use your calendar. **Allow it.**
That first reminder opens the calendar's new-event screen (tap save, or discard it).

Ask again: **remind me to call mum at 6**. Expected: nothing to tap — Heylana says
"Reminder saved for 6 PM today." Check the Calendar app: the event is there, at the next
6 o'clock, with a ten-minute nudge.

## Optional

Settings → Debug states → **teaching flight**: three sentences across three stand-in
elements, the disc arcing between them with a ring around each.
