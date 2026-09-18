# Heylana

Heylana is a small glass buddy that floats over every app on the Solana Seeker.
Hold it and talk, or tap it and type, and it answers out loud about whatever is on
your screen — and points at the exact button you need. Ask it to help you *do* something and it walks you there one tap at a time.

Say "teach me how to…" or "show me how…" and every step comes with a one-line reason
before the instruction; ask "why?" on any step to hear the reason for that one. When
it is over, "what did I just do?" gets a short recap — and for a swap, a send or
anything else on chain, what your wallet's recent activity shows actually happened.

It is good company too: say hello, ask for a joke, what it thinks, or who won the
World Cup, and it answers like a friend in a sentence or two — without reading your
screen, since none of that needs it.

It can also get the phone's own apps to do simple things you ask for: set an alarm
or a timer, open an app or a website, show a place on the map, put a number in the
dialer, search YouTube or the web, play something on Spotify, pause or skip the music,
write a text for you to send, fill in a calendar reminder for you to save, switch the
flashlight, open the camera or a selfie, and open Wi-Fi, Bluetooth, display, sound,
battery or accessibility settings. Every part of the request has to be in your own
words, the phone's own app does it in front of you, and Heylana never places a call,
sends a message or saves an event — you do.

## Who it is for

Seeker owners finding their way around Solana apps (and every other app) who want
a patient guide on the screen they are already looking at, and people who know
their way around but want a quick answer without leaving what they are doing.

## Plans

| Plan  | Talks                                  | Skills | How you get it                                   |
|-------|----------------------------------------|--------|--------------------------------------------------|
| Free  | 30 a calendar month (+20 welcome, once) | 3      | Default. The 20 come the first time a wallet connects. |
| Pro   | Unlimited                              | 10     | $15 for 30 days, paid once in USDC or SKR from Seed Vault. Stacks. |
| Judge | Unlimited                              | 10     | The judge code. Lasts until the end of Nov 9, 2026 (UTC). |

A talk is a question that got an answer; a refused or failed one does not count.
Plans follow the wallet; without one, the phone is a Free account with no welcome
talks. Skills are reference notes for one app each — where things are, how common tasks
go, what to watch out for — loaded only while that app is on screen, one at a time.
Eight are built in (Seed Vault Wallet, Kamino Earn, Seed Vault signing, Solana dApp
Store, Jupiter, YouTube, Spotify, and x402 payments — that one is for no single app and
loads whenever you ask about a 402 payment request); more install from a public list in Settings → Skills. The plan's
cap is how many can be active; the rest show greyed. A skill never acts for you:
it cannot authorise a send, a sign or a tap, and anything in it that reads like an
order is removed before it is stored.
Separately, every phone on Free has a daily budget guard: 150 questions, 150 spoken
answers and 300 listens a day. On Pro and Judge questions and spoken answers are
unlimited, with a 2000-a-day ceiling against abuse. Heylana's voice is Deepgram's Aura
(Hera or Aries, picked in Settings); if the voice cannot be had — the day's limit,
Google's quota, or too slow — the answer is shown as text and Heylana stays silent.

## Credits

The orbs the buddy turns into while it listens, thinks, works and speaks are a port
of [thinking-orbs](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/thinking-orbs)
by Jakub Antalik (MIT licence). The glow that laps the glass while it thinks, listens
and speaks is a port of his [border-beam](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/border-beam)
(MIT licence), and the way the box grows out of the disc like liquid follows his
[liquid-gooey](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/liquid-gooey)
(MIT licence).

## Privacy promises

Nothing is read unless you ask. Heylana reads the screen only when you ask, and
watches for your tap only while it is pointing at something (or during a task you
started). There are no screenshots: the screen is turned into a short text list of
the labels on it.

Exactly what leaves the phone, and where it goes:

1. **Your question and that text list of the screen** — to Heylana's server (a
   Cloudflare Worker), which passes it to **Anthropic** to write the answer. With
   it go up to your last three questions and answers from the same app (at most
   600 characters, forgotten after ten minutes or when the buddy stops) and, on
   the first answer after the buddy starts, the name you asked to be called.
2. **Your voice, only while you hold the buddy** — to **Deepgram**, to be written
   down. The phone connects to Deepgram directly with a key that stops working
   after two minutes. Deepgram is also sent a fixed list of Solana words to listen
   for; never anything from your screen.
3. **The text of the spoken answer** — through Heylana's server to **Deepgram**
   (Aura), to become speech. (Heylana's server can be set to use Google's Gemini
   instead; the app's own privacy line always names the one in use.) Conversation mode, when enabled, uses Gemini Live's
   free tier; Google may use that audio to improve its models.
4. **Your wallet address, a signed sign-in message, and the name you choose** —
   to Heylana's server, kept against your wallet.
5. **Payments** — Seed Vault signs and sends the transaction itself; Heylana's
   server reads it from the Solana chain to check it.
6. **A random install id** — to Heylana's server, to count the daily budget.
7. **Solana lookups, only for Solana questions** — Heylana's server looks things up
   before answering: your connected wallet's address, and any address or .skr/.sol
   name in your question or on a signing screen, go to the **Solana RPC provider
   (Helius)**; token prices come from **Jupiter**; .sol names from **Bonfida's**
   public resolver. Nothing else from the screen goes to them.

**Heylana prepares, you sign. Always.** Heylana never signs and never sends: every
transfer is shown in Seed Vault, and only you can approve it there. A recipient only
ever comes from your own words, never from the screen, and more than a quarter of a
balance has to be asked for twice.

8. **Skills, only when you tap Get more or Install** — the phone downloads the list
   and the skill's text from GitHub. Nothing about you goes with it: no install id,
   no wallet, no screen. A skill itself is text on the phone; it reads nothing and
   sends nothing.

9. **Crash reports, if turned on in the build** — when the app or Heylana's server
   crashes, what went wrong goes to **Sentry**: the error, the app version and the
   phone model. No screen text, no screenshots, no taps, no name, and every Solana
   address and key is removed before it leaves.

Never: the screen never goes to Deepgram or Google's voice. No API key is ever stored
on the phone. What Heylana reads off the screen is used for one request and then
dropped — never logged, never saved. The hidden "use my own key" setting is the
one exception to (1): questions go straight to Anthropic on a key you typed in.

## Architecture in ten lines

1. Android app (Kotlin, minSdk 31): a foreground overlay service draws the buddy in Views with one glass recipe; Settings is Compose.
2. An accessibility service reads the screen only on request and turns it into a numbered text listing.
3. The app sends the question and listing to a Cloudflare Worker, naming only the kind of work (quick or task); the worker picks the model, holds every key, and for Solana questions runs lookups first (balances, prices, addresses, activity, names, send checks).
4. The model replies with strict JSON: what to say, which element to point at, and whether this is a multi-step task.
5. A separate, untouchable window draws the pointer; during a task each step re-reads the screen.
6. Both Deepgram and the phone's recogniser listen from the long press and the better transcript wins; answers stream back as audio from Gemini TTS, and if the voice cannot be had the answer is shown as text instead.
7. Wallets connect over Mobile Wallet Adapter to Seed Vault; signing a message earns a 30-day session sealed by the worker.
8. Plans, talks and profiles live in Workers KV, keyed by wallet, or by install id without one.
9. Pro: the phone builds a USDC or SKR transfer with a unique reference, Seed Vault signs and sends it, and the worker verifies it on chain before extending Pro.
10. The worker enforces the daily caps and monthly talk limits, and scrubs every error of anything secret.

## Known issues

- **.skr names are not looked up.** Solana Mobile documents no public reverse-lookup
  API (names are AllDomains records read on mainnet), so the "What should I call
  you?" sheet starts empty unless you chose a name before.
- **The name is said once.** Heylana greets you by name on the first answer after
  the buddy starts. Later answers are not sent the name, so "what's my name?" is
  not something it can answer.
- **Seed Vault's approval icon is served by Heylana's worker** until heylana.xyz exists.
- **Library pins.** Mobile Wallet Adapter 2.1.1 and sol4k 0.7.0: newer releases need
  Kotlin 2.4.
- **Devnet in Seed Vault Wallet is unconfirmed.** If it refuses devnet, testing needs
  a wallet that supports it.
- **Talk counts are close, not exact.** KV takes a moment to agree across regions, so
  questions sent from two places at once can slip one or two past the limit.
- **SKR with a transfer fee** would arrive short and be refused; not yet confirmed
  whether SKR charges one.
- **Some apps hand over an empty screen** (Chrome often does). Heylana then answers
  from general knowledge, or says to switch screen reading on.
- **A payment that confirms after 60 seconds** unlocks Pro the next time Settings opens.
- **Seed Vault may hide the buddy.** Not yet checked whether Seed Vault's own signing
  screen allows other apps on top; if it does not, ask on the wallet's confirm screen.
- **.sol names may not resolve.** Bonfida's public resolver stops answering .sol after a
  chain milestone until its next release; Heylana says so plainly.
- **.skr names need a mainnet connection** when the worker runs on devnet
  (`MAINNET_RPC_URL`).
- **Addresses are typed or pasted.** Saying a 44-character address aloud does not work;
  names like bob.skr do.
- **Task steps do not look things up.** Tools go with questions; a multi-step walk-through
  uses the screen only.

- **Built-in skills are partly unverified.** Jupiter's notes come from its public docs
  (the app was locked on the test phone), and some Wallet, Kamino, Seed Vault and dApp
  Store labels are marked "(unverified)" until walked on a Seeker. Spotify's notes are all
  from its help pages (it was not installed on the test phone), and YouTube's watch page
  labels are unchecked.
- **The skills index must be public.** It lives in `skills-index/` in this repo for
  now; Get more works once that folder is copied to a public repository at the
  address the app is built with.

- **Long answers cost a second, small call.** An answer over 60 words (40 on a signing
  screen) is asked for again in fewer words once; that call is not counted as a talk.

- **Calling a contact by name opens an empty dialer.** Heylana has no access to
  contacts, so it says who to search for; a number said aloud is filled in.
- **Spotify opens, it does not play.** No Android intent makes Spotify start a song, so
  "play Burna Boy on Spotify" opens Spotify on those results and Heylana says "Tap play."
  Spotify has to be installed and signed in.
- **A selfie asks for the front camera**, but the camera app decides; some open on the back
  camera.
- **Texting a name opens the contact picker** with the message written, since Heylana has
  no access to contacts. A number goes straight to the conversation.
- **Pause needs something playing.** The media keys go to whatever app is playing; with
  nothing playing Heylana says so.
- **Directions show the place, not turn-by-turn.** The map app opens on what you
  named, and you start navigation there.

- **Clear glass washes out over white apps.** Heylana's glass is clear, so over a plain
  white page its words and mark are hard to read. Settings → **Darker glass** adds a
  dark tint under it for people who mostly use light apps.

## Mainnet switch before submission

In `worker/wrangler.toml` and the worker's secrets, then on the phone:

- [ ] `CLUSTER = "mainnet-beta"`
- [ ] `USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"` (Circle's mainnet USDC)
- [ ] `PRICE_USD = "15"`
- [ ] `RPC_URL` secret is a **mainnet** endpoint (`npx wrangler secret put RPC_URL`)
- [ ] `SKR_MINT` and `TREASURY_ADDRESS` are the real mainnet addresses
- [ ] Wallet app on the phone switched to mainnet
- [ ] Redeploy: `npx wrangler deploy` in `worker/`
- [ ] Test: pay one real 15 USDC (Plan shows **Pro until** a date 30 days away), or
      enter the judge code (Plan shows **Judge until Nov 9, 2026**)
