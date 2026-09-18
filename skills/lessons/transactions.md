---
id: transactions
title: Transactions and instructions
short: transactions
track: build
aliases: transactions, instructions, transaction, instruction
chunks: 5
recap: A transaction is signed, atomic, and lists every account up front; each instruction names a program, its accounts and its data.
checked: 2026-09-18 against solana.com/docs/core/transactions
---
A transaction is how anything changes on Solana. It holds one or more instructions plus signatures, and it is atomic: either every instruction succeeds or none of them apply.

An instruction has three parts:
- the program id to call,
- the accounts it will touch, each marked signer and/or writable,
- instruction data: bytes the program decodes (for example "transfer 5000 lamports").

The transaction message adds a header (how many signers, how many read-only accounts), the full account list, and a recent blockhash.

Key facts:
- A recent blockhash acts as a timestamp: a transaction is only valid for about 150 blocks after it, roughly a minute to a minute and a half, then it expires.
- The first signer is the fee payer.
- Size limit: 1,232 bytes per transaction. Address lookup tables (versioned v0 transactions) let you reference many more accounts cheaply.
- Every account is declared in advance so the runtime can lock writable accounts and run non-overlapping transactions in parallel.
- Signing proves consent; wallets like Seed Vault sign, and the RPC submits.

Simulation (simulateTransaction) runs it without committing, which is how Heylana checks a send before you approve it.
