package xyz.heylana.app.brain

import xyz.heylana.app.actions.QuickActions

/**
 * Questions that need no screen: small talk, a joke, an opinion, a question about
 * Heylana, or plain general knowledge. They skip the screen read entirely — no
 * listing is taken or sent — and go to the quick model.
 *
 * Deliberately narrow. Anything that could be about what is in front of the user
 * ("this", "here", a button, an app), a request for help doing something, a Solana
 * word, a send, a signing question or a quick action takes the ordinary path, which
 * reads the screen. A chat question wrongly sent the ordinary way costs a read; a
 * screen question wrongly sent as chat gets a wrong answer.
 */
object ChatQuestions {

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE)

    /** Talk for its own sake: greetings, how are you, thanks, jokes, opinions, Heylana itself. */
    private val SMALL_TALK = Regex(
        "^\\s*(hey heylana[,\\s]*)?(hi|hey|hello|yo|hiya|howdy|good (morning|afternoon|evening|night)|thanks|thank you|cheers|" +
            "gm|gn|lol|haha|nice|cool|ok(ay)?|bye|goodbye|see you|love you)\\b[\\s!.,?]*" +
            "(heylana)?[\\s!.,?]*$|" +
            "\\bhow('?s| is| are| was)\\s+(you|your|it going|things|life)\\b|\\bwhat'?s up\\b|\\bhow do you feel\\b|" +
            "\\b(tell|give) me (a|another|one more) (joke|fun fact|riddle|story|compliment)\\b|\\bmake me (laugh|smile)\\b|" +
            "\\bwhat do you think (of|about)\\b|\\byour (opinion|favou?rite|take)\\b|\\bdo you (like|love|prefer|think)\\b|" +
            "\\bwould you rather\\b|\\b(who|what) are you\\b|\\bwhat'?s your name\\b|\\bwho (made|built|created) you\\b|" +
            "\\bare you (a|an|real|human|ok|okay|happy|bored)\\b|\\bi('?m| am) (bored|tired|happy|sad|hungry|stressed)\\b",
        OPTIONS
    )

    /** General knowledge asked as such. */
    private val KNOWLEDGE = Regex(
        "^\\s*(who (is|was|were|invented|wrote|discovered|won)|when (was|were|did|is)|" +
            "what (is|are|was|were|'s) the (capital|population|meaning|difference|tallest|largest|biggest|smallest|" +
            "longest|oldest|best|fastest|speed|distance|boiling|history|origin)|" +
            "what does \\S+ mean|how (many|far|old|tall|big|long|deep|hot|cold) (is|are|was|were|does|do)|" +
            "define|translate|spell|what'?s \\d+|how do you say|what year|which (country|planet|city|ocean))\\b",
        OPTIONS
    )

    /** Anything that points at the screen, or asks for help doing something on the phone. */
    private val SCREEN = Regex(
        "\\b(this|that|these|those|here|there|screen|page|button|icon|tab|menu|app|apps|tap|click|press|" +
            "scroll|swipe|open|show|shown|see|showing|notification|settings?|option|field|link|phone|" +
            "step|next|why)\\b|" +
            "\\b(how (do|can|should) i|where (is|are|do|can)|what (do|should|can) i|can'?t i|help me|" +
            "not working|doesn'?t work|won'?t|stuck)\\b",
        OPTIONS
    )

    fun isChat(question: String): Boolean {
        val q = question.trim()
        if (q.isEmpty()) return false
        if (Routing.isSendQuestion(q) || Routing.isExplainQuestion(q)) return false
        if (QuickActions.isQuickAction(q)) return false
        if (SolanaCore.mentionsSolana(q)) return false
        if (SCREEN.containsMatchIn(q)) return false
        return SMALL_TALK.containsMatchIn(q) || KNOWLEDGE.containsMatchIn(q)
    }
}
