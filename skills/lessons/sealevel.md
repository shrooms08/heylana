---
id: sealevel
title: Sealevel and parallel execution
short: Sealevel
track: infrastructure
aliases: sealevel, parallel execution, parallel, runtime
chunks: 4
recap: Because every transaction lists its accounts, Sealevel runs transactions that touch different writable accounts at the same time.
checked: 2026-09-18 against solana.com/docs
---
Sealevel is Solana's parallel runtime.

The idea
- Every transaction declares up front every account it will read and which it will write.
- Two transactions that write different accounts cannot interfere, so they can run at the same time on different CPU cores.
- Transactions that only read the same account can also run together. Only a shared writable account forces them in sequence.

How it plays out
- The scheduler in the leader takes account locks, packs non-conflicting transactions into batches and runs them in parallel.
- A "hot" account written by many transactions at once (a popular market, a mint during a launch) becomes a queue of its own. That is why fees rise for that account while the rest of the network stays cheap (see the local fee markets lesson).
- Programs are compiled to SBF bytecode and run in a virtual machine with a compute budget per transaction.

What it means for builders
- Design state so users write their own accounts (for example one PDA per user) instead of one global account everyone writes.
- Keep writable accounts to what is needed; mark the rest read-only.

Contrast: the EVM executes transactions one at a time because it cannot know in advance what storage a transaction touches.
