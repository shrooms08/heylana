package xyz.heylana.app.brain

/**
 * A question from someone who is building on Solana, rather than using it.
 *
 * The two want different answers. "What is a PDA" from a holder wants a sentence; from a
 * developer it wants the two lines of code that derive one, which release they are for,
 * and the mistake everyone makes the first time. The eval that measures this found the
 * difference plainly: the same model answers both in prose, and the developer is left to
 * find out for themselves that the function was renamed three versions ago.
 *
 * The test is deliberately narrow — it takes the words of the work, not the words of the
 * subject. "How do I derive a PDA in Anchor" is a developer's question; "is my SOL safe"
 * is not, whatever the model would enjoy doing with it.
 */
object DevQuestion {

    /** The things only a developer names. */
    private val TOOLS = Regex(
        "(?<![\\p{L}])(" +
            "anchor|solana-cli|spl-token|web3\\.js|@solana|solana/kit|idl|borsh|bpf|sbf|" +
            "cpi|pda|lamports?|rpc|getaccountinfo|getprogramaccounts|simulatetransaction|" +
            "instruction|discriminator|realloc|lookup table|versioned transaction|compute unit|" +
            "priority fee|token-2022|token2022|associated token|mint authority|freeze authority|" +
            "geyser|validator client|agave|firedancer|seed vault|mobile wallet adapter|mwa|dapp store|" +
            "constraints?|seeds|bump|keypair|program id|account struct|anchor\\.toml|declare_id" +
            ")(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    /** The shapes a developer's question takes. */
    private val ASKING = Regex(
        "(?<![\\p{L}])(" +
            "how do i|how can i|how would i|what does .{0,20}(do|mean|return|give|take|need)|" +
            "why does|why is|" +
            "error|fails?|failed|failing|panic|throws?|exception|" +
            "derive|deserialize|deserialise|serialize|serialise|compile|deploy|" +
            "build|test|sign|simulate|call|invoke" +
            ")(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    /**
     * An error on the face of the question. This one stands on its own — "why does my
     * program fail with error code 2006" names no tool and is nobody else's question —
     * but it has to be an error and not a number: an amount is four digits too.
     */
    private val ERROR_SHAPE = Regex(
        "(error (code|number)|custom program error|program error|" +
            "0x[0-9a-f]{2,}|error \\d{3,4}|\\d{3,4} error|constrain\\w* (was )?violated)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Whether [question] is someone building, and so gets the developer's answer shape.
     *
     * It needs a developer's word **and** either a developer's way of asking or an error
     * on the face of it: "what is a lamport" is a user's question, "how do I read lamports
     * in Anchor" is not.
     */
    fun isDev(question: String): Boolean {
        val text = question.trim()
        if (text.isEmpty()) return false
        // An error is a developer's question whatever else is in it.
        if (ERROR_SHAPE.containsMatchIn(text)) return true
        if (!TOOLS.containsMatchIn(text)) return false
        return ASKING.containsMatchIn(text)
    }

    // ------------------------------------------------------------ how Solana works

    /**
     * What Solana is made of. Broad on purpose — "fees", "blocks", "clients" — because this
     * is only ever asked once the question has already loaded Solana (a Solana word, a Solana
     * app in front): here "fees" can only mean Solana's.
     */
    private val SUBJECT = Regex(
        "(?<![\\p{L}])(" +
            "solana|epochs?|slots?|validators?|leaders?|leader schedule|blocks?|blockhash|finality|" +
            "finali[sz]ed|confirmations?|forks?|" +
            "fees?|priority fees?|compute units?|compute budget|rent|rent[- ]exempt\\w*|lamports?|" +
            "accounts?|account model|programs?|instructions?|transactions?|signatures?|signers?|" +
            "versioned|lookup tables?|" +
            "tokens?|spl|token-2022|token2022|mints?|associated token|atas?|nfts?|" +
            "rpc|geyser|snapshots?|ledger|accounts ?db|" +
            "clients?|agave|firedancer|frankendancer|jito|" +
            "stak(e|es|ing|ed)|stake accounts?|delegat\\w*|rewards?|inflation|votes?|voting|" +
            "mempool|turbine|gulf stream|proof of history|poh|tower bft|sealevel|parallel|" +
            "write locks?|locks?|local fee markets?|" +
            "seed vault|mobile wallet adapter|mwa|dapp store|seeker|saga|solana mobile|" +
            "pdas?|program derived|cpi|anchor|sysvars?|bpf|sbf|loader|upgrade authority" +
            ")(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    /** Asking how something is, rather than telling Heylana to do something. */
    private val ASKS_HOW_IT_WORKS = Regex(
        "(^\\s*(what|how|why|when|which|who|where|does|do|did|is|are|was|were|can|could|will|would|" +
            "should|has|have)(?![\\p{L}])|\\?\\s*$|explain|tell me about|difference between|" +
            "what'?s|how'?s|meaning of)",
        RegexOption.IGNORE_CASE
    )

    /**
     * The user's own money, a decision, the screen in front, or something to do: Heylana's other
     * paths answer those — the balance, the send, the signing explanation, the button.
     */
    private val SOMETHING_ELSE = Regex(
        "(?<![\\p{L}])(" +
            "my (sol|balance|wallet|tokens?|funds|money|nfts?|portfolio|stake|coins?)|" +
            "how much (do i|have i|is my|is sol|am i)|how much (sol|usdc|skr) (do i|have i|is in|in my)|worth|" +
            "(sol|token|coin|usdc|skr|bonk|jup)'?s? price|price of|" +
            "should i (buy|sell|stake|unstake|swap|send|hold|invest|trust|approve|sign)|is it safe|safe to|scam|" +
            "(send|swap|buy|sell|pay|transfer) (\\d|all|everything|it|them|sol|usdc|skr|to|my)|" +
            "signing|sign this|approve this|" +
            "this|here|on (the )?screen|button|tap|click" +
            ")(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    private val ORDER = Regex("^\\s*(please\\s+)?(send|swap|buy|sell|pay|transfer|stake|unstake|approve|sign)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    /** A quoted message is the thing asked about, not the asking: "What does 'This transaction…' mean?" */
    private val QUOTED = Regex("'[^']{3,}'|\"[^\"]{3,}\"|‘[^’]{3,}’|“[^”]{3,}”")

    /**
     * Whether [question] is about how Solana works — epochs, slots, validators, fees,
     * accounts, programs, tokens, RPC, clients, staking, the mobile stack — whether or not
     * it is shaped like code.
     *
     * The dev eval found the narrow test above leaving most of these out: "What is Agave?"
     * and "How long is a Solana epoch?" were a user's questions to it, so they got no
     * lookup, no source and no date — and a stale number from memory. Every one of them now
     * gets the same treatment as a developer's: the knowledge base first, its page as a chip,
     * the month when the fact moves, and the trap where there is one. Code comes first only
     * when the question asks for it ([wantsCode]).
     *
     * Only ever asked of a question that already loaded Solana.
     */
    fun isMechanics(question: String): Boolean {
        val text = question.trim()
        if (text.isEmpty()) return false
        if (isDev(text)) return true
        // An error Heylana knows by name is a question about how Solana behaves.
        if (ErrorTable.find(text) != null) return true
        val unquoted = QUOTED.replace(text, " ")
        // A sentence that opens with an order is a thing to do: "Send 2 SOL…", "Swap it…".
        if (ORDER.containsMatchIn(unquoted)) return false
        if (SOMETHING_ELSE.containsMatchIn(unquoted)) return false
        return SUBJECT.containsMatchIn(text) && ASKS_HOW_IT_WORKS.containsMatchIn(text)
    }

    /** Asking for code, or for how to do a thing that is done in code. */
    private val CODE_ASK = Regex(
        "(?<![\\p{L}])(" +
            "code|snippet|sample|example code|write (me )?(a|an|some)|" +
            "how do i|how can i|how would i|how should i|how to|" +
            "web3\\.js|@solana|solana/kit|cli|command|terminal" +
            ")(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    /** Whether the answer should lead with code: a mechanics question that asks for it. */
    fun wantsCode(question: String): Boolean =
        // "Error Code: ConstraintSeeds" is the error's name, not a request for code.
        isMechanics(question) && CODE_ASK.containsMatchIn(ERROR_CODE.replace(question, " "))

    private val ERROR_CODE = Regex("error code", RegexOption.IGNORE_CASE)

    /**
     * Whether the answer to [question] depends on which release you are on, and so has to
     * say which month it is talking about. Anything with a moving number in it: what a
     * transaction may weigh, what a slot lasts, which client is running, what a library
     * calls its functions this year, whether a rule still holds.
     */
    private val MOVES = Regex(
        "(version|release|latest|current|now|today|anchor|agave|firedancer|token-2022|token2022|" +
            "slots?|epochs?|size|maximum|max |limit|default|still|mainnet|clients?|" +
            "compute unit|priority fee|web3\\.js|@solana|deprecat)",
        RegexOption.IGNORE_CASE
    )

    fun movesWithVersion(question: String): Boolean = MOVES.containsMatchIn(question)
}
