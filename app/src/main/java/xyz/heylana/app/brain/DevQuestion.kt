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

    /**
     * Whether the answer to [question] depends on which release you are on, and so has to
     * say which month it is talking about. Anything with a moving number in it: what a
     * transaction may weigh, what a slot lasts, which client is running, what a library
     * calls its functions this year.
     */
    private val MOVES = Regex(
        "(version|release|latest|current|now|today|anchor|agave|firedancer|token-2022|token2022|" +
            "slot time|epoch|transaction size|compute unit|priority fee|web3\\.js|@solana|deprecat)",
        RegexOption.IGNORE_CASE
    )

    fun movesWithVersion(question: String): Boolean = MOVES.containsMatchIn(question)
}
