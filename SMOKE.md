# Smoke test — teaching continues, jokes vary, texting a contact

For Minos, on the Seeker. **Claude Code already ran each of these live on the Seeker (18
`/chat` calls); this is the final confirmation.**

**Live calls this test spends: about 7 chat, 8 tts** — two or three for the teaching session,
three jokes, two texts.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**.

## 1. Teaching carries on past the first tap

Open the **Wallet**. Tap the disc, type **teach me how to swap**, tap **ask**.

Expected: step 1 points at Swap, and the disc and its words sit **clear of the Swap button**
(below it). Tap **Swap**. The swap screen opens and, after the line, **step 2 comes** — the
session does not end. Carry on or tap Done / say "stop"; the disc flies back to its edge.
Never approve anything in Seed Vault.

## 2. Jokes vary

Stop and start the buddy (Heylana → Stop buddy → Start buddy) between each: tap the disc, type
**tell me a joke**, three times. Expected: three different jokes.

## 3. Texting a contact

Tap the disc, type **text Ada I'm on my way**, tap **ask**.

Expected the first time: Android asks whether Heylana may read your contacts — **Allow**. (That
first time Messages opens with the words, for you to pick Ada.) Ask again: Messages opens **on
Ada** with "I'm on my way" typed in. Nothing is sent — clear the words before leaving.
If two contacts fit "Ada" equally, Heylana asks which one.
