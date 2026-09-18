---
id: rent-fees
title: Rent and fees
short: rent and fees
track: build
aliases: rent, fees, rent exempt, rent-exempt, transaction fees
chunks: 4
recap: Accounts hold a refundable rent-exempt deposit sized to their data; transactions pay a 5000-lamport base fee per signature, plus optional priority fees.
checked: 2026-09-18 against solana.com/docs/core/fees
---
Two different costs.

Rent: storage deposit
- Every account must hold a minimum balance to exist: rent-exempt, proportional to data size. It is roughly two years of rent up front.
- Formula today: (128 + data bytes) x 3,480 lamports x 2. An empty account needs 890,880 lamports (about 0.00089 SOL); a 165-byte token account needs 2,039,280 lamports (about 0.002 SOL).
- It is a deposit, not a charge: closing the account returns the lamports to whoever you choose.
- New accounts below the minimum are refused, so "rent collection" no longer happens in practice.

Transaction fees
- Base fee: 5,000 lamports per signature, paid by the fee payer.
- Half the base fee is burned, half goes to the leader who processed the transaction.
- Priority fee: optional extra, set as micro-lamports per compute unit you request, going to the validator (see the fees and compute lesson).
- Fees are charged even if the transaction fails after it is processed.

Why Heylana says "fee 0.002 SOL" when sending USDC to someone new: that is the rent for opening their token account, which the sender pays.
