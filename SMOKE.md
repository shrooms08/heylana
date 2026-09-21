# Smoke test — polish-10-skills (Jupiter, Wallet Earn and the dApp Store, from real screens)

For Minos. Three of Heylana's app notes were rewritten from screenshots of this Seeker, so
when you ask "show me what to tap" she points at buttons that really exist. Install the app,
run `./scripts/a11y.sh` once, then Menu → hold **Start buddy**.

**Live calls this test spends: about 6 chat, 6 spoken lines. Mainnet apps: never approve
anything. Stop at every review screen.**

---

## 1. Jupiter (2–4 chat)

1. Open **Jupiter** (unlock it if it asks).
2. Hold the disc and say **teach me to swap**.
3. She points at **Trade** in the bottom bar. Tap it.
4. She says you are on **Swap** with **Market** and tells you to type an amount. Type a
   small one you actually have (0.001). Too much and the green button says **Insufficient
   SOL balance** — that is expected.
5. Tap the green button. **Stop at the review screen.** She tells you what you would be
   signing; she never approves. Do not approve.

## 2. Wallet Earn (1–2 chat)

1. Open the **Wallet**, home screen.
2. Hold the disc and say **how do I earn on my USDC**.
3. She points at **Start** on the row "Earn …% on your USDC — Powered by kamino", and says the
   rate as "about". If she walks you on, tap **Start**. With no USDC you get the Receive page;
   with USDC the Kamino pages, then Deposit. **Stop at the review.**

## 3. dApp Store (1 chat)

1. Open the **dApp Store**.
2. Hold the disc and say **how do I install an app**.
3. On Home she points at an **Install** button; on Search, at the **search field**, and says
   Install beside the app. Nothing needs installing for the test.

---

## What I would want to hear about

- A button she names that is not on the screen, or a pointer on the wrong thing.
- A walk-through that stops by itself before you have tapped.
- Anything that sounds like she approved, signed or confirmed.
