---
title: How long is a Solana epoch? (as of 2026-09)
url: https://solana.com/upgrades/reduced-slot-times
source: Solana docs and SIMD-0525
licence: GPL-3.0 (solana-foundation/solana-com), summarised by Heylana
---
As of September 2026 an epoch is 432,000 slots, which is about 36 hours — a day and a half — at the current 300ms slot time. The 432,000 is fixed and SIMD-0525 keeps it fixed, so the wall-clock length moves with the slot time: it was about two days at 400ms slots, is about 36 hours now, and will be about 24 hours once slots reach 200ms. Answers that say an epoch is two days are quoting the old slot time.
