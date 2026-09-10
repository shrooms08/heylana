# SMOKE TEST — Phase 1: screen-aware chat

The buddy can now read whatever app is on your screen and answer questions about
it in writing.

You will need **your own Anthropic API key** for this test. Have it ready to type
or paste into the Heylana Settings screen. It goes in that one field and nowhere
else — never type it into a browser, a chat, a terminal, or any other app.

Do these in order. After each step, check the screen matches the "You should see"
line before moving on.

---

## Part A — Setup checklist

**1. Open the app.**
Tap the **Heylana** icon in the app list.

You should see: the title **Heylana**, a line of explanation, and four grey cards
in this order:
- Overlay permission
- Screen reading (accessibility)
- Notifications
- API key

Each card has either a **green ✓** on the left, or a blue button to fix it.
At the bottom: a greyed-out **Start buddy** button, the line **"Finish all four
rows above to start the buddy."**, and a **Settings** button.

---

**2. Overlay permission.**
If this card already has a green ✓, skip to step 3.
Otherwise tap **Allow overlay**, turn the switch next to **Heylana** ON, then
come back with the back arrow.

You should see: the Overlay permission card now has a **green ✓** and no button.

---

**3. Screen reading.**
On the **Screen reading (accessibility)** card, tap **Open accessibility
settings**. The card tells you what to do; follow it:

1. Find **Heylana** under **Downloaded apps** or **Installed apps**.
2. Tap **Heylana** and turn the switch on.
3. Confirm the dialog (tap **Allow** / **OK**).

Then press back until you are on the Heylana screen again.

You should see: the Screen reading card now has a **green ✓**.

---

**4. Notifications.**
If this card already has a green ✓, skip to step 5.
Otherwise tap **Allow notifications** and tap **Allow** in the Android popup.

You should see: the Notifications card now has a **green ✓**.

---

**5. API key.**
Tap **Add API key** on the fourth card.

You should see: a screen titled **Settings**, with a card **Anthropic API key**
containing an empty field labelled **Paste your key**, and a card **Model** with
**claude-sonnet-5** already filled in.

---

**6. Enter your key.**
Tap the **Paste your key** field and type or paste **your key**. The characters
show as dots. Then tap **Save**.

You should see: the key field is replaced by **"Key saved · sk-ant-…"** followed
by the last four characters of your key, with a **Replace** button next to it.
Under the Save button the word **Saved.** appears.

---

**7. Back to the checklist.**
Tap **Back**.

You should see: **all four cards now have a green ✓**, and the **Start buddy**
button is now BLUE and tappable. The "Finish all four rows" line is gone.

---

## Part B — Ask about a web page

**8. Start the buddy.**
Tap the blue **Start buddy** button.

You should see: the button label changes to **Stop buddy**, and the **purple
pixel face** appears stuck to the RIGHT edge of the screen, about a third of the
way down.

---

**9. Open a news page in Chrome.**
Press Home, tap **Chrome**, and open any news site — for example type
**bbc.com/news** in the address bar and go. If Chrome asks about accounts, tap
**Use without an account** and dismiss anything else it asks.

You should see: the news page loads, and the **purple buddy is still visible on
top of Chrome**.

---

**10. Open the chat panel.**
Tap the purple face once.

You should see: a **white card** open beside the buddy, on the side with more
room (so if the buddy is at the right edge, the card appears to its LEFT). The
card contains the line **"Ask me about this screen."**, a text field reading
**"Ask about this screen"**, and a purple **Send** button. The keyboard comes up
on its own.

---

**11. Ask the question.**
Type **what is this page about** and tap **Send**.

You should see, immediately: the card text changes to **thinking…**, and the
buddy's face changes — **the eyes look UP and the mouth goes flat**.

Then, after a few seconds: the face returns to its **normal smile**, and the card
shows a short answer of one to three sentences describing that news page — it
should mention the actual headlines or the site, not a generic answer. The text
field is empty again, ready for the next question.

---

**12. Ask a follow-up.**
Type **what should I tap to read the top story** and tap **Send**.

You should see: thinking face, then a short answer naming a headline or link
actually on that page. Heylana tells you what to tap — it never taps for you and
nothing on the page moves by itself.

---

## Part C — Ask about the Android Settings app

**13. Open Android Settings.**
Press Home, then open the phone's **Settings** app (the grey cog icon).

You should see: the Android Settings list (Network & internet, Connected devices,
Apps, Notifications, and so on) — and the **purple buddy still on top of it**.

---

**14. Ask about this screen.**
Tap the purple face, type **how do I change the display settings here**, and tap
**Send**.

You should see: the thinking face, then a short answer that refers to what is
actually on that Settings list — it should point you at the **Display** row (or
tell you to scroll to find it) rather than giving generic advice about a
different phone.

---

**15. Move the buddy and ask again.**
Drag the purple face to the LEFT edge of the screen and let go — it snaps flat to
the left edge. Now tap it.

You should see: the chat card opens **to the RIGHT of the buddy** this time,
because that is now the side with room. The buddy does not jump position.
Ask anything and confirm you still get an answer.

---

**16. Close the panel by tapping away.**
Tap anywhere on the screen well away from the white card.

You should see: the card closes and the keyboard goes away. The purple face stays
exactly where it was.

---

## Part D — Stop

**17. Check the notification.**
Swipe down from the top of the screen.

You should see: a silent notification **"Heylana is on your screen"**. Tap the
small **v** arrow on its right to expand it — a **Stop** button appears.

(While the shade is pulled down the buddy is hidden behind it. That is normal.)

---

**18. Stop the buddy.**
Tap **Stop**.

You should see: the notification disappears, and when you swipe the shade back up
the **purple buddy is gone**.

---

## What to do if something goes wrong

- **The card says "I can't read this screen yet."** — the accessibility service
  got switched off. Redo step 3.
- **The card says "API error 401"** — the key is wrong. Open Heylana → Settings,
  tap **Replace**, enter your key again, Save.
- **The card says "Couldn't reach the API"** — the phone has no internet. Check
  the connection and ask again.
- **The answer is generic and does not mention what is on screen** — that is a
  real failure of this phase. Note which app you were in and report it.

---

## Pass criteria

The phase passes only if ALL of these are true:

- All four checklist rows can be turned green, and **Start buddy** only becomes
  tappable once they are.
- The API key shows as **"Key saved · sk-ant-…"** with a **Replace** button after
  saving, and is never shown in full again.
- Tapping the buddy opens a chat card on the side with more room, with a working
  keyboard.
- Sending a question shows the **eyes-up thinking face**, then a short written
  answer of one to three sentences.
- The answer is clearly about the app actually on screen — the news page in
  Part B, the Settings list in Part C.
- Heylana never taps or types anything by itself. Nothing on the screen underneath
  moves unless you touch it.
- Dragging, snapping to the edge, and the notification **Stop** button all still
  work exactly as in Phase 0.
- Nothing crashes at any step.
