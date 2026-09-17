package xyz.heylana.app.skills

/**
 * How many skills a plan may have active, and which ones those are.
 *
 * The user switches skills on and off; the plan decides how many of the switched-on
 * ones count. The first [cap] switched-on skills, in list order (built-ins first,
 * then installed ones in the order they arrived), are active and can load. Any
 * others stay switched on but greyed until a place frees up, and a skill that is
 * off cannot be switched on while every place is taken.
 */
object SkillCap {

    const val FREE = 3
    const val PRO = 10

    enum class State {
        /** On, and within the plan: loads when its app is in front. */
        ACTIVE,

        /** On, but past the plan's cap: greyed, never loads. */
        OVER_CAP,

        /** Off, with room to switch it on. */
        OFF,

        /** Off, and every place is taken: greyed. */
        OFF_FULL
    }

    data class Row(val skill: Skill, val state: State) {
        val greyed: Boolean get() = state == State.OVER_CAP || state == State.OFF_FULL
        val switchedOn: Boolean get() = state == State.ACTIVE || state == State.OVER_CAP
    }

    /**
     * The cap to use: the plan's, as the worker last said it, or Free's when it has
     * never been heard. "Simulate Free plan" (debug builds) forces Free's.
     */
    fun capFor(knownCap: Int?, simulateFree: Boolean): Int =
        if (simulateFree) FREE else (knownCap?.takeIf { it > 0 } ?: FREE)

    fun arrange(skills: List<Skill>, switchedOn: Set<String>, cap: Int): List<Row> {
        var active = 0
        val rows = skills.map { skill ->
            val on = skill.id in switchedOn
            val state = when {
                on && active < cap -> State.ACTIVE.also { active++ }
                on -> State.OVER_CAP
                else -> State.OFF
            }
            Row(skill, state)
        }
        return if (active < cap) rows else rows.map { if (it.state == State.OFF) it.copy(state = State.OFF_FULL) else it }
    }

    fun active(skills: List<Skill>, switchedOn: Set<String>, cap: Int): List<Skill> =
        arrange(skills, switchedOn, cap).filter { it.state == State.ACTIVE }.map { it.skill }

    /** "3 of 3 active", "5 of 10 active". */
    fun headline(rows: List<Row>, cap: Int): String = "${rows.count { it.state == State.ACTIVE }} of $cap active"
}

/** Picks the one skill a request may carry. Never more than one. */
object SkillLoader {

    /**
     * The active skill for the app in front. When two are for the same app, the one
     * whose trigger words are in the question wins; next, a skill for any app whose
     * trigger words are ("what is this 402 payment" in a browser); otherwise the app's
     * main skill (the one with no triggers); otherwise the first.
     */
    fun pick(packageName: String?, question: String, active: List<Skill>): Skill? {
        val asked = question.lowercase()
        fun triggered(skill: Skill) = skill.triggers.any { word(it).containsMatchIn(asked) }
        val forApp = if (packageName.isNullOrEmpty()) emptyList() else active.filter { it.packageName == packageName }
        if (forApp.size > 1) forApp.firstOrNull(::triggered)?.let { return it }
        active.firstOrNull { it.forAnyApp && triggered(it) }?.let { return it }
        if (forApp.size <= 1) return forApp.firstOrNull()
        return forApp.firstOrNull { it.triggers.isEmpty() } ?: forApp.first()
    }

    private fun word(trigger: String) = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(trigger)}(?![\\p{L}\\p{N}])")
}
