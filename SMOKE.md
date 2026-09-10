# SMOKE TEST — phase 2 behaviours

Four changes: the voice capsule now goes away when it should, the pointer box
notices you doing the thing, Heylana remembers the last few questions, and the
connection is opened before you have finished typing.

## Budget for this test: 4 live API calls

I sent nothing to the API. Everything I could check without it, I checked on the
Seeker through the debug states screen.

| Step | Calls |
|------|-------|
| 1 debug states, "voice full cycle" | 0 |
| 2 hold-to-talk in the wallet | 1 |
| 3 type "how do I swap", then tap Swap | 1 |
| 4 "what does this do", then "and the other button" | 2 |

**Before you start:**

1. Install, run `./scripts/a11y.sh`, close Heylana from recents, reopen it.
2. Open **Settings**. Turn **Show spoken answers as text** **off** — it is on
   from the last test, and step 2 is about a spoken answer leaving nothing on
   screen. Leave **Warm up the connection** **on** for now.
3. Go back and tap **Start buddy**.

---

## 1. The debug states screen — 0 calls

**Settings → Debug states.** The buttons now sit in rows instead of one long
strip, so all of them are reachable. Tap **voice full cycle** and watch it right
through without touching anything else:

1. a purple ring around the disc, and a small pill filling in word by word with
   **where is the seed vault**;
2. the same pill turns into the drifting **aurora**, purple to blue to cyan to
   warm orange, and the mark unwinds into a turning ring;
3. the pill **shrinks and fades away** as the answer lands — it does not sit
   there, and it does not blink out;
4. the mark comes back to full white and the disc **sheds rings** while it
   speaks;
5. about a second after that, the disc is back to its resting purple and **the
   screen is empty**.

Tap it a few times. Step 3 is the one that was broken: the pill used to stay on
screen beside the buddy with the aurora still turning, and nothing ever took it
away.

The other buttons are unchanged from the last test and still cost nothing.

---

## 2. Hold-to-talk in the wallet — 1 call

Open your **wallet**. **Press and hold the buddy**, say **where is the seed
vault**, and let go.

You should see the same five things as step 1, on the real thing: the ring, your
words in the pill, the aurora, the pill melting away, the answer spoken aloud
with **no text**, and the disc back to resting about a second after it stops
speaking.

**Nothing should be left on screen except the purple box**, if the answer pointed
at something. That box has its own rules — see step 3.

Then try it once more, **hold the buddy and say nothing at all**, and let go. The
pill should melt away and the disc go back to resting, with **no message and no
box**. It used to sit there saying "listening".

---

## 3. Type a question, then do the thing — 1 call

Still in the wallet. **Tap the buddy**, type **how do I swap** and tap **ask**.

You should see the answer strip and a **purple box around Swap**.

Now **tap Swap**. The box should **flash green and disappear**, and Heylana
should **say nothing at all** about it. That is the whole feature: it noticed you
did the thing and got out of the way.

Try the other half too, on a later question: point at something and **do
nothing**. After about fifteen seconds the box should **clear quietly** — no
green, no message.

While a box is up, Heylana is watching for that one tap. When there is no box, it
is watching nothing at all: the notification and the first screen of the app both
say so now — *reads the screen only when you ask, and watches for your tap only
while it is pointing at something*.

---

## 4. A follow-up question — 2 calls

On the **Swap** screen. **Tap the buddy**, type **what does this do** and tap
**ask**. Read the answer.

Then ask a second question: **and the other button**.

The second answer should be about a **different** button on that same screen,
and should make sense as a follow-up to the first — it knows what you already
asked. Before this change the second question had nothing to go on and would
usually answer as if it were the first.

Heylana remembers the **last three questions and answers**, for **ten minutes**,
in memory only. It forgets them when you stop the buddy, when you ask from a
different app, and after ten minutes. Closing the box does not make it forget.

Worth trying, at no cost: go to a **different app** and ask something that only
makes sense as a follow-up. It should not understand it — that is the app-change
reset doing its job.

---

## Optional: the numbers, from the Mac

None of this costs a call; it just reads what the phone already printed.

```
$HOME/Library/Android/sdk/platform-tools/adb logcat -s HeylanaTokens
```

Each question prints three lines worth reading:

- `screen elements=… chars=… memory=N exchanges` — how big the screen listing was
  and how many remembered exchanges went with it.
- `input_tokens=… output_tokens=…` — compare the input number with the **744 to
  844** we were seeing on Haiku before. Memory should add roughly a hundred or so,
  never hundreds: what is sent is capped at 600 characters however long the
  answers were.
- `first_byte_ms=… warmed=true|false` — how long the answer took to start coming
  back, and whether the connection had been opened in advance.

**To see what the warmup is worth**, run step 2 with **Warm up the connection**
turned **off** in Settings, and step 3 with it back **on**. That is two calls you
were spending anyway, and it gives you a `warmed=false` number and a
`warmed=true` number to compare. I could not produce those two numbers myself:
getting them means opening a connection to the API host, and I do not touch it.

---

## What to do if something goes wrong

- **The pill stays on screen after the answer** — say whether it was showing your
  words or the aurora, and whether the disc had gone back to resting.
- **The disc never goes back to resting** — say what it was doing when it stuck:
  turning ring, shedding rings, or purple listening ring.
- **The box flashes green when you have not touched anything** — say what was on
  screen and roughly how long after the box appeared.
- **The box never goes green when you tap the thing** — say which app and which
  button, and whether it cleared quietly after fifteen seconds instead.
- **The follow-up answer ignores the first question** — quote both questions and
  both answers.
- **Anything gets slower** — quote the `first_byte_ms` lines.

---

## Pass criteria

- The voice pill melts away as the answer lands, every time, and the disc is back
  to resting about a second after speech ends with nothing left but the box.
- Holding and saying nothing leaves nothing on screen and says nothing.
- Tapping what the box points at flashes it green and clears it, silently.
- Fifteen seconds of nothing clears the box quietly.
- A follow-up question in the same app understands the one before it.
- A question in a different app does not.
- Nothing crashes, and the test costs 4 live calls and no more.
