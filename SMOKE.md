# SMOKE TEST — design v2 polish, round two

Five changes from the last smoke test: the glass is smoked so it stays visible
and readable on a bright page, it has shine and a faked refraction edge, the box
grows out of the disc, and a spoken question no longer takes over the screen.

## Budget for this test: 3 live API calls

Run by you. I sent nothing to the API — the glass was checked in the debug
preview and voice mode against a stubbed client.

| Step | Calls |
|------|-------|
| 1 glass on a bright page | 0 |
| 2 glass on a dark page | 0 |
| 3 typed question | 1 |
| 4 hold-to-talk | 1 |
| 5 show spoken answers as text | 1 |
| 6 helper script | 0 |

**Before you start:** install, run `./scripts/a11y.sh`, close Heylana from
recents, reopen it, then tap **Start buddy**.

---

## 1. Glass on a bright page — 0 calls

Open **Chrome** on a bright page. **Tap the buddy.**

You should see, as it opens:

- the box **grows out of the disc** — starting small under it and springing out
  to full width, not dropping in
- a **light sweep crosses the pane once**, left to right, and settles
- the pane is **dark smoked glass**, clearly darker than the page behind it, and
  the text on it is **easy to read**
- a **bright rim along the top edge**, brighter at the top-left, fading to
  almost nothing at the bottom-right
- a **soft glow of light** in the upper-left area of the pane
- a **soft shadow** under the pane, with no hard edges or grey rectangles
- a **purple tint curving around the top-left corner**
- the **question field lighter** than the pane around it

There is also a debug-only check: open the second launcher icon, **Heylana
Glass**. Under each panel it prints the measured contrast of the body text and
PASS or FAIL. On the emulator these read **5.38:1** over a bright backdrop and
about **14:1** over black. Both must say PASS.

---

## 2. Glass on a dark page — 0 calls

Switch Chrome to a dark page, or turn on Chrome's dark theme, and tap the buddy
again.

You should see: the pane still clearly **lighter than the page**, with the same
rim and the same readable text. It must not disappear into the background.

---

## 3. A typed question — 1 call

Type **what is the capital of japan** and tap **ask**.

You should see: **Tokyo**, in the box.

**Known gap:** the brief asked for the box to melt into a compact reply strip in
one continuous shape. That strip has never been built in this app — it was
scheduled for a design part 2 that was skipped — so there is nothing for the box
to melt into, and the answer simply appears in the box. Nothing to report here
unless the answer itself is wrong.

---

## 4. Hold-to-talk — 1 call

Over Chrome, **press and hold the buddy** and say **what is on this page**, then
let go.

While holding, you should see:

- the disc **stays exactly where it is docked** — it does not fly to the top
- the screen is **not dimmed and not blurred**
- a **purple ring** around the disc that **breathes with your voice**
- a **small glass capsule** beside the disc, filling in with **your words as you
  say them**

On release:

- the same capsule becomes the **aurora "thinking…" pill** in place — purple,
  violet, cyan and warm orange drifting through it
- then the capsule goes, the answer is **spoken aloud**, **no text appears**, and
  the disc **sheds rings** while it talks
- if the answer is about something on the page, the **purple box and arrow**
  still appear

**This is the step I could least verify.** I confirmed the capsule appears
beside the docked disc with no dim and no blur, using a stubbed answer. The
emulator has no microphone input, so everything after you let go — the
transcript, the aurora, the speaking rings and the pointing — has never actually
run. Please watch it closely and say exactly where it stops if it does.

---

## 5. Show spoken answers as text — 1 call

Open **Heylana → Settings**. At the top there is a switch, **Show spoken answers
as text**, off by default. **Turn it on**, go back, and repeat step 4.

You should see: everything as before, and this time the **words of the answer
also appear** in the box.

---

## 6. The helper script — 0 calls

**Run the app from Android Studio.** Heylana will show **Screen reading**
unticked and **Start buddy** greyed out.

**In the Local terminal:**

```
./scripts/a11y.sh
```

Then **close Heylana from recents and reopen it** — it will not notice
otherwise.

You should see: **Screen reading ticked**, **Start buddy** blue.

---

## What to do if something goes wrong

- **The pane is still hard to read on a bright page** — say what the Heylana
  Glass screen printed, and whether it said PASS.
- **The box drops in rather than growing out of the disc** — say so.
- **No sweep, no rim, or no shadow** — say which is missing.
- **The disc flies or the screen dims when you hold it** — that is the whole
  point of step 4; say so.
- **The capsule sits saying "listening" and never changes** — say whether you
  actually spoke and whether the microphone dot appeared.
- **The spoken answer shows its text with the switch off** — quote it.

---

## Pass criteria

- The pane reads as dark smoked glass with readable text on a bright page and is
  still clearly lighter than a dark page.
- Rim, sweep, highlight, corner tint and shadow are all present, with no hard
  rectangles anywhere.
- The Heylana Glass screen says PASS on both backdrops.
- The box grows out of the disc.
- A typed question answers in the box.
- Holding leaves the disc docked and the screen clear, shows a breathing ring
  and a transcript capsule, then an aurora, then speaks with no text.
- The Settings switch turns the text on and is remembered.
- `./scripts/a11y.sh` gets Start buddy working.
- Nothing crashes, and the test costs 3 live calls and no more.
