---
title: How long is a Solana slot, and how many slots does a leader get in a row? (as of 2026-09)
url: https://solana.com/upgrades/reduced-slot-times
source: Solana docs and SIMD-0525
licence: GPL-3.0 (solana-foundation/solana-com), summarised by Heylana
---
As of September 2026 a slot is 300 milliseconds on mainnet. It was 400ms until August 2026: SIMD-0525 steps it down to 200ms in stages, and two are live — 350ms from epoch 1019 (19 August 2026) and 300ms from epoch 1023 (25 August 2026). Older answers saying 400ms are out of date. A leader still gets four consecutive slots, which SIMD-0525 explicitly leaves alone, so a leader's turn is 1.2 seconds today and will be 800 milliseconds at 200ms slots.
