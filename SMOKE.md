# SMOKE TEST — no more self-promotion at the end of answers

Heylana had started tacking a line onto its answers offering more help, or
explaining what it is and that it knows about Solana. You already know what it is
from onboarding. This stops that.

Nothing else changed: pointing, tasks, voice, mute and the notification all work
exactly as before.

## Budget for this test: 3 live API calls

Run by you, not by me. I did not send anything to the API — the prompt was
checked with unit tests on the computer instead.

| Call | What you type |
|------|---------------|
| 1 | what time is it in tokyo |
| 2 | what is 15 percent of 80 |
| 3 | what can you do |

**Send each one once.** If an answer is wrong, write down exactly what it said
and move on rather than retrying.

Before you start, check **Heylana → Settings** shows **Key saved** with the last
four characters of your own key. Then **Start buddy**.

---

**1. Call 1 — a question with nothing to do with this app.**
Press Home, open **Chrome** on any page. Tap the buddy, type
**what time is it in tokyo**, and send.

You should see: the time in Tokyo, and **nothing else**.

It must **not** say any of these:

- anything about **Solana**, **Seeker**, wallets, swaps or staking
- anything about **what Heylana is** or what it can help with
- a closing offer such as *"let me know if…"*, *"I can also…"*, *"feel free
  to…"*, or *"I'm here to help with…"*

One or two sentences that answer the question and stop. That is the whole test.

---

**2. Call 2 — a sum.**
Tap the buddy, type **what is 15 percent of 80**, and send.

You should see: **12**, in one short sentence — something like "That's 12."

It must **not** add an offer of more help, and must not explain that it can do
other things too.

---

**3. Call 3 — the one time it may talk about itself.**
Tap the buddy, type **what can you do**, and send.

You should see: **two sentences** describing what Heylana does — reading the
screen you are on and answering questions about it, pointing at things, walking
you through a task.

Here it **is** allowed to describe itself, and it may mention Solana if that is
part of the answer. What it must **not** do is run on past two sentences.

---

## What to do if something goes wrong

Write down the exact words it said, and which of the three questions it was.

- **A closing offer still appears** — quote the whole line.
- **It mentions Solana or Heylana in call 1 or 2** — quote the sentence.
- **Call 3 gives nothing, or refuses to describe itself** — that is the opposite
  failure and worth reporting too.
- **Answers got shorter but also worse** — say which and how; the persona line was
  trimmed and that is the thing most likely to have overshot.

---

## Pass criteria

- Calls 1 and 2 answer the question and stop, with no offer of further help and
  no mention of Solana, Seeker or Heylana.
- Call 3 describes Heylana in about two sentences.
- Answers are still accurate and still spoken aloud.
- Pointing, tasks, voice, mute and the notification all still behave as before.
- The whole test costs 3 live calls and no more.
