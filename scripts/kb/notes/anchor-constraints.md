---
title: Anchor account constraints, as of 2026-09
url: https://www.anchor-lang.com/docs/references/account-constraints
source: Anchor docs
licence: Apache-2.0 (otter-sec/anchor), summarised by Heylana
---
As of September 2026. Anchor is v1.2.0 (released 2026-09-04); the constraint names below have been stable since 0.29.

Constraints go in `#[account(...)]` on a field of an Accounts struct. The common ones:

- `init` — creates the account. Needs `payer = <field>` and `space = <bytes>`, and the System Program in the struct. The payer pays rent exemption. Anchor writes the 8-byte discriminator, so `space` must include it.
- `init_if_needed` — `init`, but only when the account does not exist yet. Needs the `init-if-needed` cargo feature, because it is a re-initialisation risk: Anchor treats an account with zero lamports, or one owned by the System Program, as uninitialised, so someone can pre-create the address with data of their choosing. Pair it with `owner = crate::ID` and check the state in the handler.
- `mut` — the account will be written. Without it the runtime refuses the write.
- `signer` — the account signed the transaction.
- `seeds = [...]` and `bump` — re-derives the PDA from the seeds and checks it is the account that was passed. `bump` on its own uses the canonical bump; `bump = x` uses a stored one, which is cheaper.
- `has_one = other` — the field named `other` stored on this account must equal the key of the account named `other` in the struct.
- `owner = <pubkey>` — the account's owning program.
- `address = <pubkey>` — the account's key, exactly.
- `constraint = <expr>` — any expression that must be true; the raw escape hatch.
- `close = <field>` — closes the account after the instruction and sends its lamports to the field.
- `realloc = <bytes>`, with `realloc::payer` and `realloc::zero` — resizes an existing account.
- `token::mint`, `token::authority`, `mint::decimals`, `mint::authority`, `associated_token::mint`, `associated_token::authority` — SPL checks on a token account or a mint.

Account types: `Account<'info, T>` (deserialises and checks the owner and the discriminator), `Signer`, `SystemAccount`, `Program<'info, T>`, `UncheckedAccount` and `AccountInfo` (no checks; both need a `/// CHECK:` comment saying why it is safe), `Sysvar`, `InterfaceAccount` (works with Token and Token-2022), and `LazyAccount` (0.31, experimental, behind the `lazy-account` feature, for when deserialisation cost matters).
