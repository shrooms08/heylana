package xyz.heylana.app.screen

/**
 * The words the ears are told to expect.
 *
 * Speech recognition guesses at names it has never heard: "Kamino" comes back
 * as "come in oh", "SKR" as "seeker". Deepgram takes a list of terms to lean
 * toward, so Heylana sends two kinds — the things this phone always talks about,
 * and the biggest tappable labels on the screen the user is looking at right
 * now, which is usually exactly what they are about to say.
 *
 * Only labels, only from the screen already being read, and only while the user
 * is holding the buddy down.
 */
object Keyterms {

    /** One thing on screen the user could tap, and how big it is. */
    data class Labelled(val label: String, val area: Int)

    /** How many of the screen's own labels are worth sending. */
    const val MAX_FROM_SCREEN = 25

    /** The words this phone is about, whatever is on screen. */
    val ALWAYS = listOf(
        "Solana", "Seeker", "Seed Vault", "SKR", "USDC", "SOL", "swap", "stake",
        "dApp Store", "Kamino", "Jupiter", "Phantom", "Backpack", "wallet", "mint", "airdrop"
    )

    /** Longer than this is a sentence, not a name, and no use as a hint. */
    private const val MAX_LABEL_CHARS = 40

    /**
     * The fixed words first, then the screen's biggest tappable labels, with
     * anything repeated or useless dropped. Case is kept as it appears.
     */
    fun from(onScreen: List<Labelled>): List<String> {
        val terms = ArrayList<String>(ALWAYS.size + MAX_FROM_SCREEN)
        val seen = HashSet<String>()

        for (term in ALWAYS) {
            if (seen.add(term.lowercase())) terms.add(term)
        }

        onScreen.asSequence()
            .sortedByDescending { it.area }
            .map { it.label.trim() }
            .filter { worthSending(it) }
            .filter { seen.add(it.lowercase()) }
            .take(MAX_FROM_SCREEN)
            .forEach { terms.add(it) }

        return terms
    }

    /** A label is a hint only if it is a word someone might say. */
    private fun worthSending(label: String): Boolean {
        if (label.isEmpty() || label.length > MAX_LABEL_CHARS) return false
        // Numbers and amounts are read out digit by digit anyway, and balances
        // are nobody's business but the user's.
        return label.any { it.isLetter() }
    }
}
