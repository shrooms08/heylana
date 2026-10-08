package xyz.heylana.app.settings

/**
 * What the app says about its voice, following whichever provider the worker speaks
 * through — `/me` says which (`voice.provider`) and what its two voices are called. The
 * privacy line names the service the spoken answer text actually goes to.
 */
object VoiceCopy {

    const val DEEPGRAM = "deepgram"
    const val GEMINI = "gemini"
    const val CARTESIA = "cartesia"

    /** What the worker is set to speak through (wrangler.toml), until /me says otherwise. */
    const val DEFAULT_PROVIDER = DEEPGRAM

    /** The one sentence that names where the spoken answer text goes. */
    fun ttsSentence(provider: String): String = when (provider) {
        DEEPGRAM -> "The spoken answer text goes to Deepgram to become speech."
        CARTESIA -> "The spoken answer text goes to Cartesia to become speech."
        else -> "The spoken answer text goes to Google (Gemini) to become speech."
    }

    const val LIVE_SENTENCE =
        "Conversation mode, when enabled, uses Gemini Live's free tier; Google may use that audio to improve its models."

    /** The "what leaves the phone" paragraph about voice, in the old Settings screen. */
    fun privacyLine(provider: String, assemblyai: Boolean = false): String =
        "${earsSentence(assemblyai)} ${ttsSentence(provider)} $LIVE_SENTENCE The screen never goes to any of them."

    /** Where your voice goes while you hold the buddy: Deepgram, and AssemblyAI when it is listening too. */
    fun earsSentence(assemblyai: Boolean): String =
        if (assemblyai) "Your voice goes to Deepgram and AssemblyAI to be transcribed while you hold the buddy."
        else "Your voice goes to Deepgram to be transcribed while you hold the buddy."

    /** The picker's names for the two slots when /me has not said: the worker's own names for them. */
    fun defaultName(provider: String, slot: String): String {
        val archie = slot == HeylanaSettings.VOICE_ARCHIE
        return when (provider) {
            DEEPGRAM -> if (archie) "Aries" else "Callista"
            CARTESIA -> if (archie) "Archie" else "Skylar"
            else -> if (archie) "Achird" else "Sulafat"
        }
    }
}
