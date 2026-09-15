package xyz.heylana.app.brain

import xyz.heylana.app.wallet.cleanName

/**
 * Heylana says the user's name once: on the first answer after the buddy starts,
 * and never again until it is stopped and started.
 *
 * The name goes only with that one question. Later questions do not carry it at
 * all, which is the surest way the model will not use it again — and one line
 * fewer to pay for on every request after the first.
 *
 * One of these lives as long as the buddy does, so starting the buddy afresh is
 * what makes it greet again.
 */
class Greeting {

    private var answered = false

    /** The line for the next question, or null once greeted or when no name is set. */
    fun lineFor(callMe: String?): String? {
        if (answered) return null
        val name = callMe?.let(::cleanName)?.takeIf { it.isNotEmpty() } ?: return null
        return "The user's name is $name. Start this answer with their name."
    }

    /** An answer has landed; whatever it said, the first answer is gone. */
    fun answered() {
        answered = true
    }
}
