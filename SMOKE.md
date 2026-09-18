# Smoke test — system UI skipped, memory, Solana lessons

For Minos, on the Seeker, with a wallet connected, the worker on **devnet** (deployed
with the memory routes). Claude Code ran every step below on the Seeker already (see
the report); this is the final confirmation.

**Live calls this test spends: 6 chat, about 8 tts, no ears** (unless you answer by voice,
which adds one ears call per answer). Nothing is sent or signed: never tap **confirm**
on a send strip, and never approve anything in Seed Vault.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open **Heylana**. Watch along with
`adb logcat -s HeylanaState` if you like.

## 1. The notification shade is never read (1 chat, 1 tts)

1. Menu → **Start buddy** on. Go to the home screen.
2. Pull the notification shade all the way down, so it covers the screen.
3. With the shade still down, ask the buddy anything by holding it, for example "what's on
   my screen?".

Expected: the answer never mentions your notifications or quick settings. In the log, a
line `screen: skipped pkg=com.android.systemui … why=system_ui`.

Push the shade back up. Menu → **Start buddy** off.

## 2. Remember, then delete (no chat, 1 tts)

1. Menu → **Memory**. If **Keep notes about me** is off, tap it on. The list says
   "Nothing kept yet…".
2. Back to Home. Type **remember that I'm new to solana** and tap the arrow.

Expected: the strip says **"Got it, I'll remember."** and Heylana says it. No "Thinking…".

3. Menu → **Memory**. Expected: one line, **I'm new to solana**, with "You said · <today>"
   under it and a bin on the right.
4. Tap the bin. Expected: the line goes; "Nothing kept yet…" comes back.

## 3. A lesson on PDAs (3 chat, 4 tts)

1. Back to Home. The chips now start **Ask about this screen**, **Learn Solana**. Tap
   **Learn Solana**.

Expected: a page titled **Learn Solana** with two lists, **Build** (10 topics, "The account
model" first) and **Infrastructure** (11 topics, "Validators, leaders, slots and epochs"
first), each with "4 short parts" or "5 short parts".

2. Tap **PDAs and seeds**.

Expected: back on Home; after a moment the strip shows **Lesson · PDAs, 1 of 5** above a
short explanation starting "Lesson on PDAs." that ends in one question. Heylana reads it
aloud.

3. Answer it **wrong** on purpose: type **it is shorter** and tap the arrow.

Expected: "Not quite…" and the same idea explained another way, with a new question. The
label still says **1 of 5**.

4. Answer it **right**: type **it has no private key** (or whatever the question really
   asks) and tap the arrow.

Expected: a word of praise, then the next part, with its question. The label says
**2 of 5**.

5. Type **stop** and tap the arrow.

Expected: at once, without "Thinking…": **"Stopping there. Recap: A PDA is an address
derived from seeds and a program id…"**. The Lesson label is gone.

6. Menu → **Memory**. Expected: one line, **knows PDAs, <today>**, with "Lesson · <today>".

## 4. Explain the docs in Chrome (2 chat, 2 tts)

1. Menu → **Start buddy** on. In Chrome, open **solana.com/docs/core/pda**, so the first
   paragraph ("Program Derived Addresses (PDAs) are 32-byte…") is in view.
2. Tap the disc, type **explain this**, tap **ask**.

Expected: a plain explanation of that paragraph (seeds and a program ID, no private
key). In the log: `docs: solana docs in front lens=explain`.

3. Tap the disc again, type **why**, tap **ask**.

Expected: one level deeper, not a repeat: why a PDA has to come out the same every time
(the program can find its own data again). In the log: `lens=deeper`.

## 5. Put it back

1. Menu → **Start buddy** off.
2. Menu → **Memory** → tap **Keep notes about me** off, if it was off before step 2.
   Expected: "Memory is off. Nothing is kept." (turning it off deletes every line).
3. Close the Chrome tab from step 4.
