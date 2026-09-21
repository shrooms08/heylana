# Smoke test — polish-8-eval (counters, and how-Solana-works answers)

For Minos. Two things changed. The server no longer falls over when it cannot save its
counters (that was Sunday afternoon's errors), and it saves them about a tenth as often. And
any question about how Solana works now gets a looked-up answer with its page under it, not
only questions shaped like code. The server is deployed already. Install the app, then run
`./scripts/a11y.sh` once.

**Live calls this test spends: 4 chat, 4 spoken answers.** Nothing is sent, nothing is
signed, no wallet opens.

---

## 1. How Solana works, answered from the library (2 chat)

1. Open Heylana. You are on Home.
2. Tap the **Message** box, type `How long is a Solana epoch`, tap the blue arrow.
3. She says **about 36 hours** (a day and a half), and **"as of September 2026"**. Under the
   words there is **a chip** naming the page it came from. There is **no code** above the words.
   - Wrong: "two days", or "two to three days". That is the old number.
4. Type `What is Agave`. Same shape: a few spoken sentences, a chip, no code.

**What is new:** before this, both of these were treated as ordinary questions — no lookup, no
chip, and sometimes a stale number from memory.

---

## 2. Code only when you ask for code (1 chat)

1. Type `How do I add a priority fee to a transaction`.
2. This time a few lines of **code** sit above the words, with the library named on the first
   line. The words say the trap in one sentence. A chip is underneath.

---

## 3. Your own money still goes the old way (1 chat)

1. Type `How much SOL do I have`.
2. She answers with your balance, as she always has — **not** a lesson about SOL, and no
   "as of September 2026".

---

## 4. Nothing to check for the counters

The fix for Sunday's errors cannot be shown by tapping: it only matters on the day the server
runs out of saves. If you ever see "Something went wrong on my side." on every question for
a whole afternoon again, tell me — that is what this was.

---

## What I would want to hear about

- An answer about how Solana works with no chip under it.
- Code appearing when you did not ask for it.
- "400 milliseconds", "two days", or anything saying Firedancer is not live yet.
- A balance, a send or "what am I signing" answered like a lesson.
