---
id: solana-mobile-stack
title: The Solana Mobile stack
short: the Solana Mobile stack
track: infrastructure
aliases: solana mobile, solana mobile stack, seeker, saga, seeker id, skr, guardians
chunks: 5
recap: Seed Vault, Mobile Wallet Adapter and the dApp Store make the Seeker a crypto phone; Seeker ID gives a .skr name, and SKR is the ecosystem token staked to Guardians.
checked: 2026-09-18 against docs.solanamobile.com and Solana Mobile announcements
---
Solana Mobile builds Android phones for crypto: Saga (2023) and Seeker (2025). The software stack:

- Seed Vault: seeds kept in the phone's secure hardware; wallets ask it to sign and it shows its own approval screen. Keys never leave.
- Mobile Wallet Adapter (MWA): how any app on the phone asks the user's wallet to authorize, sign messages and sign and send transactions.
- Seed Vault Wallet: Solana Mobile's own wallet app on Seeker.
- Solana dApp Store: an app store for crypto apps, submitted through the Publisher Portal.
- Seeker ID: a .skr name for your wallet, an AllDomains record on mainnet, claimed at setup (unverified: details of reverse lookup).
- Genesis Token: a soulbound token proving a genuine device, used for rewards and access.

SKR
- The Seeker ecosystem token, 10 billion supply, claimable from January 21, 2026, with nearly 2 billion airdropped to Seeker users and developers.
- Staked to Guardians, who verify devices and review apps, from Seed Vault Wallet or stake.solanamobile.com. Inflation started at 10% and falls 25% a year toward 2%.
- Also used for dApp Store curation and governance (unverified: governance scope).

For builders: target Android with MWA, test on a real device, publish to the dApp Store. Heylana itself is built on this stack.
