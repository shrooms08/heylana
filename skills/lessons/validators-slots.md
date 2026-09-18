---
id: validators-slots
title: Validators, leaders, slots and epochs
short: validators and slots
track: infrastructure
aliases: validators, validator, leaders, leader schedule, slots, epochs, epoch, slot
chunks: 5
recap: Validators take turns as leader, four slots of about 400ms each; an epoch is 432,000 slots, about two days.
checked: 2026-09-18 against solana.com/docs and docs.anza.xyz
---
Validators are the computers that run Solana: they process transactions, vote on blocks and keep the ledger. Their weight in consensus is the SOL staked to them.

Time is cut into slots:
- A slot is about 400 milliseconds (a target; real averages run a little longer). In each slot one validator, the leader, may produce a block. A slot can be skipped if the leader is offline or late.
- Leaders are known in advance: the leader schedule assigns each validator slots in proportion to its stake, four consecutive slots at a time.
- Knowing the next leaders is what lets transactions go straight to them instead of waiting in a mempool (see the Turbine and Gulf Stream lesson).

An epoch is 432,000 slots, about two to two and a half days. At epoch boundaries:
- the leader schedule for a coming epoch is set from stake,
- staking rewards are paid,
- stake activations and deactivations take effect.

Validators vote on the blocks they see; a block is "confirmed" when two thirds of stake has voted for it, and "finalized" once enough further votes are stacked on top (about 32 slots later).

RPC nodes run the same software but do not vote; they serve apps.
