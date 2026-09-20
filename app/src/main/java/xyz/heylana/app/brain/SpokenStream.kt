package xyz.heylana.app.brain

import okhttp3.Response
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.voice.PcmPipe
import java.io.DataInputStream

/**
 * The one-trip answer, taken apart as it arrives.
 *
 * The worker sends frames down one connection: the audio of each sentence as it is made,
 * and the reply itself as soon as the model has finished writing it. The audio goes
 * straight into a [PcmPipe] for the speaker, so it is heard while the rest of the answer
 * is still being written; the reply comes back to whoever asked the question, exactly as
 * it always did.
 *
 * Reading stops handing anything back at the reply frame — that is when the caller can
 * get on with showing the answer — and the audio left behind is drained on a thread of
 * its own until the worker has said everything.
 */
internal class SpokenStream(
    private val response: Response,
    private val voice: SpokenAnswer
) {

    private val pipe = PcmPipe()
    private val rate = response.header("x-sample-rate")?.toIntOrNull() ?: DEFAULT_RATE
    private var audioFrames = 0
    private var audioBytes = 0L

    /**
     * Reads up to the reply and hands it back; the rest of the audio follows on its own.
     * [extract] is the same reading of the reply as an ordinary answer gets.
     */
    fun read(expectsAction: Boolean, extract: (String, Boolean) -> ProxyClient.Attempt): ProxyClient.Attempt {
        val input = DataInputStream(response.body.byteStream())
        var attempt: ProxyClient.Attempt? = null
        try {
            while (attempt == null) {
                val payload = next(input) ?: break
                attempt = take(payload, expectsAction, extract)
            }
        } catch (e: Exception) {
            HeylanaLog.state("spoken: stream ended early ${e.javaClass.simpleName}")
        }
        if (attempt == null) {
            // No reply came: the words are gone, so nothing is played over them either.
            finish(input)
            return ProxyClient.Attempt.Done(BrainReply.Failed(PlainError.line(PlainError.Kind.OUR_SIDE)))
        }
        // Whatever is still coming is audio: drained where nobody is waiting on it.
        Thread({ drain(input) }, "heylana-spoken").apply { isDaemon = true }.start()
        return attempt
    }

    /** One frame, or null at the end of the stream. */
    private fun next(input: DataInputStream): Frame? {
        val kind = input.read()
        if (kind < 0) return null
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME_BYTES) throw IllegalStateException("frame $length")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return Frame(kind, bytes)
    }

    /** Acts on one frame; the reply's own [ProxyClient.Attempt] when it is the reply. */
    private fun take(frame: Frame, expectsAction: Boolean, extract: (String, Boolean) -> ProxyClient.Attempt): ProxyClient.Attempt? {
        when (frame.kind) {
            SpokenAnswer.FRAME_AUDIO -> {
                if (audioFrames == 0) {
                    // The speaker starts now, on the answer's first sentence.
                    voice.speaking(pipe, rate)
                }
                audioFrames++
                audioBytes += frame.bytes.size
                pipe.write(frame.bytes)
            }
            SpokenAnswer.FRAME_REPLY -> return extract(String(frame.bytes, Charsets.UTF_8), expectsAction)
            SpokenAnswer.FRAME_VOICE_FAILED -> {
                val reason = String(frame.bytes, Charsets.UTF_8)
                HeylanaLog.state("spoken: nothing said reason=$reason")
                voice.notSpoken(reason)
            }
            else -> HeylanaLog.state("spoken: unknown frame kind=${frame.kind}")
        }
        return null
    }

    private fun drain(input: DataInputStream) {
        try {
            while (true) {
                val frame = next(input) ?: break
                take(frame, false) { _, _ -> ProxyClient.Attempt.Done(BrainReply.Failed("")) }
            }
        } catch (e: Exception) {
            HeylanaLog.state("spoken: audio ended early ${e.javaClass.simpleName}")
        } finally {
            finish(input)
        }
    }

    private fun finish(input: DataInputStream) {
        // Counts and bytes only: never a word of the answer.
        HeylanaLog.state("spoken: audio frames=$audioFrames bytes=$audioBytes rate=$rate")
        pipe.finish()
        runCatching { input.close() }
        runCatching { response.close() }
    }

    private class Frame(val kind: Int, val bytes: ByteArray)

    private companion object {
        const val DEFAULT_RATE = 24_000

        /** A frame is one piece of audio or one reply; anything larger is a broken stream. */
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
