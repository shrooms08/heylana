# Smoke test — flat screens and the new orb

For Minos, on the Seeker. Claude Code checked all of this on the Seeker already (1 `/chat`
call); this is the final confirmation.

**Live calls this test spends: 1 chat, 1 tts** — one question, to see the thinking orb.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open **Heylana**.

## 1. Home, resting

Expected: a pure black page, nothing glassy anywhere. At the top a menu icon, "Heylana ·
your buddy" and a speaker icon, all without circles behind them. In the middle a **ring of
coloured dots** slowly changing shape (purple, pink, orange and cyan, turning slowly) — no
logo inside it. Under it "Hi, <your name>." small and **What do you need?** large. At the
bottom two rows of flat grey pills, a flat grey **Message…** bar and the purple mic.

## 2. Home, thinking (1 chat, 1 tts)

Tap **Message…**, type **what is the tallest mountain in Africa**, tap send.

Expected: the ring turns into **dots flying round on tilted paths**, the words under it say
"One moment. / Thinking…", the name at the top says *thinking…*. Then the dots go back to
the ring, Heylana says the answer and it appears in a flat grey card under the orb. No light
runs round anything.

## 3. The menu

Tap the **menu** icon. Expected: a dark grey panel slides in from the left, full height,
over a dimmed Home. It lists, under small headings: **Buddy** — Start buddy with its switch;
**Account** — Plan (Judge until Nov 9, 2026) and Skill market (8 active); **More** —
Advanced, Privacy, Settings. Your name and short wallet are at the bottom. Tap the dimmed
part to close it.

## 4. The rest

Open the mic (tap it once): a small ring of dots sits at the top next to the back arrow,
the coloured wave below it, the flat pause and close buttons at the bottom. Tap **X**.
Menu → **Skill market**, **Advanced**, **Privacy**, **Settings**: flat grey rows with plain
icons, no glass, no glow. The buddy's disc over other apps is unchanged: still liquid glass.
