package xyz.heylana.app.voice

import java.io.File
import java.security.MessageDigest

/**
 * The audio of a line Heylana says the same way every time.
 *
 * An answer is different every time, so it is always made fresh. A **warning** is not: "No
 * real Solana app asks for your recovery phrase" is the same eleven words on every phone,
 * every time. Asking the voice to make them again costs the one thing a warning cannot
 * spare — on the Seeker, 816ms between the request and the first byte, while the user's
 * thumb is already moving towards Approve.
 *
 * So the first time a fixed line is said, its audio is kept; after that it plays from the
 * phone with no network at all. The key is the voice and the words, so switching voice
 * makes new audio rather than playing the old voice's.
 *
 * It holds raw 16-bit 24 kHz mono, the same as the stream it came from, in the app's own
 * private files. Nothing here is ever anything the user said or anything from their
 * screen: a fixed line is one of a dozen sentences written into Heylana. Nothing here
 * touches Android either, so all of it is tested on the JVM.
 */
class SpokenCache(private val dir: File) {

    /** Where this line's audio lives, whether or not it is there yet. */
    fun fileFor(voice: String, text: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$voice|$text".toByteArray())
        val name = digest.take(10).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.pcm")
    }

    /** The audio already kept for this line, or null. */
    fun ready(voice: String, text: String): File? =
        fileFor(voice, text).takeIf { it.isFile && it.length() >= MIN_BYTES }

    /** Somewhere to write this line's audio while it plays for the first time. */
    fun writingTo(voice: String, text: String): File = File(fileFor(voice, text).path + ".part")

    /**
     * The audio played through to its end: keep it. Anything short is a stream that was cut
     * off, and a cut-off warning is worse than a slow one, so it is thrown away instead.
     */
    fun keep(voice: String, text: String, written: Long): Boolean {
        val part = writingTo(voice, text)
        val wanted = fileFor(voice, text)
        if (written < MIN_BYTES || !part.isFile) {
            part.delete()
            return false
        }
        if (!part.renameTo(wanted)) {
            part.delete()
            return false
        }
        trim()
        return true
    }

    /** The oldest go once there are more than a dozen: a dozen is every line Heylana has. */
    private fun trim() {
        val files = dir.listFiles { file -> file.name.endsWith(".pcm") } ?: return
        if (files.size <= MOST) return
        files.sortedBy { it.lastModified() }.take(files.size - MOST).forEach { it.delete() }
    }

    /** Everything kept, gone: for a voice change, or a switch the user flipped. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        /** Under a tenth of a second of audio is not a sentence. */
        const val MIN_BYTES = 4_800L
        const val MOST = 12
    }
}
