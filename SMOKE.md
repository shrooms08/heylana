# Smoke test — buddy chat, the action catalogue, teaching mode, new skills

For Minos, on the Seeker, on the **Judge** plan.

**Live calls this test spends: 13 chat and 15 tts** if every step is typed (holding the disc
and speaking adds one Deepgram listen per question). Chat per step: 1 each for steps 1, 2,
3, 5, 6, 8; 2 for step 4; 1 for step 7 ("turn it off" asks nothing); 4 for step 9. The
recap in step 9 makes two model rounds inside the worker (the lookup, then the answer). An
answer over its word cap may add one small "shorten" call, which is not a talk.

## 0. Set up

1. **Redeploy the worker first** — the model is only offered the new actions after it:
   `cd worker && npx wrangler deploy`.
2. For step 3, install **Spotify** from the Play Store and sign in (it was not on the test
   phone). For step 4, have something playing (Spotify or YouTube Music).
3. Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**.
4. On the computer: `adb logcat -s HeylanaState`.

## 1. Chat, no screen read

Go to the home screen. Tap the disc, type **how's your day going**, tap **ask**.

Expected: a natural one-liner, spoken. Logcat shows `ask: screen not read why=chat` and
`brain: mode=quick why=chat`, and no `screen elements=` line for this question.

## 2. YouTube search

Tap the disc, type **search skateboarding videos on youtube**, tap **ask**.

Expected: YouTube opens on the results for skateboarding videos; Heylana says "Searching
YouTube for skateboarding videos." Logcat: `action: fired intent=youtube_search`.

## 3. Spotify

Tap the disc, type **play Burna Boy on Spotify**, tap **ask**.

Expected: Spotify opens and plays Burna Boy (or shows him, if Spotify chooses to).
Without Spotify installed Heylana says "Spotify isn't installed on this phone."

## 4. Music controls

With music playing: tap the disc, type **pause the music**, tap **ask**. The music pauses and
Heylana says "Paused." Press play in the music app again, then tap the disc, type **next
song**, tap **ask**: it skips to the next song.

## 5. A text, never sent

Tap the disc, type **text 0800 123 4567 I'm on my way**, tap **ask**.

Expected: Messages opens on 0800 123 4567 with "I'm on my way" (or "I am on my way") typed
in. **Nothing is sent.** Delete the draft; do not tap send.

## 6. A reminder

Tap the disc, type **remind me to call mum at 6**, tap **ask**.

Expected: Calendar's new-event screen, titled "call mum", at the next 6 o'clock (6 PM if it
is between 6 AM and 6 PM now), for 30 minutes. Heylana says "Reminder for 6 PM today. Check
it and tap save." Tap the X to discard.

## 7. Flashlight

Tap the disc, type **turn on the flashlight**, tap **ask**: the torch comes on. Then tap the
disc, type **turn it off**, tap **ask**: the torch goes off, with no thinking pause.
Logcat: `action: follow-up intent=flashlight state=off model=not_asked`.

## 8. Wi-Fi settings

Tap the disc, type **open wifi settings**, tap **ask**. Expected: the Wi-Fi settings page.

## 9. Teaching mode and the recap

1. Open the **Wallet** app. Tap the disc, type **teach me how to swap**, tap **ask**.
   Expected: step 1 starts with a short reason, then what to tap (for example "Swaps live on
   the Swap tab, so tap Swap."), with a box around it. Under 25 words.
2. Do the step (or tap **Next**). Step 2 appears, also with a reason.
3. Tap the disc, type **why**, tap **ask**. Expected: the reason for step 2 and the step
   again, spoken; the step box stays. Logcat: `why=teach_why`.
4. Tap **Done** (or finish the swap in Seed Vault yourself, if you want a real one).
5. Tap the disc, type **what did I just do**, tap **ask**. Expected: a short recap of the
   steps; if a swap went through, what the wallet's recent activity shows. Logcat:
   `why=recap on_chain=true`.

## 10. Skills

Settings → **Skills**. Expected: **x402 payments**, **YouTube** and **Spotify** are listed
among the built-ins (8 in all), switched on.
