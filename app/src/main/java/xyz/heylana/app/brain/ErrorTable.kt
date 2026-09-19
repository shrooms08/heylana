package xyz.heylana.app.brain

/**
 * Explaining an error, the built-in way first. When the user's words (or, when they ask
 * about an error, the screen) carry an Anchor error, a Solana program or transaction error,
 * or a Mobile Wallet Adapter or Seed Vault error, this table says what causes it, the usual
 * fix and one link — on the phone, with no model call. Only an error it does not know goes
 * to the model, with the knowledge base ([HeylanaPrompt.errorMessage]).
 *
 * Every code and message here was copied from its source on 2026-09-19:
 *  - Anchor: otter-sec/anchor v1.2.0, lang/error/src/lib.rs (the same as master);
 *  - SPL Token: solana-program/token, interface/src/error.rs;
 *  - System program: anza-xyz/solana-sdk, system-interface/src/error.rs;
 *  - instruction and transaction errors: anza-xyz/solana-sdk, instruction-error and
 *    transaction-error (their Display text, which is what logs and wallets print);
 *  - Mobile Wallet Adapter: ProtocolContract.java and the JS protocol's errors.ts;
 *  - Seed Vault: seed-vault-sdk, WalletContractV1.java.
 * The causes and fixes are Heylana's own words.
 */
object ErrorTable {

    /** What a known error means. [code] is how it was named ("3003", "0x1", "-3"), when it has one. */
    data class Known(val name: String, val code: String?, val cause: String, val fix: String, val link: String)

    /** The line Heylana says: the error, its cause, the usual fix, and where to read more. The link is [source]'s chip. */
    fun line(known: Known): String =
        "${known.name}${known.code?.let { " ($it)" } ?: ""}: ${known.cause} Usual fix: ${known.fix} " +
            "${Sources.spokenName(known.link).replaceFirstChar { it.uppercase() }} have more."

    /** The chip under that line: the error's doc page. */
    fun source(known: Known): Source = Sources.forLink(known.link)

    // ------------------------------------------------------------------ Anchor

    private data class Anchor(val code: Int, val name: String, val message: String)

    private const val ANCHOR_ERRORS = "https://www.anchor-lang.com/docs/features/errors"
    private const val ANCHOR_CONSTRAINTS = "https://www.anchor-lang.com/docs/references/account-constraints"

    /** Anchor's framework errors, v1.2.0: code, name, message, as the source has them. */
    private val ANCHOR = listOf(
        Anchor(100, "InstructionMissing", "Instruction discriminator not provided"),
        Anchor(101, "InstructionFallbackNotFound", "Fallback functions are not supported"),
        Anchor(102, "InstructionDidNotDeserialize", "The program could not deserialize the given instruction"),
        Anchor(103, "InstructionDidNotSerialize", "The program could not serialize the given instruction"),
        Anchor(1000, "IdlInstructionStub", "The program was compiled without idl instructions"),
        Anchor(1001, "IdlInstructionInvalidProgram", "Invalid program given to the IDL instruction"),
        Anchor(1002, "IdlAccountNotEmpty", "IDL account must be empty in order to resize, try closing first"),
        Anchor(1500, "EventInstructionStub", "The program was compiled without `event-cpi` feature"),
        Anchor(2000, "ConstraintMut", "A mut constraint was violated"),
        Anchor(2001, "ConstraintHasOne", "A has one constraint was violated"),
        Anchor(2002, "ConstraintSigner", "A signer constraint was violated"),
        Anchor(2003, "ConstraintRaw", "A raw constraint was violated"),
        Anchor(2004, "ConstraintOwner", "An owner constraint was violated"),
        Anchor(2005, "ConstraintRentExempt", "A rent exemption constraint was violated"),
        Anchor(2006, "ConstraintSeeds", "A seeds constraint was violated"),
        Anchor(2007, "ConstraintExecutable", "An executable constraint was violated"),
        Anchor(2008, "ConstraintState", "Deprecated Error, feel free to replace with something else"),
        Anchor(2009, "ConstraintAssociated", "An associated constraint was violated"),
        Anchor(2010, "ConstraintAssociatedInit", "An associated init constraint was violated"),
        Anchor(2011, "ConstraintClose", "A close constraint was violated"),
        Anchor(2012, "ConstraintAddress", "An address constraint was violated"),
        Anchor(2013, "ConstraintZero", "Expected zero account discriminant"),
        Anchor(2014, "ConstraintTokenMint", "A token mint constraint was violated"),
        Anchor(2015, "ConstraintTokenOwner", "A token owner constraint was violated"),
        Anchor(2016, "ConstraintMintMintAuthority", "A mint mint authority constraint was violated"),
        Anchor(2017, "ConstraintMintFreezeAuthority", "A mint freeze authority constraint was violated"),
        Anchor(2018, "ConstraintMintDecimals", "A mint decimals constraint was violated"),
        Anchor(2019, "ConstraintSpace", "A space constraint was violated"),
        Anchor(2020, "ConstraintAccountIsNone", "A required account for the constraint is None"),
        Anchor(2021, "ConstraintTokenTokenProgram", "A token account token program constraint was violated"),
        Anchor(2022, "ConstraintMintTokenProgram", "A mint token program constraint was violated"),
        Anchor(2023, "ConstraintAssociatedTokenTokenProgram", "An associated token account token program constraint was violated"),
        Anchor(2024, "ConstraintMintGroupPointerExtension", "A group pointer extension constraint was violated"),
        Anchor(2025, "ConstraintMintGroupPointerExtensionAuthority", "A group pointer extension authority constraint was violated"),
        Anchor(2026, "ConstraintMintGroupPointerExtensionGroupAddress", "A group pointer extension group address constraint was violated"),
        Anchor(2027, "ConstraintMintGroupMemberPointerExtension", "A group member pointer extension constraint was violated"),
        Anchor(2028, "ConstraintMintGroupMemberPointerExtensionAuthority", "A group member pointer extension authority constraint was violated"),
        Anchor(2029, "ConstraintMintGroupMemberPointerExtensionMemberAddress", "A group member pointer extension group address constraint was violated"),
        Anchor(2030, "ConstraintMintMetadataPointerExtension", "A metadata pointer extension constraint was violated"),
        Anchor(2031, "ConstraintMintMetadataPointerExtensionAuthority", "A metadata pointer extension authority constraint was violated"),
        Anchor(2032, "ConstraintMintMetadataPointerExtensionMetadataAddress", "A metadata pointer extension metadata address constraint was violated"),
        Anchor(2033, "ConstraintMintCloseAuthorityExtension", "A close authority constraint was violated"),
        Anchor(2034, "ConstraintMintCloseAuthorityExtensionAuthority", "A close authority extension authority constraint was violated"),
        Anchor(2035, "ConstraintMintPermanentDelegateExtension", "A permanent delegate extension constraint was violated"),
        Anchor(2036, "ConstraintMintPermanentDelegateExtensionDelegate", "A permanent delegate extension delegate constraint was violated"),
        Anchor(2037, "ConstraintMintTransferHookExtension", "A transfer hook extension constraint was violated"),
        Anchor(2038, "ConstraintMintTransferHookExtensionAuthority", "A transfer hook extension authority constraint was violated"),
        Anchor(2039, "ConstraintMintTransferHookExtensionProgramId", "A transfer hook extension transfer hook program id constraint was violated"),
        Anchor(2040, "ConstraintDuplicateMutableAccount", "A duplicate mutable account constraint was violated"),
        Anchor(2041, "AccountAlreadyMigrated", "Account is already migrated"),
        Anchor(2042, "AccountNotMigrated", "Account must be migrated before exiting"),
        Anchor(2043, "ConstraintMintPausableExtension", "A pausable extension constraint was violated"),
        Anchor(2044, "ConstraintMintPausableAuthority", "A pausable extension authority constraint was violated"),
        Anchor(2500, "RequireViolated", "A require expression was violated"),
        Anchor(2501, "RequireEqViolated", "A require_eq expression was violated"),
        Anchor(2502, "RequireKeysEqViolated", "A require_keys_eq expression was violated"),
        Anchor(2503, "RequireNeqViolated", "A require_neq expression was violated"),
        Anchor(2504, "RequireKeysNeqViolated", "A require_keys_neq expression was violated"),
        Anchor(2505, "RequireGtViolated", "A require_gt expression was violated"),
        Anchor(2506, "RequireGteViolated", "A require_gte expression was violated"),
        Anchor(3000, "AccountDiscriminatorAlreadySet", "The account discriminator was already set on this account"),
        Anchor(3001, "AccountDiscriminatorNotFound", "No discriminator was found on the account"),
        Anchor(3002, "AccountDiscriminatorMismatch", "Account discriminator did not match what was expected"),
        Anchor(3003, "AccountDidNotDeserialize", "Failed to deserialize the account"),
        Anchor(3004, "AccountDidNotSerialize", "Failed to serialize the account"),
        Anchor(3005, "AccountNotEnoughKeys", "Not enough account keys given to the instruction"),
        Anchor(3006, "AccountNotMutable", "The given account is not mutable"),
        Anchor(3007, "AccountOwnedByWrongProgram", "The given account is owned by a different program than expected"),
        Anchor(3008, "InvalidProgramId", "Program ID was not as expected"),
        Anchor(3009, "InvalidProgramExecutable", "Program account is not executable"),
        Anchor(3010, "AccountNotSigner", "The given account did not sign"),
        Anchor(3011, "AccountNotSystemOwned", "The given account is not owned by the system program"),
        Anchor(3012, "AccountNotInitialized", "The program expected this account to be already initialized"),
        Anchor(3013, "AccountNotProgramData", "The given account is not a program data account"),
        Anchor(3014, "AccountNotAssociatedTokenAccount", "The given account is not the associated token account"),
        Anchor(3015, "AccountSysvarMismatch", "The given public key does not match the required sysvar"),
        Anchor(3016, "AccountReallocExceedsLimit", "The account reallocation exceeds the MAX_PERMITTED_DATA_INCREASE limit"),
        Anchor(3017, "AccountDuplicateReallocs", "The account was duplicated for more than one reallocation"),
        Anchor(4100, "DeclaredProgramIdMismatch", "The declared program id does not match the actual program id"),
        Anchor(4101, "TryingToInitPayerAsProgramAccount", "You cannot/should not initialize the payer account as a program account"),
        Anchor(4102, "InvalidNumericConversion", "Error during numeric conversion"),
        Anchor(5000, "Deprecated", "The API being used is deprecated and should no longer be used"),
    )

    /** The usual cause and fix for the Anchor errors people actually hit. */
    private val ANCHOR_FIX = mapOf(
        "InstructionMissing" to ("The instruction data is empty or too short for Anchor's 8-byte discriminator." to
            "Call the instruction through the program's IDL client rather than building the data by hand."),
        "InstructionFallbackNotFound" to ("No instruction in the program matches the discriminator sent." to
            "The client's IDL is out of date or points at another program: rebuild, redeploy and regenerate the client."),
        "InstructionDidNotDeserialize" to ("The instruction's arguments don't match what the program expects." to
            "Regenerate the IDL and client after changing arguments, and check their types and order."),
        "ConstraintMut" to ("An account marked mut was passed as read-only." to "Pass it as writable; the IDL client does this for you."),
        "ConstraintHasOne" to ("A has_one check failed: the field stored on the account is not the account that was passed." to
            "Pass the account the stored field points to."),
        "ConstraintSigner" to ("An account that must sign did not." to "Add it as a signer of the transaction."),
        "ConstraintRaw" to ("A constraint = … expression was false." to "Check the expression and the accounts passed; the log names the account."),
        "ConstraintOwner" to ("The account belongs to a different program than owner = expects." to "Pass the account owned by the expected program."),
        "ConstraintSeeds" to ("The PDA passed doesn't match the seeds and bump in #[account(seeds = …)]." to
            "Derive it on the client with the same seeds, in the same order and encoding, and the same program id."),
        "ConstraintAddress" to ("An address = check failed: the account passed isn't the expected one." to "Pass the exact address the constraint names."),
        "ConstraintTokenMint" to ("The token account is for a different mint than token::mint expects." to "Pass the token account for that mint."),
        "ConstraintTokenOwner" to ("The token account's owner isn't the authority token::authority expects." to "Pass the token account owned by that authority."),
        "ConstraintSpace" to ("The account's size doesn't match space =." to "Allocate the space the struct needs: 8 bytes of discriminator plus its fields."),
        "RequireViolated" to ("A require! check in the program failed." to "The log shows what it compared; fix the input that fails it."),
        "RequireEqViolated" to ("A require_eq! check found two values unequal." to "The log prints both values; fix the input."),
        "RequireKeysEqViolated" to ("A require_keys_eq! check found two addresses unequal." to "The log prints both; pass the address the program expects."),
        "AccountDiscriminatorNotFound" to ("The account has no Anchor discriminator: it is empty, or wasn't created by this program." to
            "Initialise it with this program first, or pass the right account."),
        "AccountDiscriminatorMismatch" to ("The account is a different type than the instruction expects." to
            "Pass the right account; if the struct was renamed, its discriminator changed too."),
        "AccountDidNotDeserialize" to ("The account's data doesn't fit the struct the program reads it as." to
            "Usually the struct changed after the account was created (add new fields at the end and realloc, or migrate), " +
                "the wrong account was passed, or the space was too small."),
        "AccountDidNotSerialize" to ("The data being written is bigger than the account." to "Raise space =, or realloc the account."),
        "AccountNotEnoughKeys" to ("Fewer accounts were passed than the instruction's Accounts struct lists." to "Pass all of them, in order."),
        "AccountNotMutable" to ("The account must be writable but was passed read-only." to "Pass it as writable."),
        "AccountOwnedByWrongProgram" to ("The account belongs to another program." to
            "Check the address and the cluster, and Token against Token-2022 for token accounts."),
        "InvalidProgramId" to ("The program account passed isn't the program expected." to "Pass the right program id; Token and Token-2022 are different programs."),
        "AccountNotSigner" to ("An account that must sign did not." to "Add it as a signer; a PDA signs only through invoke_signed with its seeds."),
        "AccountNotInitialized" to ("The account doesn't exist yet." to "Create it first (init, or init_if_needed), or check the address."),
        "AccountNotAssociatedTokenAccount" to ("The token account passed isn't the associated token account for that wallet and mint." to
            "Derive the ATA from the wallet, the mint and the right token program."),
        "DeclaredProgramIdMismatch" to ("declare_id! doesn't match the program id it is deployed at." to "Run anchor keys sync, then rebuild and redeploy."),
        "TryingToInitPayerAsProgramAccount" to ("The payer is also the account being initialised." to "Use a separate payer account."),
    )

    private fun anchorKnown(a: Anchor): Known {
        val (cause, fix) = ANCHOR_FIX[a.name] ?: when (a.code) {
            in 100..199 -> "${a.message}." to "Regenerate the client from the program's current IDL."
            in 1000..1999 -> "${a.message}." to "Check the IDL instruction and program being used."
            in 2000..2499 -> "${a.message}: an #[account(…)] constraint failed; the log names the account." to
                "Check the account passed against that constraint."
            in 2500..2999 -> "${a.message}: a check in the program failed; the log shows its values." to "Fix the input that fails it."
            in 3000..3999 -> "${a.message}." to "Check the account passed: its address, its owner, and that it is initialised."
            else -> "${a.message}." to "See Anchor's error reference."
        }
        val link = if (a.code in 2000..2499) ANCHOR_CONSTRAINTS else ANCHOR_ERRORS
        return Known(a.name, a.code.toString(), cause, fix, link)
    }

    /** An error number from a program built with Anchor: its own errors start at 6000. */
    private fun customAnchor(code: Int): Known = Known(
        "Custom program error", code.toString(),
        "The program's own error number ${code - CUSTOM_START} in its #[error_code] enum (Anchor numbers them from 6000).",
        "Look it up in the program's IDL (its errors list) or source to see its name and message.",
        ANCHOR_ERRORS
    )

    private const val CUSTOM_START = 6000

    // ------------------------------------------------------------ Token and System

    private const val TOKEN_LINK = "https://solana.com/docs/tokens"
    private const val SYSTEM_LINK = "https://solana.com/docs/core/accounts"

    /** SPL Token's errors by number: name, cause and fix. */
    private val TOKEN = listOf(
        Triple("NotRentExempt", "Lamport balance below rent-exempt threshold.", "Fund the account to the rent-exempt minimum."),
        Triple("InsufficientFunds", "The source token account has fewer tokens than the transfer.", "Lower the amount or top the account up."),
        Triple("InvalidMint", "The mint account isn't a valid mint.", "Pass the mint's address."),
        Triple("MintMismatch", "The token account belongs to a different mint.", "Pass the token account for that mint."),
        Triple("OwnerMismatch", "The signer isn't the token account's owner or delegate.", "Sign with the owner, or approve a delegate first."),
        Triple("FixedSupply", "The mint has no mint authority, so no more can be minted.", "Nothing to fix: the supply is fixed."),
        Triple("AlreadyInUse", "The account is already initialised.", "Use it as it is, or create a new one."),
        Triple("InvalidNumberOfProvidedSigners", "The multisig got the wrong number of signers.", "Pass the signers the multisig needs."),
        Triple("InvalidNumberOfRequiredSigners", "The multisig's required signer count is invalid.", "Set m between 1 and the number of signers."),
        Triple("UninitializedState", "The token account or mint isn't initialised.", "Create and initialise it first."),
        Triple("NativeNotSupported", "The instruction doesn't work on wrapped SOL.", "Unwrap first, or use a SOL transfer."),
        Triple("NonNativeHasBalance", "A token account can only be closed when its balance is zero.", "Transfer or burn the tokens, then close it."),
        Triple("InvalidInstruction", "The Token program couldn't read the instruction.", "Build it with the token library for that program."),
        Triple("InvalidState", "The account's state doesn't allow this operation.", "Check it isn't frozen or closed."),
        Triple("Overflow", "An amount overflowed.", "Use a smaller amount."),
        Triple("AuthorityTypeNotSupported", "That authority type doesn't apply to this account.", "Use the authority type for a mint or a token account."),
        Triple("MintCannotFreeze", "The mint has no freeze authority.", "Nothing to fix: it can't freeze accounts."),
        Triple("AccountFrozen", "The token account is frozen.", "Ask the mint's freeze authority to thaw it."),
        Triple("MintDecimalsMismatch", "transferChecked was given decimals that differ from the mint's.", "Pass the mint's own decimals."),
        Triple("NonNativeNotSupported", "The instruction works only on wrapped SOL.", "Use it with a wrapped SOL account."),
    )

    /** The System program's errors by number: name, message (as the SDK prints it), fix. */
    private val SYSTEM = listOf(
        Triple("AccountAlreadyInUse", "An account with the same address already exists.", "Use init_if_needed, or a different address or seed."),
        Triple("ResultWithNegativeLamports", "The account does not have enough SOL to perform the operation.", "Fund the payer."),
        Triple("InvalidProgramId", "Cannot assign the account to this program id.", "Check the owner being assigned."),
        Triple("InvalidAccountDataLength", "Cannot allocate account data of this length.", "Allocate at most 10 MiB, and no more than the instruction allows."),
        Triple("MaxSeedLengthExceeded", "The requested seed is too long.", "Keep each seed to 32 bytes."),
        Triple("AddressWithSeedMismatch", "The address doesn't match the one derived from the seed.", "Derive it with the same base, seed and owner."),
        Triple("NonceNoRecentBlockhashes", "Advancing the nonce needs the RecentBlockhashes sysvar.", "Pass the sysvar account."),
        Triple("NonceBlockhashNotExpired", "The stored nonce is still recent.", "Wait for a new blockhash before advancing it."),
        Triple("NonceUnexpectedBlockhashValue", "The nonce given doesn't match the stored one.", "Read the nonce account again and use its value."),
    )

    private val TOKEN_PROGRAMS = Regex("Tokenkeg|TokenzQd|token program", RegexOption.IGNORE_CASE)
    private val SYSTEM_PROGRAM = Regex("\\b11111111111111111111111111111111\\b|system program", RegexOption.IGNORE_CASE)

    // --------------------------------------------------- instruction and transaction

    private const val TRANSACTIONS = "https://solana.com/docs/core/transactions"
    private const val FEES = "https://solana.com/docs/core/fees"
    private const val COMPUTE = "https://solana.com/docs/core/fees/compute-budget"
    private const val CPI = "https://solana.com/docs/core/cpi"

    /** Errors known by the words the runtime prints. Matched case-free, longest first. */
    private val PRINTED = listOf(
        Known("Insufficient funds for instruction", null, "An account didn't have enough SOL or tokens for what the instruction moves.",
            "Fund it, or lower the amount.", TRANSACTIONS),
        Known("Missing required signature", null, "An account that must sign the instruction did not.",
            "Add it as a signer; a PDA signs only through invoke_signed with its seeds.", CPI),
        Known("Invalid account data for instruction", null, "An account's data isn't what the program expects: the wrong account, or one not yet initialised.",
            "Check each account's address and that it is initialised.", "https://solana.com/docs/core/accounts"),
        Known("Incorrect program id for instruction", null, "The wrong program account was passed.",
            "Pass the right program id; Token and Token-2022 are different programs.", TRANSACTIONS),
        Known("Instruction requires an uninitialized account", null, "The account is already initialised.",
            "Skip creating it, or use init_if_needed.", "https://solana.com/docs/core/accounts"),
        Known("Instruction requires an initialized account", null, "The account hasn't been initialised.",
            "Create and initialise it first.", "https://solana.com/docs/core/accounts"),
        Known("Computational budget exceeded", null, "The transaction ran out of compute units.",
            "Raise the limit with SetComputeUnitLimit (up to 1.4 million), or do less per transaction.", COMPUTE),
        Known("Exceeded CUs meter", null, "The program ran out of compute units.",
            "Raise the limit with SetComputeUnitLimit (up to 1.4 million), or do less per transaction.", COMPUTE),
        Known("Program failed to complete", null, "The program aborted: a panic, or a stack or heap limit.",
            "Read the program log lines just above it for the reason.", "https://solana.com/docs/core/programs"),
        Known("Cross-program invocation with unauthorized signer or writable account", null,
            "A CPI passed an account as signer or writable that the caller didn't have that way.",
            "Pass it as signer or writable in the outer instruction; for a PDA, sign with invoke_signed and its seeds.", CPI),
        Known("Blockhash not found", null, "The transaction's recent blockhash expired (after about 150 blocks) or is from another cluster.",
            "Fetch a fresh blockhash and send again.", TRANSACTIONS),
        Known("Attempt to debit an account but found no record of a prior credit", null,
            "The fee payer has never held SOL on this cluster, often a devnet and mainnet mix-up.",
            "Fund the fee payer on this cluster (an airdrop on devnet).", FEES),
        Known("Insufficient funds for fee", null, "The fee payer has too little SOL for the fee.", "Add SOL to the fee payer.", FEES),
        Known("Insufficient funds for rent", null, "An account would be left below the rent-exempt minimum.",
            "Add SOL; getMinimumBalanceForRentExemption gives the amount.", "https://solana.com/docs/core/accounts"),
        Known("This transaction has already been processed", null, "The same signed transaction was sent twice; the first one landed.",
            "Look up its signature instead of sending again.", TRANSACTIONS),
        Known("Attempt to load a program that does not exist", null, "The program id isn't deployed on this cluster.",
            "Check the program id and the cluster.", "https://solana.com/docs/core/programs"),
        Known("Transaction would exceed max Block Cost Limit", null, "The block is full.", "Retry, with a priority fee.", FEES),
        Known("Transaction would exceed max account limit within the block", null,
            "Too many transactions are writing the same account in this block.", "Retry with a priority fee.", FEES),
        Known("Transaction too large", null, "The transaction is over 1,232 bytes.",
            "Use an address lookup table, or split it into two transactions.", TRANSACTIONS),
    )

    // --------------------------------------------------------- MWA and Seed Vault

    private const val MWA_SPEC = "https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec.html"
    private const val SEED_VAULT = "https://docs.solanamobile.com/developers/seed-vault"

    private val NAMED = listOf(
        Known("ERROR_AUTHORIZATION_FAILED", "-1", "The wallet refused authorization: the user declined, or the auth token is no longer valid.",
            "Ask to authorize again, without the old auth token.", MWA_SPEC),
        Known("ERROR_INVALID_PAYLOADS", "-2", "The wallet couldn't read one of the transactions or messages.", "Check they serialise correctly.", MWA_SPEC),
        Known("ERROR_NOT_SIGNED", "-3", "The user declined to sign.", "Nothing to fix; ask again if they meant to.", MWA_SPEC),
        Known("ERROR_NOT_SUBMITTED", "-4", "The wallet signed but couldn't send the transaction.",
            "Check the chain for it before sending again, so it isn't sent twice.", MWA_SPEC),
        Known("ERROR_NOT_CLONED", "-5", "The wallet couldn't clone the authorization.", "Authorize again.", MWA_SPEC),
        Known("ERROR_TOO_MANY_PAYLOADS", "-6", "More transactions than the wallet takes in one request.",
            "Send fewer at once; get_capabilities says the wallet's maximum.", MWA_SPEC),
        Known("ERROR_CLUSTER_NOT_SUPPORTED", "-7", "The wallet doesn't support the cluster asked for.", "Ask for a cluster it supports.", MWA_SPEC),
        Known("ERROR_ATTEST_ORIGIN_ANDROID", "-100", "The wallet wants the app's Android origin attested.", "See the spec's attestation section.", MWA_SPEC),
        Known("ERROR_WALLET_NOT_FOUND", null, "No Mobile Wallet Adapter wallet is installed on the device.", "Install one, like the Seed Vault Wallet.", MWA_SPEC),
        Known("ERROR_SESSION_TIMEOUT", null, "The wallet didn't answer in time.", "Try again with the wallet open.", MWA_SPEC),
        Known("ERROR_SESSION_CLOSED", null, "The wallet session closed before it finished.", "Start the request again.", MWA_SPEC),
        Known("ERROR_ASSOCIATION_CANCELLED", null, "The user closed the wallet before it connected.", "Start the request again.", MWA_SPEC),
        Known("RESULT_AUTHENTICATION_FAILED", null, "Seed Vault couldn't confirm the user: fingerprint or PIN.", "Try again.", SEED_VAULT),
        Known("RESULT_INVALID_AUTH_TOKEN", null, "The app's authorization for that seed is no longer valid.", "Authorize the seed again.", SEED_VAULT),
        Known("RESULT_NO_AVAILABLE_SEEDS", null, "No seed is available to authorize.", "Set up a seed in Seed Vault first.", SEED_VAULT),
        Known("RESULT_INVALID_PAYLOAD", null, "Seed Vault couldn't read the transaction to sign.", "Check it serialises correctly.", SEED_VAULT),
        Known("RESULT_INVALID_TRANSACTION", null, "Seed Vault couldn't read the transaction to sign.", "Check it serialises correctly.", SEED_VAULT),
        Known("RESULT_INVALID_DERIVATION_PATH", null, "The key's derivation path isn't valid for Seed Vault.", "Use a BIP-44 path Seed Vault accepts.", SEED_VAULT),
        Known("RESULT_IMPLEMENTATION_LIMIT_EXCEEDED", null, "More requests than Seed Vault takes at once.", "Send fewer at once.", SEED_VAULT),
        Known("RESULT_INVALID_PURPOSE", null, "The seed's purpose doesn't allow this.", "Ask for the purpose the seed was authorized for.", SEED_VAULT),
        Known("RESULT_UNSPECIFIED_ERROR", null, "Seed Vault failed without saying why.", "Try again; if it persists, restart Seed Vault.", SEED_VAULT),
    )

    // ------------------------------------------------------------------ finding

    private val ANCHOR_BY_NAME by lazy { ANCHOR.associateBy { it.name } }
    private val ANCHOR_BY_CODE by lazy { ANCHOR.associateBy { it.code } }

    private val ERROR_CODE = Regex("Error Code:\\s*([A-Za-z]+)")
    private val ERROR_NUMBER = Regex("Error Number:\\s*(\\d+)")
    private val CUSTOM = Regex("custom program error:\\s*0x([0-9a-fA-F]+)")
    private val CAMEL = Regex("\\b([A-Z][a-z]+(?:[A-Z][a-z0-9]+)+)\\b")
    private val UPPER_NAME = Regex("\\b((?:ERROR|RESULT)_[A-Z_]+)\\b")

    /** The first error in [text] this table knows, or null. */
    fun find(text: String): Known? {
        ERROR_CODE.find(text)?.groupValues?.get(1)?.let { name -> ANCHOR_BY_NAME[name]?.let { return anchorKnown(it) } }
        ERROR_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { number(it, text)?.let { known -> return known } }
        CUSTOM.find(text)?.groupValues?.get(1)?.toIntOrNull(16)?.let { number(it, text)?.let { known -> return known } }
        UPPER_NAME.findAll(text).map { it.value }.firstNotNullOfOrNull { name -> NAMED.firstOrNull { it.name == name } }?.let { return it }
        PRINTED.sortedByDescending { it.name.length }.firstOrNull { text.contains(it.name, ignoreCase = true) }?.let { return it }
        // A bare Anchor name, as a user types it: "what causes AccountDidNotDeserialize".
        CAMEL.findAll(text).map { it.value }.firstNotNullOfOrNull { ANCHOR_BY_NAME[it] }?.let { return anchorKnown(it) }
        return null
    }

    /** An error number: Anchor's own, a program's custom one, or Token's or System's when the text names the program. */
    private fun number(code: Int, text: String): Known? {
        ANCHOR_BY_CODE[code]?.let { return anchorKnown(it) }
        if (code >= CUSTOM_START) return customAnchor(code)
        val hex = "0x" + code.toString(16)
        if (TOKEN_PROGRAMS.containsMatchIn(text)) TOKEN.getOrNull(code)?.let { (name, cause, fix) -> return Known(name, hex, cause, fix, TOKEN_LINK) }
        if (SYSTEM_PROGRAM.containsMatchIn(text)) SYSTEM.getOrNull(code)?.let { (name, cause, fix) -> return Known(name, hex, cause, fix, SYSTEM_LINK) }
        return null
    }

    /**
     * Text that reads like an error even when the table doesn't know it: an Anchor or program
     * log line, a failed transaction, a wallet error. Such text goes to the model with the
     * knowledge base.
     */
    private val LOOKS_LIKE = Regex(
        "Error Code:|Error Number:|AnchorError|custom program error|Program log:|Program \\S+ failed|" +
            "Error processing Instruction|Transaction simulation failed|SendTransactionError|\\b(?:ERROR|RESULT)_[A-Z_]{3,}|" +
            "panicked at|failed to send transaction|\\bInstructionError\\b",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeError(text: String): Boolean = LOOKS_LIKE.containsMatchIn(text) || find(text) != null

    /** "Explain this error", "what does this error mean", "why did it fail": asking about an error on screen. */
    private val ASKS = Regex(
        "\\b(explain|what does|what's|what is|why)\\b.{0,30}\\b(error|fail(ed|ing|ure)?|went wrong)\\b|\\bwhat causes\\b|\\bhow (do i|to) fix\\b",
        RegexOption.IGNORE_CASE
    )

    fun asksAboutError(text: String): Boolean = ASKS.containsMatchIn(text)

    /** For tests: how many Anchor errors the table holds. */
    val anchorCount: Int get() = ANCHOR.size
}
