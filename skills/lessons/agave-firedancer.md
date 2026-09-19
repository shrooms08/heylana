---
id: agave-firedancer
title: Agave and Firedancer
short: Agave and Firedancer
track: infrastructure
aliases: agave, firedancer, frankendancer, validator clients, clients, jito
chunks: 4
recap: Agave (Anza, Rust) and Firedancer (Jump, C) are independent validator clients; running more than one makes Solana harder to take down with one bug.
checked: 2026-09-18 against docs.anza.xyz, firedancer docs and 2026 reports
link: https://docs.anza.xyz/
link_title: Agave docs
---
A validator client is the software a validator runs. Solana now has more than one, which matters: a bug in one client no longer stops the whole network.

Agave
- Maintained by Anza, a company spun out of Solana Labs; written in Rust. It is the original client, renamed from "Solana Labs client" in 2024.
- Jito-Solana is a widely run fork of Agave that adds MEV bundles and block engine support.

Firedancer
- Built by Jump Crypto, written from scratch in C for speed, with each part (networking, verifying, banking, shreds) as a separate process ("tile").
- Frankendancer was the hybrid: Firedancer's networking and block production with Agave's runtime and consensus. It ran on mainnet first.
- Full Firedancer launched on mainnet in December 2025. By mid-2026 reports put it at about 14% of stake, with about 26% more on Frankendancer (unverified: shares change).

Why it matters
- Client diversity: a consensus bug must be in both clients to halt the chain.
- Performance headroom: Firedancer's design targets far higher throughput; the network raises limits gradually as clients keep up.
- For app builders nothing changes: same RPC, same transactions.
