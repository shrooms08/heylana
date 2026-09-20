package xyz.heylana.app.voice

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Audio handed from the thread reading the worker's answer to the one playing it.
 *
 * The answer comes back as one stream carrying both the audio and the words, so the
 * reader cannot also be the player: it puts each piece of audio in here and carries on
 * reading, and the player takes them out as fast as the speaker needs them. Nothing is
 * copied twice and nothing is dropped; [finish] says there is no more.
 *
 * Reads block until there is something to read, as a socket's would, so the player's loop
 * is exactly the loop it uses for [HeylanaVoice]'s own stream.
 */
class PcmPipe : InputStream() {

    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = EMPTY
    private var at = 0
    @Volatile private var ended = false

    /** More audio, in the order it was made. */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty() || ended) return
        chunks.put(bytes)
    }

    /** There is no more: a read that empties the pipe now ends. */
    fun finish() {
        ended = true
        chunks.put(EMPTY)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (at >= current.size) {
            // The end marker is an empty array, and nothing else ever is.
            val next = if (ended && chunks.isEmpty()) return -1 else chunks.poll(WAIT_MS, TimeUnit.MILLISECONDS)
            if (next == null) {
                if (ended) return -1
                continue
            }
            if (next.isEmpty() && ended) return -1
            current = next
            at = 0
        }
        val taken = minOf(length, current.size - at)
        System.arraycopy(current, at, into, offset, taken)
        at += taken
        return taken
    }

    override fun available(): Int = current.size - at

    override fun close() {
        finish()
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val WAIT_MS = 200L
    }
}
