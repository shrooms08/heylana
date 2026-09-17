# Smoke test — clear glass lit from the top-left, readable smoke, purple streak

For Minos, on the Seeker.

**Live calls this test spends: 0.** Everything is on the debug states screen.

References in `design/refs`: `heylana_purple_motion.gif` (the streak). The two still PNGs
(`heylana_liquid_glass.png`, `heylana_liquid_glass_dark.png`) were not in the repo when
this was built; `liquid_glass_render.py` renders the same scene, but with the old light
sign (its rim is lit at the bottom-right; this build lights the top-left, as asked).

## 0. Set up

Install this build, run `./scripts/a11y.sh`. Heylana → Settings → scroll to the bottom →
**Debug states**. **backdrop** flips the page between black and white.

## 1. Idle disc, black and white

1. Tap **idle**. Over black: two dark clear discs, the rim brightest along the top and
   top-left, fading to a faint shade at the bottom-right; a white mark at 70%; a soft
   shadow below. No purple.
2. **backdrop** (white): light grey smoked discs with the same top-lit rim and the shadow.
   The white mark is faint (measured 1.19:1 against the face).

## 2. Panel edges, black and white

1. Tap **panel edges**. Over black: the top edge and top-left corner lit, the bottom and
   bottom-right shaded, a 1px hairline, the top a touch lighter than the bottom. There
   should be **no darker rectangle inside** the pane any more.
2. **backdrop** (white): a light grey smoked pane with a shadow; white text with a soft
   dark halo. Readable up close, but low contrast (measured 1.32:1).

## 3. Send strip, black and white

1. Tap **send strip**. The words, then **cancel** (black chip, white text) and **confirm**
   (white chip, dark text, a thin dark outline). Over white the confirm chip keeps its
   shape by that outline.

## 4. Purple streak, black and white

1. Tap **purple streak**. Expected: the box and both discs show a purple band of light
   behind the glass, slanting from lower-left to upper-right, sliding from the top-left
   corner to the bottom-right and looping every 6 seconds, with a fainter band trailing
   behind it. It brightens and bends where it crosses the rim. On the discs it is thinner
   and weaker. The text stays readable over it, and most of the pane stays clear at any
   moment.
2. **backdrop** (white): the same, over the light smoke.
3. Compare with `heylana_purple_motion.gif`: same direction, same drift, same trailing
   band.

## 5. Darker glass (optional)

Tap **darker glass** on white over **panel edges**: the pane turns mid grey and the text
is easier to read (measured 2.61:1). Tap again to turn it off.
