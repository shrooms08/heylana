---
id: account-model
title: The account model
short: the account model
track: build
aliases: accounts, account model, solana accounts
chunks: 5
recap: Everything on Solana is an account: an address, lamports, data, and an owner program that alone may change it.
checked: 2026-09-18 against solana.com/docs/core/accounts
---
Everything on Solana is stored in accounts. An account is a key-value record: the key is a 32-byte address (usually shown in base58), the value holds four things that matter: lamports (its SOL balance; 1 SOL = 1,000,000,000 lamports), data (a byte array, up to 10 MiB), owner (the program allowed to change its data and take its lamports), and executable (true for program accounts).

Rules that follow:
- Only the owner program may modify an account's data or debit its lamports. Anyone may credit lamports to any account.
- Wallets are accounts owned by the System Program with no data. Programs are accounts too, marked executable; their state lives in separate data accounts they own.
- Programs are stateless: code and state are kept apart, so one program can serve many accounts.
- Every account must hold enough lamports to be rent-exempt, in proportion to its data size (see the rent lesson).
- The System Program creates accounts and assigns their owner; after that, the owner decides.

Why it matters: a transaction must list every account it reads or writes, up front. That is what lets the runtime run transactions touching different accounts in parallel.

Common confusion: "my tokens are in my wallet". Tokens actually live in separate token accounts owned by the Token program, one per mint, linked to the wallet as their authority.
