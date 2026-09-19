# Smoke test — polish-3

For Minos, on the Seeker, with the worker deployed (it is, with the AssemblyAI key in place).

**Live calls this test spends: about 5 chat, about 12 tts, and ears for 10+ holds (ears and tts
are not counted against the budget).** Never tap Confirm or Pay.

**Before step 1:** the Solana library's search runs on Cloudflare Workers AI's free daily
allowance, which ran out on Sept 19 in the afternoon and comes back at **midnight UTC (1 AM in
Lagos)**. Do step 1 after that, or move the Cloudflare account to Workers Paid. Every other
step works any time.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open **Heylana**. To see which ear won each hold
in step 5, watch along in a terminal with `adb logcat -s HeylanaState | grep "ears="`.

## 1. A source chip from the library (1 chat)

On Home, type **how do priority fees work** and tap the arrow.

Expected: a short answer that names where it came from in a few words ("the Solana docs…",
"the Cookbook…"), with no web address in it, read aloud without one. Under it, a flat chip
with a page icon, for example **Solana Cookbook: How to Add Priority…** or **Solana docs:
Fees**. Tap the chip: Chrome opens that page. Come back to Heylana from the recent-apps
switcher and close the tab in Chrome when you are done.

If no chip appears, the library's allowance is still spent: the answer still comes, and the
worker's log (`cd worker && npx wrangler tail --format pretty`) says `"kb_error": "4006"`.

## 2. An Anchor error (no chat)

1. On Home, paste (or type) **Error Code: ConstraintSeeds. Error Number: 2006** and tap the arrow.

   Expected, at once: "ConstraintSeeds (2006): The PDA passed doesn't match the seeds and
   bump… Usual fix: … The Anchor docs have more." — no web address in the words. Under it, a
   chip **Anchor docs: account constraints**. Tap it: Chrome opens the Anchor docs page on
   account constraints.

2. Menu → hold **Start buddy** until its ring fills. In any app, tap the disc, type the same
   error, tap **ask**. The box shows the same line with a glass chip **Anchor docs: account
   constraints ↗** under it; it stays up about ten seconds after Heylana finishes speaking.
   Tap the chip: the box goes and the page opens.

3. Also: **teach me PDAs** on Home. The lesson's first piece shows a chip **Solana docs:
   PDAs** (1 chat). Say or type **stop** to end it (no chat).

## 3. A long page (1–2 chat)

1. In Chrome open **solana.stackexchange.com/questions/26** (What is a Program Derived
   Address (PDA) exactly?). Stay at the top, so only the question is on screen. Dismiss any
   sign-in sheet with its X.
2. Tap the disc, type **what is this page about**, tap **ask**.

   Expected: an answer about the question on screen, ending with **"That's what's on screen;
   there's more below."**, and a chip **Page: solana.stackexchange.com/…**.

3. Tap the disc again, type **what does the top answer say**, tap **ask**.

   Expected: Heylana says she can only see what's on screen (the question, not the answers
   below) — she never claims to have read the whole page — and the page's chip is first.

## 4. No connection (no chat)

1. Pull down Quick Settings and turn on **Aeroplane mode**.
2. On Home, type **tell me a joke** and tap the arrow.

   Expected: **"No connection."** — nothing else, no error code.

3. Turn Aeroplane mode off again.

## 5. Ten holds: which ear won (a few chats)

With the buddy started, go to the home screen. For each phrase: hold the disc, say it, let go.
The terminal shows one `ears=` line per hold: which ear won, its confidence and how long after
you let go, then every ear's result in brackets.

1. **torch on** (the flashlight comes on; no chat)
2. **turn it off** (it goes off; no chat)
3. **next song** (1 chat; if nothing is playing: "Nothing is playing to control.")
4. **flashlight on** (no chat)
5. **turn it off** (no chat)
6. **pause the music** (1 chat)
7. **torch on** (no chat)
8. **torch off** (no chat)
9. **what's the price of SOL** (1 chat)
10. **tell me a joke** (1 chat)

Expected: every hold is understood; the winner is `deepgram` or `assemblyai` almost every
time (`android` only if both cloud ears missed), and the flashlight ends off.

**Force one ear (optional):** Menu → **Settings** → Debug → **Ears** — each tap moves it Auto →
Deepgram → AssemblyAI → Phone. With one chosen, only that ear listens (the log says
`ears: forced assemblyai` and `all=[assemblyai=…]`). Put it back to **Auto** afterwards.

**Privacy:** Menu → **Privacy**, the voice row reads "To Deepgram and AssemblyAI, to be
written down…". With Ears forced to Deepgram or Phone it names Deepgram alone.

## 6. Plain error lines without spending (for the operator, optional)

Point a debug build at the stub (`heylana.proxyUrl=http://127.0.0.1:8799`, then
`adb reverse tcp:8799 tcp:8799`) and run `python3 scripts/stub-proxy.py 8799` with:

- `--brain-down`: any question says **"I can't reach my brain right now. Try again in a moment."**
- `--server-error --voice-limit`: **"Something went wrong on my side."**, with **"Voice is over
  its limit; text only for now."** under it.
- stop the stub: **"No connection."**

Put `heylana.proxyUrl` back afterwards.

## Put it back

Menu → hold **Start buddy** to stop it if you want it off.
