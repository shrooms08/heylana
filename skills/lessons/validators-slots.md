---
id: validators-slots
title: Validators, leaders, slots and epochs
short: validators and slots
track: infrastructure
aliases: validators, validator, leaders, leader schedule, slots, epochs, epoch, slot
chunks: 5
recap: Validators take turns as leader, four slots of 300ms each; an epoch is 432,000 slots, about 36 hours.
checked: 2026-09-20 against solana.com/docs, docs.anza.xyz and SIMD-0525
link: https://docs.anza.xyz/consensus/leader-rotation
link_title: Agave docs: leader rotation
---
Validators are the computers that run Solana: they process transactions, vote on blocks and keep the ledger. Their weight in consensus is the SOL staked to them.

Time is cut into slots:
- A slot is 300 milliseconds as of September 2026, and SIMD-0525 is stepping it down to 200ms in stages: 350ms and 300ms are live. In each slot one validator, the leader, may produce a block. A slot can be skipped if the leader is offline or late.
- Leaders are known in advance: the leader schedule assigns each validator slots in proportion to its stake, four consecutive slots at a time — so a leader's turn is 1.2 seconds.
- Knowing the next leaders is what lets transactions go straight to them instead of waiting in a mempool (see the Turbine and Gulf Stream lesson).

An epoch is 432,000 slots — a fixed count — which at 300ms slots is about 36 hours, and will be about 24 hours once slots reach 200ms. At epoch boundaries:
- the leader schedule for a coming epoch is set from stake,
- staking rewards are paid,
- stake activations and deactivations take effect.

Validators vote on the blocks they see; a block is "confirmed" when two thirds of stake has voted for it, and "finalized" once enough further votes are stacked on top (about 32 slots later).

RPC nodes run the same software but do not vote; they serve apps.
