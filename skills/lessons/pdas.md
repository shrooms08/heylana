---
id: pdas
title: PDAs and seeds
short: PDAs
track: build
aliases: pda, pdas, program derived address, program derived addresses, seeds, bump
chunks: 5
recap: A PDA is an address derived from seeds and a program id, off the curve so no private key exists, that only its program can sign for.
checked: 2026-09-18 against solana.com/docs/core/pda
---
A Program Derived Address (PDA) is an address computed from seeds plus a program id, not from a keypair.

How it is found: hash the seeds, a bump byte and the program id. If the result is a valid ed25519 public key (on the curve), try the next bump down from 255. The first bump that lands off the curve is the canonical bump. Off the curve means no private key exists, so no person can sign for it.

What it gives you:
- Deterministic addresses: seeds like "vault" + the user's wallet always give the same account, so clients can find it without storing it.
- Program signing: the owning program can "sign" for its PDA in a CPI with invoke_signed, passing the seeds and bump. Only that program can.
- Hash-map style storage: one PDA per user, per pair, per order.

Seeds are byte strings, up to 16 of them, each at most 32 bytes.

Everyday example: an associated token account is a PDA of the Associated Token Account program, seeded by wallet, token program and mint.

Common mistakes: not storing or checking the canonical bump, and letting a user pass any account where a PDA was expected. Anchor's seeds and bump constraints check this for you.

A PDA is just an address. It becomes an account only once something creates it.
