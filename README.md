# Heylana

An on-screen AI buddy for the Solana Seeker: a small glass disc that floats over every app, answers out loud about what is on screen, points at the button you need, and walks you through a task one tap at a time.

She is a companion and a guide for Solana users. She watches your back when you sign a transaction, explains any screen you are looking at, teaches the apps you already have, answers Solana questions with sources, and keeps up with what is happening in the ecosystem.

**[Download the signed APK (v1.0.8)](https://github.com/shrooms08/heylana/releases/tag/v1.0.8)** — Android, built and tested on a Solana Seeker, running on mainnet.

## What she does

- **Speaks before you sign.** When Seed Vault opens, she says what the transaction actually does, out loud, before you can tap Approve.
- **Warns you.** Addresses you have never sent to, sites that look like copies of real Solana apps, anything asking for your recovery phrase, and a scam watchlist of about 2,300 domains built from ScamSniffer's feed and Phantom's blocklist, checked on the phone.
- **Sends by voice.** She prepares and simulates a transfer and shows what leaves your wallet and the fee. Nothing moves until you approve it in Seed Vault. She never signs.
- **Teaches your apps.** Walk-throughs for Jupiter, the Seeker Wallet's USDC Earn, and the dApp Store, pointing at the real buttons and stopping at the review screen.
- **Answers Solana questions** from about 3,300 passages of Solana docs, Stack Exchange and Anza, with a source on every answer.
- **Keeps up.** Hackathons, releases and ecosystem news from a 6-hour cache, with dates and sources, and a live lookup when the cache has nothing.

She never says anything is "safe", never signs, and only listens while you hold her.

## Install

Download the APK from the [v1.0.8 release](https://github.com/shrooms08/heylana/releases/tag/v1.0.8) and install it on a Solana Seeker (or any Android 14+ device, though the Seed Vault beats need a Seeker).

After installing, grant the accessibility permission so she can read the screen: **Settings → Accessibility → Heylana → On → Allow**. Then open Heylana and tap **Start buddy**.

Needs Wi-Fi or mobile data. On mainnet with the judge code, everything is unlimited until Nov 9.

## Build it yourself

```bash
git clone https://github.com/shrooms08/heylana.git
cd heylana
./gradlew :app:installDebug
```

The app talks to a Cloudflare Worker that holds every API key; no keys live in the app. To run your own:

```bash
cd worker
npm install
npx wrangler secret put ANTHROPIC_API_KEY
npx wrangler secret put DEEPGRAM_API_KEY
npx wrangler secret put ASSEMBLYAI_API_KEY
npx wrangler secret put RPC_URL
npx wrangler deploy
```

Then point the app at your worker by setting `heylana.proxyUrl` in `local.properties`. `CLUSTER` and the USDC mint live in `worker/wrangler.toml`; set them to `devnet` and the devnet mint to test without real money.

## How it is built

- **App:** Kotlin, Jetpack Compose, an accessibility service for reading the screen and an overlay service for the disc
- **Wallet:** Mobile Wallet Adapter and Seed Vault. Every signature is the user's.
- **Backend:** Cloudflare Workers, KV and Vectorize
- **Brain:** Claude
- **Ears:** AssemblyAI Universal-Streaming, Deepgram and the Android recogniser, racing; the first confident final transcript wins
- **Voice:** Deepgram Aura
- **RPC:** RPC Fast as primary with Helius as fallback, with per-call latency logging (21 to 104 ms measured on mainnet)

See `PRODUCT.md` for what it does and what leaves the phone, `CLAUDE.md` for how it is built, and `worker/README.md` for the proxy that holds the keys.

## Built by

Minos, an independent developer in Lagos, Nigeria. Built for the Solana Seeker, not by Solana Mobile.

## Credits

- **Orb states** — the listening, thinking, working and speaking orbs on the disc are a Kotlin port of the geometry engine of [thinking-orbs](https://libraries.dev/orbs) by Jakub Antalik, MIT licence. The licence ships in the app at `app/src/main/assets/licenses/thinking-orbs.txt`; the port is tested against the library's own golden vectors.
- **Rim beam** — the aurora glow that laps the box and the disc while Heylana thinks, listens and speaks is a port of the border geometry of border-beam by Jakub Antalik, MIT licence (`app/src/main/assets/licenses/border-beam.txt`).
- **Gooey merges** — the box growing out of the disc and pinching between shapes follows the technique of liquid-gooey by Jakub Antalik, MIT licence (`app/src/main/assets/licenses/liquid-gooey.txt`).
- **Liquid glass reference shader** — `design/refs/liquid_glass.glsl`, from Shadertoy WftXD2.
- **The proxy** — shaped after [Farza's Clicky worker](https://github.com/farzaa/clicky) (MIT).
