# SMOKE TEST — Phase 0: floating overlay buddy

Do these in order on the emulator. After each step, check the screen matches the
"You should see" line before moving on.

---

**1. Open the app.**
Tap the **Heylana** icon in the app list.

You should see: a white screen with the large title **Heylana** at the top.
Under it the line **"Overlay permission: not granted"**. Below that, the
**Allow overlay** button is BLUE and tappable, and the **Start buddy** button
under it is GREY and NOT tappable.

---

**2. Grant the overlay permission.**
Tap the blue **Allow overlay** button.

You should see: an Android settings screen titled **"Display over other apps"**
with **Heylana** listed and a switch that is OFF.

---

**3. Turn the switch on.**
Tap the switch next to **Heylana** so it turns blue/ON.

You should see: the switch is now ON.

---

**4. Go back to the app.**
Tap the back arrow (or swipe from the left edge) until you are back on the
Heylana screen.

You should see: the line now reads **"Overlay permission: granted"**. The
**Allow overlay** button is now GREY and NOT tappable. The **Start buddy**
button is now BLUE and tappable.

---

**5. Start the buddy.**
Tap the blue **Start buddy** button.

You should see: an Android popup asking **"Allow Heylana to send you
notifications?"**. Tap **Allow**.

Then: the button label changes to **Stop buddy**, and a **purple square with a
pixel face** (two white eyes with dark pupils, and a white pixel smile) appears
stuck to the **RIGHT edge** of the screen, about a third of the way down.

---

**6. Tap the buddy once.**
Tap directly on the purple face. Do not slide your finger — just a quick tap.

You should see: a white speech bubble appear **to the LEFT of** the purple face,
with a small pointed tail touching the face, containing the text
**hey, I'm Heylana**. The purple face does NOT move.

---

**7. Tap the buddy again.**
Tap the purple face once more.

You should see: the speech bubble disappear. The purple face stays where it was.

---

**8. Drag the buddy to the left.**
Press and hold the purple face, slide your finger to the middle-left of the
screen, then lift your finger.

You should see: the face follows your finger while you drag. The moment you lift
your finger, it slides on its own and sticks flat against the **LEFT edge** of
the screen, at whatever height you released it.

---

**9. Tap the buddy on the left edge.**
Tap the purple face once.

You should see: the speech bubble appear **to the RIGHT of** the face this time,
tail pointing left at the face, again reading **hey, I'm Heylana**. The face does
NOT move. Tap once more to hide the bubble.

---

**10. Drag it back to the right.**
Press and hold the face, slide it to the right half of the screen, lift.

You should see: it snaps flat against the **RIGHT edge**.

---

**11. Go to the home screen.**
Swipe up from the bottom bar (or press Home).

You should see: the phone home screen with its wallpaper and app icons — and the
purple buddy still floating on top of it, at the same edge and height.

---

**12. Open Chrome — THE KEY TEST.**
Tap the **Chrome** icon at the bottom of the home screen. If Chrome shows a
welcome screen, tap **Use without an account** and dismiss anything else it asks.

You should see: Chrome fills the screen, and the **purple buddy is still visible
on top of Chrome**, at the same edge and height. It is drawn over Chrome, not
behind it.

---

**13. Prove it still works over Chrome.**
Tap the purple face while Chrome is open.

You should see: the speech bubble **hey, I'm Heylana** appear next to the face,
on top of Chrome. Tap again to hide it. Drag the face to the other edge — it
still drags and still snaps, all while Chrome is underneath.

---

**14. Check the notification.**
Swipe down from the top of the screen to open the notification shade.

You should see: a silent notification reading **"Heylana is on your screen"**.
Tap the small **v** arrow on its right to expand it — a **Stop** button appears
underneath.

(Note: while the notification shade is pulled down, the buddy is hidden behind
it. That is normal Android behaviour — the shade always sits above everything.)

---

**15. Stop the buddy from the notification.**
Tap **Stop** in that notification.

You should see: the notification disappears, and when you swipe the shade back up
the **purple buddy is gone** from the screen.

---

**16. Start and stop from the app.**
Open **Heylana** again. Tap **Start buddy** — the purple face reappears at the
right edge. Tap **Stop buddy** — the face disappears immediately.

You should see: the button label toggles between **Start buddy** and
**Stop buddy** each time, and the face appears and disappears to match.

---

## Pass criteria

The phase passes only if ALL of these are true:

- The purple pixel-face sprite appears on the screen.
- It can be dragged anywhere with a finger.
- It always snaps to the left or right edge when released.
- A single tap shows the bubble **hey, I'm Heylana**; another tap hides it.
- The bubble appears on the side facing away from the edge, and the sprite does
  not jump when the bubble appears.
- The sprite stays visible on top of Chrome.
- The notification **"Heylana is on your screen"** is present with a working
  **Stop** button.
- Nothing crashes at any step.
