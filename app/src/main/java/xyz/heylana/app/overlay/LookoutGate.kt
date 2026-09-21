package xyz.heylana.app.overlay

import xyz.heylana.app.wallet.TxState

/**
 * Whether the lookout may speak up now, and which rule it would be — kept away from Android so
 * a send and a window arriving together can be tested exactly as they happen on the phone.
 *
 * Two rules, both from what went wrong on the Seeker:
 *
 *  - **Heylana's own send is hers.** From the moment a send is prepared until its ending line
 *    has been said ("Cancelled. Nothing left your wallet.", "Done…"), the lookout keeps quiet:
 *    a glance takes the passive card's place on screen, and polish-9 found it wiping the Done
 *    card. A prepared card nobody confirms or cancels does not silence it for ever
 *    ([OWN_SEND_MAX_MS]).
 *  - **The in-app confirm rule needs its app in front.** A System UI window by itself is never
 *    a confirm: only one over an app with an [InAppConfirm] entry (Jupiter today), that app
 *    being the last one that put up a window of its own.
 */
object LookoutGate {

    /** What was decided, and why, in words fit for the log: names of rules, never the screen. */
    data class Verdict(val fires: Boolean, val why: String)

    /** How long after a send ends its line is still being said, and the card read. */
    const val ENDING_QUIET_MS = 8_000L

    /** A send that has not moved for this long is not in progress, whatever state it was left in. */
    const val OWN_SEND_MAX_MS = 5 * 60_000L

    /**
     * Whether Heylana's own send is still under way: prepared, waiting for the wallet or
     * confirming — or ended less than [ENDING_QUIET_MS] ago. [changedAt] is when the send last
     * moved, [now] the clock.
     */
    fun ownSendActive(tx: TxState?, changedAt: Long, now: Long): Boolean {
        if (tx == null || changedAt <= 0L) return false
        val since = now - changedAt
        return when (tx) {
            is TxState.Prepared, TxState.Waiting, is TxState.Confirming -> since < OWN_SEND_MAX_MS
            is TxState.Sent, is TxState.NotSent, TxState.Unsure -> since < ENDING_QUIET_MS
        }
    }

    /**
     * The in-app confirm rule, for a System UI window: it fires only over an app in front that
     * has an entry, and never during Heylana's own send.
     */
    fun inAppConfirm(foreground: String?, ownSend: Boolean): Verdict = when {
        ownSend -> Verdict(false, "own_send")
        foreground == null -> Verdict(false, "no_app_in_front")
        InAppConfirm.appOf(foreground) == null -> Verdict(false, "app_in_front_has_no_entry")
        else -> Verdict(true, "prompt_over_${InAppConfirm.appOf(foreground)!!.name.lowercase()}")
    }
}
