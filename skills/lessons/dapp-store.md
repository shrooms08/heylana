---
id: dapp-store
title: dApp Store publishing
short: dApp Store publishing
track: build
aliases: dapp store, dapp store publishing, publish an app, publishing
chunks: 4
recap: The Solana dApp Store takes a signed release APK through the Publisher Portal, with a publisher wallet, KYC and a few days of review.
checked: 2026-09-18 against docs.solanamobile.com/dapp-publishing
---
The Solana dApp Store is the app store on Saga and Seeker. It lists crypto apps that Google Play's policies can make hard to ship, with no store fee on in-app crypto transactions (unverified: check the current policy).

Publishing today goes through the Publisher Portal, a web app at publish.solanamobile.com:
1. Sign up, fill in the publisher profile and complete KYC or KYB.
2. Connect a publisher wallet with a browser extension (Phantom, Solflare, Backpack). Keep about 0.2 SOL in it for transaction fees and ArDrive storage uploads.
3. Prepare a release APK signed with your release key (not a debug key), plus name, description, screenshots and icon.
4. Submit. Review results arrive by email within about 3 to 5 business days, in submission order.

Behind the scenes, publisher, app and each release are recorded on chain as NFTs, and assets are stored on Arweave. The older route is the dapp-publishing CLI (solana-mobile/dapp-publishing), which does the same steps from a config file.

Tips: bump versionCode for every release, keep the same signing key forever (it identifies the app), and test the APK on a real Seeker.
