---
title: What is a local fee market on Solana? (as of 2026-09)
url: https://solana.com/docs/core/fees
source: Solana docs
licence: GPL-3.0 (solana-foundation/solana-com), summarised by Heylana
---
Priority on Solana is worked out per account, not across the whole chain. The scheduler charges by contention on the specific accounts a transaction wants to write, so a crowd competing to write one hot account raises the priority fee for that account and leaves every unrelated transaction cheap. That is a local fee market: one busy mint does not make a plain transfer expensive, which is why a single network-wide gas price does not describe Solana. The fee itself is the compute unit limit multiplied by the compute unit price in micro-lamports, set with ComputeBudgetProgram.setComputeUnitLimit and setComputeUnitPrice.
