---
title: How the network runs — slots, consensus, clients, as of 2026-09
url: https://docs.anza.xyz/
source: Anza docs and the Solana Improvement Documents
licence: Apache-2.0 (anza-xyz/agave), summarised by Heylana
---
As of September 2026. Two of these numbers changed in the last two months, so the date matters.

Slots and leaders. A slot is 300 milliseconds on mainnet, down from 400ms: SIMD-0525 is stepping it to 200ms in stages, and 350ms (epoch 1019, 19 August 2026) and 300ms (epoch 1023, 25 August 2026) are live. A leader gets four consecutive slots, which the SIMD leaves unchanged, so a leader's turn is 1.2 seconds today. An epoch is 432,000 slots — fixed — which is about 36 hours at 300ms, not the two days it used to be.

Proof of History is a clock, not consensus. A validator hashes SHA-256 over and over, each hash taking the previous one as input, so the sequence cannot be computed in parallel or faked backwards; events are stamped into it. That gives every validator an agreed order and passage of time without them talking to each other about it. Tower BFT is the consensus that votes on forks, and it uses that clock.

No mempool. A transaction is forwarded straight to the current leader and the next few leaders (Gulf Stream), so there is no public pool of pending transactions to watch or reorder. Priority is bought with a fee per account, not by bidding in a queue everyone can see.

Turbine spreads a block: the leader cuts it into shreds, each shred goes to a small set of validators, and they pass it on down a tree, so no one has to send the whole block to everyone.

Sealevel is the runtime that runs transactions at the same time. Every transaction declares the accounts it reads and writes up front, so the scheduler can run any two that do not want to write the same account; two that do are serialised, one after the other.

Geyser is the validator's plugin interface for streaming out what it sees — account writes, slots, transactions, blocks — into a database or a queue, so an indexer does not have to poll RPC. It is a shared library the validator loads, configured with `--geyser-plugin-config`.

Snapshots are a compressed copy of the accounts database at a slot. A validator that is starting, or that has fallen behind, downloads one from a peer and replays from there rather than from genesis; full snapshots are taken every so often with incremental ones in between.

Clients. Agave is the validator client maintained by Anza, a fork of the original Solana Labs validator, whose repository was archived in January 2025; the current line is Agave 3.x. Firedancer is an independent client written from scratch by Jump Crypto: the hybrid, Frankendancer (Firedancer's networking on Agave's runtime), has been on mainnet since 2024, and full Firedancer went live in December 2025. As of 2026 roughly 14% of stake runs full Firedancer and about 26% Frankendancer, with the rest on Agave — so more than one client now produces blocks.

Stake and rewards. Stake is delegated from a stake account to a vote account; rewards are worked out at the end of each epoch from the validator's vote credits and paid into the stake account automatically, minus the validator's commission. Validators pay for their own votes: a vote is a transaction with a fee, so running a validator costs SOL every epoch whether or not it earns any.

Rent. Rent is no longer collected from accounts (SIMD-0084). Every account must still hold the rent-exempt minimum for its size when it is created, and that balance is returned when the account is closed. The `rent` field in the account structure is deprecated.
