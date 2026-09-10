# SMOKE TEST — Phase 2: pointing, voice out, voice in

Heylana now points at things on screen, reads its answers out loud, and listens
when you hold the buddy down.

Before you start:

- Have **your own Anthropic API key** ready if it is not already saved. It goes in
  the Heylana Settings screen and nowhere else — never into a browser, a chat, a
  terminal, or any other app.
- **Turn the phone's volume up.** Part B and Part D check that Heylana speaks.

Do these in order. After each step, check the screen matches the "You should see"
line before moving on.

---

## Part A — Quick regression (Phase 0 and 1 still work)

**1. Open the app.**
Tap the **Heylana** icon.

You should see: the title **Heylana**, then **five** grey cards: Overlay
permission, Screen reading (accessibility), Notifications, API key, and
**Microphone (optional)**. Each has a green ✓ or a blue button to fix it.

If any of the first four is missing a green ✓, tap its button and follow it
through, then come back. The **Microphone (optional)** row may stay grey for now —
it does not block anything. Part D will need it.

---

**2. Start the buddy.**
Tap the blue **Start buddy** button.

You should see: the label change to **Stop buddy**, and the **purple pixel face**
appear stuck to the RIGHT edge of the screen, about a third of the way down.

---

**3. Open Chrome on a news page.**
Press Home, open **Chrome**, and go to any news site, for example **bbc.com**. If
Chrome asks about accounts, tap **Use without an account**.

You should see: the page load, with the **purple buddy still on top of Chrome**.

---

**4. Ask a typed question.**
Tap the purple face once. Type **what is this page about** and tap **Send**.

You should see: the keyboard drop away, the card read **thinking…**, and the
buddy's eyes look UP. Then a short answer about that actual page, read out loud in
a voice.

---

**5. Drag and snap.**
Press and hold on the face and slide it to the middle-left of the screen, then
lift.

You should see: the face follows your finger, and snaps flat to the **LEFT edge**
when you let go. Tap it — the card now opens on its **RIGHT**.

Tap somewhere well away from the card. The card closes.

Drag the buddy back to the right edge before continuing.

---

## Part B — Pointing, in the Clock app

**6. Open the Clock app on the Alarm tab.**
Press Home and open **Clock**. Tap **Alarm** in the bottom bar if it is not
already selected.

You should see: your list of alarms, a large round **+** button near the bottom
middle of the screen, and the purple buddy floating on top.

---

**7. Ask how to add an alarm.**
Tap the purple face, type **how do I add an alarm**, and tap **Send**.

You should see, in this order:

1. The keyboard disappears and the card reads **thinking…**, eyes UP.
2. A short answer appears, something like "Just tap the plus button at the bottom
   of the screen to add a new alarm."
3. **A purple box appears exactly around the round + button** — hugging it, not
   off to one side, not around the wrong control.
4. **The box pulses**, fading gently brighter and dimmer about once a second.
5. **A curved purple arrow** runs from the buddy down to the box, with an
   arrowhead touching it.
6. **The buddy's eyes cut sideways toward the box**, and its **mouth opens and
   closes** while it talks.
7. **You hear the answer spoken aloud.**

---

**8. Watch the box clear itself.**
Do nothing and count to ten.

You should see: the box and arrow fade away on their own after about 8 seconds,
and the buddy's eyes return to normal. The written answer stays in the card.

---

**9. Check that the box does not block the app.**
Ask again (**how do I add an alarm**) so the box comes back, then, while the box
is still showing, **tap the + button itself**.

You should see: the Clock app opens its new-alarm time picker normally. The box
does not swallow your tap. Press back to leave the time picker without saving.

---

## Part C — Pointing, in Chrome

**10. Go back to the news site.**
Press Home, open **Chrome**, and make sure **bbc.com** is on screen.

---

**11. Ask how to search.**
Tap the purple face, type **how do I search this site**, and tap **Send**.

You should see: a short spoken answer, and a pulsing purple box **around the
search control on the page** — the magnifying glass or search box — with the arrow
running to it from the buddy.

If the page has no visible search control, Heylana should say so in words and
draw **no box at all**. That is correct behaviour, not a failure.

---

## Part D — Voice in

**12. Allow the microphone.**
Open **Heylana** again. If the **Microphone (optional)** card is not green, tap
**Allow microphone** and tap **Allow** in the Android popup.

You should see: the Microphone card turn green.

(You can also just hold the buddy — the first hold asks for the microphone too.)

---

**13. Go back to the Clock app, Alarm tab.**
Press Home, open **Clock**, tap **Alarm**.

---

**14. Hold the buddy and speak.**
**Press and hold** your finger on the purple face and keep it held. Do not slide.

You should see, while you are still holding:

- The chat card opens by itself and reads **listening…**, with **no keyboard**.
- The buddy's **eyes go wide** and a **small red dot pulses** in its top corner.
- Android's own green microphone dot appears at the top of the screen.

Now, still holding, say clearly: **"how do I add an alarm"**.

You should see: **your words appearing in the text field as you speak**.

---

**15. Let go.**
Lift your finger.

You should see: the wide eyes return to normal, the card goes to **thinking…**,
and then the same result as step 7 — a short spoken answer plus a **pulsing purple
box exactly on the + button**.

---

**16. Check that holding and dragging stay separate.**
Press and hold the face again until the card says **listening…**, then — without
lifting — **slide your finger** across the screen and let go somewhere else.

You should see: the listening stops the moment you start sliding, the card closes,
and the buddy simply moves and snaps to the nearest edge. **Nothing is sent** and
no answer appears.

---

**17. Check the three gestures one after another.**

- **Quick tap** → the card opens. Tap again → it closes.
- **Slide** → the buddy moves and snaps to an edge. No card, no listening.
- **Press and hold** → **listening…** and the wide eyes.

You should see: each gesture does only its own thing, every time.

---

## Part E — Mute

**18. Mute Heylana.**
Tap the purple face to open the card. In the **top-right corner of the card**
there is a small **speaker icon**. Tap it.

You should see: the speaker icon change to a **speaker with a line struck through
it**.

---

**19. Ask something while muted.**
Type **what is this screen** and tap **Send**.

You should see: the written answer appear and the purple box still point at
whatever the answer is about — but **no sound at all**, and the buddy's **mouth
stays a smile** instead of opening and closing.

---

**20. Unmute.**
Tap the speaker icon again.

You should see: the line through the speaker disappear. Ask something else — the
answer is spoken aloud again and the mouth moves.

---

**21. Check that mute is remembered.**
Mute it again, then tap **Stop buddy** in the app (or **Stop** in the
notification), then **Start buddy**, then tap the face.

You should see: the speaker icon still shows the line through it — the setting
survived the restart. Unmute it again before you finish.

---

## Part F — Stop

**22. Stop from the notification.**
Swipe down from the top of the screen. Expand the **"Heylana is on your screen"**
notification with the small **v** arrow and tap **Stop**.

You should see: the notification disappear, and the **purple buddy gone** — along
with any purple box that was on screen.

---

## What to do if something goes wrong

- **The box lands next to the button instead of on it** — that is a real failure.
  Note which app and which button, and report it.
- **The answer is spoken but no box appears, and the answer clearly names a
  button** — note the app and the exact question.
- **The card says "Voice input not available on this device, type instead."** —
  this phone has no speech recogniser. Typing still works; skip Part D.
- **The card shows a small "Voice unavailable on this device." note** — the
  phone's text-to-speech would not start. Answers still appear in writing; skip
  the listening-for-sound parts.
- **The card says "I didn't catch that."** — Heylana heard no speech. Hold again
  and speak while holding.
- **The card says "API error 401"** — the key is wrong. Heylana → Settings →
  **Replace**, enter your key, Save.
- **The card says "Couldn't reach the API"** — no internet. Check the connection.

---

## Pass criteria

The phase passes only if ALL of these are true:

- The purple box lands **exactly on** the element the answer talks about, in both
  the Clock app and Chrome.
- The box **pulses**, has an **arrow from the buddy**, and **clears itself after
  about 8 seconds** — and clears at once when you ask again or close the card.
- Taps go **through** the box to the app underneath.
- Answers are **spoken aloud**, and the buddy's mouth moves while it speaks.
- The **speaker icon** silences the voice, and is remembered after a restart.
- **Holding** the buddy listens, your words appear in the field as you speak, and
  letting go sends them.
- Tap, drag and hold each do **only** their own thing; a hold that turns into a
  drag sends nothing.
- Everything from before still works: drag, snap to edge, typed questions, the
  card opening on the side with room, and the notification **Stop**.
- Heylana never taps or types anything itself. Nothing on the screen underneath
  moves unless you touch it.
- Nothing crashes at any step.
