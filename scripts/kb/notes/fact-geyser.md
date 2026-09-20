---
title: What is a Geyser plugin for? (as of 2026-09)
url: https://docs.anza.xyz/validator/geyser
source: Anza docs
licence: Apache-2.0 (anza-xyz/agave), summarised by Heylana
---
A Geyser plugin is a shared library an Agave validator loads with --geyser-plugin-config so it can stream what it sees straight out of the validator — account writes, slot status, transactions, blocks and entries — into a database, a queue or a websocket feed. It is how indexers and RPC providers keep up without polling getProgramAccounts. It has nothing to do with Minecraft: on Solana, Geyser is the validator's plugin interface, defined by the agave-geyser-plugin-interface crate.
