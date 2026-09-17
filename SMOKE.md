# Smoke test — action voice line, empty box on reopen, gliding snap

For Minos, on the Seeker.

**Live calls this test spends: 1 chat and 1 tts** (step 1 only: the question, and the
spoken line). Steps 2 and 3 spend nothing. (A tap on the disc opens the connection to
Heylana's proxy, but sends no question.)

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open Heylana, **Start buddy**. Voice not muted.

## 1. The action speaks, then goes idle

1. Tap the disc. The box opens.
2. Type **set a timer for 1 minute** and tap **ask**.

Expected: the Clock app starts a 1-minute timer. The box closes at once and the disc flies
home. **You hear "Timer set for 1 minute."** all the way through, and about one second
after the voice stops the disc goes back to its resting look.

## 2. Reopen shows an empty box

3. Tap the disc again.

Expected: an empty box — just "ask about this screen", an empty field, and the **ask**
pill ready. No "thinking…", no old line from step 1.

4. Tap outside the box. It closes.

## 3. The snap glides

5. Drag the disc to the middle of the right side of the screen and let go slowly.

Expected: the disc glides to the edge and eases in, with no hard stop.

6. Pick it up again and flick it quickly toward the left edge.

Expected: it keeps the speed of the flick and glides into the left edge, easing in — no
pause at the moment you let go.

7. Tap the disc, then tap outside the box. The flight home glides the same way. (The flight
up to the top when opening keeps its quicker feel.)

## Optional

- Settings → Debug states → **snap glide**: two discs glide to one edge, the other, and home.
- From the computer, buddy started: `python3 scripts/landing_check.py right` and
  `python3 scripts/landing_check.py left` each print PASS.
