---
title: Mobile Wallet Adapter, Seed Vault and the dApp Store, as of 2026-09
url: https://docs.solanamobile.com/mobile-wallet-adapter/mobile-apps
source: Solana Mobile docs
licence: no licence stated in the repository; quoted with a link, summarised by Heylana
---
As of September 2026, for the Solana Seeker and other Android phones.

Mobile Wallet Adapter is the protocol between an app and a wallet on the same phone. The app opens a session with the wallet app; the wallet shows its own screens; the app never sees a private key.

- `authorize` — asks the wallet for permission. It takes an identity (name, uri, icon), a chain, and the features the app wants. It returns an `auth_token` to reuse next time and the authorised `accounts`, each with an `address` (base64-encoded, not base58), and optionally a label, a display address, chains and features. `reauthorize` refreshes a token; `deauthorize` gives it up.
- `signTransactions` — the wallet signs and hands the signed transactions back; the app submits them.
- `signAndSendTransactions` — the wallet signs and submits them itself, and returns the signatures. On the Seeker this is the usual one, because the wallet knows which RPC to use.
- `signMessages` — signs arbitrary bytes, never a transaction. Sign-in uses this.
- The errors: ERROR_AUTHORIZATION_FAILED (-1, the user declined or the token is stale), ERROR_INVALID_PAYLOADS (-2), ERROR_NOT_SIGNED (-3, the user declined to sign), ERROR_NOT_SUBMITTED (-4, signed but not sent), ERROR_NOT_CLONED (-5), ERROR_TOO_MANY_PAYLOADS (-6), ERROR_CLUSTER_NOT_SUPPORTED (-7).

Seed Vault is where the keys live on a Solana phone. The seed is held by the phone's secure hardware; apps ask Seed Vault to sign, and Seed Vault shows the request and takes the user's approval — fingerprint or PIN — itself. The private key never leaves it and is never handed to an app. An app can be marked trusted, after which Seed Vault signs without asking again, which is worth telling a user about rather than letting them discover.

The dApp Store is a catalogue whose entries live on chain rather than in a company's database. Publishing means creating three NFTs — a publisher, an app, and a release — with the `dapp-store` CLI, then submitting the release for review:
`npx dapp-store init`, fill in `config.yaml`, `npx dapp-store create publisher -k <keypair>`, `create app`, `create release` (it validates the APK and uploads the media), then `npx dapp-store publish submit -k <keypair> --requestor-is-authorized`. There is no listing fee; the review is by the Solana Mobile team.
