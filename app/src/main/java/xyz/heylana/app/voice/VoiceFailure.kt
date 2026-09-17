package xyz.heylana.app.voice

/**
 * Why Heylana stayed silent, as `voice_failed reason=…` logs it. There is no second
 * voice: whatever the reason, the answer is shown as text instead.
 */
object VoiceFailure {

    /** The worker's own daily cap for this device. */
    const val DAILY_CAP = "429 daily_cap"

    /** The voice provider's quota or rate limit, passed on by the worker. */
    const val QUOTA = "quota"

    /** No audio within [FIRST_AUDIO_MS], or the stream stalled that long. */
    const val TIMEOUT = "timeout"

    /** Anything else: a refused or broken request, or a reply with no audio in it. */
    const val ERROR = "error"

    /** How long the first audio (and each read after it) may take before Heylana gives up. */
    const val FIRST_AUDIO_MS = 6_000L

    /** The reason for a refused /tts, from its status code and the worker's `reason`. */
    fun reasonFor(code: Int, refusal: String?): String = when {
        code == 429 && refusal == "daily_cap" -> DAILY_CAP
        code == 429 -> QUOTA
        refusal == "quota" -> QUOTA
        code == 408 || code == 504 -> TIMEOUT
        else -> ERROR
    }
}
