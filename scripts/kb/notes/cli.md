---
title: The solana, spl-token and anchor command lines, as of 2026-09
url: https://docs.anza.xyz/cli/usage/
source: Agave CLI docs
licence: Apache-2.0 (anza-xyz/agave), summarised by Heylana
---
As of September 2026, with the Agave CLI (the Solana Labs client was archived in January 2025).

solana:
- `solana config get` and `solana config set --url mainnet-beta|devnet|testnet|localhost` — the cluster every other command uses. `-u m`, `-u d`, `-u t`, `-u l` are the short forms.
- `solana balance [ADDRESS]`, `solana address`, `solana airdrop 1 [ADDRESS]` (devnet and testnet only).
- `solana transfer <TO> <AMOUNT> --allow-unfunded-recipient` — prints the signature.
- `solana confirm <SIGNATURE>` (add `-v` for the full transaction). There is no `confirm-transaction` subcommand.
- `solana account <ADDRESS>` — owner, lamports, data length, and the data with `--output json`.
- `solana program deploy <SO_FILE> --program-id <KEYPAIR>`, `solana program show <PROGRAM_ID>`, `solana program extend <PROGRAM_ID> <BYTES>`.
- `solana logs [ADDRESS]` — a live tail of program logs. `solana block-production`, `solana validators`, `solana epoch-info`, `solana slot`, `solana rent <BYTES>`.
- `solana-keygen new -o keypair.json`, `solana-keygen pubkey keypair.json`, `solana-keygen grind --starts-with abc:1`.

spl-token (the `spl-token` CLI, mint amounts are in UI units unless `--raw`):
- `spl-token create-token [--decimals 6] [--program-2022]` — prints the mint address.
- `spl-token create-account <MINT>` — the associated token account for the default keypair; `--owner` for someone else.
- `spl-token mint <MINT> <AMOUNT> [RECIPIENT_ACCOUNT]` — mints. There is no `mint-to` subcommand.
- `spl-token transfer <MINT> <AMOUNT> <RECIPIENT> --fund-recipient --allow-unfunded-recipient`.
- `spl-token balance <MINT>`, `spl-token accounts`, `spl-token supply <MINT>`, `spl-token display <ADDRESS>`.
- `spl-token authorize <ADDRESS> mint|freeze|owner|close <NEW_AUTHORITY>`, `spl-token burn <ACCOUNT> <AMOUNT>`, `spl-token close <MINT>`, `spl-token approve` and `spl-token revoke`, `spl-token wrap <SOL>` and `spl-token unwrap`.

anchor:
- `anchor init <NAME>`, `anchor build` (add `--no-idl` to skip the IDL), `anchor test` (`--skip-local-validator`, `--skip-deploy`), `anchor deploy`, `anchor upgrade`, `anchor idl init|upgrade|fetch`, `anchor keys list` and `anchor keys sync`, `anchor clean`, `anchor run <script>`.
- The cluster and wallet are global options: `--provider.cluster <CLUSTER>` and `--provider.wallet <PATH>`, or the `[provider]` section of Anchor.toml. There is no `--network` flag.
- `avm install <VERSION>` and `avm use <VERSION>` manage Anchor versions; since 0.31 AVM installs prebuilt binaries.
