---
id: mobile-wallet-adapter
title: Mobile Wallet Adapter
short: Mobile Wallet Adapter
track: build
aliases: mwa, mobile wallet adapter, wallet adapter
chunks: 5
recap: MWA connects an Android app to the user's wallet app over a local session to authorize, sign messages and sign and send transactions.
checked: 2026-09-18 against docs.solanamobile.com/android-native and the MWA spec
link: https://docs.solanamobile.com/developers/mobile-wallet-adapter
link_title: Solana Mobile: Mobile Wallet Adapter
---
Mobile Wallet Adapter (MWA) is the protocol a dApp on Android uses to talk to any compatible wallet app, instead of every dApp embedding every wallet.

How a session works:
- The dApp starts an association (an Android intent to a solana-wallet:// URI); the wallet app opens and the two connect over a local websocket on the phone, encrypted with keys exchanged at the start.
- authorize: the user approves the dApp by its identity (name, uri, icon) and the cluster (mainnet-beta, devnet, testnet). The wallet returns the account and an auth token to reuse later.
- sign_messages: sign arbitrary bytes, for sign-in (Sign In With Solana).
- sign_and_send_transactions: the wallet signs and submits, and returns signatures. sign_transactions (sign only) is deprecated in the spec.
- The session ends when the wallet closes.

For developers:
- Kotlin: clientlib-ktx's MobileWalletAdapter.transact; React Native: @solana-mobile/mobile-wallet-adapter-protocol; web: the wallet-standard mobile adapter.
- Pass the right cluster, or the wallet refuses a transaction built for another network.
- Treat a timeout as unknown, not failure: the wallet may have sent it. Look it up on chain before retrying.

Heylana uses MWA to connect your wallet and to hand sends to Seed Vault.
