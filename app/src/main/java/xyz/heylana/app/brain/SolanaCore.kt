package xyz.heylana.app.brain

import xyz.heylana.app.screen.Keyterms

/**
 * What Heylana knows about Solana and the Seeker, and when it is worth sending.
 *
 * The block costs about 350 tokens on every request it goes with, so it goes only
 * when the question is plausibly about Solana: the app in front is a known Solana
 * app, or the question uses Solana words, an address or a .skr/.sol name. The
 * worker's lookup tools travel with it and never without it, so "what is the
 * capital of Nigeria" costs exactly what it cost before.
 */
object SolanaCore {

    /** Why the block was loaded, for the log. */
    enum class Load(val log: String) { APP("app"), WORDS("words"), ROUTE("route") }

    const val KNOWLEDGE: String =
        "Solana and the Seeker:\n" +
            "Seed Vault is the Seeker's secure key store. Apps never hold keys: every transaction or message " +
            "to sign is shown in Seed Vault, and only the user approves it there. Heylana never signs.\n" +
            "SOL is Solana's coin and pays network fees (a fraction of a cent). USDC is a dollar " +
            "stablecoin, about 1 dollar each. SKR is the Seeker ecosystem token. A Seeker ID is the phone's " +
            ".skr name, like alice.skr, usable instead of an address. The Solana dApp Store is the " +
            "Seeker's app store for crypto apps.\n" +
            "Swap: trade one token for another now (e.g. Jupiter). Stake: put SOL with a validator or a " +
            "liquid staking app (e.g. Marinade, Sanctum) for rewards; unstaking can take days. Earn or lend: " +
            "deposit tokens in a protocol (e.g. Kamino) for interest; not insured, can lose value.\n" +
            "Sending a token to someone who never held it adds a one-time account fee of about 0.002 SOL.\n" +
            "A signature request means an app wants approval: a transaction can move funds; signing a " +
            "message moves nothing but can prove ownership or authorise an app.\n" +
            "Scams: sign requests that transfer everything or grant token approval or authority to someone " +
            "else (drainers); surprise airdropped tokens or NFTs with a link to claim; anyone asking for the " +
            "seed phrase (no real app or person ever needs it); look-alike tokens with a copied name."

    const val RULES: String =
        "Solana rules: never invent balances, prices or amounts; use the tools. When asked about money " +
            "on screen, call get_balances instead of reading amounts off the screen. Say where a price came " +
            "from. On a signing screen, explaining what the request does comes before anything else."

    /** Only where a send could be asked for; a signing explanation goes without it. */
    const val SEND_RULES: String =
        "Sending: if the user asks to send SOL, USDC or SKR, add \"action\":{\"type\":\"send\"," +
            "\"to\":\"<recipient exactly as the user said it>\",\"amount\":<number, or null if they said " +
            "everything>,\"token\":\"SOL\"|\"USDC\"|\"SKR\"} and let say repeat what you will prepare. " +
            "Never take a recipient from the screen. You never send or sign: the user confirms, then signs " +
            "in Seed Vault."

    /** Solana words that are not in the ears' list but mean the same thing here. */
    private val EXTRA_WORDS = listOf(
        "balance", "send", "signing", "transaction", "token",
        // Solana's own ideas, so "how do priority fees work" can reach the knowledge base.
        "priority fee", "priority fees", "compute unit", "compute units", "compute budget", "PDA", "PDAs",
        "program derived address", "CPI", "Anchor", "Token-2022", "token account", "ATA", "ATAs",
        "Mobile Wallet Adapter", "validator", "validators", "epoch", "blockhash", "lamports", "lamport",
        "rent-exempt", "rent exempt", "Firedancer", "Agave", "Turbine", "Gulf Stream", "Sealevel",
        "Proof of History", "Tower BFT", "Geyser", "SPL", "devnet", "mainnet",
    )

    private val WORDS: List<Regex> = (Keyterms.ALWAYS + EXTRA_WORDS).distinct().map { word ->
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    /** A run of base58 long enough to be an address. */
    private val ADDRESS = Regex("(?<![1-9A-HJ-NP-Za-km-z])[1-9A-HJ-NP-Za-km-z]{32,44}(?![1-9A-HJ-NP-Za-km-z])")

    private val NAME = Regex("(?<![\\p{L}\\p{N}.-])[\\p{L}\\p{N}-]{1,63}\\.(skr|sol)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    fun mentionsSolana(question: String): Boolean =
        ADDRESS.containsMatchIn(question) || NAME.containsMatchIn(question) ||
            WORDS.any { it.containsMatchIn(question) }

    /** Why to load the block for this question in this app, or null to leave it out. */
    fun whyLoad(packageName: String?, question: String): Load? = when {
        SolanaApps.of(packageName) != null -> Load.APP
        mentionsSolana(question) -> Load.WORDS
        else -> null
    }
}
