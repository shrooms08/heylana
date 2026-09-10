# SMOKE TEST — design part 1: the buddy disc and the glass box

The pixel face and the white card are gone. The buddy is now the brand mark in a
disc of dark glass, and the message box is one sheet of glass that drops in under
it. Nothing about what Heylana *does* changed.

## Budget for this test: 0 live API calls

Every step below is about how things look. **Do not tap ask with a real key in
the field**, except where step 9 says so, and that step uses a deliberately wrong
key, which costs nothing.

**Read this first:** during development I discovered your **real key is on this
emulator**, not the fake one I expected. I spent **one live call** finding that
out — sorry. Your key is untouched and still saved.

---

**1. Start the buddy and look at it resting.**
Open **Heylana**, tap **Start buddy**, then press Home and open any app — the
**Clock** is fine.

You should see: a **dark glass circle** against the right edge of the screen with
the **white swirl** inside it, knocked back to a soft grey. The disc has a faint
light rim. There is **no purple glow**. It sits just off the edge — fully
visible, not half cut off.

---

**2. Watch it breathe.**
Keep watching the disc for about ten seconds without touching it.

You should see: the disc **swell very slightly and settle again**, over about
three seconds each way. It is meant to be barely there — a slow breath, not a
pulse. Nothing rotates.

---

**3. Tap it to compose.**
Tap the disc once.

You should see, in one movement:

- The buddy **flies to the top centre** of the screen and settles just below the
  status bar, with a small overshoot at the end.
- The mark goes **full white**, the disc lightens, and a **purple glow blooms
  behind it**.
- The app behind **dims**.
- A **glass box drops in beneath the buddy**, nearly full width with a even gap
  each side, with soft rounded corners.
- The **keyboard opens by itself**.

In the box: a **speaker icon** in the top-right corner, a wide field reading
**ask about this screen** in grey, and a purple **ask** button beside it.

---

**4. Look closely at the glass.**
Look at the edges of the box and the ask button.

You should see: a **hairline light border** all the way round; the **top and left
edges slightly brighter** than the middle and the **bottom and right slightly
darker**, fading out rather than stopping sharply; and a **soft purple wash**
inside the glass near its top-left corner, running diagonally. The **ask** button
is the same glass with much more purple in it.

---

**5. Is the blur working?**
Look at the app *behind* the box, in the gap between the buddy and the box, and
around the edges.

- **Blur on:** whatever is behind is **soft and smeared**, as if through frosted
  glass. Text and icons behind are unreadable shapes.
- **Blur off:** everything behind stays **sharp**, just darker, and the box reads
  as a slightly milky grey panel instead.

Both are correct — Heylana asks the phone for blur and takes whatever it gets.
Android switches blur off on its own in battery saver and on weaker phones. If
you want to check deliberately: **Settings → System → Developer options →
Window blurs** toggles it. Turn it off, close and reopen the box, and the box
should get **visibly more solid** rather than disappearing.

---

**6. Close it.**
Tap anywhere on the dimmed area away from the box.

You should see: the dim clear, the box fade out, and the buddy **fly back to
exactly where it was docked**, going grey and losing its glow as it lands.

Try the same with the **back gesture** instead — same result.

---

**7. Hold it to talk.**
Press and hold the disc for about a second and keep holding.

You should see: the mark go **full white with the purple glow**, and the box open
**beside the buddy** reading **listening…** — with **no dim** and **no keyboard**
this time. Slide your finger away and let go to cancel without sending.

---

**8. Drag and snap.**
Press and drag the disc to the middle of the screen and let go.

You should see: it follows your finger, then **snaps flat to the nearest side**
and goes back to its resting grey. It should still sit just off the edge, fully
visible.

---

**9. See an error inside the glass box (optional, costs nothing).**
This checks that failures land in the box rather than crashing.

Open **Heylana → Settings → Replace**, type any nonsense such as
`sk-ant-not-a-real-key`, tap **Save**, and go back. Start the buddy, tap it, type
**what is this**, and tap **ask**.

You should see: the box show **thinking…** for a moment, then a short line inside
the same glass box reading **API error 401** and something about the key being
invalid. No crash, and the box stays open.

**Then put your real key back**: Settings → **Replace** → your key → **Save**.

---

**10. Check the notification.**
Swipe down from the top of the screen.

You should see a notification with:

- title **heylana**
- text **heylana is on your screen**
- a smaller line reading **tap the swirl to ask · only reads when you ask**
- the **swirl** as its small icon
- a **Stop** button when you expand it

It should be silent and sit low in the list.

---

## What to do if something goes wrong

- **The disc is cut off by the screen edge** — note which edge.
- **No purple glow when you tap it** — note what state it was in.
- **The buddy does not fly back to where it was docked** — note where it went.
- **The box covers the buddy, or the buddy sits on the box** — note which.
- **Tapping away does not close it** — note whether the keyboard was up.
- **Text looks like the old default font** rather than the rounded Outfit — the
  font failed to load; say so and it will fall back cleanly rather than break.

---

## Pass criteria

- Resting: a dark glass disc with a dulled swirl, no glow, breathing slowly, just
  off the edge and fully visible.
- Tapping flies it to the top centre, dims the app, drops the glass box in and
  opens the keyboard.
- Any active state — composing, listening — brings the mark to full white with a
  purple bloom.
- The glass has a hairline border, a light-to-dark bevel and a purple wash near
  its top-left.
- Tapping away or going back clears everything and returns the buddy to its dock.
- Drag and snap still work.
- The notification reads as above and its Stop button still works.
- Nothing crashes, and no API calls happen except the optional error check.
