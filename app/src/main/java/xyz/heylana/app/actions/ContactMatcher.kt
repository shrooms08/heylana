package xyz.heylana.app.actions

/**
 * A contact with a phone number, as read from the phone's own contacts. Never leaves the phone.
 * [aliases] are other names the phone knows them by: their nicknames, and a relation from the
 * owner's own card ("brother" for the person listed there as their brother).
 */
data class Contact(val name: String, val number: String, val aliases: List<String> = emptyList())

/**
 * Which contact the user meant by the name they said: "Ada" finds "Ada Obi", "my brother" finds
 * whoever the phone knows as their brother — a contact saved as "Bro Tunde", one whose nickname
 * is "bro", or the person the owner's own card lists as their brother. The same scoring as
 * [AppMatcher] — the exact name first, then a name holding every word said — and never a guess
 * between two people who fit equally well. One person with several numbers is still one person.
 */
object ContactMatcher {

    sealed interface Match {
        data class Found(val contact: Contact) : Match
        data class Ambiguous(val first: String, val second: String) : Match
        data object None : Match
    }

    /** The ways people save and say the same relation. "my" is dropped before this, as filler. */
    private val RELATIONS: List<Set<String>> = listOf(
        setOf("brother", "bro"),
        setOf("sister", "sis"),
        setOf("mum", "mom", "mother", "mummy", "mommy", "mama", "mam"),
        setOf("dad", "father", "daddy", "papa", "pops"),
        setOf("wife", "wifey"),
        setOf("husband", "hubby"),
        setOf("partner", "spouse"),
        setOf("grandma", "granny", "grandmother", "nana"),
        setOf("grandpa", "grandfather", "granddad"),
        setOf("son"), setOf("daughter"), setOf("uncle"), setOf("aunt", "auntie", "aunty"),
        setOf("cousin"), setOf("boss", "manager"), setOf("friend", "bestie", "best friend")
    )

    /** Every way of saying [word]'s relation, or null when it is not one. */
    fun relation(word: String): Set<String>? = RELATIONS.firstOrNull { word.lowercase() in it }

    fun best(said: String, contacts: List<Contact>): Match {
        val wanted = AppMatcher.words(said)
        if (wanted.isEmpty()) return Match.None
        // "brother" also means "bro"; a name is only itself.
        val variants = wanted.singleOrNull()?.let(::relation)?.map { listOf(it) } ?: listOf(wanted)
        val scored = contacts
            .groupBy { it.name.trim() }
            .map { (name, entries) ->
                val labels = listOf(name) + entries.flatMap { it.aliases }
                val score = variants.maxOf { variant -> labels.maxOf { AppMatcher.score(variant, AppMatcher.words(it)) } }
                Triple(name, entries.first(), score)
            }
            .filter { it.third > 0 }
            .sortedByDescending { it.third }
        val top = scored.firstOrNull() ?: return Match.None
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.third == top.third) return Match.Ambiguous(top.first, runnerUp.first)
        return Match.Found(top.second.copy(name = top.first))
    }
}
