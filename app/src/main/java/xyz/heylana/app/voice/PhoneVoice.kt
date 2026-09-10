package xyz.heylana.app.voice

/**
 * Which of the phone's own voices to use when Heylana's proper one cannot be
 * reached.
 *
 * Never the engine default: on most phones that is a flat, clipped voice that
 * makes the buddy sound like a satnav. Android usually ships several better
 * ones and simply does not pick them, so this does — the best en-US voice it
 * can find, preferring one that sounds like Heylana and one that does not need
 * the network to speak.
 */
object PhoneVoice {

    /** One voice the engine offers, in the terms Android describes it. */
    data class Option(
        val name: String,
        val locale: String,
        val quality: Int,
        val needsNetwork: Boolean
    )

    /**
     * The best of what is installed, or null only when the engine offers
     * nothing at all — in which case there is no voice to tune and the caller
     * leaves the engine alone.
     */
    fun best(options: List<Option>): Option? {
        if (options.isEmpty()) return null
        val english = options.filter { it.locale.isEnglish() }.ifEmpty { options }
        val american = english.filter { it.locale.isAmerican() }.ifEmpty { english }
        return american.sortedWith(
            compareByDescending<Option> { it.name.soundsFemale() }
                .thenBy { it.needsNetwork }
                .thenByDescending { it.quality }
                // Last resort so the same phone always picks the same voice.
                .thenBy { it.name }
        ).firstOrNull()
    }

    private fun String.isEnglish(): Boolean = startsWith("en", ignoreCase = true)

    private fun String.isAmerican(): Boolean =
        contains("US", ignoreCase = true) || equals("en", ignoreCase = true)

    /**
     * Android names its voices things like `en-us-x-sfg#female_1-local`, so the
     * only honest way to ask is to read the name.
     */
    private fun String.soundsFemale(): Boolean = contains("female", ignoreCase = true)

    /** A touch above flat, which is where a friendly voice sits. */
    const val PITCH = 1.05f
    const val RATE = 1.0f
}
