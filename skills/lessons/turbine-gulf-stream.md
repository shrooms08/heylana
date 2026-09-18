---
id: turbine-gulf-stream
title: Turbine and Gulf Stream (no mempool)
short: Turbine and Gulf Stream
track: infrastructure
aliases: turbine, gulf stream, mempool, no mempool, block propagation
chunks: 4
recap: Gulf Stream forwards transactions straight to upcoming leaders instead of a mempool; Turbine spreads blocks out as small shreds through a tree of validators.
checked: 2026-09-18 against solana.com/docs and docs.anza.xyz
---
Two ways Solana moves data fast.

Gulf Stream: no mempool
- Most chains keep a public waiting room of pending transactions, the mempool.
- Solana does not. Because the leader schedule is known ahead of time, RPC nodes forward a transaction straight to the current and next few leaders.
- Leaders can start work on transactions before their slot begins.
- Transactions travel over QUIC; stake-weighted quality of service gives connections from staked validators more room when the leader is busy.
- Consequence: a transaction that is not picked up before its blockhash expires simply disappears. Apps rebroadcast or rebuild; there is no queue to sit in.

Turbine: block propagation
- The leader breaks its block into small pieces called shreds, with erasure coding so missing pieces can be rebuilt.
- Shreds go out through a tree: the leader sends to a first layer of validators, each passes them to its own group, and so on.
- Stake decides position in the tree, higher stake nearer the root.
- Each validator uploads only a little, so a block reaches thousands of nodes quickly.

Validators also repair: they request missing shreds from peers.
