---
title: What is a snapshot on a Solana validator? (as of 2026-09)
url: https://docs.anza.xyz/operations/setup-a-validator
source: Anza docs
licence: Apache-2.0 (anza-xyz/agave), summarised by Heylana
---
A snapshot is a compressed copy of the whole accounts database at one slot. A validator that is starting up, or that has fallen too far behind to catch up by replaying, downloads a snapshot from a peer and begins from that slot instead of replaying the chain from genesis. Validators take full snapshots periodically with incremental ones in between. This is a different thing from the token-holder snapshot an airdrop takes; on a validator, a snapshot is the state it boots from.
