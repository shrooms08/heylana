---
title: Token-2022 extensions and what each one does, as of 2026-09
url: https://solana.com/docs/tokens/extensions
source: Solana docs
licence: GPL-3.0 (solana-foundation/solana-com), summarised by Heylana
---
As of September 2026. Token-2022 is the token program with extensions: everything SPL Token does, plus behaviour that is chosen when the mint or the account is created and cannot be added afterwards.

Mint extensions:
- TransferFee — a percentage of every transfer is withheld, with a maximum, and harvested later by the withdraw authority.
- TransferHook — every transfer calls a program the mint names, which can refuse it. The hook runs with the transfer's accounts.
- ConfidentialTransfer — balances and amounts are hidden with zero-knowledge proofs, with an auditor key option.
- InterestBearing — the balance shown grows at a rate; the underlying amount does not change, only what the UI computes.
- NonTransferable — the token cannot be moved once held ("soulbound").
- PermanentDelegate — an address that can move or burn any holder's tokens at any time. Worth reading out loud before anyone buys.
- DefaultAccountState — new token accounts start frozen until the freeze authority thaws them.
- MintCloseAuthority — the mint can be closed and its rent recovered.
- MetadataPointer and TokenMetadata — name, symbol and URI on the mint itself, without a separate metadata account.
- GroupPointer, TokenGroup and TokenGroupMember — collections.
- ScaledUiAmount — the amount shown is multiplied by a factor, for stock-split-like changes.
- Pausable — transfers, minting and burning can be paused by an authority.

Account extensions: ImmutableOwner (set automatically on associated token accounts), MemoTransfer (incoming transfers must carry a memo), CpiGuard (limits what a program can do with the account in a CPI), and the account side of ConfidentialTransfer.

Two practical points. A wallet that only knows SPL Token will not show a Token-2022 mint, so check which program owns the mint before assuming an instruction will work. And an extension is part of the mint: a token with a transfer fee or a permanent delegate is a different thing to hold than one without, whatever the name on it says.
