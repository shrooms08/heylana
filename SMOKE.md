# SMOKE TEST — Phase 2b: multi-step guidance

Heylana can now walk you through a task one step at a time, watching the screen
and moving to the next step by itself once you have done the current one.

Before you start:

- Have **your own Anthropic API key** saved. It goes in the Heylana Settings
  screen and nowhere else — never into a browser, a chat, a terminal, or any
  other app.
- **Turn the phone's volume up.** Heylana speaks every step.
- You will need **internet** for Part C.

Two things that are normal and not faults:

- **The buddy moves itself during a task.** Before each step it shifts out of the
  way so its card never covers the thing it is boxing. The **Next** and **Done**
  buttons move with it.
- **Each step takes a few seconds** — it re-reads the screen and thinks before it
  answers.

Do these in order. After each step, check the screen matches the "You should see"
line before moving on.

---

## Part A — Regression (Phases 0 to 2a still work)

**1. Open the app and start the buddy.**
Tap the **Heylana** icon. Make sure the first four cards have a green ✓, then tap
**Start buddy**.

You should see: the label change to **Stop buddy**, and the **purple pixel face**
appear at the RIGHT edge of the screen.

---

**2. Ask an ordinary question in Chrome.**
Press Home, open **Chrome**, go to any news site (for example **bbc.com**). Tap
the purple face, type **what is this page about**, and tap **Send**.

You should see: the keyboard drop away, **thinking…** with the eyes UP, then a
short answer about that actual page, **spoken aloud**.

**Importantly:** there is **no "step 1" chip and no Next or Done buttons**. This
was a question, not a task.

---

**3. One-shot pointing in the Clock app.**
Press Home, open **Clock**, tap the **Alarm** tab. Tap the purple face, type
**how do I add an alarm**, tap **Send**.

You should see: a spoken answer and a **pulsing purple box exactly on the round +
button**, with the arrow from the buddy. Again **no step chip, no Next, no Done**.

Wait about ten seconds — the box fades away on its own.

---

## Part B — A whole task in the Clock app

**4. Ask for the task.**
Still on the Clock **Alarm** tab, tap the purple face, type
**set an alarm for 7am**, and tap **Send**.

You should see, after a few seconds:

- A **"step 1"** chip in the card.
- A **Next** button and a **Done** button next to it.
- A spoken first step, something like "Let's add a new alarm, tap the Add alarm
  button, the plus icon."
- A **purple box on the round + button**, with the arrow from the buddy.
- The box **stays put** — it does not fade after a few seconds like Part A did.

---

**5. Do what it says — and do NOT press Next.**
Tap the **+ button** that is boxed.

You should see: the new-alarm time picker open. Then, **without you touching
Heylana at all**, within a few seconds the card changes by itself:

- The chip becomes **"step 2"**.
- A new spoken step appears, about setting the hour.
- The box moves to the **7 on the clock face** (or, if the hour is already 7, to
  the AM button instead — Heylana reads the screen fresh each time, so it skips
  steps that are already done).

**This automatic move is the thing this phase is about.** If you had to press
Next to get here, note it.

---

**6. Keep following the boxes.**
Tap whatever is boxed, each time. Heylana will walk you through the hour, then
**AM**, then **OK**.

You should see: the chip count up — **step 3**, **step 4** — and the box land
exactly on each control in turn. The buddy moves itself up or sideways when it
would otherwise cover the thing it is boxing.

**If the card ever stops moving forward on its own**, tap **Next**. Some controls
stay on screen after you use them (the AM button is one), so Heylana cannot always
tell you have done it. Pressing Next is the normal way through those.

---

**7. Watch it finish.**
Tap **OK** on the time picker.

You should see, within a few seconds and with no input from you:

- A spoken confirmation, something like **"All set, your alarm for 7 AM is
  created and turned on."**
- The **step chip, Next and Done all disappear** — the task is over.
- The **purple box is gone**.

---

**8. Check the alarm really exists.**
Look at the Clock app behind the card.

You should see: a **7:00 AM** alarm in the list, switched **on**, set for
**Tomorrow**.

(Delete it afterwards if you do not want it going off: expand it with the small
**v** arrow and tap **Delete**.)

---

**9. Check "stuck" handling.**
Start the task again — type **set an alarm for 7am** and Send. When the first
step appears with the box on **+**, press **Next** without tapping anything.

You should see: Heylana either give a sensible next step, or say **"Looks like
that didn't work. Try tapping it again, or tell me what you see."** with the box
still on the same control. Both are correct — the second is what it says when it
notices it has pointed at the same thing twice.

Tap **Done** to end this one.

---

## Part C — A whole task in Chrome

**10. Go to the BBC home page.**
Press Home, open **Chrome**, and go to **bbc.com**. Let it finish loading.

---

**11. Ask for the task.**
Tap the purple face, type **open the sport section**, tap **Send**.

You should see: a **"step 1"** chip with **Next** and **Done**, a spoken step, and
a **purple box** on the way into Sport. On the BBC home page there is usually no
Sport link visible, so it should box the **menu button** (the lines-and-magnifier
icon at the top left) and say so.

---

**12. Tap what is boxed.**
Tap the **menu button**.

You should see: the BBC menu open, and then **by itself** the chip becomes
**step 2** with the box landing **exactly on the Sport row** in that menu.

---

**13. Finish the task.**
Tap **Sport**.

You should see: the BBC Sport page load, and then **by itself** a spoken
confirmation such as **"You're already in the sport section, this is the BBC Sport
page."** — with the **chip, Next and Done gone** and the **box cleared**.

The address bar should read **bbc.com/sport**.

---

## Part D — Ending a task early

**14. Start a task.**
Press Home, open **Clock**, tap **Alarm**. Tap the purple face, type
**set an alarm for 6am**, tap **Send**.

You should see: **step 1**, the box on the **+ button**, Next and Done.

---

**15. Press Done.**
Tap the **Done** button in the card.

You should see, immediately:

- The **step chip, Next and Done disappear**.
- The **purple box and arrow vanish**.
- The card stays open with the last thing Heylana said, and the normal
  **Ask about this screen** field and **Send** button still there.

No new alarm should have been created.

---

**16. Check the card is a normal card again.**
Type **what is this screen** and tap **Send**.

You should see: an ordinary spoken answer with **no step chip** — you are back to
plain questions.

---

## Part E — Everything from before

**17. Drag, snap and the panel side.**
Drag the buddy to the LEFT edge and let go — it snaps. Tap it — the card opens on
its **RIGHT**. Tap well away from the card — it closes.

---

**18. Hold to talk.**
Press and hold the buddy until the card says **listening…** with wide eyes and a
red dot. Say **how do I add an alarm**, then let go.

You should see: your words appear in the field, then a spoken answer.

---

**19. Mute.**
Tap the **speaker icon** in the top-right of the card so it shows a line through
it. Ask something.

You should see: the answer in writing with **no sound**.

Tap the speaker icon again to turn the voice back on.

---

**20. Stop.**
Swipe down from the top, expand **"Heylana is on your screen"**, tap **Stop**.

You should see: the buddy gone, along with any box.

---

## What to do if something goes wrong

- **A step's box lands next to the control instead of on it** — real failure. Note
  the app, the step, and the control.
- **The card covers the thing it is pointing at** — real failure; it is supposed
  to move out of the way. Note which step.
- **The task never advances on its own, in any app, even after tapping the boxed
  control** — real failure. Note the app and step.
- **The task keeps going past 8 steps** — real failure. It should say something
  like "that is as far as I can take you, so let's stop here" and end.
- **Heylana taps or types something itself** — real failure, and the most serious
  one. It must only ever point.
- **"Looks like that didn't work…"** — expected when you press Next without doing
  the step, or when a control does not change the screen.
- **"API error 401"** — key is wrong. Heylana → Settings → **Replace**.
- **"Couldn't reach the API"** — no internet.

---

## Pass criteria

The phase passes only if ALL of these are true:

- An ordinary question still gives a one-shot answer with **no step chip**.
- A task shows a **step chip**, a **Next** button and a **Done** button.
- The box during a task **stays up** instead of fading after a few seconds.
- **The task advances by itself** after you tap the boxed control, in both the
  Clock app and Chrome, without pressing Next.
- Each step's box lands **exactly on** the right control, and the buddy moves
  itself so its card never covers it.
- The task **ends by itself** with a spoken confirmation once the goal is reached,
  and the chip, buttons and box all clear.
- The **7:00 AM alarm actually exists** in the Clock app afterwards.
- **Done** ends a task immediately and clears the box.
- Heylana never taps or types anything itself. Nothing moves unless you touch it.
- Everything from before still works: drag, snap, hold to talk, mute, typed
  questions, and the notification **Stop**.
- Nothing crashes at any step.
