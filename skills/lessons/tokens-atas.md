---
id: tokens-atas
title: Tokens and ATAs
short: tokens and ATAs
track: build
aliases: tokens, spl tokens, ata, atas, associated token account, associated token accounts, mint
chunks: 5
recap: A mint defines a token; balances live in token accounts; the associated token account is the one standard account per wallet per mint.
checked: 2026-09-18 against solana.com/docs/tokens
link: https://solana.com/docs/tokens
link_title: Solana docs: tokens
---
Tokens on Solana are handled by the Token program (and Token-2022, its extended version), not by one contract per token.

The pieces:
- Mint account: defines the token. Holds total supply, decimals (USDC has 6), and a mint authority that may create more (or none, for a fixed supply), plus an optional freeze authority.
- Token account: holds a balance of one mint for one owner. Its owner field is the wallet that controls it; the account itself is owned by the Token program.
- Associated token account (ATA): the standard token account for a wallet and mint, at a PDA derived from wallet, token program and mint. Anyone can compute it, so a sender knows where to send.

Sending tokens:
- Transfer from your ATA to theirs. If theirs does not exist yet, create it first; the creator pays its rent (about 0.002 SOL). "Create idempotent" does nothing if it already exists.
- transferChecked also states the mint and decimals, so a wrong mint or amount fails loudly.

Amounts are integers in base units: 0.05 USDC is 50,000 base units.

NFTs are mints with supply 1 and 0 decimals, usually with metadata from Metaplex.
