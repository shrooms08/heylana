package xyz.heylana.app.brain

/**
 * Everything the model is told, in one place so it is easy to tune.
 *
 * Kept deliberately short: this text is resent on every single request, so every
 * line here is paid for again and again out of a small budget. Trim before adding.
 */
object HeylanaPrompt {

    const val SYSTEM: String =
        "You are Heylana, on the user's Solana Seeker phone. You answer questions about " +
            "the screen in front of them.\n" +
            "\n" +
            "Answer only what was asked, then stop. Never describe your abilities, offer " +
            "further help, or mention Solana, Seeker or Heylana unless the question is about " +
            "them. No closing lines like \"let me know if\", \"I can also\", \"feel free to\". " +
            "Only if asked what you can do, describe it in two sentences.\n" +
            "\n" +
            "Spoken aloud: 1 to 3 short plain sentences, no markdown or symbols. Name buttons " +
            "by their visible label, never by number. Only describe what is in the list. If " +
            "the question isn't about the screen, answer it from general knowledge. You " +
            "cannot tap or type for them, so say what to tap and never claim you did it. " +
            "Never invent balances, prices or amounts.\n" +
            "\n" +
            "Reply with ONLY this JSON, no fences, no prose:\n" +
            "{\"say\":\"...\",\"point_at\":<id or null>,\"task\":{\"goal\":\"...\",\"done\":true|false}|null}\n" +
            "\n" +
            "point_at: the id in brackets of the one element they should tap, type into or " +
            "look at, else null. Only ids from the list. Never more than one.\n" +
            "\n" +
            "task: null for a question you can answer in one go. If they asked you to help DO " +
            "something needing more than one tap, task.goal restates it in one line, kept " +
            "word for word across every step. Then say describes ONLY the single next step " +
            "from where they are now, point_at is that step's element, and done is false. " +
            "When the screen shows the goal is reached, done is true, say confirms it " +
            "briefly, point_at is null."

    /**
     * Everything the model is told for one request. The Solana block and its rules
     * go only with questions routed as Solana ones; everything else gets [SYSTEM]
     * alone, exactly as before.
     */
    fun system(solana: Boolean): String =
        if (solana) "$SYSTEM\n\n${SolanaCore.KNOWLEDGE}\n\n${SolanaCore.RULES}" else SYSTEM

    /**
     * An ordinary question, with the recent conversation if there is any, and —
     * on the first answer after the buddy starts only — the line with their name.
     */
    fun userMessage(
        screenText: String,
        question: String,
        history: String? = null,
        greeting: String? = null
    ): String =
        buildString {
            greeting?.let { append(it).append("\n\n") }
            history?.let { append(it).append("\n\n") }
            append("Screen now:\n")
            append(screenText)
            append("\n\nUser asks: ").append(question)
        }

    const val SIGNING_INSTRUCTIONS: String =
        "Call explain_address on each address first. Then say in plain words what this request does, " +
            "who receives what, and whether the destination is known. End with one line of advice: " +
            "fine, check the amount, or do not sign. Never call it safe; say what you found. If nothing " +
            "could be read, say so and tell them to read the request in Seed Vault before approving."

    /**
     * Explain before you sign: the screen, what was found on it, and how to answer.
     * The recent conversation is left out so the answer is about this request only.
     */
    fun signingMessage(
        screenText: String,
        question: String,
        addresses: List<String>,
        amounts: List<String>,
        greeting: String? = null
    ): String = buildString {
        greeting?.let { append(it).append("\n\n") }
        append("Screen now:\n")
        append(screenText)
        append("\n\nSigning check. ")
        if (addresses.isEmpty() && amounts.isEmpty()) {
            append("No addresses or amounts could be read from this screen. ")
        } else {
            if (addresses.isNotEmpty()) append("Addresses on screen: ").append(addresses.joinToString(", ")).append(". ")
            if (amounts.isNotEmpty()) append("Amounts on screen: ").append(amounts.joinToString(", ")).append(". ")
        }
        append(SIGNING_INSTRUCTIONS)
        append("\n\nUser asks: ").append(question)
    }

    /**
     * The next step of a task already under way, against a freshly read screen.
     * Only the goal, the one-line step summaries and the new screen go over.
     */
    fun stepMessage(
        goal: String,
        historyText: String,
        screenText: String,
        stepNumber: Int,
        needPointerHint: Boolean
    ): String = buildString {
        append("Task in progress. Goal: ").append(goal).append('\n')
        append(historyText).append('\n')
        append("\nScreen now:\n")
        append(screenText)
        append("\n\nStep ").append(stepNumber).append(" of max ")
            .append(GuidanceSession.MAX_STEPS)
            .append(". If the goal is already reached set done true and confirm; otherwise give " +
                "the single next step and point at its element.")
        if (needPointerHint) {
            append(" Your last step pointed at nothing — if any visible element applies to this " +
                "step, give its id.")
        }
    }
}
