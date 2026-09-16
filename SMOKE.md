# Smoke test — skills, and shorter answers

For Minos, on the Seeker, on **devnet**, **Judge plan**, wallet connected.

**Live calls this test spends: 4 questions (`/chat`) and 4 spoken answers (`/tts`).**
Steps 2, 3 and 6 are one question and one spoken answer each; the fourth of each is a
spare, for doing step 6 again if the first try was slow to connect. An answer that
runs long also makes one short extra call to shorten it; it is not a talk, and Logcat
says `answer: over cap` when it happens. Get more and Install talk to GitHub only.

## 0. Set up

1. **Make the skills index public.** Create a public GitHub repo named
   `heylana-skills` under `shrooms08` and copy the *contents* of `skills-index/` into
   its top level on `main`, so `index.json` and `skills/chrome.md` sit at the root.
   (Somewhere else? Build with `-Pheylana.skillsIndexUrl=<raw url of index.json>`.)
   Check in a browser:
   `https://raw.githubusercontent.com/shrooms08/heylana-skills/main/index.json`
2. In `worker`: `npx wrangler deploy` (the shorter-answer retry and the trimmed
   address lookup need this worker), then `npx wrangler tail` in a second terminal.
3. Install this build, run `./scripts/a11y.sh`, start the buddy.
4. On the computer: `adb logcat -s HeylanaState | grep -E "brain:|usage:|answer:|skills:"`

## 1. Five built-ins

1. Heylana → **Settings** → **Skills**.

Expected: the line "Skills are reference notes for one app each. They never act for
you.", then **"5 of 10 active"**, then five cards, all switched on and none greyed:
Seed Vault Wallet, Kamino Earn, Seed Vault signing, Solana dApp Store, Jupiter. Each
says "Built in" and has no Remove button. **No live calls.**

## 2. The Wallet's Earn button

1. Open the Wallet on its home screen. Tap the buddy, type
   `what does the Kamino earn thing do`, send.

Expected: an answer about the USDC Earn Vault lending through Kamino, with its rate
moving and no insurance, in at most 3 short sentences. Logcat's brain line ends
`skill=kamino tokens=3…` (`skill=seed-vault-wallet` is also a pass). **1 chat, 1 tts.**

## 3. Installing from the dApp Store

1. Open the dApp Store on Discover. Tap the buddy, type `how do I install an app`, send.

Expected: the answer says to find the app (Discover or Search) and tap **Install**, and
the pointer boxes an **Install** button on screen. Logcat: `skill=dapp-store`.
**1 chat, 1 tts.** If it starts a walk-through, tap **Done** rather than Next.

## 4. Simulate Free plan

1. Settings → scroll to the debug switches → turn **Simulate Free plan** on.
2. Back, then **Skills**.

Expected: **"3 of 3 active"**. Seed Vault Wallet, Kamino Earn and Seed Vault signing
are on as normal; Solana dApp Store and Jupiter are greyed, switched on, and say
"Over your plan's 3 skills. Switch another off to use it."

3. Switch **Kamino Earn** off.

Expected: Solana dApp Store comes back to full brightness. Switch Kamino Earn on again:
it comes back greyed, since the three places are taken. Switch Kamino Earn off, then on,
as needed to get back to Wallet, Kamino and signing active.

4. Settings → turn **Simulate Free plan** off → Skills.

Expected: "5 of 10 active" again, nothing greyed. **No live calls.**

## 5. Get more

1. Skills → **Get more**.

Expected: a Chrome card appears below with "Tabs, the address bar and menu, and
spotting fake wallet sites." and **Install**.

2. Tap **Install**.

Expected: "Chrome installed." The Chrome card moves up into the list, switched on,
not greyed, with a **Remove** button, and the headline reads **"6 of 10 active"**.
Logcat: `skills: index loaded entries=1`, `skills: installed id=chrome tokens=233
stripped=0`. **No live calls** (GitHub only).

3. Tap **Remove** on Chrome. It disappears, "5 of 10 active".

## 6. What am I signing, short

1. Open the Wallet and start sending 0.05 USDC to your treasury until the send sheet
   shows Sending, To, From, Network fee and Send. Do not tap Send.
2. Tap the buddy, type `what am I signing`, send, and count from sending.

Expected: **two sentences, under 40 words**, spoken, starting within **4 seconds**. For
the shortened treasury address it may be exactly: "The screen shows 0.05 USDC to
7c2y…SxSv. I can't verify a shortened address from here; check it matches who you
meant." — or it names the treasury. Logcat: the brain line has
`skill=seed-vault-wallet`, and the `usage:` line has **input_tokens under 3000** and
`tools=none`. If an `answer: over cap` line appears, the spoken answer is the shortened
one. **1 chat, 1 tts.**

3. Cancel the send in the Wallet.
