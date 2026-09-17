# Smoke test — the orb disc, liquid glass, and quick actions tidy up

For Minos, on the Seeker, **Judge plan**, wallet connected.

**Live calls this test spends: 1 question (`/chat`) and 1 spoken answer (`/tts`).**
Steps 1 and 2 are on the debug states screen and call nothing.

## 0. Set up

1. In `worker`: `npx wrangler deploy` (unchanged since the quick actions fix; skip if
   that is what is deployed).
2. Install this build, run `./scripts/a11y.sh`.
3. On the computer: `adb logcat -s HeylanaState | grep -E "brain:|action:|settle"`

## 1. The disc: orb states, both sizes, glass over black and white

1. Heylana → Settings → scroll to the bottom → **Debug states**.

Expected: two discs at the top, a small one (64dp, docked size) and a larger one (80dp,
open size), both showing the mark. **No live calls** on this screen.

2. Tap **listening**. Expected: in both discs the mark swells and fades as a purple
   dotted globe comes out of its centre (about a third of a second), rippling, with a
   purple ring outside the disc breathing in and out.
3. Tap **thinking**. Expected: a slowly morphing ring of dots in the aurora colours
   (violet, blue, teal, orange) turning round.
4. Tap **working**. Expected: pale purple particles running round tilted orbits.
5. Tap **speaking**. Expected: a band of dots rolling round the orb, swelling and
   quickening with a made-up voice level.
6. Tap **back to idle**. Expected: speaking for two seconds, then the dots draw back
   into the centre as the mark returns (about a third of a second).
7. Tap **idle**, then **backdrop** to flip black and white, looking at the discs each
   time. Expected: over black, smoked glass — light catching the top-left, a lit rim,
   a clear centre, faint colour bending in at the edge; over white, translucent grey
   glass the white shows through, not a solid purple button. On both, a thin
   rainbow fringe (pink, green, blue) round the rim.
8. Tap **panel edges**, look over black, tap **backdrop**, look over white. Expected:
   the box's rim is lit with a thin rainbow fringe, a lighter band runs along its top
   edge, the purple band bends round the top-left corner, and the next/done pills have
   the same fringe. The box still has its soft shadow over white.

## 2. Colour split in motion

1. Still on Debug states, tap **fast drag**. Expected: both discs sweep left and back;
   while moving, the mark shows a red edge on one side and a blue edge on the other
   along the direction of travel, and they close back into a white mark as it stops.
2. Back out, start the buddy, and drag the disc quickly across the screen and let go.
   Expected: the same colour split while it moves and while it snaps to the edge,
   settling as it lands. It is 64dp docked. **No live calls** (a touch only opens the
   connection).

## 3. A quick action leaves nothing behind

1. Home screen. Tap the buddy (it flies to the top and grows to 80dp), type
   `set a timer for 1 minute`, send.

Expected: the Clock app comes to the front with a **1:00 timer running**; the box
melts away straight away; Heylana says "Timer set for 1 minute."; **a second after it
finishes speaking the disc is idle** at 64dp and there is no box over the Clock.
Logcat: `action: fired intent=timer`, `action: box melts, line aloud=true`,
`settle: scheduled`, `settle: run`. **1 chat, 1 tts.** Stop the timer.
