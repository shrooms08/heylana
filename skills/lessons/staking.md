---
id: staking
title: Stake, delegation, rewards and stake pools
short: staking
track: infrastructure
aliases: staking, stake, delegation, delegate, rewards, stake pools, liquid staking, lst
chunks: 5
recap: You delegate SOL from a stake account to a validator; it activates at an epoch boundary and earns rewards each epoch, or you use a stake pool for a liquid token.
checked: 2026-09-18 against solana.com/docs and the Stake program docs
---
Staking secures Solana: stake decides a validator's weight in votes and leader slots.

Native staking
- You create a stake account (owned by the Stake program) and delegate it to a validator's vote account. The SOL stays yours; the validator cannot spend it.
- Two authorities control it: the stake authority (delegate, deactivate) and the withdraw authority (take the SOL out).
- Stake activates at the next epoch boundary and deactivates the same way; network-wide changes are rate-limited per epoch, so large moves can take a few epochs (warmup and cooldown).

Rewards
- Paid each epoch from inflation (started at 8%, falling 15% a year toward 1.5%) plus a share of fees and, with Jito, MEV tips.
- The validator keeps its commission; the rest compounds into your stake.
- Poor validators (offline, skipping votes) earn less. Solana has no automatic slashing today (unverified: proposals exist).

Stake pools and liquid staking
- A pool takes many deposits, spreads them across validators, and gives you a liquid staking token (LST) such as JitoSOL, mSOL or a bank's own, usable in DeFi while it earns.
- The SPL Stake Pool program and Sanctum's infrastructure power most of them.
- Risks: the LST's smart contracts and its price against SOL on markets.
