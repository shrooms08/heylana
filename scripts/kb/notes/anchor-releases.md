---
title: What changed between Anchor 0.30 and 0.31, as of 2026-09
url: https://www.anchor-lang.com/docs/updates/release-notes/0-31-0
source: Anchor release notes
licence: Apache-2.0 (otter-sec/anchor), summarised by Heylana
---
As of September 2026. Anchor 0.31.0 was released on 2025-03-08 and was the last release before v1; the current version is v1.2.0 (2026-09-04).

What 0.31 changed from 0.30:

- Custom discriminators. The default is still the first 8 bytes of `SHA256("account:<Name>")`, but a program can override the size and the value with the `discriminator` argument — `#[account(discriminator = 1)]`. This is what lets an Anchor client talk to a program that is not Anchor's. The discriminator type became unsized, which is a breaking change.
- `LazyAccount`, a new experimental account type for when deserialising the whole account is the expensive part. Behind the `lazy-account` feature.
- Stack memory. The code generated for `init` was moved into its own stack frames, which removed a common class of stack-overflow failures during account deserialisation.
- Legacy IDL support. IDLs from before 0.30 are converted automatically by most commands (`idl fetch` is the exception), and there is an `IdlBuilder` trait for building an IDL programmatically.
- Toolchain. Solana v2.1.0 is recommended, with the Agave transition handled automatically for v1.18.19 and up. AVM installs prebuilt binaries instead of building from source. The JS package manager is configurable (npm, yarn or pnpm), there are shell completions, a `--no-idl` flag, and a Mollusk test template.
- Breaking changes to watch for when upgrading: the `EventData` trait is gone, `StateCoder` and legacy state are gone, `Program` deserialisation changed, Solana and SPL crates moved to v2, TypeScript to 5.5.4, and the TS `Program` constructor no longer infers the IDL type — write `new Program<MyProgram>(idl, provider)`.
