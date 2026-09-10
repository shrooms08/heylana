# SMOKE TEST — design v2 polish

Four fixes from the last smoke test: the buddy's flight is smooth, the glass is
a lit sheet rather than a dark slab, a spoken question is answered by voice
rather than with a wall of text, and questions that are not about the screen get
answered instead of deflected.

## Budget for this test: 3 live API calls

Run by you. I sent nothing to the API.

| Step | Calls |
|------|-------|
| 1 flight | 0 |
| 2 glass | 0 |
| 3 typed question | 1 |
| 4 voice question | 1 |
| 5 show text | 1 |
| 6 helper script | 0 |

**Before you start:** install the build, then run `./scripts/a11y.sh` in the
Local terminal, then open Heylana and tap **Start buddy**. Without the script
that button stays greyed out — that is step 6, and it is worth doing first so
the rest works.

---

## 1. The flight — 0 calls

**Tap the buddy.**

You should see it **fly to the top centre in one smooth movement**, overshoot
very slightly and settle. No stutter, no stepping, no jump at the end. The
purple bloom travels with it. The dim and the glass box arrive **after** it
lands, not during.

**Tap the dimmed area away from the box.** The box goes first, then the buddy
flies back to exactly where it was docked — same smoothness.

**Drag the buddy to the middle of the screen and let go.** It should spring to
the nearest edge with the same motion, not slide stiffly.

If any of these three stutters, say which one.

---

## 2. The glass — 0 calls

**Open the second Heylana icon in the app drawer, "Heylana Glass".** This is a
debug-only screen that shows the real message box over a bright backdrop and
over black, with the same dim the overlay uses.

You should see, on **both** panels:

- the panel clearly **lighter than the dimmed backdrop**, not a dark slab
- a **bright top edge** — a hairline of light just inside it, fading out towards
  the corners
- a border that is **brighter at the top-left** and almost gone at the
  bottom-right
- a **soft purple wash** inside the glass near the top-left
- the **question field lighter than the panel** around it, not a darker hole

**Then check it live:** press Home, open **Chrome** on a bright page, and tap the
buddy. The panel should look like the preview, and the page behind it should be
**soft and smeared** where the blur is working.

**Known gap:** there is no drop shadow under the panel. Every other layer of the
recipe is there; the shadow is not, and I have said why in the report.

---

## 3. A typed question — 1 call

Over **Chrome**, tap the buddy, type **what is the capital of japan**, tap
**ask**.

You should see: **Tokyo**, in a sentence or two. It must **not** refuse, and it
must not say it can only talk about what is on screen. This is the fix for
questions that have nothing to do with the page.

---

## 4. A spoken question — 1 call

Still over Chrome. **Press and hold the buddy** and say **what is on this page**,
then let go.

You should see:

- the app **dims and blurs**, exactly as when you type — not a small box beside
  the buddy
- **no keyboard**
- an **aurora capsule** under the buddy while it thinks: a glass pill with
  purple, violet, cyan and warm orange **drifting through it**, not a static
  gradient
- then the answer **spoken aloud with no text** in the box
- the buddy **shedding rings** outward while it speaks

If the text appears anyway, that is a failure — say so.

---

## 5. Show text — 1 call

In the box there is a **show text** chip next to the speaker icon. **Tap it.**

Then **hold the buddy and ask anything by voice again.**

You should see: the same dim, blur and capsule, and this time **the words of the
answer appear** as well as being spoken. The chip now reads **hide text**.

Close the box, stop the buddy and start it again, then open the box once more —
**no call needed, just check the chip still reads hide text.** The choice is
remembered.

---

## 6. The helper script — 0 calls

This is the workflow fix, and you have already used it.

**Run the app from Android Studio.** Open Heylana: the **Screen reading** row
will be unticked and **Start buddy** greyed out, because installing switches the
accessibility service off.

**In the Local terminal run:**

```
./scripts/a11y.sh
```

Then **reopen Heylana** — close it from recents first, or it will not notice.

You should see: **Screen reading ticked** and **Start buddy** blue, without
opening Android Settings at all.

---

## What to do if something goes wrong

- **The flight stutters** — say which of the three (out, back, snap).
- **The buddy jumps or flickers at the end of a flight** — that is the handoff
  between windows; say where it jumped to.
- **The panel still reads as a dark slab** — say whether the preview screen
  looked wrong too, or only the live one.
- **The blur is not working** — normal on battery saver and weaker phones;
  Settings, System, Developer options, Window blurs.
- **A voice answer shows its text without you asking** — quote what it said.
- **A voice answer opens beside the buddy instead of dimming the screen** — that
  is the part of this phase I could not verify on the emulator; say so.
- **Start buddy stays grey after running the script** — check you closed and
  reopened Heylana rather than just switching back to it.

---

## Pass criteria

- All three flights are smooth, with a small overshoot and no jump at the end.
- The panel reads as a lit sheet on both a bright backdrop and black, with a
  bright top edge and a field lighter than the panel.
- A question with nothing to do with the screen is answered, not refused.
- A spoken question dims and blurs like a typed one, shows a drifting aurora
  capsule, and is answered aloud with no text.
- The show-text chip turns the words on and is remembered.
- `./scripts/a11y.sh` gets Start buddy working without opening Settings.
- Nothing crashes, and the test costs 3 live calls and no more.
