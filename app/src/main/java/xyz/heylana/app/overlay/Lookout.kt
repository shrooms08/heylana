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

    /**
     * [line] is what the strip shows; [spoken] is the one short sentence Heylana says out
     * loud. They are not the same on purpose: the strip can carry the amount, the address
     * and what to do next, while the spoken line has to land in the second before a thumb
     * reaches Approve.
     */
    data class Glance(val line: String, val spoken: String, val why: Why, val about: String)

    /** No spoken warning is longer than this. Anything more is not heard in time. */
    const val SPOKEN_WORDS = 15

    /** The tail of the line on a signing screen: how to get the rest. */
    const val TAP_TO_CHECK = "Tap me to check it."

    /**
     * What Heylana says the instant a signing window appears, **before anything is read**.
     *
     * Reading a wallet's tree takes long enough to matter when a thumb is already moving
     * towards Approve — on the Seeker the warning arrived after the user had approved. So
     * the first sentence is decided from the window event alone and played from the audio
     * already on the phone; the amount, the address and what it does follow on the strip a
     * moment later, and a second sentence follows only if the look finds something worse.
     */
    const val OPENING_LINE = "Careful: something is asking for your signature."

    /**
     * Words in a **window's own title or class** that mean a signature is being asked for.
     * This is not the screen's text — nothing has been read yet — it is what the system
     * says about the window as it comes up.
     */
    private val WINDOW_WORDS = Regex(
        "(sign|signature|approve|approval|confirm|authoriz|authoris|" +
            "transaction request|review (and )?(approve|confirm|sign)|slide to)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Whether this window, by itself, is a signature being asked for — decided with no
     * read at all.
     *
     * Seed Vault has no launcher on the Seeker: every window it puts up is there because an
     * app asked for a signature or for access, so any of its windows counts. A wallet is a
     * place you also browse, so one of its windows counts only when the window itself says
     * what it is for.
     */
    fun signingWindow(event: xyz.heylana.app.screen.WindowEvent): Boolean {
        val kind = xyz.heylana.app.brain.SolanaApps.of(event.packageName)?.kind ?: return false
        return when (kind) {
            xyz.heylana.app.brain.SolanaApps.Kind.SIGNING -> true
            xyz.heylana.app.brain.SolanaApps.Kind.WALLET -> WINDOW_WORDS.containsMatchIn(event.describedAs)
            else -> false
        }
    }

    /**
     * Whether this window is looked at the instant it appears, with no gap and no waiting
     * for the burst around it to settle.
     *
     * Every window of a wallet or of Seed Vault is, because that is where a signature comes
     * from. A wallet built in Compose tells the event almost nothing about itself — the
     * Seed Vault Wallet's own sheets come through as `FrameLayout` titled "Dialog" — so the
     * screen has to be read to tell a confirm sheet from a settings dialog. That read is
     * 13 to 67ms on the Seeker, which is affordable; waiting 250ms for a burst to settle,
     * or dropping the look altogether, was not.
     */
    fun urgentWindow(event: xyz.heylana.app.screen.WindowEvent): Boolean =
        when (xyz.heylana.app.brain.SolanaApps.of(event.packageName)?.kind) {
            xyz.heylana.app.brain.SolanaApps.Kind.SIGNING, xyz.heylana.app.brain.SolanaApps.Kind.WALLET -> true
            else -> false
        }

    /**
     * The second sentence, said only when the look found something worse than a transfer.
     * A plain transfer has already been announced and needs nothing more said out loud —
     * its amount and its address are on the strip.
     */
    fun strongerLine(glance: Glance, firstTime: Boolean = false): String? = when {
        glance.why == Why.SIGNING && glance.spoken != spokenSigning("") -> glance.spoken
        firstTime -> FIRST_TIME_LINE
        else -> null
    }

    /** Said when the address on a signing screen is one this wallet has not sent to before. */
    const val FIRST_TIME_LINE = "You have not sent to this address before."

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
        /** Debug builds only: any screen with a confirm sheet's words counts as one. */
        anySigns: Boolean = false,
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
            return Glance(warning.words, spokenWarning(why), why, warning.about)
        }

        if (!SigningScan.looksLikeSigning(snapshot.packageName, text, ownWallet) && !anySigns) return null
        // Waking up is a stricter test than answering a question about a screen: Seed Vault
        // is the signing app, but its own backup and settings screens are not a signature
        // being asked for, and a buddy that pops up on those is one the user switches off.
        if (!SigningScan.hasSigningWords(text)) return null
        // What tells one signing screen from another: the app, the kind of request and the
        // amount. The same sheet redrawing is the same screen; a second, different request
        // in the same wallet is not, and is worth saying out loud again.
        val found = SigningScan.of(text)
        val about = listOfNotNull(
            snapshot.packageName ?: "signing",
            found.kinds.firstOrNull()?.name,
            found.amounts.firstOrNull(),
            found.shortAddresses.firstOrNull() ?: found.addresses.firstOrNull()?.take(8),
        ).joinToString("|")
        return Glance(signingLine(text), spokenSigning(text), Why.SIGNING, about)
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
     * What Heylana says out loud about a signing screen: which kind of request it is, in
     * one sentence short enough to hear before a thumb moves.
     *
     * **It is one of a fixed few on purpose.** The amount and the address are on the strip,
     * where they can be read; putting them in the spoken line would make every sentence a
     * new one, and a new sentence has to be made by the voice — 800ms on the Seeker, which
     * is the whole budget. A fixed line is kept after the first time and played from the
     * phone, so the warning lands while the screen is still going up.
     */
    fun spokenSigning(screenText: String): String = when (SigningScan.of(screenText).kinds.firstOrNull()) {
        SigningScan.Kind.APPROVAL -> "Careful: this is an approval, not a transfer."
        SigningScan.Kind.AUTHORITY -> "Careful: this hands control of an account to someone else."
        SigningScan.Kind.CLOSE -> "Careful: this closes an account."
        SigningScan.Kind.REVOKE -> "This cancels an approval you gave earlier."
        SigningScan.Kind.UNLIMITED -> "Careful: this asks for an unlimited amount."
        null -> "Something here wants your signature."
    }

    /**
     * Every sentence Heylana can say unasked. A fixed list, so the audio for all of them
     * fits on the phone and none of them ever waits on the voice.
     */
    val SPOKEN_LINES: List<String>
        get() = listOf(OPENING_LINE, FIRST_TIME_LINE) + Why.entries.map(::spokenWarning) + listOf(
            spokenSigning("Approve spending\nSpending cap\nApprove"),
            spokenSigning("Set authority\nApprove"),
            spokenSigning("Close token account\nApprove"),
            spokenSigning("Revoke approval\nApprove"),
            spokenSigning("Unlimited\nApprove"),
            spokenSigning("Approve"),
        ).distinct()

    /** What Heylana says out loud about a page: the warning itself, cut to one sentence. */
    fun spokenWarning(why: Why): String = when (why) {
        Why.SECRET -> "No real Solana app asks for your recovery phrase."
        Why.BLOCKLIST -> "Careful: this site is on a known phishing list."
        Why.LOOK_ALIKE -> "Careful: this looks like a copy of a real site."
        Why.SIGNING -> "Something here wants your signature."
    }

    /**
     * Whether this screen is the same one the last glance was about. A wallet redrawing
     * its own confirm sheet is not a new thing to say, and the buddy waking twice over one
     * screen is the difference between helpful and infuriating.
     */
    fun sameAsBefore(glance: Glance, last: Glance?): Boolean =
        last != null && last.why == glance.why && last.about == glance.about
}
