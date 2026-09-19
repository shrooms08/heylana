---
id: anchor
title: Anchor basics
short: Anchor
track: build
aliases: anchor, anchor framework, anchor basics
chunks: 5
recap: Anchor writes the boilerplate: #[program] for instructions, #[derive(Accounts)] to validate accounts, and an IDL for clients.
checked: 2026-09-18 against anchor-lang.com docs
link: https://www.anchor-lang.com/docs
link_title: Anchor docs
---
Anchor is the most used framework for Solana programs in Rust. It removes the boilerplate of decoding instructions and checking accounts.

The main pieces:
- declare_id!: the program's address.
- #[program] mod: each pub fn is an instruction handler taking a Context.
- #[derive(Accounts)] struct: lists the accounts an instruction needs, with constraints Anchor checks before your code runs: mut, signer, has_one, seeds and bump for PDAs, init with payer and space to create an account.
- #[account] struct: your data layout. Anchor adds an 8-byte discriminator in front so one account type cannot be passed off as another.
- Errors with #[error_code], events with emit!.

Workflow:
- anchor init creates a project; anchor build compiles and writes the IDL (a JSON description of instructions and accounts); anchor test runs tests on a local validator; anchor deploy ships it.
- Clients use the IDL: @coral-xyz/anchor in TypeScript builds typed instruction calls.

CPIs: Anchor's CpiContext calls other programs, with_signer for PDA signing.

Security still matters: constraints only check what you declare. Validate owners, signers and seeds for every account a user can pass in.
