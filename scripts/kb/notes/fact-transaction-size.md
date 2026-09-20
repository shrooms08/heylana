---
title: How big can a Solana transaction be? (as of 2026-09)
url: https://solana.com/docs/core/transactions
source: Solana docs and SIMD-0296
licence: GPL-3.0 (solana-foundation/solana-com), summarised by Heylana
---
As of September 2026 a legacy or v0 transaction is capped at 1,232 bytes — the 1,280-byte IPv6 minimum MTU less 48 bytes of headers. The v1 transaction format raises the cap to 4,096 bytes and has been live on mainnet since epoch 1035, 15 September 2026 (SIMD-0296 for the size, SIMD-0385 for the format); anything above the MTU is carried over QUIC. v1 drops address lookup tables — its addresses are all inline — and carries its resource limits in message fields instead of ComputeBudget instructions. A transaction of any version may lock at most 64 accounts.
