# SMOKE TEST — design v1: vector brand mark and launcher icon

This one is about how things look. Nothing in the app's behaviour was touched.

## Budget for this test: 0 live API calls

Nothing here talks to the API. Do not ask the buddy anything.

Note: the API key on this emulator is still the **fake** one from the last phase.
That does not matter for these four checks. Put your real key back when you next
want to use the buddy.

---

**1. Check the trace against the original.**
On the computer, open **design/compare.png**.

You should see: three panels side by side.

- **ORIGINAL** — your logo, with slightly stair-stepped edges when you look close.
- **TRACED** — the same swirl, with **smooth edges** and the four rounded tips
  still properly **round**, not clipped flat or pointed.
- **OVERLAP** — the two laid on top of each other. It should be **almost entirely
  yellow**. Yellow means the two agree. Any thick red or green area would mean
  the trace has drifted; a hairline of colour around the edge is normal.

Look especially at the small teardrop on the left and the hook on the right —
both should be present and rounded.

---

**2. Find the new launcher icon.**
On the emulator, swipe up from the bottom of the home screen to open the app
drawer, and find **Heylana** in the alphabetical list.

You should see: a **black circle with the white swirl inside it**, sitting between
**Google** and **Maps**. The old green Android robot icon should be gone.

---

**3. Check the adaptive shape.**
Press and hold the **Heylana** icon until the menu pops up, then drag it a little
onto the home screen and drop it.

You should see: the icon keep its shape as the launcher masks it — the black
background fills the whole shape edge to edge with **no white corners and no gap**,
and the swirl stays **fully inside** with room around it, never touching the edge
or getting cut off.

---

**4. Check the app itself is unchanged.**
Tap the **Heylana** icon to open the app.

You should see: exactly what you saw before — the title **Heylana**, the same five
checklist rows, **Start buddy** and **Settings**. Nothing about the screen should
look different.

Tap **Start buddy** and confirm the purple pixel-face buddy still appears at the
right edge, then tap **Stop buddy**.

---

## What to do if something goes wrong

- **The old Android robot icon is still showing** — Android caches launcher icons.
  Long-press the home screen, or reboot the emulator, and look again.
- **The swirl looks cut off in the icon** — note whether it is cut at the top,
  bottom or sides.
- **The overlap panel has thick red or green patches** — the trace has drifted
  from your logo. Say roughly where.
- **Anything in the app looks different** — that would be a mistake; nothing in
  the app's behaviour was meant to change.

---

## Pass criteria

- The traced mark matches the original, with smooth edges and rounded tips.
- The launcher icon is the black-and-white swirl, at every size it is shown.
- The adaptive icon fills its shape with the mark safely inside.
- The app opens and behaves exactly as before.
- No API calls were made.
