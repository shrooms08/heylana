package xyz.heylana.app.brain

import java.io.InputStream

/**
 * An answer the worker speaks while it is still writing it.
 *
 * The old way was two trips: ask, wait for the whole answer, ask for the voice, wait
 * again. Measured on the Seeker on Sept 20 that was 1.8s of model and 0.7s of voice, one
 * after the other, before a word was heard. Now one request brings both back — the audio
 * of each sentence as it is finished, and the reply itself as soon as it is written — and
 * the phone plays the audio as it lands.
 *
 * Whoever asks a question hands one of these in. [speaking] is called at most once, from
 * the thread reading the answer; [notSpoken] says why nothing came, and is the phone's
 * cue to say the answer the old way.
 */
interface SpokenAnswer {

    /** Audio is coming: play it as it arrives. [rate] is its sample rate. */
    fun speaking(audio: InputStream, rate: Int)

    /**
     * Nothing was heard. [SpokenAnswer.NOT_SPOKEN] means the worker never started — the
     * phone says the answer itself — and anything else is a voice failure in the worker's
     * words ("429 daily_cap"), which stays silent and shows the words instead.
     */
    fun notSpoken(reason: String)

    companion object {
        /** The worker spoke nothing at all: say it the old way. */
        const val NOT_SPOKEN = "not_spoken"

        /** Part of it was heard before the voice gave out; the rest is let go. */
        const val PARTLY_SPOKEN = "partly_spoken"

        /** What the one-trip answer comes back as. */
        const val STREAM_TYPE = "application/x-heylana-say"

        const val FRAME_AUDIO = 1
        const val FRAME_REPLY = 2
        const val FRAME_VOICE_FAILED = 3
        const val FRAME_HEADER_BYTES = 5
    }
}
