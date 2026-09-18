package xyz.heylana.app.actions

import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.wallet.Answer

/**
 * The second check for a phone action with a side effect (R3): a text message or a
 * calendar reminder. The app's own guard ([QuickGuard]) is the first — every part in
 * the user's own words; then the worker is asked to confirm it is the action it
 * proposed, to this phone, and only with its confirmation token does the action fire.
 *
 * Everything else (an alarm, a timer, an app, a page) is R2 and fires on the guard
 * alone. The own-key path has no worker in it, so its actions fire on the guard alone
 * too; through the worker, an R3 action with no id from it is never fired.
 */
object ConfirmGate {

    const val NOT_CONFIRMED = "I couldn't confirm that with Heylana's server, so I didn't do it."

    /** The intents that need the worker's confirmation before they fire. */
    val R3 = setOf(QuickAction.MESSAGE, QuickAction.REMINDER)

    fun needsConfirmation(intent: String): Boolean = intent in R3

    sealed interface Decision {
        /** [why] is for the log: "r2", "own_key", "confirmed", "debug". */
        data class Fire(val why: String) : Decision
        data class Hold(val line: String, val why: String) : Decision
    }

    suspend fun check(
        intent: String,
        actionId: String?,
        ownKey: Boolean,
        confirm: suspend (kind: String, subject: String) -> Answer<String>,
        debug: Boolean = false,
        log: (String) -> Unit = { HeylanaLog.state(it) }
    ): Decision {
        val decision = when {
            !needsConfirmation(intent) -> Decision.Fire("r2")
            ownKey -> Decision.Fire("own_key")
            debug -> Decision.Fire("debug")
            actionId == null -> Decision.Hold(NOT_CONFIRMED, "no_action_id")
            else -> when (val answer = confirm(intent, actionId)) {
                is Answer.Ok -> Decision.Fire("confirmed")
                is Answer.Refused -> Decision.Hold(NOT_CONFIRMED, "refused_${answer.code}")
                is Answer.Unreachable -> Decision.Hold(NOT_CONFIRMED, "unreachable")
            }
        }
        log(
            "action: confirm intent=$intent class=${if (needsConfirmation(intent)) "R3" else "R2"} " +
                when (decision) {
                    is Decision.Fire -> "decision=fire why=${decision.why}"
                    is Decision.Hold -> "decision=hold why=${decision.why}"
                }
        )
        return decision
    }
}
