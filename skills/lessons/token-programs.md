---
id: token-programs
title: The Token program and Token-2022
short: Token-2022
track: infrastructure
aliases: token program, token-2022, token 2022, token extensions, spl token
chunks: 4
recap: The original Token program handles plain tokens; Token-2022 is a separate program with optional extensions like transfer fees, hooks and metadata.
checked: 2026-09-18 against spl.solana.com/token-2022 and solana.com/docs/tokens/extensions
link: https://solana.com/docs/tokens/extensions
link_title: Solana docs: token extensions
---
Two programs mint and move tokens on Solana.

Token program (Tokenkeg...)
- The original. Mints, token accounts, transfers, approvals (delegates), burns, freeze. Most tokens, USDC included, live here.
- Simple, fixed account layout (a token account is 165 bytes), audited and everywhere.

Token-2022 (Tokenz...), also called Token Extensions
- A separate program with the same core instructions plus optional extensions, chosen when a mint or account is created:
  - transfer fees taken on every transfer,
  - transfer hooks that call your program on each transfer,
  - confidential transfers (encrypted amounts),
  - interest-bearing display, non-transferable (soulbound) tokens,
  - permanent delegate, default account state (frozen until approved),
  - metadata pointer and on-mint metadata, memo required on incoming transfers, and more.
- Accounts are larger and vary with extensions, so rent varies.

For builders
- Always check which program owns a mint and pass that program, and derive the ATA with it: the same wallet and mint give a different ATA under Token-2022.
- Not every wallet or exchange supports every extension; check before relying on one.
- transferChecked is required for some extensions and is the safer habit anyway.
