# Smoke test — polish-13-pulse (what's happening on Solana)

For Minos. Heylana can now answer "what hackathon is going on on Solana right now?" from real,
dated sources instead of guessing from memory. Install the app, run `./scripts/a11y.sh`, wait
five seconds, then Menu → hold **Start buddy**.

**Live calls this test spends: 3 chat, 3 spoken answers.**

---

## 1. Hackathons, right now (1 chat)

1. Hold the disc and say **what hackathon is going on on Solana right now?**
2. She names real bounties or hackathons with their reward, **their deadline**, and says they are
   open. Under the answer is a chip like **"Superteam Earn: …"** — tap it and the listing opens.
3. She never says something is open or closed unless the listing says so.

## 2. This week (1 chat)

1. Hold the disc and say **what's new on Solana this week?**
2. She gives dated items — news, a release — each with the day ("on October 6"), and one or two
   chips. The answer is complete, never cut off mid-sentence.

## 3. The control: not everything is news (1 chat)

1. Hold the disc and say **what is a PDA?**
2. She answers from the Solana docs as before, with a docs chip. (In Logcat there is **no**
   `brain: solana pulse` line for this one.)

## 4. The Home chip (0 chat, or 1 if you tap it)

Open Heylana's Home screen: the second row of chips now has **What's new on Solana**. Tapping it
asks the question from section 2.
