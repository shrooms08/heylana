---
id: fees-compute
title: Fees, priority fees, local fee markets and compute units
short: priority fees
track: infrastructure
aliases: priority fees, priority fee, compute units, compute budget, local fee markets, fee markets, cu
chunks: 5
recap: Work is measured in compute units; a priority fee in micro-lamports per CU buys earlier inclusion, and it rises only around busy accounts.
checked: 2026-09-18 against solana.com/docs/core/fees
---
Compute units (CU) measure how much work a transaction does.
- Default budget: 200,000 CU per instruction, at most 1.4 million per transaction. Set a lower, accurate limit with the Compute Budget program's SetComputeUnitLimit.
- Blocks have a total CU limit (raised from 48M to 50M, then 60M in July 2025, then 100M at epoch 1009 in July 2026) and a limit per writable account (12M CU).

Fees
- Base fee: 5,000 lamports per signature. Half is burned, half goes to the leader.
- Priority fee: SetComputeUnitPrice in micro-lamports per CU. Total priority fee = CU limit x price. Since SIMD-0096 all of it goes to the validator.
- Higher priority helps the scheduler pick your transaction first when a block is contested.

Local fee markets
- Congestion is per account, not per chain. If everyone is writing one market's account, only transactions touching it need high priority fees; a send elsewhere stays cheap.
- Fee estimators (RPC methods such as getRecentPrioritizationFees, or provider APIs like Helius's) suggest a price from recent fees for the accounts you will write.

Practical recipe: simulate to learn the CU used, set the limit a little above it, add a modest priority fee, retry with a fresh blockhash if it drops.
