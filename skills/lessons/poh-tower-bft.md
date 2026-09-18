---
id: poh-tower-bft
title: Proof of History and Tower BFT
short: Proof of History
track: infrastructure
aliases: proof of history, poh, tower bft, consensus
chunks: 5
recap: Proof of History is a verifiable clock of chained hashes; Tower BFT is the voting that uses it, with lockouts that double on each vote.
checked: 2026-09-18 against the Solana whitepaper and docs.anza.xyz
---
Proof of History (PoH) is a clock, not a consensus mechanism on its own.
- The leader runs SHA-256 in a loop, each hash taking the previous as input. The chain can only be made one step at a time, so its length proves time has passed.
- Transactions are mixed into the chain, so their order and rough timing are recorded and anyone can verify the sequence, in parallel, much faster than it was made.
- A slot's worth of ticks gives every validator the same sense of time without talking to each other first.

Tower BFT is Solana's consensus, a form of practical Byzantine fault tolerance that uses the PoH clock.
- Validators vote on forks. Each vote puts a lockout on it: after voting, a validator cannot switch forks for a number of slots.
- Each new vote on top doubles the lockouts beneath it, so old votes become very costly to abandon.
- After 32 votes stacked, a block is rooted: finalized.
- Two thirds of stake voting for a block makes it confirmed.

Together: PoH orders events, Tower BFT agrees on which fork is canonical. Alpenglow, a proposed replacement consensus (Votor and Rotor) aiming at about 150ms finality, was approved by governance in 2025; its mainnet status is unverified.
