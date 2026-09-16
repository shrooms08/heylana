package xyz.heylana.app.skills

/**
 * One app's reference notes: what its screens hold, how its common tasks go, and
 * what to watch out for. Read by the model, never obeyed by it — see
 * [xyz.heylana.app.brain.HeylanaPrompt.SKILL_RULE].
 */
data class Skill(
    val id: String,
    val name: String,
    /** The one app this skill is for. It loads only while that app is in front. */
    val packageName: String,
    val version: String,
    val author: String,
    val summary: String,
    /** One line on what this skill does with the user's data (skills are text: nothing). */
    val privacy: String,
    /**
     * Words that pick this skill over another skill for the same app, e.g. Kamino's
     * notes live in the Wallet. Empty for an app's main skill.
     */
    val triggers: List<String>,
    /** Plain text, already sanitised, under [SkillFile.MAX_BODY_TOKENS]. */
    val body: String,
    val builtIn: Boolean
) {
    val tokens: Int get() = SkillFile.tokens(body)
}

/**
 * The skill file format: `skills/<id>.md`, a YAML-style front matter block between
 * `---` lines holding one `key: value` per line, then the body.
 *
 * ```
 * ---
 * id: jupiter
 * name: Jupiter
 * package: ag.jup.jupiter.android
 * version: 1
 * author: Heylana
 * summary: Swaps, limit orders and Jupiter Lend.
 * privacy: Reference text only; reads nothing and sends nothing.
 * ---
 * Screens
 * ...
 * ```
 *
 * Only flat string values are read, so no YAML library is needed, and nothing in
 * a skill can be anything but text.
 */
object SkillFile {

    const val MAX_BODY_TOKENS = 400

    /** Big enough for any honest skill; a download larger than this is refused unread. */
    const val MAX_FILE_BYTES = 16_000

    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val REQUIRED = listOf("id", "name", "package", "version", "author", "summary", "privacy")
    private const val MAX_LINE = 160

    sealed interface Parsed {
        data class Ok(val skill: Skill, val stripped: List<String>) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    /**
     * Roughly how many tokens the model will count: about four characters each for
     * English prose. Rounded up, so a limit checked with it is never generous.
     */
    fun tokens(text: String): Int = (text.length + 3) / 4

    /** Parses, checks and sanitises one skill file. [stripped] lists the lines removed. */
    fun parse(text: String, builtIn: Boolean): Parsed {
        val lines = text.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.isNotBlank() }
        if (start < 0 || lines[start].trim() != "---") return Parsed.Bad("no_front_matter")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: return Parsed.Bad("no_front_matter")

        val fields = HashMap<String, String>()
        for (line in lines.subList(start + 1, end)) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) return Parsed.Bad("bad_front_matter")
            val key = line.substring(0, colon).trim().lowercase()
            fields[key] = unquote(line.substring(colon + 1).trim())
        }
        REQUIRED.firstOrNull { fields[it].isNullOrBlank() }?.let { return Parsed.Bad("missing_$it") }

        val id = fields.getValue("id")
        val packageName = fields.getValue("package")
        if (!ID.matches(id)) return Parsed.Bad("bad_id")
        if (!PACKAGE.matches(packageName)) return Parsed.Bad("bad_package")
        if (REQUIRED.any { fields.getValue(it).length > MAX_LINE }) return Parsed.Bad("field_too_long")

        val clean = SkillSanitiser.clean(lines.subList(end + 1, lines.size).joinToString("\n"))
        val body = clean.text.trim()
        if (body.isEmpty()) return Parsed.Bad("empty_body")
        if (tokens(body) > MAX_BODY_TOKENS) return Parsed.Bad("body_too_long")

        val skill = Skill(
            id = id,
            name = fields.getValue("name"),
            packageName = packageName,
            version = fields.getValue("version"),
            author = fields.getValue("author"),
            summary = fields.getValue("summary"),
            privacy = fields.getValue("privacy"),
            triggers = fields["triggers"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            body = body,
            builtIn = builtIn
        )
        return Parsed.Ok(skill, clean.stripped)
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
