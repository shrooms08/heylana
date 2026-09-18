---
id: wallets-seed-vault
title: Wallets and Seed Vault
short: wallets and Seed Vault
track: build
aliases: wallets, wallet, seed vault, keys, keypair, seed phrase
chunks: 4
recap: A wallet is a keypair; Seed Vault keeps the key in secure hardware and signs only after you approve on its own screen.
checked: 2026-09-18 against docs.solanamobile.com
---
A wallet is an ed25519 keypair. The public key is your address; the private key signs. A seed phrase (usually 12 or 24 words, BIP-39) generates keys, so one phrase can back many addresses along derivation paths.

Anyone with the private key or seed phrase controls the funds. No one legitimate will ever ask for it, Heylana included.

Seed Vault is the Solana Mobile system service on Saga and Seeker that holds seeds in the phone's secure hardware (secure element or TEE). Keys never leave it; apps never see them.
- Wallet apps (Seed Vault Wallet, Phantom, Solflare and others) ask Seed Vault to sign.
- Seed Vault shows its own trusted screen for approval, confirmed with fingerprint or double-press.
- Apps reach a wallet through Mobile Wallet Adapter (next lesson).

Good habits:
- Read the approval screen: amount, recipient, and which network.
- Do not mark an app as trusted if you want to approve every signature yourself.
- Keep a written backup of the seed phrase offline.

A hardware-backed key is safer than a key in an app's storage, but it cannot protect you from approving a bad transaction. Simulation and reading the screen do that.
