---
title: Is rent still collected on Solana? (as of 2026-09)
url: https://solana.com/docs/core/accounts
source: Solana docs and SIMD-0084
licence: GPL-3.0 (solana-foundation/solana-com), summarised by Heylana
---
As of September 2026, no rent is collected from accounts. SIMD-0084 turned collection off at mainnet slot 326,592,000, and the collection code has since been removed from the validator; the account structure's rent field is deprecated. What remains is rent exemption: an account must hold a minimum balance for its data size when it is created — about 0.00203928 SOL for a 165-byte token account, 0.00207408 SOL for a 170-byte Token-2022 one — and that balance comes back when the account is closed. Ask getMinimumBalanceForRentExemption rather than hardcoding it.
