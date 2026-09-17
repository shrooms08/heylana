package xyz.heylana.app.skills

import android.content.Context
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.settings.HeylanaSettings
import java.io.File

/**
 * Where skills live on the phone.
 *
 * Built-ins ship in the APK as assets (copied from `skills/` at the top of the repo)
 * and can be switched off but not removed. Installed skills are sanitised on the way
 * in and kept as files in the app's private storage, removable. What is switched off,
 * and the plan's skill cap as the worker last said it, are kept in [HeylanaSettings].
 */
class SkillStore(context: Context, private val settings: HeylanaSettings) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, DIR)

    /** Built-ins first, in file-name order; then installed skills, oldest first. */
    fun all(): List<Skill> = cache ?: synchronized(Companion) {
        cache ?: (builtIns() + installed()).distinctBy { it.id }.also { cache = it }
    }

    fun cap(): Int = SkillCap.capFor(settings.skillsCap, BuildConfig.DEBUG && settings.simulateFreePlan)

    fun switchedOn(): Set<String> = all().map { it.id }.toSet() - settings.skillsOff

    fun rows(): List<SkillCap.Row> = SkillCap.arrange(all(), switchedOn(), cap())

    fun setOn(id: String, on: Boolean) {
        settings.skillsOff = if (on) settings.skillsOff - id else settings.skillsOff + id
    }

    /** The one skill this request may carry, or null. */
    fun pick(packageName: String?, question: String): Skill? =
        SkillLoader.pick(packageName, question, SkillCap.active(all(), switchedOn(), cap()))

    sealed interface Installed {
        data class Ok(val skill: Skill) : Installed
        data class Refused(val reason: String) : Installed
    }

    /** Sanitises and stores a downloaded skill. [expectedId] is what the index called it. */
    fun install(text: String, expectedId: String): Installed {
        if (text.toByteArray().size > SkillFile.MAX_FILE_BYTES) return Installed.Refused("too_big")
        val parsed = SkillFile.parse(text, builtIn = false)
        if (parsed is SkillFile.Parsed.Bad) {
            HeylanaLog.state("skills: install refused id=$expectedId reason=${parsed.reason}")
            return Installed.Refused(parsed.reason)
        }
        val ok = parsed as SkillFile.Parsed.Ok
        val skill = ok.skill
        if (skill.id != expectedId) return Installed.Refused("id_mismatch")
        if (builtIns().any { it.id == skill.id }) return Installed.Refused("built_in")
        logStripped(skill.id, ok.stripped)

        dir.mkdirs()
        File(dir, "${skill.id}.md").writeText(render(skill))
        setOn(skill.id, true)
        forget()
        HeylanaLog.state("skills: installed id=${skill.id} tokens=${skill.tokens} stripped=${ok.stripped.size}")
        return Installed.Ok(skill)
    }

    fun remove(id: String) {
        if (builtIns().any { it.id == id }) return
        File(dir, "$id.md").delete()
        settings.skillsOff = settings.skillsOff - id
        forget()
        HeylanaLog.state("skills: removed id=$id")
    }

    private fun builtIns(): List<Skill> {
        val names = app.assets.list("")?.filter { it.endsWith(".md") }?.sorted().orEmpty()
        return names.mapNotNull { name ->
            val text = app.assets.open(name).bufferedReader().use { it.readText() }
            read(text, name, builtIn = true)
        }.sortedBy { BUILT_IN_ORDER.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    private fun installed(): List<Skill> =
        dir.listFiles { file -> file.name.endsWith(".md") }.orEmpty()
            .sortedBy { it.lastModified() }
            .mapNotNull { read(it.readText(), it.name, builtIn = false) }

    private fun read(text: String, name: String, builtIn: Boolean): Skill? =
        when (val parsed = SkillFile.parse(text, builtIn)) {
            is SkillFile.Parsed.Ok -> parsed.skill.also { logStripped(it.id, parsed.stripped) }
            is SkillFile.Parsed.Bad -> null.also { HeylanaLog.state("skills: unreadable file=$name reason=${parsed.reason}") }
        }

    /** A skill's text is not the screen and not a secret: the lines taken out are logged. */
    private fun logStripped(id: String, stripped: List<String>) {
        stripped.forEach { HeylanaLog.state("skills: stripped from $id: ${it.take(LOGGED_CHARS)}") }
    }

    private fun render(skill: Skill): String = buildString {
        append("---\n")
        append("id: ").append(skill.id).append('\n')
        append("name: ").append(skill.name).append('\n')
        append("package: ").append(skill.packageName).append('\n')
        append("version: ").append(skill.version).append('\n')
        append("author: ").append(skill.author).append('\n')
        append("summary: ").append(skill.summary).append('\n')
        append("privacy: ").append(skill.privacy).append('\n')
        if (skill.triggers.isNotEmpty()) append("triggers: ").append(skill.triggers.joinToString(", ")).append('\n')
        append("---\n")
        append(skill.body).append('\n')
    }

    companion object {
        private const val DIR = "skills"
        private const val LOGGED_CHARS = 80

        /** How the built-ins are listed. */
        val BUILT_IN_ORDER = listOf(
            "seed-vault-wallet", "kamino", "seed-vault-signing", "dapp-store", "jupiter", "x402", "youtube", "spotify"
        )

        @Volatile
        private var cache: List<Skill>? = null

        private fun forget() {
            cache = null
        }
    }
}
