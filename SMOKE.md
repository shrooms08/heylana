# Smoke test — the app: first run, Home, voice, menu

For Minos, on the Seeker, on the **Judge** plan. Claude Code walked every screen here on the
Seeker already (10 `/chat` calls); this is the final confirmation.

**Live calls this test spends: 4 chat, 4 tts** — one question, the timer's question and
its answer, one joke. Everything else (sign in, the menu, the screens) costs nothing.

Never approve a payment. Approving the sign-in message in step 1 is fine: it is a message,
not a transaction.

## 1. First run

1. On the computer: `adb shell pm clear xyz.heylana.app`, then open **Heylana**.
2. Expected: a small glowing orb, **"Let's get you set up"**, and a **Sign in with wallet**
   button. Tap it. Seed Vault opens with a sign-in message: approve it.
3. Expected: the button turns into **"Wallet connected"** and a card slides in: **"What
   should I call you?"** Type your name, tap **Continue**.
4. Expected: **"A few switches"** with four rows: Show over other apps, Screen reading,
   Notifications, Microphone. Each says why in one line and shows a tick or **Allow**.
5. Tap each row that says **Allow**: the right Android page opens (or Android asks). Allow it,
   come back. Expected: that row now shows a tick. (For Screen reading, running
   `./scripts/a11y.sh` and coming back ticks it too.)
6. Tap **Continue**. Expected: **Home** — the orb, **"Hi, <your name>. What do you need?"**,
   two rows of suggestions, a **Message…** bar and a purple mic.
7. Close Heylana and open it again. Expected: it opens straight on Home.

## 2. A typed question (1 chat, 1 tts)

Tap **Message…**, type **what's the time in Tokyo**, tap the send arrow.

Expected: the pill says *thinking…*, the orb turns with a gapped ring and a "YOU ASKED" card
shows your words. Then Heylana says the time in Tokyo out loud, and it appears in a glass
strip under the orb, with a light running round the strip's edge while she speaks.
(`adb logcat -s HeylanaState` shows `app: ask … screen=not_read` and `why=chat`.)

## 3. The timer chip (2 chat, 2 tts)

Tap the **Set a timer** chip. Expected: Heylana asks **"How long should the timer be?"**
Type **for 2 minutes** and send.

Expected: she says the timer is set for 2 minutes, Heylana stays in front, and a Clock
notification shows a timer counting down. Delete the timer in the Clock afterwards.

## 4. Talking (1 chat, 1 tts)

Hold the **mic** on Home and say **"tell me a joke"**, then let go.

Expected: the voice screen opens as you press: a colourful wave across the top that moves
with your voice, a timer counting up, **LISTENING**, and a big purple mic between a pause
and a close button. When you let go it says **THINKING**, then the joke is spoken and shown
in a strip. (Tapping the mic instead of holding also works: tap, speak, tap again.)

Tap the **X**. Expected: back on Home.

## 5. The menu (no calls)

Tap the **menu** button (top left). Expected: a glass sheet grows in from the left over a
darker Home.

1. **Start buddy**: turn the switch on. Expected: the buddy's disc appears at the edge of
   the screen, over the app.
2. **Plan** says **Judge** and **Judge until Nov 9, 2026**. There is no Go Pro button.
3. Tap **Skill market**. Expected: Seed Vault Wallet, Kamino Earn, Seed Vault signing,
   Solana dApp Store, Jupiter, x402 payments, YouTube and Spotify, each with a switch and
   "Installed", and under **More skills** Chrome with a **Get** button. The top right says
   "8 of 10 active". Tap back.
4. Menu → **Advanced**. Expected: **Use my own key**, three providers — Anthropic ticked,
   OpenAI and Gemini greyed with **soon** — a key field, **Save key**, and "Held in the
   phone's keystore. It never leaves the device." Don't save a key. Tap back.
5. Menu → **Privacy**. Expected: "No pixels, ever", then the seven things that leave the
   phone — Anthropic, Deepgram, the voice, your wallet, payments, the install id, the
   Solana lookups (Helius, Jupiter, Bonfida). Tap back.
6. Menu → **Settings**. Expected: the two voices (tapping one says "Hi, I'm Heylana" in it),
   Dark glass and Light glass, the buddy switches, Permissions, Judge code, **Stop buddy**
   and the version at the bottom. Tap **Stop buddy**: the disc goes away.

## 6. Light and Dark

Settings → **Light glass**. Expected: the page turns light, the status bar icons turn dark,
and every word is easy to read. Visit Home, the menu, the voice screen (close it with X),
Skill market, Advanced, Privacy and Settings: all readable. Settings → **Dark glass** to go
back.
