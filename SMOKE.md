# Smoke test — Skill market retired to the roadmap

For Minos, on the Seeker, on the **Judge** plan. The Seeker was not connected when this was
built, so Claude Code could not check it on the phone: this is the first check on a device.

**Live calls this test spends: 1 chat, 1 tts** — one question in the Wallet, to see that the
built-in skills still load.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open **Heylana**.

## 1. The menu has no Skill market

Tap the **menu** icon (top left). Expected, under the small headings:
- **Buddy** — Start buddy with its switch.
- **Account** — **Plan** only, reading **Unlimited until Nov 9**, with "Judge" on the right.
  There is **no Skill market row** and no "8 active" anywhere.
- **More** — Advanced, Privacy, Settings.

Tap the dimmed part to close it. Nothing on any screen mentions skills or "n of n active".

## 2. The market cannot be opened

On the computer: `adb shell am start -S -n xyz.heylana.app/.MainActivity -e screen skills`.
Expected: Heylana opens on **Home**, not a Skill market.
`adb logcat -s HeylanaState` shows no `skills: index` line at any point: the public list is
never downloaded.

## 3. Built-in skills still load (1 chat, 1 tts)

Menu → **Start buddy** on. Open the **Wallet** app. Tap the disc, type **how do I earn on my
USDC**, tap **ask**.

Expected: an answer about the Wallet's Earn vault (Kamino), and in `adb logcat -s
HeylanaState` the `brain:` line says **`skill=kamino`** (or `skill=seed-vault-wallet`), not
`skill=none`. Stop the buddy afterwards.

## 4. Plan words elsewhere

On a Free account (or with a Free wallet) the menu's Plan reads **30 talks a month** with a
thin bar under it and a **Go Pro** button; the Go Pro sheet says "Unlimited talks for 30
days" with no mention of skills. (Skip on Judge.)
