# Smoke test — one motion home

For Minos, on the Seeker.

**Live calls this test spends: 0.** (A tap on the disc opens the connection to Heylana's
proxy, but sends no question.)

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**.

## 1. Docked right

1. Drag the disc to the right edge and let go.
2. Tap the disc: it flies to the top and the box grows out of it.
3. Tap outside the box.

Expected: **one motion**. The box draws up into the disc and, without stopping, the disc
flies to the right edge, dimming and shrinking on the way, and settles there. Nothing
moves after it arrives: no slide in from the side, no second nudge, no grey circle left at
the top.

## 2. Docked left

Drag the disc to the left edge and do the same three taps. Same result.

## Optional, from the computer

`python3 scripts/landing_check.py right` and `python3 scripts/landing_check.py left` (buddy
started) each print PASS: the frames straight after landing match the frame 700ms later.
