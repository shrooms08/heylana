# Heylana

Heylana is a small glass buddy that floats over every app on the Solana Seeker.
Hold it and talk, or tap it and type, and it answers out loud about whatever is on
your screen — and points at the exact button you need. Ask it to help you *do* something and it walks you there one tap at a time.

Say "teach me how to…" or "show me how…" and every step comes with a one-line reason
before the instruction; ask "why?" on any step to hear the reason for that one. When
it is over, "what did I just do?" gets a short recap — and for a swap, a send or
anything else on chain, what your wallet's recent activity shows actually happened.

Heylana was made by Minos, an independent developer in Lagos,
for the Solana Seeker. It is not made by Solana Mobile or Solana Labs, though it hopes to
be adopted by the Seeker and become part of it; ask "who made you" and it says so in one
line, and Settings → About says the same.

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

## The app

Open Heylana and it is a place to talk, not a settings page. The first time: sign in with
your wallet (Seed Vault signs one message; no transaction), say what to call you, and
switch on four things — showing over other apps, screen reading, notifications and,
if you like, the microphone — each with one line on why. After that it opens on Home: a
slowly breathing ring of dots, "Hi, <name>. What do you need?", six suggestions — What am I
signing?, Check my balance, Explain this screen, Learn Solana, Set a timer, Play a song — and
a message bar with a mic. While the buddy is running it also says "Watching for signing
screens." under the greeting. Ask anything; Heylana answers in her voice and in a strip under the orb. Tap or hold
the mic to talk. Setting a timer or opening an app works from here too. Nothing in the
app reads your screen; to ask about another app, start the buddy and tap it there.

Heylana is also a Solana tutor. "Teach me PDAs" — or the Learn Solana suggestion, which
opens the topics in two tracks, Build and Infrastructure — starts a short lesson: four to
six small pieces, each under 40 spoken words and followed by one question to check it
landed. Answer by voice or typing; a right answer moves on, a wrong one gets explained
another way. Say "skip", "slower", "example", "why" or "stop" at any point. It ends with a
one-line recap and, if memory is on, "knows PDAs" and the date in Menu, Memory. Lessons
work in the app and over any app; over Solana's docs or Playground in the browser, "explain
this" explains the paragraph or code in view and "why" goes one level deeper. Each lesson
is taught only from Heylana's own hand-checked notes, never from the screen.

When an answer comes from somewhere you can read — Heylana's Solana library, the table of
known errors, a lesson's topic, or the web page you are on — up to two small chips sit under
it with the source's name ("Solana Cookbook: priority fees", "Anchor docs: account
constraints"); a tap opens that page in the browser. The link is only ever on the chip:
Heylana never reads a web address aloud, and says "the Anchor docs have more" instead. An
answer with chips stays up ten seconds after it is said, so there is time to tap one.

Asked about a long page, Heylana answers from the part on screen and says so: "That's what's
on screen; there's more below." It never claims to have read the whole page; if the answer
is further down, it says it can only see what's on screen and offers the page's own chip.

When something fails, Heylana says one of five plain things and nothing else: "I can't reach
my brain right now. Try again in a moment.", "Voice is over its limit; text only for now.",
"I didn't catch that.", "No connection." or "Something went wrong on my side." A
provider's own error text never reaches the screen or the voice.

On the buddy, a small label on its box says what it is doing — reading, thinking,
preparing, simulating, approve in wallet, sent, working, watching, heads up — and touching
the buddy while it talks stops it mid-sentence.

**It wakes on its own at the one moment that matters.** When a wallet's confirm sheet or
Seed Vault comes up, the buddy shows one line beside itself — what kind of request it is,
the amount and who it is for, "tap me to check it" — without being asked and without
speaking. It does the same for a screen asking for a recovery phrase and for a domain that
is a copy of a real one. One line per screen, gone after twelve seconds, and every touch
outside it still goes to the app underneath. Settings → Watch signing screens turns it off.

Starting and stopping the buddy, and turning memory on or off, take a deliberate hold: a
round button whose ring fills over a little over a second, a tick, and it happens — let go
early and nothing does. The buddy pops out at the side of the screen when it starts.

The menu holds the buddy's start button, your plan, your own API key (Advanced), exactly what leaves the phone (Privacy), and Settings: the voice, Dark or
Light, and Stop buddy.

## Plans

| Plan  | In the app              | Talks                                   | How you get it                                   |
|-------|-------------------------|-----------------------------------------|--------------------------------------------------|
| Free  | 30 talks a month        | 30 a calendar month (+20 welcome, once) | Default. The 20 come the first time a wallet connects. |
| Pro   | Unlimited talks         | Unlimited                               | $5 for 30 days, or $40 for a year (two months free), paid once in USDC or SKR from Seed Vault. Stacks. |
| Judge | Unlimited until Nov 9   | Unlimited                               | The judge code. Lasts until the end of Nov 9, 2026 (UTC). |

A talk is a question that got an answer; a refused or failed one does not count.
Plans follow the wallet; without one, the phone is a Free account with no welcome
talks. Behind the scenes Heylana carries reference notes for the apps it knows best —
where things are, how common tasks go, what to watch out for — loaded only while that app
is on screen, one at a time, and never shown as a feature. Eight are built in (Seed Vault
Wallet, Kamino Earn, Seed Vault signing, Solana dApp Store, Jupiter, YouTube, Spotify,
and x402 payments — that one is for no single app and loads whenever you ask about a 402
payment request). A note never acts for you: it cannot authorise a send, a sign or a
tap, and anything in it that reads like an order is removed before it is stored.
Separately, every phone on Free has a daily budget guard: 150 questions, 150 spoken
answers and 300 listens a day. On Pro and Judge questions and spoken answers are
unlimited, with a 2000-a-day ceiling against abuse. Heylana's voice is Deepgram's Aura
(Hera or Aries, picked in Settings); if the voice cannot be had — the day's limit,
Google's quota, or too slow — the answer is shown as text and Heylana stays silent.

**She starts speaking before she has finished thinking.** A spoken question used to be two
trips: ask, wait for the whole answer, then ask for the voice and wait again. Now it is one.
The answer is written straight into the reply as it comes, and the moment a **sentence** is
finished it goes to the voice and its audio starts coming back — while the rest is still
being written. Measured on the Seeker over ten questions on Sept 20, the wait from letting go
of the buddy to the first spoken word fell from **3.5s to 3.0s** at the median, and the model's
share of it from about 1.8s to 0.7s. Where an answer is not plain prose — a send, a quick
action, a walk-through that points at things — nothing is spoken early and the phone says it
the old way, so no answer is ever said twice or said in part.

## Roadmap

- **Skill market.** A public list of more reference notes (Chrome is the first), each
  installed, switched on and off, and counted against the plan ("3 of 3 active"), from a
  Skill market in the menu. It is built and switched off: no menu row, no screen, and the
  public list is never downloaded. Built-in notes load as before.

## Credits

The orbs the buddy turns into while it listens, thinks, works and speaks are a port
of [thinking-orbs](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/thinking-orbs)
by Jakub Antalik (MIT licence). The glow that laps the glass while it thinks, listens
and speaks is a port of his [border-beam](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/border-beam)
(MIT licence), and the way the box grows out of the disc like liquid follows his
[liquid-gooey](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/liquid-gooey)
(MIT licence).

## Design tokens

One palette, after the colour rules of Minos's sibling product Glance (its fonts and
components are not used). The values live in two places only — `ui/HeylanaTokens` for the
overlay and `ui/theme/Theme.kt` for the app, which reads the same tokens — and nothing else
in the code writes a colour down.

| Token | Value | Use |
|---|---|---|
| Background | `#0A0A0E` | Every in-app screen, under two fixed glows: the accent at 8% centred on the top-right corner (60% of the width, 40% of the height) and at 5% on the bottom-left, fading smoothly and dithered, so the page reads blue-black and never as a gradient. The overlay has no glows. |
| Accent | `#5B8CFF` | The one brand colour: the mic button, the listening ring, the border beam, the task progress rail, the pointer's box and arrow on screen, selected rows and switches, the streak inside the glass. |
| Accent hover | `#7BA3FF` | The lit side of the voice screen's mic and glow, and the bloom behind an active disc. |
| On accent | `#08122C` | Words and icons on an accent fill — never white. |
| Accent text | `#93B3FF` | Accent words on the page ("Continue without a wallet", "Allow"). |
| Accent soft | accent at 14% | A selected row. |
| Text | `#F4F4F6` | Primary words; the orb's dots. |
| Text 2 | `#C8C8D2` | Second-rank words: summaries, subtitles. |
| Muted | `#8B8B96` | Labels, hints, section heads. |
| Success / warn / danger | `#6FE39F` / `#FF9F45` / `#FF7E6E` | Status only, never decoration: a passed simulation and a done tick, a warning, a failure. |
| Hairline / strong / solid border | white 9% / white 13% / `#2F2F3A` | Edges; the chips' 1dp outline is the solid one. |
| Chip fill | `#1D1D25` | Suggestion chips and the message bar. |

**The orb is white.** On Home, in the voice screen and on the buddy's disc in every live state,
the dots are the text colour with the library's own depth shading, and only the outermost ring
takes a faint accent cast (at most 30% toward `#5B8CFF`, easing in over the outer fifth).

**The glass is unchanged except its accent**: the overlay's material, smoke, rim and light
are as before; its streak, beam and bands follow the accent.

**Numbers are monospace.** Balances, amounts, prices, percentages, counts, the plan's talks
and the voice timer are set in the phone's monospace face with tabular figures, inside
otherwise-Outfit text (in answers, the strip, row subtitles, the Go Pro sheet and the
overlay's box), so figures line up and ticking values don't jitter. Everything else is Outfit.

**Contrast on `#0A0A0E`** (WCAG), with the worst case beside it:

| Colour | On the page | Worst place | |
|---|---|---|---|
| Text `#F4F4F6` | 17.99 | 15.24 on a chip | AA |
| Text 2 `#C8C8D2` | 11.90 | 10.08 on a chip | AA |
| Muted `#8B8B96` | 5.86 | 4.97 on a chip | AA |
| Accent `#5B8CFF` | 6.25 | 5.29 on a chip | AA |
| Accent text `#93B3FF` | 9.54 | 8.08 on a chip | AA |
| Success / warn / danger | 12.39 / 9.69 / 7.96 | 10.49 / 8.21 / 6.74 on a chip | AA |
| On accent `#08122C` on the accent | 5.86 | — | AA (white on the accent would be 3.16, which is why it is not white) |

The top-right glow's centre, the brightest point of the page, is `#101421`; everything above
still clears 4.5 there (muted 5.45, accent 5.80).

## Privacy promises

Heylana reads the screen when you ask, and — while "Watch signing screens" is on — when a
new screen comes up, to see whether it is a wallet asking for a signature or a page asking
for your recovery phrase. **That read never leaves the phone**: it is used for the one line
it shows you and then dropped, and nothing about it is sent anywhere unless you then ask a
question. Switch it off in Settings and nothing is read unless you ask. It also watches for
your tap only while it is pointing at something (or during a task you started). There are
no screenshots: the screen is turned into a short text list of the labels on it.

The phishing list is **downloaded to your phone** once a day and checked there, so no web
address you visit is ever sent to Heylana or to anyone else.

Exactly what leaves the phone, and where it goes:

1. **Your question and that text list of the screen** — to Heylana's server (a
   Cloudflare Worker), which passes it to **Anthropic** to write the answer. With
   it go up to your last three questions and answers from the same app (at most
   600 characters, forgotten after ten minutes or when the buddy stops) and, on
   the first answer after the buddy starts, the name you asked to be called.
   Questions asked in the app itself carry no screen at all.
2. **Your voice, only while you hold the buddy or use the mic in the app** — to **Deepgram**
   and, when it is listening (it is unless switched off on Heylana's server or in a debug
   build), **AssemblyAI**, to be written down. The phone connects to each directly with a
   pass that stops working within two minutes (AssemblyAI's opens one session only); the
   phone's own recogniser listens too, and the best transcript is used. Both are also sent
   a fixed list of Solana words to listen for; never anything from your screen. The app's
   Privacy screen names AssemblyAI exactly when it is listening.
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
   name in your question or on a signing screen, go to a **Solana RPC provider
   (RPC Fast, or Helius when it stands in)**; token prices come from **Jupiter**; .sol names from **Bonfida's**
   public resolver. Nothing else from the screen goes to them.
   For how Solana works, and for error messages, Heylana can search its own **Solana
   library**: the Solana, Anchor and Solana Mobile docs, the Solana Cookbook, top answers
   from **Solana Stack Exchange** (shared under CC BY-SA, kept with each author's name and a
   link, and named when an answer uses one) and Agave and Anchor release notes. The few
   search words Heylana writes go to **Cloudflare Workers AI**, inside Heylana's server, to be
   matched against the library. The library holds public text only: nothing you say, no
   screen and nothing about you is ever added to it.

**Heylana prepares, you sign. Always.** Heylana never signs and never sends: every
transfer is shown in Seed Vault, and only you can approve it there. Every send needs your
approval in Seed Vault, unless you marked Heylana as trusted there. We recommend you
don't: the first send's strip says so ("Don't tick 'trust this app'"), and if Seed Vault
ever signs within a second and a half of opening, Heylana tells you it signed
automatically and how to undo that in the Wallet's connected apps. A recipient only
ever comes from your own words, never from the screen, and more than a quarter of a
balance has to be asked for twice.

**Checked before you sign.** Every send and every Pro payment is built by Heylana's
server exactly as it will be signed, then run through Solana's own simulation on the
network it will land on. The strip shows what it will do — from, to (and who that is),
amount, token, network fee, any account it opens, the network — and **Simulation
passed** before Confirm can be tapped. If the simulation fails, Heylana says why in
plain words ("Not enough USDC.", "The recipient's USDC account needs creating, fee
0.002 SOL, and there isn't enough SOL for it.", "This is for mainnet-beta, but Heylana
is on devnet.") and the wallet never opens. The phone also checks that what it hands
Seed Vault is exactly the transfer you confirmed. Afterwards Heylana says what happened:
sent, with its signature; rejected in the wallet; expired before signing; not confirmed
yet (it never sends a second copy); or not found — check your wallet.

8. **Skills** — nothing. The public list of more skills is on the roadmap and is
   never downloaded; the built-in ones are text inside the app.
9. **Memory, only if you turn it on** — to Heylana's server, kept against your wallet:
   short notes you asked it to keep ("remember that I'm new to Solana"), things you say
   about yourself ("I use Jupiter for swaps" — kept as you say it, with a "Remembered" chip,
   and "forget that" takes it straight back), preferences you said yes to, and lessons you
   finished — at most 60 lines. Never what's on your screen,
   never an address or an amount: the server refuses both. Up to 12 of them go with your
   questions (to Anthropic, as "About the user"), not with your quick actions. See, delete
   or wipe them in Menu, Memory; turning it off keeps nothing. You're asked once, when you
   first sign in.

10. **Crash reports, if turned on in the build** — when the app or Heylana's server
    crashes, what went wrong goes to **Sentry**: the error, the app version and the
    phone model. No screen text, no screenshots, no taps, no name, and every Solana
    address and key is removed before it leaves.

Never: the screen never goes to Deepgram, AssemblyAI or Google's voice. None of Heylana's own keys is
ever on the phone; the only key it ever holds is one you typed in yourself. What Heylana reads off the screen is used for one request and then
dropped — never logged, never saved. With "use my own key" (Advanced), your questions
still go through Heylana's server: your key goes with each one, kept encrypted on the phone
between times, and the server uses it for that question only and never stores or logs it.

## What Heylana can and cannot catch

Heylana is a second pair of eyes at the moment you sign. It is not an auditor, and this is
the honest boundary of it.

**What it can tell you.**

- **What a transaction it built would actually do.** Every send and every Pro payment is
  built by Heylana's server and then read back out of its own bytes, instruction by
  instruction: a transfer with who gets what, an approval or delegate with which token and
  how much, a change of authority, a closed account, and a program it does not recognise
  named rather than guessed at. Those lines sit under the confirmation strip. Anything that
  would hand someone else power over an account is refused outright — a send does not do
  that — and a simulation showing more leaving the wallet than the amount you confirmed and
  its fee stops it too.
- **What kind of request is on a signing screen.** On Seed Vault or a wallet's confirm
  sheet, Heylana reads the screen's own words and says which kind of thing it is: a
  transfer, an approval or delegate (and that it lets someone move your tokens later, with
  nothing moving now), a change of authority, a close, a spending cap with no limit. The
  amounts it quotes are always the ones the screen shows.
- **Whether you have dealt with an address before.** "First time you have sent to this
  address" means it is in neither the record of addresses Heylana has sent to for this
  wallet nor the counterparties of its recent transactions. It is not a claim about your
  whole history, and it is only ever asked while memory is on.
- **A screen asking for your recovery phrase**, in any app, and **a domain that is a copy of
  a real one** (phanton.app, jup1.ag, rnagiceden.io), or one on a public list of known
  crypto phishing sites.

**What it cannot.**

- **It cannot read a transaction another app is about to sign.** On a wallet's screen it has
  the words on that screen and nothing else: if the wallet does not say an amount, Heylana
  cannot know it, and it says so rather than guessing.
- **It cannot verify a shortened address.** 7c2y…SxSv could be any of a great many
  addresses. It matches one against addresses you already know and, when it cannot, says
  exactly that.
- **It cannot tell you a program is honest.** It can say you have never used it before, and
  what the instruction would do. It cannot audit what the program does once it runs.
- **It cannot promise a site is safe.** The blocklist is a public one and always behind the
  newest scams (Scam Sniffer's open feed runs a week behind their own); the look-alike check
  only knows the forty real domains it carries. A site nobody has reported yet, under a name
  nothing like a real one, passes both.
- **It never says "safe".** Not about a transaction, not about a site, not about an address.
  Every line says what was found and leaves the deciding to you.

**Where the lists come from.** The blocklist is built from two public sources, both checked
on Sept 20 2026: [scamsniffer/scam-database](https://github.com/scamsniffer/scam-database)
(GPL-3.0), appended to daily and the feed Phantom's own product uses, read a day at a time
from its archive files; and [phantom/blocklist](https://github.com/phantom/blocklist), about
2,300 hand-picked Solana phishing domains, frozen since January 2025 and used as a seed
rather than a live source. Heylana keeps the Solana-relevant slice of them — about 1,200
domains, a 26 KB download — because a parcel-delivery scam is someone else's job.

## What Heylana may do: the tool registry

Every tool the model can be offered, and every action Heylana can take, is one entry in
the server's registry (`worker/src/registry.ts`): a name, a version, a JSON schema and a
risk class. The model proposes; the registry decides; the phone's own checks (the send
and action guards) come first, and the registry is the second. Unknown tools, tools not
offered for the question, and arguments that do not fit their schema — an extra field
included — are rejected, and every decision is logged with the tool and its class.

| Class | Meaning | Entries |
|-------|---------|---------|
| R0 | Reads public data | get_price, explain_address, resolve_name |
| R1 | Reads your own data | get_balances, recent_activity |
| R2 | Prepares, no side effect; or a phone action done in front of you | prepare_send, propose_send, propose_action (alarm, timer, apps, pages, search, music, camera, settings), build_transfer (the preview and its simulation) |
| R3 | A side effect that needs your confirmation | send, pay (the bytes for Seed Vault, only after Confirm or Pay), message, reminder (only after the phone's guard found every part in your words) |
| R4 | Never | sign_transaction, sign_message, submit_transaction, export_seed_phrase, reveal_private_key |

An R3 action needs a confirmation token from the server, issued only when the app reports
you confirmed it — Confirm on the strip, Pay on Go Pro, or the phone's guard firing a text
or a reminder the model proposed — and only for something the server itself prepared for
you: a send or payment whose simulation passed, or an action it proposed to this phone.
A token is good for five minutes and one thing. No model tool is R3 or R4. (The technical
blueprint puts transfers in a separate R4 "high impact" class; here R4 means never, and
transfers are R3 with the strictest confirmation — a passing simulation, the strip, and
the wallet's own screen.)

## Infrastructure

**Two RPC providers, one fallback.** Every chain call the server makes — balances, token
accounts, account information, signatures, simulations, blockhashes, signature statuses and
.skr name lookups — goes through one function. On mainnet it asks **RPC Fast** first and
**Helius** second (`RPC_PRIMARY` swaps them); a call that fails, comes back with an error
worth retrying (the node is behind, unhealthy or busy), or takes longer than 1.2 seconds is
tried once on the other, and a second failure is a plain sentence, never a provider's raw
words. On devnet there is one address and no fallback. Addresses are secrets: the logs and
the endpoint below name the provider, never the URL.

**What is measured.** Each call is logged with its method, provider, milliseconds and whether
it was served, came from the fallback or failed, and each request's own log line carries the
calls it made and their total. A record a day in the server's store (kept 120 days) counts:
active wallets (by a salted hash — counted, never listed), questions by model with their
input and output tokens, characters spoken by each voice, listening sessions by each ear,
chain calls and milliseconds by provider, sends prepared and confirmed, and Pro payments with
what they came to. A price sheet turns those into an estimated daily cost; the estimate comes
with the sheet it used, because prices change and this is a budget watch, not a bill. The
listening figure is sessions times an assumed session length: the server mints the pass and
never hears the audio.

`GET /admin/usage?days=30`, behind `ADMIN_SECRET` in `X-Heylana-Admin`, returns those days,
their costs, the totals, and the **median and p95 of every provider and method over the last
24 hours**, so RPC Fast and Helius can be compared from where the phone actually is. Without
the secret the route does not exist. Nothing personal is in it: no wallet, no device, no words.
Counters are written once per request, so two requests landing together can lose an increment
— close, like the talk counts, not exact.

## Architecture in ten lines

1. Android app (Kotlin, minSdk 31): a foreground overlay service draws the buddy in Views with one glass recipe; the app itself (sign in, Home with the orb and chat, voice, menu, settings) is Compose, flat and dark, with thinking-orbs as its character.
2. An accessibility service reads the screen only on request and turns it into a numbered text listing.
3. The app sends the question and listing to a Cloudflare Worker, naming only the kind of work (quick or task); the worker picks the model, holds every key, and for Solana questions runs lookups first (balances, prices, addresses, activity, names, send checks).
4. The model replies with strict JSON: what to say, which element to point at, and whether this is a multi-step task.
5. A separate, untouchable window draws the pointer; during a task each step re-reads the screen.
6. Deepgram, AssemblyAI and the phone's recogniser all listen from the long press (the two cloud ears share one microphone); the first cloud final waits a moment for the other and the more confident wins, the phone's words are the fallback; an ordinary answer comes back down one connection, the worker speaking each sentence to Deepgram Aura as the model finishes writing it and streaming the audio on as it is made, and if the voice cannot be had the answer is shown as text instead.
7. Wallets connect over Mobile Wallet Adapter to Seed Vault; signing a message earns a 30-day session sealed by the worker.
8. Plans, talks and profiles live in Workers KV, keyed by wallet, or by install id without one.
9. Pro: the phone builds a USDC or SKR transfer with a unique reference, Seed Vault signs and sends it, and the worker verifies it on chain before extending Pro.
10. The worker enforces the daily caps and monthly talk limits, scrubs every error of anything secret, and counts what each day cost (models, voice, ears, chain calls) behind /admin/usage.

## Known issues

- **The knowledge base's search runs on Workers AI's free allowance** (10,000 "neurons" a
  day). When it is used up, searches fail until midnight UTC: answers still come, without a
  source chip, and the worker's log says `kb_error: "4006"`. Moving the Cloudflare account to
  Workers Paid removes the limit.

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
- **Texting a name uses your contacts, on the phone.** The first time, Android asks whether
  Heylana may read your contacts; after that "text Ada" opens Messages on Ada with the words
  written. Contacts are read on the phone only, never sent anywhere. Two people who fit the
  name equally get a question instead of a guess.
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
