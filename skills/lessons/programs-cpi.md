---
id: programs-cpi
title: Programs and CPI
short: programs and CPI
track: build
aliases: programs, smart contracts, cpi, cross program invocation, cross-program invocation
chunks: 5
recap: Programs are stateless code accounts; a CPI lets one program call another, passing accounts and signer rights along.
checked: 2026-09-18 against solana.com/docs/core/programs and /docs/core/cpi
link: https://solana.com/docs/core/cpi
link_title: Solana docs: CPI
---
Programs are Solana's smart contracts: executable accounts holding compiled SBF (Solana BPF) bytecode, usually written in Rust, often with Anchor. They are stateless; they read and write separate data accounts they own.

Deploying uses the upgradeable loader: the program has an upgrade authority that may replace the code, or set it to none to make it immutable.

Native programs ship with the validator: System Program (accounts, SOL transfers), Stake, Vote, Compute Budget. The SPL Token and Associated Token Account programs are on-chain programs everyone uses.

A cross-program invocation (CPI) is one program calling another's instruction during execution. The caller passes the accounts the callee needs.
- Signer and writable privileges extend from caller to callee: if the user signed, the callee sees that signature.
- invoke_signed lets a program sign for a PDA it owns, using the PDA's seeds (see the PDA lesson).
- CPIs can nest. The limit was a depth of 4; SIMD-0268 raises it to 8 once its feature gate is on (activation status unverified).

Everything runs within one transaction's compute budget, so deep CPI chains cost compute units.
