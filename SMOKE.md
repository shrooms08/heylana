# Smoke test — polish-8-eval

For Minos. This one is about developer answers: Heylana now looks the answer up in her own
Solana library **before** she thinks, so the answer comes with the page it came from, and a
source chip under it. The server is deployed already. Install the app, then run
`./scripts/a11y.sh` once.

**Live calls this test spends: 5 chat, 5 spoken answers.** Nothing is sent, nothing is
signed, no wallet opens. Devnet.

---

## 1. A developer's question, with its page under it (2 chat)

1. Open Heylana. You are on Home.
2. Tap the **Message** box and type: `What does the Anchor init constraint do`. Tap the
   blue arrow.
3. Within about five seconds she speaks, and the strip under the orb shows the answer —
   three or four lines — with **a chip under it** saying something like
   **"Anchor docs: Anchor account…"**.
4. **Tap the chip.** The browser opens on that page. Come back to Heylana.
5. Type: `How do I derive a PDA in Anchor`. Same again: an answer, and a chip under it.

**What is new:** before this, most answers like these came back with no chip at all —
Heylana answered from memory and there was nothing to check her against. Every one should
now carry a chip. If an answer has no chip, that is worth telling me.

---

## 2. The answer beats the week's card (no live calls)

The card that says "What I caught this week" sits in the same place as the answer.

1. If the card is showing on Home, leave it there — **do not** tap Dismiss.
2. Ask anything at all (you can reuse an answer already on screen from step 1: just look).
3. The **answer** is what you see under the orb, not the card. Before this fix the card
   stayed and the answer was invisible — you only heard it.
4. Close Heylana and open it again without asking anything: the card is back.

---

## 3. The four facts that had moved on (2 chat)

Heylana was repeating numbers that stopped being true this year.

1. Type: `How long is a Solana slot`. She should say **300 milliseconds** — *not* 400.
2. Type: `How long is a Solana epoch`. She should say **about 36 hours**, or a day and a
   half — *not* two days.

If either of those comes back as the old number, tell me: it means something is still
quoting a stale page.

---

## 4. A lesson still teaches (1 chat)

1. On Home tap **Learn Solana**, then **Validators, leaders, slots and epochs**.
2. Heylana starts the lesson in the strip ("Lesson · validators and slots, 1 of 5") and
   speaks the first chunk. It should say a slot is 300 milliseconds.
3. Answer her check question however you like, or say **stop** to end it. Nothing else
   needs checking here — this step is only to be sure lessons still work at all.

---

## What I would want to hear about

- An answer with no chip under it.
- A chip that opens the wrong page, or a page that does not exist.
- Anything read out that sounds like a list, a heading or a web address — answers should
  still be one to three plain spoken sentences.
- "400 milliseconds", "two days", or anything about Firedancer not being live yet.
