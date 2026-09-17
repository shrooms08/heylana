# Heylana

Heylana is an on-screen AI buddy for the Solana Seeker phone. It lives as a small
draggable sprite floating on top of every other app, always within reach, so the
user can talk to it without leaving whatever they are doing. This repo is a
hackathon build: an Android app (Kotlin, Jetpack Compose, Gradle Kotlin DSL,
package `xyz.heylana.app`, minSdk 31) built in numbered phases. Phase 0 is the
floating overlay buddy — the sprite, the drag-and-snap behaviour and the
foreground service that keeps it alive. AI, network, wallet and accessibility
features come in later phases and must not leak into earlier ones. Phase 1 adds
screen reading and text chat: the buddy reads the app in front of the user on
demand and answers questions about it. Phase 2 makes it a guide: it points at the
one thing on screen the answer is about, speaks its answers, listens when the user
holds it, and walks them through a task one step at a time, re-reading the screen
after each step.

## Package layout

```
xyz.heylana.app
├── MainActivity.kt          setup checklist + start/stop buddy
├── overlay/                 everything that draws on top of other apps
│   ├── BuddyOverlayService  foreground service (specialUse), notification, ask flow
│   ├── BuddyOverlayView     window container: drag, snap-to-edge, panel placement
│   ├── BuddySpriteView      the 96x96dp sprite, drawn in code (no image assets)
│   ├── ChatPanelView        the chat card: question field, Send, answer, mute switch
│   └── HighlightOverlayView the pointer: pulsing box + arrow, in its own window
├── screen/                  reading the app the user is looking at
│   ├── HeylanaAccessibilityService  on-demand screen reads, no continuous work
│   ├── ScreenSnapshot       the element list + its text rendering for the model
│   ├── Keyterms             the names the ears are told to expect
│   └── TapWatch             the rule for noticing the user act on what is pointed at
├── wallet/                  the user's wallet, their plan, and paying for Pro
│   ├── SeedVault            Mobile Wallet Adapter: connect + sign-in message, sign-and-send the payment
│   ├── WalletApi            /wallet/challenge, /wallet/verify, /me, /judge, /pay/*
│   ├── WalletSession        the connected address and its worker session, stored encrypted
│   ├── Cluster              mainnet-beta or devnet, as the worker's /me says
│   ├── PaymentTransaction   the USDC/SKR transferChecked to the treasury, with the reference
│   ├── ProPayment           blockhash → build → Seed Vault → ConfirmPoll (60s)
│   ├── PlanText             every word the Plan card and Go Pro sheet say
│   ├── WalletProblem        whatever stopped a wallet trip, in plain words
│   ├── Profile              what the wallet is called, what to call its owner, cleanName
│   ├── SendTransaction      a SOL transfer, or token account + transferChecked, unsigned
│   ├── SendQuote            a send the worker checked, and the confirmation strip's words
│   ├── SendFlow             a confirmed send: blockhash and Seed Vault on the quote's cluster, then landed
│   └── SendActivity         invisible; hosts SendFlow, since Seed Vault needs an activity to open from
├── brain/                   talking to the model
│   ├── ProxyClient          POST /chat through the proxy; says quick or task, never a model
│   ├── QuotaMessage         the words for talks_cap, daily_cap and an ended session
│   ├── Greeting             the user's name on the first answer after the buddy starts, never after
│   ├── SolanaCore           the Solana knowledge block and rules, and when to load them
│   ├── SolanaApps           Solana apps by package name, each checked against a primary source
│   ├── Routing              quick or task, and whether Solana knowledge and tools go along
│   ├── SigningScan          addresses and amounts on a signing screen; is this a signing screen
│   ├── SendGuard            recipient and amount from the user's own words; the 25% rule
│   ├── AddressText          every address shortened to first4…last4 before it is shown or spoken
│   ├── HeylanaPrompt        the system prompt and user messages, in one editable place
│   └── GuidanceSession      a task in progress: goal, steps given so far, stuck flag
│                            (plus Conversation, the short-term memory)
├── skills/                  per-app reference notes, loaded only while that app is in front
│   ├── Skill / SkillFile    the skills/<id>.md format: front matter, body under 400 tokens
│   ├── SkillSanitiser       strips any line that reads like an order to the model
│   ├── SkillCap / SkillLoader  which skills the plan allows; the one a request carries
│   ├── SkillStore           built-ins from the APK, installed ones in private storage
│   ├── SkillIndex           the public index: read, checked, and each skill downloaded
│   └── SkillsActivity       Settings → Skills: toggles, "n of cap active", Get more, Remove
├── actions/                 quick actions: the phone's own apps do it, Heylana never taps
│   ├── QuickAction          alarm, timer, open_app, open_url, navigate, dial; when the rules load
│   ├── QuickGuard           every argument in the user's own words; times and durations as said
│   ├── QuickIntents         the intent as plain data, the line Heylana says, AppMatcher
│   └── QuickActionRunner    turns it into an Intent and starts it in a new task
├── orbs/                    the disc's living states (a port of thinking-orbs, MIT)
│   ├── OrbEngine            orbits, wave, ribbon and ring geometry, presets; golden-vector tested
│   └── OrbPainter           a frame's dots on the disc face, tinted from the tokens
├── net/                     everything that leaves the phone
│   └── Proxy                the address, the device header, the shared client, the warmup
├── voice/                   Heylana's mouth and ears
│   ├── HeylanaVoice         streams the spoken answer from /tts and plays it as it arrives; silent on failure
│   ├── VoiceFailure         why it stayed silent: 429 daily_cap, quota, timeout, error
│   ├── DeepgramEars         PCM16 over a websocket while the buddy is held, with keyterms
│   ├── Listener             the phone's own recogniser, behind the same Ears interface
│   ├── EarsRace             both ears listen from the long press; this picks whose words win
│   └── MicPermissionActivity  invisible one-shot prompt for the microphone
├── ui/                      how everything looks
│   ├── HeylanaTokens        every colour, size, radius, duration and typeface
│   ├── GlassDrawable        the one liquid-glass recipe, used by every surface
│   └── GlassBlur            asks the window for cross-window blur, honestly
├── settings/                stored configuration
│   ├── HeylanaSettings      EncryptedSharedPreferences: device id, voice, advanced bits
│   ├── DeviceId             the random id the proxy counts a phone's day by
│   └── SettingsActivity     the voice picker, what leaves the phone, advanced, debug
└── ui/theme/                Compose theme (scaffolded)
```

## Look and feel

**Tokens live in `ui/HeylanaTokens`, and nothing that draws carries its own
values.** Colours, radii, spacing, durations, type sizes and weights all come
from there. A magic number in a view is a bug: if a value is a design decision,
or is needed twice, it belongs in the tokens file. The type is Outfit, shipped as
one variable font in `res/font` and instanced at 300, 400 and 500; if the font
resource will not load, the tokens fall back to the platform's light sans.

**The glass is clear, and its numbers are `ui/GlassSpec`.** Clear iOS-style liquid
glass (design-2d), specified by `design/refs/liquid_glass_render.py` as tuned by the
design briefs, with no colour anywhere in the material. Every surface — the disc, the
box, the strip, the task HUD, the pills — draws through `ui/GlassDrawable`:

- **Panels** (radius 20dp, edge band E 30dp, strength 34dp, magnification 0.985) and
  **the disc** (E 20dp, strength 30dp at 80dp; E 16dp, strength 24dp docked at 64dp;
  magnification 0.96; interpolated as it swells): a **12% black smoke** backing (light,
  permanent, no colour), and on API 33+ hardware canvases `ClearGlass`, an AGSL shader
  running the spec per pixel — the band `1 - smoothstep(0.30, 0.80, -d/E)` (refraction
  only), the outward pull of the surface's own backing
  (`strength × band × (0.45 + 0.55 clamp(1 - t2/0.80))`) with 1dp blur in the band and
  saturation 1.18, band brightness +0.05, the gradient +0.07 → −0.04 at full strength
  **8dp in from the edge** (so no inner rectangle), the rim (7.5% of E: +0.10 + 0.32
  ndotl, −0.10 ndotb), the lens line (at 10% of E, 4.5% wide, −0.03), the inner shadow
  (−0.06 ndotb over 0.9E), a 0.05 specular near the top and the 1px hairline
  (0.30 × (0.3 + 0.7 ndotl)).
- **Light comes from the top-left.** `ndotl = clamp(n · LIGHT)`, `ndotb = clamp(n · −LIGHT)`
  with LIGHT (−0.45, −0.89) in screen coordinates: brightest rim along the top edge and
  top-left corner, shade along the bottom and bottom-right. The reference renderer had
  the sign reversed; `GlassSpecTest` compares against it with the corrected sign.
  Below API 33 or on a software canvas the same numbers are gradients, no refraction.
  The backing it refracts is the surface's own — never the screen.
- **Shadow**: 7dp, black 22%, 8dp down, cast outside the shape only (clip-out plus a
  GPU shadow layer), so the glass stays clear and the view stays hardware rendered.
- **Chips**: radius 14dp, at least 44dp tall; black 27% with white text, or selected —
  the ask pill and confirm, and a primary Compose button — white 94% with #1A1A24 text
  and a 1dp black 15% outline so they keep their shape over white.
- **Text** on glass is white (secondary 72% white) with a soft shadow (0 1dp 8dp,
  black 40%). The mark is white, 70% docked and 100% active.
- **Blur behind** stays the system's FLAG_BLUR_BEHIND, at 8dp.

**Legibility over white is still low.** Measured on the debug states screen (WCAG
contrast): body text over the pane on a pure white page 1.32:1 (1.54:1 at the letter
edge, against its own shadow halo), over black 17.8:1; the idle mark over white 1.19:1,
over black 9.6:1. With "Darker glass" (30% more black) over white: text 2.61:1, mark
1.97:1. White text reaches 4.5:1 only over a backing darker than about 54% black.
"Darker glass" in Settings adds that 30% black (`HeylanaSettings.darkerGlass`, copied
into `GlassSpec.darkerGlass` at start and when the switch changes, read by each surface
as it draws). Debug states has a "darker glass" button and `-e darker on` for adb.

**A purple light streak lies behind the glass.** Variant C with motion
(`design/refs/liquid_glass_motion_render.py`, the GIF): #8F5BFF at 75% added into the
surface's own backing, in the shader, where the lens samples — so the rim bends it like
everything else. Its lines run at 122° and it travels along 32°: a gaussian 30dp wide
breathing ±8dp once a pass, plus a trail 90dp behind at 35% and 55dp wide, from fully
off the top-left to the trail fully off the bottom-right, linear, looping: a 6s pass
while a panel is open, 4s at 95% while thinking. It melts in and out over the glass
fade and away when the panel closes (`ChatPanelView.meltStreak`). The box, the strip
and the task HUD have it (`ChatPanelView`'s `TimeAnimator` hands the glass a phase and
a strength each frame: floats and uniforms, nothing allocated); pills do not. The disc
has it at 60% and 18dp wide at 80dp, scaled with its size, while the box is open or
Heylana is thinking. Each pane narrows it (`GlassSpec.streakFit`) so the leading streak
at its widest breath covers at most a third of the pane; the trail scales with it. Not
drawn below API 33, where there is no lens to bend it. `GlassStreakTest` holds it to
the motion renderer's formulas. Debug states: "purple streak".

**A beam laps the rim while Heylana is busy.** `ui/BorderBeam` ports border-beam's
geometry (MIT): `borderPathCoord` (arc length clockwise from the top centre, distance
inside the border, perimeter — a circle is a rounded rect whose corners meet), the
piecewise `stopsAlpha`, and the rotate family's `beamMaskStops` window (fully lit 52% to
80% of a lap, soft tails from 30% and to 95%), in AGSL as `BeamShader`. The lit window
carries the aurora tokens (#8F5BFF → #6B3BFF → #35E0E8 → #FF9A4D) with a gaussian glow
across the rim (6dp on panels, 4dp on the disc) that spills a little outside, one lap
every 1.6s. Listening, thinking and working light it on the box, the strip, the task
HUD and the disc; speaking lights it at 35% plus 65% of the playback level. While it is
lit the purple streak gives way; idle-open, no beam and the streak drifts. The overlay
hands the panel its state from the disc's look (`BuddyOverlayView.updateBeam`); the disc
works out its own. border-beam has no golden vectors (its ports were checked by pixel
harness), so `BorderBeamTest` is a phase test plus the spec's stops. Debug states:
"beam thinking", "beam speaking".

**Shapes merge like goo.** `overlay/GooeyLayer` follows liquid-gooey (MIT): the merging
shapes are drawn as plain grey blobs (`#8A8A8A` at 45%, so the goo reads over black and
white) into one view whose RenderEffect is a 6dp blur followed by an alpha colour matrix
with slope 18 and the library's intercept `0.5 − 18 × 5/12 = −7`, so touching shapes
bridge and merge. Only the silhouette is filtered: while a merge runs the pane's glass
(`GlassDrawable.hidden`) and the chips' glass step aside, and the words and the mark stay
crisp in their own views. Merges, all on the shared spring (`SPRING_STIFFNESS`,
`SPRING_DAMPING`): the box growing out of the disc as one blob and separating
(`gooeyGrowFrom`, on open), drawing back into it before the disc flies home
(`gooeyShrinkInto`), box to strip and strip to HUD (the body springs to the new height,
measured up front, while a droplet pinches back in), and the step chip and next/done
coming out of the HUD's bottom edge. API 31+; below it, the old scale and fade. Strip to
HUD in the real overlay also changes window, so only the in-pane part is gooey there.
`GooeySpecTest` holds the numbers to the library's. Debug states: "gooey open",
"gooey close", "strip to HUD".

**Every close starts the next open empty.** Whatever closed the box — an action, an
answer settling, cancel, the twenty-second timeout, a tap outside, back — the window
goes from compose or HUD to docked, and `PanelReset` resets the pane there: no status
text, no note, an empty question field, no confirm strip, the ask pill enabled, the box
shape, no beam, streak or goo (`panel: reset to empty compose`). One rule in one place,
because a reset per close path is how a stale "thinking…" survived. `PanelResetTest`.

**The edge snap and the flight home glide.** Both use `GLIDE_STIFFNESS` 150 and
`GLIDE_DAMPING` 0.85 instead of the open flight's 380 and 0.72, which keeps its feel.
The snap starts at the finger's speed (smoothed over the last moves, capped at
`GLIDE_MAX_START_DP_PER_S` 3000dp/s), so a fling carries into the glide rather than
stopping and restarting; the trace says `flight: spring stiffness=… damping=…
start_vx=… start_vy=…`. `GlideTest`; debug state "snap glide".

The purple band, the aurora under the disc face, the chromatic rim, the motion RGB
split and the purple bloom (design-2c) are still in the code behind
`GlassSpec.TINTED_EXTRAS`, off.

**Blur is asked for, never assumed.** `ui/GlassBlur` checks
`isCrossWindowBlurEnabled` at the moment a window is shown and sets
`FLAG_BLUR_BEHIND` only if the platform agrees. The answer picks the fill: thin
glass when the platform is blurring what is behind, a heavier fill when it is
not and the surface has to carry itself. Never hardcode one or the other.

## Where the keys are

**Not on the phone.** `worker/` is a Cloudflare Worker holding the Anthropic,
Gemini (and optionally Cartesia) and Deepgram keys, and it is the only thing that ever sees them. The app
knows one address — `heylana.proxyUrl` in `local.properties`, into `BuildConfig`
— and its own device id.

- `/chat` — the app sends `mode` (`quick` or `task`) and the worker picks the
  model. **The app must never name a model or hold a key to send with one.**
- `/tts` — the answer text comes back as raw 16-bit audio, streamed, so the
  phone can start playing before the sentence is finished.
- `/stt-token` — a Deepgram key that stops working after two minutes, so the
  phone can open the listening socket itself without holding the real one.

Every request carries `X-Heylana-Device`. On **Free** each device gets 150 questions,
150 spoken answers and 300 pairs of ears a day; on **Pro and Judge** questions and spoken
answers are uncapped apart from an abuse ceiling of 2000 a day each
(`PAID_DAILY_CEILING`, `dailyCapFor`; the plan is read from the account only for those two
routes). Over a cap the worker answers `429 daily_cap` and the app says so in plain words.
That is budget protection, not a product tier.

**The voice is Gemini TTS.** `/tts` speaks through `VOICE_PROVIDER`: `"gemini"` (the
default, also when unset) or `"cartesia"`, kept as an option with its old voice ids. Gemini
is `gemini-3.1-flash-tts-preview` (override with the `GEMINI_TTS_MODEL` var) on Google's
Interactions API with `stream: true` and the `GEMINI_API_KEY` secret. `worker/src/voice.ts`
turns its Server-Sent Events into raw PCM as they land: only `step.delta` events whose
delta is `type: "audio"` are decoded from base64 and passed on, in order (never the
closing `interaction.completed`, which repeats the audio; a WAV header is stripped if one
comes). It is 24 kHz mono 16-bit, the same `audio/L16` and `x-sample-rate: 24000` the phone
already plays, and the phone's `PcmFrames` keeps samples whole across odd-sized deltas.
The text goes plain, with no style prompt that could be read out. The picker's two slots
are Gemini prebuilt voices: `skylar` is **Sulafat** (Google: "Warm", female) and `archie`
is **Achird** ("Friendly", male — no male voice is described as warm). Each call logs
`provider`, `voice` and `model`, and `tts_end` logs events and bytes; never the text.
Google's 429 comes back as `429 quota`.

## Plans, wallets and payment

**Plans key on the wallet.** Connecting a wallet (Seed Vault over Mobile Wallet
Adapter) signs a plain sign-in message the worker made — never a transaction —
and the worker hands back a 30-day session. Every request then carries
`Authorization: Bearer <session>` and talks count against that wallet. With no
wallet the phone's device id is the account: Free, and no welcome bonus.

- **Free**: 30 talks a calendar month (UTC), 3 skills, plus 20 welcome talks
  granted once, the first time a wallet connects.
- **Pro**: unlimited talks, 10 skills, 30 days per payment, stacking.
- **Judge**: Pro until `JUDGE_UNTIL`, by code.

A talk is a `/chat` that succeeded upstream; a refused or failed one is not
counted. Over the limit the worker answers `429 talks_cap` and the buddy says
"That was your last free talk this month. Go Pro in Settings for unlimited."
The skill cap is only stored and shown for now.

**Payment is verified by the worker, never trusted from the phone.** The worker
quotes an exact amount in base units (USDC at face value, SKR through Jupiter's
price, rounded up) with a fresh random reference. The phone builds a
`transferChecked` to the treasury's associated token account (creating it if it
is missing, the payer covering the fee) with the reference as an extra read-only
account, and Seed Vault signs and sends it. The worker then reads that signature
from the chain (jsonParsed) and checks mint, destination owner, amount, sender
and reference before extending Pro. A reference pays once; a signature pays for
one reference. A payment that has not confirmed within 60s is remembered on the
phone and claimed the next time Settings opens.

**The worker names the cluster; the app follows.** `CLUSTER` in `wrangler.toml` is
`"mainnet-beta"`, or `"devnet"` to test with play money (with a devnet `RPC_URL`
and devnet `USDC_MINT` to match). `/me` returns it, and the app hands that same
cluster to Mobile Wallet Adapter's authorize and to the `/pay/blockhash` request,
which refuses `409 wrong_cluster` if the two disagree. There is no SKR on devnet:
the worker refuses the quote with `not_on_devnet` and the Go Pro sheet greys SKR
out with "SKR is not on devnet." The stub's `--devnet` flag does the same.

**The treasury cannot buy Pro from itself.** `/pay/confirm` refuses a transfer
whose sender is `TREASURY_ADDRESS` with `402 self_payment`: it moves nothing.

**Identity.** `GET`/`PUT /profile` (session required) keeps `{name, call_me}` in KV
against the wallet. `name` would be the wallet's Seeker ID (.skr), but Solana
Mobile documents no public reverse-lookup API — .skr names are AllDomains records
read on mainnet — so a first sign-in starts it empty, and nothing outside the
worker is asked. After connecting, Settings shows "What should I call you?",
prefilled with `call_me`, else `name`, else nothing. The chosen name is cleaned
to one plain line of at most 40 characters on both sides, because it goes into
what the model is told, and it is never logged. The phone keeps a copy in
EncryptedSharedPreferences so the buddy never has to ask the worker for it.

**The name is said once.** `brain/Greeting` belongs to one run of the buddy. Only
the first question after Start buddy carries "The user's name is X. Start this
answer with their name."; once an answer lands, no later question carries the
name at all — the surest way the model will not use it again, and one line fewer
to pay for. A failed first question keeps the greeting for the next.

**The mark is served by the worker** until heylana.xyz exists. `GET
/heylana-mark.png` returns the 256px mark embedded in `worker/src/mark.ts`,
answered before any device check or cap, because Seed Vault fetches it with no
headers. The Mobile Wallet Adapter identity is the built-in worker address with
icon path `heylana-mark.png` (relative, no leading slash, as the spec asks).

**The worker vouches for the app.** Seed Vault fetches `GET
/.well-known/assetlinks.json` from the identity address (the worker) to check the
app is really Heylana. It is answered before any device check with a Digital Asset
Links statement for `xyz.heylana.app` and the certificate fingerprints in the
`ASSETLINKS_SHA256` var (comma-separated; debug first, release added later).

**The RPC must be on CLUSTER's network.** A devnet `CLUSTER` with a mainnet
`RPC_URL` produces mainnet blockhashes that Seed Vault refuses ("Network
mismatch"). `worker/src/cluster.ts` reads the RPC's genesis hash once per address,
and `/pay/blockhash` and `/send/prepare` refuse with `503 rpc_wrong_cluster` and a
plain sentence while they disagree. The phone's send path (`wallet/SendFlow`)
carries the quote's cluster to both the blockhash request and Seed Vault, and logs
`cluster=` at each step.

## The Solana brain

**Heylana prepares; the user signs. Always.** Nothing in the app or the worker signs
or sends a transaction. A send ends in Seed Vault, where only the user can approve it.

**Tools run in the worker, never on the phone.** `/chat` with `tools: true` runs
Anthropic tool use in `worker/src/brain.ts`: at most 4 lookups and 12 seconds per
question, then one more round with tools switched off so the model answers with what
it has. Usage is summed across rounds and logged with `model`, `rounds` and
`tool_calls`. The tools (`worker/src/tools.ts`) are get_balances, get_price,
explain_address, recent_activity, resolve_name and prepare_send; their results
never carry the RPC address, and wallets are shortened to their ends.

**Solana knowledge costs nothing when it is not needed.** `SolanaCore` (about 350
tokens plus its rules) and the tool definitions go only when the app in front is a
known Solana app, the question has Solana words, an address or a .skr/.sol name, or
it routed as a send or explain question. Otherwise the request is byte-for-byte what
it was: `brain: … solana-core not loaded tools=not sent`.

**Money questions go to the task model.** Signing, wallet and swap screens, and send
or "what am I signing" questions, route to `task`. "What does this button do" is
deliberately not an explain question.

**Explain before you sign.** On Seed Vault's screen, a wallet screen that says
approve/confirm/sign/review/slide next to an amount or an address that is not the
user's own, or for "what am I signing" anywhere, `SigningScan` pulls the full
addresses, the shortened ones (7c2y…SxSv — how the Wallet and Seed Vault print
them) and the amounts off the screen text. The worker matches each shortened
address before the model is called (`worker/src/shortaddr.ts`): against the
treasury, the user's wallet and its token accounts, the addresses they typed since
the buddy started (kept in memory on the phone), and only then the counterparties
of their last 20 transactions, cached ten minutes. Exactly one match gives a name
("your Heylana treasury"); anything else is "cannot be verified from here", and the
model says: "The screen shows 0.05 USDC to 7c2y…SxSv. I can't verify a shortened
address from here; check it matches who you meant." Amounts are always read off the
screen; it never says "safe", never greets by name, and only counts are logged.

**Sending is deterministic.** A question routed as a send goes with `intent: "send"`,
and the worker makes one call offering only `propose_send`, with the model forced to
use it: it writes down to, amount (null for "everything") and token, and no prose
ever comes back. The app writes the confirmation itself — "Send 0.05 USDC to
7c2y…SxSv. Confirm?" — and says a plain line if no action came back. The raw action
(recipient's first four characters, amount, token) and `SendGuard`'s verdict are
logged at info. `SendGuard` drops the action unless the recipient and the amount are
in the user's own words (an address exactly, a name case-free with "dot" allowed;
"everything" becomes "all"). `/send/prepare` resolves and checks it and keeps it 15
minutes; over a quarter of the balance is refused until the user says "yes send it
all" or the amount again (that second turn skips the model). The strip holds until
confirm or cancel. Confirm opens `SendActivity` — no `noHistory`, or it would die
when Seed Vault opens — which builds the transfer, has Seed Vault sign and send it,
and polls `/send/confirm` until the worker sees it land. Send logs carry amounts and
at most four characters of any address.

**A send's result is read from the wallet's error code, and checked on chain.**
Seed Vault's JSON-RPC codes decide first (declined or not signed is cancelled; not
submitted is unsure; an unsupported network says so), and every result is logged
raw: exception types, code, signature count, message with addresses shortened.
When the wallet ends unsure without a signature, `/send/confirm` without one looks
among the sender's latest 10 transactions, newer than the prepare, for one that
does exactly what was prepared and is not already counted for another send. Found
is "Sent" with its short signature; not found is "check your wallet before trying
again", never "try again".

**Seed Vault gets three minutes, and a timeout is not a no.** The wallet adapter's
client timeout is 180 seconds (the library default of 90 dropped a slow approval's
signed result). A timeout anywhere in the error chain is unsure, not a failure:
a send is then looked for on chain, and a Pro payment by its reference.

**Checking the chain uses growing waits.** `wallet/Backoff` gives 2s, 3s, 5s, 8s,
13s, 21s and whatever is left of the minute. Every look is logged as
`send: check #n after=…ms signature=given|none result=…` (or `pay: check #n`). Only
"does not match" or "not yours" ends a look early. On the worker, a given
signature is checked with `getSignatureStatuses` before the transaction is read;
with none, the sender's wallet and its token account for that mint are scanned
since the send was prepared. A Pro payment with no signature is found by its
reference address, the Solana Pay way.

**The box opens one way outside a task.** Whether a tap opens it or Heylana opens
it to say something (a notice, the send strip, a spoken answer's words), the disc
flies to the top and the box is full width beneath it, from either dock side;
only a tap brings up the keyboard. The small box beside the disc is for a task
alone. Settings → Debug states has "box from left dock" and "box from right dock".

**Only a plain question greets.** The greeting is decided after routing, and a
send, a signing explanation, or anything on a wallet or swap screen never carries it.

**Answers have a word cap, enforced once.** `brain/AnswerLength`: a signing
explanation is two sentences under 40 words (the prompt asks for it); everything else
keeps 1 to 3 short sentences with 60 words as the app's line. A `say` over its cap is
sent back once with `shorten: true` and the shorter wording is used if it really is
shorter; `answer: over cap words=… cap=… now=…` is logged. The worker writes that
whole request itself (`worker/src/shorten.ts`: its own system prompt, the quick model,
150 max tokens, one text of at most 1,200 characters) and does not count it as a talk,
so it cannot be used as a free question. The own-key path sends the same prompt; a
test keeps the two copies identical. Task steps are capped at 60 too; a send's empty
`say` is never shortened.

**A sign explanation is kept small.** It goes with the Solana rules but not the send
rules, offers only `explain_address` (`tool_names`), and offers no tools at all when
the screen has only shortened addresses, which the worker checks before the single
round. `explain_address` returns only kind, label, well_known, transactions,
first_seen and age_days (plus owner and mint for a token account). A test holds a
typical Seed Vault request with its skill under 2,600 tokens by the ÷4 estimate; the
operator checks the real `input_tokens` stays under 3,000.

**Addresses are never shown or spoken whole.** `AddressText.shorten` turns any base58
run of 32 to 44 characters into first four…last four, leaving .skr and .sol names
whole. Every model answer passes through it when it is parsed, and every line
Heylana says itself; the worker's tool results were already shortened. The reply
strip shows up to six lines and scrolls beyond that.

**Lookups are timed.** Each tool call's name and milliseconds, and their total, ride
on the response's usage (`tool_ms`, `tools`) into the phone's `usage:` line, and on
the worker's log line with `signing_ms` for the address check.

**Names.** .skr names are AllDomains records on mainnet, resolved without an SDK in
`worker/src/names.ts` (one account read), using `MAINNET_RPC_URL` when the worker is
on devnet. Program-derived addresses need an ed25519 on-curve check, in
`worker/src/pda.ts`, tested against the token-account vectors the app's payment test
checked independently. .sol names go to `sdk-proxy-v2.sns.id`, which may report .sol
as unsupported; Heylana says so rather than guessing.

**Version pins.** Mobile Wallet Adapter clientlib-ktx is 2.1.1 and sol4k 0.7.0:
the newer releases are built with Kotlin 2.4 and this project's compiler cannot
read them. The two MWA artifacts share a namespace, which AGP 9 refuses unless
`android.uniquePackageNames=false` in `gradle.properties`.

The one exception is the hidden "use my own key" setting: questions only, on a
key the user typed in themselves. The voice and the ears keep using the proxy,
because those keys are not the user's to hold.

`scripts/stub-proxy.py` answers all three routes locally with fixed replies, so
the whole app can be exercised without spending anything. It also fakes the
wallet, `/me`, `/judge` (code `stub-judge`) and `/pay/*` routes — without checking
any signature or reading any chain — and `--talks-cap` makes `/chat` answer
`429 talks_cap`; `--skr-name <name>` prefills the profile as a .skr lookup would; `--send-reply` makes `/chat` propose sending 0.05 USDC to a fixed treasury address and answers `/send/*`, so the strip can be checked without a model — never tap confirm against it. Never approve a Seed Vault payment against the stub: the wallet
would send a real transaction to the stub's made-up treasury. Debug builds carry a
network config that lets them reach it on loopback and nothing else.

**Both ears listen, every time.** At the long press the phone's own recogniser
starts, and so does Deepgram — whose key and socket were already being fetched
from the first touch of the disc, and whose microphone buffers until the socket
is up. On this Seeker the two share the microphone (`deepgram: microphone open
silenced=false`). Once the user lets go, `voice/EarsRace` decides: Deepgram's
words win if they arrive within 1.5 seconds of the release; otherwise the phone's
are used as soon as Deepgram is known to have nothing, or when that window closes;
neither is nothing heard; nothing waits past 4 seconds. A Deepgram that cannot
work is simply out of the race — never a reason to hear nothing. The trace says
`ears=deepgram|android won reason=…`.

**No phone voice: a voice that cannot speak stays silent.** If `/tts` is refused (the
day's cap, the provider's quota), fails, returns no audio, or no audio arrives within
`VoiceFailure.FIRST_AUDIO_MS` (6 seconds, also the stall limit mid-stream), `HeylanaVoice`
logs `voice_failed reason=429 daily_cap|quota|timeout|error` and hands the words back:
the answer is shown as text in the strip (in a task, in the HUD) and stays up long enough
to read (350ms a word, 4 to 12 seconds) before settling. Nothing else reads it out. The
phone's own text-to-speech, the "Phone voice" choice and the "Force phone voice" debug
switch are gone. The ears race is unchanged.

**What goes where, in the words the app uses.** Heylana reads the screen only
when you ask, and watches for your tap only while it is pointing at something.
Your voice goes to Deepgram to be transcribed while you hold the buddy. The
spoken answer text goes to Google (Gemini) to become speech. Conversation mode, when
enabled, uses Gemini Live's free tier; Google may use that audio to improve its models.
The screen never goes to either. **Keep that copy and the code saying the same thing** — the keyterms sent
to Deepgram are the fixed word list only, and if screen labels are ever added to
them the sentence has to change with them.

## Quick actions

**The phone's own apps act; Heylana still taps nothing.** Asked to set an alarm or a
timer, open an app or a web page, get directions or call someone, the model adds
`action: {type: "intent", intent: alarm|timer|open_app|open_url|navigate|dial, …}`.
`QuickGuard` checks every argument against the user's own words, as `SendGuard` does
for a send: the hour and minutes as said (digits, "7:30", "0730", "seven thirty",
"half", "quarter", "noon"; "7 in the morning" refuses 19), the timer's length as said
("5 minutes", "half an hour", "an hour and a half"), every word of an app name, place
or contact, the digits of a number, and the host of a web page. An alarm label that
was not said is dropped; anything else not said is refused with one plain line.

**The intents.** Alarm: `ACTION_SET_ALARM` with hour, minutes and the label, SKIP_UI
false so the Clock comes to the front. Timer: `ACTION_SET_TIMER` with the length.
open_app: the launcher intent of the best `AppMatcher` match among apps with a
launcher icon (exact name 100, a name holding every word said 80, the reverse 60, a
near spelling up to 50; a tie at the top asks "I found X and Y. Say which one.").
open_url: `ACTION_VIEW`, https only. navigate: `ACTION_VIEW` on `geo:0,0?q=`. dial:
`ACTION_DIAL` only — `ACTION_CALL` is never built, so no call is ever placed — with the
number, or an empty dialer and "Search for Mum there" for a name (Heylana has no
contacts permission). Heylana then says one short line: "Alarm set for 7 AM
tomorrow.", "Opening Wallet.", "Opening the dialer." (never the digits).

**The catalogue.** Beyond those six (`QuickIntents.effect` builds each, as plain data
tested per action in `QuickCatalogueTest`):
- `youtube_search(query)`: `ACTION_VIEW` on `youtube.com/results?search_query=…` with
  YouTube's package set, so YouTube opens on the results; the same page in the browser if
  YouTube is missing. `ACTION_SEARCH` brought YouTube to the front having searched for
  nothing, which is what it was doing before.
- `spotify_play(query)`: `MEDIA_PLAY_FROM_SEARCH` with `query` and focus
  `vnd.android.cursor.item/*`, set to `com.spotify.music`; missing, "Spotify isn't
  installed on this phone." **It does not start playing.** Tried on the Seeker:
  play-from-search with the any-media and artist focuses, `spotify:search:<query>`, and
  that URI with `:play` — each lands on the search results, none plays (Spotify's own
  community reports the same, and an account has to be signed in at all). So the line is
  "Opened Spotify for X. Tap play." rather than a claim it is playing.
- `media_control(play|pause|next|previous)`: `AudioManager.dispatchMediaKeyEvent`, down
  and up, to whatever media session is playing. Pause with nothing playing says "Nothing is
  playing to control."
- `message(number|name, text)`: `ACTION_SENDTO` `smsto:<digits>` with `sms_body` — the
  compose screen, filled in; Heylana never sends. With a name, Messages opens its contact
  picker. The line reads neither the number nor the words. A reply that puts the recipient
  in `to` instead of `number`/`name` is read either way; the words are matched as words or
  as one run (punctuation the user did not say cannot refuse it); and where nothing takes
  `smsto:`, `sms:` and then a plain `ACTION_SEND` share follow. A refusal says which part
  failed and by how many characters, never the words themselves.
- `reminder(text, hour, minutes)`: saved into the calendar itself
  (`CalendarContract.Events` on the primary writable calendar, plus a ten-minute alert),
  so nothing is left to do: "Reminder saved for 6 PM today." The first one asks for
  WRITE_CALENDAR through `CalendarPermissionActivity` — the invisible one-shot prompt the
  microphone uses — and, that time or if it is refused, falls back to `ACTION_INSERT`,
  the calendar's own new-event screen with title, begin and a 30-minute end. Unlike an alarm, a bare
  hour is the next time that clock reading comes round ("call mum at 6" at noon is 6 PM);
  "tomorrow at 6" is the first 6 from 7 AM on (6 PM). A said half of the day stands.
- `flashlight(on|off)`: `CameraManager.setTorchMode` on the first camera with a flash. "Turn
  it off/on" within five minutes of switching the flashlight is done in the app with no
  model call (`action: follow-up intent=flashlight … model=not_asked`).
- `camera` / `selfie`: `STILL_IMAGE_CAMERA`; a selfie adds the three front-camera extras
  camera apps read (whether one honours them is up to the app).
- `web_search(query)`: the Google search page with `ACTION_VIEW`, in the default browser.
  `ACTION_WEB_SEARCH` put up a chooser on the Seeker (Chrome and the Google app both take it).
- `settings(wifi|bluetooth|display|sound|battery|accessibility)`: that page's Settings action.

Every argument is checked as before: queries, message words, reminder text and contact
names word by word (contractions spelled out on both sides, since the model writes "I am"
for "I'm"), numbers by their digits, the reminder time like an alarm's, and the command,
state, camera or page by a word the user said ("skip", "off", "selfie", "wi-fi"). A missing
app is named in one line (`QuickText.missingApp`). "Send a text to Ada" is a message, never
a Solana send: `QuickActions.isMessage` is checked before the send route. The worker's
`propose_action` offers all sixteen intents with `text`, `command`, `state` and `page`
fields, and logs only intent, times, command, state and page. **The worker has to be
redeployed** for the model to be offered the new intents.

**Nothing is left over the app it opened.** Once an action fires, the box melts
away at once (`afterQuickAction`), the line is spoken, and the disc settles to idle
a second after the speech ends, the same beat as a spoken answer. With no voice the
line shows for that second and then the box goes. A refused action still says why
in the box.

**The line outlives the close.** Closing the box stops speech, and the close only
reports back after the box has drawn into the disc and the disc has flown home — by
then the line had started, so it was cut off and the disc went idle in silence.
`afterQuickAction` sets `keepSpeechThroughClose` before closing; that one close lets
the voice play on (`speak: kept playing through the close`). And a line about to start
used to clear down the voice first, which reported "finished speaking" with nothing
playing and booked the settle that closed the box mid-flight: `stop(beforeSpeaking =
true)` reports nothing unless something was really under way. The trace for a spoken
action: `speak: line chars=N`, `voice=…`, `panel: closed`, `speak: kept playing…`,
`speak: speaking=true`, `speak: speaking=false`, `settle: scheduled`, `settle: run`
a second later. The debug QUICK_ACTION broadcast never speaks: with no phone voice, every
spoken line is a /tts call.

**Quick actions are deterministic, like sends.** Left to prose, the model said
"setting an alarm for 7pm" and wrote no action, and "open the wallet" pointed at the
icon. Now `QuickActions.isQuickAction` classifies the question first (set/make an
alarm, wake me, a timer, open/launch X, call/dial X, directions to X — polite forms
too, but not "how do I open…" or "what does the call button do"). That routes as
`quick_action`: quick model, no Solana block, no tools, no skill, no greeting, and
`intent: "quick_action"`, which makes the worker offer only `propose_action` with the
model forced to use it (`worker/src/brain.ts`). No prose comes back; the app writes
every word ("Timer set for 5 minutes."), and with no action says "I didn't catch what
to do." The own-key path, with no worker to force a tool, still sends
`QuickActions.RULES`. The brain line says `quick-action=no|forced|rules`.

**A bare hour is the morning.** `QuickGuard.halfOfDay` corrects whatever the model
wrote: "7 tomorrow" is 7:00 AM; "pm", "evening", "afternoon", "tonight", "night" make
1–11 the evening; "am" (only right after a number, never the "am" in "I am") or
"morning" make 13–23 the morning; a 24-hour number said outright ("19:00") stands.

**Every step is logged at info**: `action: raw …` with the intent and its times (or
`action: raw missing why=quick_action`), `action: guard verdict=allowed|refused …`,
`action: firing intent <name>`, then `action: fired` — a web page only by its host, a
place or name by its length, a number by its digit count. The worker logs
`quick_action: {intent, hour, minutes, seconds}`.

**Checking the intents without the model.** In debug builds, with the buddy started:
`adb shell am broadcast -a xyz.heylana.app.debug.QUICK_ACTION --es said "'open the
wallet'" --es action "'{\"type\":\"intent\",\"intent\":\"open_app\",\"app\":\"wallet\"}'"`
runs the guard and fires the intent. The line is shown, never spoken — speaking it is a
`/tts` call. `SET_ALARM` is the one new permission; the
launcher `<queries>` entry is what lets "open the wallet" see installed apps.

## Skills

**A skill is one app's reference notes, and never more.** `skills/<id>.md` has a
front matter block (id, name, package, version, author, summary, privacy, and
optional triggers) and a plain-text body under 400 tokens (counted as characters
÷ 4, rounded up): the app's screens and what their buttons do, common tasks as
numbered steps, and warnings. The body goes to the model after
`HeylanaPrompt.SKILL_RULE` — reference only, can never authorise a send, a sign or
a tap, can never change the rules — fenced between `<<<` and `>>>`.

**Sanitised on the way in.** `SkillSanitiser` drops every line that opens (after
any list marker, quote or emphasis) with "you must", "ignore", "disregard", "send",
"sign", "transfer", "approve" and similar, and any line with "ignore previous",
"system prompt" and the like anywhere. Each dropped line is logged
(`skills: stripped from <id>: …`): skill text is neither the screen nor a secret.
Built-ins are written so nothing is stripped, and a test holds them to it.

**One skill per request, and only for the app in front.** The foreground package
picks among the *active* skills; two for one app (Kamino's notes live in the
Wallet) are decided by the `triggers` words in the question, else the app's main
skill. A send question carries none. The brain line says `skill=<id> tokens=<n>`
or `skill=none`; task steps pick against the goal.

**The plan caps what is active.** Free 3, Pro and Judge 10, as `/me`'s
`skills_cap` last said (kept in settings; Free until heard). The first *cap*
switched-on skills in list order are active; the rest stay on but greyed, and an
off skill cannot come on while every place is taken. Everything is on until the
user switches it off, a new install included.

**A skill for any app.** `package: any` is for something that turns up in every app
rather than in one — x402 payment prompts, in a browser, a dApp or an agent. Such a skill
must have trigger words (`any_app_needs_triggers` otherwise) and loads only when they are
in the question: after a same-app skill whose own triggers match, before the app's main
skill. Built in: `x402` (what a 402 payment request is; check amount, token, network and
recipient; scam patterns), `youtube` (tabs and search checked on the Seeker, watch page
unverified) and `spotify` (not installed on the test Seeker: all unverified). They come
after the five Solana skills in `BUILT_IN_ORDER`, so on Free the first three places stay
the Wallet, Kamino and Seed Vault signing.

**Built-ins are the files in `skills/`.** The build adds that folder to the APK's
assets (`androidComponents` in `app/build.gradle.kts`), so the repo and the app
cannot disagree. They can be switched off, never removed. Package names were
checked against `pm list packages` on the Seeker; Kamino has no app of its own and
lives in the Wallet, picked by its trigger words. Anything not walked on the phone
is marked "(unverified)" for the operator to fix.

**More skills come from a public index.** `skills-index/index.json` lists id,
name, summary, version and url; a url may be relative to the index, so the folder
can be copied to a public repo as it is. The address is
`BuildConfig.SKILLS_INDEX_URL` (a `-Pheylana.skillsIndexUrl=` Gradle property, else
`heylana.skillsIndexUrl` in local.properties, else the public repo). "Get more" is
a plain GET with no Heylana headers, https only (loopback http in debug builds,
for a local server over `adb reverse`), no redirects, 64 KB for the index and 16 KB
a skill. Every index entry is listed: one already here (built in or installed)
says "Installed" instead of Install, and when all of them are, the line "Everything
in the index is installed" sits above the list. Install parses, sanitises, refuses a built-in's id or a body over 400
tokens, and stores the file; Remove deletes it. `skills-index/skills/chrome.md` is
the test entry.

**"Simulate Free plan"** (Settings → debug) counts skills against Free's 3 on any
plan, so the greyed rows can be checked on a Judge account.

## How the pieces talk to each other

**Response contract.** The model must reply with exactly
`{"say": "...", "point_at": <element id or null>, "task": {"goal": "...", "done":
true|false} or null}` and nothing else. `say` is 1 to 3 short sentences written to
be read aloud — no markdown, no symbols. `point_at` is the numeric id of the
single element from the screen listing that the answer is about, or null. Anything
else — a missing field, a null, a non-number, or an id that is not in the snapshot
that was just taken — means "do not point at anything". The snapshot is kept alive
until the reply comes back so the id can be turned into real screen bounds.

**`say` is one line, or up to four pieces that walk the screen.** The contract's `say` is
either a string, as it always was, or `[{"text": "one sentence", "point_at": <id or null>}]`
— at most four (`SaySegment`, `brain/SaySegment.kt`). The prompt asks for pieces when the
answer shows how something works, with `point_at` on each sentence that names a button, and
`HeylanaPrompt.TEACH_LINE` and `SEGMENTS_LINE` ask for them on a teaching task and an
explanation. The worker checks them before they reach the phone (`worker/src/say.ts`,
`checkedBody` on every reply that is not a forced tool call): a piece with no text is
dropped, a `point_at` that is not a whole number at least zero becomes null, more than four
are cut, one piece pointing at nothing collapses back to a plain string, and nothing usable
leaves an empty `say` — which the phone treats as unreadable and asks for once more. The
chat log carries `say_segments` when there is more than one. On the phone the pieces keep
the same words as `text`, so the word cap, the strip, the memory and the shorten retry are
unchanged; a shortened answer is one piece again. `BrainReply.Say.teaches` is true when an
answer walks the screen.

**Only `say` is ever spoken or shown, through one parser.** Every reply — chat, plain,
a quick action, a send, a task step, a why, a recap — goes through `brain/ReplyParser`:
all the text blocks are joined, the first JSON object with a `say` (or an `action`) is
found (braces inside strings minded, fences and prose around it ignored, its own small
JSON reader so it is tested on the JVM as it runs), and only its `say` is used, with a
sentence said twice kept once. No object, no `say`, an empty `say` where words were due
(a send or quick action may be empty), or a `say` that echoes the contract or the prompt
("point_at", "Reply with ONLY", "User asks:"…) is unreadable: the same request goes once
more with `ReplyParser.JSON_ONLY` added, and if that is unreadable too Heylana says "I
didn't catch that, say it again." Before this, a reply that did not parse was spoken
whole — prose, JSON and all. The trace says `reply: unreadable reason=… retry=once`,
`reply: text outside the json chars=N dropped`, `reply: repeated sentence dropped`. The
one exception to "no answer in the log": in debug builds only, an unreadable reply's raw
text (addresses shortened, 600 characters at most) is logged as `reply: raw`, so the
operator can see what the model did; `HeylanaLog` is compiled out of release builds. A
retry is a second /chat and counts as a talk. The shorter wording from a shorten call is
de-duplicated too, and dropped if it echoes the prompt. Every route has the same caps
(`AnswerLength.capFor`): 60 words, 40 for a signing explanation, 25 for a reply that
starts a task — chat included.

`task` is null for an ordinary one-shot question. It is an object when the request
is something to *do*: `goal` restates it in one line and stays word-for-word
identical across the whole task, `say` describes only the current step, and
`point_at` is that step's element. `done` flips to true when the screen shows the
goal is met. A malformed or goal-less task object degrades to an ordinary answer. With Solana
loaded the reply may also carry `action` (a send); `SendGuard` decides whether
anything happens.

**Guidance sessions.** A task is stateful. `GuidanceSession` holds the goal, every
step already given (its spoken text and the label of what it pointed at), and when
it started. Each advance sends the model the goal, the steps so far, and a **fresh**
snapshot, and asks for the next single step. Ordinary questions instead carry Heylana's
short-term memory, so "and then?" has something to refer back to.

A session advances two ways: the user taps **Next**, or it advances itself when the
element it pointed at is gone from a fresh snapshot, or the foreground app changed
— debounced by 900ms so a screen has time to settle. It ends on `done`, on **Done**,
when the panel closes, when the buddy stops, on any API error, or at 8 steps. If two
steps in a row point at the same element the session is treated as stuck: it says so
and stops advancing itself until the user acts.

**Teaching mode.** "Teach me…", "show me how…" or "explain each step" (`brain/Teaching`)
starts a task that teaches: `HeylanaPrompt.TEACH_LINE` goes with the first question and
every step (`GuidanceSession.teaching`), so each step's say opens with one short reason,
then the instruction. "Why?" (a short why, ten words at most) asked while a task runs is
about the step in hand, not a new question: the task, its box and its pointer stay, the
model gets the goal and the steps with no screen (`brain: mode=quick why=teach_why`), the
reason and the step are shown and spoken, and the rest of the task teaches too. Spoken
steps are held under 25 words (`AnswerLength.STEP_WORDS`: a reply that starts a task,
every next step and a why); a finished task's confirmation keeps 60. The system prompt
carries the same rule in one line.

**The recap.** When a task ends (done, Done, the panel closing, an error, the cap, or a new
question), its goal and one-line steps are kept in memory as a `FinishedTask` for ten
minutes. "What did I just do" in that window is answered from it with no screen read
(`why=recap`). A task that used a Solana app or moved money (swap, send, stake…) is
`on_chain`: that recap goes on the task model with the Solana rules and `tool_names`
`recent_activity` only, so it says what actually landed and never invents amounts; any
other recap is the quick model with the steps alone.

**Short-term memory.** `Conversation` keeps the last three ordinary exchanges —
question, answer and the app they happened in — in memory and nowhere else: never
a file, never a log, never a preference. Each is forgotten ten minutes after it
happened, and the whole lot goes the moment the user asks from a different app or
the buddy stops. What is actually sent is capped at 600 characters, oldest
dropped first, so remembering cannot quietly grow the cost of a request.

**Events only while something is waiting for them.** This is a privacy rule, not
an optimisation. The accessibility service subscribes to *nothing* by default —
its `eventTypes` is set to zero, so the system delivers no events at all — and
its handler additionally returns immediately when no watcher is registered.
There are exactly two things that may switch events on, and both switch them
straight back off:

- a **guidance session**, so it can notice a step has been completed;
- a **tap watch**, for the fifteen seconds after Heylana points at something, so
  it can notice the user acting on it and take the box away.

Events from Heylana's own package are dropped before anything else looks at them.
Do not widen this to "always listening" for convenience, and keep the promise in
the notification and the onboarding screen matching the code: *reads the screen
only when you ask, and watches for your tap only while it is pointing at
something*.

**The panel gets out of the way.** During a task the panel is a heads-up display:
the window is deliberately *not* focusable, so the app underneath keeps the
keyboard and its own dialogs, and a tap outside the card is the user doing the step
rather than a request to dismiss it. Before each step is drawn, the buddy and its
card are moved clear of whatever is about to be boxed — the other side of the
screen first, then above or below it.

**The teaching flight.** An answer with segments is played one at a time
(`BuddyOverlayService.TeachingRun`): the disc flies to each segment's element along a
quadratic bezier arc, swelling at the apex and easing in and out, stands beside it, a ring
breathes around the element, the strip travels beside the disc with that sentence, and the
tap watch runs on it; when the sentence has been spoken the next one starts, and after the
last the disc flies home and the strip melts. A segment with no element is spoken from
where the disc stands. The shape of the motion is a port of Clicky's (MIT, `design/refs/
clicky`, `OverlayWindow.swift`'s `animateBezierFlightArc`): arc height a fifth of the
distance up to 80dp, smoothstep easing, a swell of 0.22 at the apex. The pace is Heylana's:
`TeachingFlight.PACE_MS` 600ms for a 420dp hop, never under 380ms or over 900ms. The window
itself moves frame by frame, as a drag does. Where the disc stands is `standBeside`: beside
the element on the side with more room, else under it, else over it, always on screen, with
the strip on the far side from the element. With no voice, each sentence is left up long
enough to read and then the flight moves on. A task step that only explains uses segments
and shows no Next or Done — the user is not being asked to change anything yet — while a
step that needs the screen to change keeps them. `TeachingFlightTest`; debug state
"teaching flight" plays three sentences across three stand-in elements.

**The highlight window.** The pointer is drawn in its own full-screen window that
is not touchable and not focusable, so every touch falls straight through to the
app underneath. It converts accessibility bounds (which are display coordinates)
through its own `getLocationOnScreen`, so any status bar or cutout offset corrects
itself rather than being assumed away. For a one-shot answer the box clears after 8
seconds; during a task it stays up until the step changes or the task ends,
because the user needs it while they hunt for the thing. Either way it clears at once when the next
question is sent or the panel closes.

During a teaching flight the pointer is a **ring** instead: 3dp of accent around the
element, breathing 2dp in and out with the same pulse, and no arrow — the disc is standing
right next to it (`HighlightOverlayView.ring`).

**The box answers the tap it asked for.** While it is up, a tap on the element it
points at — or the screen moving — flashes it green for 300ms and clears it,
without a word being said. The rule itself lives in `screen/TapWatch`, away from
Android, so it is testable: a click only counts when it matches the element's
key, and screen movement only counts after a short grace period, because an app
is rarely still at the moment a box appears.

**Only an answer's box is on a clock.** Fifteen seconds with neither a tap nor a
change and a one-shot answer's box clears quietly, because nothing is waiting on
it. A step's box is not on that clock at all: it belongs to the step and stays
until the step changes or the task ends, exactly as it always has. The watch
behind it runs the whole time either way, so acting on a step's box is
acknowledged however long the user took to find it.

**Never detach the disc while it is being touched.** Removing a view that is
holding a gesture makes the framework cancel that gesture, and the row the disc
sits in used to be torn down and rebuilt on every layout change — including the
one that opens the microphone. The result was an `ACTION_CANCEL` three
milliseconds after the long press: no release ever came back, so a hold could
never finish. `layoutBeside` and `reorderBeside` now move the box and the capsule
around the disc and leave the disc itself alone.

**Nothing settles the buddy back to idle mid-exchange.** Settling closes the box,
and closing the box abandons the microphone, so a settle that lands at the wrong
moment throws away what the user is saying. They land easily: opening the
microphone stops the speaker, text-to-speech then reports "no longer speaking",
and that report is what books the settle a second later. `overlay/Exchange` is
the gate — it runs from the microphone opening (or a typed question being sent)
until the answer lands, the user gives up, or nothing was heard — and both the
booking and the running of a settle ask it first.

**The disc's look comes from the exchange, in one place.** Every change of
phase in `overlay/Exchange` is reported, and `DiscLook` turns it into the look:
listening, thinking while the words or the answer are on their way, idle once it
is over — pointing only if an answer has pointed at something. No path sets the
disc's look itself. Before this, each way an exchange could end had to remember to
put the disc back, and "nothing heard" forgot: the thinking ring turned for five
minutes.

**No exchange lives longer than twenty seconds.** The clock starts when the
microphone opens or a question is sent, and moving from listening to asking does
not reset it. When it runs out, both ears stop, any request is cancelled, the
capsule melts, a short "That took too long, try again." appears, and the disc
rests.

**"Heard nothing" waits for the release.** The recogniser gives up on its own
after a moment of quiet, which can easily happen before the user has started
speaking, with their finger still down. `voice/NothingHeardGate` holds that
result until they let go, which is the moment they are actually asking for an
answer.

**Re-parenting the buddy resets its animation.** Moving the disc between the
docked, compose and HUD layouts detaches the view, which cancels any running
cross-fade part way. The owner calls `refreshState()` after every re-parent
rather than trusting the attach callbacks to have fired in a useful order.

**A plain question is never refused for want of a screen.** If screen reading is
off, or the tree comes back empty (Chrome, and Heylana's own screens, often hand
over nothing), the question still goes with an empty listing. When the service is
genuinely off that listing says so, and the model answers from general knowledge
or tells the user to switch screen reading on if the question needed the screen.
Only a task step — which cannot work without a screen — refuses outright, and only
when the service really is not running.

**Chat needs no screen, so none is read.** Heylana is a buddy as well as a guide:
small talk, jokes, opinions, follow-ups and general knowledge are answered naturally in 1
to 3 short sentences, and the no-self-promotion rule still holds. `brain/ChatQuestions`
decides from the words alone, before anything is read, whether a question is chat
(greetings, how are you, a joke, what do you think, who are you, who won / what is the
capital / how far…). It is deliberately narrow: "this", "here", a button, an app, "how do
I", "why", a Solana word, a send, a signing question or a quick action all take the
ordinary path. A chat question skips the screen read entirely (`ask: screen not read
why=chat`), goes on the quick model with no Solana block, tools or skill (`brain:
mode=quick why=chat`), carries `HeylanaPrompt.NO_SCREEN` instead of a listing, may carry
the greeting, and is remembered without an app so it neither clears nor is cleared by the
screen conversation around it.

**Enabled is not running.** Android keeps a service's name in the accessibility
setting after the app crashes with that service bound, and simply stops binding
it. So "is Heylana in the list" is the wrong question: onboarding ticks the row
only when the name is listed, the master switch is on and the service is bound,
and `scripts/a11y.sh` empties the list before writing it back — writing the same
value changes nothing — then waits until the service is actually bound.

**Nothing that touches a view runs off the main thread.** The socket reads on
OkHttp's thread and the microphone on an IO thread. Anything they report is handed
back to the main thread before it reaches the overlay. Starting an animation from
the wrong thread does not glitch — Android kills the app, and because the
accessibility service lives in the same process, it takes screen reading down with
it until someone switches it off and on again.

**Every window is read, topmost first.** A wallet's send sheet, a dialog or a
prompt is its own window above the activity behind it, so reading one window
missed the one that mattered. The service reads every application window (the
keyboard is not one), drops Heylana's own, and `screen/WindowMerge` orders them by
layer, top first. The model still gets 120 elements at most; when there are more,
amounts, addresses short or full, .skr/.sol names and to/from/fee/send/approve
words are kept first. Each read logs the windows with package, layer and element
counts, and which window carried the signing words. While the full-screen box is
open, it lets touches through for the moment of the read, or Android would leave
the covered app out of the window list altogether.

**Checking a read without asking anything.** In debug builds,
`adb shell am broadcast -a xyz.heylana.app.debug.READ_SCREEN` reads the screen as a
question would and logs `debug-read:` with counts, whether it is a signing screen,
and how it would route. No request goes anywhere.

**Reading the screen.** The snapshot is taken after the keyboard is dismissed and
the app underneath has been given a moment to lay itself out again. With the
keyboard up, the bottom of the app is squeezed off screen — which is exactly where
the button the answer is about usually lives.

**The disc is 64dp docked and 80dp open.** It swells on the way to the top centre
and settles back on the way home, on the same spring as the flight: the flying
stand-in is always the 80dp-sized view and springs its `discDp`, offset by half the
size difference at take-off and landing. Bleed, bloom and dock inset are ratios of
the disc (`DISC_BLEED_RATIO`, `GLOW_BLUR_RATIO`, `DOCK_INSET_RATIO`, the old 20, 40
and 8dp around 88dp), so they scale with it. Resizing the docked disc only changes
its layout params, never detaches it. Debug states shows both sizes side by side.

**The mark dissolves into an orb.** Idle and pointing show the mark. Listening,
thinking, working and speaking dissolve it (300ms, `ORB_DISSOLVE_MS`) into a
thinking-orbs state: listening is `listening` (wave, glow tint) with the mic ring
breathing; thinking is `breathing` (the ring, coloured by the aurora stops turning
once every six seconds); working — a task's next step being worked out, or a send
from prepare to landed (`BuddyOverlayView.setWorking`, `DiscLook.WORKING`) — is
`working` (orbits, accent lifted toward white); speaking is `composing` (ribbon,
glow lifted toward white) and swells with the playback level, which
`HeylanaVoice` measures from the PCM (`PlaybackLevel`) and hands out as the speaker's
play head reaches it. Back to idle, the orb reassembles into the mark.
`orbs/OrbEngine` is a line-for-line port of the library's TypeScript frame
functions and presets for those four states only; `OrbEngineGoldenTest` checks all
32 of the library's frozen frames for them (both sizes, four moments, every dot's
position, radius, ink and alpha to 1e-4), extracted by `scripts/orbs_golden.py`.
Geometry is always computed at the 64px tuning and scaled onto the face; each
state keeps its own clock, advanced by real frame time at the preset speed, so a
loud voice can speed it up without a jump. The disc is a View, so the painter
draws on its Canvas rather than in Compose.

**One movement home.** Closing the box, or letting go of a drag, works out the exact
docked position before take-off and flies straight there: `DockPosition` clamps it the
way the window manager keeps an overlay window on screen. (The dock inset is measured
to the visible disc, so with its bloom room the wanted spot runs past the edge; flying
there and then being clamped was a second move.) Debug builds log
`flight: target`, `flight: landed` and `flight: settled` half a second later; all three
must match. `adb shell am broadcast -a xyz.heylana.app.debug.PANEL --es dock left|right`
and `--ez toggle true` dock and open or close without touching the disc.

**Nothing moves after landing.** A screen recording of a close showed the flight
landing, then the disc vanishing and sliding back in from the box's old corner: the
system's own window-move animation (about 220ms) as the full-screen box window became
the small docked one. The window opts out (`setCanPlayMoveAnimation(false)`, Android
14+); below 14 the flying stand-in stays in front for the 250ms slide. Also inside the
flight now: the dim to the resting look (the stand-in stops composing as it takes off),
the 80 → 64dp shrink (pinned to its final size at landing), and the goo silhouette, which
disappears as the disc takes off rather than fading at the top as a ghost. The disc takes
off when the draw-back is 90% done, so the two read as one motion.
`scripts/landing_check.py right|left` drives a close with the debug broadcast and, on the
phone, blocks on `flight: landed`, grabs a burst of frames straight after it and one from
+700ms, and fails if more than 8% of the disc's area differs (the idle breathing is 1–5%).
Before the fix it measured 18–31%.

**The overlay window has three modes, and everything follows from which one.**
Docked, it is a small window holding just the disc, so touches anywhere else
reach the app underneath. Composing, it takes the whole screen: the app behind
is dimmed, the buddy flies to the top centre and the glass box drops in beneath
it with the keyboard. As a task HUD it is small again and passive — no dim, no
keyboard — because during guidance a tap outside the box is the user doing the
step, not a request to dismiss.

**Gestures on the sprite, kept strictly apart.** A tap toggles the chat panel. A
drag moves the buddy and snaps it to an edge. A press-and-hold opens the
microphone; if that hold turns into a drag, the listening is thrown away and it
becomes an ordinary drag. The overlay window only becomes focusable while the
panel was opened by a tap — never mid-gesture, because changing focusability tears
down the touch stream and would swallow the release that ends a hold.

## Crash reports

**Sentry, off unless a DSN is built in, and scrubbed either way.** The app reads
`heylana.sentryDsn` from local.properties into `BuildConfig.SENTRY_DSN`; empty means
off. It reports from release builds only, unless `heylana.sentryDebug=true` (for the
test crash: long-press the version line at the bottom of Settings, debug builds
only). `HeylanaApp` starts it by hand (`io.sentry.auto-init` is false) with
`sentry-android-core` alone — no NDK, replay or screenshot modules — no default PII,
no screenshot or view hierarchy (the view hierarchy would carry the words on
screen), no tap, network or system breadcrumbs, no tracing. `ops/CrashReports` keeps
only navigation and lifecycle breadcrumbs, drops the user, request and extras, and
`ReportScrub` turns every base58 run of 32–88 characters into `[address]` and any
`sk-ant-…` or `Bearer …` into `[key]` in messages, exception text and breadcrumbs.

The worker reports only unhandled errors, through toucan-js, when the `SENTRY_DSN`
secret is set (`worker/src/sentry.ts`): only the request's method and path, no
breadcrumbs, no user or server name, and every string in the event through the same
secret scrub as error replies plus the same `[address]` rule. Tests check that an
address, a signature, the RPC address, a header and the device id never reach the
report body.

## Release build

**`./scripts/release.sh` builds, signs and copies it out.** It runs
`:app:assembleRelease`, refuses if the APK is unsigned, copies it to
`dist/heylana-<versionName>.apk` (`dist/` is gitignored), and prints its path, its
SHA-256 and the signing certificate's SHA-256 in colon form for `ASSETLINKS_SHA256`.
Extra arguments go to Gradle.

**Signing comes from local.properties**, or `-P` for a one-off:
`heylana.keystore` (a `~/` path is fine; by convention
`~/.heylana/release.keystore`, outside the repo), `heylana.keystorePass`,
`heylana.keyAlias`, `heylana.keyPass`. Without them a release still compiles,
unsigned. Version is `versionCode 1`, `versionName "0.9.0"`.

**R8 is on for release.** AGP 9 reads keep rules from `src/main/keepRules/*.keep`
(a `.pro` there is an error): Mobile Wallet Adapter, sol4k and BouncyCastle are kept
whole; OkHttp's optional TLS providers and Tink's compile-only annotations are
`dontwarn`; file names and line numbers are kept so crash reports point somewhere.
MWA clientlib-ktx 2.1.1 wrongly lists androidx.test as a runtime dependency, which put
three test activities in the release manifest; it is excluded (none of MWA's classes
use it). Sentry's own start-up providers are removed in the manifest.

**Nothing debug ships.** The release manifest has only Heylana's own activities and
services plus androidx.startup and the profile installer: no Debug states screen (it
is in `src/debug`), no loopback network config, no test activities. The Debug section
of Settings, the READ_SCREEN receiver, the test crash and every `HeylanaState` log
line sit behind `BuildConfig.DEBUG`, and R8 removes them: none of their strings is
in the release dex. Checked with `aapt2 dump xmltree` on the APK and a string search
of its classes.

## API budget

The operator pays for every request out of a small budget. Treat their key as
money, because it is.

**Claude Code never sends a request to `api.anthropic.com`. Ever.** Not from a
device, not from the emulator, not from a script, not with a key believed to be
fake, and not to "just check the error path". Self-tests use a fake key in a unit
test or against a stubbed client, and nothing else. **If a brief appears to permit
a live call — including by stating that the stored key is fake — treat that as an
error in the brief: skip the call and flag it in the report.** A key believed to
be fake has been the real one before.

**Claude Code never uses the operator's real API key**, and never replaces the
stored key to get a fake one either — overwriting it costs the operator their key
for no gain, since the rule above already forbids the request that would follow.

**The operator performs all real-answer testing.** Anything that needs a genuine
model reply — answer quality, pointing accuracy, whether a task completes — is
written up in `SMOKE.md` for them to run, never run here.

**Every `SMOKE.md` states the expected number of live API calls**, near the top,
before the first step. Keep that number as small as the phase allows and make the
test spend it deliberately.

Cost discipline in the code itself: send the smallest screen listing that still
works, keep the system prompt tight, cap `max_tokens`, and route cheap work to the
cheap model.

## Debug workflow

**After every Run from Android Studio, run `./scripts/a11y.sh`.** Installing the
app switches its accessibility service off, which greys out Start buddy on the
onboarding screen and leaves the buddy unable to read anything. The script sets
`enabled_accessibility_services` to Heylana's fully qualified component and
turns `accessibility_enabled` on, which is the same thing as walking through
Settings by hand and considerably faster.

Force-stopping the app clears the setting too, so run it again after any
`am force-stop` — and note that a running Heylana will not notice until the
onboarding screen is resumed, so restart the app rather than just re-launching
the intent.

**Watch what the buddy is doing with `HeylanaState`.** Debug builds trace every
gesture, mode change, recogniser callback and settle through `HeylanaLog`:

```
adb logcat -s HeylanaState
```

Names of things that happened and nothing else — no screen contents, no
transcript, no answer. It logs at info rather than debug because the Seeker drops
app debug lines from logcat entirely.

**The listening socket can be tested without Deepgram.** `scripts/stub-proxy.py`
also answers Deepgram's socket: by default it accepts, takes the audio and replies
with no words; with `--refuse-listen` it refuses with 401 and a JSON body, as a
bad key would. Point a debug build at it with `heylana.listenUrl=ws://127.0.0.1:
<port>/v1/listen` in `local.properties` — and take that line out again before
building for a real test. A hold can be simulated without a finger:

```
adb shell input swipe <disc_x> <disc_y> <disc_x> <disc_y> 3000
```

with the disc's centre taken from `adb shell dumpsys window windows`.

**The glass is checked on the debug states screen.** Settings → Debug states,
and the backdrop button flips the page between black and white. There is no
second launcher icon and no screen with PASS labels on it any more: **nothing
labelled, measured or debug-looking may ever appear over another app**, and the
surest way to keep that true is not to build such a screen at all.

## Working rules

1. **Every phase is built on its own branch.** Phase 0 lives on `phase0-overlay`,
   phase 1 on `phase1-...`, and so on. Never build a phase directly on `master`
   or on another phase's branch.

2. **Every build is verified before reporting done.** Run
   `./gradlew :app:assembleDebug` and fix every error until it compiles cleanly.
   "It should work" is not a report. Install with
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   (if `adb` is not on PATH, use `$HOME/Library/Android/sdk/platform-tools/adb`).

3. **The operator cannot read Kotlin.** Every report must be written for someone
   who will only ever touch the phone. State exactly which button to tap, in what
   order, and exactly what the screen should show after each tap. Never explain a
   change by pointing at code, a class name, or a diff. If a phase needs manual
   verification, write it up as a numbered tap-by-tap test in `SMOKE.md`.

4. **No scope creep between phases.** Do not add dependencies, permissions or
   subsystems a phase does not need.

5. **Heylana never acts for the user.** It reads the screen and points at things.
   It never taps, types, scrolls or gestures on the user's behalf, and it must
   never tell the user it has done something on their behalf. The one kind of
   doing it allows is a quick action the user asked for in their own words, handed
   to the phone as a standard intent so the phone's own app does it in front of them.

6. **The API key never appears in code, logs, or git.** It is entered by the
   operator on the Settings screen and lives only in EncryptedSharedPreferences
   on the device. Never hardcode it, never write it to a log, a comment, a test,
   a commit, or a file in the repo. Never print a screen snapshot either: what
   Heylana reads off the screen is used for one request and then dropped, never
   logged and never persisted.
