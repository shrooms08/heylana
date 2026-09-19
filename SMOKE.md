# Smoke test — polish-4-palette

For Minos, on the Seeker. The new colours: a blue-black page, one blue accent, a white orb,
and numbers in a monospace face.

**Live calls this test spends: 1 chat (step 4), about 2 tts.** Never tap Confirm or Pay.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open **Heylana**.

## 1. Home

Expected: the page is near-black with a faint blue cast in the top-right corner and a fainter
one bottom-left — no visible rings or bands. The orb is white dots, with only the outermost
dots faintly blue. The mic button is blue with a dark mic icon. The suggestion chips are dark
pills with a thin grey outline.

## 2. The menu

Tap the menu (top left). Expected: the drawer is a dark grey panel; nothing purple. Hold
**Start buddy**: the ring that fills around the button is blue.

## 3. The voice screen

Tap the mic once. Expected: the glow rising from the bottom and the wave are blue, the big mic
is blue with a dark icon, and the timer's digits don't jitter as they count (monospace). Tap
the close button.

## 4. The buddy (1 chat)

1. In Chrome, open **example.com** (a white page).
2. Tap the disc, type **what is this page about**, tap **ask**.

   Expected while it thinks: a blue light laps the box's edge and the disc; the disc's dots are
   white. The answer: white words in the glass box, with a chip under it.

3. Tap outside the box to close it. Close the example.com tab.

## 5. Numbers

On Home, paste **Error Code: ConstraintSeeds. Error Number: 2006** and tap the arrow (no chat).
Expected: in the answer, "2006" is in a monospace face; the rest is the usual rounded font.
Menu → the Plan row: the date's number ("Nov 9") is monospace too.

## Put it back

Menu → hold **Start buddy** to stop it if you want it off.
