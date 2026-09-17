# Smoke test — quick actions fire

For Minos, on the Seeker, **Judge plan**, wallet connected.

**Live calls this test spends: 4 questions (`/chat`) and 4 spoken answers (`/tts`).**
One of each per step.

## 0. Set up

1. In `worker`: `npx wrangler deploy` (the forced action tool lives in the worker), then
   `npx wrangler tail` in a second terminal.
2. Install this build, run `./scripts/a11y.sh`, start the buddy.
3. On the computer: `adb logcat -s HeylanaState | grep -E "brain:|action:|usage:"`

Each step's Logcat shows, in order: `brain: mode=quick why=quick_action … quick-action=forced`,
`action: raw type=intent intent=…`, `action: guard verdict=allowed …`,
`action: firing intent …`, `action: fired intent=…`. If a step fails, the last of those
lines that appears says where it stopped.

## 1. An alarm

1. Go to the home screen. Tap the buddy, type `set an alarm for 7 tomorrow`, send.

Expected: the Clock app comes to the front with a **7:00 AM** alarm added and on, and
Heylana says **"Alarm set for 7 AM tomorrow."** Logcat: `action: guard verdict=allowed
intent=alarm hour=7 minutes=0`. `wrangler tail`: `"quick_action":{"intent":"alarm","hour":7,…}`.
**1 chat, 1 tts.** Delete the test alarm afterwards.

## 2. A timer

1. Home screen. Tap the buddy, type `set a timer for 5 minutes`, send.

Expected: the Clock app opens on a **5:00 timer, running**, and Heylana says **"Timer set
for 5 minutes."** Logcat: `intent=timer seconds=300`. **1 chat, 1 tts.** Stop the timer.

## 3. Opening an app

1. Home screen. Tap the buddy, type `open the wallet`, send.

Expected: the **Wallet** app comes to the front — no pointer at its icon — and Heylana
says **"Opening Wallet."** Logcat: `action: open_app match="Wallet"
package=com.solanamobile.wallet score=100`. **1 chat, 1 tts.**

## 4. The dialer, never a call

1. Home screen. Tap the buddy, type `call 0800 123 4567`, send.

Expected: the Phone app opens with **0800 123 4567** in the number field and **no call
placed**; Heylana says "Opening the dialer with 08001234567." Logcat: `intent=dial
digits=11 name=false`. **1 chat, 1 tts.** Back out without calling.
