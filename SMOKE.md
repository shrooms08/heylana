# Smoke test — one flight home, rim beam, gooey merges

For Minos, on the Seeker.

**Live calls this test spends: 0.** Steps 1 and 2 are on the debug states screen; step 3
starts the buddy and opens and closes the box without asking anything. (A tap on the disc
opens the connection to Heylana's proxy, but sends no question.)

## 0. Set up

Install this build, run `./scripts/a11y.sh`. Heylana → Settings → scroll to the bottom →
**Debug states**. **backdrop** flips black and white.

## 1. The beam, on the panel and the disc, black and white

1. Tap **beam thinking**. Expected: a soft aurora-coloured glow (violet into blue, teal,
   orange) about a quarter of the rim long laps the box's rim and both discs' rims,
   clockwise, once every 1.6 seconds, glowing a little past the edge. No purple streak
   while it runs.
2. Tap **beam speaking**. Expected: the same beam on the box and discs, its brightness
   pulsing with a made-up voice.
3. **backdrop**, and both again over white: the beam reads on white too.
4. Tap **purple streak**: no beam, the streak drifts instead.

## 2. Gooey merges

1. Tap **gooey open** (white backdrop is easiest to see). Expected: under the 80dp disc a
   grey blob swells out of it — disc and blob one shape — and grows into the box, which
   comes away from the disc, then the glass and the words fade in.
2. Tap **gooey close**. After a moment the box draws back up into the disc as one blob and
   disappears into it.
3. Tap **strip to HUD**. Expected: the box pinches up into the one-line strip, a droplet
   drawn back into it; then the strip grows into the task HUD and step 2, next and done
   come out of its bottom edge.

## 3. One movement home, both sides

1. Back out of Settings, **Start buddy**. Drag the disc to the right edge and let go:
   it snaps to the edge in one movement.
2. Tap the disc: it flies to the top, the box grows out of it. Tap outside the box: the box
   draws back into the disc and **the disc flies straight to its dock in one movement** —
   no second nudge sideways when it arrives.
3. Drag the disc to the left edge and do the same.

If you have Logcat: `adb logcat -s HeylanaState | grep flight:` shows `target`, `landed`
and `settled` for every flight; the x and y must be the same on all three.
