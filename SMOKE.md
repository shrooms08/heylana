# Smoke test — clear liquid glass

For Minos, on the Seeker.

**Live calls this test spends: 0.** Everything is on the debug states screen.

The references are `design/refs/heylana_liquid_glass.png` and
`heylana_liquid_glass_dark.png`. (They were not in the repo when this was built; the
same pictures come out of `design/refs/liquid_glass_render.py`.)

## 0. Set up

Install this build, run `./scripts/a11y.sh`. Heylana → Settings → scroll to the bottom →
**Debug states**. The **backdrop** button flips the page between black and white.

## 1. The disc, idle, over black and white

1. Tap **idle**. Over black: two dark, clear glass discs (64dp and 80dp), no purple
   anywhere; a bright thin rim that is brightest along the bottom-right, a faint dark line
   just inside it, a slightly lighter band near the edge, a white mark at 70%, and a soft
   shadow below. Compare with the discs in the dark reference: same bright lower-right
   rim and clear body; the reference's body looks lighter because it has a teal glow
   behind it to show through.
2. Tap **backdrop** (white). Expected: the discs are almost invisible — clear glass over a
   white page — with the shadow below and a faint rim. The white mark is hard to see.
   That is what Darker glass is for (step 4).

## 2. The panel over black and white

1. Tap **panel edges**, then **backdrop** to see both. Over black: a dark clear pane,
   radius 20dp, with the same bright bottom-right rim and hairline, the top a touch
   lighter than the bottom, white text with a soft shadow, and black chips (step 2, next,
   done) with thin hairlines. No purple band, no rainbow fringe. Over white: a white pane
   with a soft shadow below; the white text is hard to read (it shows mostly as its soft
   shadow).
2. Compare with the panel in the references, in this order:
   - **Edge band**: a lighter band inside the rim, fading in by about 24dp.
   - **Rim**: brightest along the bottom and bottom-right, faint top-left, a 1px hairline
     all round.
   - **Frost**: the reference body is visibly frosted because it bends and blurs a photo
     behind it; on the overlay there is nothing of the app to bend (the system blur does
     that in the real overlay), so the body looks clearer.

## 3. The send strip

1. Tap **send strip** on black and white. Expected: "Send 0.05 USDC to 7c2y…SxSv.
   Confirm?" in white, then **cancel** as a black chip with white text and **confirm** as
   a white chip with dark text, both 44dp tall with 14dp corners — the same pair as the
   references. Over white the confirm chip melts into the page; its dark label still
   shows.

## 4. Darker glass

1. Tap **darker glass** (caption: "darker glass on"), with the white backdrop, on **idle**
   and **send strip**. Expected: the discs and the strip turn into readable grey glass; the
   white mark and the white text show, and confirm stands out. Tap it again to turn it off.
2. The real switch: Settings → **Darker glass**. Turning it on changes the buddy's disc and
   box the next time they draw.
