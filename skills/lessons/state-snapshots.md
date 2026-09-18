---
id: state-snapshots
title: State, the accounts database and snapshots
short: accounts DB and snapshots
track: infrastructure
aliases: state, accounts db, accountsdb, snapshots, snapshot, ledger
chunks: 4
recap: Validators keep current state in AccountsDB and history in the ledger; snapshots let a new node start from recent state instead of replaying from genesis.
checked: 2026-09-18 against docs.anza.xyz
---
A validator stores two different things.

Current state: AccountsDB
- Every account's latest lamports, owner and data. This is what programs read and write.
- Updates are appended and older versions cleaned up; indexes let the node find an account by address, and optionally by owner or mint (secondary indexes, which RPC nodes need for getProgramAccounts).
- The state is large (hundreds of gigabytes and growing), which is why validators need lots of fast NVMe storage and RAM.

History: the ledger
- The blocks and transactions themselves, in a local store (Blockstore).
- Validators keep only recent history and prune older slots. Full history lives in archival services such as Google Bigtable-backed RPC or community archives.

Snapshots
- A snapshot is the full AccountsDB at a slot, compressed. Incremental snapshots hold only changes since the last full one.
- A new validator downloads a recent snapshot from a peer, loads it, and replays only the few blocks after it, so it catches up in hours, not weeks.
- The snapshot's bank hash lets it check the state it loaded.

Rent and closing unused accounts matter here: every live account is state that every validator must hold.
