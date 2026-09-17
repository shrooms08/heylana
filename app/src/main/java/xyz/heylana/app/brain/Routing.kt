package xyz.heylana.app.brain

import xyz.heylana.app.actions.QuickActions

/**
 * Which model answers, and whether Solana knowledge and tools go with it.
 *
 * Anything where a wrong answer costs money goes to the task model: a signing
 * screen, a wallet or swap screen, and any question about sending or about what
 * is being signed. Everything else stays on the quick model. The worker still
 * names the model; the app only says quick or task.
 */
object Routing {

    enum class Why(val log: String) {
        SIGNING_SCREEN("signing_screen"),
        EXPLAIN_QUESTION("explain_question"),
        SEND_QUESTION("send_question"),
        QUICK_ACTION("quick_action"),
        /** Needs no screen: small talk, a joke, general knowledge. The screen is never read. */
        CHAT("chat"),
        WALLET_SCREEN("wallet_screen"),
        SWAP_SCREEN("swap_screen"),
        PLAIN("plain")
    }

    data class Route(val mode: String, val why: Why, val solana: SolanaCore.Load?) {
        val toolsWanted: Boolean get() = solana != null

        /** Explain before you sign: a signing screen, or "what am I signing" anywhere. */
        val explainsSigning: Boolean get() = why == Why.SIGNING_SCREEN || why == Why.EXPLAIN_QUESTION

        /**
         * Only a plain question greets by name. A send, a signing explanation, or
         * anything on a wallet or swap screen is about money: no hello on those.
         */
        val allowsGreeting: Boolean get() = why == Why.PLAIN || why == Why.CHAT

        /** No screen was read for this question, and none goes with it. */
        val skipsScreen: Boolean get() = why == Why.CHAT
    }

    val PLAIN = Route(ProxyClient.MODE_QUICK, Why.PLAIN, null)

    /** Chat, decided from the words alone and before the screen is read: quick model, no Solana, no tools. */
    val CHAT = Route(ProxyClient.MODE_QUICK, Why.CHAT, null)

    fun chatRoute(question: String): Route? = if (ChatQuestions.isChat(question)) CHAT else null

    private val SEND = Regex("(?<![\\p{L}])(send|transfer)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    /**
     * Only questions about signing or approving. "What does this do" is left out
     * on purpose: it is asked about every button in every app, and must stay on
     * the quick model without tools.
     */
    private val EXPLAIN = Regex(
        "what am i (signing|approving)|what (does|will|would) (this|it) (sign|approve)|" +
            "what is this (transaction|signature request|approval)|is (this|it) safe to (sign|approve)|" +
            "explain (this|the) (transaction|signature request|approval)",
        RegexOption.IGNORE_CASE
    )

    fun isSendQuestion(question: String): Boolean = SEND.containsMatchIn(question)

    fun isExplainQuestion(question: String): Boolean = EXPLAIN.containsMatchIn(question)

    fun forQuestion(packageName: String?, question: String, screenText: String = "", ownWallet: String? = null): Route {
        val app = SolanaApps.of(packageName)
        val why = when {
            SigningScan.looksLikeSigning(packageName, screenText, ownWallet) -> Why.SIGNING_SCREEN
            isExplainQuestion(question) -> Why.EXPLAIN_QUESTION
            isSendQuestion(question) -> Why.SEND_QUESTION
            QuickActions.isQuickAction(question) -> Why.QUICK_ACTION
            app?.kind == SolanaApps.Kind.WALLET -> Why.WALLET_SCREEN
            app?.kind == SolanaApps.Kind.SWAP -> Why.SWAP_SCREEN
            else -> Why.PLAIN
        }
        // An alarm or "open the wallet" needs no Solana knowledge and no lookups: the
        // action tool is all it gets, on the quick model.
        if (why == Why.QUICK_ACTION) return Route(ProxyClient.MODE_QUICK, why, null)
        // A send or explain question needs the tools even in Chrome.
        val load = SolanaCore.whyLoad(packageName, question) ?: if (why != Why.PLAIN) SolanaCore.Load.ROUTE else null
        val mode = if (why == Why.PLAIN) ProxyClient.MODE_QUICK else ProxyClient.MODE_TASK
        return Route(mode, why, load)
    }
}
