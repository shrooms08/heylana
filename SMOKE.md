# SMOKE TEST — Phase 2c: token trim and model routing

This phase makes every question cost less. The test below is deliberately small.

## Budget for this test: EXACTLY 4 live API calls

That is the whole cost of running this page, and it breaks down like this:

| Call | What triggers it | Which model |
|------|------------------|-------------|
| 1 | The question in Chrome (Part B) | Quick model — Haiku |
| 2 | The question in Clock (Part C) | Quick model — Haiku |
| 3 | Starting the timer task (Part D) | Quick model — Haiku |
| 4 | The task moving to step 2 by itself (Part D) | Task model — Sonnet |

Pressing **Done** in Part E costs **nothing** — that is why Part E uses the task
that is already running rather than starting a new one.

**Do not re-send a question to "check it again".** Every send is money. If a step
does not do what this page says, write down what happened and move on.

---

## Part A — Put your key back (no API calls)

Your real key was **replaced with a fake one** during development, on purpose, so
that no test could ever spend your budget. You need to put yours back before
anything below will work.

**1. Open Settings.**
Tap the **Heylana** icon, then the **Settings** button at the bottom.

You should see: **"Key saved · sk-ant-…0000"** — that is the fake key — and below
it a **Models** card with two fields:

- **Quick model**, showing **claude-haiku-4-5-20251001**
- **Task model**, showing **claude-sonnet-5**

---

**2. Replace the key.**
Tap **Replace**, type or paste **your key**, tap **Save**.

You should see: **"Key saved · sk-ant-…"** ending in the last four characters of
**your** key, and the word **Saved.**

Tap **Back**, check all five checklist rows, then tap **Start buddy**.

---

## Part B — Call 1: a question in Chrome

**3. Open a news page.**
Press Home, open **Chrome**, go to **bbc.com**.

---

**4. Ask one question.**
Tap the purple face, type **what is this page about**, tap **Send**.

You should see: **thinking…**, then a short spoken answer about that page.

**No step chip, no Next, no Done** — this was a question, not a task.

That was **call 1**, on the quick model.

---

## Part C — Call 2: pointing in Clock

**5. Open the Clock app.**
Press Home, open **Clock**, tap the **Alarm** tab.

---

**6. Ask one question.**
Tap the purple face, type **how do I add an alarm**, tap **Send**.

You should see: a short spoken answer and a **pulsing purple box exactly on the
round + button**, with the arrow from the buddy. Still **no step chip**.

That was **call 2**, on the quick model. The box fades by itself after about
eight seconds.

---

## Part D — Calls 3 and 4: a two-step task on the Timer tab

**7. Go to the Timer tab.**
In the Clock app, tap **Timer** in the bottom bar.

You should see: the timer keypad, with digits and a start button.

---

**8. Ask for the task.**
Tap the purple face, type **start a 1 minute timer**, tap **Send**.

You should see: a **"step 1"** chip, a **Next** button, a **Done** button, a
spoken first step, and a **purple box** on whatever you should touch first —
usually the **1** on the keypad.

That was **call 3**, on the quick model.

---

**9. Do the step, and do not press Next.**
Tap whatever is boxed.

You should see: within a few seconds, and with no input from you, the chip becomes
**"step 2"** with a new spoken step and the box moved to the next control —
usually the **start** button.

That was **call 4**, on the task model. **Stop here — do not tap the boxed start
button**, or the task will spend a fifth call finishing itself.

---

## Part E — Done costs nothing

**10. End the task.**
Tap the **Done** button in the card.

You should see and hear:

- Heylana say **"Okay, stopping here."** out loud, and the same words in the card.
- The **step chip, Next and Done disappear**.
- The **purple box and arrow vanish**.

No API call is made by pressing Done.

---

## Part F — Reading the token count in Logcat (no API calls)

This is how you check what a question actually cost.

**11. Open Logcat.**
On the computer, open **Android Studio** with the Heylana project. At the bottom
of the window click the **Logcat** tab. If you cannot see it, use the menu
**View → Tool Windows → Logcat**.

You should see: a fast-scrolling list of messages, and the emulator selected in
the dropdown at the top left.

---

**12. Filter it.**
Click the search box at the top of the Logcat panel and type exactly:

```
tag:HeylanaTokens
```

You should see: the list shrink to only Heylana's own counter lines.

---

**13. Read the numbers.**
Scroll to the top of what is left and find the lines from **call 1** — the Chrome
question. There are two kinds:

```
screen elements=24 chars=1211
model=claude-haiku-4-5-20251001 input_tokens=... output_tokens=...
```

The **input_tokens** number on the second line is what that question cost you to
send. That is the number this phase set out to reduce.

You should see: the model on **calls 1, 2 and 3** is
**claude-haiku-4-5-20251001**, and on **call 4** it is **claude-sonnet-5**. That
proves the cheap model is doing the ordinary work.

These lines contain only counts and model names — never anything from your screen
or your conversation.

---

## What to do if something goes wrong

- **"API error 401"** — the key is wrong or you skipped Part A. Settings →
  **Replace** → your key → **Save**.
- **"Couldn't reach the API"** — no internet.
- **The Logcat filter shows nothing** — check the emulator is picked in the
  dropdown, and that you have asked at least one question since opening Logcat.
- **A question comes back noticeably worse than before** — the quick model is
  cheaper and may be weaker. Note the question and the answer. You can put
  **claude-sonnet-5** into the **Quick model** field in Settings to undo the
  routing, at higher cost.
- **The count of live calls does not match the table** — that matters for the
  budget. Note where the extra call happened.

---

## Pass criteria

- Both model fields appear in Settings with the right defaults, and both save.
- A question gives a one-shot answer with **no step chip**; a task gives a chip
  with **Next** and **Done**.
- The task moves to step 2 **by itself**.
- **Done** says **"Okay, stopping here."** and clears the chip, buttons and box.
- Logcat under **tag:HeylanaTokens** shows **Haiku for calls 1 to 3** and
  **Sonnet for call 4**, with an input token count for each.
- The whole test cost **4 live calls** and no more.
- Answers are still good enough to be useful, and the box still lands on the right
  control.
- Nothing crashes.
