---
title: SPL Token and associated token accounts, as of 2026-09
url: https://solana.com/docs/tokens
source: Solana docs
licence: GPL-3.0 (solana-foundation/solana-com), summarised by Heylana
---
As of September 2026. Two token programs run side by side: SPL Token (TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA) and Token-2022 (TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb). A mint is owned by one of them for ever; there is no in-place upgrade from one to the other, so moving a token to Token-2022 means a new mint and moving holders, or wrapping with the token-wrap program.

A mint account holds the supply, the decimals, the mint authority and the optional freeze authority. A token account holds a balance of one mint for one owner. The mint does not know who holds it; the token accounts do.

The instructions worth knowing: InitializeMint, InitializeAccount, Transfer (3, deprecated in favour of TransferChecked), Approve (4) and Revoke (5), SetAuthority (6), MintTo (7), Burn (8), CloseAccount (9), FreezeAccount (10) and ThawAccount (11), TransferChecked (12), ApproveChecked (13), MintToChecked (14), BurnChecked (15), SyncNative (17). The "checked" ones carry the mint and its decimals so the amount cannot be read wrongly; prefer them.

Approve is the quiet one: it gives a delegate the right to move up to an amount out of the account later. Nothing moves when it is signed.

The errors, by name: NotRentExempt, InsufficientFunds (the source account has fewer tokens than the transfer), InvalidMint, MintMismatch (the token account belongs to a different mint), OwnerMismatch (the signer is not the owner or the delegate), FixedSupply, AlreadyInUse, NonNativeHasBalance (a token account can only be closed at a zero balance), AccountFrozen, InvalidState.

Associated token accounts: the ATA program (ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL) derives one address per owner and mint, so anyone can work out where to send tokens without being told. Create it with `createAssociatedTokenAccountIdempotent` (or `getOrCreateAssociatedTokenAccount` in JS), which costs the rent of the account — 165 bytes, 0.00203928 SOL, and 170 bytes and 0.00207408 SOL under Token-2022, which adds the immutable-owner extension. Query `getMinimumBalanceForRentExemption` rather than hardcoding.

The Memo program (MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr) attaches a UTF-8 note to a transaction. It is a separate instruction, it is public, and nothing enforces what it says.
