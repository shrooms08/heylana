# SMOKE TEST — design 2a, the buddy's states

Heylana now looks different in every state she can be in: resting, listening,
thinking, answering, pointing, walking you through a task. This test is about
what you see, not what she says.

## Budget for this test: 4 live API calls

I sent nothing to the API. Everything below that I could check, I checked on the
phone through a debug screen that fakes the answers.

| Step | Calls |
|------|-------|
| 1 the debug states screen | 0 |
| 2 "what is my balance" in the wallet | 1 |
| 3 hold-to-talk: "where is the seed vault" | 1 |
| 4 "help me receive SOL" | 2 |

**Before you start:** install, run `./scripts/a11y.sh`, close Heylana from
recents, reopen it, then tap **Start buddy**.

---

## 1. The debug states screen — 0 calls

Open **Heylana**, tap **Settings**, scroll to the bottom and tap **Debug
states**. This screen costs nothing: it fakes Heylana's answers so you can look
at every state without spending a call.

Along the bottom is a row of buttons. Swipe that row sideways to reach them all.
Tap each in turn. This is what each one should show.

**idle** — a dark purple disc with the swirl mark in it, knocked back and quiet.
A bright edge along the top of the disc that fades smoothly round to the bottom.
No square, no hard corners, no seam anywhere around it.

**backdrop** — the far right button. Tap it to flip the page from black to
white, and tap **idle** again. The disc must still read as a piece of glass: a
bright edge, a soft shadow under it, and a lighter face. Tap **backdrop** again
to go back to black.

**tapped** — a purple bloom opens behind the disc, the mark comes up to full
white, and the box grows out with **ask about this screen** in it, a purple
**ask** pill and a speaker icon.

**thinking** — the mark unwinds into a broken ring that turns on its own, and
the box says **thinking…** with the ask pill dimmed.

**typed answer** — the box shrinks into a **single-line strip** holding the
answer and the speaker icon. The question field is gone.

**voice answer** — no box at all. A small pill floats under the disc saying
**thinking…** with purple, blue, cyan and warm orange drifting through it. After
a moment the pill goes, the mark comes to full white and the disc **sheds rings**
as she speaks. No words appear on screen — that is the point.

**pointing** — the strip stays, a purple box appears lower down the page, and the
**mark leans and stretches toward it**.

**task 2 of 4** — the strip grows a second row: a **step 2** chip on the left,
a purple **next** and a quiet **done** on the right, and a thin purple rail along
the bottom **filled halfway**.

**done** — starts on that task, then the second row and the rail go away, the
strip says the task is finished, and a second later everything closes and the
disc is back to resting.

If any one of those does not match, say which button you tapped and what you saw
instead. Nothing here costs a call, so tap them as often as you like.

---

## 2. A question in the wallet — 1 call

Open your **wallet**. **Tap the buddy**, type **what is my balance** and tap
**ask**.

You should see: the disc bloom and the ring turn while it thinks, then the box
**shrink into a one-line strip** with the answer in it. If the answer is about
something on screen, a purple box appears around that thing and the mark leans
toward it.

---

## 3. Hold-to-talk — 1 call

Anywhere, **press and hold the buddy** and say **where is the seed vault**, then
let go.

While holding: the disc **stays where it is**, a purple ring around it breathes
with your voice, and a small capsule beside it fills in with your words.

On release: the capsule turns into the drifting **aurora pill**, then goes, and
the answer is **spoken aloud with no text**, with rings shedding off the disc.

**This is the step I could least verify.** The phone's states screen shows the
aurora, the speaking rings and the silence correctly, but the microphone half —
your words appearing as you speak — has still never run here. Watch it closely
and say exactly where it stops if it does.

---

## 4. A task — 2 calls

Open your **wallet** again. **Tap the buddy**, type **help me receive SOL** and
tap **ask**.

You should see: an answer strip, and under it the **step row** — a **step 1**
chip, **next**, **done** and the purple rail nearly empty. A purple box points at
what to tap.

**Tap what it points at.** The step row must survive that tap: it does not close
when you touch the screen underneath.

The rail fills as you go. When you reach the address, the step row and the rail
go away and the strip confirms it, then everything settles back to the resting
disc.

That is 2 calls: one to start the task, one for the step after your tap.

---

## What to do if something goes wrong

- **A faint square or a hard edge around the disc** — say which backdrop you
  were on, black or white.
- **The bright edge stops dead somewhere around the disc** — say roughly where.
- **The box does not shrink to a strip after an answer** — say so.
- **Words appear on screen when you asked out loud** — quote them.
- **The step row vanishes when you tap what she points at** — that is the whole
  point of step 4; say so.
- **The rail does not fill, or the step number does not change** — say what the
  chip said each time.

---

## Pass criteria

- Every one of the eight buttons on the debug states screen shows what is listed
  above, on both backdrops.
- The disc reads as glass on black and on white, with no square and no seam.
- A typed answer melts into a one-line strip.
- A spoken answer shows no text and sheds rings.
- Pointing leans the mark toward the purple box.
- A task shows the step chip, next, done and a rail that fills, and survives a
  tap on the screen underneath.
- Everything returns to the resting disc on its own.
- Nothing crashes, and the test costs 4 live calls and no more.
