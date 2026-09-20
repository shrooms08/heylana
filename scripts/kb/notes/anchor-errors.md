---
title: Anchor error codes and what causes them, as of 2026-09
url: https://www.anchor-lang.com/docs/errors
source: Anchor docs
licence: Apache-2.0 (otter-sec/anchor), summarised by Heylana
---
As of September 2026, from Anchor's error enum. The number is what the runtime prints; a program's own errors start at 6000.

- 100 InstructionMissing, 101 InstructionFallbackNotFound, 102 InstructionDidNotDeserialize — 102 is the common one: the arguments sent do not match the instruction's types, usually a stale IDL or a changed argument.
- 1000 IdlInstructionStub, 1001 IdlInstructionInvalidProgram.
- 2000 ConstraintMut — the account was written without `mut`.
- 2001 ConstraintHasOne — a `has_one` check failed: the field stored on the account is not the account that was passed.
- 2002 ConstraintSigner — an account that had to sign did not.
- 2003 ConstraintRaw — a `constraint = ...` expression was false.
- 2004 ConstraintOwner — the account is owned by a different program than the constraint says.
- 2005 ConstraintRentExempt, 2006 ConstraintSeeds — 2006 means the address passed is not what the seeds and bump derive; the usual cause is a seed in the wrong order, a missing seed, or the wrong bump.
- 2007 ConstraintExecutable, 2009 ConstraintAssociated, 2012 ConstraintAddress.
- 2015 ConstraintTokenOwner, 2016 ConstraintMintMintAuthority, 2017 ConstraintMintFreezeAuthority, 2018 ConstraintMintDecimals, 2019 ConstraintSpace — 2019 means the space declared does not match what the account needs, remembering the 8-byte discriminator.
- 2021 ConstraintTokenTokenProgram, 2022 ConstraintMintTokenProgram — passing the Token program where Token-2022 is wanted, or the other way round.
- 3001 AccountDiscriminatorMismatch — the account is of a different type than the struct expects.
- 3002 AccountDidNotDeserialize, 3003 AccountDidNotSerialize.
- 3007 AccountOwnedByWrongProgram — the account belongs to another program; usually the wrong address, or an account that was never initialised by this program.
- 3012 AccountNotInitialized — the account does not exist yet. Create it first (`init`, or `init_if_needed`), or check the address.
- 3014 AccountSysvarMismatch, 3015 AccountReallocExceedsLimit, 3016 AccountDuplicateReallocs.
- 6000 and up — the program's own `#[error_code]` enum, in declaration order. "custom program error: 0x1770" is 6000 in hex, so it is that program's first error, not an Anchor one.
