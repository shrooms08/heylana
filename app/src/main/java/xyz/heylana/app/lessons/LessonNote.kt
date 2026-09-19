package xyz.heylana.app.lessons

import xyz.heylana.app.brain.Source
import xyz.heylana.app.brain.Sources

/**
 * One topic of the Solana curriculum: `skills/lessons/<id>.md`, hand-written and checked
 * against the docs (lines that could not be checked say "unverified"). The body is the only
 * extra context a lesson turn carries, and it stays under [MAX_TOKENS].
 */
data class LessonNote(
    val id: String,
    val title: String,
    /** What the Memory line says: "knows PDAs, 2026-09-18". */
    val short: String,
    val track: String,
    val aliases: List<String>,
    /** How many chunks the lesson is taught in, 4 to 6. */
    val chunks: Int,
    /** The one line said at the end. */
    val recap: String,
    val body: String,
    /** The topic's doc page, shown as a chip under every turn of the lesson. */
    val link: Source? = null
) {
    val tokens: Int get() = (body.length + 3) / 4

    /**
     * The body cut into [chunks] slices, in order, each a run of whole lines (a bullet
     * list stays with its heading where it can), so chunk n is taught from slice n.
     * Every non-blank line is in exactly one slice.
     */
    fun slices(): List<String> {
        // Too few blocks for the chunks wanted: line by line instead.
        val blocks = blocks().takeIf { it.size >= chunks } ?: lines()
        val n = chunks.coerceAtMost(blocks.size).coerceAtLeast(1)
        val total = blocks.sumOf { it.length }
        val out = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        var used = 0
        blocks.forEachIndexed { i, block ->
            val remainingBlocks = blocks.size - i
            val remainingSlices = n - out.size
            // Close this slice when it has its share, or when every later block is needed for its own slice.
            val share = total.toDouble() * (out.size + 1) / n
            if (current.isNotEmpty() && remainingSlices > 1 && (used >= share || remainingBlocks < remainingSlices)) {
                out += current
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(block)
            used += block.length
        }
        if (current.isNotEmpty()) out += current
        return out.map { it.toString() }
    }

    private fun lines(): List<String> = body.lines().map { it.trimEnd() }.filter { it.isNotBlank() }

    /** Lines, with each short bullet list kept together with the line that introduces it. */
    private fun blocks(): List<String> {
        val out = mutableListOf<String>()
        for (line in lines()) {
            val bullet = line.trimStart().startsWith("- ")
            val last = out.lastOrNull()
            if (bullet && last != null && last.length + line.length < BLOCK_CHARS) {
                out[out.lastIndex] = "$last\n$line"
            } else out += line
        }
        return out
    }

    companion object {
        const val MAX_TOKENS = 450
        const val MIN_CHUNKS = 4
        const val MAX_CHUNKS = 6
        const val BUILD = "build"
        const val INFRASTRUCTURE = "infrastructure"

        /** A heading and its bullets stay one block up to about this size. */
        private const val BLOCK_CHARS = 420

        sealed interface Parsed {
            data class Ok(val note: LessonNote) : Parsed
            data class Bad(val reason: String) : Parsed
        }

        fun parse(text: String): Parsed {
            val normalised = text.replace("\r\n", "\n")
            if (!normalised.startsWith("---\n")) return Parsed.Bad("no_front_matter")
            val end = normalised.indexOf("\n---", 4)
            if (end < 0) return Parsed.Bad("no_front_matter")
            val fields = normalised.substring(4, end).lines().mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }.toMap()
            val body = normalised.substring(end + 4).trim()
            val id = fields["id"].orEmpty()
            val title = fields["title"].orEmpty()
            val track = fields["track"].orEmpty()
            val chunks = fields["chunks"]?.toIntOrNull() ?: 0
            val recap = fields["recap"].orEmpty()
            return when {
                !Regex("[a-z0-9-]{2,40}").matches(id) -> Parsed.Bad("bad_id")
                title.isEmpty() -> Parsed.Bad("no_title")
                track != BUILD && track != INFRASTRUCTURE -> Parsed.Bad("bad_track")
                chunks !in MIN_CHUNKS..MAX_CHUNKS -> Parsed.Bad("bad_chunks")
                recap.isEmpty() -> Parsed.Bad("no_recap")
                body.isEmpty() -> Parsed.Bad("no_body")
                (body.length + 3) / 4 >= MAX_TOKENS -> Parsed.Bad("too_long")
                else -> Parsed.Ok(
                    LessonNote(
                        id = id,
                        title = title,
                        short = fields["short"]?.takeIf { it.isNotEmpty() } ?: title,
                        track = track,
                        aliases = fields["aliases"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                        chunks = chunks,
                        recap = recap,
                        body = body,
                        link = fields["link"]?.takeIf { it.startsWith("https://") }?.let { url ->
                            Source(Sources.fit(fields["link_title"]?.takeIf { it.isNotEmpty() } ?: title), url)
                        }
                    )
                )
            }
        }
    }
}
