---
id: rpc
title: RPC, providers, websockets and Geyser
short: RPC
track: infrastructure
aliases: rpc, rpc nodes, helius, quicknode, websockets, geyser, grpc
chunks: 5
recap: Apps talk to Solana through RPC nodes: JSON-RPC for reads and sends, websockets for subscriptions, and Geyser plugins for streaming at scale.
checked: 2026-09-18 against solana.com/docs/rpc
---
An RPC node runs validator software without voting, and answers apps.

JSON-RPC over HTTP
- Reads: getBalance, getAccountInfo, getTokenAccountsByOwner, getProgramAccounts, getTransaction, getSignaturesForAddress.
- Writes: sendTransaction forwards a signed transaction to the leaders; simulateTransaction dry-runs it.
- Commitment: processed (seen), confirmed (two thirds voted), finalized (rooted). Pick per request.

Websockets
- Subscriptions push changes: accountSubscribe, programSubscribe, logsSubscribe, signatureSubscribe, slotSubscribe.
- Handy for apps; they can drop under load, so reconnect and backfill.

Geyser
- A plugin interface inside the validator that streams account updates, transactions and slots as they happen.
- Providers expose it as gRPC streams (often called Yellowstone gRPC) for indexers, bots and analytics.

Providers
- Public endpoints (api.mainnet-beta.solana.com, api.devnet.solana.com) are rate-limited and not for production.
- Helius, QuickNode, Triton and others run fleets of RPC nodes with keys, higher limits, enhanced APIs (parsed transactions, DAS for NFTs, priority fee estimates, webhooks) and staked connections for better landing.
- Keep the key server-side: an RPC URL with a key in it is a secret, which is why Heylana's lives on its worker.
