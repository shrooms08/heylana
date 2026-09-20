package xyz.heylana.app.overlay

import xyz.heylana.app.brain.AddressText
import xyz.heylana.app.brain.SigningScan
import xyz.heylana.app.screen.ScamWatch
import xyz.heylana.app.screen.ScreenSnapshot

/**
 * The buddy noticing something without being asked.
 *
 * Two screens are worth a word unprompted: a wallet asking for a signature, and a page
 * asking for a recovery phrase or wearing a name one letter away from a real one. This
 * decides which, and writes the line itself — **no model is asked and nothing leaves the
 * phone**, so a glance costs nothing and tells no one what the user is looking at.
 *
 * The rules it keeps to:
 *
 *  - one glance per screen, not one per redraw: the same window says its piece once;
 *  - it never speaks. The line is shown beside the disc and a tap opens the box, which is
 *    where Heylana starts talking, as always;
 *  - it never gives a verdict. "Approve" is not "safe" and a look-alike is not "a scam":
 *    the line says what is on the screen and leaves the deciding to the user.
 */
object Lookout {

    /** What a glance is about, for the log and so the same one is not shown twice. */
    enum class Why { SIGNING, SECRET, BLOCKLIST, LOOK_ALIKE }

    data class Glance(val line: String, val why: Why, val about: String)

    /** The tail of the line on a signing screen: how to get the rest. */
    const val TAP_TO_CHECK = "Tap me to check it."

    /**
     * What to say about this screen, if anything.
     *
     * [blocked] is the worker's blocklist, already on the phone; [ownWallet] is the user's
     * own address, so their own wallet on screen is not a stranger.
     */
    fun glanceAt(
        snapshot: ScreenSnapshot,
        blocked: Set<String> = emptySet(),
        ownWallet: String? = null,
    ): Glance? {
        val text = snapshot.toPromptText()
        // A wallet is where a phrase legitimately appears, so the phrase warning stays out
        // of one; a look-alike domain is a browser's business either way.
        val kind = xyz.heylana.app.brain.SolanaApps.of(snapshot.packageName)?.kind
        val wallet = kind == xyz.heylana.app.brain.SolanaApps.Kind.WALLET ||
            kind == xyz.heylana.app.brain.SolanaApps.Kind.SIGNING

        // A page asking for the one secret that is never shared comes first: it is the
        // only one of these where the harm is done the moment the user types.
        ScamWatch.of(text, snapshot.pageAddress, snapshot.packageName, blocked, wallet)?.let { warning ->
            val why = when (warning.why) {
                ScamWatch.Warning.Why.SECRET -> Why.SECRET
                ScamWatch.Warning.Why.BLOCKLIST -> Why.BLOCKLIST
                ScamWatch.Warning.Why.LOOK_ALIKE -> Why.LOOK_ALIKE
            }
            return Glance(warning.words, why, warning.about)
        }

        if (!SigningScan.looksLikeSigning(snapshot.packageName, text, ownWallet)) return null
        // Waking up is a stricter test than answering a question about a screen: Seed Vault
        // is the signing app, but its own backup and settings screens are not a signature
        // being asked for, and a buddy that pops up on those is one the user switches off.
        if (!SigningScan.hasSigningWords(text)) return null
        return Glance(signingLine(text), Why.SIGNING, about = snapshot.packageName ?: "signing")
    }

    /**
     * The one line for a signing screen, written from what was read: the kind of request
     * if the screen's words say, then the amount and who it is for if they are there.
     * Never a verdict, and never longer than a strip can hold.
     */
    fun signingLine(screenText: String): String {
        val found = SigningScan.of(screenText)
        val kind = found.kinds.firstOrNull()
        val opening = when (kind) {
            SigningScan.Kind.APPROVAL -> "This looks like an approval, not a transfer."
            SigningScan.Kind.AUTHORITY -> "This looks like a change of authority over an account."
            SigningScan.Kind.CLOSE -> "This looks like closing an account."
            SigningScan.Kind.REVOKE -> "This looks like cancelling an earlier approval."
            SigningScan.Kind.UNLIMITED -> "This asks for an unlimited amount."
            null -> "Something here wants your signature."
        }
        val amount = found.amounts.firstOrNull()
        val who = (found.addresses.firstOrNull()?.let(AddressText::shorten) ?: found.shortAddresses.firstOrNull())
        val middle = when {
            amount != null && who != null -> " $amount to $who."
            amount != null -> " $amount."
            else -> ""
        }
        return "$opening$middle $TAP_TO_CHECK"
    }

    /**
     * Whether this screen is the same one the last glance was about. A wallet redrawing
     * its own confirm sheet is not a new thing to say, and the buddy waking twice over one
     * screen is the difference between helpful and infuriating.
     */
    fun sameAsBefore(glance: Glance, last: Glance?): Boolean =
        last != null && last.why == glance.why && last.about == glance.about
}
