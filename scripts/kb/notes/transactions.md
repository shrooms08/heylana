---
title: Transaction size, versions, lookup tables and priority fees, as of 2026-09
url: https://solana.com/docs/core/transactions
source: Solana docs
licence: GPL-3.0 (solana-foundation/solana-com), summarised by Heylana
---
As of September 2026, and this is the part that moved most this year.

Size. A legacy or v0 transaction is capped at 1,232 bytes — the 1,280-byte IPv6 minimum MTU minus 48 bytes of headers. The v1 format raises that to 4,096 bytes and has been live on mainnet since epoch 1035, 15 September 2026 (SIMD-0296 for the size, SIMD-0385 for the format). Transactions above the MTU travel over QUIC. Note that v1 deliberately drops address lookup tables: all its addresses are inline, and its resource limits live in message fields rather than in ComputeBudget instructions.

Accounts. A transaction may lock at most 64 accounts (MAX_TX_ACCOUNT_LOCKS). A feature gate that would raise it to 128 exists and is not active. Every account a transaction touches is declared up front, as read-only or writable — that is what lets Sealevel run transactions that do not overlap at the same time, and why two transactions that write the same account run one after the other.

Versioned transactions. Legacy transactions list every address in full. v0 added address lookup tables: a table account holds addresses, and the transaction references them by a one-byte index instead of 32 bytes each, which is how a transaction reaches far more than the roughly thirty addresses that fit inline. Create a table, extend it, wait a slot for it to warm up, then pass it in the transaction's `addressTableLookups`.

Compute. An instruction gets 200,000 compute units by default (a builtin gets 3,000), and a transaction may ask for at most 1,400,000. With no ComputeBudget instruction the transaction's limit is 200,000 times the number of instructions, capped at 1.4M.

Priority fees. `ComputeBudgetProgram.setComputeUnitLimit({ units })` sets the limit and `ComputeBudgetProgram.setComputeUnitPrice({ microLamports })` sets the price. The priority fee is the limit multiplied by the price, in micro-lamports, so asking for fewer units makes the same price cheaper. `getRecentPrioritizationFees` tells you what recent transactions paid for the accounts you are about to write. There is no `setPriorityFee` instruction.

Local fee markets. Priority is worked out per account, not across the whole chain: a queue for one hot account raises the price of writing that account and leaves everything else alone. That is why a busy mint does not make an unrelated transfer expensive.

Blockhash. A transaction carries a recent blockhash and is only valid for about 150 blocks — "Blockhash not found" means it went stale, so fetch a fresh one and sign again. "This transaction has already been processed" means the same signature, from the same blockhash and message, was already accepted.
